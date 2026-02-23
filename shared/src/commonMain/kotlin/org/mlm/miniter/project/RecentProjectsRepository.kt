package org.mlm.miniter.project

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import org.mlm.miniter.platform.PlatformFileSystem
import kotlin.time.Clock

class RecentProjectsRepository(private val json: Json) {

    private val _recents = MutableStateFlow<List<RecentProject>>(emptyList())
    val recents: StateFlow<List<RecentProject>> = _recents

    private val maxRecents = 20

    private fun recentsFilePath(): String {
        val dir = PlatformFileSystem.getAppDataDirectory("miniter")
        return PlatformFileSystem.combinePath(dir, "recent_projects.json")
    }

    suspend fun load() {
        try {
            val path = recentsFilePath()
            if (!PlatformFileSystem.exists(path)) return
            val content = PlatformFileSystem.readText(path)
            val list = json.decodeFromString<List<RecentProject>>(content)
            val valid = list.filter { PlatformFileSystem.exists(it.path) }
            _recents.value = valid
        } catch (e: Exception) {
            e.printStackTrace()
            if (_recents.value.isEmpty()) {
                _recents.value = emptyList()
            }
        }
    }

    suspend fun addRecent(path: String, name: String) {
        _recents.update { current ->
            val filtered = current.filter { it.path != path }
            val entry = RecentProject(
                path = path,
                name = name,
                lastOpenedAt = Clock.System.now().toEpochMilliseconds(),
            )
            (listOf(entry) + filtered).take(maxRecents)
        }
        save()
    }

    suspend fun removeRecent(path: String) {
        _recents.update { it.filter { r -> r.path != path } }
        save()
    }

    suspend fun clearAll() {
        _recents.value = emptyList()
        save()
    }

    private suspend fun save() {
        try {
            val content = json.encodeToString<List<RecentProject>>(_recents.value)
            PlatformFileSystem.writeText(recentsFilePath(), content)
        } catch (_: Exception) { }
    }
}
