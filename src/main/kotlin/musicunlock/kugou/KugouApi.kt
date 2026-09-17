package musicunlock.kugou

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import musicunlock.online.MusicAccount
import musicunlock.online.MusicLyrics
import musicunlock.online.MusicSearchResult
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicPlaylist
import musicunlock.online.MusicSong
import musicunlock.online.OnlineMusicProvider
import musicunlock.online.OnlineQrResult
import musicunlock.online.OnlineQrState
import musicunlock.online.PlaybackSource
import java.math.BigInteger
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 酷狗官方二维码 / 网页接口客户端。
 *
 * 请求签名、设备注册、用户歌单、歌单歌曲和播放地址都封装在这里；
 * UI 仅通过统一的 [OnlineMusicProvider] 使用。
 */
object KugouApi : OnlineMusicProvider {

    override val platform = MusicPlatform.KUGOU

    private const val APP_ID = 1005
    private const val CLIENT_VERSION = 20489
    private const val SRC_APP_ID = 2919
    private const val ANDROID_SALT = "OIlwieks28dk2k092lksi2UIkp"
    private const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val SIGN_KEY_SALT = "57ae12eb6890223e355ccfcb74edf70d"
    private const val USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"
    private const val PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB"

    private data class Session(
        val userId: String,
        val token: String,
        var dfid: String?,
        val guid: String,
        val mid: String,
        val nickname: String?,
        val avatarUrl: String?,
    )

    @Volatile
    private var session: Session? = null
    private val cookieJar = LinkedHashMap<String, String>()

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // ============================================================
    // 登录
    // ============================================================

    /** 获取二维码 key。 */
    fun qrKey(): String {
        val params = linkedMapOf(
            "appid" to "1014",
            "type" to "1",
            "plat" to "4",
            "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=$APP_ID&",
            "srcappid" to SRC_APP_ID.toString(),
        )
        val root = signedJson(
            baseUrl = "https://login-user.kugou.com",
            path = "/v2/qrcode",
            method = "GET",
            customParams = params,
            body = null,
            encryptType = EncryptType.WEB,
        )
        checkStatus(root, "获取登录二维码失败")
        return root.obj("data")?.string("qrcode")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("获取登录二维码失败：未返回 qrcode")
    }

    /** 轮询二维码状态；成功时保存 token/userid。 */
    fun qrCheck(key: String): OnlineQrResult {
        val root = signedJson(
            baseUrl = "https://login-user.kugou.com",
            path = "/v2/get_userinfo_qrcode",
            method = "GET",
            customParams = linkedMapOf(
                "plat" to "4",
                "appid" to APP_ID.toString(),
                "srcappid" to SRC_APP_ID.toString(),
                "qrcode" to key,
                "dev" to (currentDevice().dfid ?: ""),
            ),
            body = null,
            encryptType = EncryptType.WEB,
        )
        val status = root.obj("data")?.int("status") ?: 1
        return when (status) {
            0 -> OnlineQrResult(OnlineQrState.EXPIRED, "二维码已失效，请刷新")
            1 -> OnlineQrResult(OnlineQrState.WAIT, "等待扫码")
            2 -> OnlineQrResult(OnlineQrState.SCANNED, "已扫码，请在手机上确认登录")
            4 -> {
                val data = root.obj("data") ?: return OnlineQrResult(OnlineQrState.ERROR, "登录成功但未返回账号信息")
                val userId = data.string("userid") ?: data.string("user_id")
                val token = data.string("token")
                if (userId.isNullOrBlank() || token.isNullOrBlank()) {
                    return OnlineQrResult(OnlineQrState.ERROR, "登录成功但未返回 token")
                }
                storeLogin(
                    userId = userId,
                    token = token,
                    nickname = data.string("nickname") ?: data.string("user_name"),
                    avatar = data.string("pic") ?: data.string("avatar"),
                )
                OnlineQrResult(OnlineQrState.SUCCESS, "登录成功")
            }
            else -> OnlineQrResult(OnlineQrState.ERROR, root.string("error") ?: "登录状态异常")
        }
    }

