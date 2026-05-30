package com.myAllVideoBrowser.data.repository

import android.content.Context
import com.myAllVideoBrowser.data.local.room.dao.AdBlockDao
import com.myAllVideoBrowser.data.local.room.entity.AdBlockList
import com.myAllVideoBrowser.di.qualifier.ApplicationContext
import com.myAllVideoBrowser.ui.main.home.browser.adblocker.AdBlockEngine
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdBlockRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val adBlockDao: AdBlockDao,
    private val okHttpClient: OkHttpProxyClient,
    private val adBlockEngine: AdBlockEngine
) {
    private val _activeDownloads = MutableStateFlow<Set<Int>>(emptySet())
    val activeDownloads: Flow<Set<Int>> = _activeDownloads.asStateFlow()

    fun getAllLists(): Flow<List<AdBlockList>> = adBlockDao.getAllLists()

    suspend fun checkAndPrepopulateDefaults() {
        val count = adBlockDao.getCount()
        if (count == 0) {
            val defaults = listOf(
                AdBlockList(
                    name = "EasyPrivacy (Built-in)",
                    url = null,
                    isEnabled = true,
                    isDownloaded = true
                ),
                AdBlockList(
                    name = "EasyList",
                    url = "https://easylist.to/easylist/easylist.txt",
                    isEnabled = false,
                    isDownloaded = false
                ),
                AdBlockList(
                    name = "EasyPrivacy",
                    url = "https://easylist.to/easylist/easyprivacy.txt",
                    isEnabled = true,
                    isDownloaded = false
                ),
                AdBlockList(
                    name = "OISD Privacy",
                    url = "https://abp.oisd.nl/",
                    isEnabled = false,
                    isDownloaded = false
                ),
                AdBlockList(
                    name = "AdGuard Social Media",
                    url = "https://filters.adtidy.org/extension/chromium/filters/4.txt",
                    isEnabled = false,
                    isDownloaded = false
                ),
                AdBlockList(
                    name = "AdGuard Base",
                    url = "https://filters.adtidy.org/extension/chromium/filters/2.txt",
                    isEnabled = false,
                    isDownloaded = false
                ),
                AdBlockList(
                    name = "AdGuard Tracking Protection (Privacy)",
                    url = "https://filters.adtidy.org/extension/chromium/filters/3.txt",
                    isEnabled = false,
                    isDownloaded = false
                ),
                AdBlockList(
                    name = "uBlock filters",
                    url = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
                    isEnabled = false,
                    isDownloaded = false
                )
            )
            defaults.forEach { adBlockDao.insert(it) }
        }
    }

    suspend fun downloadEnabledLists() {
        val enabledNotDownloaded =
            adBlockDao.getEnabledLists().filter { !it.isDownloaded && it.url != null }
        enabledNotDownloaded.forEach {
            downloadList(it)
        }
    }

    suspend fun addCustomList(name: String, url: String) {
        val newList = AdBlockList(name = name, url = url, isEnabled = false, isDownloaded = false)
        adBlockDao.insert(newList)
    }

    suspend fun toggleList(list: AdBlockList) {
        val updated = list.copy(isEnabled = !list.isEnabled)
        adBlockDao.update(updated)
        if (updated.isEnabled && !updated.isDownloaded && updated.url != null) {
            downloadList(updated)
        } else {
            adBlockEngine.loadRules()
        }
    }

    suspend fun deleteList(list: AdBlockList) {
        list.localPath?.let { File(it).delete() }
        adBlockDao.delete(list)
        adBlockEngine.loadRules()
    }

    suspend fun downloadList(list: AdBlockList): Boolean = withContext(Dispatchers.IO) {
        val url = list.url ?: return@withContext false

        _activeDownloads.update { it + list.id }
        adBlockDao.update(list.copy(isDownloadFailed = false))

        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.getProxyOkHttpClient().newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body
                    val dir = File(context.filesDir, "adblock")
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, "list_${list.id}.txt")

                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    val updatedList = list.copy(
                        localPath = file.absolutePath,
                        lastUpdated = System.currentTimeMillis(),
                        isDownloaded = true,
                        isDownloadFailed = false
                    )
                    adBlockDao.update(updatedList)
                    adBlockEngine.loadRules()
                    return@withContext true
                } else {
                    adBlockDao.update(list.copy(isDownloadFailed = true))
                }
            }
        } catch (e: Exception) {
            AppLogger.e("Download failed: ${list.url} ${e.message}")
            adBlockDao.update(list.copy(isDownloadFailed = true))
        } finally {
            _activeDownloads.update { it - list.id }
        }
        return@withContext false
    }
}
