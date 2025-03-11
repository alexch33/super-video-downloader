package com.myAllVideoBrowser.di.module.activity

import android.app.Activity
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.myAllVideoBrowser.di.ActivityScoped
import com.myAllVideoBrowser.di.FragmentScoped
import com.myAllVideoBrowser.ui.main.bookmarks.BookmarksFragment
import com.myAllVideoBrowser.ui.main.help.HelpFragment
import com.myAllVideoBrowser.ui.main.history.HistoryFragment
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.MainActivity
import com.myAllVideoBrowser.ui.main.home.browser.homeTab.BrowserHomeFragment
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTabFragment
import com.myAllVideoBrowser.ui.main.home.browser.detectedVideos.DetectedVideosTabFragment
import com.myAllVideoBrowser.ui.main.progress.ProgressFragment
import com.myAllVideoBrowser.ui.main.proxies.ProxiesFragment
import com.myAllVideoBrowser.ui.main.settings.SettingsFragment
import com.myAllVideoBrowser.ui.main.video.VideoFragment
import com.myAllVideoBrowser.util.fragment.FragmentFactory
import com.myAllVideoBrowser.util.fragment.FragmentFactoryImpl
import dagger.Binds
import dagger.Module
import dagger.android.ContributesAndroidInjector

@Module
abstract class MainModule {

    @OptIn(UnstableApi::class)
    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindBrowserFragment(): BrowserFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindProxiesFragment(): ProxiesFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindHistoryFragment(): HistoryFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindHelpFragment(): HelpFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindProgressFragment(): ProgressFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindVideoFragment(): VideoFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindSettingsFragment(): SettingsFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindWebTabFragment(): WebTabFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindDetectedVideosFragment(): DetectedVideosTabFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindBrowserHomeFragment(): BrowserHomeFragment

    @FragmentScoped
    @ContributesAndroidInjector
    abstract fun bindBookmarksFragment(): BookmarksFragment

    @ActivityScoped
    @Binds
    abstract fun bindMainActivity(mainActivity: MainActivity): Activity

    @ActivityScoped
    @Binds
    abstract fun bindFragmentFactory(fragmentFactory: FragmentFactoryImpl): FragmentFactory
}