    /** 使用浏览器登录 Cookie。 */
    fun loginWithCookie(cookieHeader: String): MusicAccount {
        if (cookieHeader.isBlank()) throw IllegalStateException("请先复制 Cookie")
        val cookies = parseCookieHeader(cookieHeader)
        val userId = firstNonBlank(cookies, "userid", "qqmusic_uin", "uid", "user_id")
            ?: throw IllegalStateException("Cookie 中缺少 userid，请登录 kugou.com 后完整复制 Cookie")
        val token = firstNonBlank(cookies, "token", "kugou_token", "music_key", "qqmusic_key")
            ?: throw IllegalStateException("Cookie 中缺少 token，请登录 kugou.com 后完整复制 Cookie")
        synchronized(cookieJar) {
            cookieJar.clear()
            cookieJar.putAll(cookies)
        }
        storeLogin(
            userId = userId,
            token = token,
            nickname = firstNonBlank(cookies, "nickname", "user_name", "username", "nick")
                ?.let(::decodeCookieValue),
            avatar = firstNonBlank(cookies, "pic", "avatar", "headimg", "head_img")
                ?.let(::decodeCookieValue),
            dfid = firstNonBlank(cookies, "dfid", "kg_dfid"),
            guid = firstNonBlank(cookies, "KUGOU_API_GUID", "kg_mid", "guid"),
            mid = firstNonBlank(cookies, "KUGOU_API_MID", "mid"),
        )
        // 用用户歌单接口检查登录票据。
        playlists()
        return account() ?: throw IllegalStateException("Cookie 无效或已过期，请重新登录后复制")
    }

    override fun restoreSession(cookieHeader: String): MusicAccount = loginWithCookie(cookieHeader)

