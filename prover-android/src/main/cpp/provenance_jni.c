// JNI layer over the steam-provenance C ABI (provenance_client.h; contract: client-cffi/README.md of the pinned
// package's commit). NativeProverBridge.kt declares every function here.
//
// Streams. The five PcStream callbacks are nonblocking and may run on a native thread, so they never enter the
// JVM: each stream's userdata is a Queue with a bounded read ring (connection -> prover) and a bounded write ring
// (prover -> connection) under one mutex. The callbacks only copy between those rings and the borrowed buffers
// (which expire on return) and park a pending token per operation (read, write, flush, close). A Kotlin worker
// (NativeProverStream) moves bytes between the rings and the real connection through the queue_* functions,
// which call pc_wake for the parked token, outside the mutex, whenever the state that operation waits for
// changes. The worker blocks on the queue's condition variable, never on the prover.
//
// Errors. A failing pc_* call throws NativeProverException(status, diagnostic bytes) and frees the diagnostic;
// every owned PcBuffer is copied into a Java array and freed with pc_buffer_free before the call returns.

#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "provenance_client.h"

#define EXPORT(type, name) JNIEXPORT type JNICALL Java_com_dmarket_p2p_tracker_adapter_notary_NativeProverBridge_##name

static JavaVM *g_vm;
static jclass g_exception;
static jmethodID g_exception_init;
static jclass g_response;
static jmethodID g_response_init;
static jclass g_bytes;
static jclass g_progress;
static jmethodID g_progress_on;

// ---- Queue ------------------------------------------------------------------------------------------------

typedef struct {
    uint8_t *data;
    size_t cap, head, len;
} Ring;

typedef struct {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    Ring rx;          // connection -> prover
    Ring tx;          // prover -> connection
    size_t inflight;  // taken from tx by the worker, not yet acknowledged as written
    bool eof, failed, close_requested, closed, released;
    uint64_t t_read, t_write, t_flush, t_close;
    int refs;  // the worker's, and the prover stream's until release
} Queue;

static size_t ring_put(Ring *r, const uint8_t *src, size_t len) {
    size_t n = r->cap - r->len < len ? r->cap - r->len : len;
    for (size_t done = 0; done < n;) {
        size_t at = (r->head + r->len) % r->cap;
        size_t run = r->cap - at < n - done ? r->cap - at : n - done;
        memcpy(r->data + at, src + done, run);
        r->len += run;
        done += run;
    }
    return n;
}

static size_t ring_take(Ring *r, uint8_t *dst, size_t len) {
    size_t n = r->len < len ? r->len : len;
    for (size_t done = 0; done < n;) {
        size_t run = r->cap - r->head < n - done ? r->cap - r->head : n - done;
        memcpy(dst + done, r->data + r->head, run);
        r->head = (r->head + run) % r->cap;
        r->len -= run;
        done += run;
    }
    return n;
}

static uint64_t take_token(uint64_t *slot) {
    uint64_t token = *slot;
    *slot = 0;
    return token;
}

static void queue_unref(Queue *q) {
    pthread_mutex_lock(&q->mu);
    int refs = --q->refs;
    pthread_mutex_unlock(&q->mu);
    if (refs) return;
    pthread_cond_destroy(&q->cv);
    pthread_mutex_destroy(&q->mu);
    free(q->rx.data);
    free(q->tx.data);
    free(q);
}

// ---- PcStream callbacks: no JVM, no blocking ---------------------------------------------------------------

static int32_t on_read(void *userdata, uint64_t token, uint8_t *buf, size_t len, size_t *count) {
    Queue *q = userdata;
    uint64_t wake = 0;
    int32_t status;
    *count = 0;
    pthread_mutex_lock(&q->mu);
    if (q->rx.len) {
        *count = ring_take(&q->rx, buf, len);
        wake = take_token(&q->t_read);  // a retry that succeeds still delivers the earlier pending wake
        pthread_cond_broadcast(&q->cv);  // space for the worker
        status = PC_IO_OK;
    } else if (q->failed) {
        status = PC_IO_ERROR;
    } else if (q->eof) {
        wake = take_token(&q->t_read);
        status = PC_IO_OK;  // count 0: EOF
    } else {
        q->t_read = token;
        status = PC_IO_PENDING;
    }
    pthread_mutex_unlock(&q->mu);
    if (wake) pc_wake(wake);
    return status;
}

