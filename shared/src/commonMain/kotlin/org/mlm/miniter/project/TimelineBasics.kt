package org.mlm.miniter.project

import org.mlm.miniter.editor.model.RustClipSnapshot
import org.mlm.miniter.editor.model.RustTrackSnapshot

object TimelineBasics {

    fun followersAfter(track: RustTrackSnapshot, clip: RustClipSnapshot): List<RustClipSnapshot> {
        val endUs = clip.timelineStartUs + clip.timelineDurationUs
        return track.clips
            .filter { it.id != clip.id && it.timelineStartUs >= endUs }
            .sortedBy { it.timelineStartUs }
    }

    fun rippleMovesForRemoval(
        track: RustTrackSnapshot,
        clip: RustClipSnapshot,
    ): Map<String, Long> {
        val gapUs = clip.timelineDurationUs.coerceAtLeast(0L)
        if (gapUs <= 0L) return emptyMap()
        return followersAfter(track, clip).associate { follower ->
            follower.id to (follower.timelineStartUs - gapUs).coerceAtLeast(0L)
        }
    }

    fun compactMoves(track: RustTrackSnapshot): Map<String, Long> {
        val sorted = track.clips.sortedBy { it.timelineStartUs }
        var cursor = 0L
        val moves = mutableMapOf<String, Long>()
        for (clip in sorted) {
            if (clip.timelineStartUs != cursor) moves[clip.id] = cursor
            cursor += clip.timelineDurationUs.coerceAtLeast(0L)
        }
        return moves
    }

    data class SplitTarget(val clipId: String, val trackId: String, val atUs: Long)

    fun splitTargetsAt(
        tracks: List<RustTrackSnapshot>,
        playheadUs: Long,
    ): List<SplitTarget> {
        if (playheadUs <= 0L) return emptyList()
        return tracks.flatMap { track ->
            track.clips.mapNotNull { clip ->
                val start = clip.timelineStartUs
                val end = start + clip.timelineDurationUs
                if (playheadUs > start && playheadUs < end) {
                    SplitTarget(clip.id, track.id, playheadUs)
                } else null
            }
        }
    }

    fun findTrackForClip(
        tracks: List<RustTrackSnapshot>,
        clipId: String,
    ): RustTrackSnapshot? = tracks.firstOrNull { t -> t.clips.any { it.id == clipId } }

    fun findClip(
        tracks: List<RustTrackSnapshot>,
        clipId: String,
    ): RustClipSnapshot? = tracks.flatMap { it.clips }.firstOrNull { it.id == clipId }
}
