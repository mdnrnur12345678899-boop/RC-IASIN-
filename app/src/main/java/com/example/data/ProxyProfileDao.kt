package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyProfileDao {
    @Query("SELECT * FROM proxy_profiles ORDER BY isLastUsed DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ProxyProfile>>

    @Query("SELECT * FROM proxy_profiles WHERE isLastUsed = 1 LIMIT 1")
    suspend fun getLastUsed(): ProxyProfile?

    @Query("SELECT * FROM proxy_profiles ORDER BY isLastUsed DESC, updatedAt DESC LIMIT 1")
    suspend fun getLatestAny(): ProxyProfile?

    @Query("SELECT * FROM proxy_profiles WHERE host = :host AND port = :port AND protocol = :protocol LIMIT 1")
    suspend fun findMatching(host: String, port: Int, protocol: ProxyProtocol): ProxyProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ProxyProfile): Long

    @Query("UPDATE proxy_profiles SET isLastUsed = 0")
    suspend fun clearLastUsed()

    @Query("UPDATE proxy_profiles SET isLastUsed = 1, updatedAt = :timestamp WHERE id = :id")
    suspend fun markLastUsed(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE proxy_profiles SET lastLatencyMs = :latencyMs, lastExitIp = :exitIp, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateVerification(
        id: Long,
        latencyMs: Long,
        exitIp: String,
        timestamp: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM proxy_profiles WHERE id = :id")
    suspend fun deleteById(id: Long)
}
