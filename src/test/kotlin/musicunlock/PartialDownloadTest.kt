package musicunlock

import musicunlock.online.PartialDownloadStore
import musicunlock.online.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PartialDownloadTest {
    @Test
    fun `same source resumes while changed quality discards old part`() {
        val dir = Files.createTempDirectory("musicunlock-partial")
        val first = PlaybackSource(
            url = "https://cdn-a.example/song.flac?token=one",
            formatHint = "flac",
            qualityLabel = "无损",
            bitrateKbps = 900,
            lossless = true,
            contentLengthBytes = 10_000,
        )
        val initial = PartialDownloadStore.prepare(dir.toFile(), "seed", first)
        initial.part.writeBytes(ByteArray(512) { 1 })

        val resumed = PartialDownloadStore.prepare(
            dir.toFile(),
            "seed",
            first.copy(url = "https://cdn-b.example/song.flac?token=two"),
        )
        assertEquals(512L, resumed.offset)
        assertTrue(resumed.part.isFile)

        val changed = PartialDownloadStore.prepare(
            dir.toFile(),
            "seed",
            first.copy(url = "https://cdn-b.example/song.mp3?token=three", formatHint = "mp3", qualityLabel = "320k", bitrateKbps = 320, lossless = false),
        )
        assertEquals(0L, changed.offset)
        assertFalse(changed.part.exists())
        assertTrue(changed.metadataFile.isFile)
    }
}
