package com.myAllVideoBrowser.util

import android.net.Uri
import androidx.core.net.toUri
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID

class YoutubeDlpFfmpegProcessor private constructor() {

    companion object {
        @Volatile
        private var instance: YoutubeDlpFfmpegProcessor? = null

        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "ogg", "wav", "flac")

        fun getInstance(): YoutubeDlpFfmpegProcessor =
            instance ?: synchronized(this) {
                instance ?: YoutubeDlpFfmpegProcessor().also { instance = it }
            }
    }

    fun processDownload(inputUri: Uri): Uri? {
        val inputFile = File(inputUri.path ?: run {
            AppLogger.e("Input URI path is null.")
            return null
        })

        if (!inputFile.exists()) {
            AppLogger.e("Input file does not exist: ${inputFile.absolutePath}")
            return null
        }

        val inputExtension = inputFile.extension.lowercase(java.util.Locale.ROOT)
        val isAudio = AUDIO_EXTENSIONS.contains(inputExtension)

        val remuxFormat: String
        val outputExtension: String

        if (isAudio) {
            remuxFormat = "mp3"
            outputExtension = "mp3"
        } else {
            remuxFormat = "mp4"
            outputExtension = "mp4"
        }

        val outputDir = inputFile.parentFile ?: return null
        val outputName = "${inputFile.nameWithoutExtension}_processed.$outputExtension"
        val outputFile = File(outputDir, outputName)


        if (outputFile.exists()) {
            outputFile.delete()
        }

        val request = YoutubeDLRequest("file:${inputFile.absolutePath}")

        request.addOption("--force-overwrites")
        request.addOption("--enable-file-urls")
        request.addOption("-o", outputFile.absolutePath)

        if (isAudio) {
            request.addOption("--extract-audio")
            request.addOption("--audio-format", remuxFormat)
        } else {
            request.addOption("--remux-video", remuxFormat)
        }

        AppLogger.d("Starting youtube-dlp process for: ${inputFile.name}")
        AppLogger.d("Command options: ${request.buildCommand().joinToString(" ")}")

        var processingSuccess = false

        try {
            YoutubeDL.getInstance()
                .execute(request, "remux_${UUID.randomUUID()}") { progress, etaInSeconds, line ->
                    AppLogger.d("[REDUX_LOG] $line")
                    if (line.contains("[ffmpeg] Deleting original file") || line.contains("already is in target format") || line.contains(
                            "ExtractAudio] Destination:"
                        )
                    ) {
                        processingSuccess = true
                    }
                }
        } catch (e: YoutubeDLException) {
            AppLogger.e("youtube-dlp execution failed. ${e.message}")
            e.printStackTrace()
            return null
        } catch (_: InterruptedException) {
            AppLogger.e("youtube-dlp process was interrupted.")
            return null
        }

        if (processingSuccess && outputFile.exists()) {
            AppLogger.d("Remux process successful. Output: ${outputFile.absolutePath}")
            return outputFile.toUri()
        } else {
            AppLogger.e("Remux process failed. 'processingSuccess' flag was not set or output file not found.")
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return null
        }
    }
}
