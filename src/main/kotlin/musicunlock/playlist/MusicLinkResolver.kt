package musicunlock.playlist

import musicunlock.online.MusicPlatform
import java.net.URI
import java.net.URLDecoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

enum class MusicLinkKind { SONG, PLAYLIST, ALBUM, ARTIST }

data class ParsedMusicLink(
    val platform: MusicPlatform,
    val kind: MusicLinkKind,
    val id: String,
    val url: String,
)

/** 识别网易云、QQ、酷狗、酷我分享链接中的歌曲/歌单/专辑/歌手 id。 */
object MusicLinkResolver {
    private val httpUrl = Regex("""https?://[^\s，。]+""", RegexOption.IGNORE_CASE)
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun parse(input: String): ParsedMusicLink? {
        val text = input.trim()
        val url = httpUrl.find(text)?.value?.trimEnd(',', '，', '。', ')', '）', ']', '】') ?: return null
        parseUrl(url)?.let { return it }
        return follow(url)?.let(::parseUrl)
    }

    fun parseUrl(url: String): ParsedMusicLink? {
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty().removePrefix("www.").removePrefix("m.").lowercase()
        val route = listOf(uri.path.orEmpty(), uri.fragment.orEmpty()).joinToString("/")
        val params = parseParams(uri.rawQuery) + parseParams(uri.rawFragment)
        return when {
            host == "music.163.com" || host.endsWith(".music.163.com") || host == "163cn.tv" -> parseNetease(uri, route, params)
            host == "y.qq.com" || host.endsWith(".y.qq.com") || host == "c6.y.qq.com" -> parseQq(route, params)
            host == "kugou.com" || host.endsWith(".kugou.com") -> parseKugou(uri, route, params)
            host == "kuwo.cn" || host.endsWith(".kuwo.cn") -> parseKuwo(route, params)
            else -> null
        }
    }

    private fun parseNetease(uri: URI, route: String, params: Map<String, String>): ParsedMusicLink? {
        val id = params["id"] ?: pathAfter(route, "playlist") ?: pathAfter(route, "song") ?: pathAfter(route, "album")
        val kind = when {
            route.contains("playlist") -> MusicLinkKind.PLAYLIST
            route.contains("song") -> MusicLinkKind.SONG
            route.contains("album") -> MusicLinkKind.ALBUM
            route.contains("artist") || route.contains("singer") -> MusicLinkKind.ARTIST
            else -> params["id"]?.let { MusicLinkKind.SONG }
        } ?: return null
        return id?.let { ParsedMusicLink(MusicPlatform.NETEASE, kind, it, uri.toString()) }
    }

    private fun parseQq(route: String, params: Map<String, String>): ParsedMusicLink? {
        val explicitKind = params["type"]?.lowercase()
        val id = params["songmid"] ?: params["songMid"] ?: params["disstid"] ?: params["albummid"] ?: params["singerMid"] ?: params["mid"]
        val kind = when {
            route.contains("song", true) || params.containsKey("songmid") -> MusicLinkKind.SONG
            route.contains("playlist", true) || params.containsKey("disstid") -> MusicLinkKind.PLAYLIST
            route.contains("album", true) || params.containsKey("albummid") -> MusicLinkKind.ALBUM
            route.contains("singer", true) || params.containsKey("singerMid") -> MusicLinkKind.ARTIST
            explicitKind == "song" -> MusicLinkKind.SONG
            else -> null
        } ?: return null
        val resolved = id ?: lastPathToken(route) ?: return null
        return ParsedMusicLink(MusicPlatform.QQ, kind, resolved, "https://y.qq.com/")
    }

    private fun parseKugou(uri: URI, route: String, params: Map<String, String>): ParsedMusicLink? {
        val hash = params["hash"] ?: params["HASH"]
        if (!hash.isNullOrBlank()) return ParsedMusicLink(MusicPlatform.KUGOU, MusicLinkKind.SONG, hash, uri.toString())
        val albumId = params["albumid"] ?: params["albumId"] ?: pathAfter(route, "album")
        if (!albumId.isNullOrBlank()) return ParsedMusicLink(MusicPlatform.KUGOU, MusicLinkKind.ALBUM, albumId, uri.toString())
        val artistId = params["singerid"] ?: params["singerId"] ?: pathAfter(route, "singer") ?: pathAfter(route, "artist")
        if (!artistId.isNullOrBlank()) return ParsedMusicLink(MusicPlatform.KUGOU, MusicLinkKind.ARTIST, artistId, uri.toString())
        val listId = params["listid"] ?: params["specialid"] ?: params["id"] ?: pathAfter(route, "list") ?: pathAfter(route, "special")
        if (!listId.isNullOrBlank() && (route.contains("list", true) || route.contains("special", true) || params.containsKey("listid"))) {
            return ParsedMusicLink(MusicPlatform.KUGOU, MusicLinkKind.PLAYLIST, listId, uri.toString())
        }
        return null
    }

    private fun parseKuwo(route: String, params: Map<String, String>): ParsedMusicLink? {
        val id = params["id"] ?: params["rid"] ?: params["albumId"] ?: params["artistid"]
        val kind = when {
            route.contains("playlist_detail", true) -> MusicLinkKind.PLAYLIST
            route.contains("play_detail", true) || params.containsKey("rid") -> MusicLinkKind.SONG
            route.contains("album", true) -> MusicLinkKind.ALBUM
            route.contains("artist", true) -> MusicLinkKind.ARTIST
            else -> null
        } ?: return null
        val resolved = id ?: lastPathToken(route) ?: return null
        return ParsedMusicLink(MusicPlatform.KUWO, kind, resolved, "https://www.kuwo.cn/")
    }

    private fun follow(url: String): String? = runCatching {
        var current = url
        repeat(5) {
            val request = HttpRequest.newBuilder(URI.create(current))
                .header("User-Agent", "Mozilla/5.0 MusicUnlock")
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            val location = response.headers().firstValue("Location").orElse(null) ?: return null
            current = URI.create(current).resolve(location).toString()
            if (parseUrl(current) != null) return current
        }
        null
    }.getOrNull()

    private fun parseParams(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val query = raw.substringAfter('?', raw)
        return query.split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "").trim()
            if (key.isBlank()) return@mapNotNull null
            val value = runCatching { URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8) }.getOrDefault("")
            key to value
        }.toMap()
    }

    private fun pathAfter(route: String, segment: String): String? {
        val parts = route.split('/').filter(String::isNotBlank)
        val index = parts.indexOfFirst { it.equals(segment, ignoreCase = true) }
        return parts.getOrNull(index + 1)?.substringBefore('?')
    }

    private fun lastPathToken(route: String): String? = route.split('/').filter(String::isNotBlank).lastOrNull()?.substringBefore('?')
}
