package musicunlock

import musicunlock.online.MusicSong
import musicunlock.online.OutputTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutputTemplateTest {
    private val song = MusicSong(
        id = "1",
        name = "Night Drive",
        artists = listOf("A/B", "C"),
        albumName = "Album: One",
        coverUrl = null,
        durationSeconds = 180,
        trackNumber = 3,
        discNumber = 1,
        year = 2026,
        genre = "Rock",
    )

    @Test
    fun `renders nested template and numeric padding`() {
        val value = OutputTemplate.render(
            "{artist}/{album}/{track:02} - {title}.{ext}",
            song,
            platform = "netease",
            qualityLabel = "无损",
        )
        assertEquals("A_B _ C${java.io.File.separator}Album_ One${java.io.File.separator}03 - Night Drive.{ext}", value)
    }

    @Test
    fun `sanitizes traversal and reserved characters`() {
        val unsafe = song.copy(name = "../bad:name", artists = listOf(".."))
        val value = OutputTemplate.render("{artist}/{title}", unsafe, "qq")
        assertFalse(value.contains(".."))
        assertFalse(value.contains(":"))
        assertFalse(value.startsWith("/"))
    }
}
