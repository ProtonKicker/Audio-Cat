package com.audiocat.scanner

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/** Audio-stream details read from the container's first audio track. */
data class AudioTrackInfo(
    val mime: String? = null,
    val sampleRate: Int = 0,
    val channelCount: Int = 0,
    val bitRate: Int = 0,
)

/** Tags extracted from a media file's metadata. */
data class TrackTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0L,
    val lyrics: String? = null,
)

/**
 * Reads tags and audio-stream details from a media file.
 *
 * Only the *audio* track is ever inspected: [MediaExtractor] is asked for the
 * first track whose MIME type starts with `audio/`, and the extractor is always
 * released. Video codecs are therefore never instantiated, which is what keeps
 * video containers as cheap to scan as plain audio files.
 */
class TrackMetadataReader(private val context: Context) {

    /** Reads tags; returns null when the file cannot be parsed. */
    fun readTags(uri: String): TrackTags? = withRetriever(uri) { retriever ->
        TrackTags(
            title = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
            album = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
            durationMs = retriever.metadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L,
            lyrics = retriever.lyrics(),
        )
    }

    /** Reads the audio codec configuration, ignoring every video track. */
    fun readAudioInfo(uri: String): AudioTrackInfo {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.optString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                return AudioTrackInfo(
                    mime = mime,
                    sampleRate = format.optInt(MediaFormat.KEY_SAMPLE_RATE),
                    channelCount = format.optInt(MediaFormat.KEY_CHANNEL_COUNT),
                    bitRate = format.optInt(MediaFormat.KEY_BIT_RATE),
                )
            }
            AudioTrackInfo()
        } catch (e: Exception) {
            Log.w(TAG, "readAudioInfo failed for $uri", e)
            AudioTrackInfo()
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Reads an embedded cover image, if the container carries one. */
    fun embeddedPicture(uri: String): ByteArray? = withRetriever(uri) { it.embeddedPicture }

    private inline fun <T> withRetriever(uri: String, block: (MediaMetadataRetriever) -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            block(retriever)
        } catch (e: Exception) {
            Log.w(TAG, "metadata read failed for $uri", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Lyrics live behind `METADATA_KEY_LYRICS`. The SDK stubs only expose that
     * constant from API 30 onwards, so the documented literal is used instead -
     * `extractMetadata` simply returns null on containers that lack the field.
     */
    private fun MediaMetadataRetriever.lyrics(): String? = metadata(METADATA_KEY_LYRICS)

    private fun MediaMetadataRetriever.metadata(key: Int): String? =
        runCatching { extractMetadata(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "TrackMetadataReader"

        /** Numeric value of `MediaMetadataRetriever.METADATA_KEY_LYRICS`. */
        const val METADATA_KEY_LYRICS = 35
    }
}

internal fun MediaFormat.optInt(key: String): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

internal fun MediaFormat.optString(key: String): String? =
    if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null
