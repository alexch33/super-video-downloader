package com.myAllVideoBrowser.ui.component.adapter

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.databinding.ItemWebTabButtonBinding
import com.myAllVideoBrowser.ui.main.home.browser.webTab.WebTab

interface WebTabsListener {
    fun onCloseTabClicked(webTab: WebTab)
    fun onSelectTabClicked(webTab: WebTab)
}

class WebTabsAdapter(
    private var webTabs: List<WebTab>,
    private var webTabsListener: WebTabsListener
) : RecyclerView.Adapter<WebTabsAdapter.WebTabsViewHolder>() {

    private var selectedTabIndex: Int = -1

    class WebTabsViewHolder(val binding: ItemWebTabButtonBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(webTab: WebTab, webTabsListener: WebTabsListener, isSelected: Boolean) {
            with(binding)
            {
                val context = this.root.context

                this.webTab = webTab
                this.tabListener = webTabsListener

                if (isSelected) {
                    itemWebTabButton.setCardBackgroundColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorPrimaryContainer,
                            Color.TRANSPARENT
                        )
                    )
                    tabTitle.setTextColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnPrimaryContainer,
                            Color.BLACK
                        )
                    )
                    closeTab.iconTint = ColorStateList.valueOf(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnPrimaryContainer,
                            Color.BLACK
                        )
                    )
                } else {
                    itemWebTabButton.setCardBackgroundColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorSurfaceContainerLow,
                            Color.TRANSPARENT
                        )
                    )
                    tabTitle.setTextColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnSurface,
                            Color.BLACK
                        )
                    )
                    closeTab.iconTint = ColorStateList.valueOf(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            Color.BLACK
                        )
                    )
                }

                this.closeTab.visibility = if (webTab.isHome()) {
                    View.GONE
                } else {
                    View.VISIBLE
                }

                if (webTab.isHome()) {
                    val bm =
                        AppCompatResources.getDrawable(
                            context,
                            R.drawable.home_48px
                        )

                    this.faviconTab.setImageDrawable(bm)
                } else {
                    val placeholder = AppCompatResources.getDrawable(
                        context,
                        R.drawable.public_24px
                    )

                    Glide.with(context)
                        .load(webTab.getFavicon())
                        .placeholder(placeholder)
                        .error(placeholder)
                        .circleCrop()
                        .into(this.faviconTab)
                }

                if (!webTab.isHome()) {
                    if (webTab.getTitle().isEmpty()) {
                        this.tabTitle.text = webTab.getUrl()
                    } else {
                        this.tabTitle.text = webTab.getTitle()
                    }
                }

                executePendingBindings()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WebTabsViewHolder {
        val binding = DataBindingUtil.inflate<ItemWebTabButtonBinding>(
            LayoutInflater.from(parent.context),
            R.layout.item_web_tab_button, parent, false
        )

        return WebTabsViewHolder(binding)
    }

    override fun getItemCount() = webTabs.size

    override fun onBindViewHolder(holder: WebTabsViewHolder, position: Int) =
        holder.bind(webTabs[position], webTabsListener, position == selectedTabIndex)

    fun setData(webTabs: List<WebTab>) {
        this.webTabs = webTabs
        notifyDataSetChanged()
    }

    fun setSelectedTab(index: Int) {
        val prev = selectedTabIndex
        selectedTabIndex = index
        if (prev in 0 until itemCount) {
            notifyItemChanged(prev)
        }
        if (selectedTabIndex in 0 until itemCount) {
            notifyItemChanged(selectedTabIndex)
        }
    }
}
