package musicunlock

import com.google.gson.GsonBuilder
import musicunlock.backup.AppBackupService
import musicunlock.library.LibraryEntry
import musicunlock.library.LibraryIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppBackupServiceTest {
    @Test
    fun `backs up and restores library and task files`() {
        val dir = Files.createTempDirectory("musicunlock-backup")
        val audio = dir.resolve("song.mp3").toFile().also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val sourceIndex = dir.resolve("source-library.json").toFile()
        sourceIndex.writeText(
            GsonBuilder().create().toJson(
                listOf(
                    LibraryEntry(
                        path = audio.absolutePath,
                        size = audio.length(),
                        modifiedAt = audio.lastModified(),
                        title = "Song",
                        artist = "Artist",
                        album = null,
                        durationSeconds = 120,
                        format = "MP3",
                        bitRateKbps = 320,
                    ),
                ),
            ),
        )
        val sourceTasks = dir.resolve("source-tasks.json").toFile().also { it.writeText("[]") }
        val sourceConversions = dir.resolve("source-conversions.json").toFile().also { it.writeText("[]") }
        val backupFile = dir.resolve("backup.zip").toFile()

        val export = AppBackupService(
            library = LibraryIndex(sourceIndex),
            downloadTaskFile = sourceTasks,
            conversionTaskFile = sourceConversions,
        ).export(backupFile, includeSettings = false)
        assertTrue(backupFile.isFile)
        assertEquals(3, export.entryCount)

        val restoredTasks = dir.resolve("restored-tasks.json").toFile()
        val restoredConversions = dir.resolve("restored-conversions.json").toFile()
        val targetLibrary = LibraryIndex(dir.resolve("target-library.json").toFile())
        val restore = AppBackupService(
            library = targetLibrary,
            downloadTaskFile = restoredTasks,
            conversionTaskFile = restoredConversions,
        ).restore(
            source = backupFile,
            restoreSettings = false,
            restoreLibrary = true,
            restoreTasks = true,
        )

        assertEquals(1, restore.libraryImported)
        assertEquals(2, restore.taskFilesRestored)
        assertTrue(restore.warnings.isEmpty())
        assertEquals(audio.absolutePath, targetLibrary.all().single().path)
        assertEquals("[]", restoredTasks.readText())
        assertEquals("[]", restoredConversions.readText())
    }
}
