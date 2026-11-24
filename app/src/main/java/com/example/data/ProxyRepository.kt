package com.example.data

import kotlinx.coroutines.flow.Flow

class ProxyRepository(private val dao: ProxyProfileDao) {
    val profiles: Flow<List<ProxyProfile>> = dao.observeAll()

    suspend fun getLastUsed(): ProxyProfile? = dao.getLastUsed()

    suspend fun saveProfile(profile: ProxyProfile): Long {
        dao.clearLastUsed()
        val existing = if (profile.id == 0L) {
            dao.findMatching(profile.host, profile.port, profile.protocol)
        } else null
        val targetId = if (profile.id != 0L) profile.id else (existing?.id ?: 0L)
        return dao.upsert(
            profile.copy(
                id = targetId,
                isLastUsed = true,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun markLastUsed(id: Long) {
        dao.clearLastUsed()
        dao.markLastUsed(id)
    }

    suspend fun updateVerification(id: Long, latencyMs: Long, exitIp: String) {
        dao.updateVerification(id, latencyMs, exitIp)
    }

    suspend fun deleteProfile(id: Long) {
        dao.deleteById(id)
    }
}
