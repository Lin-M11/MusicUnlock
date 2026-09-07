package musicunlock.qq

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration

/**
 * QQ 音乐网页端接口客户端。
 *
 * 登录链路与 y.qq.com 网页版一致：
 * - 生成二维码（ssl.ptlogin2.qq.com/ptqrshow，AppId 716027609）
 * - 轮询扫码结果（ptqrlogin）→ 确认后按返回的 check_sig 地址换取 p_skey
 * - 通过 graph.qq.com OAuth 授权获取 code
 * - 用 code 调 musicu.fcg 的 QQConnectLogin/QQLogin 换取 QQ 音乐登录票据
 *   （musicid + musickey，等价于网页 Cookie 中的 uin + qqmusic_key/qm_keyst）
 *
 * 歌单、歌单详情与播放地址均走官方 musicu.fcg 网关；下载播放地址在账号
 * 权限内可用（无版权 / VIP 专属 / 需单独购买的数字专辑会返回明确原因）。
 */
object QqMusicApi {

    private const val MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val VERSION_CODE = 13020508

    /** 「我喜欢的音乐」封面占位图。 */
    private const val FAVORITE_COVER = "https://y.gtimg.cn/mediastyle/global/img/cover_like.png"

    /** QQ 登录会话 Cookie（qrsig / p_skey / skey 等），按名存值，仅进程内保留。 */
    private val cookieJar = LinkedHashMap<String, String>()

    /** 当前登录凭证：musicid（QQ 号）+ musickey（qqmusic_key/qm_keyst 等价票据）。 */
    @Volatile
    private var credential: QqCredential? = null

    private var qrsig: String? = null
    private var qrLoginUrl: String? = null

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private val random = SecureRandom()

    // ============================================================
    //  二维码登录：二维码 → 轮询 → 完成登录
    // ============================================================

    /** 获取登录二维码 PNG；失败抛出带原因的异常。 */
    fun qrImage(): ByteArray {
        clearSession()
        val t = "0.${random.nextInt(1_000_000_000)}"
        val url = "https://ssl.ptlogin2.qq.com/ptqrshow?appid=716027609&e=2&l=M&s=3&d=72&v=4&t=$t&daid=383&pt_3rd_aid=100497308"
        val resp = getRaw(url, mapOf("Referer" to "https://xui.ptlogin2.qq.com/"))
        checkStatus(resp, "获取登录二维码失败")
        mergeCookies(resp.setCookies)
        val sig = cookieJar["qrsig"]
            ?: throw IllegalStateException("获取登录二维码失败：未返回 qrsig")
        qrsig = sig
        return resp.body
    }

    /** 轮询扫码状态；返回 SUCCESS 后需调用 [finishQrLogin] 完成登录。 */
    fun pollQr(): QqQrResult {
        val sig = qrsig ?: throw IllegalStateException("二维码已失效，请刷新")
        val ts = System.currentTimeMillis()
        val query = buildQuery(
            "u1" to "https://graph.qq.com/oauth2.0/login_jump",
            "ptqrtoken" to hash33(sig).toString(),
            "ptredirect" to "0",
            "h" to "1",
            "t" to "1",
            "g" to "1",
            "from_ui" to "1",
            "ptlang" to "2052",
            "action" to "0-0-$ts",
            "js_ver" to "20102616",
            "js_type" to "1",
            "login_sig" to "",
            "pt_uistyle" to "40",
            "aid" to "716027609",
            "daid" to "383",
            "pt_3rd_aid" to "100497308",
        )
        val resp = getRaw(
            "https://ssl.ptlogin2.qq.com/ptqrlogin?$query",
            mapOf("Referer" to "https://xui.ptlogin2.qq.com/", "Cookie" to "qrsig=$sig"),
        )
        checkStatus(resp, "登录状态查询失败")
        mergeCookies(resp.setCookies)
        val parsed = parseQrCallback(String(resp.body, Charsets.UTF_8))
        return when {
            parsed.code == 0 -> {
                if (parsed.url.isNullOrBlank()) {
                    QqQrResult(QqLoginState.ERROR, "登录成功但未返回跳转地址，请重试")
                } else {
                    qrLoginUrl = parsed.url
                    QqQrResult(QqLoginState.SUCCESS, null)
                }
            }
            parsed.code == 65 -> QqQrResult(QqLoginState.EXPIRED, "二维码已失效，请刷新")
            parsed.code == 66 -> QqQrResult(QqLoginState.WAIT, "等待扫码")
            parsed.code == 67 -> QqQrResult(QqLoginState.SCANNED, "已扫码，请在手机上确认登录")
            else -> QqQrResult(QqLoginState.WAIT, null)
        }
    }

