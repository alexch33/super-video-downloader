package com.myAllVideoBrowser.ui.main.progress

import android.app.DownloadManager
import android.database.Cursor
import com.nhaarman.mockito_kotlin.*
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import com.myAllVideoBrowser.data.repository.ProgressRepository
import com.myAllVideoBrowser.util.FileUtil
import com.myAllVideoBrowser.util.scheduler.BaseSchedulers
import com.myAllVideoBrowser.util.scheduler.StubbedSchedulers
import com.myAllVideoBrowser.waitUntil
import io.reactivex.rxjava3.core.Flowable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import java.io.File
import java.util.concurrent.Callable

class ProgressViewModelTest {

    private lateinit var downloadManager: DownloadManager

    private lateinit var fileUtil: FileUtil

    private lateinit var file: File

    private lateinit var cursor: Cursor

    private lateinit var baseSchedulers: BaseSchedulers

    private lateinit var progresRepository: ProgressRepository

    private lateinit var progressViewModel: ProgressViewModel

    private lateinit var progressInfo1: ProgressInfo

    private lateinit var progressInfo2: ProgressInfo

    private lateinit var videoInfo: VideoInfo

    @Before
    fun setup() {
        downloadManager = mock()
        fileUtil = mock()
        file = mock()
        cursor = mock()
        baseSchedulers = StubbedSchedulers()
        progresRepository = mock()
        progressViewModel = spy(ProgressViewModel(fileUtil, progresRepository))

        videoInfo = VideoInfo(id = "id", downloadUrls = emptyList())
        progressInfo1 = ProgressInfo(downloadId = 1, videoInfo = videoInfo)
        progressInfo2 = ProgressInfo(downloadId = 2, videoInfo = videoInfo)
    }

//    @Test
//    fun `test attach and detach ProgressViewModel`() {
//        doReturn(Flowable.just(listOf(progressInfo1, progressInfo2))).`when`(progresRepository).getProgressInfos()
//        progressViewModel.start()
//
//        assertNotNull(progressViewModel.compositeDisposable)
//        verify(progressViewModel, times(2)).downloadProgressStartListen()
//
//        progressViewModel.stop()
//        assertEquals(0, progressViewModel.compositeDisposable.size())
//    }

    @Test
    fun `fail to create video folder should not download video`() {
        doReturn(file).`when`(fileUtil).folderDir
        doReturn(false).`when`(file).exists()
        doReturn(false).`when`(file).mkdirs()
        progressViewModel.downloadVideo(videoInfo)

        verify(downloadManager, never()).enqueue(any())
    }

//    @Test
//    fun `download video should show progress`() {
//        doReturn(Flowable.just(listOf<ProgressInfo>())).`when`(progresRepository).getProgressInfos()
//        progressViewModel.start()
//
//        doReturn(file).`when`(fileUtil).folderDir
//        doReturn(true).`when`(file).exists()
//        val downloadId = 123L
//        doReturn(downloadId).`when`(downloadManager).enqueue(any())
//
//        progressViewModel.downloadVideo(videoInfo)
//
//        verify(downloadManager).enqueue(any())
//        verify(progresRepository).saveProgressInfo(argThat {
//            this.downloadId == downloadId
//            this.videoInfo == videoInfo
//        })
//        verify(progressViewModel).downloadProgressStartListen(argThat {
//            this.downloadId == downloadId
//            this.videoInfo == videoInfo
//        })
//    }

//    @Test
//    fun `download new video should update list downloading videos`() {
//        doReturn(Flowable.just(listOf<ProgressInfo>())).`when`(progresRepository).getProgressInfos()
//        progressViewModel.start()
//
//        val status = DownloadManager.STATUS_RUNNING
//        doReturn(cursor).`when`(downloadManager).query(any())
//        doReturn(status).`when`(cursor).getInt(anyInt())
//        progressViewModel.downloadProgressStartListen(progressInfo1)
//
//        waitUntil("Wait for emitting progress status", Callable {
//            assertEquals(1, progressViewModel.progressInfos.size)
//            assertEquals(progressInfo1, progressViewModel.progressInfos[0])
//            true
//        }, 2000)
//
//    }

//    @Test
//    fun `download video should update progress until downloading complete`() {
//        doReturn(Flowable.just(listOf<ProgressInfo>())).`when`(progresRepository).getProgressInfos()
//        progressViewModel.start()
//
//
//        progressViewModel.progressInfos.add(progressInfo1)
//        val status = DownloadManager.STATUS_RUNNING
//        doReturn(cursor).`when`(downloadManager).query(any())
//        doReturn(status).`when`(cursor).getInt(anyInt())
//        progressViewModel.downloadProgressStartListen(progressInfo1)
//
//        waitUntil("Wait for emitting progress status", Callable {
//            assertEquals(1, progressViewModel.progressInfos.size)
//            assertEquals(progressInfo1, progressViewModel.progressInfos[0])
//            true
//        }, 2000)
//    }

//    @Test
//    fun `download completed should remove progress of downloaded item`() {
//        doReturn(Flowable.just(listOf<ProgressInfo>())).`when`(progresRepository).getProgressInfos()
//        progressViewModel.start()
//
//
//        progressViewModel.progressInfos.add(progressInfo1)
//        val status = DownloadManager.STATUS_SUCCESSFUL
//        doReturn(cursor).`when`(downloadManager).query(any())
//        doReturn(status).`when`(cursor).getInt(anyInt())
//        progressViewModel.downloadProgressStartListen(progressInfo1)
//
//        waitUntil("Wait for emitting progress status", Callable {
//            assertEquals(0, progressViewModel.progressInfos.size)
//            verify(progresRepository).deleteProgressInfo(progressInfo1)
//            true
//        }, 2000)
//    }

//    @Test
//    fun `download failed should remove progress of downloaded item`() {
//        doReturn(Flowable.just(listOf<ProgressInfo>())).`when`(progresRepository).getProgressInfos()
//        progressViewModel.start()
//
//
//        progressViewModel.progressInfos.add(progressInfo1)
//        val status = DownloadManager.STATUS_FAILED
//        doReturn(cursor).`when`(downloadManager).query(any())
//        doReturn(status).`when`(cursor).getInt(anyInt())
//        progressViewModel.downloadProgressStartListen(progressInfo1)
//
//        waitUntil("Wait for emitting progress status", Callable {
//            assertEquals(0, progressViewModel.progressInfos.size)
//            true
//        }, 2000)
//    }
}