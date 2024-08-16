package com.myAllVideoBrowser.ui.component.binding

import androidx.databinding.BindingAdapter
import android.net.Uri
import androidx.core.content.FileProvider
import android.widget.VideoView
import java.io.File

object VideoViewBinding {

    @BindingAdapter("app:videoURI")
    @JvmStatic
    fun VideoView.setVideoURI(videoPath: String?) {
        videoPath?.let { path ->
            val uri = if (path.startsWith("http")) {
                Uri.parse(path)
            } else {
                FileProvider.getUriForFile(context, context.packageName + ".provider", File(path))
            }
            setVideoURI(uri)
        }
    }
}