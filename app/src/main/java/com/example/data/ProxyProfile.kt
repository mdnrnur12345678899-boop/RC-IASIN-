package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxy_profiles")
data class ProxyProfile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: ProxyProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val isLastUsed: Boolean = false,
    val lastLatencyMs: Long? = null,
    val lastExitIp: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toConfig(): ProxyConfig = ProxyConfig(
        protocol = protocol,
        host = host,
        port = port,
        username = username,
        password = password,
        label = name
    )
}
