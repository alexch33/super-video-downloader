package com.myAllVideoBrowser.ui.main.progress

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModelProvider
import androidx.databinding.ObservableArrayList
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.MainViewModel
import com.myAllVideoBrowser.util.SingleLiveEvent
import org.hamcrest.CoreMatchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import util.RecyclerViewMatcher.Companion.recyclerViewWithId
import util.RecyclerViewUtil.recyclerViewSizeIs
import util.ViewModelUtil
import util.rule.InjectedFragmentTestRule

class ProgressFragmentTest {

    private lateinit var mainActivity: MainActivity

    private lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var progressFragment: ProgressFragment

    private lateinit var progressViewModel: ProgressViewModel

    private lateinit var mainViewModel: MainViewModel

    private lateinit var progressInfo1: ProgressInfo

    private lateinit var progressInfo2: ProgressInfo

    private val downloadVideoEvent = SingleLiveEvent<VideoInfo>()

    private val progressInfos = ObservableArrayList<ProgressInfo>()

    private val screen = Screen()

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val uiRule = InjectedFragmentTestRule<ProgressFragment> {
        it.mainActivity = mainActivity
        it.viewModelFactory = viewModelFactory
    }

    @Before
    fun setup() {
        mainActivity = mock()
        progressViewModel = mock()
        mainViewModel = mock()
        viewModelFactory = ViewModelUtil.createFor(progressViewModel)
        doReturn(mainViewModel).`when`(mainActivity).mainViewModel
        doReturn(downloadVideoEvent).`when`(mainViewModel).downloadVideoEvent

        progressInfo1 = ProgressInfo(id = "id1", videoInfo = VideoInfo(title = "title1", ext = "ext1"))
        progressInfo2 = ProgressInfo(id = "id2", videoInfo = VideoInfo(title = "title2", ext = "ext2"))
    }

    @Test
    fun show_empty_screen() {
        doReturn(progressInfos).`when`(progressViewModel).progressInfos
        screen.start()
        verify(progressViewModel).start()
        screen.verifyListSize(0)
        screen.hasEmptyLayout(true)
    }

    @Test
    fun show_list_downloading_videos() {
        progressInfos.addAll(listOf(progressInfo1, progressInfo2))
        doReturn(progressInfos).`when`(progressViewModel).progressInfos
        screen.start()
        verify(progressViewModel).start()
        screen.verifyListSize(2)
        screen.verifyListProgressInfos()
        screen.hasEmptyLayout(false)
    }

    @Test
    fun observe_download_video_event() {
        progressInfos.addAll(listOf(progressInfo1, progressInfo2))
        doReturn(progressInfos).`when`(progressViewModel).progressInfos
        val videoInfo = VideoInfo(id = "id", downloadUrl = "url")
        screen.start()
        downloadVideoEvent.value = videoInfo
        verify(progressViewModel).downloadVideo(videoInfo)
    }

    inner class Screen {
        fun start() {
            progressFragment = ProgressFragment()
            uiRule.launchFragment(progressFragment)
        }

        fun hasEmptyLayout(isShown: Boolean) {
            onView(withId(R.id.layout_empty)).check(matches(if (isShown) isDisplayed() else not(isDisplayed())))
        }

        fun verifyListSize(size: Int) {
            onView(withId(R.id.rv_progress)).check(matches(recyclerViewSizeIs(size)))
        }

        fun verifyListProgressInfos() {
            onView(recyclerViewWithId(R.id.rv_progress).atPositionOnView(0, R.id.tv_title))
                .check(matches(withText(progressInfo1.videoInfo.name)))
            onView(recyclerViewWithId(R.id.rv_progress).atPositionOnView(1, R.id.tv_title))
                .check(matches(withText(progressInfo2.videoInfo.name)))
        }
    }
}