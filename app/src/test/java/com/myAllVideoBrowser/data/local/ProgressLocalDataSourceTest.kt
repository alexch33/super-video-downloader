package com.myAllVideoBrowser.data.local

import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.myAllVideoBrowser.data.local.room.dao.ProgressDao
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.data.local.room.entity.VideoInfo
import io.reactivex.rxjava3.core.Flowable
import org.junit.Before
import org.junit.Test

class ProgressLocalDataSourceTest {

    private lateinit var progressDao: ProgressDao

    private lateinit var progressLocalDataSource: ProgressLocalDataSource

    private lateinit var progressInfo: ProgressInfo

    @Before
    fun setup() {
        progressDao = mock()
        progressLocalDataSource = ProgressLocalDataSource(progressDao)
        progressInfo = ProgressInfo(id = "id", downloadId = 123, videoInfo = VideoInfo())
    }

    @Test
    fun `test get list downloading videos`() {
        doReturn(Flowable.just(listOf(progressInfo))).`when`(progressDao).getProgressInfos()
        progressLocalDataSource.getProgressInfos()
            .test()
            .assertNoErrors()
            .assertValue { it == listOf(progressInfo) }
        verify(progressDao).getProgressInfos()
    }

    @Test
    fun `test get save downloading video`() {
        progressLocalDataSource.saveProgressInfo(progressInfo)
        verify(progressDao).insertProgressInfo(progressInfo)
    }

    @Test
    fun `test delete downloading video`() {
        progressLocalDataSource.deleteProgressInfo(progressInfo)
        verify(progressDao).deleteProgressInfo(progressInfo)
    }
}