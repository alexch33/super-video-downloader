package com.myAllVideoBrowser.data.local.model

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils.OPTIONS_RECYCLE_INPUT
import android.media.ThumbnailUtils.extractThumbnail
import android.provider.MediaStore.Images.Thumbnails.MICRO_KIND
import android.provider.MediaStore.Images.Thumbnails.MINI_KIND
import kotlin.math.roundToInt

class ThumbnailHelper {
    companion object {
        private const val TARGET_SIZE_MICRO_THUMBNAIL = 96

        fun createVideoThumbnail(filePath: String?, kind: Int): Bitmap? {
            var bitmap: Bitmap? = null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(filePath)
                bitmap = retriever.getScaledFrameAtTime(1000, 0, 320, 240)
            } catch (ex: IllegalArgumentException) {
                // Assume this is a corrupt video file
            } catch (ex: RuntimeException) {
                // Assume this is a corrupt video file.
            } finally {
                try {
                    retriever.release()
                } catch (ex: RuntimeException) {
                    // Ignore failures while cleaning up.
                }
            }
            if (bitmap == null) return null
            if (kind == MINI_KIND) {
                // Scale down the bitmap if it's too large.
                val width = bitmap.width
                val height = bitmap.height
                val max = width.coerceAtLeast(height)
                if (max > 512) {
                    val scale = 512f / max
                    val w = (scale * width).roundToInt()
                    val h = (scale * height).roundToInt()
                    bitmap = Bitmap.createScaledBitmap(bitmap, w, h, true)
                }
            } else if (kind == MICRO_KIND) {
                bitmap = extractThumbnail(
                    bitmap,
                    Companion.TARGET_SIZE_MICRO_THUMBNAIL,
                    Companion.TARGET_SIZE_MICRO_THUMBNAIL,
                    OPTIONS_RECYCLE_INPUT
                )
            }
            return bitmap
        }

    }

}