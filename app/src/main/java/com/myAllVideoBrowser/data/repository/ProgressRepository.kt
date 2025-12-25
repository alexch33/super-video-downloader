package com.myAllVideoBrowser.data.repository

import com.myAllVideoBrowser.data.local.room.dao.ProgressDao
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import com.myAllVideoBrowser.di.qualifier.LocalData
import io.reactivex.rxjava3.core.Flowable
import javax.inject.Inject
import javax.inject.Singleton

interface ProgressRepository {

    fun getProgressInfos(): Flowable<List<ProgressInfo>>

    fun saveProgressInfo(progressInfo: ProgressInfo)

    fun deleteProgressInfo(progressInfo: ProgressInfo)
}

@Singleton
class ProgressRepositoryImpl @Inject constructor(
    @LocalData private val localDataSource: ProgressRepository
) : ProgressRepository {
    private var lastSavedInfo: ProgressInfo? = null

    private var lastDeletedInfo: ProgressInfo? = null


    override fun getProgressInfos(): Flowable<List<ProgressInfo>> {
        return localDataSource.getProgressInfos()
    }

    override fun saveProgressInfo(progressInfo: ProgressInfo) {
        if (progressInfo.hashCode() != lastSavedInfo.hashCode()) {
            lastSavedInfo = progressInfo
            localDataSource.saveProgressInfo(progressInfo)
        }
    }

    override fun deleteProgressInfo(progressInfo: ProgressInfo) {
        if (progressInfo.hashCode() != lastDeletedInfo.hashCode()) {
            lastDeletedInfo = progressInfo
            localDataSource.deleteProgressInfo(progressInfo)
        }
    }
}