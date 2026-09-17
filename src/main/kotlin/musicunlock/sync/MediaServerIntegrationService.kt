package musicunlock.sync

import com.google.gson.JsonParser
import musicunlock.settings.MediaServerConfig
import musicunlock.settings.MediaServerType
import musicunlock.settings.PlatformCredentialStore
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

data class MediaServerScanResult(
    val serverName: String,
    val success: Boolean,
    val message: String,
)

/** 触发 Plex、Jellyfin、Navidrome / Subsonic 重新扫描曲库。 */
class MediaServerIntegrationService(
    private val credentials: PlatformCredentialStore = PlatformCredentialStore(),
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun trigger(server: MediaServerConfig, path: String? = null): MediaServerScanResult = runCatching {
        when (server.type) {
            MediaServerType.PLEX -> scanPlex(server, path)
            MediaServerType.JELLYFIN -> scanJellyfin(server)
            MediaServerType.NAVIDROME, MediaServerType.SUBSONIC -> scanSubsonic(server)
        }
        MediaServerScanResult(server.name, true, "已请求媒体服务器重新扫描")
    }.getOrElse {
        MediaServerScanResult(server.name, false, it.message ?: it.toString())
    }

    private fun scanPlex(server: MediaServerConfig, path: String?) {
        val token = requireSecret(server, "Plex Token")
        val libraryIds = server.libraryId?.let(::listOf) ?: plexLibraryIds(server, token)
        check(libraryIds.isNotEmpty()) { "没有找到可刷新的 Plex 媒体库" }
        libraryIds.forEach { id ->
            val query = buildMap {
                put("X-Plex-Token", token)
                path?.takeIf(String::isNotBlank)?.let { put("path", it) }
            }
            send(
                HttpRequest.newBuilder(URI.create("${server.baseUrl}/library/sections/$id/refresh?${queryString(query)}"))
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
            )
        }
    }

    private fun plexLibraryIds(server: MediaServerConfig, token: String): List<String> {
        val response = send(
            HttpRequest.newBuilder(URI.create("${server.baseUrl}/library/sections?X-Plex-Token=${encode(token)}"))
                .header("Accept", "application/json")
                .GET()
                .build(),
        )
        val root = JsonParser.parseString(String(response, StandardCharsets.UTF_8)).asJsonObject
        return root.getAsJsonObject("MediaContainer")?.getAsJsonArray("Directory")
            ?.mapNotNull { it.asJsonObject.get("key")?.asString }
            .orEmpty()
    }

    private fun scanJellyfin(server: MediaServerConfig) {
        val token = requireSecret(server, "Jellyfin API Key")
        send(
            HttpRequest.newBuilder(URI.create("${server.baseUrl}/Library/Refresh"))
                .header("X-Emby-Token", token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
        )
    }

    private fun scanSubsonic(server: MediaServerConfig) {
        val secret = requireSecret(server, "Subsonic 密码")
        val username = server.username ?: error("缺少 Subsonic 用户名")
        val salt = UUID.randomUUID().toString().take(8)
        val token = md5(secret + salt)
        val query = queryString(
            linkedMapOf(
                "u" to username,
                "t" to token,
                "s" to salt,
                "v" to "1.16.1",
                "c" to "MusicUnlock",
                "f" to "json",
            ),
        )
        send(HttpRequest.newBuilder(URI.create("${server.baseUrl}/rest/startScan.view?$query")).GET().build())
    }

    private fun requireSecret(server: MediaServerConfig, label: String): String =
        credentials.get(secretKey(server.id))?.takeIf(String::isNotBlank) ?: error("未配置 $label")

    private fun send(request: HttpRequest): ByteArray {
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
        return response.body()
    }

    private fun queryString(values: Map<String, String>): String =
        values.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun secretKey(id: String): String = "media-server:$id"
}
