package org.mlm.miniter.editor

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.mlm.miniter.editor.model.RustAudioFilterSnapshot
import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustExportProfileSnapshot
import org.mlm.miniter.editor.model.RustProjectSnapshot
import org.mlm.miniter.editor.model.RustTextStyleSnapshot
import org.mlm.miniter.editor.model.RustTransitionSnapshot
import org.mlm.miniter.editor.model.RustTrackKind
import org.mlm.miniter.editor.model.RustVideoEffectSnapshot

class RustCommandJson(
    private val json: Json,
) {
    fun importProject(project: RustProjectSnapshot): String {
        return json.encodeToString(project)
    }

    fun addTrack(kind: RustTrackKind, name: String): String =
        wrap("AddTrack") {
            put("kind", JsonPrimitive(kind.name))
            put("name", JsonPrimitive(name))
        }

    fun removeTrack(trackId: String): String =
        wrap("RemoveTrack") {
            put("track_id", uuid(trackId))
        }

    fun renameTrack(trackId: String, newName: String): String =
        wrap("RenameTrack") {
            put("track_id", uuid(trackId))
            put("new_name", JsonPrimitive(newName))
        }

    fun setTrackMuted(trackId: String, muted: Boolean): String =
        wrap("SetTrackMuted") {
            put("track_id", uuid(trackId))
            put("muted", JsonPrimitive(muted))
        }

    fun setTrackLocked(trackId: String, locked: Boolean): String =
        wrap("SetTrackLocked") {
            put("track_id", uuid(trackId))
            put("locked", JsonPrimitive(locked))
        }

    fun addClip(trackId: String, clip: RustClipSnapshot): String =
        wrap("AddClip") {
            put("track_id", uuid(trackId))
            put("clip", json.encodeToJsonElement(RustClipSnapshot.serializer(), clip))
        }

    fun removeClip(clipId: String): String =
        wrap("RemoveClip") {
            put("clip_id", uuid(clipId))
        }

    fun moveClip(clipId: String, trackId: String, newStartUs: Long): String =
        wrap("MoveClip") {
            put("clip_id", uuid(clipId))
            put("new_track_id", uuid(trackId))
            put("new_start", JsonPrimitive(newStartUs))
        }

    fun duplicateClip(
        sourceClipId: String,
        newClipId: String,
        targetTrackId: String,
        targetStartUs: Long,
    ): String = wrap("DuplicateClip") {
        put("source_clip_id", uuid(sourceClipId))
        put("new_clip_id", uuid(newClipId))
        put("target_track_id", uuid(targetTrackId))
        put("target_start", JsonPrimitive(targetStartUs))
    }

    fun trimClipStart(
        clipId: String,
        newStartUs: Long,
        newSourceStartUs: Long,
    ): String = wrap("TrimClipStart") {
        put("clip_id", uuid(clipId))
        put("new_start", JsonPrimitive(newStartUs))
        put("new_source_start", JsonPrimitive(newSourceStartUs))
    }

    fun trimClipEnd(
        clipId: String,
        newDurationUs: Long,
    ): String = wrap("TrimClipEnd") {
        put("clip_id", uuid(clipId))
        put("new_duration", JsonPrimitive(newDurationUs))
    }

    fun splitClip(
        clipId: String,
        atUs: Long,
        newClipId: String,
    ): String = wrap("SplitClip") {
        put("clip_id", uuid(clipId))
        put("at", JsonPrimitive(atUs))
        put("new_clip_id", uuid(newClipId))
    }

    fun setClipSpeed(clipId: String, speed: Double): String =
        wrap("SetClipSpeed") {
            put("clip_id", uuid(clipId))
            put("speed", JsonPrimitive(speed))
        }

    fun setClipVolume(clipId: String, volume: Float): String =
        wrap("SetClipVolume") {
            put("clip_id", uuid(clipId))
            put("volume", JsonPrimitive(volume))
        }

    fun setClipOpacity(clipId: String, opacity: Float): String =
        wrap("SetClipOpacity") {
            put("clip_id", uuid(clipId))
            put("opacity", JsonPrimitive(opacity))
        }

    fun setClipMuted(clipId: String, muted: Boolean): String =
        wrap("SetClipMuted") {
            put("clip_id", uuid(clipId))
            put("muted", JsonPrimitive(muted))
        }

    fun addVideoFilter(clipId: String, filter: RustVideoEffectSnapshot): String =
        wrap("AddVideoFilter") {
            put("clip_id", uuid(clipId))
            put("filter", json.encodeToJsonElement(RustVideoEffectSnapshot.serializer(), filter))
        }

    fun updateVideoFilter(
        clipId: String,
        index: Int,
        filter: RustVideoEffectSnapshot,
    ): String = wrap("UpdateVideoFilter") {
        put("clip_id", uuid(clipId))
        put("index", JsonPrimitive(index))
        put("filter", json.encodeToJsonElement(RustVideoEffectSnapshot.serializer(), filter))
    }

    fun setVideoFilterEnabled(clipId: String, index: Int, enabled: Boolean): String =
        wrap("SetVideoFilterEnabled") {
            put("clip_id", uuid(clipId))
            put("index", JsonPrimitive(index))
            put("enabled", JsonPrimitive(enabled))
        }

    fun moveVideoFilter(clipId: String, from: Int, to: Int): String =
        wrap("MoveVideoFilter") {
            put("clip_id", uuid(clipId))
            put("from", JsonPrimitive(from))
            put("to", JsonPrimitive(to))
        }

    fun removeVideoFilter(clipId: String, index: Int): String =
        wrap("RemoveVideoFilter") {
            put("clip_id", uuid(clipId))
            put("index", JsonPrimitive(index))
        }

    fun addAudioFilter(clipId: String, filter: RustAudioFilterSnapshot): String =
        wrap("AddAudioFilter") {
            put("clip_id", uuid(clipId))
            put("filter", json.encodeToJsonElement(RustAudioFilterSnapshot.serializer(), filter))
        }

    fun removeAudioFilter(clipId: String, index: Int): String =
        wrap("RemoveAudioFilter") {
            put("clip_id", uuid(clipId))
            put("index", JsonPrimitive(index))
        }

    fun setTransitionIn(clipId: String, transition: RustTransitionSnapshot?): String =
        wrap("SetTransitionIn") {
            put("clip_id", uuid(clipId))
            if (transition != null) {
                put("transition", json.encodeToJsonElement(RustTransitionSnapshot.serializer(), transition))
            }
        }

    fun setTransitionOut(clipId: String, transition: RustTransitionSnapshot?): String =
        wrap("SetTransitionOut") {
            put("clip_id", uuid(clipId))
            if (transition != null) {
                put("transition", json.encodeToJsonElement(RustTransitionSnapshot.serializer(), transition))
            }
        }

    fun updateTextContent(clipId: String, text: String): String =
        wrap("UpdateTextContent") {
            put("clip_id", uuid(clipId))
            put("text", JsonPrimitive(text))
        }

    fun updateTextStyle(clipId: String, style: RustTextStyleSnapshot): String =
        wrap("UpdateTextStyle") {
            put("clip_id", uuid(clipId))
            put("style", json.encodeToJsonElement(RustTextStyleSnapshot.serializer(), style))
        }

    fun setExportProfile(profile: RustExportProfileSnapshot): String =
        wrap("SetExportProfile") {
            put("profile", json.encodeToJsonElement(RustExportProfileSnapshot.serializer(), profile))
        }

    fun batch(label: String, commands: List<String>): String =
        wrap("Batch") {
            put("label", JsonPrimitive(label))
            put(
                "commands",
                JsonArray(commands.map { json.parseToJsonElement(it) })
            )
        }

    private fun wrap(
        variant: String,
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String {
        val payload = buildJsonObject(block)
        val root = buildJsonObject {
            put(variant, payload)
        }
        return root.toString()
    }

    private fun uuid(value: String): JsonElement = JsonPrimitive(value)
}
