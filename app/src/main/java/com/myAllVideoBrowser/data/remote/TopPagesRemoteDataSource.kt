package com.myAllVideoBrowser.data.remote

import android.net.Uri
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.remote.service.ConfigService
import com.myAllVideoBrowser.data.repository.TopPagesRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopPagesRemoteDataSource @Inject constructor(
    private val configService: ConfigService
) : TopPagesRepository {

    override suspend fun getTopPages(): List<PageInfo> {
        val result = arrayListOf<PageInfo>()
        result.add(PageInfo(link = "https://www.imdb.com"))
        result.add(PageInfo(link = "https://www.tiktok.com"))
        result.add(PageInfo(link = "https://www.vimeo.com/watch"))
        result.add(PageInfo(link = "https://www.facebook.com/watch"))
        result.add(PageInfo(link = "https://www.instagram.com"))
        result.add(PageInfo(link = "https://www.twitter.com"))
        result.add(PageInfo(link = "https://www.bilibili.com"))
        result.add(PageInfo(link = "https://www.dailymotion.com"))

        for (page in result) {
            page.name = Uri.parse(page.link).host.toString()
        }

        return result
    }

    override fun saveTopPage(pageInfo: PageInfo) {
    }

    override fun deletePageInfo(pageInfo: PageInfo) {
    }

    override suspend fun updateLocalStorage() {

    }
}