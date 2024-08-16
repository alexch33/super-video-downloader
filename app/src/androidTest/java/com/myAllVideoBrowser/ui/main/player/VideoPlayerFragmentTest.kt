package com.myAllVideoBrowser.ui.main.player

import androidx.lifecycle.ViewModelProvider
import androidx.databinding.ObservableField
import android.os.Bundle
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.appcompat.widget.Toolbar
import android.widget.ImageButton
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.myAllVideoBrowser.R
import org.hamcrest.CoreMatchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import util.ViewModelUtil
import util.rule.InjectedFragmentTestRule

class VideoPlayerFragmentTest {

    private lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var videoPlayerViewModel: VideoPlayerViewModel

    private lateinit var videoPlayerFragment: VideoPlayerFragment

    private val videoName = ObservableField("")

    private val videoUrl = ObservableField("")

    private lateinit var videoNameTest: String

    private lateinit var videoUrlTest: String

    private val screen = Screen()


    @get:Rule
    val uiRule = InjectedFragmentTestRule<VideoPlayerFragment> {
        it.viewModelFactory = viewModelFactory
    }

    @Before
    fun setup() {
        videoNameTest = "video_name"
        videoUrlTest = "http://video_url"
        videoPlayerViewModel = mock()
        viewModelFactory = ViewModelUtil.createFor(videoPlayerViewModel)
        doReturn(videoName).`when`(videoPlayerViewModel).videoName
        doReturn(videoUrl).`when`(videoPlayerViewModel).videoUrl
    }

    @Test
    fun initial_ui() {
        screen.start()
        screen.hasToolBar()
        screen.hasVideoView()
        screen.hasVideoPlayer()

        assertEquals(videoUrlTest, videoUrl.get())
        assertEquals(videoNameTest, videoName.get())
    }

    @Test
    fun press_back_button_should_close_activity() {
        screen.start()
        screen.pressBackButton()

        assertTrue(uiRule.activity.isFinishing)
    }

    inner class Screen {
        fun start() {
            val bundle = Bundle().apply {
                putString(VideoPlayerFragment.VIDEO_URL, videoUrlTest)
                putString(VideoPlayerFragment.VIDEO_NAME, videoNameTest)
            }
            videoPlayerFragment = VideoPlayerFragment().apply {
                arguments = bundle
            }
            uiRule.launchFragment(videoPlayerFragment)
        }

        fun hasToolBar() {
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
        }

        fun hasVideoView() {
            onView(withId(R.id.video_view)).check(matches(isDisplayed()))
        }

        fun hasVideoPlayer() {
            onView(withId(R.id.tv_current_time)).check(matches(isDisplayed()))
            onView(withId(R.id.seek_bar)).check(matches(isDisplayed()))
            onView(withId(R.id.tv_total_time)).check(matches(isDisplayed()))
            onView(withId(R.id.iv_prev)).check(matches(isDisplayed()))
            onView(withId(R.id.iv_play)).check(matches(isDisplayed()))
            onView(withId(R.id.iv_next)).check(matches(isDisplayed()))
        }

        fun pressBackButton() {
            onView(
                allOf(
                    isAssignableFrom(ImageButton::class.java),
                    withParent(isAssignableFrom(Toolbar::class.java))
                )
            ).perform(click())
        }
    }
}