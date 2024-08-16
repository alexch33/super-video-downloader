package com.myAllVideoBrowser.data.local

import android.net.Uri
import com.myAllVideoBrowser.data.local.room.dao.PageDao
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.repository.TopPagesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopPagesLocalDataSource @Inject constructor(
    private val pageDao: PageDao
) : TopPagesRepository {

    override suspend fun getTopPages(): List<PageInfo> {
        val list1 = arrayListOf<PageInfo>()
        val imdb = Uri.parse("https://www.imdb.com")
        list1.add(PageInfo(link = imdb.toString()))
        list1.add(PageInfo(link = "https://www.tiktok.com"))
        list1.add(PageInfo(link = "https://www.dailymotion.com"))
        list1.add(PageInfo(link = "https://www.facebook.com/watch"))
        list1.add(PageInfo(link = "https://www.instagram.com"))
        list1.add(PageInfo(link = "https://www.twitter.com"))
        list1.add(PageInfo(link = "https://www.pinterest.com/videos"))
        list1.add(PageInfo(link = "https://www.twitch.tv"))

        for (page in list1) {
            page.name = Uri.parse(page.link).host.toString()
        }
        val list2 = pageDao.getPageInfos().blockingFirst()

        val result = arrayListOf<PageInfo>()
        for (item in list1) {
            val daoItem = list2.firstOrNull { it.link == item.link }
            if (daoItem != null) {
                result.add(daoItem)
            } else {
                result.add(item)
            }
        }

        return result
    }

    override fun saveTopPage(pageInfo: PageInfo) {
        pageDao.insertProgressInfo(pageInfo)
    }

    override fun deletePageInfo(pageInfo: PageInfo) {
        pageDao.deleteProgressInfo(pageInfo)
    }

    override suspend fun updateLocalStorage() {

    }
}