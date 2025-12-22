package com.myAllVideoBrowser.util.proxy_utils

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import java.net.Authenticator
import java.net.PasswordAuthentication
import javax.inject.Inject

private data class LastConfig(val proxy: Proxy, val isDohEnabled: Boolean)

class CustomProxyController @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
) {
    private var lastAppliedConfig: LastConfig? = null

    init {
        updateProxyState()
    }

    fun getCurrentRunningProxy(): Proxy {
        return if (isProxyOn()) {
            return getLocalProxy()
        } else {
            Proxy.noProxy()
        }
    }

    fun getProxyCredentials(): Pair<String, String> {
        val currProx = getCurrentRunningProxy()
        return Pair(currProx.user, currProx.password)
    }

    fun updateProxyState() {
        setCurrentProxy(getCurrentRunningProxy())
    }

    private fun setCurrentProxy(proxy: Proxy) {
        val isDohEnabled = sharedPrefHelper.getIsDohOn()
        val newConfig = LastConfig(proxy, isDohEnabled)

        if (newConfig == lastAppliedConfig) {
            AppLogger.d("Proxy config is unchanged. No action needed.")
            return
        }

        val isProxyActive = proxy != Proxy.noProxy() || isDohEnabled

        if (isProxyActive) {
            AppLogger.d("Applying local proxy settings (127.0.0.1:8888).")
            val localProxy = getLocalProxy()

            // These are for the ONLY local proxy
            System.setProperty("http.proxyHost", localProxy.host)
            System.setProperty("http.proxyPort", localProxy.port)
            System.setProperty("https.proxyHost", localProxy.host)
            System.setProperty("https.proxyPort", localProxy.port)

            System.setProperty("http.proxyUser", localProxy.user)
            System.setProperty("http.proxyPassword", localProxy.password)
            System.setProperty("https.proxyUser", localProxy.user)
            System.setProperty("https.proxyPassword", localProxy.password)

            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")

            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(
                        localProxy.user,
                        localProxy.password.toCharArray()
                    )
                }
            })

            // The WebView proxy override points to the local proxy.
            // Other library (ProxyManager) is responsible for chaining this local proxy
            // to the user's proxy and/or DoH.
            val proxyConfig =
                ProxyConfig.Builder().addProxyRule("${localProxy.host}:${localProxy.port}").build()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                try {
                    ProxyController.getInstance().setProxyOverride(proxyConfig, { }) {}
                } catch (e: Exception) {
                    AppLogger.d("ERROR SETTING PROXY: $e")
                }
            }
        } else {
            AppLogger.d("Clearing all proxy settings.")
            System.setProperty("http.proxyHost", "")
            System.setProperty("http.proxyPort", "")
            System.setProperty("https.proxyHost", "")
            System.setProperty("https.proxyPort", "")
            System.setProperty("http.proxyUser", "")
            System.setProperty("http.proxyPassword", "")
            System.setProperty("https.proxyUser", "")
            System.setProperty("https.proxyPassword", "")

            Authenticator.setDefault(null)

            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride({ }) {}
            }
        }

        lastAppliedConfig = newConfig
    }

    private fun isProxyOn(): Boolean {
        return sharedPrefHelper.getIsProxyOn() || sharedPrefHelper.getIsDohOn()
    }

    private fun getLocalProxy(): Proxy {
        val creds = sharedPrefHelper.getGeneratedCreds()
        val localProxy = Proxy(
            host = "127.0.0.1",
            port = "8888",
            user = creds.localUser,
            password = creds.localPassword
        )
        return localProxy
    }
}
