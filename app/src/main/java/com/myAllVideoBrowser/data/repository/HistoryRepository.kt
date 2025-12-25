package com.myAllVideoBrowser.data.repository

import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import com.myAllVideoBrowser.di.qualifier.LocalData
import io.reactivex.rxjava3.core.Flowable
import javax.inject.Inject
import javax.inject.Singleton

interface HistoryRepository {
    fun getAllHistory() : Flowable<List<HistoryItem>>

    fun saveHistory(history: HistoryItem)

    fun deleteHistory(history: HistoryItem)

    fun deleteAllHistory()
}

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    @LocalData private val localDataSource: HistoryRepository
) : HistoryRepository {
    override fun getAllHistory(): Flowable<List<HistoryItem>> {
        return localDataSource.getAllHistory()
    }

    override fun saveHistory(history: HistoryItem) {
        localDataSource.saveHistory(history)
    }

    override fun deleteHistory(history: HistoryItem) {
        localDataSource.deleteHistory(history)
    }

    override fun deleteAllHistory() {
        localDataSource.deleteAllHistory()
    }

}