    /** 扫码确认后完成授权，返回登录账号。 */
    fun finishQrLogin(): QqAccount {
        val loginUrl = qrLoginUrl
            ?: throw IllegalStateException("缺少登录跳转地址，请重新扫码")
        qrLoginUrl = null

        // Step 1：访问 ptqrlogin 返回的 check_sig 地址，换取 p_skey
        val checkResp = getRaw(toAbsoluteHttps(loginUrl), mapOf("Referer" to "https://xui.ptlogin2.qq.com/"))
        checkStatus(checkResp, "QQ 登录校验失败")
        mergeCookies(checkResp.setCookies)
        val pSkey = cookieJar["p_skey"]
            ?: throw IllegalStateException("QQ 登录失败：未获取到 p_skey（账号可能触发安全验证，请改用浏览器登录后粘贴 Cookie）")
        val gtk = hash33(pSkey, 5381)

        // Step 2：OAuth 授权，从跳转地址中取 code
        val form = buildQuery(
            "response_type" to "code",
            "client_id" to "100497308",
            "redirect_uri" to "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
            "scope" to "get_user_info,get_app_friends",
            "state" to "state",
            "switch" to "",
            "from_ptlogin" to "1",
            "src" to "1",
            "update_auth" to "1",
            "openapi" to "1010_1030",
            "g_tk" to gtk.toString(),
            "auth_time" to System.currentTimeMillis().toString(),
            "ui" to guid(),
        )
        val authResp = postRaw(
            "https://graph.qq.com/oauth2.0/authorize",
            form,
            mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Referer" to "https://xui.ptlogin2.qq.com/",
                "Cookie" to cookieHeader(),
            ),
        )
        mergeCookies(authResp.setCookies)
        val location = authResp.location
            ?: throw IllegalStateException("QQ 登录失败：授权未跳转，请重新扫码或改用浏览器登录粘贴 Cookie")
        val code = extractCode(location)
            ?: throw IllegalStateException("QQ 登录失败：未获取到授权 code（${location.take(160)}）")

