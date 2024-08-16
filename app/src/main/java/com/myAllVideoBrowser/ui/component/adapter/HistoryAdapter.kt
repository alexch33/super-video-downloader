package com.myAllVideoBrowser.ui.component.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.marlonlom.utilities.timeago.TimeAgo
import com.github.marlonlom.utilities.timeago.TimeAgoMessages
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.databinding.ItemHistoryBinding
import com.myAllVideoBrowser.util.ContextUtils
import java.util.*

interface HistoryListener {
    fun onHistoryOpenClicked(view: View, id: String)

    fun onHistoryDeleteClicked(view: View, id: String)

    fun onMenuClicked(view: View, id: String)

    fun onAllHistoryDeleteClicked()
}

class HistoryAdapter(
    private var historyItems: List<HistoryItem>,
    private var historyListener: HistoryListener
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    class HistoryViewHolder(val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(historyItem: HistoryItem, historyListener: HistoryListener) {
            with(binding)
            {
                this.historyItem = historyItem
                this.historyListener = historyListener
                this.historyId = historyItem.id
                this.tvTime.text = convertTimeToTimeAgo(historyItem.datetime)

                if (historyItem.faviconBitmap() == null) {
                    val bm =
                        AppCompatResources.getDrawable(
                            ContextUtils.getApplicationContext(),
                            R.drawable.ic_browser
                        )

                    this.favicon.setImageDrawable(bm)
                }

                executePendingBindings()
            }
        }

        private fun convertTimeToTimeAgo(time: Long): String {
            val locale: Locale = Locale.getAvailableLocales().first()
            val messages: TimeAgoMessages =
                TimeAgoMessages.Builder().withLocale(locale).build()

            return TimeAgo.using(time, messages)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = DataBindingUtil.inflate<ItemHistoryBinding>(
            LayoutInflater.from(parent.context),
            R.layout.item_history, parent, false
        )

        return HistoryViewHolder(binding)
    }

    override fun getItemCount() = historyItems.size

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) =
        holder.bind(historyItems[position], historyListener)

    fun setData(historyItems: List<HistoryItem>) {
        this.historyItems = historyItems
        notifyDataSetChanged()
    }
}
