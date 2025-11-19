package com.myAllVideoBrowser.util.downloaders.ffmpeg_downloader

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import androidx.work.WorkerParameters
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.FFmpegSessionCompleteCallback
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.myAllVideoBrowser.util.AppLogger
import com.myAllVideoBrowser.util.downloaders.generic_downloader.GenericDownloader
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskItem
import com.myAllVideoBrowser.util.downloaders.generic_downloader.models.VideoTaskState
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.GenericDownloadWorkerWrapper
import com.myAllVideoBrowser.util.downloaders.generic_downloader.workers.Progress
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class FfmpegDownloaderWorker(appContext: Context, workerParams: WorkerParameters) :
    GenericDownloadWorkerWrapper(appContext, workerParams) {

    private val ffmpegSession = AtomicReference<FFmpegSession?>()
    private var outputFileName: String? = null
    private val progress = Progress(1, 100)

    companion object {
        @Volatile
        private var isCanceled: Boolean = false
    }

    override fun handleAction(
        action: String, task: VideoTaskItem, headers: Map<String, String>, isFileRemove: Boolean
    ) {
        when (action) {
            GenericDownloader.DownloaderActions.DOWNLOAD,
            GenericDownloader.DownloaderActions.RESUME -> {
                isCanceled = false
                startDownload(task, headers)
            }

            GenericDownloader.DownloaderActions.CANCEL,
            GenericDownloader.DownloaderActions.PAUSE -> {
                isCanceled = true
                cancelTask(task)
            }
        }
    }

    private fun startDownload(task: VideoTaskItem, headers: Map<String, String>) {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY)!!
        AppLogger.d("FFmpeg: Starting download for task: $taskId")

        val tmpDir = fileUtil.tmpDir.resolve(taskId).apply { mkdirs() }
        val sanitizedTitle = task.fileName
            .replace(Regex("[^a-zA-Z0-9.-]"), "_")
            .take(50)
        outputFileName = tmpDir.resolve("${sanitizedTitle}_${Date().time}.mp4").absolutePath
        val fullOutputPath = outputFileName!!

        val arguments = buildFfmpegArguments(task.url, headers, fullOutputPath)
        AppLogger.d("FFmpeg: Executing with arguments: $arguments")

        saveProgress(taskId, progress, VideoTaskState.PENDING)
        showProgress(task.also { it.mId = taskId }, progress)

        val session = FFmpegKit.executeWithArgumentsAsync(
            arguments.toTypedArray(),
            FFmpegSessionCompleteCallback { session ->
                val returnCode = session.returnCode
                val allLogs = session.allLogsAsString
                AppLogger.d("FFmpeg: Session completed with return code: $returnCode")
                if (!ReturnCode.isSuccess(returnCode)) {
                    AppLogger.e("FFmpeg Logs: $allLogs")
                }

                val finalState = when {
                    ReturnCode.isSuccess(returnCode) -> VideoTaskState.SUCCESS
                    ReturnCode.isCancel(returnCode) && isCanceled -> VideoTaskState.CANCELED
                    else -> VideoTaskState.ERROR
                }

                finishWork(task.also {
                    it.mId = taskId
                    it.taskState = finalState
                    it.filePath = fullOutputPath
                    it.errorMessage =
                        if (finalState == VideoTaskState.ERROR) "FFmpeg failed: ${session.failStackTrace}" else null
                })
            },
            Executors.newSingleThreadExecutor()
        )
        ffmpegSession.set(session)
    }


    private fun onProgress(progress: Progress, task: VideoTaskItem) {
        if (getDone()) return
        showProgress(task, progress)
        saveProgress(task.mId, progress, VideoTaskState.DOWNLOADING)
    }

    private fun buildFfmpegArguments(
        url: String,
        headers: Map<String, String>,
        fullOutputPath: String
    ): List<String> {
        if (url.isEmpty()) {
            throw IllegalArgumentException("Input URL is empty or invalid.")
        }
        val proxyHost = proxyController.getCurrentRunningProxy().host
        val proxyPort = proxyController.getCurrentRunningProxy().port
        val pass = proxyController.getCurrentRunningProxy().password
        val user = proxyController.getCurrentRunningProxy().user


        val arguments = mutableListOf<String>()
        val fixedHeaders = decodeCookieHeader(headers).toMutableMap()

        arguments.add("-y") // Overwrite output file if it exists

        if (proxyHost.isNotEmpty() && proxyPort.isNotEmpty()) {
            val proxyUrl = if (user.isNotEmpty() && pass.isNotEmpty()) {
                "http://$user:$pass@$proxyHost:$proxyPort"
            } else {
                "http://$proxyHost:$proxyPort"
            }
            arguments.add("-http_proxy")
            arguments.add(proxyUrl)
        }

        arguments.add("-protocol_whitelist")
        arguments.add("http,https,tcp,tls,crypto,httpproxy,hls,file,pipe")

        arguments.add("-allowed_extensions")
        arguments.add("ALL")

        val cookieValue =
            fixedHeaders.remove("Cookie")
        if (!cookieValue.isNullOrBlank()) {
            try {
                val domain = url.toHttpUrlOrNull()
                    ?.let { getBaseDomain(it.host) }
                if (domain != null) {
                    val ffmpegCookieString = cookieValue
                        .split(';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n") { cookie ->
                            "$cookie; path=/; domain=$domain;"
                        }

                    if (ffmpegCookieString.isNotEmpty()) {
                        arguments.add("-cookies")
                        arguments.add(ffmpegCookieString)
                        AppLogger.d("FFmpeg: Using -cookies flag for domain: $domain")
                    } else {
                        fixedHeaders["Cookie"] = cookieValue
                    }

                } else {
                    AppLogger.w("FFmpeg: Could not extract domain. Falling back to Cookie header.")
                    fixedHeaders["Cookie"] = cookieValue
                }
            } catch (e: Exception) {
                AppLogger.e("FFmpeg: Could not process URL/cookies. Falling back to Cookie header. $e")
                e.printStackTrace()
                fixedHeaders["Cookie"] = cookieValue
            }
        }

        var userAgent: String? = null
        val otherHeaders = mutableListOf<String>()
        fixedHeaders.forEach { (name, value) ->
            if (name.equals("User-Agent", ignoreCase = true)) {
                userAgent = value
            } else {
                if (value.isNotEmpty()) {
                    otherHeaders.add("$name: $value")
                }
            }
        }

        userAgent?.let {
            arguments.add("-user_agent")
            arguments.add(it) // No single quotes
        }

        otherHeaders.remove("Cookie")
        val headersString = otherHeaders.joinToString(separator = "\r\n")

        if (headersString.isNotEmpty()) {
            arguments.add("-headers")
            arguments.add(headersString) // No single quotes
        }

        arguments.add("-i")
        arguments.add(url)

        // F. Output-related options (must come AFTER -i)
        if (isAudio(url)) {
            // For audio-only, convert to MP3
            arguments.add("-vn") // Disable video
            arguments.add("-c:a")
            arguments.add("libmp3lame")
            arguments.add("-q:a")
            arguments.add("2") // High quality
        } else {
            arguments.add("-c")
            arguments.add("copy")

            arguments.add("-bsf:a")
            arguments.add("aac_adtstoasc")

            arguments.add("-movflags")
            arguments.add("frag_keyframe+empty_moov")
        }

        if (!validateOutputPath(fullOutputPath)) {
            throw IllegalArgumentException("Invalid output path: $fullOutputPath")
        }

        arguments.add(fullOutputPath)

        AppLogger.d("FFmpeg command: ${arguments.joinToString(" ")}")

        return arguments
    }

    private fun isAudio(url: String): Boolean {
        return url.endsWith(".mp3", true) || url.endsWith(".wav", true) || url.endsWith(
            ".aac",
            true
        ) ||
                url.endsWith(".flac", true) || url.endsWith(".ogg", true)
    }

    private fun getBaseDomain(host: String): String {
        val publicSuffixes = setOf(
            "com", "org", "net", "edu", "gov", "mil", "int",

            "app", "dev", "io", "xyz", "tech", "site", "online", "shop", "club", "store",
            "design", "info", "biz", "name", "pro", "mobi", "link", "live", "studio",

            "ai", "au", "ca", "cn", "de", "eu", "fr", "in", "it", "jp", "kr", "nl", "ru",
            "uk", "us", "tv", "me", "ws", "gg", "to", "so",

            "co.uk", "org.uk", "me.uk",
            "com.au", "net.au", "org.au",
            "co.jp", "or.jp", "ne.jp",
            "co.in", "org.in",
            "co.nz", "net.nz", "org.nz",
            "com.br", "net.br", "org.br",
            "com.cn", "net.cn", "org.cn",
            "com.de",
            "com.fr",
            "com.es",
            "com.mx",
            "com.tw",
            "com.ua",
            "co.za",
            "ac.uk",
            "gov.uk",
            "gov.au"
        )

        val parts = host.split('.').reversed()

        if (parts.size >= 2) {
            // Handle multi-part suffixes like "co.uk"
            if (parts.size > 2 && publicSuffixes.contains("${parts[1]}.${parts[0]}")) {
                return "${parts[2]}.${parts[1]}.${parts[0]}"
            }
            return "${parts[1]}.${parts[0]}"
        }

        return host
    }

    private fun validateOutputPath(outputPath: String): Boolean {
        val outputFile = File(outputPath)
        val parentDir = outputFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            return parentDir.mkdirs()
        }
        return true
    }

    private fun cancelTask(task: VideoTaskItem) {
        val taskId = inputData.getString(GenericDownloader.Constants.TASK_ID_KEY) ?: return
        AppLogger.d("FFmpeg: Cancelling task: $taskId")
        ffmpegSession.get()?.cancel()
        finishWork(task)
    }

    override fun finishWork(item: VideoTaskItem?) {
        if (getDone()) {
            getContinuation().resume(Result.success())
            return
        }
        setDone()

        val taskId = item?.mId ?: run {
            AppLogger.e("FFmpeg: Cannot finish work, taskId is NULL")
            getContinuation().resume(Result.failure())
            return
        }
        AppLogger.d("FFmpeg: Finishing work for task $taskId with state ${item.taskState}")

        ffmpegSession.set(null)

        handleTaskCompletion(item)

        val notificationData = notificationsHelper.createNotificationBuilder(item)
        showNotificationFinal(notificationData.first, notificationData.second)

        val result =
            if (item.taskState == VideoTaskState.ERROR) Result.failure() else Result.success()
        try {
            getContinuation().resume(result)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun handleTaskCompletion(item: VideoTaskItem) {
        val sourcePath = File(item.filePath)

        when (item.taskState) {
            VideoTaskState.CANCELED -> {
                sourcePath.parentFile?.deleteRecursively()
                saveProgress(item.mId, progress, item.taskState, "Canceled")
            }

            VideoTaskState.SUCCESS -> {
                val targetPath = fixFileName(File(fileUtil.folderDir, sourcePath.name).path)
                val fileMoved = fileUtil.moveMedia(
                    applicationContext,
                    sourcePath.toUri(),
                    File(targetPath).toUri()
                )

                if (fileMoved) {
                    AppLogger.d("FFmpeg: File moved successfully to $targetPath")
                    sourcePath.parentFile?.deleteRecursively()
                    saveProgress(item.mId, Progress(100, 100), item.taskState, "Success")
                } else {
                    AppLogger.e("FFmpeg: Failed to move file to $targetPath")
                    item.taskState = VideoTaskState.ERROR
                    item.errorMessage = "Error moving file"
                    saveProgress(item.mId, progress, item.taskState, "Error moving file")
                }
            }

            else -> {
                sourcePath.parentFile?.deleteRecursively()
                saveProgress(item.mId, progress, item.taskState, item.errorMessage ?: "Error")
            }
        }
    }

    private fun showProgress(taskItem: VideoTaskItem, progress: Progress) {
        taskItem.apply {
            lineInfo = "Downloading: ${taskItem.fileName}"
            taskState = VideoTaskState.DOWNLOADING
            // Since we can't get live progress, total and download size are simulated
            totalSize = progress.totalBytes
            downloadSize = progress.currentBytes
            percent = getPercentFromBytes(downloadSize, totalSize)
        }

        val notificationData = notificationsHelper.createNotificationBuilder(taskItem)
        showLongRunningNotificationAsync(notificationData.first, notificationData.second)
    }

    private fun saveProgress(
        taskId: String, progress: Progress, downloadStatus: Int, infoLine: String = ""
    ) {
        if (getDone() && downloadStatus == VideoTaskState.DOWNLOADING) return

        val dbTask =
            progressRepository.getProgressInfos().blockingFirst().find { it.id == taskId } ?: return
        if (dbTask.downloadStatus == VideoTaskState.SUCCESS) return

        dbTask.downloadStatus = downloadStatus
        dbTask.infoLine = infoLine
        dbTask.progressTotal = progress.totalBytes
        dbTask.progressDownloaded = progress.currentBytes
        dbTask.isLive =
            progress.totalBytes == progress.currentBytes && downloadStatus == VideoTaskState.DOWNLOADING

        progressRepository.saveProgressInfo(dbTask)
    }

    private fun decodeCookieHeader(headers: Map<String, String>): Map<String, String> {
        return headers.toMutableMap().also { fixedHeaders ->
            headers["Cookie"]?.let {
                val cookies = String(Base64.decode(it, Base64.DEFAULT))
                fixedHeaders["Cookie"] = cookies
            }
        }
    }
}
