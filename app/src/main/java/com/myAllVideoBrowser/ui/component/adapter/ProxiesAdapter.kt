package com.myAllVideoBrowser.ui.component.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.Proxy
import com.myAllVideoBrowser.databinding.ItemProxiesBinding
import com.myAllVideoBrowser.ui.main.proxies.ProxiesViewModel

interface ProxiesListener {
    fun onProxyClicked(view: View, proxy: Proxy)

    fun onProxyToggle(isChecked: Boolean)
}

class ProxiesAdapter(
    private var proxiesList: List<Proxy>,
    private val proxiesListener: ProxiesListener,
    private val proxiesViewModel: ProxiesViewModel
) : RecyclerView.Adapter<ProxiesAdapter.ProxiesViewHolder>() {
    class ProxiesViewHolder(val binding: ItemProxiesBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(
            proxy: Proxy,
            proxiesListener: ProxiesListener,
            isChecked: Boolean
        ) {
            with(binding)
            {
                this.proxy = proxy
                this.proxiesListener = proxiesListener
                this.isChecked = isChecked
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
            proxiesList[position] == proxiesViewModel.currentProxy.get()
        )
    }

    fun setData(proxies: List<Proxy>) {
        this.proxiesList = proxies
        notifyDataSetChanged()
    }
}
