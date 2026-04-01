package org.mlm.miniter.rust

class RustCoreRepository {
    private var current: RustCoreSession? = null

    fun currentOrNull(): RustCoreSession? = current

    fun create(projectName: String): RustCoreSession {
        val session = RustCoreSession(projectName)
        current = session
        return session
    }

    fun open(json: String): RustCoreSession {
        val session = RustCoreSession.fromJson(json)
        current = session
        return session
    }

    fun clear() {
        current = null
    }
}
