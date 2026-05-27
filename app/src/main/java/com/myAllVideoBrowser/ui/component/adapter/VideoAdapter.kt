package com.myAllVideoBrowser.ui.component.adapter

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.myAllVideoBrowser.R
import com.myAllVideoBrowser.data.local.model.LocalVideo
import com.myAllVideoBrowser.databinding.ItemVideoBinding
import com.myAllVideoBrowser.util.FileUtil

class VideoAdapter(
    private var localVideos: List<LocalVideo>,
    private val videoListener: VideoListener,
    private val fileUtil: FileUtil
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    var isSelectionMode = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    val selectedItems = mutableSetOf<Long>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val binding = DataBindingUtil.inflate<ItemVideoBinding>(
            LayoutInflater.from(parent.context), R.layout.item_video, parent, false
        )

        return VideoViewHolder(binding, fileUtil)
    }

    override fun getItemCount() = localVideos.size

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) =
        holder.bind(
            localVideos[position],
            videoListener,
            isSelectionMode,
            selectedItems.contains(localVideos[position].id)
        )

    inner class VideoViewHolder(var binding: ItemVideoBinding, var fileUtil: FileUtil) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(
            localVideo: LocalVideo,
            videoListener: VideoListener,
            selectionMode: Boolean,
            isSelected: Boolean
        ) {
            val size = getScreenResolution(itemView.context)

            with(binding) {
                this.localVideo = localVideo
                this.videoListener = videoListener
                this.isSelectionMode = selectionMode
                this.isSelected = isSelected

                if (localVideo.uri.toString().contains(".mp3")) {
                    this.ivThumbnail.setImageResource(R.drawable.audio_file_24px)
                } else if (localVideo.uri.toString().contains(".mp4")) {
                    Glide.with(this@VideoViewHolder.itemView.context).load(localVideo.uri)
                        .fitCenter()
                        .error(R.drawable.ic_video_24dp)
                        .placeholder(R.drawable.ic_video_24dp)
                        .diskCacheStrategy(DiskCacheStrategy.NONE)
                        .apply(RequestOptions().override(size.first / 8, size.second / 8))
                        .into(this.ivThumbnail)
                } else {
                    this.ivThumbnail.setImageResource(R.drawable.ic_video_24dp)
                }

                root.setOnLongClickListener {
                    videoListener.onItemLongClicked(localVideo)
                    true
                }

                cbSelect.setOnClickListener {
                    videoListener.onItemSelected(localVideo, cbSelect.isChecked)
                }

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

    fun setData(localVideos: List<LocalVideo>) {
        this.localVideos = localVideos
        notifyDataSetChanged()
    }

    fun selectAll() {
        selectedItems.clear()
        selectedItems.addAll(localVideos.map { it.id })
        notifyDataSetChanged()
    }

    fun deselectAll() {
        selectedItems.clear()
        notifyDataSetChanged()
    }

    fun toggleSelection(id: Long) {
        if (selectedItems.contains(id)) {
            selectedItems.remove(id)
        } else {
            selectedItems.add(id)
        }
        notifyDataSetChanged()
    }
}

interface VideoListener {
    fun onItemClicked(localVideo: LocalVideo)
    fun onMenuClicked(view: View, localVideo: LocalVideo)
    fun onItemLongClicked(localVideo: LocalVideo)
    fun onItemSelected(localVideo: LocalVideo, isSelected: Boolean)
}

@GlideModule
class MyGlideModule : AppGlideModule() {
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setLogLevel(Log.ERROR)
    }
}
