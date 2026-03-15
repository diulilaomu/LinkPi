package com.example.link_pi.data

import android.content.Context
import com.example.link_pi.data.model.ManagedSession
import com.example.link_pi.data.model.SessionStatus
import com.example.link_pi.data.model.SessionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SessionRegistry private constructor(context: Context) {

    private val storage = SessionStorage(context)
    private val lock = Any()

    private val _sessions = MutableStateFlow<List<ManagedSession>>(emptyList())
    val sessions: StateFlow<List<ManagedSession>> = _sessions.asStateFlow()

    private val _pausedSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val pausedSessionIds: StateFlow<Set<String>> = _pausedSessionIds.asStateFlow()

    private val ioScope = kotlinx.coroutines.CoroutineScope(
        Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    companion object {
        @Volatile
        private var instance: SessionRegistry? = null

        fun getInstance(context: Context): SessionRegistry {
            return instance ?: synchronized(this) {
                instance ?: SessionRegistry(context.applicationContext).also { instance = it }
            }
        }
    }

    suspend fun load() = withContext(Dispatchers.IO) {
        val all = storage.loadAll()
        synchronized(lock) {
            _sessions.value = all
            _pausedSessionIds.value = all.filter { it.status == SessionStatus.PAUSED }.map { it.id }.toSet()
        }
    }

    fun register(session: ManagedSession) {
        synchronized(lock) {
            _sessions.value = listOf(session) + _sessions.value.filter { it.id != session.id }
        }
        ioScope.launch { storage.save(session) }
    }

    fun update(session: ManagedSession) {
        val updated = session.copy(updatedAt = System.currentTimeMillis())
        synchronized(lock) {
            _sessions.value = _sessions.value.map { if (it.id == updated.id) updated else it }
        }
        ioScope.launch { storage.save(updated) }
    }

    fun endSession(id: String) {
        synchronized(lock) {
            val session = _sessions.value.find { it.id == id } ?: return
            val ended = session.copy(status = SessionStatus.ENDED, updatedAt = System.currentTimeMillis())
            _sessions.value = _sessions.value.map { if (it.id == id) ended else it }
            _pausedSessionIds.value = _pausedSessionIds.value - id
            ioScope.launch { storage.save(ended) }
        }
    }

    fun pauseSession(id: String) {
        synchronized(lock) {
            val session = _sessions.value.find { it.id == id } ?: return
            if (session.status != SessionStatus.ACTIVE) return
            val paused = session.copy(status = SessionStatus.PAUSED, updatedAt = System.currentTimeMillis())
            _sessions.value = _sessions.value.map { if (it.id == id) paused else it }
            _pausedSessionIds.value = _pausedSessionIds.value + id
            ioScope.launch { storage.save(paused) }
        }
    }

    fun resumeSession(id: String) {
        synchronized(lock) {
            val session = _sessions.value.find { it.id == id } ?: return
            if (session.status != SessionStatus.PAUSED) return
            val active = session.copy(status = SessionStatus.ACTIVE, updatedAt = System.currentTimeMillis())
            _sessions.value = _sessions.value.map { if (it.id == id) active else it }
            _pausedSessionIds.value = _pausedSessionIds.value - id
            ioScope.launch { storage.save(active) }
        }
    }

    fun deleteSession(id: String) {
        synchronized(lock) {
            _sessions.value = _sessions.value.filter { it.id != id }
            _pausedSessionIds.value = _pausedSessionIds.value - id
        }
        ioScope.launch { storage.delete(id) }
    }

    fun cleanupEnded(maxAge: Long = 7 * 24 * 3600 * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAge
        synchronized(lock) {
            _sessions.value = _sessions.value.filter {
                !(it.status == SessionStatus.ENDED && it.updatedAt < cutoff)
            }
        }
        ioScope.launch { storage.cleanup(maxAge) }
    }

    fun isPaused(id: String): Boolean = id in _pausedSessionIds.value

    fun getByType(type: SessionType): List<ManagedSession> =
        _sessions.value.filter { it.type == type }
}
