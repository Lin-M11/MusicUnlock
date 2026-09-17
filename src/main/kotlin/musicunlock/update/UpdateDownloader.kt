package musicunlock.update

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration

data class UpdateDownloadResult(
    val file: File,
    val asset: ReleaseAsset,
    val sha256: String,
    val checksumVerified: Boolean,
)

/** 选择并下载当前平台安装包，存在 SHA256SUMS.txt 时强制校验。 */
object UpdateDownloader {
    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun selectAsset(release: ReleaseInfo, platform: UpdatePlatform = currentPlatform()): ReleaseAsset? {
        val preferred = when (platform) {
            UpdatePlatform.MACOS -> listOf(".dmg")
            UpdatePlatform.WINDOWS -> listOf(".exe")
            UpdatePlatform.LINUX_DEB -> listOf(".deb")
            UpdatePlatform.LINUX_RPM -> listOf(".rpm")
        }
        return preferred.firstNotNullOfOrNull { suffix ->
            release.assets.firstOrNull { it.name.endsWith(suffix, ignoreCase = true) }
        }
    }

    fun download(
        release: ReleaseInfo,
        target: File,
        platform: UpdatePlatform = currentPlatform(),
    ): UpdateDownloadResult {
        val asset = selectAsset(release, platform)
            ?: throw IllegalStateException("当前版本没有适用于本机的安装包")
        target.parentFile?.mkdirs()
        val temp = File.createTempFile(target.name, ".download", target.parentFile)
        try {
            downloadTo(asset.downloadUrl, temp)
            if (asset.size > 0L && temp.length() != asset.size) {
                throw IllegalStateException("安装包大小不完整，请重新下载")
            }
            val checksum = checksumFor(release, asset.name)
            val actual = sha256(temp)
            if (checksum != null && !checksum.equals(actual, ignoreCase = true)) {
                throw IllegalStateException("安装包校验失败，请重新下载")
            }
            moveReplacing(temp, target)
            return UpdateDownloadResult(target, asset, actual, checksum != null)
        } finally {
            temp.delete()
        }
    }

    private fun checksumFor(release: ReleaseInfo, assetName: String): String? {
        val checksumAsset = release.assets.firstOrNull { it.name.equals("SHA256SUMS.txt", ignoreCase = true) }
            ?: return null
        val checksumText = getText(checksumAsset.downloadUrl)
        return checksumText.lineSequence().firstNotNullOfOrNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            if (parts.size != 2) return@firstNotNullOfOrNull null
            val name = parts[1].removePrefix("*").trim().removePrefix("./")
            if (name == assetName) parts[0].trim().takeIf { it.length == 64 } else null
        }
    }

    private fun downloadTo(url: String, target: File) {
        val response = send(
            url,
            HttpResponse.BodyHandlers.ofFile(
                target.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ),
        )
        if (response.statusCode() !in 200..299) {
            target.delete()
            throw IllegalStateException("下载安装包失败（HTTP ${response.statusCode()}）")
        }
    }

    private fun getText(url: String): String {
        val response = send(url, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("读取安装包校验文件失败（HTTP ${response.statusCode()}）")
        }
        return response.body()
    }

    private fun <T> send(url: String, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(30))
            .header("User-Agent", "MusicUnlock")
            .header("Accept", "application/octet-stream")
            .GET()
            .build()
        return client.send(request, handler)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

enum class UpdatePlatform {
    MACOS,
    WINDOWS,
    LINUX_DEB,
    LINUX_RPM,
}

fun currentPlatform(): UpdatePlatform {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") -> UpdatePlatform.MACOS
        os.contains("win") -> UpdatePlatform.WINDOWS
        linuxPrefersRpm() -> UpdatePlatform.LINUX_RPM
        else -> UpdatePlatform.LINUX_DEB
    }
}

private fun linuxPrefersRpm(): Boolean = runCatching {
    val text = File("/etc/os-release").takeIf(File::isFile)?.readText().orEmpty().lowercase()
    listOf("rhel", "fedora", "centos", "rocky", "almalinux", "opensuse", "suse").any(text::contains)
}.getOrDefault(false)
