package com.myAllVideoBrowser.ui.main.proxies

import androidx.databinding.ObservableField
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.ui.main.base.BaseViewModel
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.DnsStampHelper
import com.myAllVideoBrowser.util.SharedPrefHelper
import com.myAllVideoBrowser.util.proxy_utils.CustomProxyController
import com.myAllVideoBrowser.util.proxy_utils.ProxyWorker
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
            updateChain()
        }
    }

    fun setSecureDnsState(isOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            isSecureDnsOn.set(isOn)
            sharedPrefHelper.setIsDohOn(isOn)
            proxyController.updateProxyState()
            updateChain()
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
                updateChain()
            }
        }
    }

    fun saveCustomDns() {
        viewModelScope.launch(Dispatchers.IO) {
            val url = customDnsUrl.get() ?: ""
            sharedPrefHelper.saveCustomDnsUrl(url)
            if (isSecureDnsOn.get() == true && selectedDnsProvider.get() == SecureDnsProvider.CUSTOM) {
                updateChain()
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
            updateChain()
        }
    }

    fun turnOnProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsProxyOn(true)
            proxyController.updateProxyState()
            isProxyOn.set(true)
            updateChain()
        }
    }

    fun shutdownProxyWorker() {
        WorkManager.getInstance(ContextUtils.getApplicationContext())
            .cancelUniqueWork(ProxyWorker.WORK_NAME)
    }

    private fun updateChain() {
        val useProxy = sharedPrefHelper.getIsProxyOn()
        val useDns = sharedPrefHelper.getIsDohOn()

        if (!useProxy && !useDns) {
            shutdownProxyWorker()
            return
        }

        val workRequest = OneTimeWorkRequestBuilder<ProxyWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(ContextUtils.getApplicationContext()).enqueueUniqueWork(
            ProxyWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
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
                updateChain()
            }
        }
    }

    override fun stop() {

    }
}
