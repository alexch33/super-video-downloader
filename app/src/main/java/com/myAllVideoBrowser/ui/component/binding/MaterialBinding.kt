package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import com.google.android.material.button.MaterialButton

object MaterialBinding {
    @BindingAdapter("app:icon")
    @JvmStatic
    fun setIconResource(button: MaterialButton, resourceId: Int) {
        if (resourceId != 0) {
            button.setIconResource(resourceId)
        } else {
            button.icon = null
        }
    }
}