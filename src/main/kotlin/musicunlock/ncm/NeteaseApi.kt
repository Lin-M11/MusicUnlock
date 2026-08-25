package musicunlock.ncm

import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration

/**
 * 网易云官方网页端接口客户端。
 *
 * - 扫码登录：`unikey` 生成二维码 → 轮询扫码状态（800 失效 / 801 等待 / 802 已扫待确认 / 803 成功）
 * - 登录态（Cookie）保存在进程内 CookieManager，支持退出登录时清空
 * - 歌单、歌曲详情、播放地址均走官方网页端接口
 *
 * 后续扩展其他平台时，各平台客户端实现统一登录/歌单/下载接口即可。
 */
object NeteaseApi {

    private const val BASE = "https://music.163.com"
    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    private val client: HttpClient = HttpClient.newBuilder()
        .cookieHandler(cookieManager)
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$BASE/",
        "Accept" to "*/*",
    )

    // ============================================================
    //  扫码登录
    // ============================================================

    /** 获取二维码 key（unikey）。 */
    fun qrKey(): String {
        val body = "type=1"
        val request = HttpRequest.newBuilder(URI.create("$BASE/api/login/qrcode/unikey"))
            .headers(*commonHeaders.toHeaderArray())
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val raw = sendJson(request)
        val resp = NeteaseJson.gson.fromJson(raw, RawUnikeyResponse::class.java)
        if (resp.code != 200) {
            throw IllegalStateException("获取登录二维码失败（code=${resp.code}）")
        }
        val unikey = resp.unikey
        if (unikey.isNullOrBlank()) {
            throw IllegalStateException("获取登录二维码失败：未返回 unikey")
        }
        return unikey
    }

    /** 轮询扫码状态。 */
    fun qrCheck(key: String): QrCheckResult {
        val uri = URI.create("$BASE/api/login/qrcode/client/login?key=${encode(key)}&type=1")
        val request = HttpRequest.newBuilder(uri)
            .headers(*commonHeaders.toHeaderArray())
            .GET()
            .build()
        val raw = sendJson(request)
        return parseQrCheck(raw)
    }

    // ============================================================
    //  账号与歌单
    // ============================================================

    /** 当前登录账号；未登录返回 null。 */
    fun account(): NeteaseAccount? = parseAccount(get("$BASE/api/nuser/account/get"))

    /** 拉取用户全部歌单（含收藏），自动翻页。 */
    fun playlists(): List<NeteasePlaylist> {
        val account = account() ?: throw IllegalStateException("未登录")
        val result = mutableListOf<NeteasePlaylist>()
        var offset = 0
        val limit = 1000
        while (true) {
            val uri = "$BASE/api/user/playlist?uid=${account.userId}&limit=$limit&offset=$offset"
            val (page, more) = parseUserPlaylists(get(uri))
            result.addAll(page)
            if (!more || page.size < limit) break
            offset += limit
        }
        return result
    }

    /** 歌单内全部歌曲 id（来自歌单详情的 trackIds）。 */
    fun playlistTrackIds(playlistId: Long): List<Long> {
        val raw = get("$BASE/api/v6/playlist/detail?id=$playlistId")
        return parsePlaylistTrackIds(raw)
    }

    /** 批量获取歌曲详情（含歌手/专辑/封面）。 */
    fun songDetails(ids: List<Long>): List<NeteaseSong> {
        if (ids.isEmpty()) return emptyList()
        val c = ids.joinToString(prefix = "[", postfix = "]") { """{"id":$it}""" }
        val uri = URI.create("$BASE/api/v3/song/detail?c=${encode(c)}")
        val request = HttpRequest.newBuilder(uri)
            .headers(*commonHeaders.toHeaderArray())
            .GET()
            .build()
        val raw = sendJson(request)
        return parseSongDetails(raw)
    }

    // ============================================================
    //  播放地址
    // ============================================================

    /**
     * 获取歌曲播放地址（高码率优先）。
     * 返回 null 表示该码率不可用（VIP/下架），由调用方降级到更低码率。
     */
    fun songUrl(songId: Long, br: Int): NeteaseSongUrl? {
        val raw = get("$BASE/api/song/enhance/player/url?id=$songId&ids=${encode("[$songId]")}&br=$br")
        return parseSongUrl(raw, songId, br)
    }

    // ============================================================
    //  下载与退出
    // ============================================================

    /** 下载 URL 到本地文件（覆盖已存在文件）。 */
    fun download(url: String, target: Path) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .headers(*commonHeaders.toHeaderArray())
            .GET()
            .timeout(Duration.ofMinutes(10))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target))
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("下载失败（HTTP ${response.statusCode()}）")
        }
    }

    /** 下载字节（用于封面图等小资源）。 */
    fun downloadBytes(url: String): ByteArray? {
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .headers(*commonHeaders.toHeaderArray())
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() in 200..299 && response.body().isNotEmpty()) response.body() else null
        } catch (e: Exception) {
            null
        }
    }

    /** 退出登录：清空会话内 Cookie。 */
    fun logout() {
        cookieManager.cookieStore.removeAll()
    }

    // ============================================================
    //  解析（独立成函数便于测试）
    // ============================================================

    internal fun parseQrCheck(raw: String): QrCheckResult {
        val resp = NeteaseJson.gson.fromJson(raw, RawQrCheckResponse::class.java)
        val state = when (resp.code) {
            800 -> QrLoginState.EXPIRED
            801 -> QrLoginState.WAIT
            802 -> QrLoginState.SCANNED
            803 -> QrLoginState.SUCCESS
            else -> QrLoginState.UNKNOWN
        }
        return QrCheckResult(state, resp.message)
    }

    internal fun parseAccount(raw: String): NeteaseAccount? {
        val resp = NeteaseJson.gson.fromJson(raw, RawAccountResponse::class.java)
        val profile = resp.profile ?: return null
        val nickname = profile.nickname?.takeIf { it.isNotBlank() } ?: return null
        return NeteaseAccount(
            nickname = nickname,
            avatarUrl = profile.avatarUrl?.takeIf { it.isNotBlank() },
            userId = profile.userId,
        )
    }

    internal fun parseUserPlaylists(raw: String): Pair<List<NeteasePlaylist>, Boolean> {
        val resp = NeteaseJson.gson.fromJson(raw, RawUserPlaylistResponse::class.java)
        if (resp.code != 200) throw IllegalStateException("获取歌单失败（code=${resp.code}）")
        val playlists = resp.playlist.orEmpty().mapNotNull { p ->
            val name = p.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NeteasePlaylist(
                id = p.id,
                name = name,
                coverImgUrl = p.coverImgUrl?.takeIf { it.isNotBlank() },
                trackCount = p.trackCount,
            )
        }
        return playlists to resp.more
    }

    internal fun parsePlaylistTrackIds(raw: String): List<Long> {
        val resp = NeteaseJson.gson.fromJson(raw, RawPlaylistDetailResponse::class.java)
        if (resp.code != 200) {
            throw IllegalStateException("获取歌单详情失败（code=${resp.code}）")
        }
        val playlist = resp.playlist ?: throw IllegalStateException("获取歌单详情失败：未返回歌单")
        return playlist.trackIds.orEmpty().map { it.id }.filter { it > 0 }
    }

    internal fun parseSongDetails(raw: String): List<NeteaseSong> {
        val resp = NeteaseJson.gson.fromJson(raw, RawSongDetailResponse::class.java)
        if (resp.code != 200) throw IllegalStateException("获取歌曲详情失败（code=${resp.code}）")
        return resp.songs.orEmpty().mapNotNull { song ->
            val name = song.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NeteaseSong(
                id = song.id,
                name = name,
                artists = song.ar.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) },
                albumName = song.al?.name?.takeIf { it.isNotBlank() },
                albumPicUrl = song.al?.picUrl?.takeIf { it.isNotBlank() },
            )
        }
    }

    internal fun parseSongUrl(raw: String, songId: Long, br: Int): NeteaseSongUrl? {
        val resp = NeteaseJson.gson.fromJson(raw, RawUrlResponse::class.java)
        if (resp.code != 200) return null
        val item = resp.data.orEmpty().firstOrNull { it.id == songId } ?: resp.data.orEmpty().firstOrNull()
        val url = item?.url?.takeIf { it.isNotBlank() } ?: return null
        return NeteaseSongUrl(
            id = item.id,
            url = url.toHttps(),
            br = item.br,
            type = item.type,
            size = item.size,
        )
    }

    // ============================================================
    //  HTTP 工具
    // ============================================================

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder(URI.create(url))
            .headers(*commonHeaders.toHeaderArray())
            .GET()
            .timeout(Duration.ofSeconds(20))
            .build()
        return sendJson(request)
    }

    private fun sendJson(request: HttpRequest): String {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("请求失败（HTTP ${response.statusCode()}）")
        }
        return response.body()
    }

    private fun Map<String, String>.toHeaderArray(): Array<String> =
        flatMap { listOf(it.key, it.value) }.toTypedArray()

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8)

    private fun String.toHttps(): String =
        if (startsWith("http://")) "https://" + substring("http://".length) else this
}
