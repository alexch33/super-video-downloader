package com.myAllVideoBrowser.ui.main.video

import android.app.Activity
import android.app.Instrumentation
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModelProvider
import androidx.databinding.ObservableArrayList
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.espresso.matcher.RootMatchers.*
import androidx.test.espresso.matcher.ViewMatchers.*
import com.nhaarman.mockito_kotlin.*
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.ui.main.player.VideoPlayerActivity
import com.myAllVideoBrowser.ui.main.player.VideoPlayerFragment
import com.myAllVideoBrowser.util.AppUtil
import com.myAllVideoBrowser.util.IntentUtil
import com.myAllVideoBrowser.util.SingleLiveEvent
import org.hamcrest.CoreMatchers
import org.hamcrest.CoreMatchers.allOf
import org.hamcrest.CoreMatchers.endsWith
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import util.RecyclerViewMatcher.Companion.recyclerViewWithId
import util.RecyclerViewUtil.recyclerViewSizeIs
import util.ViewModelUtil
import util.rule.InjectedFragmentTestRule
import java.io.File

class VideoFragmentTest {

    private lateinit var intentUtil: IntentUtil

    private lateinit var appUtil: AppUtil

    private lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var videoFragment: VideoFragment

    private lateinit var videoViewModel: VideoViewModel

    private lateinit var renameErrorEvent: SingleLiveEvent<Int>

    private lateinit var localVideo1: LocalVideo

    private lateinit var localVideo2: LocalVideo

    private val localVideos = ObservableArrayList<LocalVideo>()

    private val emptyLocalVideos = ObservableArrayList<LocalVideo>()

    private val screen = Screen()

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val uiRule = InjectedFragmentTestRule<VideoFragment> {
        it.intentUtil = intentUtil
        it.appUtil = appUtil
        it.viewModelFactory = viewModelFactory
    }

    @Before
    fun setup() {
        intentUtil = mock()
        appUtil = mock()
        videoViewModel = mock()
        viewModelFactory = ViewModelUtil.createFor(videoViewModel)

        renameErrorEvent = SingleLiveEvent()
        doReturn(renameErrorEvent).`when`(videoViewModel).renameErrorEvent

        localVideo1 = LocalVideo(uri = File("path1/name1"))
        localVideo2 = LocalVideo(uri = File("path2/name2"))
        localVideos.addAll(listOf(localVideo1, localVideo2))
        doReturn(localVideos).`when`(videoViewModel).localVideos
    }

    @Test
    fun show_empty_screen() {
        doReturn(emptyLocalVideos).`when`(videoViewModel).localVideos
        screen.start()
        verify(videoViewModel).start()
        screen.verifyListSize(0)
        screen.hasEmptyLayout(true)
    }

    @Test
    fun show_list_downloaded_videos() {
        screen.start()
        verify(videoViewModel).start()
        screen.verifyListSize(2)
        screen.verifyListVideos()
        screen.hasEmptyLayout(false)
    }

    @Test
    fun open_video_player_screen() {
        screen.start()
        screen.openVideoAtPosition(0)
        screen.verifyOpenVideoPlayerScreen()
    }

    @Test
    fun click_more_button_should_show_popup_menu() {
        screen.start()
        screen.openPopupMenu(0)
        screen.verifyOpenPopupMenu()
    }

    @Test
    fun delete_video() {
        screen.start()
        screen.openPopupMenu(0)
        screen.deleteVideo()

        verify(videoViewModel).deleteVideo(localVideo1.uri)
    }

    @Test
    fun share_video() {
        screen.start()
        screen.openPopupMenu(0)
        screen.shareVideo()

        verify(intentUtil).shareVideo(any(), eq(localVideo1.uri))
    }

    @Test
    fun rename_video_successfully() {
        screen.start()
        screen.openPopupMenu(0)
        screen.openRenameVideoDialog()
        verify(appUtil).showSoftKeyboard(any())

        val newName = "newName"
        val newFile = File(localVideo1.uri.parent, newName + localVideo1.uri.extension)
        screen.hasRenameVideoDialog(localVideo1.uri.nameWithoutExtension)
        screen.changeName(newName)
        screen.renameVideo()

        verify(appUtil).hideSoftKeyboard(any())
        verify(videoViewModel).renameVideo(any(), eq(localVideo1.uri), eq(newName), eq(newFile))
    }

