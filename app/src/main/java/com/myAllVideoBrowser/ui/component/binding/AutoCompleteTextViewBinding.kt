package com.myAllVideoBrowser.ui.component.binding

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
}