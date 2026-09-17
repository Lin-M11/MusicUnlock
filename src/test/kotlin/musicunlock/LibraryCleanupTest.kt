package musicunlock

import musicunlock.library.DuplicateCleanupPlanner
import musicunlock.library.DuplicateMatchKind
import musicunlock.library.LibraryCleanupService
import musicunlock.library.LibraryEntry
import musicunlock.library.LibraryIndex
import musicunlock.library.ManagedLibraryTrashService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LibraryCleanupTest {
    @Test
    fun `planner keeps higher quality file and managed trash can undo`() {
        val dir = Files.createTempDirectory("musicunlock-cleanup")
        val mp3 = dir.resolve("song.mp3").toFile().also { it.writeBytes(ByteArray(128)) }
        val flac = dir.resolve("song.flac").toFile().also { it.writeBytes(ByteArray(256)) }
        val highQuality = LibraryEntry(
            path = flac.absolutePath,
            size = flac.length(),
            modifiedAt = flac.lastModified(),
            title = "Song",
            artist = "Artist",
            album = "Album",
            durationSeconds = 180,
            hasCover = true,
            hasLyrics = true,
            contentHash = "same-content",
            format = "FLAC",
            bitRateKbps = 900,
        )
        val lowQuality = highQuality.copy(
            path = mp3.absolutePath,
            size = mp3.length(),
            modifiedAt = mp3.lastModified(),
            hasCover = false,
            hasLyrics = false,
            format = "MP3",
            bitRateKbps = 128,
        )
        val plan = DuplicateCleanupPlanner.plan(listOf(listOf(lowQuality, highQuality)))

        assertEquals(highQuality.path, plan.decisions.single().keepPath)
        assertEquals(DuplicateMatchKind.EXACT, plan.decisions.single().matchKind)
        assertTrue(plan.decisions.single().reason.contains("无损"))

        val service = LibraryCleanupService(
            index = LibraryIndex(dir.resolve("library.json").toFile()),
            trash = ManagedLibraryTrashService(dir.resolve("trash").toFile()),
            journalDir = dir.resolve("journal").toFile(),
        )
        val outcome = service.apply(plan)
        assertEquals(1, outcome.movedTracks)
        assertFalse(mp3.exists())
        assertTrue(flac.exists())

        val undo = service.undo(outcome.id)
        assertEquals(1, undo.restoreCount)
        assertTrue(undo.failed.isEmpty())
        assertTrue(mp3.exists())
    }

    @Test
    fun `likely duplicates require explicit confirmation`() {
        val dir = Files.createTempDirectory("musicunlock-cleanup-likely")
        val first = LibraryEntry(
            path = dir.resolve("first.mp3").toFile().also { it.writeBytes(byteArrayOf(1)) }.absolutePath,
            size = 1,
            modifiedAt = 0,
            title = "Song",
            artist = "Artist",
            album = null,
            durationSeconds = 120,
            format = "MP3",
            bitRateKbps = 320,
        )
        val second = first.copy(
            path = dir.resolve("second.mp3").toFile().also { it.writeBytes(byteArrayOf(2)) }.absolutePath,
            size = 1,
            modifiedAt = 0,
            bitRateKbps = 128,
        )
        val plan = DuplicateCleanupPlanner.plan(listOf(listOf(first, second)))
        assertEquals(DuplicateMatchKind.LIKELY, plan.decisions.single().matchKind)

        val service = LibraryCleanupService(
            index = LibraryIndex(dir.resolve("library.json").toFile()),
            trash = ManagedLibraryTrashService(dir.resolve("trash").toFile()),
            journalDir = dir.resolve("journal").toFile(),
        )
        assertEquals(0, service.apply(plan).movedTracks)
        assertTrue(service.apply(plan, allowLikelyDuplicates = true).movedTracks > 0)
    }
}
