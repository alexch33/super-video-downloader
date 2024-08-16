package com.myAllVideoBrowser.ui.main.home

import android.app.Activity
import android.app.Instrumentation
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModelProvider
import androidx.databinding.ObservableArrayList
import androidx.databinding.ObservableBoolean
import androidx.databinding.ObservableField
import androidx.databinding.ObservableInt
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.*
import com.nhaarman.mockito_kotlin.*
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserViewModel
import com.myAllVideoBrowser.ui.main.player.VideoPlayerActivity
import com.myAllVideoBrowser.ui.main.player.VideoPlayerFragment
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.SingleLiveEvent
import io.reactivex.rxjava3.subjects.PublishSubject
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import util.RecyclerViewMatcher.Companion.recyclerViewWithId
import util.RecyclerViewUtil.recyclerViewSizeIs
import util.ViewModelUtil
import util.rule.InjectedFragmentTestRule
import util.waitUntil
import java.util.concurrent.Callable

class BrowserFragmentTest {

    private lateinit var mainActivity: MainActivity

    private lateinit var appUtil: AppUtil

    private lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var browserFragment: BrowserFragment

    private lateinit var browserViewModel: BrowserViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var publishSubject: PublishSubject<String>

    private lateinit var suggestions: List<Suggestion>

    private lateinit var pageInfo1: PageInfo

    private lateinit var pageInfo2: PageInfo

    private lateinit var videoInfo: VideoInfo

    private val listPages = ObservableArrayList<PageInfo>()

    private val listSuggestions = ObservableArrayList<Suggestion>()

    private val changeFocusEvent = SingleLiveEvent<Boolean>()

    private val pressBackBtnEvent = SingleLiveEvent<Void?>()

    private val showDownloadDialogEvent = SingleLiveEvent<VideoInfo>()

    private val downloadVideoEvent = SingleLiveEvent<VideoInfo>()

    private val pageUrl = ObservableField<String>("")

    private val textInput = ObservableField<String>("")

    private val isShowPage = ObservableBoolean(false)

    private val isFocus = ObservableBoolean(false)

    private val isShowFabBtn = ObservableBoolean(false)

    private val isExpandedAppbar = ObservableBoolean(true)

    private val isShowProgress = ObservableBoolean(false)

    private val progress = ObservableInt(0)

    private val screen = Screen()

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val uiRule = InjectedFragmentTestRule<BrowserFragment> {
        it.mainActivity = mainActivity
        it.appUtil = appUtil
        it.viewModelFactory = viewModelFactory
    }

    @Before
    fun setup() {
        mainActivity = mock()
        appUtil = mock()
        mainViewModel = mock()
        browserViewModel = mock()
        publishSubject = PublishSubject.create()

        doReturn(mainViewModel).`when`(mainActivity).mainViewModel
        doReturn(downloadVideoEvent).`when`(mainViewModel).downloadVideoEvent
        doReturn(pressBackBtnEvent).`when`(mainViewModel).pressBackBtnEvent

        viewModelFactory = ViewModelUtil.createFor(browserViewModel)
        doReturn(publishSubject).`when`(browserViewModel).publishSubject
        doReturn(changeFocusEvent).`when`(browserViewModel).changeSearchFocusEvent
        doReturn(showDownloadDialogEvent).`when`(browserViewModel).showDownloadDialogEvent

        videoInfo = VideoInfo(title = "title", ext = "ext", downloadUrl = "https://download_url")
        suggestions = listOf(Suggestion(content = "https://video1"), Suggestion(content = "https://video2"))
        pageInfo1 = PageInfo(name = "Facebook", link = "https://facebook.com")
        pageInfo2 = PageInfo(name = "Youtube", link = "https://youtube.com")
        listPages.addAll(listOf(pageInfo1, pageInfo2))
        doReturn(listPages).`when`(browserViewModel).listPages
        doReturn(listSuggestions).`when`(browserViewModel).listSuggestions
        doReturn(pageUrl).`when`(browserViewModel).pageUrl
        doReturn(textInput).`when`(browserViewModel).textInput
        doReturn(isShowPage).`when`(browserViewModel).isShowPage
        doReturn(isFocus).`when`(browserViewModel).isTabInputFocused
//        doReturn(isShowFabBtn).`when`(browserViewModel).isShowFabBtn
        doReturn(isExpandedAppbar).`when`(browserViewModel).isExpandedAppbar
        doReturn(isShowProgress).`when`(browserViewModel).isShowProgress
        doReturn(progress).`when`(browserViewModel).progress
    }

