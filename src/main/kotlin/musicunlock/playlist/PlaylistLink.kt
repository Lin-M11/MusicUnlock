package musicunlock.playlist

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 歌单链接解析。
 *
 * 支持直接粘贴歌单 ID、`music.163.com` 的歌单链接，以及分享短链
 * （如 `163cn.tv`，通过跟随跳转拿到真实地址）。
 */
object PlaylistLink {

    private const val HOST_SUFFIX = "music.163.com"
    private const val MAX_REDIRECTS = 3
    private const val TIMEOUT_SECONDS = 6L

    /** 从粘贴文本中解析网易云歌单 ID；无法识别时返回 null（仅解析，不联网）。 */
    fun parseNeteaseId(input: String): Long? {
        val url = firstUrl(input)
        if (url == null) {
            val digits = input.trim()
            return digits.takeIf { it.length in 5..20 && it.all(Char::isDigit) }?.toLongOrNull()
        }
        val uri = runCatching { URI.create(url) }.getOrNull() ?: return null
        val host = uri.host.orEmpty()
        if (host != HOST_SUFFIX && !host.endsWith(".$HOST_SUFFIX")) return null
        // 分享链接常把路由放在 # 之后，例如 /#/playlist?id=123
        val route = listOfNotNull(uri.path, uri.fragment).joinToString("/")
        if (!route.contains("playlist")) return null
        queryParam(uri.rawQuery, "id")?.let { return it }
        queryParam(uri.rawFragment, "id")?.let { return it }
        pathId(route)?.let { return it }
        return null
    }

    /** 解析歌单 ID；短链会跟随跳转后再解析。 */
    fun resolveNeteaseId(input: String): Long? {
        parseNeteaseId(input)?.let { return it }
        val url = firstUrl(input) ?: return null
        return followRedirects(url)?.let { parseNeteaseId(it) }
    }

    /** 从文本中取出第一个 http/https 链接。 */
    private fun firstUrl(input: String): String? {
        val start = input.indexOf("http")
        if (start < 0) return null
        val rest = input.substring(start)
        return rest.takeWhile { !it.isWhitespace() }.trimEnd(',', '，', '。', ')', '）', ']', '】')
    }

    /** 解析 `?id=` / `&id=` 查询参数。 */
    private fun queryParam(query: String?, name: String): Long? {
        if (query.isNullOrBlank()) return null
        // fragment 里可能带着路由前缀，例如 /playlist?id=123
        val pairs = query.substringAfter('?', query).split('&')
        for (pair in pairs) {
            if (pair.substringBefore('=', "") != name) continue
            pair.substringAfter('=', "").toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        return null
    }

    /** 解析 `/playlist/<id>` 形式的路径。 */
    private fun pathId(route: String): Long? {
        val parts = route.split('/').filter { it.isNotEmpty() }
        val index = parts.indexOfFirst { it == "playlist" }
        if (index < 0 || index + 1 >= parts.size) return null
        return parts[index + 1].toLongOrNull()?.takeIf { it > 0 }
    }

    /** 跟随跳转拿最终地址；失败返回 null。 */
    private fun followRedirects(url: String): String? = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        var current = url
        repeat(MAX_REDIRECTS) {
            val request = HttpRequest.newBuilder(URI.create(current))
                .header("User-Agent", "MusicUnlock")
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            val location = response.headers().firstValue("Location").orElse(null) ?: return null
            current = URI.create(current).resolve(location).toString()
            if (parseNeteaseId(current) != null) return current
        }
        null
    } catch (e: Exception) {
        null
    }
}
