package com.myAllVideoBrowser.util.proxy_utils.proxy_manager

import android.util.Base64
import java.io.Closeable

/**
 * This object directly maps to the JNI functions. The function signatures have been updated
 * to remove the unnecessary 'ptr' argument, matching the Go and C layers.
 */
object ProxyChainNative {
    init {
        // Must load the Go .so first
        System.loadLibrary("proxychain")
        // Then load the JNI glue .so
        System.loadLibrary("proxychain_jni")
    }


    /**
     * Initializes the Go runtime and returns a dummy pointer (always 1) for legacy
     * compatibility.
     */
    external fun go_init_chain(): Long

    /**
     * Stops the Go proxy instance and cleans up resources. This is the only function
     * that still accepts the dummy pointer to maintain a consistent close() pattern.
     */
    external fun go_destroy_chain(ptr: Long)

    /**
     * Updates the proxy chain configuration.
     * The pointer argument has been removed.
     */
    external fun go_update_chain(configB64: String): Int

    /**
     * Starts the local proxy on a given port without authentication.
     * The pointer argument has been removed.
     */
    external fun go_start_local_proxy(port: Int): Int

    /**
     * Starts the local proxy on a given port with username/password authentication.
     * The pointer argument has been removed.
     */
    external fun go_start_local_proxy_auth(
        port: Int, user: String, pass: String
    ): Int

    /**
     * Stops the local proxy service.
     * The pointer argument has been removed.
     */
    external fun go_stop_local_proxy()

    // The deprecated go_create_socket function has been removed entirely.
}

/**
 * Manages the lifecycle of the Go-based proxy. The internal 'chainPtr' is kept
 * to manage the initialized state but is no longer passed to most native functions.
 */
object ProxyManager : Closeable {

    private var chainPtr: Long = 0

    fun init(): Boolean {
        if (isInitialized()) return true
        chainPtr = ProxyChainNative.go_init_chain()
        return isInitialized()
    }

    override fun close() {
        stopLocalProxy()
        if (isInitialized()) {
            ProxyChainNative.go_destroy_chain(chainPtr)
            chainPtr = 0
        }
    }

    fun updateChain(configLines: List<String>): Boolean {
        if (!isInitialized()) return false
        val configText = configLines.joinToString("\n")
        val configB64 = Base64.encodeToString(configText.toByteArray(), Base64.NO_WRAP)
        val res = ProxyChainNative.go_update_chain(configB64)
        return res == 0
    }

    fun startLocalProxy(port: Int): Boolean {
        if (!isInitialized()) return false
        val res = ProxyChainNative.go_start_local_proxy(port)
        return res == 0
    }

    fun startLocalProxyAuth(port: Int, user: String, pass: String): Boolean {
        if (!isInitialized()) return false
        val res = ProxyChainNative.go_start_local_proxy_auth(port, user, pass)
        return res == 0
    }

    fun stopLocalProxy() {
        if (isInitialized()) {
            ProxyChainNative.go_stop_local_proxy()
        }
    }

    fun isInitialized(): Boolean = chainPtr != 0L
}
