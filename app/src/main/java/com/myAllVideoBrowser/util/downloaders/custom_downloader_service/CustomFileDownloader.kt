package com.myAllVideoBrowser.util.downloaders.custom_downloader_service

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
    private val headers: MutableMap<String, String>,
    private val client: OkHttpClient,
    private val listener: DownloadListener?,
) : DownloadListener {
    private val executorService: ExecutorService = Executors.newFixedThreadPool(threadCount)
    private val isPaused = AtomicBoolean(false)
    private val isCanceled = AtomicBoolean(false)
    private var lastProgressUpdate = AtomicLong(0L)
    private val totalBytesAll = AtomicLong(0L)
    private val totalBytesChunks = AtomicLongArray(threadCount)
    private val copiedBytesChunks = AtomicLongArray(threadCount)
    private val copiedBytesSingle = AtomicLong(0L)
    private val callBackIntervalMin = 1000

    companion object {
        const val STOPPED = "STOPPED"
        const val CANCELED = "CANCELED"
        private const val STOP_FILE_NAME = "stop"
        private const val DOWNLOAD_BUFFER_SIZE = 1024

        fun stop(fileToStop: File) {
            File(fileToStop.parentFile, STOP_FILE_NAME).createNewFile()
        }

        fun cancel(fileToStop: File) {
            fileToStop.parentFile?.deleteRecursively()
        }

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

        unStop(file)

        val isUrlSupportBytesRangeHeader = isUrlSupportingBytesRangeHeader()

        if (!isUrlSupportBytesRangeHeader) {
            val result = executorService.submit {
                this.onFailure(Exception("Connection Error or download type not supported, try again"))
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

    override fun onSuccess() {
        executorService.shutdown()

        AppLogger.d("DOWNLOAD SUCCESS: $file")

        listener?.onSuccess()
    }

    override fun onFailure(e: Throwable) {
        executorService.shutdown()

        AppLogger.e("Task Download Failed $e")

        listener?.onFailure(e)
    }

    override fun onProgressUpdate(downloadedBytes: Long, totalBytes: Long) {
        val time = Date().time
        if (time - lastProgressUpdate.get() >= callBackIntervalMin) {
            isPaused.set(isStopped(file))
            isCanceled.set(isCanceled(file))
            lastProgressUpdate.set(time)
            listener?.onProgressUpdate(downloadedBytes, totalBytes)
        }
    }

    override fun onChunkProgressUpdate(downloadedBytes: Long, allBytes: Long, chunkIndex: Int) {
        copiedBytesChunks[chunkIndex] = downloadedBytes

        onProgressUpdate(totalCopiedBytes, totalBytesAll.get())

        listener?.onChunkProgressUpdate(downloadedBytes, allBytes, chunkIndex)
    }

    override fun onChunkFailure(e: Throwable, index: Chunk) {
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
        } else
            getOkRequestRange(range.first + bytesCopied, range.last)
        val res = client.newCall(req).execute()

        if (res.body.contentLength() == -1L) {
            throw Error("Content Length Not Found")
        }

        val inputStream = res.body.byteStream()
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)

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
                if (isStopped(file)) {
                    throw Exception(STOPPED)
                }
                if (isCanceled(file)) {
                    throw Exception(CANCELED)
                }
            }
        }
    }

    private fun singleThreadDownload() {
        val chunkFile = File(file.parentFile, "chunk_s")

        var bytesCopied = 0L
        AppLogger.d(
            "SINGLE THREAD DOWNLOAD START AT $bytesCopied"
        )

        val req = getOkRequest()
        val res = client.newCall(req).execute()

        val inputStream = res.body.byteStream()
        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)

        copiedBytesSingle.set(bytesCopied)
        totalBytesAll.set(res.body.contentLength())

        var bytesRead = 0

        chunkFile.outputStream().channel.use { chunkChannel ->
            RandomAccessFile(file, "rw").channel.use { fileChannel ->
                inputStream.use { urlStream ->
                    while (!isPaused.get() && !isCanceled.get() && (urlStream.read(buffer)
                            .also { bytesRead = it }) >= 0
                    ) {
                        fileChannel.write(ByteBuffer.wrap(buffer), bytesCopied)
                        bytesCopied += bytesRead
                        chunkChannel.write(ByteBuffer.wrap("$bytesCopied".toByteArray()), 0)
                        this.onProgressUpdate(bytesCopied, totalBytesAll.get())
                    }

                    executorService.shutdown()

                    if (isStopped(file)) {
                        AppLogger.d("SINGLE THREAD DOWNLOAD STOPPED")
                        throw Exception(STOPPED)
                    }
                    if (isCanceled(file)) {
                        AppLogger.d("SINGLE THREAD DOWNLOAD CANCELED")
                        throw Exception(CANCELED)
                    }
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

        return Request.Builder().url(url).headers(headers.toHeaders())
            .header("Range", range).build()
    }

    private fun getContentLength(): Long {
        val req = getOkRequest()
        val response = client.newCall(req).execute()

        return response.body.contentLength()
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
}
