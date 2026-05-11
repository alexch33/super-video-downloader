package com.myAllVideoBrowser.ui.main.base

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.RelativeLayout
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import dagger.android.support.DaggerFragment

abstract class BaseFragment : DaggerFragment() {
    fun getThemeBackgroundColor(): Int {
        val color =
            MaterialColors.getColor(requireContext(), R.attr.colorSurface, Color.YELLOW)
        return color
    }
}