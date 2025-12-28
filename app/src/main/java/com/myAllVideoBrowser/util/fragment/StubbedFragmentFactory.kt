package com.myAllVideoBrowser.util.fragment

import androidx.fragment.app.Fragment

class StubbedFragmentFactory : FragmentFactory {
    override fun createBrowserFragment() = Fragment()

    override fun createProgressFragment() = Fragment()

    override fun createVideoFragment() = Fragment()

    override fun createSettingsFragment() = Fragment()

    override fun createHistoryFragment() = Fragment()

    override fun createBrowserHomeFragment() = Fragment()

    override fun createWebTabFragment() = Fragment()
    override fun createDetectedVideosTabFragment() = Fragment()
    override fun createGeckoViewFragment() = Fragment()
}