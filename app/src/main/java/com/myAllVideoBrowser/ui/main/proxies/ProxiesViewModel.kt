package com.myAllVideoBrowser.ui.main.proxies

import androidx.databinding.ObservableField
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import javax.inject.Inject

class ProxiesViewModel @Inject constructor(
    private val proxyController: CustomProxyController,
    private val baseSchedulers: BaseSchedulers
) : BaseViewModel() {
    val currentProxy = ObservableField(Proxy.noProxy())

    val proxiesList: ObservableField<MutableList<Proxy>> = ObservableField(mutableListOf())

    val isProxyOn = ObservableField(false)

    private val compositeDisposable = CompositeDisposable()

    override fun start() {
        fetchProxies()
        currentProxy.set(proxyController.getCurrentSavedProxy())
        isProxyOn.set(proxyController.isProxyOn())
    }

    private fun fetchProxies() {
        val disposable = proxyController.fetchProxyList().subscribeOn(baseSchedulers.io)
            .observeOn(baseSchedulers.computation).subscribe {
                proxiesList.set(it.toMutableList())
            }
        compositeDisposable.add(disposable)
    }

    override fun stop() {
        compositeDisposable.clear()
    }


    fun setProxy(proxy: Proxy) {
        proxyController.setCurrentProxy(proxy)
        currentProxy.set(proxy)
        isProxyOn.set(true)

        refreshList()
    }

    fun turnOffProxy() {
        proxyController.setIsProxyOn(false)
        isProxyOn.set(false)
    }

    fun turnOnProxy() {
        proxyController.setIsProxyOn(true)
        isProxyOn.set(true)
    }

    private fun refreshList() {
        val refreshed = proxiesList.get()?.toMutableList()
        proxiesList.set(refreshed)
    }
}