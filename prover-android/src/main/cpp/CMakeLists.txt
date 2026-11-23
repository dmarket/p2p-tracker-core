# libprovenance_jni.so: the JNI layer over the steam-provenance C ABI. Linked dynamically against the package's
# libprovenance_client_cffi.so, which verifyProverNativeLibs has checked and staged; PROVENANCE_JNILIBS and
# PROVENANCE_INCLUDE point at its output.
cmake_minimum_required(VERSION 3.22)
project(provenance_jni C)

add_library(provenance_jni SHARED provenance_jni.c)
set_target_properties(provenance_jni PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)
target_include_directories(provenance_jni PRIVATE "${PROVENANCE_INCLUDE}")
target_compile_options(provenance_jni PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
# The package's library has no SONAME: linked by -l, DT_NEEDED is its file name rather than this build's path.
target_link_directories(provenance_jni PRIVATE "${PROVENANCE_JNILIBS}/${ANDROID_ABI}")
target_link_libraries(provenance_jni PRIVATE provenance_client_cffi)
# 16 KB pages (Android 15+) on every ABI; --no-undefined catches a symbol the C ABI does not export.
target_link_options(provenance_jni PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
