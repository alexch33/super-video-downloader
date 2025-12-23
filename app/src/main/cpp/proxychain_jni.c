#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>

#include "libproxychain.h"

// =========================
// JNI LIFECYCLE HOOK
// =========================

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    // This function is automatically called by Android when the library is loaded.
    // It's a good place to do any initial setup.
    return JNI_VERSION_1_6;
}

// =========================
// Helpers
// =========================

static char* jstring_to_utf8(JNIEnv* env, jstring js) {
    const char* tmp = (*env)->GetStringUTFChars(env, js, NULL);
    if (!tmp) return NULL;

    char* out = strdup(tmp);
    (*env)->ReleaseStringUTFChars(env, js, tmp);
    return out;
}

// =========================
// JNI API IMPLEMENTATIONS
// =========================

JNIEXPORT jlong JNICALL
Java_com_myAllVideoBrowser_util_proxy_1utils_proxy_1manager_ProxyChainNative_go_1init_1chain(
        JNIEnv* env,
        jclass clazz
) {
    (void)env;
    (void)clazz;
    // The Go function returns a dummy '1' now, which we pass along.
    return (jlong) go_init_chain();
}

JNIEXPORT void JNICALL
Java_com_myAllVideoBrowser_util_proxy_1utils_proxy_1manager_ProxyChainNative_go_1destroy_1chain(
        JNIEnv* env,
        jclass clazz,
        jlong ptr
) {
    (void)env;
    (void)clazz;
    if (ptr != 0) {
        go_destroy_chain(ptr);
    }
}

JNIEXPORT jint JNICALL
Java_com_myAllVideoBrowser_util_proxy_1utils_proxy_1manager_ProxyChainNative_go_1update_1chain(
        JNIEnv* env,
        jclass clazz,
        jstring configB64
) {
    (void)clazz;
    if (!configB64) {
        return -1;
    }
    char* cfg = jstring_to_utf8(env, configB64);
    if (!cfg) {
        return -2;
    }
    int rc = go_update_chain(cfg);
    free(cfg);
    return rc;
}

JNIEXPORT jint JNICALL
Java_com_myAllVideoBrowser_util_proxy_1utils_proxy_1manager_ProxyChainNative_go_1start_1local_1proxy(
        JNIEnv* env,
        jclass clazz,
        jint port
) {
    (void)env;
    (void)clazz;
    if (port <= 0) {
        return -1;
    }
    return go_start_local_proxy(port);
}

JNIEXPORT jint JNICALL
Java_com_myAllVideoBrowser_util_proxy_1utils_proxy_1manager_ProxyChainNative_go_1start_1local_1proxy_1auth(
        JNIEnv* env,
        jclass clazz,
        jint port,
        jstring user,
        jstring pass
) {
    (void)clazz;
    if (port <= 0 || !user || !pass) {
        return -1;
    }
    char* u = jstring_to_utf8(env, user);
    char* p = jstring_to_utf8(env, pass);
    if (!u || !p) {
        free(u);
        free(p);
        return -2;
    }
    int rc = go_start_local_proxy_auth(
            port,
            u,
            p
    );
    free(u);
    free(p);
    return rc;
}

JNIEXPORT void JNICALL
Java_com_myAllVideoBrowser_util_proxy_1utils_proxy_1manager_ProxyChainNative_go_1stop_1local_1proxy(
        JNIEnv* env,
        jclass clazz
) {
    (void)env;
    (void)clazz;
    go_stop_local_proxy();
}