    @Test
    fun show_list_top_pages() {
        screen.start()
        screen.hasToolbar(true)

        waitUntil("Wait for rendering UI", Callable {
            verify(browserViewModel).start()
            screen.verifyListSize(2)
            screen.verifyListPages()
            true
        }, 500)
    }

    @Test
    fun open_a_page_in_top_pages() {
        screen.start()
        screen.openTopPage(0)
        verify(browserViewModel).loadPage(pageInfo1.link)
        screen.loadPage(pageInfo1.link)

        waitUntil("Wait for rendering UI", Callable {
            screen.hasTopPages(false)
            screen.hasWebView(true)
            verify(appUtil).hideSoftKeyboard(any())
            true
        }, 500)
    }

    @Test
    fun type_a_page_url_and_press_enter_to_load_the_page() {
        val link = "https://facebook.com"
        screen.start()
        isFocus.set(true)
        screen.typeKeywordAndPressDoneButton(link)

        waitUntil("Wait for rendering UI", Callable {
            assertEquals(link, browserViewModel.textInput.get())
            verify(browserViewModel, times(link.length)).showSuggestions()
            verify(browserViewModel, atLeastOnce()).loadPage(link)
            true
        }, 500)
    }

    @Test
    fun type_a_keyword_should_show_list_suggested_pages() {
        val keyword = "video"
        screen.start()
        isFocus.set(true)
        screen.typeKeyword(keyword)
        listSuggestions.addAll(suggestions)

        assertEquals(keyword, browserViewModel.textInput.get())
        verify(browserViewModel, times(keyword.length)).showSuggestions()
        screen.verifyListSuggestions(suggestions)

        val url = suggestions[0].content
        screen.openSuggestedPage(url)
        verify(browserViewModel).loadPage(url)
    }

    @Test
    fun show_download_video_dialog_and_preview_video() {
        screen.start()
        screen.loadPage("https://facebook.com")
        showDownloadDialogEvent.value = videoInfo

        screen.hasDownloadVideoDialog()
        screen.previewVideo()
        screen.checkDownloadVideoDialogIsDismissed()
        screen.verifyOpenVideoPlayerScreen(videoInfo)
    }

    @Test
    fun show_download_video_dialog_and_download_video() {
        screen.start()
        screen.loadPage("https://facebook.com")
        showDownloadDialogEvent.value = videoInfo

        screen.hasDownloadVideoDialog()
        screen.downloadVideo()
        screen.checkDownloadVideoDialogIsDismissed()
        assertEquals(videoInfo, mainViewModel.downloadVideoEvent.value)
    }

    @Test
    fun show_download_video_dialog_and_cancel_dialog() {
        screen.start()
        screen.loadPage("https://facebook.com")
        showDownloadDialogEvent.value = videoInfo

        screen.hasDownloadVideoDialog()
        screen.cancelDownloadVideoDialog()
        screen.checkDownloadVideoDialogIsDismissed()
    }

    @Test
    fun webview_loaded_only_one_page_then_press_back_button_should_open_top_pages() {
        screen.start()
        screen.loadPage("https://facebook.com")
        pressBackBtnEvent.call()

        waitUntil("Wait for updating data", Callable {
            assertFalse(isShowPage.get())
            true
        }, 500)
    }

    @Test
    fun top_pages_screen_is_visible_then_press_back_should_close_app() {
        screen.start()
        pressBackBtnEvent.call()

        waitUntil("Wait for finishing activity", Callable {
            assertTrue(uiRule.activity.isFinishing)
            true
        }, 500)
    }

