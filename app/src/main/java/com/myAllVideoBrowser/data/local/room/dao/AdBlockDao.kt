package com.myAllVideoBrowser.data.local.room.dao

import androidx.room.*
import com.myAllVideoBrowser.data.local.room.entity.AdBlockList
import kotlinx.coroutines.flow.Flow

@Dao
interface AdBlockDao {
    @Query("SELECT * FROM adblock_lists")
    fun getAllLists(): Flow<List<AdBlockList>>

    @Query("SELECT * FROM adblock_lists WHERE isEnabled = 1")
    suspend fun getEnabledLists(): List<AdBlockList>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(list: AdBlockList)

    @Update
    suspend fun update(list: AdBlockList)

    @Delete
    suspend fun delete(list: AdBlockList)

    @Query("SELECT * FROM adblock_lists WHERE id = :id")
    suspend fun getListById(id: Int): AdBlockList?

    @Query("SELECT COUNT(*) FROM adblock_lists")
    suspend fun getCount(): Int
}
