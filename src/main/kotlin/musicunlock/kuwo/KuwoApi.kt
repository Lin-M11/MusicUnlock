package musicunlock.kuwo

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import musicunlock.online.MusicAccount
import musicunlock.online.MusicLyrics
import musicunlock.online.MusicSearchResult
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicPlaylist
import musicunlock.online.MusicSong
import musicunlock.online.OnlineMusicProvider
import musicunlock.online.PlaybackSource
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.random.Random

/**
 * 酷我官方网页 / 客户端接口。
 *
 * 登录态来自官方网页 Cookie；我的歌单和播放地址使用酷我公开的 pl.svc 与
 * www openapi。页面只依赖 [OnlineMusicProvider]。
 */
object KuwoApi : OnlineMusicProvider {

    override val platform = MusicPlatform.KUWO

    private const val USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val ANTI_BOT_COOKIE = "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324"
    private const val ANTI_BOT_PASSWORD = "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324"

    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Volatile
    private var accountCache: MusicAccount? = null
    private val cookieJar = LinkedHashMap<String, String>()

    override fun restoreSession(cookieHeader: String): MusicAccount = loginWithCookie(cookieHeader)

    fun loginWithCookie(cookieHeader: String): MusicAccount {
        if (cookieHeader.isBlank()) throw IllegalStateException("请先复制 Cookie")
        val parsed = parseCookieHeader(cookieHeader)
        val userId = firstNonBlank(parsed, "userid", "t3kwid", "uid", "kwid", "loginUid")
            ?: throw IllegalStateException("Cookie 中缺少 userid，请登录 www.kuwo.cn 后完整复制 Cookie")
        val sid = firstNonBlank(parsed, "sid", "websid", "loginSid") ?: "0"
        if (sid == "0") throw IllegalStateException("Cookie 中缺少登录票据 sid/websid，请重新登录后复制")

        synchronized(cookieJar) {
            cookieJar.clear()
            cookieJar.putAll(parsed)
        }
        ensureAntiBotCookie()
        if (cookieJar["kw_token"].isNullOrBlank()) {
            cookieJar["kw_token"] = randomAlphaNumeric(11)
        }

        val nickname = firstNonBlank(parsed, "nickname", "uname3", "username", "uname", "nick")
            ?.let(::decodeCookieValue)
            ?.takeIf { it.isNotBlank() }
            ?: "酷我用户 $userId"
        val avatar = firstNonBlank(parsed, "pic3", "avatar", "userpic", "pic")
            ?.let(::decodeCookieValue)
            ?.takeIf { it.isNotBlank() }
        val account = MusicAccount(nickname = nickname, avatarUrl = avatar, userId = userId)
        accountCache = account

        // 验证登录票据是否仍可用；无效票据不会返回用户歌单。
        if (playlists().isEmpty()) {
            val raw = rawUserList()
            val result = raw.get("result")?.takeIf { it.isJsonPrimitive }?.asString
            if (result != null && result != "ok") {
                logout()
                throw IllegalStateException("Cookie 无效或已过期，请重新登录后复制")
            }
        }
        return account
    }

