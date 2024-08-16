package com.myAllVideoBrowser.ui.component.binding

import androidx.annotation.OptIn
import androidx.databinding.BindingAdapter
import androidx.media3.common.util.UnstableApi
//import androidx.viewpager2.widget.ViewPager2
import com.myAllVideoBrowser.ui.main.home.browser.BrowserFragment
import com.myAllVideoBrowser.ui.main.home.browser.CustomViewPager2
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab

object CustomViewPager2Binding {

    @OptIn(UnstableApi::class)
    @BindingAdapter("app:items")
    @JvmStatic
    fun CustomViewPager2.setWebItems(currentItems: List<WebTab>?) {
        with(adapter as BrowserFragment.TabsFragmentStateAdapter?) {
            this?.setRoutes(currentItems ?: emptyList())
        }
    }

    @BindingAdapter("app:offScreenPageLimit")
    @JvmStatic
    fun CustomViewPager2.setOffScreenPageLimit(pageLimit: Int) {
        offscreenPageLimit = pageLimit
    }

    @BindingAdapter("app:currentItem")
    @JvmStatic
    fun CustomViewPager2.setCurrentItem(currentItemPosition: Int) {
        currentItem = currentItemPosition
    }
}
