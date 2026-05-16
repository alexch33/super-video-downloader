package com.myAllVideoBrowser.util

import okhttp3.Interceptor
import okhttp3.Response


class ProxyRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        val maxTries = 3

        while (true) {
            try {
                return chain.proceed(request)
            } catch (e: java.net.ConnectException) {
                attempt++

                val isProxyError = e.message?.contains("127.0.0.1") == true

                if (isProxyError && attempt < maxTries) {
                    Thread.sleep(500)
                    continue
                } else {
                    throw e
                }
            }
        }
    }
}