package com.myAllVideoBrowser.data.remote

import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.remote.service.ConfigService
import com.myAllVideoBrowser.data.repository.TopPagesRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopPagesRemoteDataSource @Inject constructor(
    private val configService: ConfigService
) : TopPagesRepository {

    override suspend fun getTopPages(): List<PageInfo> {
        return emptyList()
    }

    override fun saveTopPage(pageInfo: PageInfo) {
    }

    override fun replaceBookmarksWith(pageInfos: List<PageInfo>) {

    }

    override fun deletePageInfo(pageInfo: PageInfo) {
    }

    override suspend fun updateLocalStorageFavicons() : Flow<PageInfo> {
        throw NotImplementedError("updateLocalStorage for remote storage NOT IMPLEMENTED")
    }
}