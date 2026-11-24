package com.myAllVideoBrowser.ui.component.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.databinding.ItemProxiesBinding

interface ProxiesListener {
    fun onProxyRemoveClicked(proxy: Proxy)
    fun onProxyToggle(isChecked: Boolean)
}

class ProxiesAdapter(
    private var proxiesList: List<Proxy>,
    private val proxiesListener: ProxiesListener,
) : RecyclerView.Adapter<ProxiesAdapter.ProxiesViewHolder>() {
    class ProxiesViewHolder(val binding: ItemProxiesBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            proxy: Proxy,
            proxiesListener: ProxiesListener,
        ) {
            with(binding)
            {
                this.proxy = proxy
                this.proxiesListener = proxiesListener
                executePendingBindings()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxiesViewHolder {

        val binding = DataBindingUtil.inflate<ItemProxiesBinding>(
            LayoutInflater.from(parent.context), R.layout.item_proxies, parent, false
        )

        return ProxiesViewHolder(binding)
    }

    override fun getItemCount(): Int {
        return proxiesList.size
    }

    override fun onBindViewHolder(holder: ProxiesViewHolder, position: Int) {
        holder.bind(
            proxiesList[position],
            proxiesListener,
        )
    }

    fun setData(proxies: List<Proxy>) {
        val newList = proxies.filter { it != Proxy.noProxy() }
        val oldList = this.proxiesList
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition].host == newList[newItemPosition].host &&
                        oldList[oldItemPosition].port == newList[newItemPosition].port
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition] == newList[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        this.proxiesList = newList
        diffResult.dispatchUpdatesTo(this)
    }
}
