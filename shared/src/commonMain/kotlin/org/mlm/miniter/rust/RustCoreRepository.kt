package org.mlm.miniter.rust

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
}
