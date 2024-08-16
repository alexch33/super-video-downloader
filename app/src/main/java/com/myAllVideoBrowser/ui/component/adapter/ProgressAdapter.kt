package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import android.graphics.Color
import android.util.DisplayMetrics
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.databinding.ItemProgressBinding

class ProgressAdapter(
    private var progressInfos: List<ProgressInfo>,
    private var videoListener: ProgressListener
) : RecyclerView.Adapter<ProgressAdapter.ProgressViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProgressViewHolder {
        val binding = DataBindingUtil.inflate<ItemProgressBinding>(
            LayoutInflater.from(parent.context), R.layout.item_progress, parent, false
        )

        return ProgressViewHolder(binding)
    }

    override fun getItemCount() = progressInfos.size

    override fun onBindViewHolder(holder: ProgressViewHolder, position: Int) =
        holder.bind(progressInfos[position], videoListener)

    class ProgressViewHolder(val binding: ItemProgressBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(progressInfo: ProgressInfo, progressListener: ProgressListener) {
            val thumbnail = progressInfo.videoInfo.thumbnail
            val placeholder = R.drawable.ic_video_24dp
            val size = getScreenResolution(itemView.context)
            val color =
                MaterialColors.getColor(itemView.context, R.attr.colorSurfaceVariant, Color.YELLOW)

            with(binding)
            {
                this.cardProgress.setCardBackgroundColor(color)

                this.progressInfo = progressInfo
                this.progressListener = progressListener
                this.downloadId = progressInfo.downloadId
                this.isRegular = progressInfo.videoInfo.isRegularDownload

                Glide.with(this@ProgressViewHolder.itemView.context).load(thumbnail).fitCenter()
                    .error(placeholder)
                    .placeholder(placeholder)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .apply(RequestOptions().override(size.first / 8, size.second / 8))
                    .into(this.ivThumbnail)

                executePendingBindings()
            }
        }

        private fun getScreenResolution(context: Context): Pair<Int, Int> {
            val displayMetrics = DisplayMetrics()
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            windowManager.defaultDisplay.getMetrics(displayMetrics)

            val widthPixels = displayMetrics.widthPixels
            val heightPixels = displayMetrics.heightPixels

            return Pair(widthPixels, heightPixels)
        }
    }

    fun setData(progressInfos: List<ProgressInfo>) {
        this.progressInfos = progressInfos
        notifyDataSetChanged()
    }
}

interface ProgressListener {
    fun onMenuClicked(view: View, downloadId: Long, isRegular: Boolean)
}