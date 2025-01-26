package com.myAllVideoBrowser.data.local

import android.net.Uri
import com.myAllVideoBrowser.data.local.room.dao.PageDao
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.repository.TopPagesRepository
import com.myAllVideoBrowser.util.SharedPrefHelper
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopPagesLocalDataSource @Inject constructor(
    private val pageDao: PageDao,
    private val sharedPrefHelper: SharedPrefHelper
) : TopPagesRepository {

    override suspend fun getTopPages(): List<PageInfo> {
        val localBookmarks = pageDao.getPageInfos().blockingFirst(emptyList())
        if (localBookmarks.isEmpty()) {
            val isFirstStart = sharedPrefHelper.getIsFirstStart()
            if (isFirstStart) {
                val defaultList = getDefaultBookmarks()
                pageDao.insertAllProgressInfo(defaultList)

                return defaultList
            }
        }

        return localBookmarks
    }

    override fun saveTopPage(pageInfo: PageInfo) {
        pageDao.insertProgressInfo(pageInfo)
    }

    override fun replaceBookmarksWith(pageInfos: List<PageInfo>) {
        pageDao.deleteAll()
        pageDao.insertAllProgressInfo(pageInfos)
    }

    override fun deletePageInfo(pageInfo: PageInfo) {
        pageDao.deleteProgressInfo(pageInfo)
    }

    override suspend fun updateLocalStorageFavicons(): Flow<PageInfo> {
        throw NotImplementedError("NO NEED, HANDLED BY REPO")
    }

    private fun getDefaultBookmarks(): List<PageInfo> {
        val defaultList = arrayListOf<PageInfo>()

        defaultList.add(PageInfo(link = "https://www.imdb.com"))
        defaultList.add(PageInfo(link = "https://www.tiktok.com"))
        defaultList.add(PageInfo(link = "https://www.dailymotion.com"))
        defaultList.add(PageInfo(link = "https://www.facebook.com/watch"))
        defaultList.add(PageInfo(link = "https://www.instagram.com"))
        defaultList.add(PageInfo(link = "https://www.twitter.com"))
        defaultList.add(PageInfo(link = "https://www.pinterest.com/videos"))
        defaultList.add(PageInfo(link = "https://www.twitch.tv"))

        return defaultList.mapIndexed { index, page ->
            page.name = Uri.parse(page.link).host.toString()
            page.order = index
            page
        }
    }
}