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

        val outputDir = inputFile.parentFile ?: return null
        val outputName = "${inputFile.nameWithoutExtension}_processed.mp4"
        val outputFile = File(outputDir, outputName)

        if (outputFile.exists()) {
            outputFile.delete()
        }

        val request = YoutubeDLRequest("file:${inputFile.absolutePath}")

        request.addOption("--force-overwrites")
        request.addOption("--enable-file-urls")
        request.addOption("-o", outputFile.absolutePath)
        request.addOption("--remux-video", "mp4")

        AppLogger.d("Starting youtube-dlp remux process for: ${inputFile.name}")
        AppLogger.d("Command options: ${request.buildCommand().joinToString(" ")}")

        var processingSuccess = false
        val taskId = "remux_${UUID.randomUUID()}"

        try {
            YoutubeDL.getInstance().execute(request, taskId) { progress, etaInSeconds, line ->
                AppLogger.d("[REDUX_LOG] $line")
                if (line.contains("[ffmpeg] Deleting original file") || line.contains("already is in target format")) {
                    processingSuccess = true
                }
            }
        } catch (e: YoutubeDLException) {
            AppLogger.e("youtube-dlp execution failed. ${e.message}")
            e.printStackTrace()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return null
        } catch (_: InterruptedException) {
            AppLogger.e("youtube-dlp process was interrupted.")
            Thread.currentThread().interrupt()
            if (outputFile.exists()) {
                outputFile.delete()
            }
            return null
        }

        if (processingSuccess && outputFile.exists()) {
            AppLogger.d("Remux process successful. Output: ${outputFile.absolutePath}")
            // The original input file is deleted automatically by youtube-dlp on success.
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
