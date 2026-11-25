package com.myAllVideoBrowser.ui.component.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.github.marlonlom.utilities.timeago.TimeAgo
import com.github.marlonlom.utilities.timeago.TimeAgoMessages
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.databinding.ItemHistorySearchBinding
import java.util.*

class HistorySearchAdapter(
    private var historyItems: List<HistoryItem>,
    private var historyListener: HistoryListener
) : RecyclerView.Adapter<HistorySearchAdapter.HistorySearchViewHolder>() {

    class HistorySearchViewHolder(val binding: ItemHistorySearchBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(historyItem: HistoryItem, historyListener: HistoryListener) {
            with(binding)
            {
                this.historyItem = historyItem
                this.historyListener = historyListener
                this.historyId = historyItem.id
                this.tvTime.text = convertTimeToTimeAgo(historyItem.datetime)

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistorySearchViewHolder {
        val binding = DataBindingUtil.inflate<ItemHistorySearchBinding>(
            LayoutInflater.from(parent.context),
            R.layout.item_history_search, parent, false
        )

        return HistorySearchViewHolder(binding)
    }

    override fun getItemCount() = historyItems.size

    override fun onBindViewHolder(holder: HistorySearchViewHolder, position: Int) =
        holder.bind(historyItems[position], historyListener)

    fun setData(newHistoryItems: List<HistoryItem>) {
        val reversedItems = newHistoryItems.asReversed()
        val oldList = this.historyItems
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = reversedItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].id == reversedItems[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition] == reversedItems[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        this.historyItems = reversedItems
        diffResult.dispatchUpdatesTo(this)
    }
}
