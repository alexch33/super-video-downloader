package com.myAllVideoBrowser.ui.main.proxies

import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.DnsStampHelper
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyHop
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

class ProxiesViewModel @Inject constructor(
    private val proxyController: CustomProxyController,
    private val sharedPrefHelper: SharedPrefHelper
) : BaseViewModel() {
    val proxiesList: ObservableField<MutableList<Proxy>> = ObservableField(mutableListOf())

    val realProxyCount: ObservableField<Int> = ObservableField(0)

    val isProxyOn = ObservableField(false)

    enum class SecureDnsProvider(val url: String) {
        CLOUDFLARE("https://cloudflare-dns.com/dns-query"),
        QUAD9("https://dns.quad9.net/dns-query"),
        ADGUARD("https://dns.adguard-dns.com/dns-query"),
        CUSTOM("");

        fun getCleanUrl(customUrlForProvider: String? = null): String? {
            val urlToParse = if (this == CUSTOM) {
                customUrlForProvider ?: return null
            } else {
                this.url
            }

            if (urlToParse.isBlank()) return null

            // Check if it's a DNS Stamp and decode it
            return if (urlToParse.startsWith("sdns://")) {
                DnsStampHelper.decodeDnsStamp(urlToParse)?.toString()
            } else {
                // It's already a clean URL, so return it directly
                urlToParse
            }
        }
    }

    val isSecureDnsOn = ObservableField(true)

    val selectedDnsProvider = ObservableField(SecureDnsProvider.CLOUDFLARE)

    val customDnsUrl = ObservableField("")

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            isSecureDnsOn.set(sharedPrefHelper.getIsDohOn())
            val savedProviderName = sharedPrefHelper.getSelectedDnsProvider()
            val provider = SecureDnsProvider.entries.find { it.name == savedProviderName }
                ?: SecureDnsProvider.ADGUARD
            selectedDnsProvider.set(provider)
            customDnsUrl.set(sharedPrefHelper.getCustomDnsUrl())

            val usersProxy = sharedPrefHelper.getUserProxyChain()
            proxiesList.set(usersProxy.toMutableList())
            realProxyCount.set(usersProxy.count { it != Proxy.noProxy() })
            isProxyOn.set(sharedPrefHelper.getIsProxyOn())
            updateChain(usersProxy.toList())
        }
    }

    fun setSecureDnsState(isOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isSecureDnsOn.set(isOn)
            sharedPrefHelper.setIsDohOn(isOn)
            proxyController.updateProxyState()
            updateChain(proxiesList.get() ?: emptyList())
        }
    }

    fun setSecureDnsProvider(provider: SecureDnsProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            selectedDnsProvider.set(provider)
            sharedPrefHelper.saveSelectedDnsProvider(provider.name)
            if (provider == SecureDnsProvider.CUSTOM) {
                saveCustomDns()
            }
            proxyController.updateProxyState()
            if (isSecureDnsOn.get() == true) {
                updateChain(proxiesList.get() ?: emptyList())
            }
        }
    }

    fun saveCustomDns() {
        viewModelScope.launch(Dispatchers.IO) {
            val url = customDnsUrl.get() ?: ""
            sharedPrefHelper.saveCustomDnsUrl(url)
            if (isSecureDnsOn.get() == true && selectedDnsProvider.get() == SecureDnsProvider.CUSTOM) {
                updateChain(proxiesList.get() ?: emptyList())
            }
        }
    }

    fun addProxy(newProxy: Proxy) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = proxiesList.get() ?: mutableListOf()
            currentList.add(newProxy)
            saveAndUpdateProxies(currentList)
        }
    }

    fun removeProxy(proxyToRemove: Proxy) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = proxiesList.get() ?: mutableListOf()
            currentList.remove(proxyToRemove)
            saveAndUpdateProxies(currentList)
        }
    }

    fun turnOffProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsProxyOn(false)
            proxyController.updateProxyState()
            isProxyOn.set(false)
            updateChain(listOf(Proxy.noProxy()))
        }
    }

    fun turnOnProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsProxyOn(true)
            proxyController.updateProxyState()
            isProxyOn.set(true)
            updateChain(sharedPrefHelper.getUserProxyChain().toList())
        }
    }

    private fun updateChain(proxies: List<Proxy>) {
        if (isProxyOn.get() == false && isSecureDnsOn.get() == false) {
            ProxyManager.stopLocalProxy()
            return
        }

        val proxyHops = proxies.filter { it != Proxy.noProxy() }.map { proxy ->
            ProxyHop(
                type = proxy.type.name.lowercase(),
                address = proxy.host,
                port = proxy.port.toInt(),
                username = proxy.user.takeIf { it.isNotBlank() },
                password = proxy.password.takeIf { it.isNotBlank() })
        }


        val dnsUrl: String? = if (isSecureDnsOn.get() == true) {
            val provider = selectedDnsProvider.get()

            if (provider == SecureDnsProvider.CUSTOM) {
                provider.getCleanUrl(customDnsUrl.get())
            } else {
                provider?.getCleanUrl()
            }
        } else {
            null
        }

        val localCreds = sharedPrefHelper.getGeneratedCreds()

        ProxyManager.startProxyChain(
            localPort = 8888,
            localUser = localCreds.localUser,
            localPass = localCreds.localPassword,
            hops = proxyHops,
            dnsUrl = dnsUrl
        )
    }

    private fun saveAndUpdateProxies(updatedList: List<Proxy>) {
        viewModelScope.launch(Dispatchers.IO) {
            val cleanedList = if (updatedList.size > 1) {
                updatedList.filter { it != Proxy.noProxy() }
            } else {
                updatedList
            }

            sharedPrefHelper.saveUserProxyChain(cleanedList.toTypedArray())

            proxiesList.set(cleanedList.toMutableList())
            realProxyCount.set(cleanedList.count { it != Proxy.noProxy() })
            proxyController.updateProxyState()
            if (isProxyOn.get() == true) {
                updateChain(cleanedList)
            }
        }
    }

    override fun stop() {

    }
}