static int32_t on_write(void *userdata, uint64_t token, const uint8_t *buf, size_t len, size_t *count) {
    Queue *q = userdata;
    uint64_t wake = 0;
    int32_t status;
    *count = 0;
    pthread_mutex_lock(&q->mu);
    if (q->failed || q->close_requested) {
        status = PC_IO_ERROR;
    } else if (q->tx.len == q->tx.cap) {
        q->t_write = token;
        status = PC_IO_PENDING;
    } else {
        *count = ring_put(&q->tx, buf, len);
        wake = take_token(&q->t_write);
        pthread_cond_broadcast(&q->cv);  // data for the worker
        status = PC_IO_OK;
    }
    pthread_mutex_unlock(&q->mu);
    if (wake) pc_wake(wake);
    return status;
}

static int32_t on_flush(void *userdata, uint64_t token) {
    Queue *q = userdata;
    uint64_t wake = 0;
    int32_t status;
    pthread_mutex_lock(&q->mu);
    if (q->failed) {
        status = PC_IO_ERROR;
    } else if (q->tx.len == 0 && q->inflight == 0) {  // drained to the connection
        wake = take_token(&q->t_flush);
        status = PC_IO_OK;
    } else {
        q->t_flush = token;
        status = PC_IO_PENDING;
    }
    pthread_mutex_unlock(&q->mu);
    if (wake) pc_wake(wake);
    return status;
}

static int32_t on_close(void *userdata, uint64_t token) {
    Queue *q = userdata;
    uint64_t wake = 0;
    int32_t status;
    pthread_mutex_lock(&q->mu);
    if (q->failed) {
        status = PC_IO_ERROR;
    } else if (q->closed) {
        wake = take_token(&q->t_close);
        status = PC_IO_OK;
    } else {
        q->close_requested = true;
        q->t_close = token;
        pthread_cond_broadcast(&q->cv);  // the worker shuts the write side once tx is drained
        status = PC_IO_PENDING;
    }
    pthread_mutex_unlock(&q->mu);
    if (wake) pc_wake(wake);
    return status;
}

static void on_release(void *userdata) {
    Queue *q = userdata;
    pthread_mutex_lock(&q->mu);
    q->released = true;
    pthread_cond_broadcast(&q->cv);  // stops the worker
    pthread_mutex_unlock(&q->mu);
    queue_unref(q);
}

// ---- Helpers -------------------------------------------------------------------------------------------------

// Throws NativeProverException(status, diagnostic) and frees the diagnostic. With an exception already pending
// (an allocation failure on the way here) that one stays.
static void throw_status(JNIEnv *env, int32_t status, PcBuffer *error) {
    jbyteArray diagnostic = NULL;
    if (!(*env)->ExceptionCheck(env) && error && error->len && error->len <= INT32_MAX) {
        diagnostic = (*env)->NewByteArray(env, (jsize)error->len);
        if (diagnostic) (*env)->SetByteArrayRegion(env, diagnostic, 0, (jsize)error->len, (const jbyte *)error->data);
    }
    pc_buffer_free(error);
    if ((*env)->ExceptionCheck(env)) return;
    jobject e = (*env)->NewObject(env, g_exception, g_exception_init, (jint)status, diagnostic);
    if (e) (*env)->Throw(env, e);
}

// A failure of this layer before or after the C ABI, reported as PC_INVALID_ARGUMENT with `what` as diagnostic.
static void throw_argument(JNIEnv *env, const char *what) {
    if ((*env)->ExceptionCheck(env)) return;
    jsize len = (jsize)strlen(what);
    jbyteArray diagnostic = (*env)->NewByteArray(env, len);
    if (!diagnostic) return;
    (*env)->SetByteArrayRegion(env, diagnostic, 0, len, (const jbyte *)what);
    jobject e = (*env)->NewObject(env, g_exception, g_exception_init, (jint)PC_INVALID_ARGUMENT, diagnostic);
    if (e) (*env)->Throw(env, e);
}

