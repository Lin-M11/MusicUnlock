package musicunlock

import musicunlock.online.DownloadPreflight
import musicunlock.online.DownloadPreflightIssueKind
import musicunlock.online.MusicAccount
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicPlaylist
import musicunlock.online.MusicSong
import musicunlock.online.OnlineMusicProvider
import musicunlock.online.PlaybackSource
import musicunlock.settings.QualityStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class DownloadPreflightTest {
    @Test
    fun `reports quality size and unavailable reason`() {
        val dir = Files.createTempDirectory("musicunlock-preflight")
        val song = MusicSong(
            id = "ok",
            name = "Song",
            artists = listOf("Artist"),
            albumName = null,
            coverUrl = null,
            durationSeconds = 180,
        )
        val unavailable = song.copy(id = "blocked", name = "Blocked")
        val report = DownloadPreflight.inspect(
            provider = PreflightProvider(),
            songs = listOf(song, unavailable),
            outputDir = dir.toFile(),
            quality = QualityStrategy.HIGHEST,
        )

        assertEquals(1, report.availableCount)
        assertEquals(1, report.unavailableCount)
        assertEquals(4_800_000L, report.items.first().estimatedBytes)
        assertEquals(DownloadPreflightIssueKind.AUTH, report.items[1].issueKind)
        assertTrue(report.enoughSpace == true)
    }
}

private class PreflightProvider : OnlineMusicProvider {
    override val platform = MusicPlatform.NETEASE
    override fun restoreSession(cookieHeader: String): MusicAccount = error("unused")
    override fun exportSessionCookie(): String? = null
    override fun logout() = Unit
    override fun account(): MusicAccount? = null
    override fun playlists(): List<MusicPlaylist> = emptyList()
    override fun songs(playlist: MusicPlaylist): List<MusicSong> = emptyList()
    override fun playback(song: MusicSong): PlaybackSource = playback(song, QualityStrategy.HIGHEST)
    override fun playback(song: MusicSong, quality: QualityStrategy): PlaybackSource {
        if (song.id == "blocked") error("login token expired")
        return PlaybackSource(
            url = "https://example.test/song.mp3",
            formatHint = "mp3",
            qualityLabel = "320k",
            bitrateKbps = 320,
            contentLengthBytes = 4_800_000L,
        )
    }
    override fun download(url: String, target: Path) = error("unused")
    override fun bytes(url: String): ByteArray? = null
}
