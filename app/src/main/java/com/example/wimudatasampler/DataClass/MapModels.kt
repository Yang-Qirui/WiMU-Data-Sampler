package com.example.wimudatasampler.DataClass

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

object MapModels {
    @Entity(tableName = "maps")
    data class ImageMap(
        @PrimaryKey
        @ColumnInfo(name = "map_id")
        val mapId: String = UUID.randomUUID().toString(),
        val name: String = "",
        val content: String = "",
        val metersForMapWidth: Float = 0.0f,
        @ColumnInfo(name = "is_selected")
        val isSelected: Boolean = false,
    )
}