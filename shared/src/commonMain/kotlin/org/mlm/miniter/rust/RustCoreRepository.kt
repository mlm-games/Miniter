package org.mlm.miniter.rust

import org.mlm.miniter.platform.synchronized

class RustCoreRepository {
    private var current: RustCoreSession? = null
    private val lock = Any()

    fun currentOrNull(): RustCoreSession? = synchronized(lock) { current }

    fun create(projectName: String): RustCoreSession {
        val session = RustCoreSession(projectName)
        synchronized(lock) { current = session }
        return session
    }

    fun open(json: String): RustCoreSession {
        val session = RustCoreSession.fromJson(json)
        synchronized(lock) { current = session }
        return session
    }

    fun clear() {
        synchronized(lock) { current = null }
    }

    fun <T> withSession(block: (RustCoreSession) -> T): T? =
        synchronized(lock) {
            val session = current ?: return@synchronized null
            block(session)
        }

    fun clearAtomically(afterClear: () -> Unit) {
        synchronized(lock) {
            current = null
            afterClear()
        }
    }
}
