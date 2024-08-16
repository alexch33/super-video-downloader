package com.myAllVideoBrowser.data.local.room.dao

import androidx.room.*
import com.myAllVideoBrowser.data.local.room.entity.HistoryItem
import io.reactivex.rxjava3.core.Flowable

@Dao
interface HistoryDao {

    @Query("SELECT * FROM HistoryItem")
    fun getHistory(): Flowable<List<HistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertHistoryItem(item: HistoryItem)

    @Delete
    fun deleteHistoryItem(historyItem: HistoryItem)

    @Query("DELETE FROM HistoryItem")
    fun clear()
}