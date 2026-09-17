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

/** 本地曲库扫描、缺失检查、批量改名、标签修复与跨平台匹配。 */
class LibraryMaintenanceService(
    private val index: LibraryIndex = LibraryIndex(),
) {
    fun scan(directory: File, hash: Boolean = true): LibraryScanReport = index.scan(directory, hash)

    fun audit(directory: File? = null): LibraryAuditReport {
        val entries = index.all().filter { directory == null || File(it.path).absolutePath.startsWith(directory.absolutePath) }
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

    fun removeDuplicates(groups: List<List<LibraryEntry>>): Int {
        var removed = 0
        groups.forEach { group ->
            val keep = group.minByOrNull { it.path.length } ?: return@forEach
            group.filter { it.path != keep.path }.forEach { entry ->
                if (runCatching { File(entry.path).delete() }.getOrDefault(false)) {
                    index.remove(entry.path)
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

    private fun LibraryEntry.toMusicSong(): MusicSong = MusicSong(
        id = sourceSongId ?: File(path).nameWithoutExtension,
        name = title ?: File(path).nameWithoutExtension,
        artists = artist?.split('/', '、', ',', '；', ';')?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
        albumName = album,
        coverUrl = null,
        metadata = mapOf("localPath" to path),
        durationSeconds = durationSeconds,
    )

    private fun normalize(value: String): String = buildString {
        value.lowercase().forEach { ch -> if (ch.isLetterOrDigit()) append(ch) }
    }
}