    override fun exportSessionCookie(): String? {
        val snapshot = synchronized(cookieJar) { LinkedHashMap(cookieJar) }
        if (snapshot.isEmpty() || accountCache == null) return null
        return snapshot.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    override fun logout() {
        synchronized(cookieJar) { cookieJar.clear() }
        accountCache = null
    }

    override fun account(): MusicAccount? = accountCache

    override fun playlists(): List<MusicPlaylist> {
        val out = mutableListOf<MusicPlaylist>()
        var page = 0
        var total = Int.MAX_VALUE
        while (out.size < total && page < 20) {
            val root = rawUserList(page = page, pageSize = 1000)
            if (root.get("result")?.asString != "ok") {
                throw IllegalStateException("获取酷我歌单失败：${root.get("result")?.asString ?: "接口异常"}")
            }
            total = root.get("total")?.asInt ?: root.getAsJsonArray("plist")?.size() ?: 0
            val list = root.getAsJsonArray("plist") ?: break
            if (list.size() == 0) break
            list.forEach { element ->
                val item = element.asJsonObject
                val id = item.long("id") ?: return@forEach
                val type = item.string("type").orEmpty().uppercase()
                if (type in setOf("ORDER", "TAG", "ARTIST", "ALBUM", "USER")) return@forEach
                val name = item.string("title")?.takeIf { it.isNotBlank() } ?: return@forEach
                out += MusicPlaylist(
                    id = id.toString(),
                    name = name,
                    coverUrl = item.string("pic")?.takeIf { it.isNotBlank() },
                    trackCount = item.int("musicnum") ?: 0,
                    metadata = mapOf("type" to type, "uid" to (item.string("uid") ?: "")),
                )
            }
            page++
        }
        return out.distinctBy { it.id }
    }

    override fun songs(playlist: MusicPlaylist): List<MusicSong> {
        val pid = playlist.id.toLongOrNull() ?: return emptyList()
        val out = mutableListOf<MusicSong>()
        var page = 0
        var total = playlist.trackCount.coerceAtLeast(0)
        while (page < 50 && (total == 0 || out.size < total)) {
            val root = rawPlaylist(pid, page = page, pageSize = 1000)
            if (root.get("result")?.asString != "ok") {
                throw IllegalStateException("获取歌单失败：${root.get("result")?.asString ?: "接口异常"}")
            }
            if (total == 0) total = root.int("total") ?: root.int("validtotal") ?: 0
            val list = root.getAsJsonArray("musiclist") ?: break
            if (list.size() == 0) break
            list.forEach { element ->
                val item = element.asJsonObject
                val id = item.long("rid") ?: item.long("id") ?: return@forEach
                val name = item.string("name")?.takeIf { it.isNotBlank() }
                    ?: item.string("FSONGNAME")?.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val artist = item.string("artist")?.takeIf { it.isNotBlank() }
                    ?: item.string("FARTIST").orEmpty()
                val album = item.string("album")?.takeIf { it.isNotBlank() }
                    ?: item.string("FALBUM")?.takeIf { it.isNotBlank() }
                val cover = item.string("albumpic")?.takeIf { it.isNotBlank() }
                    ?: item.string("pic")?.takeIf { it.isNotBlank() }
                out += MusicSong(
                    id = id.toString(),
                    name = name,
                    artists = splitArtists(artist),
                    albumName = album,
                    coverUrl = cover,
                    metadata = mapOf("rid" to id.toString()),
                    durationSeconds = item.long("duration")?.div(1000)?.toInt()?.takeIf { it > 0 },
                )
            }
            page++
        }
        return out
    }

    override fun playback(song: MusicSong): PlaybackSource = playback(song, musicunlock.settings.QualityStrategy.HIGHEST)

    override fun playback(song: MusicSong, quality: musicunlock.settings.QualityStrategy): PlaybackSource {
        val rid = song.metadata["rid"] ?: song.id
        val qualities = when (quality) {
            musicunlock.settings.QualityStrategy.LOSSLESS_FIRST -> listOf("flac", "320kmp3", "192kmp3", "128kmp3")
            musicunlock.settings.QualityStrategy.HIGHEST -> listOf("flac", "320kmp3", "192kmp3", "128kmp3")
            musicunlock.settings.QualityStrategy.MP3_320 -> listOf("320kmp3", "192kmp3", "128kmp3")
            musicunlock.settings.QualityStrategy.BALANCED -> listOf("192kmp3", "128kmp3", "320kmp3")
            musicunlock.settings.QualityStrategy.SMALLEST -> listOf("128kmp3", "192kmp3", "320kmp3", "flac")
        }
        var lastMessage = "获取播放地址失败"
        for (requested in qualities) {
            val response = playUrl(rid, requested)
            val code = response.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
            if (code != 200 || response.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) {
                lastMessage = response.string("msg") ?: response.string("message") ?: lastMessage
                continue
            }
            val data = response.getAsJsonObject("data") ?: continue
            val url = data.string("url")?.takeIf { it.isNotBlank() } ?: continue
            val lossless = requested == "flac" || (data.string("format") ?: "").lowercase() in setOf("flac", "ape", "wav")
            val bitrate = when {
                requested.startsWith("320") -> 320
                requested.startsWith("192") -> 192
                requested.startsWith("128") -> 128
                else -> null
            }
            return PlaybackSource(
                url = url,
                formatHint = if (lossless) "flac" else "mp3",
                qualityLabel = if (lossless) "无损" else bitrate?.let { "${it}k" },
                bitrateKbps = bitrate,
                lossless = lossless,
            )
        }
        throw IllegalStateException(lastMessage)
    }

    override fun lyrics(song: MusicSong): MusicLyrics? {
        val rid = song.metadata["rid"] ?: song.id
        val root = apiJson("https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=$rid") ?: return null
        val list = root.obj("data")?.arr("lrclist") ?: return null
        val lrc = buildString {
            list.forEach { element ->
                val item = element.asJsonObject
                val time = item.string("time")?.toDoubleOrNull() ?: return@forEach
                val text = item.string("lineLyric") ?: return@forEach
                val minutes = (time / 60).toInt()
                val seconds = time - minutes * 60
                append("[%02d:%05.2f]".format(minutes, seconds))
                appendLine(text)
            }
        }.trim()
        if (lrc.isBlank()) return null
        return MusicLyrics(lrc)
    }

    override fun search(query: String, limit: Int): List<MusicSearchResult> {
        val encoded = encode(query)
        val pageSize = limit.coerceIn(1, 50)
        val out = mutableListOf<MusicSearchResult>()
        val songs = apiJson(
            "https://www.kuwo.cn/api/www/search/searchMusicBykeyWord?key=$encoded&pn=1&rn=$pageSize&httpsStatus=1",
        )
        songs?.obj("data")?.arr("list")?.forEach { element ->
            val item = element.asJsonObject
            val rid = item.long("rid") ?: return@forEach
            val name = item.string("name") ?: return@forEach
            val artist = item.string("artist").orEmpty()
            val album = item.string("album")
            val cover = item.string("pic")
            val song = MusicSong(
                id = rid.toString(),
                name = name,
                artists = splitArtists(artist),
                albumName = album,
                coverUrl = cover,
                metadata = mapOf("rid" to rid.toString()),
                durationSeconds = item.long("duration")?.div(1000)?.toInt()?.takeIf { it > 0 },
            )
            out += MusicSearchResult(platform, musicunlock.online.SearchResultKind.SONG, song.id, song.name, song.artistText, song.coverUrl, song = song)
        }
        val playlists = apiJson(
            "https://www.kuwo.cn/api/www/search/searchPlayListBykeyWord?key=$encoded&pn=1&rn=$pageSize&httpsStatus=1",
        )
        playlists?.obj("data")?.arr("list")?.forEach { element ->
            val item = element.asJsonObject
            val id = item.long("id") ?: return@forEach
            val name = item.string("name") ?: return@forEach
            val playlist = MusicPlaylist(
                id = id.toString(),
                name = name,
                coverUrl = item.string("pic"),
                trackCount = item.int("songnum") ?: 0,
            )
            out += MusicSearchResult(platform, musicunlock.online.SearchResultKind.PLAYLIST, playlist.id, playlist.name, "${playlist.trackCount} 首", playlist.coverUrl, playlist = playlist)
        }
        return out.distinctBy { "${it.kind}:${it.id}" }.take(limit)
    }

    override fun song(songId: String): MusicSong? {
        val rid = songId.substringBefore(':').toLongOrNull() ?: return null
        val root = apiJson("https://www.kuwo.cn/api/www/music/musicInfo?mid=$rid&httpsStatus=1") ?: return null
        val item = root.obj("data") ?: return null
        val name = item.string("name") ?: return null
        return MusicSong(
            id = rid.toString(),
            name = name,
            artists = splitArtists(item.string("artist").orEmpty()),
            albumName = item.string("album"),
            coverUrl = item.string("pic"),
            metadata = mapOf("rid" to rid.toString()),
            durationSeconds = item.long("duration")?.div(1000)?.toInt()?.takeIf { it > 0 },
        )
    }

    override fun playlist(playlistId: String): MusicPlaylist? {
        val id = playlistId.toLongOrNull() ?: return null
        val root = rawPlaylist(id, page = 0, pageSize = 1)
        if (root.get("result")?.asString != "ok") return null
        return MusicPlaylist(
            id = id.toString(),
            name = root.string("title") ?: root.string("name") ?: "酷我歌单 $id",
            coverUrl = root.string("pic"),
            trackCount = root.int("total") ?: root.int("validtotal") ?: 0,
        )
    }

    override fun album(albumId: String): Pair<MusicPlaylist, List<MusicSong>>? {
        val id = albumId.toLongOrNull() ?: return null
        val root = apiJson("https://www.kuwo.cn/api/www/album/albumInfo?albumId=$id&pn=1&rn=1000&httpsStatus=1") ?: return null
        val data = root.obj("data") ?: return null
        val list = data.arr("musicList") ?: data.arr("list") ?: return null
        val songs = list.mapNotNull { element ->
            val item = element.asJsonObject
            val rid = item.long("rid") ?: item.long("id") ?: return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            MusicSong(rid.toString(), name, splitArtists(item.string("artist").orEmpty()), data.string("album"), data.string("pic"), mapOf("rid" to rid.toString()))
        }
        val playlist = MusicPlaylist(id.toString(), data.string("album") ?: data.string("name") ?: "酷我专辑", data.string("pic"), songs.size)
        return playlist to songs
    }

    override fun artistSongs(artistId: String, limit: Int): List<MusicSong> {
        val id = artistId.toLongOrNull() ?: return emptyList()
        val root = apiJson("https://www.kuwo.cn/api/www/artist/artistMusic?artistid=$id&pn=1&rn=${limit.coerceIn(1, 100)}&httpsStatus=1") ?: return emptyList()
        return root.obj("data")?.arr("list")?.mapNotNull { element ->
            val item = element.asJsonObject
            val rid = item.long("rid") ?: item.long("id") ?: return@mapNotNull null
            val name = item.string("name") ?: return@mapNotNull null
            MusicSong(
                id = rid.toString(),
                name = name,
                artists = splitArtists(item.string("artist").orEmpty()),
                albumName = item.string("album"),
                coverUrl = item.string("pic"),
                metadata = mapOf("rid" to rid.toString()),
            )
        }.orEmpty()
    }

    override fun download(url: String, target: Path) {
        val response = request(url, headers = mapOf("Referer" to "https://www.kuwo.cn/"))
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("下载失败（HTTP ${response.statusCode()}）")
        }
        Files.newOutputStream(target).use { output -> output.write(response.body()) }
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
            "Referer" to "https://www.kuwo.cn/",
            "Cookie" to sessionCookieHeader(),
        ),
        offset = offset,
        onProgress = onProgress,
        shouldContinue = shouldContinue,
    )

    override fun bytes(url: String): ByteArray? = runCatching {
        val response = request(url, headers = mapOf("Referer" to "https://www.kuwo.cn/"))
        response.body().takeIf { response.statusCode() in 200..299 }
    }.getOrNull()

    // ============================================================
    // 官方接口
    // ============================================================

    private fun apiJson(url: String): JsonObject? = runCatching {
        val response = request(url, headers = apiHeaders())
        if (response.statusCode() !in 200..299) return@runCatching null
        JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject
    }.getOrNull()

    private fun JsonObject.obj(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.arr(name: String): com.google.gson.JsonArray? =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun rawUserList(page: Int = 0, pageSize: Int = 1000): JsonObject {
        val userId = accountCache?.userId ?: cookieJar["userid"] ?: cookieJar["t3kwid"]
            ?: throw IllegalStateException("未登录")
        val sid = cookieJar["sid"] ?: cookieJar["websid"] ?: "0"
        val query = buildQuery(
            "op" to "getuserlist",
            "uid" to userId,
            "userid" to userId,
            "sid" to sid,
            "loginUid" to userId,
            "loginSid" to sid,
            "pn" to page,
            "rn" to pageSize,
            "encode" to "utf8",
            "keyset" to "pl2012",
            "identity" to "kuwo",
            "pcmp4" to 1,
            "vipver" to "MUSIC_9.0.5.0_W1",
            "newver" to 1,
        )
        val response = request("http://nplserver.kuwo.cn/pl.svc?$query")
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("获取酷我歌单失败（HTTP ${response.statusCode()}）")
        }
        return JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject
    }

    private fun rawPlaylist(pid: Long, page: Int, pageSize: Int): JsonObject {
        val query = buildQuery(
            "op" to "getlistinfo",
            "pid" to pid,
            "pn" to page,
            "rn" to pageSize,
            "encode" to "utf8",
            "keyset" to "pl2012",
            "identity" to "kuwo",
            "pcmp4" to 1,
            "vipver" to "MUSIC_9.0.5.0_W1",
            "newver" to 1,
        )
        val response = request("http://nplserver.kuwo.cn/pl.svc?$query")
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("获取歌单失败（HTTP ${response.statusCode()}）")
        }
        return JsonParser.parseString(String(response.body(), Charsets.UTF_8)).asJsonObject
    }

    private fun playUrl(rid: String, quality: String): JsonObject {
        val query = buildQuery(
            "mid" to rid,
            "type" to "music",
            "httpsStatus" to 1,
            "plat" to "web_www",
            "from" to "",
            "br" to quality,
            "reqId" to UUID.randomUUID().toString(),
        )
        val response = request(
            url = "https://www.kuwo.cn/api/v1/www/music/playUrl?$query",
            headers = apiHeaders(),
        )
        val body = String(response.body(), Charsets.UTF_8)
        return runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw IllegalStateException("播放地址接口返回异常") }
    }

    private fun apiHeaders(): Map<String, String> {
        ensureAntiBotCookie()
        val cookieValue = cookieJar[ANTI_BOT_COOKIE] ?: randomAlphaNumeric(32)
        return mapOf(
            "Referer" to "https://www.kuwo.cn/",
            "Secret" to antiBotSecret(cookieValue, ANTI_BOT_PASSWORD),
            "csrf" to (cookieJar["kw_token"] ?: randomAlphaNumeric(11)),
            "Cookie" to sessionCookieHeader(),
        )
    }

    // ============================================================
    // HTTP / Cookie / 加密工具
    // ============================================================

    private fun request(url: String, headers: Map<String, String> = emptyMap()): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .GET()
        headers.forEach { (key, value) -> builder.header(key, value) }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun ensureAntiBotCookie() {
        synchronized(cookieJar) {
            if (cookieJar[ANTI_BOT_COOKIE].isNullOrBlank()) {
                cookieJar[ANTI_BOT_COOKIE] = randomAlphaNumeric(32)
            }
        }
    }

    private fun sessionCookieHeader(): String =
        synchronized(cookieJar) {
            cookieJar.entries.joinToString("; ") { (key, value) -> "$key=$value" }
        }

    private fun buildQuery(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value?.toString().orEmpty())}"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun decodeCookieValue(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8)
    }.getOrDefault(value)

    private fun firstNonBlank(map: Map<String, String>, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> map[key]?.takeIf { it.isNotBlank() } }

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

    private fun randomAlphaNumeric(length: Int): String {
        val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        return buildString { repeat(length) { append(alphabet[Random.nextInt(alphabet.length)]) } }
    }

    private fun splitArtists(value: String): List<String> =
        value.split('&', '、', ',', '，', '/', ';', '；')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 复刻酷我网页的 Secret 头算法。 */
    internal fun antiBotSecret(text: String, password: String): String =
        antiBotSecret(text, password, (Math.round(1e9 * Random.nextDouble()) % 100_000_000).toLong())

    internal fun antiBotSecret(text: String, password: String, random: Long): String {
        val digitString = buildString { password.forEach { append(it.code.toString()) } }
        val split = digitString.length / 5
        val seedText = buildString {
            for (i in 1..5) {
                val index = i * split
                if (index < digitString.length) append(digitString[index])
            }
        }
        val seed = seedText.toIntOrNull() ?: return ""
        val add = ceil(password.length / 2.0).toInt()
        val modulus = Int.MAX_VALUE.toDouble()

        var folded = run {
            var raw = digitString + random.toString()
            while (raw.length > 10) {
                raw = jsNumberToString(jsParseInt(raw.substring(0, 10)) + jsParseInt(raw.substring(10)))
            }
            raw.toDouble()
        }
        var state = (seed * folded + add) % modulus
        val out = StringBuilder()
        text.forEach { char ->
            val value = char.code xor floor(state / modulus * 255).toInt()
            out.append(value.toString(16).padStart(2, '0'))
            state = (seed * state + add) % modulus
        }
        out.append(random.toString(16).padStart(8, '0'))
        return out.toString()
    }


    /** parseInt 在遇到小数点、e 等非数字字符时即停止，用于复刻网页算法。 */
    private fun jsParseInt(value: String): Double {
        val text = value.trimStart()
        var index = 0
        var negative = false
        if (index < text.length && (text[index] == '+' || text[index] == '-')) {
            negative = text[index] == '-'
            index++
        }
        val start = index
        while (index < text.length && text[index].isDigit()) index++
        if (index == start) return 0.0
        val parsed = text.substring(start, index).toDouble()
        return if (negative) -parsed else parsed
    }

    private fun jsNumberToString(value: Double): String {
        if (value == 0.0) return "0"
        val absolute = kotlin.math.abs(value)
        if (absolute >= 1e21 || absolute < 1e-6) {
            return value.toString()
                .replace('E', 'e')
                .replace(Regex("e(?=[0-9])"), "e+")
        }
        return if (value % 1.0 == 0.0 && absolute < 9.007199254740992E15) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.int(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.long(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }
}
