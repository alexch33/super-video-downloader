package com.myAllVideoBrowser.ui.main.settings.adblock

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.data.local.room.entity.AdBlockList
import com.myAllVideoBrowser.databinding.ItemAdblockListBinding

class AdBlockListAdapter(
    private val viewModel: AdBlockSettingsViewModel
) : ListAdapter<AdBlockList, AdBlockListAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAdblockListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position), viewModel)
    }

    class ViewHolder(private val binding: ItemAdblockListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AdBlockList, viewModel: AdBlockSettingsViewModel) {
            binding.list = item
            binding.viewModel = viewModel
            binding.executePendingBindings()
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<AdBlockList>() {
        override fun areItemsTheSame(oldItem: AdBlockList, newItem: AdBlockList): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: AdBlockList, newItem: AdBlockList): Boolean {
            return oldItem == newItem
        }
    }
}
