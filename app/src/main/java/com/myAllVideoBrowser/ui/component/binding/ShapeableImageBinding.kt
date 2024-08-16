package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import com.google.android.material.imageview.ShapeableImageView

object ShapeableImageBinding {
    @BindingAdapter("app:srcCompat")
    @JvmStatic
    fun setImageDrawable(view: ShapeableImageView, drawable: Int) {
        view.setImageResource(drawable)
    }
}