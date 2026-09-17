package musicunlock

import musicunlock.library.LibraryEntry
import musicunlock.library.LibraryExportService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LibraryExportServiceTest {
    @Test
    fun `exports m3u8 and csv without losing commas or quotes`() {
        val dir = Files.createTempDirectory("musicunlock-library-export")
        val audio = dir.resolve("song.mp3").toFile().also { it.writeBytes(byteArrayOf(1)) }
        val entry = LibraryEntry(
            path = audio.absolutePath,
            size = audio.length(),
            modifiedAt = audio.lastModified(),
            title = "Song, \"Live\"",
            artist = "Artist",
            album = "Album",
            durationSeconds = 180,
            format = "MP3",
            bitRateKbps = 320,
            hasCover = true,
            hasLyrics = true,
        )
        val m3u8 = dir.resolve("library.m3u8").toFile()
        val csv = dir.resolve("library.csv").toFile()

        assertEquals(1, LibraryExportService.exportM3u8(listOf(entry), m3u8))
        assertEquals(1, LibraryExportService.exportCsv(listOf(entry), csv))
        assertTrue(m3u8.readText().contains("#EXTINF:180,Artist - Song, \"Live\""))
        assertTrue(csv.readText().contains("\"Song, \"\"Live\"\"\""))
    }
}
