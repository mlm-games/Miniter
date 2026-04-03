package org.mlm.miniter.project

import kotlinx.serialization.Serializable
import kotlin.time.Clock

@Serializable
data class RecentProject(
    val path: String,
    val name: String,
    val lastOpenedAt: Long = Clock.System.now().toEpochMilliseconds(),
    val thumbnailPath: String? = null,
)
