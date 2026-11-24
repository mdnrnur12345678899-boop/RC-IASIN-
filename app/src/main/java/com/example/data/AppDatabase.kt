package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class ProxyConverters {
    @TypeConverter
    fun fromProtocol(protocol: ProxyProtocol): String = protocol.name

    @TypeConverter
    fun toProtocol(value: String): ProxyProtocol = ProxyProtocol.fromString(value)
}

@Database(entities = [ProxyProfile::class], version = 1, exportSchema = false)
@TypeConverters(ProxyConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun proxyProfileDao(): ProxyProfileDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "netrelay_database.db"
                ).build().also { instance = it }
            }
        }
    }
}
