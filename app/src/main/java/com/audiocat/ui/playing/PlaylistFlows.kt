package com.audiocat.ui.playing

import com.audiocat.data.db.AudioCatDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Observes playlists together with their track counts.
 *
 * Both values come from Room, so a playlist can never show a stale count after
 * tracks are added or removed. Counting uses a single grouped query rather than
 * one query per playlist.
 */
internal fun AudioCatDatabase.observePlaylistRows(): Flow<List<PlaylistRow>> =
    combine(
        playlistDao().observeAll(),
        playlistDao().observeTrackCounts(),
    ) { playlists, counts ->
        val byId = counts.associate { it.playlistId to it.count }
        playlists.map { playlist -> PlaylistRow(playlist, byId[playlist.id] ?: 0) }
    }
