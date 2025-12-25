package com.myAllVideoBrowser.data.repository

import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.di.qualifier.LocalData
import com.myAllVideoBrowser.di.qualifier.RemoteData
import com.myAllVideoBrowser.util.FaviconUtils
import com.myAllVideoBrowser.util.proxy_utils.OkHttpProxyClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

interface TopPagesRepository {
    suspend fun getTopPages(): List<PageInfo>

    fun saveTopPage(pageInfo: PageInfo)

    fun replaceBookmarksWith(pageInfos: List<PageInfo>)

    fun deletePageInfo(pageInfo: PageInfo)

    suspend fun updateLocalStorageFavicons(): Flow<PageInfo>
}

@Singleton
class TopPagesRepositoryImpl @Inject constructor(
    @param:LocalData private val localDataSource: TopPagesRepository,
    private val okHttpClient: OkHttpProxyClient
) : TopPagesRepository {

    override suspend fun getTopPages(): List<PageInfo> {
        return localDataSource.getTopPages()
    }

    override fun saveTopPage(pageInfo: PageInfo) {
        localDataSource.saveTopPage(pageInfo)
    }

    override fun replaceBookmarksWith(pageInfos: List<PageInfo>) {
        localDataSource.replaceBookmarksWith(pageInfos)
    }

    override fun deletePageInfo(pageInfo: PageInfo) {
        localDataSource.deletePageInfo(pageInfo)
    }

    // Returns flow of updated elements
    override suspend fun updateLocalStorageFavicons(): Flow<PageInfo> = callbackFlow {
        val pages = localDataSource.getTopPages()
        for (page in pages) {
            if (page.faviconBitmap() == null) {
                val bitmap = try {
                    FaviconUtils.getEncodedFaviconFromUrl(
                        okHttpClient.getProxyOkHttpClient(), page.link
                    )
                } catch (e: Throwable) {
                    null
                }

                val bitmapBytes = try {
                    FaviconUtils.bitmapToBytes(bitmap)
                } catch (e: Throwable) {
                    null
                }
                page.favicon = bitmapBytes
                localDataSource.saveTopPage(page)
                delay(10)
                trySend(page)
            }
        }

        awaitClose { }
    }
}