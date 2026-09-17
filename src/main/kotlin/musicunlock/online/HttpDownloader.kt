package musicunlock.online

import musicunlock.settings.AppSettings
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import kotlin.math.max

/** 带 Range 恢复、限速、进度和统一错误分类的 HTTP 文件下载实现。 */
object HttpDownloader {

    fun download(
        settings: AppSettings,
        url: String,
        target: Path,
        headers: Map<String, String> = emptyMap(),
        offset: Long = 0L,
        onProgress: (downloaded: Long, total: Long?, bytesPerSecond: Long) -> Unit = { _, _, _ -> },
        shouldContinue: () -> Boolean = { true },
    ) {
        val existing = if (offset > 0 && Files.isRegularFile(target)) Files.size(target) else 0L
        val resumeFrom = existing.coerceAtLeast(0L)
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(settings.downloadTimeoutSeconds.coerceAtLeast(5)))
            .GET()
        headers.forEach { (name, value) -> builder.header(name, value) }
        if (resumeFrom > 0) builder.header("Range", "bytes=$resumeFrom-")

        val response = try {
            OnlineNetwork.client(settings).send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ClassifiedDownloadException(DownloadErrorKind.CANCELLED, "下载已暂停", true, e)
        } catch (e: Exception) {
            throw classifyNetwork(e)
        }

        val status = response.statusCode()
        if (status == 416 && resumeFrom > 0) {
            val total = response.headers().firstValue("Content-Range").orElse(null)
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
            if (total == null || resumeFrom >= total) {
                onProgress(resumeFrom, total ?: resumeFrom, 0L)
                return
            }
        }
        if (status !in 200..299) {
            throw classifyHttp(status, "下载失败（HTTP $status）")
        }

        if (status == 206 && resumeFrom > 0) {
            val contentRange = response.headers().firstValue("Content-Range").orElse(null)
            val rangeStart = contentRange?.substringBefore('-')?.substringAfter(' ')?.toLongOrNull()
            if (rangeStart != resumeFrom) {
                Files.deleteIfExists(target)
                throw ClassifiedDownloadException(
                    DownloadErrorKind.NETWORK,
                    "服务器返回的断点位置不一致，已丢弃旧分片并将重新下载",
                    true,
                )
            }
        }

        val append = status == 206 && resumeFrom > 0
        if (!append && resumeFrom > 0) {
            Files.deleteIfExists(target)
        }
        Files.createDirectories(target.parent)
        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            .takeIf { it >= 0L }
        val total = when {
            status == 206 -> response.headers().firstValue("Content-Range").orElse(null)
                ?.substringAfterLast('/', "")
                ?.toLongOrNull()
                ?.takeIf { it >= 0L }
            contentLength != null && append -> resumeFrom + contentLength
            contentLength != null -> contentLength
            else -> null
        }

        var downloaded = if (append) Files.size(target) else 0L
        val startedAt = System.nanoTime()
        var lastReportAt = startedAt
        var lastBytes = downloaded
        val startAt = downloaded
        val throttle = SpeedThrottle(settings.downloadSpeedLimitKbps)
        response.body().use { input ->
            val options = if (append) {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
            } else {
                arrayOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            }
            Files.newOutputStream(target, *options).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (!shouldContinue() || Thread.currentThread().isInterrupted) {
                        throw ClassifiedDownloadException(DownloadErrorKind.CANCELLED, "下载已暂停", true)
                    }
                    val read = input.read(buffer)
                    if (read < 0) break
                    throttle.beforeWrite(output, read)
                    output.write(buffer, 0, read)
                    downloaded += read
                    val now = System.nanoTime()
                    if (now - lastReportAt >= REPORT_INTERVAL_NANOS) {
                        val elapsed = max(1L, now - startedAt)
                        val instantElapsed = max(1L, now - lastReportAt)
                        val instantSpeed = (downloaded - lastBytes) * 1_000_000_000L / instantElapsed
                        val averageSpeed = (downloaded - startAt) * 1_000_000_000L / elapsed
                        onProgress(downloaded, total, if (instantSpeed > 0) instantSpeed else averageSpeed)
                        lastReportAt = now
                        lastBytes = downloaded
                    }
                }
                output.flush()
            }
        }
        val elapsed = max(1L, System.nanoTime() - startedAt)
        if (total != null && downloaded < total) {
            throw ClassifiedDownloadException(
                DownloadErrorKind.NETWORK,
                "下载连接提前结束：已接收 $downloaded / $total 字节",
                true,
            )
        }
        val average = (downloaded - startAt) * 1_000_000_000L / elapsed
        onProgress(downloaded, total, average.coerceAtLeast(0L))
    }

    private fun classifyNetwork(error: Exception): ClassifiedDownloadException {
        val text = error.message.orEmpty()
        val lower = text.lowercase()
        return when {
            lower.contains("unknownhost") || lower.contains("connect") || lower.contains("timed out") ||
                lower.contains("timeout") || lower.contains("network") || lower.contains("ssl") ->
                ClassifiedDownloadException(DownloadErrorKind.NETWORK, "网络连接失败：$text", true, error)
            else -> ClassifiedDownloadException(DownloadErrorKind.UNKNOWN, "下载失败：${text.ifBlank { error.toString() }}", true, error)
        }
    }

    fun classifyHttp(status: Int, fallback: String): ClassifiedDownloadException = when (status) {
        401, 403 -> ClassifiedDownloadException(DownloadErrorKind.AUTH, "登录状态已失效或账号无访问权限（HTTP $status）", false)
        402 -> ClassifiedDownloadException(DownloadErrorKind.RIGHTS, "当前账号没有该歌曲的下载权限（HTTP 402）", false)
        404 -> ClassifiedDownloadException(DownloadErrorKind.RIGHTS, "资源不存在或已下架（HTTP 404）", false)
        408, 425, 429 -> ClassifiedDownloadException(DownloadErrorKind.NETWORK, "服务器繁忙或请求受限（HTTP $status）", true)
        in 500..599 -> ClassifiedDownloadException(DownloadErrorKind.SERVER, "音乐服务暂时不可用（HTTP $status）", true)
        else -> ClassifiedDownloadException(DownloadErrorKind.UNKNOWN, "$fallback（HTTP $status）", false)
    }

    private class SpeedThrottle(limitKbps: Int) {
        private val bytesPerSecond = limitKbps.coerceAtLeast(0) * 1024L
        private var startedAt = System.nanoTime()
        private var bytesSent = 0L

        fun beforeWrite(output: OutputStream, nextBytes: Int) {
            if (bytesPerSecond <= 0L) return
            val now = System.nanoTime()
            val elapsedNanos = now - startedAt
            val expectedNanos = bytesSent * 1_000_000_000L / bytesPerSecond
            val waitNanos = expectedNanos - elapsedNanos
            if (waitNanos > 0L) {
                val millis = waitNanos / 1_000_000L
                val nanos = (waitNanos % 1_000_000L).toInt()
                try {
                    Thread.sleep(millis, nanos)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ClassifiedDownloadException(DownloadErrorKind.CANCELLED, "下载已暂停", true, e)
                }
            }
            bytesSent += nextBytes
            if (elapsedNanos > 10_000_000_000L) {
                startedAt = now
                bytesSent = 0L
            }
        }
    }

    private const val REPORT_INTERVAL_NANOS = 250_000_000L
}
