package com.myAllVideoBrowser.util.proxy_utils.proxy_manager

import android.util.Base64
import java.io.Closeable

// This object directly maps to the JNI functions
object ProxyChainNative {
    init {
        // Must load the Go .so first
        System.loadLibrary("proxychain")
        // Then load the JNI glue .so
        System.loadLibrary("proxychain_jni")
    }

    // These now match the C signatures EXACTLY (excluding the env and clazz params)
    external fun go_init_chain(): Long
    external fun go_destroy_chain(ptr: Long)

    // All the functions that were missing the 'ptr' argument are now corrected
    external fun go_update_chain(ptr: Long, configB64: String): Int

    external fun go_start_local_proxy(ptr: Long, port: Int): Int
    external fun go_start_local_proxy_auth(
        ptr: Long, port: Int, user: String, pass: String
    ): Int

    external fun go_stop_local_proxy(ptr: Long)

    external fun go_create_socket(ptr: Long, uri: String): Int
}

object ProxyManager : Closeable {

    private var chainPtr: Long = 0

    fun init(): Boolean {
        if (chainPtr != 0L) return true
        chainPtr = ProxyChainNative.go_init_chain()
        return chainPtr != 0L
    }

    override fun close() {
        stopLocalProxy()
        if (chainPtr != 0L) {
            ProxyChainNative.go_destroy_chain(chainPtr)
            chainPtr = 0
        }
    }

    fun updateChain(configLines: List<String>): Boolean {
        if (chainPtr == 0L) return false
        val configText = configLines.joinToString("\n")
        val configB64 = Base64.encodeToString(configText.toByteArray(), Base64.NO_WRAP)
        // Pass the pointer
        val res = ProxyChainNative.go_update_chain(chainPtr, configB64)
        return res == 0
    }

    fun startLocalProxy(port: Int): Boolean {
        if (chainPtr == 0L) return false
        // Pass the pointer
        val res = ProxyChainNative.go_start_local_proxy(chainPtr, port)
        return res == 0
    }

    fun startLocalProxyAuth(port: Int, user: String, pass: String): Boolean {
        if (chainPtr == 0L) return false
        // Pass the pointer
        val res = ProxyChainNative.go_start_local_proxy_auth(chainPtr, port, user, pass)
        return res == 0
    }

    fun stopLocalProxy() {
        if (chainPtr != 0L) {
            // Pass the pointer
            ProxyChainNative.go_stop_local_proxy(chainPtr)
        }
    }

    fun createSocket(uri: String): Int {
        if (chainPtr == 0L) return -1
        // Pass the pointer
        return ProxyChainNative.go_create_socket(chainPtr, uri)
    }

    fun isInitialized(): Boolean = chainPtr != 0L
}
