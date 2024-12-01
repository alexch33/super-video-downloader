package com.myAllVideoBrowser.util.proxy_utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.Executors
import javax.inject.Inject

class OkHttpProxyClient @Inject constructor(
    private val okHttpClient: OkHttpClient?,
    private val proxyController: CustomProxyController
) {
    private var currentProxy: com.myAllVideoBrowser.data.local.model.Proxy
    private var httpClientCached: OkHttpClient? = null
    private val executor = Executors.newFixedThreadPool(3).asCoroutineDispatcher()
    private val standaloneScope = CoroutineScope(SupervisorJob() + executor)

    init {
        currentProxy = getProxy()

        standaloneScope.launch {
            proxyController.setClient(getProxyOkHttpClient())
        }
    }

    fun getProxyOkHttpClient(): OkHttpClient {
        val proxy = getProxy()

        if (proxy.host != currentProxy.host && proxy.port != currentProxy.port || (httpClientCached == null)) {
            currentProxy = proxy
            val proxyCredentials = getProxyCredentials()
            val proxyAuthenticator = Authenticator { _, response ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", proxyCredentials)
                    .build()
            }
            httpClientCached =
                if (proxy == com.myAllVideoBrowser.data.local.model.Proxy.noProxy()) {
                    okHttpClient?.newBuilder()!!.build()
                } else {
                    okHttpClient?.newBuilder()
                        ?.proxy(
                            Proxy(
                                Proxy.Type.HTTP,
                                InetSocketAddress(proxy.host, proxy.port.toIntOrNull() ?: 1)
                            )
                        )
                        ?.proxyAuthenticator(proxyAuthenticator)!!.build()
                }
        }

        return httpClientCached!!

    }

    private fun getProxy(): com.myAllVideoBrowser.data.local.model.Proxy {
        return proxyController.getCurrentRunningProxy()
    }

    private fun getProxyCredentials(): String {
        val creds = proxyController.getProxyCredentials()
        return Credentials.basic(creds.first, creds.second)
    }
}