package com.myAllVideoBrowser.util.fragment

import androidx.fragment.app.Fragment
import com.myAllVideoBrowser.geckoview_example.GeckoViewFragment
import com.myAllVideoBrowser.ui.main.history.HistoryFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.homeTab.BrowserHomeFragment
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.DetectedVideosTabFragment
import com.myAllVideoBrowser.ui.main.progress.ProgressFragment
import com.myAllVideoBrowser.ui.main.settings.SettingsFragment
import com.myAllVideoBrowser.ui.main.video.VideoFragment
import javax.inject.Inject

interface FragmentFactory {
    fun createBrowserFragment(): Fragment
    fun createProgressFragment(): Fragment
    fun createVideoFragment(): Fragment
    fun createSettingsFragment(): Fragment
    fun createHistoryFragment(): Fragment

    fun createBrowserHomeFragment(): Fragment

    fun createWebTabFragment(): Fragment

    fun createDetectedVideosTabFragment(): Fragment

    fun createGeckoViewFragment(): Fragment
}

class FragmentFactoryImpl @Inject constructor() : FragmentFactory {
    override fun createBrowserFragment() = BrowserFragment.newInstance()

    override fun createProgressFragment() = ProgressFragment.newInstance()

    override fun createVideoFragment() = VideoFragment.newInstance()

    override fun createSettingsFragment() = SettingsFragment.newInstance()

    override fun createHistoryFragment() = HistoryFragment.newInstance()

    override fun createBrowserHomeFragment() = BrowserHomeFragment.newInstance()

    override fun createWebTabFragment() = WebTabFragment.newInstance()

    override fun createDetectedVideosTabFragment() = DetectedVideosTabFragment.newInstance()

    override fun createGeckoViewFragment() = GeckoViewFragment.newInstance(null);
}