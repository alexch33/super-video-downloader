package com.myAllVideoBrowser.ui.main.home

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import androidx.databinding.ObservableField
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.util.SingleLiveEvent
import com.myAllVideoBrowser.util.fragment.FragmentFactory
import com.myAllVideoBrowser.util.fragment.StubbedFragmentFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import util.ViewModelUtil
import util.rule.InjectedActivityTestRule

class MainActivityTest {

    private lateinit var fragmentFactory: FragmentFactory

    private lateinit var viewModelFactory: ViewModelProvider.Factory

    private lateinit var mainViewModel: MainViewModel

    private val pressBackBtnEvent = SingleLiveEvent<Void?>()

    private val currentItem = ObservableField<Int>()

    private val screen = Screen()

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val uiRule = InjectedActivityTestRule(MainActivity::class.java) {
        it.viewModelFactory = viewModelFactory
        it.fragmentFactory = fragmentFactory
    }

    @Before
    fun setup() {
        fragmentFactory = StubbedFragmentFactory()
        mainViewModel = mock()
        viewModelFactory = ViewModelUtil.createFor(mainViewModel)
        doReturn(pressBackBtnEvent).`when`(mainViewModel).pressBackBtnEvent
        doReturn(currentItem).`when`(mainViewModel).currentItem
    }

    @Test
    fun initial_ui() {
        screen.start()
        screen.hasBottomBar()
    }

    @Test
    fun test_press_back_button() {
        screen.start()
        screen.swipeLeftViewPager()

        assertEquals(1, currentItem.get())

        screen.pressBackButton()

        assertEquals(0, currentItem.get())

        screen.pressBackButton()

        assertNull(pressBackBtnEvent.value)
    }

    @Test
    fun test_press_bottom_bar() {
        screen.start()
        screen.openProgressTab()

        assertEquals(1, currentItem.get())

        screen.openVideoTab()

        assertEquals(2, currentItem.get())

        screen.openSettingsTab()

        assertEquals(3, currentItem.get())

        screen.openBrowserTab()

        assertEquals(0, currentItem.get())
    }

    inner class Screen {

        fun start() {
            uiRule.launchActivity(Intent())
        }

        fun pressBackButton() {
            uiRule.activity.onBackPressed()
        }

        fun hasBottomBar() {
            onView(withId(R.id.bottom_bar)).check(matches(isDisplayed()))
            onView(withId(R.id.tab_browser)).check(matches(isDisplayed()))
            onView(withId(R.id.tab_progress)).check(matches(isDisplayed()))
            onView(withId(R.id.tab_video)).check(matches(isDisplayed()))
            onView(withId(R.id.tab_settings)).check(matches(isDisplayed()))
        }

        fun swipeLeftViewPager() {
            onView(withId(R.id.view_pager)).perform(swipeLeft())
        }

        fun openBrowserTab() {
            onView(withId(R.id.tab_browser)).perform(click())
        }

        fun openProgressTab() {
            onView(withId(R.id.tab_progress)).perform(click())
        }

        fun openVideoTab() {
            onView(withId(R.id.tab_video)).perform(click())
        }

        fun openSettingsTab() {
            onView(withId(R.id.tab_settings)).perform(click())
        }
    }
}