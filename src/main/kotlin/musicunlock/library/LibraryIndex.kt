package musicunlock.library

import com.google.gson.GsonBuilder
import musicunlock.online.MusicSong
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

data class LibraryEntry(
    val path: String,
    val size: Long,
    val modifiedAt: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationSeconds: Int?,
    val platform: String? = null,
    val sourceSongId: String? = null,
    val hasCover: Boolean = false,
    val hasLyrics: Boolean = false,
    val contentHash: String? = null,
)

class LibraryScanReport(
    val indexed: Int,
    val removed: Int,
    val invalid: List<String>,
)

/** 轻量本地曲库索引，用于增量同步、重复检测和下载状态展示。 */
class LibraryIndex(
    private val indexFile: File = defaultLibraryIndexFile(),
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val lock = Any()
    @Volatile
    private var entries: Map<String, LibraryEntry> = load()

    fun all(): List<LibraryEntry> = entries.values.toList()

    fun find(song: MusicSong, platform: String? = null): List<LibraryEntry> {
        val exact = entries.values.filter {
            it.sourceSongId == song.id && (platform == null || it.platform == platform)
        }
        if (exact.isNotEmpty()) return exact
        val title = normalize(song.name)
        val artists = song.artists.map(::normalize).filter(String::isNotBlank)
        return entries.values.filter { entry ->
            normalize(entry.title.orEmpty()) == title &&
                (artists.isEmpty() || artists.any { normalize(entry.artist.orEmpty()).contains(it) }) &&
                (song.durationSeconds == null || entry.durationSeconds == null ||
                    kotlin.math.abs(song.durationSeconds - entry.durationSeconds) <= 3)
        }
    }

    fun contains(song: MusicSong, platform: String? = null): Boolean = find(song, platform).isNotEmpty()

    fun upsert(file: File, platform: String? = null, sourceSongId: String? = null, hash: Boolean = false): LibraryEntry? {
        val entry = readEntry(file, platform, sourceSongId, hash) ?: return null
        synchronized(lock) {
            entries = entries + (entry.path to entry)
            persist()
        }
        return entry
    }

    fun remove(path: String) {
        synchronized(lock) {
            entries = entries - path
            persist()
        }
    }

    fun scan(directory: File, hash: Boolean = false): LibraryScanReport {
        if (!directory.isDirectory) return LibraryScanReport(0, 0, listOf(directory.absolutePath))
        val found = linkedMapOf<String, LibraryEntry>()
        val invalid = mutableListOf<String>()
        val extensions = setOf("mp3", "flac", "ogg", "oga", "m4a", "mp4", "wav", "ape", "wma")
        Files.walk(directory.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .forEach { path ->
                    val file = path.toFile()
                    if (file.extension.lowercase() !in extensions) return@forEach
                    val entry = readEntry(file, null, null, hash)
                    if (entry == null) invalid += file.absolutePath else found[entry.path] = entry
                }
        }
        val before = entries
        val removed = before.keys.count { it.startsWith(directory.absolutePath) && it !in found }
        synchronized(lock) {
            entries = entries.filterKeys { !it.startsWith(directory.absolutePath) } + found
            persist()
        }
        return LibraryScanReport(found.size, removed, invalid)
    }

    fun removeMissing(): Int {
        val missing = entries.values.filterNot { File(it.path).isFile }.map { it.path }
        synchronized(lock) {
            entries = entries - missing.toSet()
            persist()
        }
        return missing.size
    }

    fun duplicateGroups(): List<List<LibraryEntry>> {
        val groups = mutableMapOf<String, MutableList<LibraryEntry>>()
        entries.values.forEach { entry ->
            val key = entry.contentHash?.takeIf(String::isNotBlank)
                ?: if (!entry.title.isNullOrBlank() && !entry.artist.isNullOrBlank()) {
                    "${normalize(entry.title)}|${normalize(entry.artist)}|${entry.durationSeconds ?: 0}"
                } else null
            if (key != null) groups.getOrPut(key) { mutableListOf() } += entry
        }
        return groups.values.filter { it.size > 1 }
    }

    private fun readEntry(file: File, platform: String?, songId: String?, hash: Boolean): LibraryEntry? = runCatching {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        val custom = runCatching { tag?.getFirst(FieldKey.CUSTOM1) }.getOrNull()
        val customParts = custom?.split(':', limit = 2).orEmpty()
        LibraryEntry(
            path = file.absolutePath,
            size = file.length(),
            modifiedAt = file.lastModified(),
            title = runCatching { tag?.getFirst(FieldKey.TITLE) }.getOrNull()?.takeIf(String::isNotBlank),
            artist = runCatching { tag?.getFirst(FieldKey.ARTIST) }.getOrNull()?.takeIf(String::isNotBlank),
            album = runCatching { tag?.getFirst(FieldKey.ALBUM) }.getOrNull()?.takeIf(String::isNotBlank),
            durationSeconds = runCatching { audio.audioHeader.trackLength }.getOrNull()?.takeIf { it > 0 },
            platform = platform ?: customParts.getOrNull(0)?.takeIf(String::isNotBlank),
            sourceSongId = songId ?: customParts.getOrNull(1)?.takeIf(String::isNotBlank),
            hasCover = runCatching { tag?.firstArtwork != null }.getOrDefault(false),
            hasLyrics = runCatching { !tag?.getFirst(FieldKey.LYRICS).isNullOrBlank() }.getOrDefault(false) ||
                File(file.parentFile, "${file.nameWithoutExtension}.lrc").isFile,
            contentHash = if (hash) sha256(file) else null,
        )
    }.getOrNull()

    private fun persist() {
        runCatching {
            indexFile.parentFile?.mkdirs()
            val temp = File.createTempFile(indexFile.name, ".tmp", indexFile.parentFile)
            temp.writeText(gson.toJson(entries.values.toList()))
            Files.move(
                temp.toPath(),
                indexFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun load(): Map<String, LibraryEntry> = runCatching {
        if (!indexFile.isFile) return emptyMap()
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, LibraryEntry::class.java).type
        gson.fromJson<List<LibraryEntry>>(indexFile.readText(), type).associateBy { it.path }
    }.getOrDefault(emptyMap())

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun normalize(value: String): String = buildString {
        value.lowercase().forEach { ch -> if (ch.isLetterOrDigit()) append(ch) }
    }
}

fun defaultLibraryIndexFile(): File = File(System.getProperty("user.home"), ".musicunlock/library.json")
