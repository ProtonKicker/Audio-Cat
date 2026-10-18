package com.audiocat.files

/**
 * Batch-rename rule builder.
 *
 * Kept free of Android and filesystem dependencies so the naming logic can be
 * unit tested exhaustively; [FileOperations] performs the actual disk work using
 * these results.
 */
data class RenamePattern(
    /** Text inserted before the original name. */
    val prefix: String = "",
    /** Text appended after the original name (before the extension). */
    val suffix: String = "",
    /** Literal text to find in the original name. */
    val find: String = "",
    /** Replacement for [find]; empty deletes the match. */
    val replaceWith: String = "",
    /** When true, [find] is matched case-sensitively. */
    val matchCase: Boolean = false,
    /** When true, replace every occurrence rather than the first. */
    val replaceAll: Boolean = true,
    /** Sequential numbering appended as `_001`, `_002`, ... when enabled. */
    val numbering: Boolean = false,
    val numberStart: Int = 1,
    val numberPadding: Int = 3,
    /** Uses the track's "artist - title" tags instead of the filename. */
    val useTags: Boolean = false,
) {
    val isNoOp: Boolean
        get() = prefix.isEmpty() && suffix.isEmpty() && !numbering && !useTags &&
            (find.isEmpty() || find == replaceWith)

    companion object {
        val NONE = RenamePattern()
    }
}

/** Minimal view of a track needed to compute a new file name. */
data class RenameTarget(
    val path: String,
    val title: String,
    val artist: String?,
)

/** A computed rename, ready to be applied. */
data class RenamePlanItem(
    val from: String,
    val to: String,
    /** False when [to] equals [from], meaning the file needs no work. */
    val changed: Boolean,
) {
    val fileName: String get() = to.substringAfterLast('/')
}

/**
 * Pure rename planner. Never touches the filesystem.
 */
object RenamePlanner {

    /** Characters that are illegal in file names on common Android volumes. */
    private val ILLEGAL = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')

    /** Upper bound on "(2)", "(3)", ... suffixes before giving up on a stem. */
    private const val MAX_COLLISION_ATTEMPTS = 1_000

    fun sanitize(name: String): String {
        var cleaned = name
        ILLEGAL.forEach { cleaned = cleaned.replace(it, '_') }
        // Trailing dots and spaces are dropped by many filesystems; strip them
        // so the path we compute matches what lands on disk.
        return cleaned.trim().trimEnd('.', ' ').ifEmpty { "untitled" }
    }

    /**
     * Computes new file names for [targets].
     *
     * Collisions are resolved deterministically by appending ` (2)`, ` (3)`, ...
     * before the extension. Only names that are *not* being vacated block a
     * rename, so swapping two names (a.mp3 -> b.mp3 while b.mp3 -> a.mp3) works
     * and no file is ever written over another.
     *
     * @param occupiedExisting absolute paths that already exist on disk and are
     *   not part of [targets]. Pass an empty set when the caller will run
     *   [validate] instead (which is what the UI does).
     */
    fun plan(
        targets: List<RenameTarget>,
        pattern: RenamePattern,
        occupiedExisting: Set<String> = emptySet(),
    ): List<RenamePlanItem> {
        if (pattern.isNoOp || targets.isEmpty()) {
            return targets.map { RenamePlanItem(it.path, it.path, changed = false) }
        }

        // Names that will still be occupied once every rename has been applied.
        // Anything in the batch is being vacated, so it does not block a move.
        val vacated = targets.mapTo(HashSet(targets.size)) { it.path }
        val occupied = occupiedExisting.filterNotTo(HashSet(occupiedExisting.size)) { it in vacated }
        val claimed = HashSet<String>(targets.size * 2)

        fun destinationFor(stem: String, ext: String, directory: String, counter: Int): String {
            val name = when {
                counter == 1 && ext.isBlank() -> stem
                counter == 1 -> "$stem.$ext"
                ext.isBlank() -> "$stem ($counter)"
                else -> "$stem ($counter).$ext"
            }
            return if (directory.isEmpty()) name else "$directory/$name"
        }

        return targets.mapIndexed { index, target ->
            val directory = target.path.substringBeforeLast('/', "")
            val originalName = target.path.substringAfterLast('/')
            val extension = originalName.substringAfterLast('.', "")
            val base = if (pattern.useTags) {
                buildString {
                    target.artist?.takeIf { it.isNotBlank() }?.let { append(it).append(" - ") }
                    append(target.title.ifBlank { originalName.substringBeforeLast('.') })
                }
            } else {
                originalName.substringBeforeLast('.')
            }

            var stem = applyReplacements(base, pattern)
            if (pattern.numbering) {
                val number = (pattern.numberStart + index).toString()
                    .padStart(pattern.numberPadding.coerceIn(1, 10), '0')
                stem = "${stem}_$number"
            }
            stem = sanitize(pattern.prefix + stem + pattern.suffix)

            var counter = 1
            var fullPath = destinationFor(stem, extension, directory, counter)
            // Only a name claimed earlier in this batch, or owned by a file that
            // is staying put, forces a suffix.
            while (counter < MAX_COLLISION_ATTEMPTS && (fullPath in claimed || fullPath in occupied)) {
                counter++
                fullPath = destinationFor(stem, extension, directory, counter)
            }

            claimed += fullPath
            RenamePlanItem(
                from = target.path,
                to = fullPath,
                changed = fullPath != target.path,
            )
        }
    }

    private fun applyReplacements(base: String, pattern: RenamePattern): String {
        if (pattern.find.isEmpty()) return base
        val source = if (pattern.matchCase) base else base.lowercase()
        val needle = if (pattern.matchCase) pattern.find else pattern.find.lowercase()
        if (!source.contains(needle)) return base

        val builder = StringBuilder()
        var cursor = 0
        while (true) {
            val hit = source.indexOf(needle, cursor)
            if (hit < 0) {
                builder.append(base, cursor, base.length)
                break
            }
            builder.append(base, cursor, hit)
            builder.append(pattern.replaceWith)
            cursor = hit + needle.length
            if (!pattern.replaceAll) {
                builder.append(base, cursor, base.length)
                break
            }
        }
        return builder.toString()
    }

    /**
     * Validates a plan against the current directory contents before anything is
     * written to disk. Returns a human-readable error, or null when safe.
     */
    fun validate(plan: List<RenamePlanItem>, existingPaths: Set<String>): String? {
        val movingFrom = plan.filter { it.changed }.mapTo(HashSet()) { it.from }
        val seen = HashSet<String>()
        for (item in plan) {
            if (!item.changed) continue
            val fileName = item.fileName
            if (fileName.isBlank()) return "A new file name is empty."
            // Renaming onto a file that exists and is not itself being moved
            // would silently destroy data.
            if (item.to in existingPaths && item.to !in movingFrom) {
                return "\"$fileName\" already exists."
            }
            if (!seen.add(item.to)) return "\"$fileName\" is used more than once."
        }
        return null
    }
}
