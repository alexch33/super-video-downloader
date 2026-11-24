package com.myAllVideoBrowser.ui.component.binding

import android.view.View
import android.view.ViewTreeObserver
import android.widget.AutoCompleteTextView
import androidx.databinding.BindingAdapter
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.ui.component.adapter.SuggestionAdapter
import com.myAllVideoBrowser.ui.component.adapter.TabSuggestionAdapter

object AutoCompleteTextViewBinding {

    @BindingAdapter("app:items")
    @JvmStatic
    fun AutoCompleteTextView.setSuggestions(items: List<*>?) {
        when (val currAdapter = adapter) {
            is SuggestionAdapter -> {
                @Suppress("UNCHECKED_CAST")
                currAdapter.setData((items as? List<Suggestion>) ?: emptyList())
            }
            is TabSuggestionAdapter -> {
                @Suppress("UNCHECKED_CAST")
                currAdapter.setData((items as? List<HistoryItem>) ?: emptyList())
            }
        }
    }

    @BindingAdapter("app:dropDownScreenWidthMargin")
    @JvmStatic
    fun AutoCompleteTextView.setDropDownScreenWidthMargin(marginDp: Float) {
        val updatePopupBounds = {
            val displayMetrics = resources.displayMetrics
            val marginPx = (marginDp * displayMetrics.density).toInt()
            val screenWidth = displayMetrics.widthPixels
            val targetWidth = screenWidth - (marginPx * 2)

            if (targetWidth > 0) {
                dropDownWidth = targetWidth
            }

            val location = IntArray(2)
            getLocationOnScreen(location)
            val viewX = location[0]
            dropDownHorizontalOffset = marginPx - viewX
        }

        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            updatePopupBounds()
        }

        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
                updatePopupBounds()
            }

            override fun onViewDetachedFromWindow(v: View) {
                v.viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
            }
        })

        if (isAttachedToWindow) {
            viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        }

        post(updatePopupBounds)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updatePopupBounds()
        }
    }
}