// Copies a Java array out of the heap for the duration of a call. NULL or empty -> an empty span.
static bool copy_in(JNIEnv *env, jbyteArray array, uint8_t **out, PcBytes *span) {
    *out = NULL;
    span->data = NULL;
    span->len = 0;
    if (!array) return true;
    jsize len = (*env)->GetArrayLength(env, array);
    if (!len) return true;
    *out = malloc((size_t)len);
    if (!*out) {
        throw_argument(env, "out of memory");
        return false;
    }
    (*env)->GetByteArrayRegion(env, array, 0, len, (jbyte *)*out);
    span->data = *out;
    span->len = (size_t)len;
    return true;
}

static jbyteArray bytes_out(JNIEnv *env, const uint8_t *data, size_t len) {
    if (len > INT32_MAX) {
        throw_argument(env, "too large for a Java array");
        return NULL;
    }
    jbyteArray array = (*env)->NewByteArray(env, (jsize)len);
    if (array && len) (*env)->SetByteArrayRegion(env, array, 0, (jsize)len, (const jbyte *)data);
    return array;
}

// Copies an owned buffer into a Java array and frees it, also on failure.
static jbyteArray buffer_out(JNIEnv *env, PcBuffer *buffer) {
    jbyteArray array = bytes_out(env, buffer->data, buffer->len);
    pc_buffer_free(buffer);
    return array;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    g_vm = vm;
#define GLOBAL_CLASS(slot, name)                          \
    do {                                                  \
        jclass local = (*env)->FindClass(env, name);      \
        if (!local) return JNI_ERR;                       \
        slot = (*env)->NewGlobalRef(env, local);          \
        (*env)->DeleteLocalRef(env, local);               \
        if (!slot) return JNI_ERR;                        \
    } while (0)
    GLOBAL_CLASS(g_exception, "com/dmarket/p2p/tracker/adapter/notary/NativeProverException");
    GLOBAL_CLASS(g_response, "com/dmarket/p2p/tracker/adapter/notary/NativeResponse");
    GLOBAL_CLASS(g_progress, "com/dmarket/p2p/tracker/adapter/notary/NativeProverProgress");
    GLOBAL_CLASS(g_bytes, "[B");
#undef GLOBAL_CLASS
    g_exception_init = (*env)->GetMethodID(env, g_exception, "<init>", "(I[B)V");
    g_response_init = (*env)->GetMethodID(env, g_response, "<init>", "(IJ[B)V");
    g_progress_on = (*env)->GetMethodID(env, g_progress, "onProgress", "(IILjava/lang/String;F)V");
    if (!g_exception_init || !g_response_init || !g_progress_on) return JNI_ERR;
    return JNI_VERSION_1_6;
}

// ---- Queue: the worker's side ----------------------------------------------------------------------------

EXPORT(jlong, queueNew)(JNIEnv *env, jclass cls, jint readCapacity, jint writeCapacity) {
    (void)cls;
    Queue *q = calloc(1, sizeof(*q));
    if (q && readCapacity > 0 && writeCapacity > 0) {
        q->rx.cap = (size_t)readCapacity;
        q->tx.cap = (size_t)writeCapacity;
        q->rx.data = malloc(q->rx.cap);
        q->tx.data = malloc(q->tx.cap);
    }
    if (!q || !q->rx.data || !q->tx.data) {
        if (q) {
            free(q->rx.data);
            free(q->tx.data);
            free(q);
        }
        throw_argument(env, "queue");
        return 0;
    }
    pthread_mutex_init(&q->mu, NULL);
    pthread_cond_init(&q->cv, NULL);
    q->refs = 1;
    return (jlong)(intptr_t)q;
}

