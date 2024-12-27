package com.myAllVideoBrowser.data.remote

import com.myAllVideoBrowser.data.local.room.entity.AdHost
import com.myAllVideoBrowser.data.repository.AdBlockHostsRepository
import com.myAllVideoBrowser.util.AdBlockerHelper
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.Request
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import javax.inject.Inject
import javax.inject.Singleton

const val AD_HOSTS_URL_LIST_ADAWAY = "https://adaway.org/hosts.txt"
const val AD_HOSTS_URLS_LIST_ADMIRAL = "https://v.firebog.net/hosts/Admiral.txt"
const val TRACKING_BLACK_LIST = "https://v.firebog.net/hosts/Easyprivacy.txt"
const val AD_HOSTS_URLS_AD_GUARD = "https://v.firebog.net/hosts/AdguardDNS.txt"

@Singleton
class AdBlockHostsRemoteDataSource @Inject constructor(
    private val okHttpClient: OkHttpProxyClient
) : AdBlockHostsRepository {
    override suspend fun fetchHosts(): Set<AdHost> {
        val tasks = listOf(
            fetchListFromUrl(AD_HOSTS_URLS_AD_GUARD).catch { emit(emptySet()) }.cancellable(),
            fetchListFromUrl(AD_HOSTS_URL_LIST_ADAWAY).catch { emit(emptySet()) }.cancellable(),
            fetchListFromUrl(AD_HOSTS_URLS_LIST_ADMIRAL).catch { emit(emptySet()) }.cancellable(),
            fetchListFromUrl(TRACKING_BLACK_LIST).catch { emit(emptySet()) }.cancellable()
        )
        val result = mutableSetOf<AdHost>()
        merge(tasks[0], tasks[1], tasks[2], tasks[3]).cancellable().onEach { hosts ->
            result.addAll(hosts)
        }.collect()

        return result.toSet()
    }

    private suspend fun fetchListFromUrl(url: String): kotlinx.coroutines.flow.Flow<Set<AdHost>> {
        return flow {
            okHttpClient.getProxyOkHttpClient().newCall(Request.Builder().url(url).build()).execute()
                .use { response ->
                    response.takeIf { it.isSuccessful }?.body?.byteStream()?.use { stream ->
                        emit(readAdServersFromStream(stream))
                    }
                }
        }
    }

    override fun isAds(url: String): Boolean {
        throw Exception("use isAds from local")
    }

    override suspend fun addHosts(hosts: Set<AdHost>) {
        throw Exception("To remote hosts forbidden to add")
    }

    override suspend fun removeHosts(hosts: Set<AdHost>) {
        throw Exception("To remote hosts forbidden to remove")
    }

    override suspend fun addHost(host: AdHost) {
        throw Exception("To remote host forbidden to add")
    }

    override suspend fun removeHost(host: AdHost) {
        throw Exception("To remote host forbidden to remove")
    }

    override suspend fun removeAllHost() {
        throw Exception("To remote host forbidden to removeAllHost")
    }

    override suspend fun getHostsCount(): Int {
        throw Exception("To remote host forbidden to getHostsCount")
    }

    override fun getCachedCount(): Int {
        return 0
    }

    override suspend fun initialize(isUpdate: Boolean): Boolean {
        throw Exception("no need to Initialize AdBlockHostsRemoteDataSource")
    }

    private suspend fun readAdServersFromStream(inputStream: InputStream): Set<AdHost> {
        val br2 = BufferedReader(InputStreamReader(inputStream))
        val result = mutableSetOf<AdHost>()

        try {
            var line2: String?

            yield()

            while (withContext(Dispatchers.IO) {
                    br2.readLine()
                }.also { line2 = it } != null) {
                if (!line2.toString().startsWith("#")) {
                    val parsedLine = AdBlockerHelper.parseAdsLine(line2)
                    if (parsedLine.contains(Regex(".+\\..+"))) {
                        result.add(AdHost(parsedLine))
                    }
                }
            }
            yield()
        } catch (e: IOException) {
            e.printStackTrace()
            yield()
        } finally {
            yield()
            withContext(Dispatchers.IO) {
                br2.close()
            }
        }

        return result
    }

}
