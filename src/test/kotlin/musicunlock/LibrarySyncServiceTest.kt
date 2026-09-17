package musicunlock

import musicunlock.library.LibraryEntry
import musicunlock.settings.LibrarySyncProfile
import musicunlock.settings.SyncDestinationType
import musicunlock.settings.SyncMode
import musicunlock.sync.LibrarySyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LibrarySyncServiceTest {
    @Test
    fun `syncs library files and writes manifest`() {
        val dir = Files.createTempDirectory("musicunlock-sync")
        val source = dir.resolve("source.mp3").toFile().also { it.writeBytes(ByteArray(128) { 1 }) }
        val destination = dir.resolve("device")
        val entry = LibraryEntry(
            path = source.absolutePath,
            size = source.length(),
            modifiedAt = source.lastModified(),
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationSeconds = 180,
            format = "MP3",
            bitRateKbps = 320,
        )
        val profile = LibrarySyncProfile(
            id = "local",
            name = "Device",
            destinationType = SyncDestinationType.LOCAL_FOLDER,
            localPath = destination.toString(),
            mode = SyncMode.MIRROR,
        )

        val first = LibrarySyncService().sync(profile, listOf(entry))
        assertEquals(1, first.uploaded)
        assertTrue(destination.resolve("Artist/Album/Song.mp3").toFile().isFile)
        assertTrue(destination.resolve(".musicunlock-sync.json").toFile().isFile)

        val second = LibrarySyncService().sync(profile, listOf(entry))
        assertEquals(1, second.skipped)
        assertEquals(0, second.uploaded)
    }
}
