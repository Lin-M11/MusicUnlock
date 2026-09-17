package musicunlock

import com.sun.net.httpserver.HttpServer
import musicunlock.update.ReleaseAsset
import musicunlock.update.ReleaseInfo
import musicunlock.update.UpdateDownloader
import musicunlock.update.UpdatePlatform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest

class UpdateDownloaderTest {
    @Test
    fun `downloads selected windows asset and verifies checksum`() {
        val payload = ByteArray(4096) { (it % 127).toByte() }
        val checksum = sha256(payload)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/MusicUnlock.exe") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/SHA256SUMS.txt") { exchange ->
            val body = "$checksum  ./MusicUnlock.exe\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val release = ReleaseInfo(
                version = "2.0.0",
                pageUrl = "$base/release",
                assets = listOf(
                    ReleaseAsset("MusicUnlock.exe", "$base/MusicUnlock.exe"),
                    ReleaseAsset("SHA256SUMS.txt", "$base/SHA256SUMS.txt"),
                ),
            )
            val target = Files.createTempDirectory("musicunlock-update").resolve("MusicUnlock.exe").toFile()
            val result = UpdateDownloader.download(release, target, UpdatePlatform.WINDOWS)

            assertTrue(result.checksumVerified)
            assertArrayEquals(payload, target.readBytes())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `checksum mismatch does not leave install package`() {
        val payload = byteArrayOf(1, 2, 3)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/MusicUnlock.exe") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.createContext("/SHA256SUMS.txt") { exchange ->
            val body = "${"0".repeat(64)}  MusicUnlock.exe\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val release = ReleaseInfo(
                version = "2.0.0",
                pageUrl = "$base/release",
                assets = listOf(
                    ReleaseAsset("MusicUnlock.exe", "$base/MusicUnlock.exe"),
                    ReleaseAsset("SHA256SUMS.txt", "$base/SHA256SUMS.txt"),
                ),
            )
            val target = Files.createTempDirectory("musicunlock-update-bad").resolve("MusicUnlock.exe").toFile()
            assertThrows(IllegalStateException::class.java) {
                UpdateDownloader.download(release, target, UpdatePlatform.WINDOWS)
            }
            assertTrue(!target.exists())
        } finally {
            server.stop(0)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
