package musicunlock

import com.sun.net.httpserver.HttpServer
import musicunlock.online.HttpDownloader
import musicunlock.settings.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files

class HttpDownloaderTest {
    @Test
    fun `resumes with range and writes complete file`() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        var observedRange: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/audio") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            observedRange = range
            val start = range?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()?.toInt() ?: 0
            val body = payload.copyOfRange(start, payload.size)
            exchange.responseHeaders.add("Content-Range", "bytes $start-${payload.lastIndex}/${payload.size}")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val dir = Files.createTempDirectory("musicunlock-http")
            val target = dir.resolve("song.part")
            val prefix = payload.copyOfRange(0, 4096)
            Files.write(target, prefix)
            HttpDownloader.download(
                settings = AppSettings(downloadTimeoutSeconds = 20),
                url = "http://127.0.0.1:${server.address.port}/audio",
                target = target,
                offset = prefix.size.toLong(),
            )
            assertEquals("bytes=4096-", observedRange)
            assertArrayEquals(payload, Files.readAllBytes(target))
        } finally {
            server.stop(0)
        }
    }
}
