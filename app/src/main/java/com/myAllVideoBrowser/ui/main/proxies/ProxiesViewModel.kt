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

    private val defaultDohConfig = "doh=strict:https://cloudflare-dns.com/dns-query"

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            val usersProxy = sharedPrefHelper.getUserProxyChain()
            proxiesList.set(usersProxy.toMutableList())
            realProxyCount.set(usersProxy.count { it != Proxy.noProxy() })
            isProxyOn.set(proxyController.isProxyOn())
            updateChain(usersProxy.toList())
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

    private fun saveAndUpdateProxies(updatedList: List<Proxy>) {
        val cleanedList = if (updatedList.size > 1) {
            updatedList.filter { it != Proxy.noProxy() }
        } else {
            updatedList
        }

        sharedPrefHelper.saveUserProxyChain(cleanedList.toTypedArray())

        proxiesList.set(cleanedList.toMutableList())
        realProxyCount.set(cleanedList.count { it != Proxy.noProxy() })

        if (isProxyOn.get() == true) {
            updateChain(cleanedList)
        }
    }

    fun turnOffProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            proxyController.setIsProxyOn(false)
            isProxyOn.set(false)
            updateChain(listOf(Proxy.noProxy()))
        }
    }

    fun turnOnProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            proxyController.setIsProxyOn(true)
            isProxyOn.set(true)
            updateChain(sharedPrefHelper.getUserProxyChain().toList())
        }
    }

    private fun updateChain(proxies: List<Proxy>) {
        val proxy = proxies.firstOrNull() ?: Proxy.noProxy()
        if (proxy != Proxy.noProxy()) {
            val formattedProxyChain = proxies.map { it.toString() }
            ProxyManager.updateChain(
                formattedProxyChain + listOf(
                    defaultDohConfig
                )
            )
        } else {
            ProxyManager.updateChain(
                listOf(
                    defaultDohConfig
                )
            )
        }
    }

    override fun stop() {

    }
}
