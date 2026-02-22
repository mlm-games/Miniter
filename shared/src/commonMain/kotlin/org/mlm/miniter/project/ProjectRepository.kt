package org.mlm.miniter.project

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Clock

class ProjectRepository(private val json: Json) {

    suspend fun save(project: MinterProject, path: String) = withContext(Dispatchers.Default) {
        val content = json.encodeToString(MinterProject.serializer(), project.copy(
            lastModifiedAt = Clock.System.now().toEpochMilliseconds()
        ))
        writeTextToFile(path, content)
    }

    suspend fun load(path: String): MinterProject = withContext(Dispatchers.Default) {
        val content = readTextFromFile(path)
        json.decodeFromString(MinterProject.serializer(), content)
    }
}

suspend fun writeTextToFile(path: String, content: String) = withContext(Dispatchers.IO) {
    File(path).writeText(content, Charsets.UTF_8)
}

suspend fun readTextFromFile(path: String): String = withContext(Dispatchers.IO) {
    File(path).readText(Charsets.UTF_8)
}