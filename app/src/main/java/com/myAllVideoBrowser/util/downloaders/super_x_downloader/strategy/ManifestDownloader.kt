package com.myAllVideoBrowser.util.downloaders.super_x_downloader.strategy

import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import com.myAllVideoBrowser.util.downloaders.super_x_downloader.control.FileBasedDownloadController
import java.io.File

/**
 * Defines a common interface for a download strategy based on a manifest type (HLS, MPD, etc.).
 */
interface ManifestDownloader {

    /**
     * Downloads and merges the content defined by the task.
     *
     * @param task The video task item containing URLs and metadata.
     * @param headers The network request headers.
     * @param downloadDir The directory for temporary files.
     * @param controller The controller to check for pause/cancel signals.
     * @param onProgress A lambda to report download progress.
     * @return The final, merged media file.
     * @throws Exception on failure or if the download is interrupted.
     */
    suspend fun download(
        task: VideoTaskItem,
        headers: Map<String, String>,
        downloadDir: File,
        controller: FileBasedDownloadController,
        onProgress: (progress: Progress) -> Unit
    ): File
}
