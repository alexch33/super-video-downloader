package com.myAllVideoBrowser.util.downloaders.super_x_downloader.control

import java.io.File
import java.io.IOException

class FileBasedDownloadController(private val downloadDir: File) {
    companion object {
        const val PAUSE_FLAG_FILENAME = "pause"
        const val CANCEL_FLAG_FILENAME = "cancel"
        const val STOP_AND_SAVE_FLAG_FILENAME = "stop_and_save"
    }

    private val pauseFlag = File(downloadDir, PAUSE_FLAG_FILENAME)
    private val cancelFlag = File(downloadDir, CANCEL_FLAG_FILENAME)
    private val stopAndSaveFlag = File(downloadDir, STOP_AND_SAVE_FLAG_FILENAME)

    /**
     * Initializes the controller for a new download, ensuring no old flags exist.
     */
    fun start() {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        pauseFlag.delete()
        cancelFlag.delete()
        stopAndSaveFlag.delete()
    }

    /**
     * Signals the worker to pause by creating the pause flag.
     * @throws IOException if the flag file cannot be created.
     */
    @Throws(IOException::class)
    fun requestPause() {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        pauseFlag.createNewFile()
    }

    /**
     * Signals the worker to cancel by creating the cancel flag.
     * @throws IOException if the flag file cannot be created.
     */
    @Throws(IOException::class)
    fun requestCancel() {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        cancelFlag.createNewFile()
    }

    /**
     * Signals a live stream to stop and merge by creating the flag.
     * @throws IOException if the flag file cannot be created.
     */
    @Throws(IOException::class)
    fun requestStopAndSave() {
        if (!downloadDir.exists()) {
            downloadDir.mkdirs()
        }
        stopAndSaveFlag.createNewFile()
    }

    fun isPauseRequested(): Boolean = pauseFlag.exists()

    fun isCancelRequested(): Boolean = cancelFlag.exists()

    fun isStopAndSaveRequested(): Boolean = stopAndSaveFlag.exists()

    /**
     * Checks if any stop-like action has been requested.
     * This is useful for breaking loops.
     */
    fun isInterrupted(): Boolean {
        return isPauseRequested() || isCancelRequested() || isStopAndSaveRequested()
    }
}
