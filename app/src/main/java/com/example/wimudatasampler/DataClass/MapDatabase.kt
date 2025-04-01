package com.example.wimudatasampler.DataClass

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.wimudatasampler.DataClass.dao.MapDao
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.synchronized

@Database(
    entities = [
        MapModels.ImageMap::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MapDatabase : RoomDatabase() {
    abstract fun mapDao(): MapDao

    companion object {
        private var INSTANCE: MapDatabase? = null

        @OptIn(InternalCoroutinesApi::class)
        fun getInstance(context: Context): MapDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    MapDatabase::class.java,
                    "MapDatabase.db"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            //NOTHING
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

