package musicunlock.library

import musicunlock.online.MusicSong
import musicunlock.online.OutputTemplate
import musicunlock.online.ProviderRegistry
import musicunlock.service.AudioTagData
import musicunlock.service.TagWriter
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs

data class LibraryIssue(
    val path: String,
    val missingTitle: Boolean,
    val missingArtist: Boolean,
    val missingCover: Boolean,
    val missingLyrics: Boolean,
    val duplicateOf: String? = null,
)

class LibraryAuditReport(
    val total: Int,
    val issues: List<LibraryIssue>,
    val duplicateGroups: List<List<LibraryEntry>>,
)

class RenameOutcome(val renamed: Int, val failed: List<String>)

enum class EditableTagField {
    TITLE,
    ARTIST,
    ALBUM,
    ALBUM_ARTIST,
    TRACK_NUMBER,
    DISC_NUMBER,
    YEAR,
    GENRE,
    COMPOSER,
    ISRC,
    LYRICS,
    COVER,
    RATING,
    FAVORITE,
}

data class EditableTags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val composer: String? = null,
    val isrc: String? = null,
    val lyrics: String? = null,
    val cover: ByteArray? = null,
    val rating: Int? = null,
    val favorite: Boolean? = null,
)

/** 本地曲库扫描、缺失检查、批量改名、标签修复与跨平台匹配。 */
class LibraryMaintenanceService(
    private val index: LibraryIndex = LibraryIndex(),
) {
    fun scan(directory: File, hash: Boolean = true, analyze: Boolean = false): LibraryScanReport =
        index.scan(directory, hash = hash || analyze, analyze = analyze)

    fun audit(directory: File? = null): LibraryAuditReport {
        val entries = index.all().filter { directory == null || isInsideDirectory(it.path, directory) }
        val duplicates = index.duplicateGroups()
        val duplicateMap = duplicates.flatten().associate { entry ->
            entry.path to duplicates.first { group -> group.any { it.path == entry.path } }
                .firstOrNull { it.path != entry.path }
                ?.path
        }
        val issues = entries.mapNotNull { entry ->
            val issue = LibraryIssue(
                path = entry.path,
                missingTitle = entry.title.isNullOrBlank(),
                missingArtist = entry.artist.isNullOrBlank(),
                missingCover = !entry.hasCover,
                missingLyrics = !entry.hasLyrics,
                duplicateOf = duplicateMap[entry.path],
            )
            if (issue.missingTitle || issue.missingArtist || issue.missingCover || issue.missingLyrics || issue.duplicateOf != null) issue else null
        }
        return LibraryAuditReport(entries.size, issues, duplicates)
    }

    fun renameByTemplate(entries: List<LibraryEntry>, template: String): RenameOutcome {
        var renamed = 0
        val failed = mutableListOf<String>()
        entries.forEach { entry ->
            runCatching {
                val file = File(entry.path)
                if (!file.isFile) throw IllegalStateException("文件不存在")
                val song = entry.toMusicSong()
                val rendered = OutputTemplate.render(template, song, entry.platform ?: "本地")
                val target = File(file.parentFile, "$rendered.${file.extension}")
                if (target.absolutePath == file.absolutePath) return@runCatching
                target.parentFile?.mkdirs()
                if (target.exists()) throw IllegalStateException("目标文件已存在：${target.name}")
                Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                val lrc = File(file.parentFile, "${file.nameWithoutExtension}.lrc")
                if (lrc.isFile) {
                    Files.move(
                        lrc.toPath(),
                        File(target.parentFile, "${target.nameWithoutExtension}.lrc").toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                index.remove(file.absolutePath)
                index.upsert(target, entry.platform, entry.sourceSongId)
                renamed++
            }.onFailure { failed += "${entry.path}: ${it.message}" }
        }
        return RenameOutcome(renamed, failed)
    }

    fun repairTags(entry: LibraryEntry, fetchOnline: Boolean = true): Boolean {
        val file = File(entry.path)
        if (!file.isFile) return false
        val local = entry.toMusicSong()
        val provider = entry.platform?.let(ProviderRegistry::find)
        val song = if (fetchOnline && provider != null && !entry.sourceSongId.isNullOrBlank()) {
            runCatching { provider.song(entry.sourceSongId) }.getOrNull() ?: local
        } else local
        val cover = song.coverUrl?.let { provider?.bytes(it) }
        val lyrics = provider?.lyrics(song)
        val ok = TagWriter.embed(
            file,
            AudioTagData(
                title = song.name,
                artist = song.artistText,
                album = song.albumName,
                trackNumber = song.trackNumber,
                discNumber = song.discNumber,
                year = song.year,
                genre = song.genre,
                composer = song.composer,
                isrc = song.isrc,
                lyrics = lyrics?.let(TagWriter::mergedLyrics),
                cover = cover,
                platform = entry.platform,
                sourceSongId = entry.sourceSongId,
            ),
        )
        if (lyrics != null && !lyrics.isEmpty) TagWriter.writeLyricsFile(file, lyrics)
        index.upsert(file, entry.platform, entry.sourceSongId, hash = false)
        return ok
    }

    fun repairIssues(issues: List<LibraryIssue>, fetchOnline: Boolean = true): Int {
        val entries = index.all().associateBy { it.path }
        return issues.mapNotNull { entries[it.path] }.count { repairTags(it, fetchOnline) }
    }

    /** 按 [fields] 指定的字段写回标签；[fields] 为空时不改动任何文件。 */
    fun updateTags(
        entry: LibraryEntry,
        tags: EditableTags,
        fields: Set<EditableTagField>,
    ): Boolean {
        val file = File(entry.path)
        if (!file.isFile || fields.isEmpty()) return false
        val ok = TagWriter.embed(
            file,
            tags.toAudioTagData(entry, fields),
            clearFields = tags.clearFields(fields),
            clearCover = EditableTagField.COVER in fields && tags.cover == null,
        )
        if (ok) index.upsert(file, entry.platform, entry.sourceSongId, hash = false)
        return ok
    }

    /** 批量写回标签；必须显式给出 [fields]，避免误用默认值覆盖全部字段。 */
    fun updateTagsBatch(
        entries: List<LibraryEntry>,
        tags: EditableTags,
        fields: Set<EditableTagField>,
    ): Int = entries.count { entry -> updateTags(entry, tags, fields) }

    fun snapshotTags(entry: LibraryEntry): EditableTags? = runCatching {
        val audio = AudioFileIO.read(File(entry.path))
        val tag = audio.tag
        EditableTags(
            title = runCatching { tag?.getFirst(FieldKey.TITLE) }.getOrNull(),
            artist = runCatching { tag?.getFirst(FieldKey.ARTIST) }.getOrNull(),
            album = runCatching { tag?.getFirst(FieldKey.ALBUM) }.getOrNull(),
            albumArtist = runCatching { tag?.getFirst(FieldKey.ALBUM_ARTIST) }.getOrNull(),
            trackNumber = runCatching { tag?.getFirst(FieldKey.TRACK) }.getOrNull()?.filter(Char::isDigit)?.toIntOrNull(),
            discNumber = runCatching { tag?.getFirst(FieldKey.DISC_NO) }.getOrNull()?.filter(Char::isDigit)?.toIntOrNull(),
            year = runCatching { tag?.getFirst(FieldKey.YEAR) }.getOrNull()?.filter(Char::isDigit)?.take(4)?.toIntOrNull(),
            genre = runCatching { tag?.getFirst(FieldKey.GENRE) }.getOrNull(),
            composer = runCatching { tag?.getFirst(FieldKey.COMPOSER) }.getOrNull(),
            isrc = runCatching { tag?.getFirst(FieldKey.ISRC) }.getOrNull(),
            lyrics = runCatching { tag?.getFirst(FieldKey.LYRICS) }.getOrNull(),
            cover = runCatching { tag?.firstArtwork?.binaryData }.getOrNull(),
            rating = runCatching { tag?.getFirst(FieldKey.RATING) }.getOrNull()?.filter(Char::isDigit)?.toIntOrNull(),
            favorite = runCatching { tag?.getFirst(FieldKey.CUSTOM2) }.getOrNull()?.let { it == "favorite=1" },
        )
    }.getOrNull()

    fun removeDuplicates(groups: List<List<LibraryEntry>>): Int {
        var removed = 0
        DuplicateCleanupPlanner.plan(groups).decisions
            .filter { it.matchKind == DuplicateMatchKind.EXACT }
            .forEach { decision ->
                decision.removePaths.forEach { path ->
                    val file = File(path)
                    if (runCatching { file.delete() }.getOrDefault(false)) {
                        File(file.parentFile, "${file.nameWithoutExtension}.lrc").takeIf(File::isFile)?.delete()
                        index.remove(path)
                        removed++
                    }
                }
            }
        return removed
    }

    fun portableLibrary(entries: List<LibraryEntry>): Int = entries.count { File(it.path).isFile }

    /**
     * 在目标平台匹配一首迁移候选。
     * 优先 ISRC，其次规范化歌名/歌手，并用时长容差降低同名版本误匹配。
     */
    fun matchAcrossPlatform(song: MusicSong, candidates: List<MusicSong>): MusicSong? {
        if (!song.isrc.isNullOrBlank()) {
            candidates.firstOrNull { it.isrc.equals(song.isrc, ignoreCase = true) }?.let { return it }
        }
        val title = normalize(song.name)
        val artists = song.artists.map(::normalize).filter(String::isNotBlank)
        return candidates
            .mapNotNull { candidate ->
                val candidateTitle = normalize(candidate.name)
                if (candidateTitle != title) return@mapNotNull null
                val candidateArtists = candidate.artists.map(::normalize)
                val artistScore = artists.count { artist -> candidateArtists.any { it.contains(artist) || artist.contains(it) } }
                val durationDiff = if (song.durationSeconds != null && candidate.durationSeconds != null) {
                    abs(song.durationSeconds - candidate.durationSeconds)
                } else 0
                if (durationDiff > 5) return@mapNotNull null
                Triple(candidate, artistScore, durationDiff)
            }
            .sortedWith(compareByDescending<Triple<MusicSong, Int, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()
            ?.first
    }

    private fun EditableTags.toAudioTagData(entry: LibraryEntry, fields: Set<EditableTagField>): AudioTagData = AudioTagData(
        title = title.takeIf { EditableTagField.TITLE in fields }?.trim()?.takeIf(String::isNotBlank),
        artist = artist.takeIf { EditableTagField.ARTIST in fields }?.trim()?.takeIf(String::isNotBlank),
        album = album.takeIf { EditableTagField.ALBUM in fields }?.trim()?.takeIf(String::isNotBlank),
        albumArtist = albumArtist.takeIf { EditableTagField.ALBUM_ARTIST in fields }?.trim()?.takeIf(String::isNotBlank),
        trackNumber = trackNumber.takeIf { EditableTagField.TRACK_NUMBER in fields },
        discNumber = discNumber.takeIf { EditableTagField.DISC_NUMBER in fields },
        year = year.takeIf { EditableTagField.YEAR in fields },
        genre = genre.takeIf { EditableTagField.GENRE in fields }?.trim()?.takeIf(String::isNotBlank),
        composer = composer.takeIf { EditableTagField.COMPOSER in fields }?.trim()?.takeIf(String::isNotBlank),
        isrc = isrc.takeIf { EditableTagField.ISRC in fields }?.trim()?.takeIf(String::isNotBlank),
        lyrics = lyrics.takeIf { EditableTagField.LYRICS in fields }?.trim()?.takeIf(String::isNotBlank),
        cover = cover.takeIf { EditableTagField.COVER in fields },
        platform = entry.platform,
        sourceSongId = entry.sourceSongId,
        rating = rating.takeIf { EditableTagField.RATING in fields },
        favorite = favorite.takeIf { EditableTagField.FAVORITE in fields },
    )

    private fun EditableTags.clearFields(fields: Set<EditableTagField>): Set<FieldKey> = buildSet {
        if (EditableTagField.TITLE in fields && title.isNullOrBlank()) add(FieldKey.TITLE)
        if (EditableTagField.ARTIST in fields && artist.isNullOrBlank()) add(FieldKey.ARTIST)
        if (EditableTagField.ALBUM in fields && album.isNullOrBlank()) add(FieldKey.ALBUM)
        if (EditableTagField.ALBUM_ARTIST in fields && albumArtist.isNullOrBlank()) add(FieldKey.ALBUM_ARTIST)
        if (EditableTagField.TRACK_NUMBER in fields && trackNumber == null) add(FieldKey.TRACK)
        if (EditableTagField.DISC_NUMBER in fields && discNumber == null) add(FieldKey.DISC_NO)
        if (EditableTagField.YEAR in fields && year == null) add(FieldKey.YEAR)
        if (EditableTagField.GENRE in fields && genre.isNullOrBlank()) add(FieldKey.GENRE)
        if (EditableTagField.COMPOSER in fields && composer.isNullOrBlank()) add(FieldKey.COMPOSER)
        if (EditableTagField.ISRC in fields && isrc.isNullOrBlank()) add(FieldKey.ISRC)
        if (EditableTagField.LYRICS in fields && lyrics.isNullOrBlank()) add(FieldKey.LYRICS)
        if (EditableTagField.RATING in fields && (rating == null || rating <= 0)) add(FieldKey.RATING)
        if (EditableTagField.FAVORITE in fields && favorite == null) add(FieldKey.CUSTOM2)
    }

    private fun LibraryEntry.toMusicSong(): MusicSong = MusicSong(
        id = sourceSongId ?: File(path).nameWithoutExtension,
        name = title ?: File(path).nameWithoutExtension,
        artists = artist?.split('/', '、', ',', '；', ';')?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
        albumName = album,
        coverUrl = null,
        metadata = buildMap {
            put("localPath", path)
            fingerprint?.let { put("fingerprint", it) }
        },
        durationSeconds = durationSeconds,
        trackNumber = trackNumber,
        discNumber = discNumber,
        year = year,
        genre = genre,
        composer = composer,
        isrc = isrc,
    )

    private fun normalize(value: String): String = buildString {
        value.lowercase().forEach { ch -> if (ch.isLetterOrDigit()) append(ch) }
    }
}

/**
 * 判断 [path] 是否位于 [directory] 之下。
 * 按绝对路径与分隔符边界比较，既兼容 Windows 的反斜杠，也避免 `/music` 误匹配 `/music2`。
 */
internal fun isInsideDirectory(path: String, directory: File): Boolean {
    val root = directory.absoluteFile.path.trimEnd('/', '\\')
    val target = File(path).absoluteFile.path
    return target == root || target.startsWith("$root/") || target.startsWith("$root\\")
}
