package musicunlock

import musicunlock.library.LibraryEntry
import musicunlock.settings.LibrarySyncProfile
import musicunlock.settings.SyncDestinationType
import musicunlock.settings.SyncMode
import musicunlock.sync.LibrarySyncService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

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

    @Test
    fun `exclude patterns treat regex metacharacters as literals`() {
        val dir = Files.createTempDirectory("musicunlock-sync-meta")
        val source = sample(dir, "C++ (Live) [2024].mp3")

        val result = LibrarySyncService().sync(profile(dir, listOf("C++ (Live) [2024].mp3")), listOf(entry(source)))

        assertTrue("排除规则不应让同步报错：${result.errors}", result.errors.isEmpty())
        assertEquals(0, result.uploaded)
    }

    @Test
    fun `exclude patterns treat character classes as literals`() {
        val dir = Files.createTempDirectory("musicunlock-sync-bracket")
        val source = sample(dir, "[Remix] track.mp3")

        val result = LibrarySyncService().sync(profile(dir, listOf("[Remix]*")), listOf(entry(source)))

        assertTrue("排除规则不应让同步报错：${result.errors}", result.errors.isEmpty())
        assertEquals(0, result.uploaded)
    }

    @Test
    fun `exclude patterns keep wildcard semantics`() {
        val dir = Files.createTempDirectory("musicunlock-sync-wildcard")
        val source = sample(dir, "Live At Home.mp3")

        val result = LibrarySyncService().sync(profile(dir, listOf("*live*")), listOf(entry(source)))

        assertTrue("排除规则不应让同步报错：${result.errors}", result.errors.isEmpty())
        assertEquals(0, result.uploaded)
    }

    @Test
    fun `entries outside exclude patterns are still uploaded`() {
        val dir = Files.createTempDirectory("musicunlock-sync-keep")
        val source = sample(dir, "C++ (Live) [2024].mp3")

        val result = LibrarySyncService().sync(profile(dir, listOf("*.flac")), listOf(entry(source)))

        assertTrue("排除规则不应让同步报错：${result.errors}", result.errors.isEmpty())
        assertEquals(1, result.uploaded)
    }

    private fun sample(dir: Path, name: String): File =
        dir.resolve(name).toFile().also { it.writeBytes(ByteArray(128) { 1 }) }

    private fun entry(source: File) = LibraryEntry(
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

    private fun profile(dir: Path, excludePatterns: List<String>) = LibrarySyncProfile(
        id = "local",
        name = "Device",
        destinationType = SyncDestinationType.LOCAL_FOLDER,
        localPath = dir.resolve("device").toString(),
        mode = SyncMode.MIRROR,
        excludePatterns = excludePatterns,
    )
}
