package musicunlock.library

import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

enum class DuplicateMatchKind {
    /** 内容哈希完全一致，可以安全自动去重。 */
    EXACT,

    /** 仅按歌名、歌手和时长推断，必须由用户确认后再处理。 */
    LIKELY,
}

data class DuplicateCleanupDecision(
    val keepPath: String,
    val removePaths: List<String>,
    val matchKind: DuplicateMatchKind,
    val reason: String,
    val reclaimBytes: Long,
)

data class LibraryCleanupPlan(
    val decisions: List<DuplicateCleanupDecision>,
) {
    val reclaimBytes: Long = decisions.sumOf { it.reclaimBytes }
    val exactTrackCount: Int = decisions.filter { it.matchKind == DuplicateMatchKind.EXACT }.sumOf { it.removePaths.size }
    val likelyTrackCount: Int = decisions.filter { it.matchKind == DuplicateMatchKind.LIKELY }.sumOf { it.removePaths.size }
    val requiresConfirmation: Boolean get() = likelyTrackCount > 0
}

data class LibraryCleanupJournalEntry(
    val originalPath: String,
    val trashPath: String,
    val sidecarOriginalPath: String? = null,
    val sidecarTrashPath: String? = null,
    val size: Long = 0L,
)

data class LibraryCleanupJournal(
    val id: String,
    val createdAt: Long = System.currentTimeMillis(),
    val undoneAt: Long? = null,
    val entries: List<LibraryCleanupJournalEntry> = emptyList(),
)

data class LibraryCleanupOutcome(
    val id: String,
    val movedTracks: Int,
    val movedFiles: Int,
    val reclaimedBytes: Long,
    val failed: List<String>,
    val journalFile: File?,
)

data class LibraryCleanupUndoOutcome(
    val restoreCount: Int,
    val failed: List<String>,
)

interface LibraryTrashService {
    fun move(file: File, cleanupId: String): File
    fun restore(trashPath: File, originalPath: File)
}

/** 应用管理的可撤销回收站；文件保存在用户目录，不依赖桌面环境的回收站实现。 */
class ManagedLibraryTrashService(
    private val root: File = defaultLibraryTrashDir(),
) : LibraryTrashService {
    override fun move(file: File, cleanupId: String): File {
        require(file.isFile) { "文件不存在：${file.absolutePath}" }
        val target = uniqueTarget(File(File(root, cleanupId), file.name))
        target.parentFile?.mkdirs()
        moveFile(file, target)
        return target
    }

    override fun restore(trashPath: File, originalPath: File) {
        require(trashPath.isFile) { "回收站文件不存在：${trashPath.absolutePath}" }
        require(!originalPath.exists()) { "原位置已有同名文件：${originalPath.absolutePath}" }
        originalPath.parentFile?.mkdirs()
        moveFile(trashPath, originalPath)
    }

    private fun uniqueTarget(requested: File): File {
        if (!requested.exists()) return requested
        var index = 2
        while (true) {
            val candidate = File(requested.parentFile, "${requested.nameWithoutExtension} ($index).${requested.extension}")
            if (!candidate.exists()) return candidate
            index++
        }
    }
}

