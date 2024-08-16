package com.myAllVideoBrowser.data.local.room.dao

import androidx.room.*
import com.myAllVideoBrowser.data.local.room.entity.ProgressInfo
import io.reactivex.rxjava3.core.Flowable

@Dao
interface ProgressDao {

    @Query("SELECT * FROM ProgressInfo")
    fun getProgressInfos(): Flowable<List<ProgressInfo>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProgressInfo(progressInfo: ProgressInfo)

    @Delete
    fun deleteProgressInfo(progressInfo: ProgressInfo)
}