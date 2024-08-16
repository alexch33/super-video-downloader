package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import android.widget.AutoCompleteTextView
import com.myAllVideoBrowser.data.local.model.Suggestion
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.ui.component.adapter.SuggestionAdapter
import com.myAllVideoBrowser.ui.component.adapter.TabSuggestionAdapter

object AutoCompleteTextViewBinding {

    @BindingAdapter("app:items")
    @JvmStatic
    fun AutoCompleteTextView.setSuggestions(items: List<Suggestion>?) {
        with(adapter as SuggestionAdapter?) {
            if (items != null) {
                this?.setData(items)
            } else {
                this?.setData(emptyList())
            }
        }
    }

    @BindingAdapter("app:items")
    @JvmStatic
    fun AutoCompleteTextView.setTabSuggestions(items: List<HistoryItem>?) {
        with(adapter as TabSuggestionAdapter?) {
            if (items != null) {
                this?.setData(items)
            } else {
                this?.setData(emptyList())
            }
        }
    }
}