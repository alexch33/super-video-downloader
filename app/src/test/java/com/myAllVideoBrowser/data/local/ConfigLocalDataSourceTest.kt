package com.myAllVideoBrowser.data.local

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.myAllVideoBrowser.data.local.room.dao.ConfigDao
import com.myAllVideoBrowser.data.local.room.entity.SupportedPage
import io.reactivex.rxjava3.core.Maybe
//import io.reactivex.Maybe
import org.junit.Before
import org.junit.Test

class ConfigLocalDataSourceTest {

    private lateinit var configDao: ConfigDao

    private lateinit var configLocalDataSource: ConfigLocalDataSource

    private lateinit var supportedPages: List<SupportedPage>

    @Before
    fun setup() {
        configDao = mock()
        configLocalDataSource = ConfigLocalDataSource(configDao)
        supportedPages = listOf(SupportedPage(id = "id1", name = "name1"), SupportedPage(id = "id2", name = "name2"))
    }

    @Test
    fun `test get supported pages`() {
        doReturn(Maybe.just(supportedPages)).`when`(configDao).getSupportedPages()

        configLocalDataSource.getSupportedPages()
            .test()
            .assertNoErrors()
            .assertValue { it == supportedPages }
    }

    @Test
    fun `test save supported pages`() {
        configLocalDataSource.saveSupportedPages(supportedPages)
        verify(configDao).insertSupportedPage(supportedPages[0])
        verify(configDao).insertSupportedPage(supportedPages[1])
    }
}