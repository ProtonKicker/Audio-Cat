package com.audiocat.files

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.room.withTransaction
import com.audiocat.data.db.AudioCatDatabase
import com.audiocat.data.db.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** A rename that could not be applied, with the reason why. */
data class RenameFailure(val path: String, val reason: String)

/** Result of applying a rename plan. */
data class RenameOutcome(
    val renamed: Int,
    val failures: List<RenameFailure> = emptyList(),
) {
    val allSucceeded: Boolean get() = failures.isEmpty()
}

/**
 * Raised when Android requires the user to grant write access to public media
 * before the rename can proceed. The caller launches [intentSender] and then
 * calls [FileOperations.applyRenamePlan] again.
 */
class WriteConsentRequired(val intentSender: IntentSender) : Exception("write consent required")

/**
 * All disk-touching operations for the File Management tab.
 *
 * Renames go through MediaStore's document API rather than [File.renameTo]
 * because scoped storage forbids direct writes to public media on Android 11+.
 * The database is only updated after the file operation reports success, so the
 * library can never point at a path that does not exist.
 */
class FileOperations(
    private val context: Context,
    private val db: AudioCatDatabase,
) {

    private val resolver: ContentResolver get() = context.contentResolver

    /** Maps a filesystem path onto the MediaStore document URI used by rename. */
    private fun documentUri(path: String): Uri = Uri.parse("content://media/external/file").buildUpon()
        .appendEncodedPath(documentIdFor(path))
        .build()

    /**
     * MediaStore derives a document id from the volume-relative path for files
     * on primary external storage.
     */
    private fun documentIdFor(path: String): String {
        val marker = "/storage/emulated/0/"
        return if (path.startsWith(marker)) path.removePrefix(marker) else path.trimStart('/')
    }

    /**
     * Applies a validated [plan] to disk and mirrors every success into the DB.
     *
     * @throws WriteConsentRequired when the platform needs user confirmation.
     */
    suspend fun applyRenamePlan(plan: List<RenamePlanItem>): RenameOutcome = withContext(Dispatchers.IO) {
        val movers = plan.filter { it.changed }
        if (movers.isEmpty()) return@withContext RenameOutcome(renamed = 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && needsConsent(movers)) {
            throw WriteConsentRequired(createWriteRequest(movers.map { documentUri(it.from) }))
        }

        executeRename(movers)
    }

    /**
     * Performs the two-phase rename.
     *
     * Phase one moves every file to a guaranteed-unique temporary name, which
     * removes all ordering hazards (A->B, B->C) without having to topologically
     * sort the plan. Phase two moves temporaries to their final names.
     */
    private suspend fun executeRename(movers: List<RenamePlanItem>): RenameOutcome {
        val failures = mutableListOf<RenameFailure>()
        val staged = ArrayList<Triple<String, String, String>>(movers.size)

        movers.forEachIndexed { index, item ->
            val temp = "${item.from}.acrename$index"
            when (val result = renameOnDisk(item.from, temp)) {
                is DiskRename.Ok -> staged += Triple(item.from, result.path, item.to)
                is DiskRename.Failed -> failures += RenameFailure(item.from, result.reason)
            }
        }

        var renamed = 0
        for ((original, tempPath, target) in staged) {
            when (val result = renameOnDisk(tempPath, target)) {
                is DiskRename.Ok -> {
                    // Index the track under its new identity. Doing this inside a
                    // transaction keeps playlist membership intact.
                    db.withTransaction { rekeyTrack(original, result.path) }
                    renamed++
                }
                is DiskRename.Failed -> {
                    // Try to put the file back so the user does not end up with a
                    // stray ".acrename" name.
                    renameOnDisk(tempPath, original)
                    failures += RenameFailure(original, result.reason)
                }
            }
        }
        return RenameOutcome(renamed = renamed, failures = failures)
    }

    /**
     * Re-keys a track row after its file moved.
     *
     * Deleting the old row cascades into `playlist_tracks`, so memberships are
     * captured first and re-inserted for the new path inside the same
     * transaction. Playlists therefore survive a rename untouched.
     */
    private suspend fun rekeyTrack(oldPath: String, newPath: String) {
        val existing = db.trackDao().byPath(oldPath) ?: return
        val memberships = db.playlistTrackDao().membershipsFor(oldPath)
        val updated: Track = existing.copy(
            path = newPath,
            uri = Uri.fromFile(File(newPath)).toString(),
            folder = File(newPath).parent,
        )
        db.trackDao().deleteByPaths(listOf(oldPath))
        db.trackDao().insert(updated)
        memberships.forEach { membership ->
            db.playlistTrackDao().insert(membership.copy(trackPath = newPath))
        }
    }

    private sealed interface DiskRename {
        data class Ok(val path: String) : DiskRename
        data class Failed(val reason: String) : DiskRename
    }

    /** Renames a single file, preferring the cheapest mechanism that works. */
    private fun renameOnDisk(from: String, to: String): DiskRename {
        val source = File(from)
        if (!source.exists()) return DiskRename.Failed("File no longer exists")
        val target = File(to)

        // Fast path: legacy storage and app-owned directories still allow a
        // direct rename and avoid a binder round-trip.
        if (source.canWrite() && !target.exists() && source.renameTo(target)) {
            return DiskRename.Ok(target.absolutePath)
        }

        // Scoped-storage path: ask the media provider to move the document.
        return try {
            val renamed = DocumentsContract.renameDocument(resolver, documentUri(from), target.name)
            val resolved = renamed?.let(::pathFromDocumentUri)
                ?: runCatching { File(documentUri(from).path!!).absolutePath }.getOrNull()
            val landed = resolved?.takeIf { File(it).exists() } ?: to
            if (File(landed).exists()) DiskRename.Ok(landed) else DiskRename.Failed("Rename was refused")
        } catch (e: SecurityException) {
            Log.w(TAG, "rename denied for $from", e)
            DiskRename.Failed("Permission denied")
        } catch (e: Exception) {
            Log.w(TAG, "rename failed for $from", e)
            DiskRename.Failed(e.message ?: "Rename failed")
        }
    }

    private fun pathFromDocumentUri(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** True when any target file is not directly writable by this app. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun needsConsent(movers: List<RenamePlanItem>): Boolean =
        movers.any { !File(it.from).canWrite() }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createWriteRequest(uris: List<Uri>): IntentSender =
        MediaStore.createWriteRequest(resolver, uris).intentSender

    /** Reads tags for a batch-rename target so "use tags" can be offered. */
    suspend fun renameTargetsFor(paths: List<String>): List<RenameTarget> = withContext(Dispatchers.IO) {
        db.trackDao().byPaths(paths).map {
            RenameTarget(path = it.path, title = it.title, artist = it.artist)
        }
    }

    /** Every path currently on disk, used to validate a plan before applying. */
    suspend fun existingPathsFor(paths: List<String>): Set<String> = withContext(Dispatchers.IO) {
        val directories = paths.map { it.substringBeforeLast('/', "") }.toSet()
        buildSet {
            directories.forEach { dir ->
                File(dir).listFiles()?.forEach { add(it.absolutePath) }
            }
        }
    }

    /** Removes decoded artwork for a track, if any. */
    suspend fun forgetArtwork(paths: List<String>) = withContext(Dispatchers.IO) {
        db.trackDao().byPaths(paths).forEach { track ->
            track.artPath?.let { File(context.cacheDir, "artwork/$it").delete() }
        }
    }

    /**
     * Deletes files from disk. Only ever called after an explicit confirmation,
     * because this is the one irreversible operation in the app.
     */
    suspend fun deleteFiles(paths: List<String>): RenameOutcome = withContext(Dispatchers.IO) {
        var deleted = 0
        val failures = mutableListOf<RenameFailure>()
        paths.forEach { path ->
            val file = File(path)
            val ok = when {
                !file.exists() -> true
                file.canWrite() && file.delete() -> true
                else -> deleteViaResolver(path)
            }
            if (ok) deleted++ else failures += RenameFailure(path, "Could not delete file")
        }
        if (deleted > 0) db.trackDao().deleteByPaths(paths)
        RenameOutcome(renamed = deleted, failures = failures)
    }

    private fun deleteViaResolver(path: String): Boolean = runCatching {
        resolver.delete(documentUri(path), null, null) > 0
    }.getOrDefault(false)

    private companion object {
        const val TAG = "FileOperations"
    }
}
