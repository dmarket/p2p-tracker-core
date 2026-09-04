package com.dmarket.p2p.tracker.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

// [credentialsInclude] is a no-op here: OkHttp manages its own cookie jar and the browser
// cross-site `credentials:"include"` case does not apply on JVM/Android.
actual fun platformHttpEngine(credentialsInclude: Boolean): HttpClientEngine = OkHttp.create()