EXPORT(jlong, streamNew)(JNIEnv *env, jclass cls, jlong queue) {
    (void)cls;
    Queue *q = (Queue *)(intptr_t)queue;
    if (!q) {
        throw_argument(env, "queue");
        return 0;
    }
    pthread_mutex_lock(&q->mu);
    q->refs++;  // the prover stream's, dropped by on_release
    pthread_mutex_unlock(&q->mu);
    PcStream callbacks = {PC_ABI_VERSION, q, on_read, on_write, on_flush, on_close, on_release};
    PcStreamHandle *handle = NULL;
    PcBuffer error = {0};
    int32_t status = pc_stream_new(&callbacks, &handle, &error);
    if (status != PC_OK) {
        queue_unref(q);  // userdata stays ours on failure
        throw_status(env, status, &error);
        return 0;
    }
    return (jlong)(intptr_t)handle;
}

EXPORT(void, queueFree)(JNIEnv *env, jclass cls, jlong queue) {
    (void)env;
    (void)cls;
    if (queue) queue_unref((Queue *)(intptr_t)queue);
}

// Blocks until all of bytes[offset, offset+length) is queued for the prover. False once the stream is released
// or failed: the worker stops reading.
EXPORT(jboolean, queueOfferRead)(JNIEnv *env, jclass cls, jlong queue, jbyteArray bytes, jint offset, jint length) {
    (void)cls;
    Queue *q = (Queue *)(intptr_t)queue;
    uint8_t chunk[4096];
    jint done = 0;
    while (done < length) {
        jint n = length - done < (jint)sizeof(chunk) ? length - done : (jint)sizeof(chunk);
        (*env)->GetByteArrayRegion(env, bytes, offset + done, n, (jbyte *)chunk);
        if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
        for (jint put = 0; put < n;) {
            pthread_mutex_lock(&q->mu);
            while (q->rx.len == q->rx.cap && !q->released && !q->failed) pthread_cond_wait(&q->cv, &q->mu);
            if (q->released || q->failed) {
                pthread_mutex_unlock(&q->mu);
                return JNI_FALSE;
            }
            put += (jint)ring_put(&q->rx, chunk + put, (size_t)(n - put));
            uint64_t wake = take_token(&q->t_read);
            pthread_mutex_unlock(&q->mu);
            if (wake) pc_wake(wake);
        }
        done += n;
    }
    return JNI_TRUE;
}

static void queue_set(Queue *q, bool *flag, uint64_t *t1, uint64_t *t2, uint64_t *t3, uint64_t *t4) {
    uint64_t wakes[4] = {0};
    pthread_mutex_lock(&q->mu);
    *flag = true;
    uint64_t *slots[4] = {t1, t2, t3, t4};
    for (int i = 0; i < 4; i++)
        if (slots[i]) wakes[i] = take_token(slots[i]);
    pthread_cond_broadcast(&q->cv);
    pthread_mutex_unlock(&q->mu);
    for (int i = 0; i < 4; i++)
        if (wakes[i]) pc_wake(wakes[i]);
}

// The connection's read side ended: the prover reads EOF once the queued bytes are consumed.
EXPORT(void, queueEndRead)(JNIEnv *env, jclass cls, jlong queue) {
    (void)env;
    (void)cls;
    Queue *q = (Queue *)(intptr_t)queue;
    queue_set(q, &q->eof, &q->t_read, NULL, NULL, NULL);
}

// The connection failed: every operation, pending or later, returns a transport error.
EXPORT(void, queueFail)(JNIEnv *env, jclass cls, jlong queue) {
    (void)env;
    (void)cls;
    Queue *q = (Queue *)(intptr_t)queue;
    queue_set(q, &q->failed, &q->t_read, &q->t_write, &q->t_flush, &q->t_close);
}

