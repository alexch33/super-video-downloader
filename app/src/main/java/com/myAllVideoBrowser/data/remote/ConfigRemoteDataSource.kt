package com.myAllVideoBrowser.data.remote

import com.myAllVideoBrowser.data.local.room.entity.SupportedPage
import com.myAllVideoBrowser.data.remote.service.ConfigService
import com.myAllVideoBrowser.data.repository.ConfigRepository
import io.reactivex.rxjava3.core.Flowable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConfigRemoteDataSource @Inject constructor(
    private val configService: ConfigService
) : ConfigRepository {

    override fun getSupportedPages(): Flowable<List<SupportedPage>> {
        return configService.getSupportedPages()
    }

    override fun saveSupportedPages(supportedPages: List<SupportedPage>) {
    }
}