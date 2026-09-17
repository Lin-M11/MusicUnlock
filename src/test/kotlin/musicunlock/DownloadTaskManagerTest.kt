package musicunlock

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import musicunlock.library.LibraryIndex
import musicunlock.online.DownloadTaskManager
import musicunlock.online.DownloadTaskState
import musicunlock.online.HttpDownloader
import musicunlock.online.MusicAccount
import musicunlock.online.MusicLyrics
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicPlaylist
import musicunlock.online.MusicSong
import musicunlock.online.OnlineMusicProvider
import musicunlock.online.PlaybackSource
import musicunlock.online.toDownloadPreferences
import musicunlock.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

class DownloadTaskManagerTest {
    @Test
    fun `downloads a song and skips it after indexing`() = runBlocking {
        val payload = ByteArray(32 * 1024) { (it % 127).toByte() }.also {
            it[0] = 0x49
            it[1] = 0x44
            it[2] = 0x33
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/song") { exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val dir = Files.createTempDirectory("musicunlock-task")
            val settings = AppSettings(
                outputDir = dir.toString(),
                outputTemplate = "{artist}/{title}",
                downloadConcurrency = 1,
                downloadRetryCount = 0,
            )
            val library = LibraryIndex(dir.resolve("library.json").toFile())
            val provider = FakeProvider("http://127.0.0.1:${server.address.port}/song")
            val manager = DownloadTaskManager(
                library = library,
                settingsProvider = { settings },
                taskFile = dir.resolve("tasks.json").toFile(),
                providerResolver = { provider },
            )
            val song = fakeSong("song-1")
            val id = manager.enqueue(provider, song, dir.toFile(), settings.toDownloadPreferences())

            val completed = withTimeout(5_000L) {
                manager.tasks.first { tasks -> tasks.first { it.id == id }.state == DownloadTaskState.COMPLETED }
            }.first { it.id == id }
            assertEquals(DownloadTaskState.COMPLETED, completed.state)
            assertTrue(Path.of(completed.outputPath!!).toFile().isFile)

        } finally {
            server.stop(0)
        }
    }

    private fun fakeSong(id: String) = MusicSong(
        id = id,
        name = "Test Song",
        artists = listOf("Test Artist"),
        albumName = "Test Album",
        coverUrl = null,
    )
}

private class FakeProvider(private val url: String) : OnlineMusicProvider {
    override val platform = MusicPlatform.NETEASE
    override fun restoreSession(cookieHeader: String): MusicAccount = error("unused")
    override fun exportSessionCookie(): String? = null
    override fun logout() = Unit
    override fun account(): MusicAccount? = null
    override fun playlists(): List<MusicPlaylist> = emptyList()
    override fun songs(playlist: MusicPlaylist): List<MusicSong> = emptyList()
    override fun playback(song: MusicSong): PlaybackSource = PlaybackSource(url, "mp3", "128k", 128)
    override fun download(url: String, target: Path) = error("legacy download should not be used")
    override fun download(
        url: String,
        target: Path,
        offset: Long,
        onProgress: (Long, Long?, Long) -> Unit,
        shouldContinue: () -> Boolean,
    ) = HttpDownloader.download(
        settings = musicunlock.online.OnlineNetwork.settings(),
        url = url,
        target = target,
        offset = offset,
        onProgress = onProgress,
        shouldContinue = shouldContinue,
    )
    override fun lyrics(song: MusicSong): MusicLyrics? = null
    override fun bytes(url: String): ByteArray? = null
}