    inner class Screen {
        fun start() {
            browserFragment = BrowserFragment()
            uiRule.launchFragment(browserFragment)
        }

        fun verifyListSize(size: Int) {
            onView(withId(R.id.top_pages_grid)).check(matches(recyclerViewSizeIs(size)))
        }

        fun verifyListPages() {
            onView(recyclerViewWithId(R.id.top_pages_grid).atPositionOnView(0, R.id.tv_name))
                .check(matches(withText(pageInfo1.name)))
            onView(recyclerViewWithId(R.id.top_pages_grid).atPositionOnView(1, R.id.tv_name))
                .check(matches(withText(pageInfo2.name)))
        }

        fun verifyListSuggestions(suggestions: List<Suggestion>) {
            suggestions.map { suggestion ->
                onView(withText(suggestion.content)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
            }
        }

        fun openSuggestedPage(content: String) {
            onView(withText(content)).inRoot(isPlatformPopup()).perform(click())
        }

        fun openTopPage(position: Int) {
            onView(recyclerViewWithId(R.id.top_pages_grid).atPosition(position)).perform(click())
        }

        fun hasTopPages(isShown: Boolean) {
            onView(withId(R.id.top_pages_grid)).check(matches(if (isShown) isDisplayed() else not(isDisplayed())))
        }

        fun hasWebView(isShown: Boolean) {
            onView(withId(R.id.webview)).check(matches(if (isShown) isDisplayed() else not(isDisplayed())))
        }

        fun hasToolbar(isShown: Boolean) {
            onView(withId(R.id.toolbar)).check(matches(if (isShown) isDisplayed() else not(isDisplayed())))
        }

        fun typeKeyword(keyword: String) {
            onView(withId(R.id.et_search)).perform(typeText(keyword))
        }

        fun typeKeywordAndPressDoneButton(keyword: String) {
            onView(withId(R.id.et_search)).perform(typeText(keyword), pressImeActionButton())
        }

        fun hasDownloadVideoDialog() {
            onView(withId(R.id.tv_title)).inRoot(isDialog())
                .check(matches(allOf(withText(R.string.dialog_video_title), isDisplayed())))
            onView(withId(R.id.tv_preview)).inRoot(isDialog())
                .check(matches(allOf(withText(R.string.dialog_preview_video), isDisplayed())))
            onView(withId(R.id.tv_download)).inRoot(isDialog())
                .check(matches(allOf(withText(R.string.dialog_download_video), isDisplayed())))
            onView(withId(R.id.tv_cancel)).inRoot(isDialog())
                .check(matches(allOf(withText(R.string.dialog_download_cancel), isDisplayed())))
        }

        fun checkDownloadVideoDialogIsDismissed() {
            onView(withText(R.string.dialog_video_title)).check(doesNotExist())
            onView(withText(R.string.dialog_preview_video)).check(doesNotExist())
            onView(withText(R.string.dialog_download_video)).check(doesNotExist())
            onView(withText(R.string.dialog_download_cancel)).check(doesNotExist())
        }

        fun previewVideo() {
            intending(hasComponent(VideoPlayerActivity::class.java.name))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            onView(withId(R.id.tv_preview)).inRoot(isDialog()).perform(click())
        }

        fun downloadVideo() {
            onView(withId(R.id.tv_download)).inRoot(isDialog()).perform(click())
        }

        fun cancelDownloadVideoDialog() {
            onView(withId(R.id.tv_cancel)).inRoot(isDialog()).perform(click())
        }

        fun verifyOpenVideoPlayerScreen(videoInfo: VideoInfo) {
            intended(
                allOf(
                    hasComponent(VideoPlayerActivity::class.java.name),
                    hasExtra(VideoPlayerFragment.VIDEO_URL, videoInfo.downloadUrl),
                    hasExtra(VideoPlayerFragment.VIDEO_NAME, videoInfo.name)
                )
            )
        }

        fun loadPage(url: String) {
            isShowPage.set(true)
            isFocus.set(false)
            changeFocusEvent.value = false
            pageUrl.set(url)
        }
    }
}