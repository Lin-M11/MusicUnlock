package musicunlock.update

import com.google.gson.JsonParser
import musicunlock.AppLinks
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** GitHub Releases 上的最新正式版本。 */
data class ReleaseInfo(
    /** 去掉前导 v 的版本号，例如 1.2.0。 */
    val version: String,
    /** Release 页面地址，用于打开下载入口。 */
    val pageUrl: String,
)

/**
 * 启动时检查 GitHub Releases 是否有新版本。
 *
 * 以 `releases/latest` 为准（GitHub 该接口不返回草稿与预发布版本）。
 * 网络不可用、接口限流、超时或返回体异常时一律返回 null，
 * 调用方按"无更新"处理，不影响启动。
 */
object UpdateChecker {

    private const val TIMEOUT_SECONDS = 5L

    /** releases/latest 接口地址。 */
    val apiUrl: String get() = "https://api.github.com/repos/${AppLinks.REPO}/releases/latest"

    /** 检查更新：仅当远端版本高于 [current] 时返回结果，其余情况返回 null。 */
    fun check(current: String): ReleaseInfo? = check(current, apiUrl)

    /** 同上，但可指定接口地址（便于在测试中覆盖不可达等异常分支）。 */
    internal fun check(current: String, url: String): ReleaseInfo? {
        val release = fetchLatest(url) ?: return null
        return if (isNewer(current, release.version)) release else null
    }

    private fun fetchLatest(url: String): ReleaseInfo? = try {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MusicUnlock")
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) parseLatest(response.body()) else null
    } catch (e: Exception) {
        null
    }

    /** 解析 releases/latest 返回体；不是合法 JSON 或缺少 tag_name 时返回 null。 */
    fun parseLatest(json: String): ReleaseInfo? = try {
        val root = JsonParser.parseString(json).asJsonObject
        val tag = root.get("tag_name")?.asString?.trim().orEmpty()
        val page = root.get("html_url")?.asString?.trim().orEmpty()
        if (tag.isBlank()) null else ReleaseInfo(normalize(tag), page.ifBlank { AppLinks.RELEASES_PAGE })
    } catch (e: Exception) {
        null
    }

    /** [latest] 是否高于 [current]。 */
    fun isNewer(current: String, latest: String): Boolean = compareVersions(current, latest) < 0

    /**
     * 比较版本号：按数字段逐级比较（1.10.0 > 1.9.0），缺省段视为 0（1.1 == 1.1.0），
     * 前导 v 与 `-beta.1` / `+build` 之类后缀不参与比较。
     */
    fun compareVersions(a: String, b: String): Int {
        val left = segments(a)
        val right = segments(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
            if (diff != 0) return diff
        }
        return 0
    }

    private fun segments(value: String): List<Int> =
        normalize(value).split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun normalize(value: String): String =
        value.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
}
