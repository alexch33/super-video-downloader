package com.myAllVideoBrowser.util

import android.net.Uri
import androidx.core.net.toUri
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File

/**
 * A processor that uses FFmpegKit to process downloaded files for maximum compatibility.
 * This class has been refactored to use FFmpegKit directly, removing the youtube-dlp dependency
 * for local file processing, which provides more control and robustness.
 */
class FfmpegProcessor private constructor() {

    companion object {
        @Volatile
        private var instance: FfmpegProcessor? = null
        private const val TAG = "FfmpegProcessor"

        private val AUDIO_EXTENSIONS =
            setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")

        fun getInstance(): FfmpegProcessor =
            instance ?: synchronized(this) {
                instance ?: FfmpegProcessor().also { instance = it }
            }
    }

    fun processDownload(inputUri: Uri, isFlv: Boolean, onProgress: (Int) -> Unit): Uri? {
        AppLogger.d("$TAG: processDownload started for URI: $inputUri. Is FLV: $isFlv")
        val inputFile = File(inputUri.path ?: run {
            AppLogger.e("$TAG: Input URI path is null.")
            return null
        })

        if (!inputFile.exists()) {
            AppLogger.e("$TAG: Input file does not exist: ${inputFile.absolutePath}")
            return null
        }

        var resultUri: Uri? = null

        val inputExtension = inputFile.extension.lowercase(java.util.Locale.ROOT)
        val isAudioOnly = AUDIO_EXTENSIONS.contains(inputExtension)
        AppLogger.d("$TAG: Detected extension: '$inputExtension'. Is audio only: $isAudioOnly")

        val outputExtension = if (isAudioOnly) "mp3" else "mp4"
        val outputDir = inputFile.parentFile ?: return null
        val outputName = "${inputFile.nameWithoutExtension}_processed.$outputExtension"
        val outputFile = File(outputDir, outputName)
        AppLogger.d("$TAG: Generated output file path: ${outputFile.absolutePath}")

        if (outputFile.exists()) {
            AppLogger.w("$TAG: Output file already exists. Deleting it.")
            outputFile.delete()
        }

        try {
            AppLogger.d("$TAG: Bypassing MediaInformationSession to directly process potentially corrupt file.")
            resultUri =
                executeProcessing(inputFile, outputFile, isFlv, isAudioOnly, 0.0, onProgress)
        } catch (e: Exception) {
            AppLogger.e("$TAG: Exception during executeProcessing call. ${e.message} ${e.printStackTrace()}")
            resultUri = null
        } finally {
            AppLogger.d("$TAG: Processing finished, returning final URI: $resultUri")
        }

        return resultUri
    }

    /**
     * Executes the actual FFmpeg processing command.
     * @return The Uri of the successfully processed file, or null on failure.
     */
    private fun executeProcessing(
        inputFile: File,
        outputFile: File,
        isFlv: Boolean,
        isAudioOnly: Boolean,
        totalDuration: Double,
        onProgress: (Int) -> Unit
    ): Uri? {
        AppLogger.d("$TAG: executeProcessing started for input: ${inputFile.name}")
        val arguments = mutableListOf<String>().apply {
            add("-y") // Overwrite output file

            if (isFlv) {
                AppLogger.d("$TAG: Applying FLV-specific and corruption-handling flags.")
                add("-fflags"); add("+discardcorrupt")
                add("-f"); add("flv") // Explicitly tell FFmpeg the input container is FLV.
            }

            add("-i"); add(inputFile.absolutePath) // Input file

            if (isAudioOnly) {
                AppLogger.d("$TAG: Applying audio-only processing: Re-encoding to MP3.")
                add("-c:a"); add("libmp3lame")
                add("-q:a"); add("2")
            } else {
                AppLogger.d("$TAG: Applying stream copy (-c copy) as it's fastest and proven to work.")
                add("-c"); add("copy")
                add("-bsf:a"); add("aac_adtstoasc")
            }

            if (!isAudioOnly) {
                AppLogger.d("$TAG: Adding '-movflags +faststart' to optimize for streaming.")
                add("-movflags"); add("+faststart")
            }

            add(outputFile.absolutePath)
        }

        AppLogger.d("$TAG: Starting FFmpeg process for: ${inputFile.name}")
        AppLogger.d("$TAG: FFmpeg Command: ${arguments.joinToString(" ")}")

        onProgress(0)

        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())

        return if (ReturnCode.isSuccess(session.returnCode)) {
            AppLogger.d("$TAG: FFmpeg process successful. Output: ${outputFile.absolutePath}")
            onProgress(100)
            AppLogger.d("$TAG: Deleting original input file: ${inputFile.absolutePath}")
            inputFile.delete()
            outputFile.toUri()
        } else {
            AppLogger.e("$TAG: FFmpeg execution failed with code ${session.returnCode}. Full Log:\n${session.allLogsAsString}")
            if (outputFile.exists()) {
                AppLogger.e("$TAG: Deleting failed output file: ${outputFile.absolutePath}")
                outputFile.delete()
            }
            null
        }
    }
}