        // Step 3：用 code 换 QQ 音乐登录票据
        val loginBody = jsonBody(
            "comm" to mapOf(
                "cv" to VERSION_CODE,
                "v" to VERSION_CODE,
                "ct" to "11",
                "tmeAppID" to "qqmusic",
                "format" to "json",
                "inCharset" to "utf-8",
                "outCharset" to "utf-8",
                "uid" to "0",
                "tmeLoginType" to "2",
            ),
            "QQConnectLogin.LoginServer" to mapOf(
                "module" to "QQConnectLogin.LoginServer",
                "method" to "QQLogin",
                "param" to mapOf("code" to code),
            ),
        )
        val loginResp = postRaw(
            MUSICU,
            loginBody,
            mapOf(
                "Content-Type" to "application/json;charset=UTF-8",
                "Referer" to "https://y.qq.com/",
                "Origin" to "https://y.qq.com",
                "Cookie" to cookieHeader(),
            ),
        )
        checkStatus(loginResp, "QQ 音乐登录失败")
        val parsed = parseQQLoginResult(String(loginResp.body, Charsets.UTF_8))
            ?: throw IllegalStateException("QQ 音乐登录失败：未返回登录票据")
        val cred = QqCredential(
            musicid = parsed.musicid,
            musickey = parsed.musickey,
            nickname = parsed.nickname,
            avatarUrl = parsed.avatarUrl,
            loginType = if (parsed.musickey.startsWith("W_X")) 1 else 2,
        )
        credential = cred
        ensureProfile(cred)
        return account()
            ?: throw IllegalStateException("QQ 音乐登录失败：无法读取账号信息")
    }

    // ============================================================
    //  Cookie 登录（浏览器登录 y.qq.com 后复制完整 Cookie）
    // ============================================================

    /** 使用网页登录 Cookie 登录；需包含播放票据 qqmusic_key / qm_keyst。 */
    fun loginWithCookie(cookieHeader: String): QqAccount {
        if (cookieHeader.isBlank()) throw IllegalStateException("请先复制 Cookie")
        val jar = parseCookieHeader(cookieHeader)
        val rawUin = if (jar["login_type"] == "2") {
            jar["wxuin"] ?: jar["uin"]
        } else {
            jar["uin"] ?: jar["qqmusic_uin"] ?: jar["wxuin"] ?: jar["p_uin"]
        }
        val uin = rawUin?.filter { it.isDigit() }
            ?: throw IllegalStateException("Cookie 中缺少 uin，请登录 https://y.qq.com 后完整复制 Cookie")
        val key = jar["qm_keyst"] ?: jar["qqmusic_key"] ?: jar["music_key"]
            ?: throw IllegalStateException("Cookie 中缺少播放票据 qqmusic_key / qm_keyst，请在 https://y.qq.com 登录后重新完整复制 Cookie")

        clearSession()
        jar.forEach { (k, v) -> cookieJar[k] = v }
        val loginType = when {
            jar["login_type"] == "2" -> 1
            key.startsWith("W_X") -> 1
            else -> 2
        }
        credential = QqCredential(uin, key, null, null, loginType)
        return try {
            ensureProfile(credential!!)
            myPlaylists()
            account() ?: throw IllegalStateException("Cookie 无效或已过期，请重新登录后复制")
        } catch (e: QqAuthExpiredException) {
            throw IllegalStateException("Cookie 无效或已过期，请重新登录后复制")
        }
    }

    // ============================================================
    //  账号 / 歌单 / 歌曲 / 播放地址
    // ============================================================

    /** 当前登录账号；未登录返回 null。 */
    fun account(): QqAccount? {
        val cred = credential ?: return null
        val nick = cred.nickname?.takeIf { it.isNotBlank() }
            ?: "QQ 用户 ${cred.musicid}"
        return QqAccount(
            nickname = nick,
            avatarUrl = cred.avatarUrl?.takeIf { it.isNotBlank() },
            musicid = cred.musicid,
        )
    }

    /** 拉取用户创建的歌单（含「我喜欢的音乐」，若官方返回）。 */
    fun myPlaylists(): List<QqPlaylist> {
        val cred = requireCredential()
        val body = moduleBody(
            "music.musicasset.PlaylistBaseRead",
            "GetPlaylistByUin",
            mapOf("uin" to cred.musicid),
        )
        val raw = postMusicu(body, cred)
        val playlists = parseMyPlaylists(raw)
        if (playlists.none { it.isFavorite }) {
            // 官方未把「我喜欢的音乐」列入创建歌单时，手动补充一项
            val count = favoriteCount(cred)
            return listOf(
                QqPlaylist(
                    id = 201,
                    dirId = 201,
                    name = "我喜欢的音乐",
                    coverUrl = FAVORITE_COVER,
                    trackCount = count ?: 0,
                ),
            ) + playlists
        }
        return playlists
    }

    /** 拉取单个歌单内的全部歌曲（自动翻页）。 */
    fun playlistSongs(playlist: QqPlaylist): List<QqSong> {
        val cred = requireCredential()
        val euin = if (playlist.isFavorite) {
            encryptUin(cred) ?: throw IllegalStateException("无法读取「我喜欢的音乐」（获取账号标识失败）")
        } else {
            null
        }
        val songs = mutableListOf<QqSong>()
        var begin = 0
        val pageSize = 100
        while (true) {
            val param = linkedMapOf<String, Any?>(
                "song_begin" to begin,
                "song_num" to pageSize,
                "onlysonglist" to true,
                "userinfo" to true,
            )
            if (playlist.isFavorite) {
                param["disstid"] = 0
                param["dirid"] = 201
                if (euin != null) param["enc_host_uin"] = euin
            } else {
                param["disstid"] = playlist.id
                param["dirid"] = 0
            }
            val body = moduleBody("music.srfDissInfo.DissInfo", "CgiGetDiss", param)
            val raw = postMusicu(body, cred)
            val (page, total) = parseSonglistPage(raw)
            if (page.isEmpty()) break
            songs.addAll(page)
            val expected = total
            begin += page.size
            if (expected > 0 && songs.size >= expected) break
            if (page.size < pageSize) break
            if (begin > 5000) break
        }
        return songs
    }

    /** 按音质请求播放地址；返回 null 表示该音质不可用。 */
    fun songUrl(song: QqSong, quality: Int): QqSongUrlResult? {
        val cred = credential ?: return null
        val prefix = if (quality >= 320) "M800" else "M500"
        val filename = "$prefix${song.mid}${song.mid}.mp3"
        val param = linkedMapOf<String, Any?>(
            "filename" to arrayOf(filename),
            "guid" to guid(),
            "songmid" to arrayOf(song.mid),
            "songtype" to arrayOf(0),
        )
        val body = moduleBody("music.vkey.GetVkey.UrlGetVkey", "UrlGetVkey", param, ct = "19")
        val raw = postMusicu(body, cred)
        return parseSongUrl(raw)
    }

    /** 下载 URL 到本地文件（覆盖已存在文件）。 */
    fun download(url: String, target: Path) {
        val request = HttpRequest.newBuilder(URI.create(url))
            .headers(
                "User-Agent", USER_AGENT,
                "Referer", "https://y.qq.com/",
                "Accept", "*/*",
            )
            .GET()
            .timeout(Duration.ofMinutes(10))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target))
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("下载失败（HTTP ${response.statusCode()}）")
        }
    }

    /** 下载字节（用于封面等小资源）。 */
    fun downloadBytes(url: String): ByteArray? {
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .headers("User-Agent", USER_AGENT, "Referer", "https://y.qq.com/")
                .GET()
                .timeout(Duration.ofSeconds(20))
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() in 200..299 && response.body().isNotEmpty()) response.body() else null
        } catch (e: Exception) {
            null
        }
    }

    /** 退出登录：清空会话 Cookie 与登录凭证。 */
    fun logout() {
        clearSession()
    }

    // ============================================================
    //  解析（独立成函数便于测试；使用官方真实返回结构的样本）
    // ============================================================

    internal class QrCallback(val code: Int, val url: String?, val message: String?)

    private val callbackPattern = Regex("ptuiCB\\((.*?)\\)", setOf(RegexOption.DOT_MATCHES_ALL))
    private val quotedArgPattern = Regex("'((?:\\\\.|[^'\\\\])*)'")

    internal fun parseQrCallback(text: String): QrCallback {
        val inside = callbackPattern.find(text)?.groupValues?.get(1)
            ?: return QrCallback(-1, null, null)
        val args = quotedArgPattern.findAll(inside).map { it.groupValues[1] }.toList()
        val code = args.getOrNull(0)?.toIntOrNull() ?: -1
        val url = args.getOrNull(2)?.takeIf { it.isNotBlank() }
        val message = args.getOrNull(4)?.takeIf { it.isNotBlank() }
        return QrCallback(code, url, message)
    }

    internal class QqLoginParsed(
        val musicid: String,
        val musickey: String,
        val nickname: String?,
        val avatarUrl: String?,
    )

    internal fun parseQQLoginResult(raw: String): QqLoginParsed? {
        val root = parseObject(raw)
        val module = root.module("QQConnectLogin.LoginServer") ?: return null
        if (module.code() != 0) throw IllegalStateException(loginError(module))
        val data = module.obj("data") ?: return null
        val musicid = data.str("musicid") ?: data.str("openId") ?: data.str("openid")
            ?: throw IllegalStateException("QQ 音乐登录失败：未返回 musicid")
        val musickey = data.str("musickey")
            ?: throw IllegalStateException("QQ 音乐登录失败：未返回 musickey")
        val nickname = data.str("nickname") ?: data.str("nick")
        val avatar = data.str("logo") ?: data.str("avatar")
        return QqLoginParsed(musicid, musickey, nickname, avatar)
    }

    internal fun parseMyPlaylists(raw: String): List<QqPlaylist> {
        val root = parseObject(raw)
        val module = root.module("music.musicasset.PlaylistBaseRead") ?: throw IllegalStateException("获取歌单失败：未返回数据")
        throwIfAuthExpired(module)
        val data = module.obj("data") ?: return emptyList()
        val list = data.arr("v_playlist") ?: return emptyList()
        return list.mapNotNull { item ->
            val o = item.asJsonObjectOrNull() ?: return@mapNotNull null
            val name = o.str("dirName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val dirId = o.lng("dirId")
            val tid = o.lng("tid")
            val id = if (tid > 0) tid else if (dirId > 0) dirId else return@mapNotNull null
            QqPlaylist(
                id = id,
                dirId = dirId,
                name = name,
                coverUrl = o.str("picUrl")?.takeIf { it.isNotBlank() },
                trackCount = o.int("songNum").coerceAtLeast(0),
            )
        }
    }

    /** 解析一页歌单歌曲；返回（歌曲列表，歌单总曲目数）。 */
    internal fun parseSonglistPage(raw: String): Pair<List<QqSong>, Int> {
        val root = parseObject(raw)
        val module = root.module("music.srfDissInfo.DissInfo") ?: throw IllegalStateException("获取歌单详情失败：未返回数据")
        throwIfAuthExpired(module)
        val data = module.obj("data") ?: return emptyList<QqSong>() to 0
        val total = (data.int("total_song_num")).coerceAtLeast(0)
        val list = data.arr("songlist") ?: return emptyList<QqSong>() to total
        val songs = list.mapNotNull { item ->
            val o = item.asJsonObjectOrNull() ?: return@mapNotNull null
            parseSongObject(o)
        }
        return songs to total
    }

    /** 解析 vkey 响应；无 purl 时 reason 给出明确原因。 */
    internal fun parseSongUrl(raw: String): QqSongUrlResult {
        val root = parseObject(raw)
        val module = root.module("music.vkey.GetVkey.UrlGetVkey")
            ?: return QqSongUrlResult(null, "获取播放地址失败：未返回数据")
        throwIfAuthExpired(module)
        val data = module.obj("data") ?: return QqSongUrlResult(null, "获取播放地址失败：未返回数据")
        val info = data.arr("midurlinfo")?.firstOrNull()?.asJsonObjectOrNull()
            ?: return QqSongUrlResult(null, "获取播放地址失败：未返回数据")
        val purl = info.str("purl")?.takeIf { it.isNotBlank() }
        if (purl != null) {
            return QqSongUrlResult("https://isure.stream.qqmusic.qq.com/$purl", null)
        }
        return QqSongUrlResult(null, unavailableReason(info))
    }

    internal fun parseHomepageProfile(raw: String): QqProfileParsed? {
        val root = parseObject(raw)
        val data = root.obj("data") ?: return null
        val creator = data.obj("creator") ?: return null
        val favoriteCount = data.arr("mymusic")?.firstOrNull()?.asJsonObjectOrNull()?.int("num0")
        return QqProfileParsed(
            nickname = creator.str("nick"),
            avatarUrl = creator.str("headpic"),
            encryptUin = creator.str("encrypt_uin"),
            favoriteCount = favoriteCount,
        )
    }

    internal class QqProfileParsed(
        val nickname: String?,
        val avatarUrl: String?,
        val encryptUin: String?,
        /** 「我喜欢的音乐」歌曲数（主页 mymusic 返回）。 */
        val favoriteCount: Int?,
    )

    internal fun parseSongObject(o: JsonObject): QqSong? {
        val mid = o.str("mid")?.takeIf { it.isNotBlank() } ?: return null
        val name = o.str("name") ?: o.str("title")
        if (name.isNullOrBlank()) return null
        val file = o.obj("file")
        val album = o.obj("album")
        return QqSong(
            mid = mid,
            mediaMid = file?.str("media_mid")?.takeIf { it.isNotBlank() },
            name = name,
            artists = (o.arr("singer") ?: JsonArray()).mapNotNull { singer ->
                (singer.asJsonObjectOrNull())?.str("name")?.takeIf { it.isNotBlank() }
            },
            albumName = album?.str("name")?.takeIf { it.isNotBlank() },
            albumMid = album?.str("mid")?.takeIf { it.isNotBlank() },
            size320 = file?.lng("size_320mp3") ?: 0,
            size128 = file?.lng("size_128mp3") ?: 0,
        )
    }

    /** 登录后尽力读取主页资料（昵称/头像/加密 uin/我喜欢的数量），失败不影响登录。 */
    private fun ensureProfile(cred: QqCredential) {
        if (profileLoaded) return
        profileLoaded = true
        try {
            val query = buildQuery("ct" to "20", "cv" to "4747474", "cid" to "205360838", "userid" to cred.musicid, "format" to "json")
            val resp = getRaw(
                "https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg?$query",
                mapOf("Referer" to "https://y.qq.com/", "Cookie" to cookieHeader()),
            )
            if (resp.status in 200..299) {
                val profile = parseHomepageProfile(String(resp.body, Charsets.UTF_8))
                if (profile != null) {
                    profile.nickname?.takeIf { it.isNotBlank() }?.let { cred.nickname = it }
                    profile.avatarUrl?.takeIf { it.isNotBlank() }?.let { cred.avatarUrl = it }
                    profile.encryptUin?.takeIf { it.isNotBlank() }?.let { encryptUinCache = it }
                    profile.favoriteCount?.let { favoriteCountCache = it }
                }
            }
        } catch (e: Exception) {
            // 主页资料为可选信息，忽略失败
        }
    }

    @Volatile
    private var profileLoaded = false

    @Volatile
    private var encryptUinCache: String? = null

    @Volatile
    private var favoriteCountCache: Int? = null

    private fun encryptUin(cred: QqCredential): String? {
        ensureProfile(cred)
        return encryptUinCache
    }

    private fun favoriteCount(cred: QqCredential): Int? {
        ensureProfile(cred)
        return favoriteCountCache
    }

    // ============================================================
    //  内部实现
    // ============================================================

    private class QqCredential(
        val musicid: String,
        val musickey: String,
        var nickname: String?,
        var avatarUrl: String?,
        val loginType: Int,
    )

    private class HttpResponseData(
        val status: Int,
        val body: ByteArray,
        val setCookies: List<String>,
        val location: String?,
    )

    private fun requireCredential(): QqCredential =
        credential ?: throw IllegalStateException("未登录")

    private fun clearSession() {
        cookieJar.clear()
        credential = null
        qrsig = null
        qrLoginUrl = null
    }

    /** 调用 musicu.fcg；自动带登录凭证 Cookie 与 comm 字段。 */
    private fun postMusicu(body: String, cred: QqCredential): String {
        val resp = postRaw(
            MUSICU,
            body,
            mapOf(
                "Content-Type" to "application/json;charset=UTF-8",
                "Referer" to "https://y.qq.com/",
                "Origin" to "https://y.qq.com",
                "Cookie" to musicuCookie(cred),
            ),
        )
        checkStatus(resp, "QQ 音乐请求失败")
        return String(resp.body, Charsets.UTF_8)
    }

    private fun musicuCookie(cred: QqCredential): String {
        val base = "uin=${cred.musicid}; qqmusic_key=${cred.musickey}; qm_keyst=${cred.musickey}; tmeLoginType=${cred.loginType};"
        return if (cred.loginType == 1) base + " wxuin=${cred.musicid};" else base
    }

    private fun moduleBody(module: String, method: String, param: Map<String, Any?>, ct: String = "11"): String {
        val cred = credential
        val comm = linkedMapOf<String, Any?>(
            "cv" to VERSION_CODE,
            "v" to VERSION_CODE,
            "ct" to ct,
            "tmeAppID" to "qqmusic",
            "format" to "json",
            "inCharset" to "utf-8",
            "outCharset" to "utf-8",
            "uid" to (cred?.musicid ?: "0"),
        )
        if (cred != null) {
            comm["qq"] = cred.musicid
            comm["authst"] = cred.musickey
            comm["tmeLoginType"] = if (cred.loginType == 1) "1" else "2"
        }
        return jsonBody(
            "comm" to comm,
            module to mapOf(
                "module" to module,
                "method" to method,
                "param" to param,
            ),
        )
    }

    private fun checkStatus(resp: HttpResponseData, prefix: String) {
        if (resp.status !in 200..299) {
            throw IllegalStateException("$prefix（HTTP ${resp.status}）")
        }
    }

    private fun getRaw(url: String, headers: Map<String, String>): HttpResponseData {
        val builder = HttpRequest.newBuilder(URI.create(url)).GET().timeout(Duration.ofSeconds(20))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(builder.build())
    }

    private fun postRaw(url: String, body: String, headers: Map<String, String>): HttpResponseData {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(25))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return send(builder.build())
    }

    private fun send(request: HttpRequest): HttpResponseData {
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return HttpResponseData(
            status = response.statusCode(),
            body = response.body(),
            setCookies = response.headers().allValues("Set-Cookie"),
            location = response.headers().firstValue("Location").orElse(null),
        )
    }

    // ---- cookie / 工具 ----

    private fun mergeCookies(setCookies: List<String>) {
        for (header in setCookies) {
            val raw = header.substringBefore(';')
            val eq = raw.indexOf('=')
            if (eq <= 0) continue
            val name = raw.substring(0, eq).trim()
            val value = raw.substring(eq + 1).trim()
            val old = cookieJar[name]
            if (value.isNotEmpty() || old == null) {
                cookieJar[name] = value
            }
        }
    }

    private fun cookieHeader(): String =
        cookieJar.entries.joinToString("; ") { (k, v) -> "$k=$v" }

    internal fun parseCookieHeader(header: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        header.split(';').forEach { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
            }
        }
        return out
    }

    /** QQ 网页端 hash33：逐字符累加后保留低 31 位，用于 ptqrtoken 与 g_tk。 */
    internal fun hash33(str: String, seed: Int = 0): Int {
        var hash = seed
        for (ch in str) {
            hash += (hash shl 5) + ch.code
            hash = hash and 0x7fffffff
        }
        return hash
    }

    private fun guid(): String {
        val hex = "0123456789abcdef"
        return buildString(32) { repeat(32) { append(hex[random.nextInt(16)]) } }
    }

    private fun extractCode(location: String): String? {
        val marker = "code="
        val start = location.indexOf(marker)
        if (start < 0) return null
        var end = start + marker.length
        while (end < location.length && location[end] != '&' && location[end] != '#') end++
        val code = location.substring(start + marker.length, end)
        return code.takeIf { it.isNotBlank() }
    }

    private fun toAbsoluteHttps(url: String): String = when {
        url.startsWith("https://") || url.startsWith("http://") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://ssl.ptlogin2.graph.qq.com$url"
        else -> "https://ssl.ptlogin2.graph.qq.com/$url"
    }

    private fun buildQuery(vararg pairs: Pair<String, Any?>): String =
        pairs.mapNotNull { (k, v) ->
            if (v == null) null else "$k=${URLEncoder.encode(v.toString(), Charsets.UTF_8)}"
        }.joinToString("&")

    private fun jsonBody(vararg pairs: Pair<String, Any?>): String {
        val sb = StringBuilder("{")
        pairs.forEachIndexed { index, (key, value) ->
            if (index > 0) sb.append(',')
            sb.append('"').append(key).append('"').append(':')
            appendJsonValue(sb, value)
        }
        sb.append('}')
        return sb.toString()
    }

    private fun appendJsonValue(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.append('"').append(escapeJson(value)).append('"')
            is Number, is Boolean -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                value.forEach { (k, v) ->
                    if (!first) sb.append(',')
                    first = false
                    sb.append('"').append(escapeJson(k.toString())).append('"').append(':')
                    appendJsonValue(sb, v)
                }
                sb.append('}')
            }
            is Array<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    appendJsonValue(sb, v)
                }
                sb.append(']')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    appendJsonValue(sb, v)
                }
                sb.append(']')
            }
            else -> sb.append('"').append(escapeJson(value.toString())).append('"')
        }
    }

    private fun escapeJson(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun unavailableReason(info: JsonObject): String {
        val uiAlert = info.int("uiAlert")
        val pneedbuy = info.int("pneedbuy")
        val result = info.int("result")
        return when {
            uiAlert == 41 || uiAlert == 42 -> "VIP 专享歌曲，当前账号无会员权限，已跳过"
            pneedbuy == 1 -> "数字专辑/单曲，需在 QQ 音乐内购买后下载"
            result == 104003 -> "播放受限（登录态或账号权限不足），已跳过"
            info.str("tips")?.takeIf { it.isNotBlank() } != null ->
                "无法获取播放地址（${info.str("tips")}），已跳过"
            else -> "无法获取播放地址（无版权或当前账号无权限），已跳过"
        }
    }

    /** 模块返回登录失效码时抛出明确异常。 */
    private fun throwIfAuthExpired(module: JsonObject) {
        val code = module.code()
        if (code in LOGIN_EXPIRED_CODES) {
            throw QqAuthExpiredException("QQ 登录已失效，请退出后重新登录")
        }
    }

    private val LOGIN_EXPIRED_CODES = setOf(1000, 104400, 104401)

    private fun loginError(module: JsonObject): String {
        val message = module.str("message") ?: module.str("msg")
        return "QQ 音乐登录失败（code=${module.code()}${message?.let { "，$it" } ?: ""}）"
    }

    // ---- JsonObject 便捷访问 ----

    private fun parseObject(raw: String): JsonObject =
        JsonParser.parseString(raw).asJsonObject

    private fun JsonObject.module(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.str(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int =
        get(name)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

    private fun JsonObject.lng(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private fun JsonObject.code(): Int = get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null
}

/** QQ 登录态失效异常（区别于普通请求失败）。 */
class QqAuthExpiredException(message: String) : IllegalStateException(message)