    override fun exportSessionCookie(): String? {
        val current = session ?: return null
        val snapshot = synchronized(cookieJar) { LinkedHashMap(cookieJar) }
        snapshot["userid"] = current.userId
        snapshot["token"] = current.token
        current.dfid?.let { snapshot["dfid"] = it; snapshot["kg_dfid"] = it }
        snapshot["kg_mid"] = current.guid
        snapshot["KUGOU_API_MID"] = current.mid
        return snapshot.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    override fun logout() {
        session = null
        synchronized(cookieJar) { cookieJar.clear() }
    }

    override fun account(): MusicAccount? = session?.let {
        MusicAccount(
            nickname = it.nickname?.takeIf(String::isNotBlank) ?: "酷狗用户 ${it.userId}",
            avatarUrl = it.avatarUrl,
            userId = it.userId,
        )
    }

    // ============================================================
    // 歌单 / 歌曲 / 播放地址
    // ============================================================

    override fun playlists(): List<MusicPlaylist> {
        val current = requireSession()
        val out = mutableListOf<MusicPlaylist>()
        var page = 1
        val pageSize = 100
        while (page <= 100) {
            val body = json(linkedMapOf(
                "userid" to current.userId,
                "token" to current.token,
                "total_ver" to 979,
                "type" to 2,
                "page" to page,
                "pagesize" to pageSize,
            ))
            val root = signedJson(
                baseUrl = "https://gateway.kugou.com",
                path = "/v7/get_all_list",
                method = "POST",
                customParams = linkedMapOf(
                    "plat" to "1",
                    "userid" to current.userId,
                    "token" to current.token,
                ),
                body = body,
                encryptType = EncryptType.ANDROID,
                xRouter = "cloudlist.service.kugou.com",
            )
            checkStatus(root, "获取酷狗歌单失败")
            val data = root.obj("data") ?: break
            val list = data.array("info") ?: data.array("list") ?: data.array("playlists") ?: break
            if (list.size() == 0) break
            list.mapNotNullTo(out) { parsePlaylist(it.asJsonObject) }
            val total = data.int("total") ?: data.int("count") ?: 0
            if (list.size() < pageSize || (total > 0 && out.size >= total)) break
            page++
        }
        return out.distinctBy { it.id }
    }

    override fun songs(playlist: MusicPlaylist): List<MusicSong> {
        if (session == null || playlist.metadata["public"] == "true") return publicPlaylistSongs(playlist.id)
        val current = requireSession()
        val out = mutableListOf<MusicSong>()
        var page = 1
        val pageSize = 300
        while (page <= 100) {
            val body = json(linkedMapOf(
                "listid" to playlist.id,
                "userid" to current.userId,
                "type" to 0,
                "page" to page,
                "pagesize" to pageSize,
                "area_code" to 1,
                "allplatform" to 1,
                "show_cover" to 1,
                "token" to current.token,
            ))
            val root = signedJson(
                baseUrl = "https://gateway.kugou.com",
                path = "/v4/get_list_all_file_v3",
                method = "POST",
                customParams = emptyMap(),
                body = body,
                encryptType = EncryptType.ANDROID,
                xRouter = "cloudlist.service.kugou.com",
            )
            checkStatus(root, "获取歌单歌曲失败")
            val data = root.obj("data") ?: break
            val list = data.array("info") ?: data.array("songs") ?: data.array("list") ?: break
            if (list.size() == 0) break
            list.mapNotNullTo(out) { parseSong(it.asJsonObject) }
            val total = data.int("total") ?: data.int("count") ?: 0
            if (list.size() < pageSize) break
            if (total > 0 && out.size >= total) break
            page++
        }
        return out.distinctBy { it.metadata["hash"] ?: it.id }
    }

    override fun playback(song: MusicSong): PlaybackSource = playback(song, musicunlock.settings.QualityStrategy.HIGHEST)

    override fun playback(song: MusicSong, quality: musicunlock.settings.QualityStrategy): PlaybackSource {
        val hash = song.metadata["hash"] ?: song.id
        val albumId = song.metadata["albumId"] ?: "0"
        val albumAudioId = song.metadata["albumAudioId"] ?: "0"
        val current = requireSession()
        if (current.dfid.isNullOrBlank()) registerDevice()

        val qualities = when (quality) {
            musicunlock.settings.QualityStrategy.LOSSLESS_FIRST -> listOf("flac", "320", "128")
            musicunlock.settings.QualityStrategy.HIGHEST -> listOf("flac", "320", "128")
            musicunlock.settings.QualityStrategy.MP3_320 -> listOf("320", "128")
            musicunlock.settings.QualityStrategy.BALANCED -> listOf("128", "320")
            musicunlock.settings.QualityStrategy.SMALLEST -> listOf("128", "320", "flac")
        }
        var lastMessage = "获取播放地址失败"
        for (requestedQuality in qualities) {
            repeat(2) { attempt ->
                val currentMid = currentDevice().mid
                val key = md5("$hash$SIGN_KEY_SALT$APP_ID$currentMid${current.userId}")
                val root = runCatching {
                    signedJson(
                        baseUrl = "https://gateway.kugou.com",
                        path = "/v5/url",
                        method = "GET",
                        customParams = linkedMapOf(
                            "album_id" to albumId,
                            "area_code" to "1",
                            "hash" to hash.lowercase(),
                            "ssa_flag" to "is_fromtrack",
                            "version" to "11430",
                            "page_id" to "151369488",
                            "quality" to requestedQuality,
                            "album_audio_id" to albumAudioId,
                            "behavior" to "play",
                            "pid" to "2",
                            "cmd" to "26",
                            "pidversion" to "3001",
                            "a" to key,
                            "cdn" to "0",
                            "pid" to "2",
                        ),
                        body = null,
                        encryptType = EncryptType.ANDROID,
                    )
                }.getOrNull()
                if (root == null) {
                    lastMessage = "获取播放地址失败：接口请求异常"
                    if (attempt == 0) registerDevice(force = true)
                    return@repeat
                }
                val url = root.obj("data")?.array("url")?.firstOrNull()?.asString
                    ?: root.obj("data")?.array("url")?.firstOrNull()?.asJsonObject?.string("url")
                if (!url.isNullOrBlank()) {
                    val lossless = requestedQuality == "flac"
                    return PlaybackSource(
                        url = url,
                        formatHint = if (lossless) "flac" else "mp3",
                        qualityLabel = if (lossless) "无损" else "${requestedQuality}k",
                        bitrateKbps = requestedQuality.toIntOrNull(),
                        lossless = lossless,
                    )
                }
                lastMessage = root.string("error") ?: root.string("msg") ?: lastMessage
                if (root.int("error_code") == 30012 && attempt == 0) registerDevice(force = true)
            }
        }
        throw IllegalStateException(lastMessage)
    }

    override fun lyrics(song: MusicSong): MusicLyrics? {
        val hash = song.metadata["hash"] ?: song.id
        val keyword = listOf(song.name, song.artistText).filter(String::isNotBlank).joinToString(" - ")
        val searchQuery = buildQuery(
            "ver" to 1,
            "man" to "yes",
            "client" to "mobi",
            "keyword" to keyword,
            "duration" to (song.durationSeconds ?: 0),
            "hash" to hash.lowercase(),
        )
        val search = runCatching { JsonParser.parseString(requestText("https://krcs.kugou.com/search?$searchQuery")).asJsonObject }.getOrNull() ?: return null
        val candidate = search.getAsJsonArray("candidates")?.firstOrNull()?.asJsonObject ?: return null
        val id = candidate.string("id") ?: return null
        val accessKey = candidate.string("accesskey") ?: return null
        val downloadQuery = buildQuery(
            "ver" to 1,
            "client" to "pc",
            "id" to id,
            "accesskey" to accessKey,
            "fmt" to "lrc",
            "charset" to "utf8",
        )
        val root = runCatching { JsonParser.parseString(requestText("https://lyrics.kugou.com/download?$downloadQuery")).asJsonObject }.getOrNull() ?: return null
        val content = root.string("content")?.let { runCatching { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) }.getOrNull() }.orEmpty()
        if (content.isBlank()) return null
        return MusicLyrics(content)
    }

