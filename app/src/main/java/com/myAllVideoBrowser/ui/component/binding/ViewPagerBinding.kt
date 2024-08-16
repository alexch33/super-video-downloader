package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import androidx.viewpager.widget.ViewPager

object ViewPagerBinding {

    @BindingAdapter("app:offScreenPageLimit")
    @JvmStatic
    fun ViewPager.setOffScreenPageLimit(pageLimit: Int) {
        offscreenPageLimit = pageLimit
    }
}