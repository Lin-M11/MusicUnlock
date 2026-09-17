package musicunlock.backup

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import musicunlock.BuildInfo
import musicunlock.library.LibraryIndex
import musicunlock.online.defaultTaskFile
import musicunlock.service.defaultConversionTaskFile
import musicunlock.settings.SettingsPortability
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupManifest(
    val schemaVersion: Int = 1,
    val appVersion: String,
    val createdAt: String,
    val entries: Map<String, String> = emptyMap(),
)

data class BackupExportResult(
    val file: File,
    val entryCount: Int,
    val bytes: Long,
)

data class BackupRestoreResult(
    val settingsImported: Boolean,
    val libraryImported: Int,
    val taskFilesRestored: Int,
    val warnings: List<String>,
)

/**
 * 应用级备份：设置、曲库索引和两类任务队列统一打包。
 * 登录 Cookie 始终排除；任务和曲库中的文件路径允许在恢复时重新映射。
 */
class AppBackupService(
    private val library: LibraryIndex = LibraryIndex(),
    private val downloadTaskFile: File = defaultTaskFile(),
    private val conversionTaskFile: File = defaultConversionTaskFile(),
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun export(
        target: File,
        includeSettings: Boolean = true,
        includeLibrary: Boolean = true,
        includeTasks: Boolean = true,
    ): BackupExportResult {
        val entries = linkedMapOf<String, ByteArray>()
        if (includeSettings) entries[SETTINGS_ENTRY] = SettingsPortability.toJson().toByteArray(Charsets.UTF_8)
        if (includeLibrary) entries[LIBRARY_ENTRY] = library.exportJson().toByteArray(Charsets.UTF_8)
        if (includeTasks) {
            if (downloadTaskFile.isFile) entries[DOWNLOAD_TASKS_ENTRY] = downloadTaskFile.readBytes()
            if (conversionTaskFile.isFile) entries[CONVERSION_TASKS_ENTRY] = conversionTaskFile.readBytes()
        }
        val manifest = BackupManifest(
            appVersion = BuildInfo.VERSION,
            createdAt = Instant.now().toString(),
            entries = entries.mapValues { (_, bytes) -> sha256(bytes) },
        )

        target.parentFile?.mkdirs()
        val temp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try {
            ZipOutputStream(temp.outputStream().buffered()).use { zip ->
                writeEntry(zip, MANIFEST_ENTRY, gson.toJson(manifest).toByteArray(Charsets.UTF_8))
                entries.forEach { (name, bytes) -> writeEntry(zip, name, bytes) }
            }
            moveReplacing(temp, target)
        } finally {
            temp.delete()
        }
        return BackupExportResult(target, entries.size, target.length())
    }

    fun restore(
        source: File,
        restoreSettings: Boolean = true,
        restoreLibrary: Boolean = true,
        restoreTasks: Boolean = true,
        relinkFrom: String? = null,
        relinkTo: String? = null,
    ): BackupRestoreResult {
        require(source.isFile) { "备份文件不存在：${source.absolutePath}" }
        val entries = readEntries(source)
        val manifest = entries[MANIFEST_ENTRY]?.toString(Charsets.UTF_8)?.let { json ->
            runCatching { gson.fromJson(json, BackupManifest::class.java) }.getOrNull()
        }
        val warnings = mutableListOf<String>()
        manifest?.entries?.forEach { (name, expected) ->
            val bytes = entries[name] ?: return@forEach
            if (sha256(bytes) != expected) warnings += "备份条目校验失败：$name"
        }

        var settingsImported = false
        if (restoreSettings && warnings.none { it.contains(SETTINGS_ENTRY) }) {
            entries[SETTINGS_ENTRY]?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)?.let { json ->
                SettingsPortability.importJson(json)
                settingsImported = true
            }
        }

        var libraryImported = 0
        if (restoreLibrary && warnings.none { it.contains(LIBRARY_ENTRY) }) {
            entries[LIBRARY_ENTRY]?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank)?.let { json ->
                libraryImported = library.importJson(json, merge = true, onlyExisting = false).imported
            }
        }
        if (!relinkFrom.isNullOrBlank() && !relinkTo.isNullOrBlank()) {
            library.relink(relinkFrom, relinkTo)
        }

        var restoredTaskFiles = 0
        if (restoreTasks) {
            listOf(DOWNLOAD_TASKS_ENTRY to downloadTaskFile, CONVERSION_TASKS_ENTRY to conversionTaskFile).forEach { (name, target) ->
                if (warnings.any { it.contains(name) }) return@forEach
                val bytes = entries[name] ?: return@forEach
                if (!isJsonArray(bytes.toString(Charsets.UTF_8))) {
                    warnings += "任务文件格式无效：$name"
                    return@forEach
                }
                target.parentFile?.mkdirs()
                val temp = File.createTempFile(target.name, ".tmp", target.parentFile)
                try {
                    temp.writeBytes(bytes)
                    moveReplacing(temp, target)
                    restoredTaskFiles++
                } finally {
                    temp.delete()
                }
            }
        }

        return BackupRestoreResult(settingsImported, libraryImported, restoredTaskFiles, warnings)
    }

    private fun readEntries(source: File): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name !in ALLOWED_ENTRIES) {
                    zip.closeEntry()
                    continue
                }
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun isJsonArray(json: String): Boolean = runCatching {
        JsonParser.parseString(json).isJsonArray
    }.getOrDefault(false)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MANIFEST_ENTRY = "manifest.json"
        const val SETTINGS_ENTRY = "settings.json"
        const val LIBRARY_ENTRY = "library.json"
        const val DOWNLOAD_TASKS_ENTRY = "download-tasks.json"
        const val CONVERSION_TASKS_ENTRY = "conversion-tasks.json"
        val ALLOWED_ENTRIES = setOf(MANIFEST_ENTRY, SETTINGS_ENTRY, LIBRARY_ENTRY, DOWNLOAD_TASKS_ENTRY, CONVERSION_TASKS_ENTRY)
    }
}
