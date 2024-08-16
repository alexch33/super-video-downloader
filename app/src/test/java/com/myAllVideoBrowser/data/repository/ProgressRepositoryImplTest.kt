package com.myAllVideoBrowser.data.repository

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.myAllVideoBrowser.data.local.ProgressLocalDataSource
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import io.reactivex.rxjava3.core.Flowable
import org.junit.Before
import org.junit.Test

class ProgressRepositoryImplTest {

    private lateinit var progressLocalDataSource: ProgressLocalDataSource

    private lateinit var progressRepository: ProgressRepositoryImpl

    private lateinit var progressInfo: ProgressInfo

    @Before
    fun setup() {
        progressLocalDataSource = mock()
        progressRepository = ProgressRepositoryImpl(progressLocalDataSource)
        progressInfo = ProgressInfo(id = "id", downloadId = 123, videoInfo = VideoInfo())
    }

    @Test
    fun `test get list downloading videos`() {
        doReturn(Flowable.just(listOf(progressInfo))).`when`(progressLocalDataSource).getProgressInfos()
        progressRepository.getProgressInfos()
            .test()
            .assertNoErrors()
            .assertValue { it == listOf(progressInfo) }
        verify(progressLocalDataSource).getProgressInfos()
    }

    @Test
    fun `test save downloading video`() {
        progressRepository.saveProgressInfo(progressInfo)
        verify(progressLocalDataSource).saveProgressInfo(progressInfo)
    }

    @Test
    fun `test delete downloading video`() {
        progressRepository.deleteProgressInfo(progressInfo)
        verify(progressLocalDataSource).deleteProgressInfo(progressInfo)
    }
}