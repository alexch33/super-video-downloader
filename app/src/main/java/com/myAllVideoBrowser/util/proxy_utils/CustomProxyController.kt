package com.myAllVideoBrowser.util.proxy_utils

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.core.Observable
import java.net.Authenticator
import java.net.PasswordAuthentication
import javax.inject.Inject

class CustomProxyController @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
    private val schedulers: BaseSchedulers,
) {

    init {
        if (isProxyOn()) {
            setCurrentProxy(getCurrentRunningProxy())
        }
    }

    fun getCurrentRunningProxy(): Proxy {
        return if (isProxyOn()) {
            sharedPrefHelper.getCurrentProxy()
        } else {
            Proxy.noProxy()
        }
    }

    fun getCurrentSavedProxy(): Proxy {
        return sharedPrefHelper.getCurrentProxy()
    }

    fun getProxyCredentials(): Pair<String, String> {
        val currProx = getCurrentRunningProxy()
        return Pair(currProx.user, currProx.password)
    }

    fun setCurrentProxy(proxy: Proxy) {
        if (proxy == Proxy.noProxy()) {
            System.setProperty("http.proxyUser", "")
            System.setProperty("http.proxyPassword", "")
            System.setProperty("https.proxyUser", "")
            System.setProperty("https.proxyPassword", "")

            Authenticator.setDefault(object : Authenticator() {})

            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride({ }) {}
            }
        } else {
            sharedPrefHelper.setIsProxyOn(true)

            System.setProperty("http.proxyUser", proxy.user.trim())
            System.setProperty("http.proxyPassword", proxy.password.trim())
            System.setProperty("https.proxyUser", proxy.user.trim())
            System.setProperty("https.proxyPassword", proxy.password.trim())

            System.setProperty("http.proxyHost", proxy.host.trim())
            System.setProperty("http.proxyPort", proxy.port.trim())

            System.setProperty("https.proxyHost", proxy.host.trim())
            System.setProperty("https.proxyPort", proxy.port.trim())
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")

            Authenticator.setDefault(object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication {
                    return PasswordAuthentication(proxy.user, proxy.password.toCharArray())
                }
            })

            val proxyConfig =
                ProxyConfig.Builder().addProxyRule("${proxy.host}:${proxy.port}").build()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                try {
                    ProxyController.getInstance().setProxyOverride(proxyConfig, { }) {}
                } catch (e: Exception) {
                    AppLogger.d("ERROR SETTING PROXY: $e")
                }
            }
        }

        sharedPrefHelper.setCurrentProxy(proxy)
    }

    fun fetchUserProxy(): Observable<Proxy> {
        return Observable.create { emitter ->
            val userProxy = sharedPrefHelper.getUserProxy()
            if (userProxy != null) {
                emitter.onNext(userProxy)
                emitter.onComplete()
            } else {
                emitter.onComplete()
            }
        }.doOnError {}.subscribeOn(schedulers.io)
    }

    fun isProxyOn(): Boolean {
        return sharedPrefHelper.getIsProxyOn()
    }

    fun setIsProxyOn(isOn: Boolean) {
        if (isOn) {
            setCurrentProxy(sharedPrefHelper.getCurrentProxy())
        } else {
            System.setProperty("http.proxyUser", "")
            System.setProperty("http.proxyPassword", "")
            System.setProperty("https.proxyUser", "")
            System.setProperty("https.proxyPassword", "")

            System.setProperty("http.proxyHost", "")
            System.setProperty("http.proxyPort", "")

            System.setProperty("https.proxyHost", "")
            System.setProperty("https.proxyPort", "")
            System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "")

            Authenticator.setDefault(object : Authenticator() {})

            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride({ }) {}
            }
        }

        sharedPrefHelper.setIsProxyOn(isOn)
    }
}
