package com.myAllVideoBrowser.data.repository


import com.myAllVideoBrowser.data.local.room.entity.AdHost
import com.myAllVideoBrowser.di.qualifier.LocalData
import com.myAllVideoBrowser.di.qualifier.RemoteData
import com.myAllVideoBrowser.util.SharedPrefHelper

import javax.inject.Inject
import javax.inject.Singleton

interface AdBlockHostsRepository {
    suspend fun initialize(isUpdate: Boolean): Boolean

    suspend fun fetchHosts(): Set<AdHost>

    fun isAds(url: String): Boolean

    suspend fun addHosts(hosts: Set<AdHost>)

    suspend fun removeHosts(hosts: Set<AdHost>)

    suspend fun addHost(host: AdHost)

    suspend fun removeHost(host: AdHost)
    suspend fun removeAllHost()


    suspend fun getHostsCount(): Int

    fun getCachedCount(): Int
}

@Singleton
class AdBlockHostsRepositoryImpl @Inject constructor(
    @LocalData private val localDataSource: AdBlockHostsRepository,
    @RemoteData private val remoteDataSource: AdBlockHostsRepository,
    private val sharedPrefHelper: SharedPrefHelper
) : AdBlockHostsRepository {
    override suspend fun initialize(isUpdate: Boolean): Boolean {
        val isPopulated = sharedPrefHelper.getIsPopulated()

        if (isUpdate) {
            val freshHosts = fetchHosts()

            if (freshHosts.isNotEmpty()) {
                localDataSource.addHosts(freshHosts)
            }
            val remoteInitialized = freshHosts.isNotEmpty()

            if (!isPopulated && !remoteInitialized) {
                localDataSource.initialize(true)

                return false
            }

            return remoteInitialized
        } else {
            return localDataSource.initialize(false)
        }
    }

    override suspend fun fetchHosts(): Set<AdHost> {
        return remoteDataSource.fetchHosts()
    }

    override fun isAds(url: String): Boolean {
        return localDataSource.isAds(url)
    }

    override suspend fun addHosts(hosts: Set<AdHost>) {
        localDataSource.addHosts(hosts)
    }

    override suspend fun removeHosts(hosts: Set<AdHost>) {
        localDataSource.removeHosts(hosts)
    }

    override suspend fun addHost(host: AdHost) {
        localDataSource.addHost(host)
    }

    override suspend fun removeHost(host: AdHost) {
        localDataSource.removeHost(host)
    }

    override suspend fun removeAllHost() {
        localDataSource.removeAllHost()
    }

    override suspend fun getHostsCount(): Int {
        return localDataSource.getHostsCount()
    }

    override fun getCachedCount(): Int {
        return localDataSource.getCachedCount()
    }
}
