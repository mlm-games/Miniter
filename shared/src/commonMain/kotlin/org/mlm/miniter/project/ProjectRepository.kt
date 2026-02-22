package org.mlm.miniter.project

import kotlinx.serialization.json.Json
import org.mlm.miniter.platform.PlatformFileSystem
import kotlin.time.Clock

class ProjectRepository(private val json: Json) {

    suspend fun save(project: MinterProject, path: String) {
        val content = json.encodeToString(
            MinterProject.serializer(),
            project.copy(lastModifiedAt = Clock.System.now().toEpochMilliseconds())
        )
        PlatformFileSystem.writeText(path, content)
    }

    suspend fun load(path: String): MinterProject {
        val content = PlatformFileSystem.readText(path)
        return json.decodeFromString(MinterProject.serializer(), content)
    }

    fun exists(path: String): Boolean {
        return PlatformFileSystem.exists(path)
    }
}
