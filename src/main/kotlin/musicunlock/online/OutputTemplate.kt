package musicunlock.online

import java.io.File
import java.util.Locale

/** 文件与目录命名模板。模板变量不会继承路径分隔符，避免意外写出目标目录。 */
object OutputTemplate {

    private val tokenPattern = Regex("""\{([a-zA-Z][a-zA-Z0-9_]*)(?::(\d+))?}""")
    private val invalidSegmentChars = Regex("""[\\/:*?"<>|\r\n\t]""")

    fun render(
        template: String,
        song: MusicSong,
        platform: String,
        playlistName: String? = null,
        qualityLabel: String? = null,
        bitrateKbps: Int? = null,
    ): String {
        val values = mapOf(
            "title" to song.name,
            "artist" to song.artistText.ifBlank { "未知歌手" },
            "album" to (song.albumName ?: "未知专辑"),
            "playlist" to (playlistName ?: "未分类"),
            "platform" to platform,
            "track" to (song.trackNumber?.toString() ?: "0"),
            "track02" to (song.trackNumber?.toString()?.padStart(2, '0') ?: "00"),
            "disc" to (song.discNumber?.toString() ?: "1"),
            "year" to (song.year?.toString() ?: "未知年份"),
            "genre" to (song.genre ?: "未知流派"),
            "composer" to (song.composer ?: "未知作曲"),
            "isrc" to (song.isrc ?: "无ISRC"),
            "quality" to (qualityLabel ?: "原始音质"),
            "bitrate" to (bitrateKbps?.toString() ?: "0"),
        )
        val raw = tokenPattern.replace(template) { match ->
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            val width = match.groupValues[2].toIntOrNull()
            val value = sanitizeSegment(values[key] ?: match.value)
            if (width != null && value.all(Char::isDigit)) value.padStart(width, '0') else value
        }
        val segments = raw
            .replace('\\', '/')
            .split('/')
            .map { sanitizeSegment(it) }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        return if (segments.isEmpty()) "未命名" else segments.joinToString(File.separator)
    }

    fun sanitizeSegment(value: String): String {
        val cleaned = value
            .replace(invalidSegmentChars, "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.')
        val shortened = if (cleaned.length > 120) cleaned.substring(0, 120).trim().trim('.') else cleaned
        return shortened.ifBlank { "未命名" }
    }

    fun defaultTemplate(): String = "{artist}/{album}/{title}"
}