    override fun search(query: String, limit: Int): List<MusicSearchResult> {
        val params = linkedMapOf(
            "keyword" to query,
            "page" to "1",
            "pagesize" to limit.coerceIn(1, 50).toString(),
            "bitrate" to "0",
            "isfuzzy" to "0",
            "inputtype" to "0",
            "platform" to "WebFilter",
            "userid" to "0",
            "clientver" to "2000",
            "iscorrection" to "1",
            "privilege_filter" to "0",
            "filter" to "10",
            "token" to "",
            "srcappid" to SRC_APP_ID.toString(),
        )
        val root = runCatching {
            signedJson(
                baseUrl = "https://complexsearch.kugou.com",
                path = "/v2/search/song",
                method = "GET",
                customParams = params,
                body = null,
                encryptType = EncryptType.WEB,
            )
        }.getOrNull() ?: return emptyList()
        return root.obj("data")?.array("lists")?.mapNotNull { element ->
            val item = element.asJsonObject
            val song = parseSong(item) ?: return@mapNotNull null
            MusicSearchResult(
                platform = platform,
                kind = musicunlock.online.SearchResultKind.SONG,
                id = song.id,
                title = song.name,
                subtitle = song.artistText,
                coverUrl = song.coverUrl,
                song = song,
            )
        }.orEmpty().take(limit)
    }

    override fun song(songId: String): MusicSong? {
        val hash = songId.substringBefore(':')
        val query = buildQuery("r" to "play/getdata", "hash" to hash.lowercase())
        val root = runCatching { JsonParser.parseString(requestText("https://wwwapi.kugou.com/yy/index.php?$query")).asJsonObject }.getOrNull() ?: return null
        return root.obj("data")?.let(::parseSong)
    }

    override fun playlist(playlistId: String): MusicPlaylist? {
        if (session != null) return MusicPlaylist(playlistId, "酷狗歌单 $playlistId", null, 0, mapOf("public" to "false"))
        val query = buildQuery("specialid" to playlistId, "version" to 9108)
        val root = runCatching { JsonParser.parseString(requestText("https://mobilecdn.kugou.com/api/v3/special/info?$query")).asJsonObject }.getOrNull()
        val data = root?.obj("data")
        return MusicPlaylist(
            id = playlistId,
            name = data?.string("specialname") ?: "酷狗歌单 $playlistId",
            coverUrl = data?.string("imgurl"),
            trackCount = data?.int("songcount") ?: 0,
            metadata = mapOf("public" to "true"),
        )
    }

