package com.myAllVideoBrowser.util.downloaders.super_x_downloader

import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * A coroutine-based downloader for individual media segments.
 * It handles retries and checks for cancellation/pause signals.
 */
class SegmentDownloader(
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val controller: FileBasedDownloadController,
    private val onProgress: ((bytes: Long) -> Unit)? = null
) {

    companion object {
        private const val RETRY_COUNT = 3
    }

    /**
     * Downloads a single segment from a URL to a file with retries.
     * This is a suspend function and integrates with structured concurrency.
     *
     * @param segmentUrl The URL of the segment to download.
     * @param outputFile The file where the segment will be saved.
     * @param logPrefix A prefix for logging (e.g., "HLS", "MPD").
     * @param segmentIdentifier A unique identifier for the segment for logging (e.g., index).
     * @return The number of bytes downloaded.
     * @throws IOException if the download fails after all retries.
     * @throws CancellationException if a pause or cancel is requested via the controller.
     */
    suspend fun download(
        segmentUrl: String,
        outputFile: File,
        logPrefix: String,
        segmentIdentifier: Any
    ): Long {
        if (outputFile.exists() && outputFile.length() > 0) {
            AppLogger.d("$logPrefix: Segment $segmentIdentifier already exists. Skipping.")
            return outputFile.length()
        }

        var lastException: Exception? = null
        for (attempt in 1..RETRY_COUNT) {
            // Check for interruption before every attempt
            if (controller.isInterrupted()) {
                throw CancellationException("Download interrupted by user.")
            }

            try {
                AppLogger.d("$logPrefix: Downloading segment $segmentIdentifier from $segmentUrl (Attempt $attempt/$RETRY_COUNT)")
                val request = Request.Builder().url(segmentUrl).apply {
                    headers.forEach { (key, value) -> addHeader(key, value) }
                }.build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw IOException("Failed to download segment $segmentIdentifier. HTTP ${response.code}")
                }

                var bytesCopied = 0L
                response.body.byteStream().use { input ->
                    outputFile.outputStream().use { output ->
                        bytesCopied = input.copyTo(output)
                    }
                }
                AppLogger.d("$logPrefix: Segment $segmentIdentifier downloaded successfully.")
                onProgress?.invoke(bytesCopied)
                return bytesCopied // Success, return the size
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                lastException = e
                AppLogger.w("$logPrefix: Failed to download segment $segmentIdentifier on attempt $attempt: ${e.message}")
                if (outputFile.exists()) outputFile.delete() // Clean up failed partial download

                // Don't wait on the last attempt
                if (attempt < RETRY_COUNT) {
                    delay(1000L * attempt)
                }
            }
        }
        throw IOException(
            "Failed to download segment $segmentIdentifier after $RETRY_COUNT attempts.", lastException
        )
    }
}
