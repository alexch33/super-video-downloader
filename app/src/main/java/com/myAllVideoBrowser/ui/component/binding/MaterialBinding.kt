package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

object MaterialBinding {
    @BindingAdapter("icon")
    @JvmStatic
    fun setIconResource(button: MaterialButton, resourceId: Int) {
        if (resourceId != 0) {
            button.setIconResource(resourceId)
        } else {
            button.icon = null
        }
    }

    @BindingAdapter("cardStrokeWidth")
    @JvmStatic
    fun setCardStrokeWidth(cardView: MaterialCardView, strokeWidth: Float) {
        cardView.strokeWidth = strokeWidth.roundToInt()
    }
}