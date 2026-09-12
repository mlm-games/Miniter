package org.mlm.miniter.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.rust.RustCoreRepository
import org.mlm.miniter.rust.RustCoreSession

class RustDispatchException(
    message: String,
    val commandJson: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

class RustNoSessionException(
    message: String = "No active Rust session",
) : IllegalStateException(message)

class RustProjectStore(
    private val repository: RustCoreRepository,
) {
    private val wireJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        classDiscriminator = "type"
    }

    val commands = RustCommandJson(wireJson)

    private val _snapshot = MutableStateFlow<RustProjectSnapshot?>(null)
    val snapshot: StateFlow<RustProjectSnapshot?> = _snapshot.asStateFlow()

    fun currentSessionOrNull(): RustCoreSession? = repository.currentOrNull()

    fun create(projectName: String): RustProjectSnapshot {
        repository.create(projectName)
        return refresh()
    }

    fun openProjectJson(json: String): RustProjectSnapshot {
        repository.open(json)
        return refresh()
    }

    fun replaceSnapshot(snapshot: RustProjectSnapshot): RustProjectSnapshot {
        val rawJson = wireJson.encodeToString(RustProjectSnapshot.serializer(), snapshot)
        repository.open(rawJson)
        return refresh()
    }

    @Throws(RustNoSessionException::class)
    fun exportProjectJson(): String {
        return repository.withSession { session ->
            session.toJson()
        } ?: throw RustNoSessionException("Cannot export project: no active Rust session")
    }

    @Throws(RustDispatchException::class, RustNoSessionException::class)
    fun dispatch(commandJson: String): RustProjectSnapshot =
        dispatchOrThrow(commandJson)

    @Throws(RustDispatchException::class, RustNoSessionException::class)
    fun dispatchOrThrow(commandJson: String): RustProjectSnapshot {
        val applied = repository.withSession { session ->
            session.dispatch(commandJson)
        } ?: throw RustNoSessionException("Cannot dispatch command: no active Rust session")
        if (!applied) {
            throw RustDispatchException("Rust rejected command (dispatch returned false)", commandJson)
        }
        return refresh()
    }

    fun tryDispatch(commandJson: String): Boolean {
        return try {
            dispatchOrThrow(commandJson)
            true
        } catch (_: Exception) {
            false
        }
    }

    @Throws(RustDispatchException::class, RustNoSessionException::class)
    fun dispatchWithLabel(commandJson: String, label: String): RustProjectSnapshot {
        val applied = repository.withSession { session ->
            session.dispatchWithLabel(commandJson, label)
        } ?: throw RustNoSessionException("Cannot dispatch labeled command: no active Rust session")
        if (!applied) {
            throw RustDispatchException("Rust rejected labeled command '$label' (dispatch returned false)", commandJson)
        }
        return refresh()
    }

    fun beginEdit(label: String) {
        repository.withSession { it.beginEdit(label) }
    }

    @Throws(RustDispatchException::class, RustNoSessionException::class)
    fun dispatchOpen(commandJson: String): RustProjectSnapshot {
        val applied = repository.withSession { session ->
            session.dispatchOpen(commandJson)
        } ?: throw RustNoSessionException("Cannot dispatch open-edit command: no active Rust session")
        if (!applied) {
            throw RustDispatchException("Rust rejected open-edit command (dispatch returned false)", commandJson)
        }
        return refresh()
    }

    fun commitEdit(): RustProjectSnapshot? {
        val committed = repository.withSession {
            it.commitEdit()
            true
        } ?: return null
        if (!committed) return null
        return refresh()
    }

    fun cancelEdit(): RustProjectSnapshot? {
        val cancelled = repository.withSession { session ->
            session.cancelEdit()
        } ?: return null
        if (!cancelled) return null
        return refresh()
    }

    fun undo(): RustProjectSnapshot? {
        val moved = repository.withSession { session ->
            session.undo()
        } ?: return null
        if (!moved) return null
        return refresh()
    }

    fun redo(): RustProjectSnapshot? {
        val moved = repository.withSession { session ->
            session.redo()
        } ?: return null
        if (!moved) return null
        return refresh()
    }

    fun canUndo(): Boolean = repository.currentOrNull()?.canUndo() ?: false

    fun canRedo(): Boolean = repository.currentOrNull()?.canRedo() ?: false

    fun undoLabel(): String? = repository.currentOrNull()?.undoLabel()
    fun redoLabel(): String? = repository.currentOrNull()?.redoLabel()
    fun undoDepth(): UInt = repository.currentOrNull()?.undoDepth() ?: 0u
    fun redoDepth(): UInt = repository.currentOrNull()?.redoDepth() ?: 0u
    fun transactionOpen(): Boolean = repository.currentOrNull()?.transactionOpen() ?: false

    fun playheadUs(): Long = repository.currentOrNull()?.playheadUs() ?: 0L

    fun setPlayheadUs(us: Long) {
        repository.currentOrNull()?.setPlayheadUs(us)
    }

    fun durationUs(): Long = repository.currentOrNull()?.durationUs() ?: 0L

    fun renderPlanAtPlayhead(width: Int, height: Int): String? {
        return repository.currentOrNull()?.renderPlanAtPlayhead(width, height)
    }

    fun validateRenderPlanAtPlayhead(width: Int, height: Int): String? {
        return repository.currentOrNull()?.validateRenderPlanAtPlayhead(width, height)
    }

    fun clear() {
        repository.clearAtomically {
            _snapshot.value = null
        }
    }

    @Throws(RustNoSessionException::class)
    private fun refresh(): RustProjectSnapshot {
        val json = repository.withSession { session ->
            session.toJson()
        } ?: throw RustNoSessionException("Cannot refresh snapshot: no active Rust session")
        val snapshot = wireJson.decodeFromString(RustProjectSnapshot.serializer(), json)
        _snapshot.value = snapshot
        return snapshot
    }
}
