package musicunlock.ncm

import java.math.BigInteger
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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
    //  weapi 加密（官方网页端登录接口要求）
    // ============================================================

    private const val WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val WEAPI_IV = "0102030405060708"
    private const val WEAPI_BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val WEAPI_RSA_MODULUS =
        BigInteger("e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7", 16)
    private val WEAPI_RSA_EXPONENT = BigInteger("010001", 16)
    private val weapiRandom = SecureRandom()

    /** 生成 weapi 请求体（params + encSecKey，application/x-www-form-urlencoded）。 */
    private fun weapiBody(data: Map<String, Any?>): String {
        val text = NeteaseJson.gson.toJson(data)
        val secretKey = buildString {
            repeat(16) { append(WEAPI_BASE62[weapiRandom.nextInt(WEAPI_BASE62.length)]) }
        }
        val params = aesEncrypt(aesEncrypt(text, WEAPI_PRESET_KEY), secretKey)
        val encSecKey = rsaEncrypt(secretKey.reversed())
        return "params=${encode(params)}&encSecKey=$encSecKey"
    }

    private fun aesEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(WEAPI_IV.toByteArray(Charsets.UTF_8)),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    /** 官方 weapi 的 RSA：把倒序 secretKey 作为大整数做裸 RSA，输出固定 256 位 hex。 */
    private fun rsaEncrypt(text: String): String {
        val m = BigInteger(1, text.toByteArray(Charsets.UTF_8))
        val c = m.modPow(WEAPI_RSA_EXPONENT, WEAPI_RSA_MODULUS)
        return c.toString(16).padStart(256, '0')
    }

    // ============================================================
    //  扫码登录
    // ============================================================

    private var sessionPrimed = false

    /** 当前扫码登录会话的 chainId（与官方网页端一致，同一会话复用）。 */
    private var loginChainId: String? = null

    /** 先访问官方首页，获得 os=pc 等会话 Cookie，模拟真实网页端登录环境。 */
    private fun primeSession() {
        if (sessionPrimed) return
        runCatching {
            val request = HttpRequest.newBuilder(URI.create("$BASE/"))
                .headers(*commonHeaders.toHeaderArray())
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding())
        }
        sessionPrimed = true
    }

    private fun newLoginChainId(): String {
        val randomNum = java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000)
        return "v1_unknown-$randomNum" + "_web_login_" + System.currentTimeMillis()
    }

    /** 官方网页端扫码登录附带的环境头。 */
    private fun loginHeaders(): Map<String, String> = mapOf(
        "x-loginmethod" to "QrCode",
        "x-login-chain-id" to (loginChainId ?: newLoginChainId()),
        "x-os" to "web",
        "nm-gcore-status" to "1",
    )

    /** 获取二维码 key（unikey）。 */
    fun qrKey(): String {
        primeSession()
        loginChainId = newLoginChainId()
        val raw = postWeapi("/weapi/login/qrcode/unikey", linkedMapOf("type" to 3), loginHeaders())
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
        val raw = postWeapi("/weapi/login/qrcode/client/login", linkedMapOf("key" to key, "type" to 3), loginHeaders())
        return parseQrCheck(raw)
    }

    // ============================================================
    //  验证码登录（扫码登录被账号风控拦截时的备选方案）
    // ============================================================

    /** 向指定手机号发送登录验证码（参数与网易云官方网页登录页一致）。 */
    fun sendSmsCode(phone: String): String {
        val raw = postWeapi(
            "/weapi/sms/captcha/sent",
            linkedMapOf(
                "secrete" to "music_user_login",
                "cellphone" to phone,
                "countrycode" to "86",
            ),
        )
        val resp = NeteaseJson.gson.fromJson(raw, RawCode::class.java)
        if (resp.code != 200) {
            val msg = NeteaseJson.gson.fromJson(raw, RawQrCheckResponse::class.java).message
            throw IllegalStateException(smsRiskMessage(resp.code, msg))
        }
        return "验证码已发送，请注意查收"
    }

    /** 使用官方网页登录后复制的 Cookie 登录（账号触发安全验证时的兜底方案）。 */
    fun loginWithCookie(cookieHeader: String): NeteaseAccount {
        cookieManager.cookieStore.removeAll()
        val uri = URI.create("https://music.163.com")
        cookieHeader.split(';').forEach { part ->
            val kv = part.trim().split('=', limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) {
                val c = HttpCookie(kv[0].trim(), kv[1].trim())
                c.domain = ".music.163.com"
                c.path = "/"
                cookieManager.cookieStore.add(uri, c)
            }
        }
        return account() ?: throw IllegalStateException("Cookie 无效或已过期，请重新登录后复制")
    }

    /** 恢复上次保存的登录 Cookie；失效时抛出异常。 */
    fun restoreSession(cookieHeader: String): NeteaseAccount = loginWithCookie(cookieHeader)

    /** 使用手机号 + 短信验证码登录，成功后返回账号信息。 */
    fun loginWithSms(phone: String, captcha: String): NeteaseAccount {
        val raw = postWeapi(
            "/weapi/w/login/cellphone",
            linkedMapOf(
                "type" to "1",
                "https" to "true",
                "phone" to phone,
                "countrycode" to "86",
                "captcha" to captcha,
                "remember" to "true",
            ),
        )
        val acc = parseAccount(raw)
        if (acc != null) return acc
        val code = NeteaseJson.gson.fromJson(raw, RawCode::class.java).code
        val msg = NeteaseJson.gson.fromJson(raw, RawQrCheckResponse::class.java).message
        throw IllegalStateException("验证码登录失败（code=$code${msg?.let { "，$it" } ?: ""}）")
    }

    /** POST 到 weapi 接口（params + encSecKey 表单）。 */
    private fun postWeapi(path: String, data: Map<String, Any?>, extraHeaders: Map<String, String> = emptyMap()): String {
        val builder = HttpRequest.newBuilder(URI.create("$BASE$path"))
            .headers(*commonHeaders.toHeaderArray())
            .header("Content-Type", "application/x-www-form-urlencoded")
        if (extraHeaders.isNotEmpty()) {
            builder.headers(*extraHeaders.toHeaderArray())
        }
        val request = builder
            .POST(HttpRequest.BodyPublishers.ofString(weapiBody(data)))
            .build()
        return sendJson(request)
    }

    /** 网易云风控返回码（验证码/扫码在当前网络不可用）时的友好提示。 */
    private fun smsRiskMessage(code: Int, msg: String?): String =
        if (code == -12 || code == -460 || code == 460) {
            "当前网络触发网易云安全验证，验证码登录不可用，请使用「浏览器登录」"
        } else {
            "code=$code${msg?.let { "，$it" } ?: ""}"
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

    /** 导出当前登录 Cookie，供配置文件保存。 */
    fun exportSessionCookie(): String? {
        val cookies = cookieManager.cookieStore
            .get(URI.create(BASE))
            .filter { !it.hasExpired() && it.name.isNotBlank() && it.value.isNotBlank() }
            .distinctBy { it.name }
        if (cookies.isEmpty()) return null
        return cookies.joinToString("; ") { "${it.name}=${it.value}" }
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
            8821 -> QrLoginState.RISK
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
            isTrial = item.freeTrialInfo != null,
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