/** 先制定重复文件处理计划，只有显式 apply 才会移动文件。 */
class LibraryCleanupService(
    private val index: LibraryIndex = LibraryIndex(),
    private val trash: LibraryTrashService = ManagedLibraryTrashService(),
    private val journalDir: File = defaultLibraryCleanupJournalDir(),
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun plan(groups: List<List<LibraryEntry>> = index.duplicateGroups()): LibraryCleanupPlan =
        DuplicateCleanupPlanner.plan(groups)

    fun apply(plan: LibraryCleanupPlan, allowLikelyDuplicates: Boolean = false): LibraryCleanupOutcome {
        val id = UUID.randomUUID().toString()
        val journalEntries = mutableListOf<LibraryCleanupJournalEntry>()
        val failed = mutableListOf<String>()
        var reclaimedBytes = 0L
        var movedFiles = 0

        plan.decisions.forEach decisionLoop@ { decision ->
            if (decision.matchKind == DuplicateMatchKind.LIKELY && !allowLikelyDuplicates) return@decisionLoop
            if (!File(decision.keepPath).isFile) {
                failed += "保留文件不存在：${decision.keepPath}"
                return@decisionLoop
            }
            decision.removePaths.forEach removeLoop@ { path ->
                val source = File(path)
                if (!source.isFile) {
                    failed += "待删除文件不存在：$path"
                    return@removeLoop
                }
                runCatching {
                    val trashed = trash.move(source, id)
                    val lrc = File(source.parentFile, "${source.nameWithoutExtension}.lrc").takeIf(File::isFile)
                    val lrcTrashed = lrc?.let { sidecar ->
                        runCatching { trash.move(sidecar, id) }
                            .onFailure { failed += "${sidecar.absolutePath}：${it.message ?: it}" }
                            .getOrNull()
                    }
                    journalEntries += LibraryCleanupJournalEntry(
                        originalPath = source.absolutePath,
                        trashPath = trashed.absolutePath,
                        sidecarOriginalPath = lrc?.takeIf { lrcTrashed != null }?.absolutePath,
                        sidecarTrashPath = lrcTrashed?.absolutePath,
                        size = source.length(),
                    )
                    reclaimedBytes += source.length()
                    movedFiles += if (lrcTrashed != null) 2 else 1
                    index.remove(source.absolutePath)
                    if (lrcTrashed != null) index.remove(lrc.absolutePath)
                }.onFailure { failed += "$path：${it.message ?: it}" }
            }
        }

        if (journalEntries.isEmpty()) {
            return LibraryCleanupOutcome(id, 0, 0, 0L, failed, null)
        }
        val journal = LibraryCleanupJournal(id = id, entries = journalEntries)
        val file = journalFile(id)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(gson.toJson(journal))
        }.onFailure {
            journalEntries.asReversed().forEach { entry ->
                runCatching { trash.restore(File(entry.trashPath), File(entry.originalPath)) }
                if (!entry.sidecarTrashPath.isNullOrBlank() && !entry.sidecarOriginalPath.isNullOrBlank()) {
                    runCatching { trash.restore(File(entry.sidecarTrashPath), File(entry.sidecarOriginalPath)) }
                }
            }
            return LibraryCleanupOutcome(
                id = id,
                movedTracks = journalEntries.size,
                movedFiles = movedFiles,
                reclaimedBytes = reclaimedBytes,
                failed = failed + "清理记录写入失败：${it.message ?: it}",
                journalFile = null,
            )
        }
        return LibraryCleanupOutcome(
            id = id,
            movedTracks = journalEntries.size,
            movedFiles = movedFiles,
            reclaimedBytes = reclaimedBytes,
            failed = failed,
            journalFile = file,
        )
    }

    fun undo(cleanupId: String): LibraryCleanupUndoOutcome {
        val file = journalFile(cleanupId)
        val journal = runCatching { gson.fromJson(file.readText(), LibraryCleanupJournal::class.java) }.getOrNull()
            ?: return LibraryCleanupUndoOutcome(0, listOf("找不到清理记录：$cleanupId"))
        if (journal.undoneAt != null) return LibraryCleanupUndoOutcome(0, listOf("这次清理已经撤销"))

        var restored = 0
        val failed = mutableListOf<String>()
        journal.entries.asReversed().forEach { entry ->
            fun restoreResource(trashPath: String?, originalPath: String?) {
                if (trashPath.isNullOrBlank() || originalPath.isNullOrBlank()) return
                val trashFile = File(trashPath)
                val originalFile = File(originalPath)
                if (!trashFile.isFile && originalFile.isFile) return
                runCatching { trash.restore(trashFile, originalFile) }
                    .onSuccess { restored++ }
                    .onFailure { failed += "$originalPath：${it.message ?: it}" }
            }
            restoreResource(entry.sidecarTrashPath, entry.sidecarOriginalPath)
            restoreResource(entry.trashPath, entry.originalPath)
        }
        if (failed.isEmpty()) {
            runCatching { file.writeText(gson.toJson(journal.copy(undoneAt = System.currentTimeMillis()))) }
        }
        return LibraryCleanupUndoOutcome(restored, failed)
    }

    fun undoLast(): LibraryCleanupUndoOutcome {
        val latest = journalDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedByDescending(File::lastModified)
            ?.firstOrNull { candidate ->
                runCatching { gson.fromJson(candidate.readText(), LibraryCleanupJournal::class.java)?.undoneAt == null }
                    .getOrDefault(false)
            }
            ?: return LibraryCleanupUndoOutcome(0, listOf("暂无可撤销的清理记录"))
        return undo(latest.nameWithoutExtension)
    }

    private fun journalFile(cleanupId: String): File = File(journalDir, "$cleanupId.json")
}

