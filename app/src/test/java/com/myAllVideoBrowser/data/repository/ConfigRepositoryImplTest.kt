package com.myAllVideoBrowser.data.repository

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.myAllVideoBrowser.data.local.ConfigLocalDataSource
import com.myAllVideoBrowser.data.local.room.entity.SupportedPage
import com.myAllVideoBrowser.data.remote.ConfigRemoteDataSource
import io.reactivex.rxjava3.core.Flowable
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ConfigRepositoryImplTest {

    private lateinit var localData: ConfigLocalDataSource

    private lateinit var remoteData: ConfigRemoteDataSource

    private lateinit var configRepository: ConfigRepositoryImpl

    private lateinit var supportedPage1: SupportedPage

    private lateinit var supportedPage2: SupportedPage

    private lateinit var supportedPages: List<SupportedPage>

    @Before
    fun setup() {
        localData = mock()
        remoteData = mock()
        configRepository = ConfigRepositoryImpl(localData, remoteData)

        supportedPage1 = SupportedPage(id = "id1")
        supportedPage2 = SupportedPage(id = "id2")
        supportedPages = listOf(supportedPage1, supportedPage2)
    }

    @Test
    fun `save config info into cache, local and remote source`() {
        configRepository.saveSupportedPages(supportedPages)

        assertEquals(supportedPages, configRepository.cachedSupportedPages)
        verify(remoteData).saveSupportedPages(supportedPages)
        verify(localData).saveSupportedPages(supportedPages)
    }

    @Test
    fun `get config from cache`() {
        configRepository.cachedSupportedPages = supportedPages

        configRepository.getSupportedPages().test()
            .assertNoErrors()
            .assertValue { it == supportedPages }
    }

    @Test
    fun `get config from local source should save config to cache`() {
        doReturn(Flowable.just(supportedPages)).`when`(localData).getSupportedPages()

        configRepository.getSupportedPages().test()
            .assertNoErrors()
            .assertValue { it == supportedPages }

        assertEquals(supportedPages, configRepository.cachedSupportedPages)
    }

    @Test
    fun `get config from remote source should save config to cache and local source`() {
        doReturn(Flowable.just(listOf<SupportedPage>())).`when`(localData).getSupportedPages()
        doReturn(Flowable.just(supportedPages)).`when`(remoteData).getSupportedPages()

        configRepository.getSupportedPages().test()
            .assertNoErrors()
            .assertValue { it == supportedPages }

        assertEquals(supportedPages, configRepository.cachedSupportedPages)
        verify(localData).saveSupportedPages(supportedPages)
    }
}