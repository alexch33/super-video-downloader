package com.myAllVideoBrowser.util.proxy_utils

import android.net.Uri
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.core.Observable
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.readByteString
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import javax.inject.Inject

class CustomProxyController @Inject constructor(
    private val sharedPrefHelper: SharedPrefHelper,
    private val schedulers: BaseSchedulers,
    private var okHttpClient: OkHttpClient
) {

    // TODO store api key securely
    private val apiKey = "qwerty"
    private val proxiesDataUrl =
        "https://raw.githubusercontent.com/proxifly/free-proxy-list/main/proxies/protocols/http/data.txt"

    init {
        if (isProxyOn()) {
            setCurrentProxy(getCurrentRunningProxy())
        }
    }

    fun setClient(client: OkHttpClient) {
        this.okHttpClient = client
    }

    fun getClient(): OkHttpClient? {
        return okHttpClient
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
                ProxyController.getInstance().setProxyOverride(proxyConfig, { }) {}
            }
        }

        sharedPrefHelper.setCurrentProxy(proxy)
    }

    fun fetchProxyList(): Observable<List<Proxy>> {
        val proxiesText =
            "10.0.2.2 \t2080 \tProxies!!! \t \t \tCity \tLocal \t0.0.0.0 \t0.0.0.0 \t0.0.0.0 \texample.domen.com \t96767\n" +
                    "0.0.0.0 \t8081 \tProxies!!! \tloginhere \tpasswordhere \tCity \tCountry \t0.0.0.0 \t0.0.0.0 \t0.0.0.0 \texample.domen.com \t96767\n" +
                    "127.0.0.1 \t8080 \tProxies!!! \tlogin \tpass \tCity \tCountry \t0.0.0.0 \t0.0.0.0 \t0.0.0.0 \texample.domen.com \t96767"


        return Observable.create<List<Proxy>> { emitter ->
            val result = arrayListOf<Proxy>()
            val proxyLines = proxiesText.split("\n")
            for (proxyLine in proxyLines) {
                val proxDataArr = proxyLine.split(Regex(" \t"))
                val host = proxDataArr[0].trim()
                val port = proxDataArr[1].trim()
                val user = proxDataArr[3].trim()
                val password = proxDataArr[4].trim()
                val city = proxDataArr[5].trim()
                val country = proxDataArr[6].trim()

                result.add(
                    Proxy(
                        host = host,
                        port = port,
                        countryCode = country,
                        cityName = city,
                        user = user,
                        password = password
                    )
                )
            }


            emitter.onNext(result)

            val response = try {
                okHttpClient.newCall(Request.Builder().url(proxiesDataUrl).build()).execute()
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }

            if (response != null) {
                val data = response.body.byteStream().readByteString(254).utf8()
                val httpProxiesList = data.split("\n").toSet()
                response.body.close()
                for (httpProxyString in httpProxiesList) {
                    if (httpProxyString.startsWith("http")) {
                        val uri = Uri.parse(httpProxyString)
                        val host = uri.host
                        val port = uri.port
                        if (!host.isNullOrEmpty() && port.toString().isNotEmpty()) {
                            AppLogger.d("CHECKING PROXY $host:$port")
                            if (isProxyWorking(host, port)) {
                                AppLogger.d("PROXY OK $host:$port")
                                result.add(
                                    Proxy(
                                        httpProxyString.hashCode().toString(),
                                        host,
                                        port.toString()
                                    )
                                )
                                emitter.onNext(result)
                            } else {
                                AppLogger.d("PROXY FAILED: $httpProxyString")
                            }
                        }
                    }
                }

            }

            emitter.onComplete()
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

    private fun isProxyWorking(proxyHost: String, proxyPort: Int): Boolean {
        val proxy =
            java.net.Proxy(java.net.Proxy.Type.HTTP, InetSocketAddress(proxyHost, proxyPort))
        val client = OkHttpClient.Builder()
            .proxy(proxy)
            .build()

        val request = Request.Builder()
            .url("https://www.google.com")
            .head() // Use HEAD request
            .build()

        return try {
            client.newCall(request).execute().isSuccessful
        } catch (e: IOException) {
            false
        }
    }
}
