package org.mlm.miniter.project

import io.github.aakira.napier.Napier
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.mlm.miniter.platform.PlatformFileSystem
import org.mlm.miniter.platform.randomUuid
import kotlin.time.Clock

data class RecoveryInfo(
    val projectName: String?,
    val projectPath: String?,
    val updatedAtMs: Long,
    val recoveryPath: String,
)

@Serializable
private data class RecoveryMarker(
    val sessionId: String,
    val projectPath: String? = null,
    val projectName: String? = null,
    val updatedAtMs: Long = 0L,
)

object ProjectRecovery {
    private const val APP_DATA_DIRECTORY = "miniter"
    private const val RECOVERY_DIRECTORY = "recovery"
    private const val RECOVERY_FILE_NAME = "recovery.mntr"
    private const val MARKER_FILE_NAME = "session.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val sessionId = randomUuid()

    fun recoveryFilePath(): String =
        PlatformFileSystem.combinePath(recoveryDirectory(), RECOVERY_FILE_NAME)

    fun markerFilePath(): String =
        PlatformFileSystem.combinePath(recoveryDirectory(), MARKER_FILE_NAME)

    private fun recoveryDirectory(): String = PlatformFileSystem.combinePath(
        PlatformFileSystem.getAppDataDirectory(APP_DATA_DIRECTORY),
        RECOVERY_DIRECTORY,
    )

    suspend fun beginSession() {
        try {
            PlatformFileSystem.createDirectories(recoveryDirectory())
            if (PlatformFileSystem.exists(markerFilePath())) return
            writeMarker(projectPath = null, projectName = null)
        } catch (e: Exception) {
            Napier.w("Could not start recovery session: ${e.message}")
        }
    }

    suspend fun pending(): RecoveryInfo? {
        return try {
            val markerPath = markerFilePath()
            val recoveryPath = recoveryFilePath()
            if (!PlatformFileSystem.exists(markerPath)) return null
            if (!PlatformFileSystem.exists(recoveryPath)) return null
            val marker = readMarker(markerPath)
            RecoveryInfo(
                projectName = marker?.projectName,
                projectPath = marker?.projectPath,
                updatedAtMs = marker?.updatedAtMs ?: 0L,
                recoveryPath = recoveryPath,
            )
        } catch (e: Exception) {
            Napier.w("Could not inspect recovery state: ${e.message}")
            null
        }
    }

    suspend fun readRecoveryJson(): String? = try {
        val path = recoveryFilePath()
        if (PlatformFileSystem.exists(path)) PlatformFileSystem.readText(path) else null
    } catch (e: Exception) {
        Napier.w("Could not read recovery copy: ${e.message}")
        null
    }

    suspend fun writeRecovery(projectJson: String, projectPath: String?, projectName: String?) {
        try {
            PlatformFileSystem.createDirectories(recoveryDirectory())
            PlatformFileSystem.writeTextDurable(recoveryFilePath(), projectJson)
            writeMarker(projectPath = projectPath, projectName = projectName)
        } catch (e: Exception) {
            Napier.w("Could not write recovery copy: ${e.message}")
        }
    }

    suspend fun claim(projectPath: String?, projectName: String?) {
        try {
            PlatformFileSystem.createDirectories(recoveryDirectory())
            writeMarker(projectPath = projectPath, projectName = projectName)
        } catch (e: Exception) {
            Napier.w("Could not claim recovery session: ${e.message}")
        }
    }

    suspend fun clearAfterSave() {
        try {
            PlatformFileSystem.delete(recoveryFilePath())
        } catch (e: Exception) {
            Napier.w("Could not clear recovery copy: ${e.message}")
        }
    }

    suspend fun discard() {
        try {
            PlatformFileSystem.delete(recoveryFilePath())
            PlatformFileSystem.delete(markerFilePath())
        } catch (e: Exception) {
            Napier.w("Could not discard recovery state: ${e.message}")
        }
    }

    suspend fun endSession() {
        try {
            val markerPath = markerFilePath()
            if (!PlatformFileSystem.exists(markerPath)) return
            val marker = readMarker(markerPath) ?: return
            if (marker.sessionId != sessionId) return
            PlatformFileSystem.delete(recoveryFilePath())
            PlatformFileSystem.delete(markerPath)
        } catch (e: Exception) {
            Napier.w("Could not end recovery session: ${e.message}")
        }
    }

    private suspend fun writeMarker(projectPath: String?, projectName: String?) {
        val marker = RecoveryMarker(
            sessionId = sessionId,
            projectPath = projectPath,
            projectName = projectName,
            updatedAtMs = Clock.System.now().toEpochMilliseconds(),
        )
        PlatformFileSystem.writeTextDurable(
            markerFilePath(),
            json.encodeToString(RecoveryMarker.serializer(), marker),
        )
    }

    private suspend fun readMarker(path: String): RecoveryMarker? = try {
        json.decodeFromString(RecoveryMarker.serializer(), PlatformFileSystem.readText(path))
    } catch (e: Exception) {
        Napier.w("Could not read recovery session marker: ${e.message}")
        null
    }
}
