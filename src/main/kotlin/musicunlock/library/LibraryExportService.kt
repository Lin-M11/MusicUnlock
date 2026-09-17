package musicunlock.library

import java.io.File

/** 曲库导出：M3U8、CSV，以及供脚本继续处理的音乐列表。 */
object LibraryExportService {
    fun exportM3u8(entries: List<LibraryEntry>, target: File): Int {
        target.parentFile?.mkdirs()
        val lines = mutableListOf("#EXTM3U")
        var count = 0
        entries.filter { File(it.path).isFile }.forEach { entry ->
            val title = entry.title?.takeIf(String::isNotBlank) ?: File(entry.path).nameWithoutExtension
            val artist = entry.artist?.takeIf(String::isNotBlank) ?: "未知歌手"
            val duration = entry.durationSeconds ?: -1
            lines += "#EXTINF:$duration,$artist - $title"
            lines += File(entry.path).absolutePath
            count++
        }
        target.writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
        return count
    }

    fun exportCsv(entries: List<LibraryEntry>, target: File): Int {
        target.parentFile?.mkdirs()
        val header = listOf("path", "title", "artist", "album", "albumArtist", "trackNumber", "discNumber", "year", "genre", "composer", "isrc", "durationSeconds", "platform", "format", "bitrateKbps", "hasCover", "hasLyrics", "isFavorite", "rating", "playCount")
        val lines = mutableListOf(header.joinToString(",") { csv(it) })
        entries.forEach { entry ->
            lines += listOf(
                entry.path,
                entry.title.orEmpty(),
                entry.artist.orEmpty(),
                entry.album.orEmpty(),
                entry.albumArtist.orEmpty(),
                entry.trackNumber?.toString().orEmpty(),
                entry.discNumber?.toString().orEmpty(),
                entry.year?.toString().orEmpty(),
                entry.genre.orEmpty(),
                entry.composer.orEmpty(),
                entry.isrc.orEmpty(),
                entry.durationSeconds?.toString().orEmpty(),
                entry.platform.orEmpty(),
                entry.format.orEmpty(),
                entry.bitRateKbps?.toString().orEmpty(),
                entry.hasCover.toString(),
                entry.hasLyrics.toString(),
                entry.isFavorite.toString(),
                entry.rating.toString(),
                entry.playCount.toString(),
            ).joinToString(",") { csv(it) }
        }
        target.writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
        return entries.size
    }

    private fun csv(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