// Blocks until there is work for the connection. > 0: that many bytes copied into dst, to be written and then
// acknowledged with queueAckWrite. -1: everything is written and the prover asked to close; shut the write side
// and call queueCloseDone. -2: the stream was released or failed; stop.
EXPORT(jint, queueTakeWrite)(JNIEnv *env, jclass cls, jlong queue, jbyteArray dst) {
    (void)cls;
    Queue *q = (Queue *)(intptr_t)queue;
    jsize cap = (*env)->GetArrayLength(env, dst);
    uint8_t chunk[4096];
    size_t want = (size_t)cap < sizeof(chunk) ? (size_t)cap : sizeof(chunk);
    pthread_mutex_lock(&q->mu);
    while (!q->released && !q->failed && q->tx.len == 0 && !(q->close_requested && !q->closed))
        pthread_cond_wait(&q->cv, &q->mu);
    if (q->released || q->failed) {
        pthread_mutex_unlock(&q->mu);
        return -2;
    }
    if (q->tx.len == 0) {
        pthread_mutex_unlock(&q->mu);
        return -1;
    }
    size_t n = ring_take(&q->tx, chunk, want);
    q->inflight += n;
    uint64_t wake = take_token(&q->t_write);
    pthread_mutex_unlock(&q->mu);
    if (wake) pc_wake(wake);
    (*env)->SetByteArrayRegion(env, dst, 0, (jsize)n, (const jbyte *)chunk);
    return (jint)n;
}

// count bytes from queueTakeWrite reached the connection. Completes a pending flush once nothing is left.
EXPORT(void, queueAckWrite)(JNIEnv *env, jclass cls, jlong queue, jint count) {
    (void)env;
    (void)cls;
    Queue *q = (Queue *)(intptr_t)queue;
    uint64_t wake = 0;
    pthread_mutex_lock(&q->mu);
    q->inflight -= (size_t)count;
    if (q->tx.len == 0 && q->inflight == 0) {
        wake = take_token(&q->t_flush);
        pthread_cond_broadcast(&q->cv);
    }
    pthread_mutex_unlock(&q->mu);
    if (wake) pc_wake(wake);
}

// The write side is shut: completes the prover's pending close.
EXPORT(void, queueCloseDone)(JNIEnv *env, jclass cls, jlong queue) {
    (void)env;
    (void)cls;
    Queue *q = (Queue *)(intptr_t)queue;
    queue_set(q, &q->closed, &q->t_close, NULL, NULL, NULL);
}

EXPORT(jboolean, queueReleased)(JNIEnv *env, jclass cls, jlong queue) {
    (void)env;
    (void)cls;
    Queue *q = (Queue *)(intptr_t)queue;
    pthread_mutex_lock(&q->mu);
    bool released = q->released;
    pthread_mutex_unlock(&q->mu);
    return released ? JNI_TRUE : JNI_FALSE;
}

// ---- Context, cancel, wake, buffers --------------------------------------------------------------------

EXPORT(jint, abiVersion)(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jint)pc_abi_version();
}

EXPORT(jlong, contextNew)(JNIEnv *env, jclass cls) {
    (void)cls;
    PcContext *context = NULL;
    PcBuffer error = {0};
    int32_t status = pc_context_new(&context, &error);
    if (status != PC_OK) throw_status(env, status, &error);
    return (jlong)(intptr_t)context;
}

EXPORT(void, contextFree)(JNIEnv *env, jclass cls, jlong context) {
    (void)env;
    (void)cls;
    pc_context_free((PcContext *)(intptr_t)context);
}

EXPORT(jlong, cancelNew)(JNIEnv *env, jclass cls) {
    (void)cls;
    PcCancel *cancel = NULL;
    PcBuffer error = {0};
    int32_t status = pc_cancel_new(&cancel, &error);
    if (status != PC_OK) throw_status(env, status, &error);
    return (jlong)(intptr_t)cancel;
}

EXPORT(void, cancel)(JNIEnv *env, jclass cls, jlong cancel) {
    (void)env;
    (void)cls;
    if (cancel) pc_cancel((const PcCancel *)(intptr_t)cancel);
}

EXPORT(void, cancelFree)(JNIEnv *env, jclass cls, jlong cancel) {
    (void)env;
    (void)cls;
    pc_cancel_free((PcCancel *)(intptr_t)cancel);
}

