package org.mlm.miniter.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.rust.RustCoreRepository
import org.mlm.miniter.rust.RustCoreSession

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

    fun exportProjectJson(): String {
        val session = repository.currentOrNull() ?: error("No active Rust session")
        return session.toJson()
    }

    fun dispatch(commandJson: String): RustProjectSnapshot {
        val session = repository.currentOrNull() ?: error("No active Rust session")
        session.dispatch(commandJson)
        return refresh()
    }

    fun undo(): RustProjectSnapshot? {
        val session = repository.currentOrNull() ?: return null
        session.undo()
        return refresh()
    }

    fun redo(): RustProjectSnapshot? {
        val session = repository.currentOrNull() ?: return null
        session.redo()
        return refresh()
    }

    fun canUndo(): Boolean = repository.currentOrNull()?.canUndo() ?: false

    fun canRedo(): Boolean = repository.currentOrNull()?.canRedo() ?: false

    fun playheadUs(): Long = repository.currentOrNull()?.playheadUs() ?: 0L

    fun setPlayheadUs(us: Long) {
        repository.currentOrNull()?.setPlayheadUs(us)
    }

    fun durationUs(): Long = repository.currentOrNull()?.durationUs() ?: 0L

    fun renderPlanAtPlayhead(width: Int, height: Int): String? {
        return repository.currentOrNull()?.renderPlanAtPlayhead(width, height)
    }

    fun clear() {
        repository.clear()
        _snapshot.value = null
    }

    private fun refresh(): RustProjectSnapshot {
        val session = repository.currentOrNull() ?: error("No active Rust session")
        val snapshot = wireJson.decodeFromString(RustProjectSnapshot.serializer(), session.toJson())
        _snapshot.value = snapshot
        return snapshot
    }
}
