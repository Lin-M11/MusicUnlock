package musicunlock

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import musicunlock.online.MusicAccount
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicPlaylist
import musicunlock.online.MusicSong
import musicunlock.online.OnlineMusicProvider
import musicunlock.online.PlaybackSource
import musicunlock.player.AudioPlaybackEngine
import musicunlock.player.AudioPlayerService
import musicunlock.player.PlayerPlaybackState
import musicunlock.player.PlayerTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class AudioPlayerServiceTest {
    @Test
    fun `loads cached wav and controls playback state`() = runBlocking {
        val dir = Files.createTempDirectory("musicunlock-player")
        val wav = dir.resolve("source.wav").toFile()
        writeSilentWav(wav)
        val engine = FakePlaybackEngine()
        val service = AudioPlayerService(
            cacheDir = dir.resolve("cache").toFile(),
            engine = engine,
            providerResolver = { FakePlaybackProvider(wav.readBytes()) },
        )
        val track = PlayerTrack(
            platformId = "netease",
            song = MusicSong("1", "Song", listOf("Artist"), null, null, durationSeconds = 2),
        )

        service.playQueue(listOf(track))
        val playing = withTimeout(5_000L) {
            service.state.first { it.state == PlayerPlaybackState.PLAYING }
        }
        assertEquals("Song", playing.current?.song?.name)
        assertTrue(engine.playing)

        service.toggle()
        assertTrue(!engine.playing)
        assertEquals(PlayerPlaybackState.PAUSED, service.state.value.state)
        service.setVolume(0.4f)
        assertEquals(0.4f, service.state.value.volume)
        service.stop()
        assertEquals(PlayerPlaybackState.IDLE, service.state.value.state)
        service.close()
    }

    private fun writeSilentWav(target: java.io.File) {
        val format = AudioFormat(8_000f, 16, 1, true, false)
        val data = ByteArray(16_000)
        AudioInputStream(ByteArrayInputStream(data), format, (data.size / format.frameSize).toLong()).use { stream ->
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, target)
        }
    }
}

private class FakePlaybackEngine : AudioPlaybackEngine {
    var playing = false
    private var position = 0L

    override fun load(file: java.io.File) = Unit
    override fun play() { playing = true }
    override fun pause() { playing = false }
    override fun stop() { playing = false; position = 0L }
    override fun seekTo(millis: Long) { position = millis }
    override fun setVolume(volume: Float) = Unit
    override fun positionMillis(): Long = position
    override fun durationMillis(): Long = 2_000L
    override fun isPlaying(): Boolean = playing
    override fun close() { playing = false }
}

private class FakePlaybackProvider(private val bytes: ByteArray) : OnlineMusicProvider {
    override val platform = MusicPlatform.NETEASE
    override fun restoreSession(cookieHeader: String): MusicAccount = error("unused")
    override fun exportSessionCookie(): String? = null
    override fun logout() = Unit
    override fun account(): MusicAccount? = null
    override fun playlists(): List<MusicPlaylist> = emptyList()
    override fun songs(playlist: MusicPlaylist): List<MusicSong> = emptyList()
    override fun playback(song: MusicSong): PlaybackSource = PlaybackSource("test://song", "wav")
    override fun download(url: String, target: Path) {
        Files.write(target, bytes)
    }
    override fun bytes(url: String): ByteArray? = null
}