object DuplicateCleanupPlanner {
    fun plan(groups: List<List<LibraryEntry>>): LibraryCleanupPlan {
        val decisions = groups.mapNotNull { rawGroup ->
            val group = rawGroup.distinctBy { it.path }.filter { File(it.path).isFile }
            if (group.size < 2) return@mapNotNull null
            val exact = group.all { !it.contentHash.isNullOrBlank() } && group.map { it.contentHash }.distinct().size == 1
            val keep = group.maxWithOrNull(compareBy<LibraryEntry>({ qualityScore(it) }, { it.path.length * -1 }, { it.path }))
                ?: return@mapNotNull null
            val remove = group.filterNot { it.path == keep.path }
            DuplicateCleanupDecision(
                keepPath = keep.path,
                removePaths = remove.map { it.path },
                matchKind = if (exact) DuplicateMatchKind.EXACT else DuplicateMatchKind.LIKELY,
                reason = keepReason(keep, exact),
                reclaimBytes = remove.sumOf { it.size.coerceAtLeast(0L) },
            )
        }
        return LibraryCleanupPlan(decisions)
    }

    private fun qualityScore(entry: LibraryEntry): Int {
        var score = formatScore(entry.format ?: File(entry.path).extension)
        score += (entry.bitRateKbps ?: 0).coerceIn(0, 2_000) / 4
        if (entry.hasCover) score += 30
        if (entry.hasLyrics) score += 20
        if (!entry.title.isNullOrBlank()) score += 12
        if (!entry.artist.isNullOrBlank()) score += 12
        if (!entry.album.isNullOrBlank()) score += 6
        if (!entry.contentHash.isNullOrBlank()) score += 8
        if (DUPLICATE_SUFFIX.containsMatchIn(File(entry.path).nameWithoutExtension)) score -= 80
        return score
    }

    private fun formatScore(value: String): Int = when (value.lowercase().removePrefix(".")) {
        "flac" -> 1_000
        "ape" -> 960
        "wav" -> 900
        "m4a", "alac" -> 780
        "ogg", "oga", "opus" -> 700
        "mp3" -> 620
        "wma" -> 540
        else -> 400
    }

    private fun keepReason(keep: LibraryEntry, exact: Boolean): String {
        val reasons = mutableListOf<String>()
        reasons += if (exact) "内容完全相同" else "歌名、歌手与时长一致，需确认"
        reasons += when ((keep.format ?: File(keep.path).extension).lowercase()) {
            "flac", "ape", "wav" -> "保留无损版本"
            else -> "保留质量与标签更完整的版本"
        }
        if (keep.hasCover && keep.hasLyrics) reasons += "保留封面与歌词齐全的版本"
        return reasons.joinToString("；")
    }

    private val DUPLICATE_SUFFIX = Regex("""\(\d+\)$""")
}

fun defaultLibraryTrashDir(): File =
    File(System.getProperty("user.home"), ".musicunlock/trash")

fun defaultLibraryCleanupJournalDir(): File =
    File(System.getProperty("user.home"), ".musicunlock/library-cleanup")

private fun moveFile(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath())
    } catch (_: java.nio.file.FileSystemException) {
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        Files.delete(source.toPath())
    }
}
