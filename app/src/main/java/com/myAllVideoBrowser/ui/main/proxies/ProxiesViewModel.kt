package com.myAllVideoBrowser.ui.main.proxies

import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
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

    enum class DohProvider(val url: String) {
        CLOUDFLARE("doh=strict:https://cloudflare-dns.com/dns-query"), QUAD9("doh=strict:https://dns.quad9.net/dns-query"), ADGUARD(
            "doh=strict:https://dns.adguard-dns.com/dns-query"
        )
    }

    val isDohOn = ObservableField(true)

    val selectedDohProvider = ObservableField(DohProvider.CLOUDFLARE)

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            isDohOn.set(sharedPrefHelper.getIsDohOn())
            val savedProviderName = sharedPrefHelper.getSelectedDohProvider()
            val provider =
                DohProvider.entries.find { it.name == savedProviderName } ?: DohProvider.CLOUDFLARE
            selectedDohProvider.set(provider)

            val usersProxy = sharedPrefHelper.getUserProxyChain()
            proxiesList.set(usersProxy.toMutableList())
            realProxyCount.set(usersProxy.count { it != Proxy.noProxy() })
            isProxyOn.set(sharedPrefHelper.getIsProxyOn())
            updateChain(usersProxy.toList())
        }
    }

    fun setDohState(isOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isDohOn.set(isOn)
            sharedPrefHelper.setIsDohOn(isOn)
            proxyController.updateProxyState()
            updateChain(proxiesList.get() ?: emptyList())
        }
    }

    fun setDohProvider(provider: DohProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            selectedDohProvider.set(provider)
            sharedPrefHelper.saveSelectedDohProvider(provider.name)
            proxyController.updateProxyState()
            if (isDohOn.get() == true) {
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
        val proxyChain = mutableListOf<String>()
        val realProxies = proxies.filter { it != Proxy.noProxy() }

        if (realProxies.isNotEmpty()) {
            proxyChain.addAll(realProxies.map { it.toString() })
        }

        if (isDohOn.get() == true) {
            selectedDohProvider.get()?.let { provider ->
                proxyChain.add(provider.url)
            }
        }

        ProxyManager.updateChain(proxyChain)
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
