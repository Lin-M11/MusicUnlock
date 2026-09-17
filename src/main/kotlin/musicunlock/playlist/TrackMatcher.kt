package musicunlock.playlist

import musicunlock.online.MusicSong
import java.util.Locale

/** 队列中的一个本地文件。 */
data class LocalTrack(val path: String, val fileName: String)

/** 一首歌单曲目与本地命中文件。 */
data class TrackMatch(val song: MusicSong, val filePaths: List<String>)

/** 匹配结果：命中的曲目与仍未命中的曲目。 */
class MatchOutcome(
    val matched: List<TrackMatch>,
    val unmatched: List<MusicSong>,
) {
    val matchedPaths: Set<String> = matched.flatMapTo(LinkedHashSet()) { it.filePaths }
}

/**
 * 把歌单曲目与本地队列文件按名称匹配。
 *
 * 文件名形如 `歌名 - 歌手`、`歌手 - 歌名`、`歌名`、`01 歌名` 都能命中；
 * 文件名里写明了别的歌名或别的歌手时不命中，避免同名歌曲误勾。
 */
object TrackMatcher {

    private val numericToken = Regex("""\d+[a-z]*""")

    fun match(songs: List<MusicSong>, files: List<LocalTrack>): MatchOutcome {
        val matched = mutableListOf<TrackMatch>()
        val unmatched = mutableListOf<MusicSong>()
        for (song in songs) {
            val title = normalize(baseTitle(song.name))
            if (title.length < 2) {
                unmatched += song
                continue
            }
            val artistKeys = song.artists.map(::normalize).filter { it.isNotEmpty() }
            val hits = files.filter { matches(title, artistKeys, it.fileName) }.map { it.path }
            if (hits.isEmpty()) unmatched += song else matched += TrackMatch(song, hits)
        }
        return MatchOutcome(matched, unmatched)
    }

    /** 单个文件名是否命中给定标题与歌手。 */
    internal fun matches(title: String, artistKeys: List<String>, fileName: String): Boolean {
        val segments = segments(fileName)
        if (segments.isEmpty()) return false
        val name = normalize(baseName(fileName))
        val single = segments.size == 1
        val titleIndex = segments.indexOfFirst { normalize(baseTitle(it)) == title }
        val hit = titleIndex >= 0 || (single && (name == title || name.endsWith(title)))
        if (!hit) return false
        if (single || artistKeys.isEmpty()) return true
        if (artistKeys.any(name::contains)) return true
        // 其余片段只是编号或括号备注时不算歌手信息
        return segments.filterIndexed { index, _ -> index != titleIndex }.all(::isDecoration)
    }

    /** 去掉扩展名。 */
    internal fun baseName(fileName: String): String =
        fileName.substringBeforeLast('.', fileName)

    /** 取主标题：去掉 `(Live)`、`[Remix]` 之类的括号后缀。 */
    internal fun baseTitle(title: String): String {
        val index = title.indexOfFirst { it == '(' || it == '（' || it == '[' || it == '【' }
        if (index <= 0) return title
        val head = title.substring(0, index).trim()
        return if (head.length >= 2) head else title
    }

    /** 按 `歌名 - 歌手` 之类的分隔符切分文件名片段。 */
    internal fun segments(fileName: String): List<String> =
        baseName(fileName)
            .split(" - ", " – ", " — ", "_")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 只有编号、码率或括号备注的片段，不算歌手信息。 */
    internal fun isDecoration(token: String): Boolean {
        val text = token.trim()
        if (text.isEmpty()) return true
        val compact = normalize(text)
        if (compact.isEmpty() || numericToken.matches(compact)) return true
        val brackets = mapOf('(' to ')', '（' to '）', '[' to ']', '【' to '】')
        return brackets[text.first()] == text.last()
    }

    /** 只保留字母、数字与汉字，忽略大小写、空格与标点。 */
    internal fun normalize(text: String): String = buildString {
        for (ch in text.lowercase(Locale.ROOT)) {
            if (ch.isLetterOrDigit()) append(ch)
        }
    }
}
