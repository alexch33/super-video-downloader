package com.myAllVideoBrowser.data.local

import android.net.Uri

import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.dao.AdHostDao
import com.myAllVideoBrowser.data.local.room.entity.AdHost
import com.myAllVideoBrowser.data.repository.AdBlockHostsRepository
import com.myAllVideoBrowser.util.AdBlockerHelper.parseAdsLine
import com.myAllVideoBrowser.util.ContextUtils
import com.myAllVideoBrowser.util.SharedPrefHelper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AdBlockHostsLocalDataSource @Inject constructor(
    private val adHostDao: AdHostDao,
    private val sharedPrefHelper: SharedPrefHelper
) : AdBlockHostsRepository {
    private val hostsCache = mutableSetOf<AdHost>()
    override suspend fun initialize(isUpdate: Boolean): Boolean {
        return fetchHosts().isNotEmpty()
    }

    override suspend fun fetchHosts(): Set<AdHost> {
        val isPopulated = sharedPrefHelper.getIsPopulated()

        if (isPopulated) {
            hostsCache.addAll(adHostDao.getAdHosts())
        } else {
            var counter = 0

            fetchHostsFromFiles().onEach { adHosts ->
                counter += adHosts.size
                adHostDao.insertAdHosts(adHosts)
            }.onCompletion {
                if (it == null && counter > 80000) {
                    sharedPrefHelper.setIsPopulated(true)
                }
            }.collect()

            hostsCache.addAll(adHostDao.getAdHosts())
        }

        return hostsCache
    }

    override fun isAds(url: String): Boolean {
        val uri = try {
            Uri.parse(url)
        } catch (_: Throwable) {
            null
        }
        val host = uri?.host.toString()
            .replace("www.", "")
            .replace("m.", "")
            .trim()
        if (host.isNotEmpty()) {
            return hostsCache.contains(AdHost(host))
        }

        return false
    }

    override suspend fun addHosts(hosts: Set<AdHost>) {
        adHostDao.insertAdHosts(hosts)
    }

    override suspend fun addHost(host: AdHost) {
        adHostDao.insertAdHost(host)
    }

    override suspend fun removeHosts(hosts: Set<AdHost>) {
        adHostDao.deleteAdHosts(hosts)
    }

    override suspend fun removeHost(host: AdHost) {
        adHostDao.deleteAdHost(host)
    }

    override suspend fun removeAllHost() {
        adHostDao.deleteAllAdHosts()
    }

    override suspend fun getHostsCount(): Int {
        return adHostDao.getHostsCount()
    }

    private suspend fun fetchHostsFromFiles(): Flow<Set<AdHost>> {
        val tasks = listOf(
            fetchHostsFromFileRaw(R.raw.adblockserverlist).catch { emit(emptySet()) },
            fetchHostsFromFileRaw(R.raw.adblockserverlist2).catch { emit(emptySet()) },
            fetchHostsFromFileRaw(R.raw.adblockserverlist3).catch { emit(emptySet()) }
        )

        return merge(tasks[0], tasks[1], tasks[2])
    }

    private suspend fun fetchHostsFromFileRaw(resource: Int): kotlinx.coroutines.flow.Flow<Set<AdHost>> {
        return flow {
            val inputStream =
                ContextUtils.getApplicationContext().resources.openRawResource(resource)

            emit(readAdServersFromStream(inputStream))
        }
    }

    private suspend fun readAdServersFromStream(inputStream: InputStream): Set<AdHost> {
        return withContext(Dispatchers.IO) {
            inputStream.bufferedReader().useLines { lines ->
                lines.filterNot { it.startsWith("#") }
                    .map { parseAdsLine(it) }
                    .filter { it.contains(Regex(".+\\..+")) }
                    .map { AdHost(it) }
                    .toSet()
            }
        }
    }

    override fun getCachedCount(): Int {
        return hostsCache.size
    }
}
