package com.audiocat.files

import android.app.Activity
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
 * Raised when Android requires the user to consent before deleting one or more
 * media files the app did not create. The caller launches [intentSender]; once
 * the platform result arrives it calls [FileOperations.deleteFiles] again with the
 * same list.
 */
class DeleteConsentRequired(val intentSender: IntentSender) : Exception("delete consent required")

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
                    db.withTransaction { rekeyTrack(original, result.path) }
                    renamed++
                }
                is DiskRename.Failed -> {
                    renameOnDisk(tempPath, original)
                    failures += RenameFailure(original, result.reason)
                }
            }
        }
        return RenameOutcome(renamed = renamed, failures = failures)
    }

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

        if (source.canWrite() && !target.exists() && source.renameTo(target)) {
            return DiskRename.Ok(target.absolutePath)
        }

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

    @RequiresApi(Build.VERSION_CODES.R)
    private fun needsConsent(movers: List<RenamePlanItem>): Boolean =
        movers.any { !File(it.from).canWrite() }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createWriteRequest(uris: List<Uri>): IntentSender =
        MediaStore.createWriteRequest(resolver, uris).intentSender

    suspend fun renameTargetsFor(paths: List<String>): List<RenameTarget> = withContext(Dispatchers.IO) {
        db.trackDao().byPaths(paths).map {
            RenameTarget(path = it.path, title = it.title, artist = it.artist)
        }
    }

    suspend fun existingPathsFor(paths: List<String>): Set<String> = withContext(Dispatchers.IO) {
        val directories = paths.map { it.substringBeforeLast('/', "") }.toSet()
        buildSet {
            directories.forEach { dir ->
                File(dir).listFiles()?.forEach { add(it.absolutePath) }
            }
        }
    }

    suspend fun forgetArtwork(paths: List<String>) = withContext(Dispatchers.IO) {
        db.trackDao().byPaths(paths).forEach { track ->
            track.artPath?.let { File(context.cacheDir, "artwork/$it").delete() }
        }
    }

    /**
     * One file queued for deletion. Carries both its on-disk [path] and the
     * MediaStore [uri] the scanner recorded for it.
     */
    data class Deletable(val path: String, val uri: String)

    /**
     * Deletes files from disk. Only ever called after an explicit confirmation.
     *
     * Resolution order per file:
     * 1. Physical [File.delete] when the app can write the path directly — this is
     *    the case with [android.os.Environment.isExternalStorageManager] on
     *    Android 11+, or with the manifest opt-in
     *    `android:requestLegacyExternalStorage="true"` on Android 10.
     * 2. MediaStore [ContentResolver.delete] using the row's own content URI.
     *    On Android 11+ the platform throws a RecoverableSecurityException when
     *    the file was not created by this app; we translate that into a
     *    [DeleteConsentRequired] so the UI can launch the system consent prompt.
     *
     * @throws DeleteConsentRequired when user consent is needed first.
     */
    suspend fun deleteFiles(targets: List<Deletable>): RenameOutcome = withContext(Dispatchers.IO) {
        // Phase 1 — pass each file through deleteOne. Accumulate any RSE
        // IntentSender we are handed; we throw ONE [DeleteConsentRequired] so
        // the UI can launch the system consent prompt for the whole set.
        var consentSender: IntentSender? = null
        var deleted = 0
        val failures = mutableListOf<RenameFailure>()
        for (target in targets) {
            try {
                if (deleteOne(target)) {
                    deleted++
                } else {
                    failures += RenameFailure(target.path, "Could not delete file")
                }
            } catch (e: Exception) {
                val sender = extractRseIntentSender(e)
                if (sender != null) {
                    consentSender = sender
                } else {
                    Log.w(TAG, "deleteFiles: non-recoverable failure for ${target.path}", e)
                    failures += RenameFailure(target.path, e.message ?: "Could not delete")
                }
            }
        }
        if (consentSender != null) {
            throw DeleteConsentRequired(consentSender)
        }
        if (deleted > 0) db.trackDao().deleteByPaths(targets.map { it.path })
        RenameOutcome(renamed = deleted, failures = failures)
    }

    /** Tries direct delete first, falls back to a MediaStore delete. */
    private fun deleteOne(target: Deletable): Boolean {
        val file = File(target.path)
        if (!file.exists()) return true
        if (file.canWrite()) {
            val ok = file.delete()
            if (ok) return true
            // Direct delete failed; some devices report canWrite=true but the
            // file provider still refuses — fall through to MediaStore.
        }
        val uri = Uri.parse(target.uri)
        val count = resolver.delete(uri, null, null)
        return count > 0
    }

    /**
     * On Android 11+ the platform may throw RecoverableSecurityException with
     * an embedded IntentSender for the consent prompt. Reflect into it because
     * the stable API for batching consent was only added in the same release.
     */
    private fun extractRseIntentSender(e: Throwable): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        var current: Throwable? = e
        while (current != null) {
            if (current.javaClass.simpleName == "RecoverableSecurityException") {
                val m = runCatching {
                    current.javaClass.getMethod("getIntentSender")
                }.getOrNull() ?: runCatching {
                    current.javaClass.getDeclaredMethod("getIntentSender").apply { isAccessible = true }
                }.getOrNull() ?: return null
                return runCatching { m.invoke(current) as? IntentSender }.getOrNull()
            }
            current = current.cause
        }
        return null
    }

    private companion object {
        const val TAG = "FileOperations"
    }
}
