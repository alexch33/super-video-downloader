package com.myAllVideoBrowser.ui.main.settings.adblock

import android.view.View
import android.widget.TextView
import androidx.databinding.BindingAdapter
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.AdBlockList
import com.myAllVideoBrowser.util.DateUtils

object AdBlockBindingAdapters {
    @JvmStatic
    @BindingAdapter("adBlockStatus")
    fun setAdBlockStatus(view: TextView, list: AdBlockList?) {
        list ?: return
        if (list.url == null) {
            view.text = view.context.getString(R.string.built_in_list)
            view.setTextColor(
                MaterialColors.getColor(
                    view,
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                )
            )
            return
        }

        when {
            list.isDownloadFailed -> {
                view.text = "Status: ${view.context.getString(R.string.download_error)}"
                view.setTextColor(
                    MaterialColors.getColor(
                        view,
                        com.google.android.material.R.attr.colorErrorContainer
                    )
                )
            }

            !list.isDownloaded -> {
                view.text = "Status: ${view.context.getString(R.string.not_downloaded)}"
                view.setTextColor(
                    MaterialColors.getColor(
                        view,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
            }

            else -> {
                view.text = "${view.context.getString(R.string.last_updated)}: ${
                    DateUtils.formatDateTime(list.lastUpdated)
                }"
                view.setTextColor(
                    MaterialColors.getColor(
                        view,
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    )
                )
            }
        }
    }

    @JvmStatic
    @BindingAdapter("visibleIf")
    fun setVisibleIf(view: View, condition: Boolean) {
        view.visibility = if (condition) View.VISIBLE else View.GONE
    }
}