EXPORT(void, wake)(JNIEnv *env, jclass cls, jlong token) {
    (void)env;
    (void)cls;
    pc_wake((uint64_t)token);
}

EXPORT(void, streamFree)(JNIEnv *env, jclass cls, jlong stream) {
    (void)env;
    (void)cls;
    pc_stream_free((PcStreamHandle *)(intptr_t)stream);
}

EXPORT(void, materialFree)(JNIEnv *env, jclass cls, jlong material) {
    (void)env;
    (void)cls;
    pc_material_free((PcMaterial *)(intptr_t)material);
}

// ---- Issuance ------------------------------------------------------------------------------------------

// Progress may arrive on a thread the JVM does not know; it is attached for the call. The listener's exception
// is cleared: nothing may unwind into the prover.
static void on_progress(void *userdata, uint32_t mode, uint32_t index, PcBytes name, float fraction) {
    jobject listener = userdata;
    JNIEnv *env;
    bool attached = false;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return;
        attached = true;
    }
    char stage[64];
    size_t len = name.len < sizeof(stage) - 1 ? name.len : sizeof(stage) - 1;
    for (size_t i = 0; i < len; i++) stage[i] = name.data[i] < 0x80 && name.data[i] ? (char)name.data[i] : '?';
    stage[len] = 0;
    jstring text = (*env)->NewStringUTF(env, stage);
    if (text) {
        (*env)->CallVoidMethod(env, listener, g_progress_on, (jint)mode, (jint)index, text, (jfloat)fraction);
        (*env)->DeleteLocalRef(env, text);
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}

static jlong issue(JNIEnv *env, bool mpc, jlong context, jbyteArray config, jbyteArray body, jlong notary,
                   jlong target, jlong deadlineMs, jlong cancel, jobject progress) {
    uint8_t *config_copy, *body_copy = NULL;
    PcBytes config_span, body_span;
    if (!copy_in(env, config, &config_copy, &config_span)) return 0;
    if (!copy_in(env, body, &body_copy, &body_span)) {
        free(config_copy);
        return 0;
    }
    jobject listener = progress ? (*env)->NewGlobalRef(env, progress) : NULL;
    PcMaterial *material = NULL;
    PcBuffer error = {0};
    int32_t status =
        mpc ? pc_issue_mpc((PcContext *)(intptr_t)context, config_span, body_span, (PcStreamHandle *)(intptr_t)notary,
                           (PcStreamHandle *)(intptr_t)target, (uint64_t)deadlineMs, (const PcCancel *)(intptr_t)cancel,
                           listener ? on_progress : NULL, listener, &material, &error)
            : pc_issue_proxy((PcContext *)(intptr_t)context, config_span, body_span, (PcStreamHandle *)(intptr_t)notary,
                             (uint64_t)deadlineMs, (const PcCancel *)(intptr_t)cancel, listener ? on_progress : NULL,
                             listener, &material, &error);
    if (listener) (*env)->DeleteGlobalRef(env, listener);
    free(config_copy);
    free(body_copy);
    if (status != PC_OK) {
        throw_status(env, status, &error);
        return 0;
    }
    return (jlong)(intptr_t)material;
}

EXPORT(jlong, nativeIssueMpc)(JNIEnv *env, jclass cls, jlong context, jbyteArray config, jbyteArray body,
                              jlong notary, jlong target, jlong deadlineMs, jlong cancel, jobject progress) {
    (void)cls;
    return issue(env, true, context, config, body, notary, target, deadlineMs, cancel, progress);
}

EXPORT(jlong, nativeIssueProxy)(JNIEnv *env, jclass cls, jlong context, jbyteArray config, jbyteArray body,
                                jlong notary, jlong deadlineMs, jlong cancel, jobject progress) {
    (void)cls;
    return issue(env, false, context, config, body, notary, 0, deadlineMs, cancel, progress);
}

// ---- Material ------------------------------------------------------------------------------------------

