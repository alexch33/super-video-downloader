package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import com.google.android.material.appbar.AppBarLayout

object AppBarBinding {

    @BindingAdapter("app:smoothExpanded")
    @JvmStatic
    fun AppBarLayout.setExpanded(isExpanded: Boolean) {
        setExpanded(isExpanded, true)
    }
}