package com.example.wimudatasampler.DataClass.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.wimudatasampler.DataClass.MapModels
import kotlinx.coroutines.flow.Flow


@Dao
interface MapDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(map: MapModels.ImageMap): Long

    @Update
    suspend fun update(map: MapModels.ImageMap): Int

    @Query("SELECT * FROM maps WHERE map_id = :mapId LIMIT 1")
    suspend fun getEntryById(mapId: String): MapModels.ImageMap?

    @Query("SELECT * FROM maps WHERE is_selected = 1 LIMIT 1")
    suspend fun getSelectedMap(): MapModels.ImageMap?

    @Query("SELECT * FROM maps")
    fun getAllMaps(): Flow<List<MapModels.ImageMap>>

    @Query("DELETE FROM maps WHERE map_id = :mapId")
    suspend fun delete(mapId: String): Int

    @Transaction
    suspend fun selectMap(map: MapModels.ImageMap) {
        resetAllSelections()
        setMapSelected(map.mapId)
    }

    @Query("UPDATE maps SET is_selected = 0")
    suspend fun resetAllSelections()

    @Query("UPDATE maps SET is_selected = 1 WHERE map_id = :mapId")
    suspend fun setMapSelected(mapId: String)

}
