package com.myAllVideoBrowser.ui.main.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.spy
import com.nhaarman.mockito_kotlin.verify
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.room.entity.SupportedPage
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ConfigRepository
import com.myAllVideoBrowser.data.repository.TopPagesRepository
import com.myAllVideoBrowser.data.repository.VideoRepository
import com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel
import com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel.Companion.SEARCH_URL
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import com.myAllVideoBrowser.util.scheduler.StubbedSchedulers
import com.myAllVideoBrowser.waitUntil
import io.reactivex.rxjava3.core.Flowable
//import io.reactivex.Flowable
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Callable
import java.util.regex.Matcher
import java.util.regex.Pattern

class BrowserViewModelTest {

    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var topPagesRepository: TopPagesRepository

    private lateinit var configRepository: ConfigRepository

    private lateinit var videoRepository: VideoRepository

    private lateinit var browserViewModel: BrowserViewModel

    private lateinit var baseSchedulers: BaseSchedulers

    private lateinit var pattern: Pattern

    private lateinit var matcher: Matcher

    private lateinit var url: String

    @Before
    fun setup() {
        topPagesRepository = mock()
        configRepository = mock()
        videoRepository = mock()
        baseSchedulers = StubbedSchedulers()
        pattern = mock()
        matcher = mock()
        browserViewModel = spy(BrowserViewModel())

//        doReturn(Flowable.just(listOf(PageInfo()))).`when`(topPagesRepository).getTopPages()
        url = "http://duckduckgo.com"
    }

    @Test
    fun `test attach and detach BrowserViewModel`() {
        browserViewModel.start()

        assertNotNull(BrowserViewModel.instance)

        browserViewModel.stop()
        assertEquals(null, BrowserViewModel.instance)
    }

//    @Test
//    fun `load page with different inputs`() {
//        browserViewModel.loadPage(url, pattern)
//
//        assertTrue(browserViewModel.isShowPage.get())
//        assertFalse(browserViewModel.isTabInputFocused.get())
//        assertEquals(url, browserViewModel.pageUrl.get())
//
//        url = "google.com"
//        doReturn(matcher).`when`(pattern).matcher(url)
//        doReturn(true).`when`(matcher).matches()
//        browserViewModel.loadPage(url, pattern)
//        assertEquals("http://$url", browserViewModel.pageUrl.get())
//        assertEquals("http://$url", browserViewModel.textInput.get())
//
//        url = "video"
//        doReturn(matcher).`when`(pattern).matcher(url)
//        doReturn(false).`when`(matcher).matches()
//        browserViewModel.loadPage(url, pattern)
//        assertEquals(String.format(SEARCH_URL, url), browserViewModel.pageUrl.get())
//        assertEquals(String.format(SEARCH_URL, url), browserViewModel.textInput.get())
//    }
//
//    @Test
//    fun `start page should update data`() {
//        doReturn(Flowable.just(listOf<SupportedPage>())).`when`(configRepository).getSupportedPages()
//        browserViewModel.start()
//        browserViewModel.startPage(url)
//
//        assertEquals(url, browserViewModel.textInput.get())
//        assertTrue(browserViewModel.isShowPage.get())
//        assertTrue(browserViewModel.isShowProgress.get())
//        assertTrue(browserViewModel.isExpandedAppbar.get())
//        verify(browserViewModel).verifyLinkStatus(url)
//    }
//
//    @Test
//    fun `load resource should update data`() {
//        url = "https://facebook.com"
//        doReturn(Flowable.just(listOf<SupportedPage>())).`when`(configRepository).getSupportedPages()
//        browserViewModel.start()
//        browserViewModel.loadResource(url, url, "")
//
//        assertEquals(url, browserViewModel.textInput.get())
//        assertEquals(ScriptUtil.FACEBOOK_SCRIPT, browserViewModel.pageUrl.get())
//        verify(browserViewModel).verifyLinkStatus(url)
//    }
//
//    @Test
//    fun `finish page should update data`() {
//        doReturn(Flowable.just(listOf<SupportedPage>())).`when`(configRepository).getSupportedPages()
//        browserViewModel.start()
//        browserViewModel.finishPage(url)
//
//        assertEquals(url, browserViewModel.textInput.get())
//        assertFalse(browserViewModel.isShowProgress.get())
//        verify(browserViewModel).verifyLinkStatus(url)
//    }
//
//    @Test
//    fun `verify link status and throw an error should disable fab button`() {
//        doReturn(Flowable.error<Exception>(Throwable("error"))).`when`(configRepository).getSupportedPages()
//        browserViewModel.start()
//        browserViewModel.verifyLinkStatus(url)
//        TODO("make test")
////        assertFalse(browserViewModel.isShowFabBtn.get())
//    }
//
//    @Test
//    fun `verify link status with a valid url should enable fab button`() {
//        doReturn(Flowable.just(listOf(SupportedPage(pattern = url)))).`when`(configRepository).getSupportedPages()
//        browserViewModel.start()
//        browserViewModel.verifyLinkStatus(url)
//
////        assertTrue(browserViewModel.isShowFabBtn.get())
//    }
//
//    @Test
//    fun `get video info failed and throw an error`() {
//        browserViewModel.textInput.set(url)
//        doReturn(Flowable.error<Exception>(Throwable("error"))).`when`(videoRepository).getVideoInfo(url)
//
//        browserViewModel.start()
//        browserViewModel.showVideoInfo()
//
//        assertNull(browserViewModel.showDownloadDialogEvent.value)
//    }
//
//    @Test
//    fun `get video info successfully should show download dialog`() {
//        browserViewModel.textInput.set(url)
//        val videoInfo = VideoInfo(id = "id")
//        doReturn(Flowable.just(videoInfo)).`when`(videoRepository).getVideoInfo(url)
//
//        browserViewModel.start()
//        browserViewModel.showVideoInfo()
//
//        assertEquals(videoInfo, browserViewModel.showDownloadDialogEvent.value)
//    }
//
//    @Test
//    fun `get list suggestions successfully should update data`() {
//        val supportedPages =
//            listOf(SupportedPage(name = "input1"), SupportedPage(name = "input2"), SupportedPage(name = "data3"))
//        doReturn(Flowable.just(supportedPages)).`when`(configRepository).getSupportedPages()
//        browserViewModel.start()
//        browserViewModel.showSuggestions()
//        browserViewModel.publishSubject.onNext("input")
//
//        waitUntil("Wait for emitting search keyword", Callable {
//            assertEquals(2, browserViewModel.listSuggestions.size)
//            assertEquals("input1", browserViewModel.listSuggestions[0].content)
//            assertEquals("input2", browserViewModel.listSuggestions[1].content)
//            true
//        }, 400)
//    }
}