EXPORT(jobject, response)(JNIEnv *env, jclass cls, jlong material) {
    (void)cls;
    PcResponse response;
    PcBuffer error = {0};
    int32_t status = pc_response((const PcMaterial *)(intptr_t)material, &response, &error);
    if (status != PC_OK) {
        throw_status(env, status, &error);
        return NULL;
    }
    jbyteArray body = bytes_out(env, response.body.data, response.body.len);
    if (!body) return NULL;
    return (*env)->NewObject(env, g_response, g_response_init, (jint)response.status, (jlong)response.header_count, body);
}

// [name, value] of the header at index.
EXPORT(jobjectArray, responseHeader)(JNIEnv *env, jclass cls, jlong material, jlong index) {
    (void)cls;
    PcBytes name, value;
    PcBuffer error = {0};
    if (index < 0) {
        throw_argument(env, "negative header index");
        return NULL;
    }
    int32_t status = pc_response_header((const PcMaterial *)(intptr_t)material, (size_t)index, &name, &value, &error);
    if (status != PC_OK) {
        throw_status(env, status, &error);
        return NULL;
    }
    jobjectArray pair = (*env)->NewObjectArray(env, 2, g_bytes, NULL);
    jbyteArray n = pair ? bytes_out(env, name.data, name.len) : NULL;
    jbyteArray v = n ? bytes_out(env, value.data, value.len) : NULL;
    if (!v) return NULL;
    (*env)->SetObjectArrayElement(env, pair, 0, n);
    (*env)->SetObjectArrayElement(env, pair, 1, v);
    return pair;
}

// [attestation, secrets]. The secrets hold the private transcript: the caller protects their storage.
EXPORT(jobjectArray, materialExport)(JNIEnv *env, jclass cls, jlong material) {
    (void)cls;
    PcBuffer attestation = {0}, secrets = {0}, error = {0};
    int32_t status = pc_material_export((const PcMaterial *)(intptr_t)material, &attestation, &secrets, &error);
    if (status != PC_OK) {
        throw_status(env, status, &error);
        return NULL;
    }
    jbyteArray a = buffer_out(env, &attestation);
    jbyteArray s = a ? buffer_out(env, &secrets) : NULL;
    pc_buffer_free(&secrets);  // no-op unless the first copy failed
    jobjectArray pair = s ? (*env)->NewObjectArray(env, 2, g_bytes, NULL) : NULL;
    if (!pair) return NULL;
    (*env)->SetObjectArrayElement(env, pair, 0, a);
    (*env)->SetObjectArrayElement(env, pair, 1, s);
    return pair;
}

EXPORT(jlong, materialImport)(JNIEnv *env, jclass cls, jbyteArray attestation, jbyteArray secrets) {
    (void)cls;
    uint8_t *a_copy, *s_copy;
    PcBytes a_span, s_span;
    if (!copy_in(env, attestation, &a_copy, &a_span)) return 0;
    if (!copy_in(env, secrets, &s_copy, &s_span)) {
        free(a_copy);
        return 0;
    }
    PcMaterial *material = NULL;
    PcBuffer error = {0};
    int32_t status = pc_material_import(a_span, s_span, &material, &error);
    free(a_copy);
    free(s_copy);
    if (status != PC_OK) throw_status(env, status, &error);
    return (jlong)(intptr_t)material;
}

// The presentation for policy (UTF-8 JSON; null or empty = the core defaults).
EXPORT(jbyteArray, present)(JNIEnv *env, jclass cls, jlong material, jbyteArray policy) {
    (void)cls;
    uint8_t *p_copy;
    PcBytes p_span;
    if (!copy_in(env, policy, &p_copy, &p_span)) return NULL;
    PcBuffer out = {0}, error = {0};
    int32_t status = pc_present((const PcMaterial *)(intptr_t)material, p_span, &out, &error);
    free(p_copy);
    if (status != PC_OK) {
        throw_status(env, status, &error);
        return NULL;
    }
    return buffer_out(env, &out);
}
