package com.myAllVideoBrowser.ui.main.proxies

import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.proxy_manager.ProxyManager
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

class ProxiesViewModel @Inject constructor(
    private val proxyController: CustomProxyController,
    private val baseSchedulers: BaseSchedulers,
    private val sharedPrefHelper: SharedPrefHelper
) : BaseViewModel() {
    val currentProxy = ObservableField(Proxy.noProxy())

    val userProxy = ObservableField(Proxy.noProxy())

    val proxiesList: ObservableField<MutableList<Proxy>> = ObservableField(mutableListOf())

    val isProxyOn = ObservableField(false)

    private var compositeDisposable = CompositeDisposable()

    private val defaultDohConfig = "doh=strict:https://cloudflare-dns.com/dns-query"

    override fun start() {
        if (compositeDisposable.size() >= 1) {
            compositeDisposable.dispose()
            compositeDisposable = CompositeDisposable()
        }

        fetchProxies()
        viewModelScope.launch(Dispatchers.IO) {
            val usersProxy = sharedPrefHelper.getUserProxy()
            userProxy.set(usersProxy)
            currentProxy.set(usersProxy)
            isProxyOn.set(proxyController.isProxyOn())
            updateChain(usersProxy)
        }
    }

    override fun stop() {
        compositeDisposable.clear()
    }


    fun turnOffProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            proxyController.setIsProxyOn(false)
            isProxyOn.set(false)
            updateChain(Proxy.noProxy())
        }
    }

    fun turnOnProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            proxyController.setIsProxyOn(true)
            isProxyOn.set(true)
            updateChain(sharedPrefHelper.getUserProxy())
        }
    }

    fun setUserProxy(userProxy: Proxy) {
        viewModelScope.launch(Dispatchers.IO) {
            this@ProxiesViewModel.userProxy.set(userProxy)
            sharedPrefHelper.saveUserProxy(userProxy)

            updateChain(userProxy)
            currentProxy.set(userProxy)
            isProxyOn.set(sharedPrefHelper.getIsProxyOn())

            refreshList()
        }
    }

    private fun fetchProxies() {
        val disposable = proxyController.fetchUserProxy().subscribeOn(baseSchedulers.io)
            .observeOn(baseSchedulers.computation).subscribe {
                proxiesList.set(listOf(it).toMutableList())
            }
        compositeDisposable.add(disposable)
    }

    private fun refreshList() {
        val refreshed = proxiesList.get()?.toMutableList()
        proxiesList.set(refreshed)
    }

    private fun updateChain(proxy: Proxy) {
        if (proxy != Proxy.noProxy()) {
            ProxyManager.updateChain(
                listOf(
                    proxy.toString(), defaultDohConfig
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
}