    fun publicPlaylistSongs(playlistId: String): List<MusicSong> {
        val out = mutableListOf<MusicSong>()
        var page = 1
        while (page <= 100) {
            val query = buildQuery("specialid" to playlistId, "page" to page, "pagesize" to 300, "version" to 9108)
            val root = runCatching { JsonParser.parseString(requestText("https://mobilecdn.kugou.com/api/v3/special/song?$query")).asJsonObject }.getOrNull() ?: break
            val data = root.obj("data") ?: break
            val list = data.array("info") ?: data.array("list") ?: break
            if (list.size() == 0) break
            list.mapNotNullTo(out) { element -> parseSong(element.asJsonObject) }
            val total = data.int("total") ?: 0
            if (list.size() < 300 || (total > 0 && out.size >= total)) break
            page++
        }
        return out.distinctBy { it.metadata["hash"] ?: it.id }
    }

    override fun album(albumId: String): Pair<MusicPlaylist, List<MusicSong>>? {
        val query = buildQuery("albumid" to albumId, "version" to 9108)
        val root = runCatching { JsonParser.parseString(requestText("https://mobilecdn.kugou.com/api/v3/album/info?$query")).asJsonObject }.getOrNull() ?: return null
        val data = root.obj("data") ?: return null
        val songs = publicAlbumSongs(albumId)
        val playlist = MusicPlaylist(
            id = albumId,
            name = data.string("albumname") ?: data.string("album_name") ?: "酷狗专辑 $albumId",
            coverUrl = data.string("imgurl") ?: data.string("cover"),
            trackCount = songs.size,
            metadata = mapOf("kind" to "album"),
        )
        return playlist to songs
    }

    override fun artistSongs(artistId: String, limit: Int): List<MusicSong> = publicArtistSongs(artistId, limit)

    private fun publicAlbumSongs(albumId: String): List<MusicSong> {
        val query = buildQuery("albumid" to albumId, "page" to 1, "pagesize" to 1000, "version" to 9108)
        val root = runCatching { JsonParser.parseString(requestText("https://mobilecdn.kugou.com/api/v3/album/song?$query")).asJsonObject }.getOrNull() ?: return emptyList()
        return root.obj("data")?.array("info")?.mapNotNull { element -> parseSong(element.asJsonObject) }.orEmpty()
    }

    private fun publicArtistSongs(artistId: String, limit: Int): List<MusicSong> {
        val query = buildQuery("singerid" to artistId, "page" to 1, "pagesize" to limit.coerceIn(1, 100), "version" to 9108)
        val root = runCatching { JsonParser.parseString(requestText("https://mobilecdn.kugou.com/api/v3/singer/song?$query")).asJsonObject }.getOrNull() ?: return emptyList()
        return root.obj("data")?.array("info")?.mapNotNull { element -> parseSong(element.asJsonObject) }.orEmpty()
    }