    @Test
    fun rename_video_failed() {
        screen.start()
        screen.openPopupMenu(0)
        screen.openRenameVideoDialog()
        screen.closeRenameVideoDialog()
        renameErrorEvent.value = VideoViewModel.FILE_INVALID_ERROR_CODE
        screen.verifyShowToast(R.string.video_rename_invalid)
    }

    @Test
    fun rename_video_with_an_existent_name() {
        screen.start()
        screen.openPopupMenu(0)
        screen.openRenameVideoDialog()
        screen.closeRenameVideoDialog()
        renameErrorEvent.value = VideoViewModel.FILE_EXIST_ERROR_CODE
        screen.verifyShowToast(R.string.video_rename_exist)
    }

    inner class Screen {
        fun start() {
            videoFragment = VideoFragment()
            uiRule.launchFragment(videoFragment)
        }

        fun hasEmptyLayout(isShown: Boolean) {
            onView(withId(R.id.layout_empty)).check(matches(if (isShown) isDisplayed() else CoreMatchers.not(isDisplayed())))
        }

        fun verifyListSize(size: Int) {
            onView(withId(R.id.rv_video)).check(matches(recyclerViewSizeIs(size)))
        }

        fun verifyListVideos() {
            onView(recyclerViewWithId(R.id.rv_video).atPositionOnView(0, R.id.tv_name))
                .check(matches(withText(localVideo1.uri.name)))
            onView(recyclerViewWithId(R.id.rv_video).atPositionOnView(1, R.id.tv_name))
                .check(matches(withText(localVideo2.uri.name)))
        }

        fun openPopupMenu(position: Int) {
            onView(recyclerViewWithId(R.id.rv_video).atPositionOnView(position, R.id.iv_more)).perform(click())
        }

        fun verifyOpenPopupMenu() {
            onView(withText(R.string.video_menu_rename)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
            onView(withText(R.string.video_menu_delete)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
            onView(withText(R.string.video_menu_share)).inRoot(isPlatformPopup()).check(matches(isDisplayed()))
        }

        fun openRenameVideoDialog() {
            onView(withText(R.string.video_menu_rename)).inRoot(isPlatformPopup()).perform(click())
        }

        fun deleteVideo() {
            onView(withText(R.string.video_menu_delete)).inRoot(isPlatformPopup()).perform(click())
        }

        fun shareVideo() {
            onView(withText(R.string.video_menu_share)).inRoot(isPlatformPopup()).perform(click())
        }

        fun renameVideo() {
            onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
        }

        fun closeRenameVideoDialog() {
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).perform(click())
        }

        fun changeName(newName: String) {
            onView(withClassName(endsWith("EditText"))).perform(replaceText(newName))
        }

        fun hasRenameVideoDialog(oldName: String) {
            onView(withText(oldName)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(android.R.string.ok)).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText(android.R.string.cancel)).inRoot(isDialog()).check(matches(isDisplayed()))
        }

        fun verifyShowToast(textId: Int) {
            onView(withText(textId)).inRoot(withDecorView(not(uiRule.activity.window.decorView)))
                .check(matches(isDisplayed()))
        }

        fun openVideoAtPosition(position: Int) {
            intending(hasComponent(VideoPlayerActivity::class.java.name))
                .respondWith(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null))
            onView(recyclerViewWithId(R.id.rv_video).atPosition(position)).perform(click())
        }

        fun verifyOpenVideoPlayerScreen() {
            intended(
                allOf(
                    hasComponent(VideoPlayerActivity::class.java.name),
                    hasExtra(VideoPlayerFragment.VIDEO_URL, localVideo1.uri.path),
                    hasExtra(
                        VideoPlayerFragment.VIDEO_NAME, localVideo1.uri.name
                    )
                )
            )
        }
    }
}