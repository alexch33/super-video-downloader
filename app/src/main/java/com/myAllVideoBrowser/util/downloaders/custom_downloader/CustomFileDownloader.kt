package com.myAllVideoBrowser.util.downloaders.custom_downloader

import com.myAllVideoBrowser.util.AppLogger
import okhttp3.Headers.Companion.toHeaders
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.RandomAccessFile
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

interface DownloadListener {
    fun onSuccess()

    fun onFailure(e: Throwable)

    fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long)

    fun onChunkProgressUpdate(downloadedBytes: Long, allBytesChunk: Long, chunkIndex: Int)

    fun onChunkFailure(e: Throwable, index: CustomFileDownloader.Chunk)
}

class CustomFileDownloader(
    private val url: URL,
    // File is always must be placed in folder with file name without extension
    private val file: File,
    private val threadCount: Int,
    private val headers: Map<String, String>,
    private val client: OkHttpClient,
    private val listener: DownloadListener?,
) {
    private val executorService: ExecutorService = Executors.newFixedThreadPool(threadCount)
    private val isPaused = AtomicBoolean(false)
    private val isCanceled = AtomicBoolean(false)
    private var lastProgressUpdate = AtomicLong(0L)
    private val totalBytesAll = AtomicLong(0L)
    private val totalBytesChunks = AtomicLongArray(threadCount)
    private val copiedBytesChunks = AtomicLongArray(threadCount)
    private val callBackIntervalMin = 1000

    companion object {
        const val STOPPED = "STOPPED"
        const val CANCELED = "CANCELED"

        fun stop(fileToStop: File) {
            File(fileToStop.parentFile, Helper.STOP_FILE_NAME).createNewFile()
        }

        fun cancel(fileToStop: File) {
            fileToStop.parentFile?.deleteRecursively()
        }
    }

    private val totalCopiedBytes: Long
        get() {
            var sum = 0L
            for (i in 0..<copiedBytesChunks.length()) {
                val value = copiedBytesChunks.get(i)
                sum += value
            }

            return sum
        }

    fun download() {
        val contentSize = try {
            getContentLength()
        } catch (e: Throwable) {
            this.onFailure(e)

            return
        }

        val randomAccessFile = try {
            RandomAccessFile(file, "rw")
        } catch (e: Throwable) {
            this.onFailure(e)

            return
        }
        val fileChannel = randomAccessFile.channel

        totalBytesAll.set(contentSize)

        Helper.unStop(file)

        val isUrlSupportBytesRangeHeader = isUrlSupportingBytesRangeHeader()

        if (!isUrlSupportBytesRangeHeader) {
            val result = executorService.submit {
                AppLogger.d("Range header not supported, falling back to single-threaded download.")
                downloadRegularStream(fileChannel)
            }
            try {
                result.get()
            } catch (e: Throwable) {
                this.onFailure(e)
            }
            this.onSuccess()

            return
        }

        val chunkSize = contentSize / threadCount
        val ranges = (0 until threadCount).map {
            val start = it * chunkSize
            val end = if (it == threadCount - 1) contentSize - 1 else (it + 1) * chunkSize - 1
            start..end
        }

        val chunkFutureMap = mutableMapOf<Chunk, Future<*>>()
        AppLogger.d(
            "Start Downloading: file: $file threadCount: $threadCount ranges: $ranges"
        )
        ranges.forEachIndexed { index, range ->
            chunkFutureMap[Chunk(index, range, chunkSize)] = executorService.submit {
                downloadChunk(range, fileChannel, index * chunkSize, index)
            }
        }

        var allPartsSucceed = true
        chunkFutureMap.forEach { entry ->
            try {
                entry.value.get()
            } catch (e: Throwable) {
                allPartsSucceed = false
                this.onChunkFailure(e, entry.key)
            }
        }

        val isStopped = isPaused.get()
        val isCanceled = isCanceled.get()

        if (allPartsSucceed && !isStopped) {
            this.onSuccess()
        } else if (isStopped) {
            AppLogger.d("CHUNKS STOPPED")
            this.onFailure(Error(STOPPED))
        } else if (isCanceled) {
            AppLogger.d("CHUNKS CANCELED")
            this.onFailure(Error(CANCELED))
        } else {
            AppLogger.d("CHUNKS ERROR")
            this.onFailure(Error("Not All Chunks downloaded, retry"))
        }
    }

    private fun downloadRegularStream(fileChannel: FileChannel) {
        val req = getOkRequest()
        val res = client.newCall(req).execute()

        if (!res.isSuccessful) {
            this.onFailure(Exception("Failed to download file: ${res.code}"))
            return
        }

        val contentLength = res.body.contentLength()
        if (contentLength == -1L) {
            AppLogger.w("Content length is unknown for single-threaded download.")
            // Continue download even if content length is unknown, progress updates might be limited
        } else {
            totalBytesAll.set(contentLength)
        }

        val inputStream = res.body.byteStream()
        val buffer = ByteArray(Helper.DOWNLOAD_BUFFER_SIZE)
        var bytesCopied = 0L
        var bytesRead = 0

        copiedBytesChunks[0] = 0L
        totalBytesChunks[0] = contentLength

        try {
            inputStream.use { urlStream ->
                while (!isPaused.get() && !isCanceled.get() && (urlStream.read(buffer).also { bytesRead = it }) >= 0) {
                    fileChannel.write(ByteBuffer.wrap(buffer, 0, bytesRead), bytesCopied)
                    bytesCopied += bytesRead
                    copiedBytesChunks[0] = bytesCopied
                    onProgressUpdate(bytesCopied, totalBytesAll.get())
                }
            }

            if (Helper.isStopped(file)) {
                this.onFailure(Error(STOPPED))
            } else if (Helper.isCanceled(file)) {
                this.onFailure(Error(CANCELED))
            } else {
                this.onSuccess()
            }
        } catch (e: Throwable) {
            this.onFailure(e)
        } finally {
            res.close()
            fileChannel.close()
        }
    }

    private fun onSuccess() {
        executorService.shutdown()

        AppLogger.d("DOWNLOAD SUCCESS: $file")

        listener?.onSuccess()
    }

    private fun onFailure(e: Throwable) {
        executorService.shutdown()

        AppLogger.e("Task Download Failed $e")

        listener?.onFailure(e)
    }

    private fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
        val time = Date().time
        if (time - lastProgressUpdate.get() >= callBackIntervalMin) {
            isPaused.set(Helper.isStopped(file))
            isCanceled.set(Helper.isCanceled(file))
            lastProgressUpdate.set(time)
            listener?.onProgressUpdate(downloadedBytes, totalBytes)
        }
    }

    private fun onChunkProgressUpdate(downloadedBytes: Long, allBytes: Long, chunkIndex: Int) {
        copiedBytesChunks[chunkIndex] = downloadedBytes

        onProgressUpdate(totalCopiedBytes, totalBytesAll.get())

        listener?.onChunkProgressUpdate(downloadedBytes, allBytes, chunkIndex)
    }

    private fun onChunkFailure(e: Throwable, index: Chunk) {
        AppLogger.e("Chunk $index Download Failed ${e.printStackTrace()}")
        listener?.onChunkFailure(e, index)
    }

    private fun downloadChunk(
        range: LongRange, fileChannel: FileChannel, offset: Long, chunkIndex: Int
    ) {
        val chunkFile = File(file.parentFile, "chunk_$chunkIndex")
        val isResume = !chunkFile.createNewFile()
        var bytesCopied = 0L
        if (isResume) {
            bytesCopied = chunkFile.inputStream().use { chunkStream ->
                chunkStream.bufferedReader().use {
                    val text = it.readText().trim()
                    text.toLongOrNull() ?: 0L
                }
            }
        }
        AppLogger.d(
            "CHUNK $chunkIndex DOWNLOAD START, bytes copied: $bytesCopied  isResume: $isResume"
        )

        copiedBytesChunks[chunkIndex] = bytesCopied

        if (range.first + bytesCopied >= range.last) {
            val total = range.last - range.first + 1
            totalBytesChunks[chunkIndex] = total
            copiedBytesChunks[chunkIndex] = total

            return
        }

        val req = if (threadCount == 1) {
            getOkRequestRange(range.first + bytesCopied, null)
        } else getOkRequestRange(range.first + bytesCopied, range.last)
        val res = client.newCall(req).execute()

        if (res.body.contentLength() == -1L) {
            throw Error("Content Length Not Found")
        }

        val inputStream = res.body.byteStream()
        val buffer = ByteArray(Helper.DOWNLOAD_BUFFER_SIZE)

        copiedBytesChunks[chunkIndex] = bytesCopied
        totalBytesChunks[chunkIndex] = res.body.contentLength()

        var bytesRead = 0

        RandomAccessFile(chunkFile, "rw").channel.use { chunkChannel ->
            inputStream.use { urlStream ->
                while (!isPaused.get() && !isCanceled.get() && (urlStream.read( // && bytesCopied < range.last
                        buffer
                    ).also { bytesRead = it }) >= 0
                ) {
                    fileChannel.write(ByteBuffer.wrap(buffer), offset + bytesCopied)
                    bytesCopied += bytesRead
                    chunkChannel.write(ByteBuffer.wrap("$bytesCopied".toByteArray()), 0)
                    this.onChunkProgressUpdate(
                        bytesCopied, totalBytesChunks[chunkIndex], chunkIndex
                    )
                }
                if (Helper.isStopped(file)) {
                    throw Exception(STOPPED)
                }
                if (Helper.isCanceled(file)) {
                    throw Exception(CANCELED)
                }
            }
        }
    }

    private fun isUrlSupportingBytesRangeHeader(): Boolean {
        val req = getOkRequestRange(0, 0)

        var res: Response? = null
        try {
            res = client.newCall(req).execute()
            return res.code == 206
        } catch (e: Throwable) {
            return false
        } finally {
            res?.close()
        }
    }

    private fun getOkRequest(): Request {
        return Request.Builder().url(url).headers(headers.toHeaders()).build()
    }

    private fun getOkRequestRange(startByte: Long?, endByte: Long?): Request {
        val end = endByte ?: ""
        val range = "bytes=$startByte-$end"

        return Request.Builder().url(url).headers(headers.toHeaders()).header("Range", range)
            .build()
    }

    private fun getContentLength(): Long {
        val req = getOkRequest()
        val response = client.newCall(req).execute()

        val contentLength = response.body.contentLength()
        response.body.close()

        return contentLength
    }

    data class Chunk(val chunkIndex: Int, val range: LongRange, val chunkSize: Long) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Chunk

            if (chunkIndex != other.chunkIndex) return false
            if (range != other.range) return false
            if (chunkSize != other.chunkSize) return false

            return true
        }

        override fun hashCode(): Int {
            var result = chunkIndex
            result = 31 * result + range.hashCode()
            result = 31 * result + chunkSize.hashCode()
            return result
        }
    }

    private object Helper {
        const val STOP_FILE_NAME = "stop"
        const val DOWNLOAD_BUFFER_SIZE = 1024

        fun unStop(fileToUnStop: File) {
            File(fileToUnStop.parentFile, STOP_FILE_NAME).delete()
        }

        fun isStopped(fileToCheck: File): Boolean {
            return File(
                fileToCheck.parentFile, STOP_FILE_NAME
            ).exists()
        }

        fun isCanceled(fileToCheck: File): Boolean {
            return !(fileToCheck.parentFile?.exists() ?: false)
        }
    }
}
