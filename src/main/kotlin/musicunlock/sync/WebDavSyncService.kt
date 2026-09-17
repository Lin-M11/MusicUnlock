package musicunlock.sync

import musicunlock.backup.AppBackupService
import musicunlock.settings.AppSettings
import musicunlock.settings.PlatformCredentialStore
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration
import java.util.Base64

data class WebDavSyncResult(
    val remoteUrl: String,
    val bytes: Long,
    val message: String,
)

/** 通过标准 WebDAV 上传/下载完整备份，可接 Nextcloud、坚果云、NAS 等。 */
class WebDavSyncService(
    private val credentials: PlatformCredentialStore = PlatformCredentialStore(),
    private val backupService: AppBackupService = AppBackupService(),
) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    @Synchronized
    fun backup(settings: AppSettings, password: String? = null): WebDavSyncResult {
        if (password != null) credentials.put(WEBDAV_PASSWORD, password)
        val remote = remoteUri(settings)
        val temp = Files.createTempFile("MusicUnlock-webdav-", ".zip").toFile()
        try {
            backupService.export(temp)
            val request = request(settings, remote).PUT(HttpRequest.BodyPublishers.ofFile(temp.toPath())).build()
            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            check(response.statusCode() in 200..299) { "WebDAV 上传失败（HTTP ${response.statusCode()}）" }
            return WebDavSyncResult(remote.toString(), temp.length(), "备份已上传")
        } finally {
            temp.delete()
        }
    }

    @Synchronized
    fun restore(settings: AppSettings, password: String? = null): WebDavSyncResult {
        if (password != null) credentials.put(WEBDAV_PASSWORD, password)
        val remote = remoteUri(settings)
        val temp = Files.createTempFile("MusicUnlock-webdav-restore-", ".zip").toFile()
        try {
            val request = request(settings, remote).GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(temp.toPath()))
            check(response.statusCode() in 200..299) { "WebDAV 下载失败（HTTP ${response.statusCode()}）" }
            backupService.restore(temp)
            return WebDavSyncResult(remote.toString(), temp.length(), "备份已恢复")
        } finally {
            temp.delete()
        }
    }

    private fun remoteUri(settings: AppSettings): URI {
        val base = settings.webdavUrl?.takeIf(String::isNotBlank) ?: error("尚未配置 WebDAV 地址")
        val normalized = if (base.endsWith('/')) base else "$base/"
        return URI.create(normalized).resolve(settings.webdavRemoteFile)
    }

    private fun request(settings: AppSettings, uri: URI): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMinutes(30))
            .header("User-Agent", "MusicUnlock")
        val username = settings.webdavUsername?.takeIf(String::isNotBlank)
        if (username != null) {
            val password = credentials.get(WEBDAV_PASSWORD).orEmpty()
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
            builder.header("Authorization", "Basic $token")
        }
        return builder
    }

    private companion object {
        const val WEBDAV_PASSWORD = "webdav-password"
    }
}
