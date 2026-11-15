package com.myAllVideoBrowser.ui.main.proxies

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ProxiesViewModel @Inject constructor(
    private val proxyController: CustomProxyController,
    private val sharedPrefHelper: SharedPrefHelper
) : BaseViewModel() {

    private val _proxiesList = MutableLiveData<List<Proxy>>(emptyList())
    val proxiesList: LiveData<List<Proxy>> = _proxiesList

    private val _realProxyCount = MutableLiveData(0)
    val realProxyCount: LiveData<Int> = _realProxyCount

    private val _isProxyOn = MutableLiveData(false)
    val isProxyOn: LiveData<Boolean> = _isProxyOn

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

    private val _isSecureDnsOn = MutableLiveData(true)
    val isSecureDnsOn: LiveData<Boolean> = _isSecureDnsOn

    private val _selectedDnsProvider = MutableLiveData(SecureDnsProvider.CLOUDFLARE)
    val selectedDnsProvider: LiveData<SecureDnsProvider> = _selectedDnsProvider

    private val _customDnsUrl = MutableLiveData("")
    val customDnsUrl: LiveData<String> = _customDnsUrl

    override fun start() {
        viewModelScope.launch(Dispatchers.IO) {
            val dohOn = sharedPrefHelper.getIsDohOn()
            val savedProviderName = sharedPrefHelper.getSelectedDnsProvider()
            val provider = SecureDnsProvider.entries.find { it.name == savedProviderName }
                ?: SecureDnsProvider.ADGUARD
            val customDns = sharedPrefHelper.getCustomDnsUrl()
            val usersProxy = sharedPrefHelper.getUserProxyChain()
            val proxyOn = sharedPrefHelper.getIsProxyOn()

            withContext(Dispatchers.Main) {
                _isSecureDnsOn.value = dohOn
                _selectedDnsProvider.value = provider
                _customDnsUrl.value = customDns
                _proxiesList.value = usersProxy.toList()
                _realProxyCount.value = usersProxy.count { it != Proxy.noProxy() }
                _isProxyOn.value = proxyOn
            }
            updateChain()
        }
    }

    fun setSecureDnsState(isOn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isSecureDnsOn.postValue(isOn)
            sharedPrefHelper.setIsDohOn(isOn)
            proxyController.updateProxyState()
            updateChain()
        }
    }

    fun setSecureDnsProvider(provider: SecureDnsProvider) {
        viewModelScope.launch(Dispatchers.IO) {
            _selectedDnsProvider.postValue(provider)
            sharedPrefHelper.saveSelectedDnsProvider(provider.name)
            if (provider == SecureDnsProvider.CUSTOM) {
                saveCustomDns()
            }
            proxyController.updateProxyState()
            if (_isSecureDnsOn.value == true) {
                updateChain()
            }
        }
    }

    fun saveCustomDns() {
        viewModelScope.launch(Dispatchers.IO) {
            val url = _customDnsUrl.value ?: ""
            sharedPrefHelper.saveCustomDnsUrl(url)
            if (_isSecureDnsOn.value == true && _selectedDnsProvider.value == SecureDnsProvider.CUSTOM) {
                updateChain()
            }
        }
    }

    fun setCustomDnsUrl(url: String) {
        _customDnsUrl.value = url
    }

    fun addProxy(newProxy: Proxy) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = _proxiesList.value?.toMutableList() ?: mutableListOf()
            currentList.add(newProxy)
            saveAndUpdateProxies(currentList)
        }
    }

    fun removeProxy(proxyToRemove: Proxy) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentList = _proxiesList.value?.toMutableList() ?: mutableListOf()
            currentList.remove(proxyToRemove)
            saveAndUpdateProxies(currentList)
        }
    }

    fun turnOffProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsProxyOn(false)
            proxyController.updateProxyState()
            _isProxyOn.postValue(false)
            updateChain()
        }
    }

    fun turnOnProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            sharedPrefHelper.setIsProxyOn(true)
            proxyController.updateProxyState()
            _isProxyOn.postValue(true)
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

            _proxiesList.postValue(cleanedList)
            _realProxyCount.postValue(cleanedList.count { it != Proxy.noProxy() })
            proxyController.updateProxyState()
            if (_isProxyOn.value == true) {
                updateChain()
            }
        }
    }

    override fun stop() {
    }
}
