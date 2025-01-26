package com.myAllVideoBrowser.data.local.room.dao

import androidx.room.*
import com.myAllVideoBrowser.data.local.room.entity.PageInfo
import io.reactivex.rxjava3.core.Observable

@Dao
interface PageDao {

    @Query("SELECT * FROM PageInfo ORDER BY `order` ASC")
    fun getPageInfos(): Observable<List<PageInfo>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProgressInfo(progressInfo: PageInfo)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllProgressInfo(progressInfos: List<PageInfo>)

    @Delete
    fun deleteProgressInfo(progressInfo: PageInfo)

    @Query("DELETE FROM PageInfo")
    fun deleteAll()
}