    override fun download(url: String, target: Path) {
        val response = requestRaw(url, headers = mapOf("Referer" to "https://www.kugou.com/"))
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("下载失败（HTTP ${response.statusCode()}）")
        }
        Files.newOutputStream(target).use { it.write(response.body()) }
    }

    override fun download(
        url: String,
        target: Path,
        offset: Long,
        onProgress: (Long, Long?, Long) -> Unit,
        shouldContinue: () -> Boolean,
    ) = musicunlock.online.HttpDownloader.download(
        settings = musicunlock.online.OnlineNetwork.settings(),
        url = url,
        target = target,
        headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "https://www.kugou.com/",
        ),
        offset = offset,
        onProgress = onProgress,
        shouldContinue = shouldContinue,
    )

    override fun bytes(url: String): ByteArray? = runCatching {
        val response = requestRaw(url, headers = mapOf("Referer" to "https://www.kugou.com/"))
        response.body().takeIf { response.statusCode() in 200..299 }
    }.getOrNull()

    // ============================================================
    // 设备注册与请求签名
    // ============================================================

    private enum class EncryptType { ANDROID, WEB }

    private data class Device(val guid: String, val mid: String, val dfid: String?)

    private fun currentDevice(): Device {
        val current = session
        val guid = current?.guid
            ?: firstNonBlank(cookieJar, "KUGOU_API_GUID", "kg_mid", "guid")
            ?: UUID.randomUUID().toString()
        val rawMid = current?.mid ?: firstNonBlank(cookieJar, "KUGOU_API_MID", "mid")
        val mid = rawMid?.takeIf { it.all(Char::isDigit) } ?: calculateMid(guid)
        val dfid = current?.dfid ?: firstNonBlank(cookieJar, "dfid", "kg_dfid")
        return Device(guid, mid, dfid)
    }

    private fun storeLogin(
        userId: String,
        token: String,
        nickname: String?,
        avatar: String?,
        dfid: String? = null,
        guid: String? = null,
        mid: String? = null,
    ) {
        val resolvedGuid = guid ?: firstNonBlank(cookieJar, "KUGOU_API_GUID", "kg_mid", "guid") ?: UUID.randomUUID().toString()
        val resolvedMid = mid?.takeIf { it.all(Char::isDigit) } ?: calculateMid(resolvedGuid)
        session = Session(
            userId = userId,
            token = token,
            dfid = dfid ?: firstNonBlank(cookieJar, "dfid", "kg_dfid"),
            guid = resolvedGuid,
            mid = resolvedMid,
            nickname = nickname,
            avatarUrl = avatar,
        )
    }

    private fun requireSession(): Session = session ?: throw IllegalStateException("未登录")

    private fun registerDevice(force: Boolean = false) {
        val current = requireSession()
        if (!force && !current.dfid.isNullOrBlank()) return
        val seed = randomAlphaNumeric(6).lowercase()
        val digest = md5(seed)
        val aesKey = digest.substring(0, 16)
        val aesIv = digest.substring(16, 32)
        val deviceData = linkedMapOf<String, Any?>(
            "availableRamSize" to 4983533568L,
            "availableRomSize" to 48114719L,
            "availableSDSize" to 48114717L,
            "basebandVer" to "",
            "batteryLevel" to 100,
            "batteryStatus" to 3,
            "brand" to "Redmi",
            "buildSerial" to "unknown",
            "device" to "marble",
            "imei" to current.guid,
            "imsi" to "",
            "manufacturer" to "Xiaomi",
            "uuid" to current.guid,
            "accelerometer" to false,
            "accelerometerValue" to "",
            "gravity" to false,
            "gravityValue" to "",
            "gyroscope" to false,
            "gyroscopeValue" to "",
            "light" to false,
            "lightValue" to "",
            "magnetic" to false,
            "magneticValue" to "",
            "orientation" to false,
            "orientationValue" to "",
            "pressure" to false,
            "pressureValue" to "",
            "step_counter" to false,
            "step_counterValue" to "",
            "temperature" to false,
            "temperatureValue" to "",
        )
        val encrypted = aesEncryptBase64(json(deviceData), aesKey, aesIv)
        val portraitPlain = json(linkedMapOf(
            "aes" to seed,
            "uid" to (current.userId.toLongOrNull() ?: 0L),
            "token" to current.token,
        ))
        val portrait = rsaEncryptHex(portraitPlain).uppercase()
        val response = signedRaw(
            baseUrl = "https://userservice.kugou.com",
            path = "/risk/v2/r_register_dev",
            method = "POST",
            customParams = linkedMapOf(
                "part" to "1",
                "platid" to "1",
                "p" to portrait,
            ),
            body = encrypted,
            encryptType = EncryptType.ANDROID,
        )
        var root = runCatching { JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject }.getOrNull()
        if (root == null) {
            root = runCatching {
                JsonParser.parseString(aesDecrypt(response.body(), aesKey, aesIv)).asJsonObject
            }.getOrNull()
        }
        val dfid = root?.obj("data")?.string("dfid") ?: root?.obj("data")?.string("device_id")
        if (!dfid.isNullOrBlank()) {
            current.dfid = dfid
            synchronized(cookieJar) {
                cookieJar["dfid"] = dfid
                cookieJar["kg_dfid"] = dfid
            }
        } else {
            throw IllegalStateException("酷狗播放验证失败：无法获取设备标识")
        }
    }

    private fun signedJson(
        baseUrl: String,
        path: String,
        method: String,
        customParams: Map<String, String>,
        body: String?,
        encryptType: EncryptType,
        xRouter: String? = null,
    ): JsonObject {
        val response = signedRaw(baseUrl, path, method, customParams, body, encryptType, xRouter)
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("酷狗请求失败（HTTP ${response.statusCode()}）")
        }
        return runCatching { JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject }
            .getOrElse { throw IllegalStateException("酷狗接口返回异常") }
    }

    private fun signedRaw(
        baseUrl: String,
        path: String,
        method: String,
        customParams: Map<String, String>,
        body: String?,
        encryptType: EncryptType,
        xRouter: String? = null,
    ): HttpResponse<ByteArray> {
        val device = currentDevice()
        val current = session
        val params = linkedMapOf<String, String>(
            "dfid" to (device.dfid ?: "-"),
            "mid" to device.mid,
            "uuid" to "-",
            "appid" to APP_ID.toString(),
            "clientver" to CLIENT_VERSION.toString(),
            "clienttime" to (System.currentTimeMillis() / 1000).toString(),
        )
        current?.let {
            params["token"] = it.token
            params["userid"] = it.userId
        }
        params.putAll(customParams)
        params["signature"] = when (encryptType) {
            EncryptType.ANDROID -> signature(ANDROID_SALT, params, body.orEmpty())
            EncryptType.WEB -> signature(WEB_SALT, params, body.orEmpty())
        }

        val query = params.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val builder = HttpRequest.newBuilder(URI.create("$baseUrl$path?$query"))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .header("dfid", device.dfid ?: "-")
            .header("clienttime", params["clienttime"]!!)
            .header("mid", device.mid)
            .header("kg-rc", "1")
            .header("kg-thash", "5d816a0")
            .header("kg-rec", "1")
            .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
            .header("Cookie", cookieHeader(current))
        if (body != null) builder.header("Content-Type", "application/json;charset=UTF-8")
        xRouter?.let { builder.header("x-router", it) }

        val request = when (method.uppercase()) {
            "POST" -> builder.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty())).build()
            else -> builder.GET().build()
        }
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun requestRaw(url: String, headers: Map<String, String>): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .GET()
        headers.forEach { (key, value) -> builder.header(key, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun signature(salt: String, params: Map<String, String>, body: String): String {
        val sorted = params.entries.sortedBy { it.key }
            .joinToString("") { (key, value) -> "$key=$value" }
        return md5("$salt$sorted$body$salt")
    }

    private fun cookieHeader(current: Session?): String {
        val cookie = synchronized(cookieJar) { LinkedHashMap(cookieJar) }
        current?.let {
            cookie["token"] = it.token
            cookie["userid"] = it.userId
            cookie["KUGOU_API_MID"] = it.mid
            it.dfid?.let { value -> cookie["dfid"] = value; cookie["kg_dfid"] = value }
        }
        return cookie.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private fun checkStatus(root: JsonObject, prefix: String) {
        val status = root.int("status")
        val errorCode = root.int("error_code") ?: root.int("errcode") ?: 0
        if (status == 0 || errorCode != 0) {
            throw IllegalStateException("$prefix：${root.string("error") ?: root.string("msg") ?: "接口异常"}")
        }
    }

    private fun parsePlaylist(item: JsonObject): MusicPlaylist? {
        val id = item.string("listid")
            ?: item.string("list_id")
            ?: item.string("id")
            ?: return null
        val name = item.string("listname")
            ?: item.string("name")
            ?: item.string("title")
            ?: return null
        val globalId = item.string("global_collection_id")
            ?: item.string("global_id")
        val count = item.int("songcount")
            ?: item.int("song_count")
            ?: item.int("list_count")
            ?: item.int("count")
            ?: item.int("musicnum")
            ?: 0
        return MusicPlaylist(
            id = id,
            name = name,
            coverUrl = item.string("pic")
                ?: item.string("list_pic")
                ?: item.string("cover")
                ?: item.string("img"),
            trackCount = count,
            metadata = buildMap {
                globalId?.let { put("globalCollectionId", it) }
            },
        )
    }

    private fun requestText(url: String): String {
        val response = requestRaw(url, emptyMap())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("酷狗请求失败（HTTP ${response.statusCode()}）")
        }
        return String(response.body(), Charsets.UTF_8)
    }

    private fun parseSong(item: JsonObject): MusicSong? {
        val audioInfo = item.obj("audio_info")
        val hash = item.string("hash")
            ?: audioInfo?.string("hash")
            ?: item.string("audio_hash")
            ?: return null
        val albumInfo = item.obj("album_info")
        val albumAudioId = item.string("album_audio_id")
            ?: audioInfo?.string("album_audio_id")
            ?: audioInfo?.string("audio_group_id")
            ?: item.string("audio_id")
            ?: "0"
        val name = item.string("songname")
            ?: item.string("audio_name")
            ?: item.string("name")
            ?: item.string("filename")
            ?: hash
        val artist = item.string("singername")
            ?: item.string("author_name")
            ?: item.array("authors")?.firstString("author_name")
            ?: ""
        val cover = item.obj("trans_param")?.string("union_cover")
            ?.replace("{size}", "500")
            ?: albumInfo?.string("cover")
            ?: item.string("cover")
            ?: item.string("img")
        return MusicSong(
            id = hash,
            name = name,
            artists = splitArtists(artist),
            albumName = item.string("album_name")
                ?: albumInfo?.string("album_name")
                ?: item.string("album"),
            coverUrl = cover,
            metadata = buildMap {
                put("hash", hash)
                put("albumAudioId", albumAudioId)
                item.string("album_id")?.let { put("albumId", it) }
                audioInfo?.string("album_id")?.let { put("albumId", it) }
                albumInfo?.string("album_id")?.let { put("albumId", it) }
            },
            durationSeconds = item.int("duration")?.takeIf { it > 0 }
                ?: audioInfo?.int("duration")?.takeIf { it > 0 }
                ?: item.int("timelength")?.div(1000)?.takeIf { it > 0 },
            trackNumber = item.int("sort")?.takeIf { it > 0 },
            year = item.string("publish_time")?.take(4)?.toIntOrNull(),
        )
    }

    private fun findAudioUrl(root: JsonObject): String? {
        val data = root.get("data") ?: return null
        return findUrlInKnownFields(data) ?: findUrl(data)
    }

    private fun findUrlInKnownFields(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonArray) {
            element.asJsonArray.forEach { child -> findUrlInKnownFields(child)?.let { return it } }
            return null
        }
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        for (name in listOf("url", "play_url", "backup_url", "audio_url")) {
            obj.get(name)?.let { child -> findUrl(child)?.let { return it } }
        }
        return null
    }

    private fun findUrl(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) {
            val text = runCatching { element.asString }.getOrNull()
            return text?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }
        if (element.isJsonArray) {
            element.asJsonArray.forEach { child -> findUrl(child)?.let { return it } }
            return null
        }
        val obj = element.asJsonObject
        obj.entrySet().forEach { (_, child) -> findUrl(child)?.let { return it } }
        return null
    }

    // ============================================================
    // 加密 / 工具
    // ============================================================

    private fun calculateMid(guid: String): String =
        BigInteger(1, md5Bytes(guid)).toString(10)

    private fun md5(value: String): String = md5Bytes(value).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun md5Bytes(value: String): ByteArray =
        MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))

    private fun aesEncryptBase64(value: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun aesDecrypt(value: ByteArray, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(iv.toByteArray(Charsets.UTF_8)),
        )
        return String(cipher.doFinal(value), Charsets.UTF_8)
    }

    private fun rsaEncryptHex(value: String): String {
        val keyBytes = Base64.getDecoder().decode(PUBLIC_KEY)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun json(value: Any?): String = com.google.gson.Gson().toJson(value)

    private fun randomAlphaNumeric(length: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return buildString { repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    private fun splitArtists(value: String): List<String> =
        value.split('&', '、', ',', '，', '/', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun parseCookieHeader(header: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        header.split(';').forEach { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@forEach
            val key = part.substring(0, index).trim()
            val value = part.substring(index + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    private fun firstNonBlank(map: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> map[key]?.takeIf { it.isNotBlank() } }

    private fun decodeCookieValue(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

    private fun buildQuery(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value?.toString().orEmpty())}" }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.array(name: String): JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonArray.firstString(name: String): String? =
        firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.string(name)
}
