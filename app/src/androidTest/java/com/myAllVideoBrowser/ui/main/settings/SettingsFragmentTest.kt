package com.myAllVideoBrowser.ui.main.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.IntentUtil
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.SystemUtil
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import util.ViewModelUtil
import util.rule.InjectedFragmentTestRule
import java.io.File

class SettingsFragmentTest {

    private lateinit var fileUtil: FileUtil

    private lateinit var systemUtil: SystemUtil

    private lateinit var intentUtil: IntentUtil

    private lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var settingsFragment: SettingsFragment

    private lateinit var settingsViewModel: SettingsViewModel

    private lateinit var clearCookiesEvent: SingleLiveEvent<Void?>

    private lateinit var openVideoFolderEvent: SingleLiveEvent<Void?>

    private lateinit var file: File

    private val screen = Screen()

    private val path = "videoPath"

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val uiRule = InjectedFragmentTestRule<SettingsFragment> {
        it.fileUtil = fileUtil
        it.systemUtil = systemUtil
        it.intentUtil = intentUtil
        it.viewModelFactory = viewModelFactory
    }

    @Before
    fun setup() {
        fileUtil = mock()
        systemUtil = mock()
        intentUtil = mock()
        file = mock()
        settingsViewModel = mock()
        clearCookiesEvent = SingleLiveEvent()
        openVideoFolderEvent = SingleLiveEvent()
        viewModelFactory = ViewModelUtil.createFor(settingsViewModel)
        doReturn(clearCookiesEvent).`when`(settingsViewModel).clearCookiesEvent
        doReturn(openVideoFolderEvent).`when`(settingsViewModel).openVideoFolderEvent
        doReturn(file).`when`(fileUtil).folderDir
        doReturn(path).`when`(file).path
    }

    @Test
    fun initial_ui() {
        screen.start()
        screen.hasSettings()
    }

    @Test
    fun clear_cookies() {
        screen.start()
        clearCookiesEvent.call()
        verify(systemUtil).clearCookies(uiRule.fragment.context)
    }

    @Test
    fun open_video_folder() {
        screen.start()
        openVideoFolderEvent.call()
        verify(intentUtil).openVideoFolder(uiRule.fragment.context, path)
    }

    inner class Screen {
        fun start() {
            settingsFragment = SettingsFragment()
            uiRule.launchFragment(settingsFragment)
        }

        fun hasSettings() {
            onView(withId(R.id.tv_general)).check(matches(isDisplayed()))
            onView(withId(R.id.layout_folder)).check(matches(isDisplayed()))
            onView(withId(R.id.layout_clear_cookie)).check(matches(isDisplayed()))
        }
    }
}