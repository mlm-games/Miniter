package org.mlm.miniter.project

import io.github.aakira.napier.Napier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.mlm.miniter.platform.PlatformFileSystem
import kotlin.time.Clock

class RecentProjectsRepository(private val json: Json) {

    private val _recents = MutableStateFlow<List<RecentProject>>(emptyList())
    val recents: StateFlow<List<RecentProject>> = _recents

    private val mutex = Mutex()

    private val maxRecents = 20

    private fun recentsFilePath(): String {
        val dir = PlatformFileSystem.getAppDataDirectory("miniter")
        return PlatformFileSystem.combinePath(dir, "recent_projects.json")
    }

    suspend fun load() {
        var pruned = false
        mutex.withLock {
            try {
                val path = recentsFilePath()
                if (!PlatformFileSystem.exists(path)) return
                val content = PlatformFileSystem.readText(path)
                val list = json.decodeFromString<List<RecentProject>>(content)
                val valid = list.filter { PlatformFileSystem.exists(it.path) }.take(maxRecents)
                pruned = valid.size != list.size
                _recents.value = valid
            } catch (e: Exception) {
                Napier.e("Failed to load recent projects", e)
                if (_recents.value.isEmpty()) {
                    _recents.value = emptyList()
                }
            }
        }
        if (pruned) save()
    }

    suspend fun addRecent(path: String, name: String) {
        mutex.withLock {
            val current = _recents.value
            val filtered = current.filter { it.path != path }
            val entry = RecentProject(
                path = path,
                name = name,
                lastOpenedAt = Clock.System.now().toEpochMilliseconds(),
            )
            _recents.value = (listOf(entry) + filtered).take(maxRecents)
            saveLocked()
        }
    }

    suspend fun removeRecent(path: String) {
        mutex.withLock {
            _recents.value = _recents.value.filter { r -> r.path != path }
            saveLocked()
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            _recents.value = emptyList()
            saveLocked()
        }
    }

    private suspend fun save() {
        mutex.withLock { saveLocked() }
    }

    private suspend fun saveLocked() {
        try {
            val content = json.encodeToString<List<RecentProject>>(_recents.value)
            PlatformFileSystem.writeText(recentsFilePath(), content)
        } catch (e: Exception) {
            Napier.e("Failed to save recent projects", e)
        }
    }
}
