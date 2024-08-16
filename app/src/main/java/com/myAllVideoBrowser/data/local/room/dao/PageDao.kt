package com.myAllVideoBrowser.data.local.room.dao

import androidx.room.*
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import io.reactivex.rxjava3.core.Observable

@Dao
interface PageDao {

    @Query("SELECT * FROM PageInfo")
    fun getPageInfos(): Observable<List<PageInfo>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProgressInfo(progressInfo: PageInfo)

    @Delete
    fun deleteProgressInfo(progressInfo: PageInfo)
}