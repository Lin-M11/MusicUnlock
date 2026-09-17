package musicunlock

import musicunlock.library.LibraryEntry
import musicunlock.library.LibraryIndex
import musicunlock.library.SmartPlaylistKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class LibraryIndexSqliteTest {
    @Test
    fun `indexes searches playlists history and milestones`() {
        val dir = Files.createTempDirectory("musicunlock-sqlite-library")
        val audio = dir.resolve("Song.wav").toFile()
        writeWav(audio)
        val index = LibraryIndex(dir.resolve("library.json").toFile())

        val entry = index.upsert(audio)
        assertEquals(audio.absolutePath, entry?.path)
        assertEquals(1, index.search("Song").size)

        index.toggleFavorite(audio.absolutePath, true)
        index.setRating(audio.absolutePath, 5)
        index.recordPlay(audio.absolutePath)
        val saved = index.get(audio.absolutePath)!!
        assertTrue(saved.isFavorite)
        assertEquals(5, saved.rating)
        assertEquals(1, saved.playCount)
        assertEquals(1, index.smartPlaylist(SmartPlaylistKind.FAVORITES).size)
        assertEquals(1, index.smartPlaylist(SmartPlaylistKind.MOST_PLAYED).size)

        val playlist = index.createPlaylist("测试歌单")
        assertEquals(1, index.addToPlaylist(playlist.id, listOf(audio.absolutePath)))
        assertEquals(audio.absolutePath, index.playlistTracks(playlist.id).single().path)
        index.deletePlaylist(playlist.id)
        assertTrue(index.playlists().isEmpty())
    }

    @Test
    fun `migrates legacy json and groups acoustic duplicates`() {
        val dir = Files.createTempDirectory("musicunlock-sqlite-migrate")
        val first = dir.resolve("first.mp3").toFile().also { it.writeBytes(byteArrayOf(1)) }
        val second = dir.resolve("second.flac").toFile().also { it.writeBytes(byteArrayOf(2)) }
        val entries = listOf(
            LibraryEntry(first.absolutePath, 1, 1, "Song", "Artist", "Album", 180, format = "MP3", contentHash = "same"),
            LibraryEntry(second.absolutePath, 1, 1, "Song", "Artist", "Album", 180, format = "FLAC", contentHash = "same"),
        )
        val json = dir.resolve("legacy.json").toFile()
        json.writeText(com.google.gson.Gson().toJson(entries))

        val index = LibraryIndex(json)
        assertEquals(2, index.all().size)
        assertEquals(1, index.duplicateGroups().size)
        assertFalse(index.search("Artist").isEmpty())
    }

    @Test
    fun `relinks playlist history and full text search paths`() {
        val dir = Files.createTempDirectory("musicunlock-sqlite-relink")
        val oldRoot = dir.resolve("old").toFile().apply { mkdirs() }
        val newRoot = dir.resolve("new")
        val audio = oldRoot.resolve("Song.wav")
        writeWav(audio)
        val index = LibraryIndex(dir.resolve("library.json").toFile())
        index.upsert(audio)
        val playlist = index.createPlaylist("测试歌单")
        assertEquals(1, index.addToPlaylist(playlist.id, listOf(audio.absolutePath)))
        index.recordPlay(audio.absolutePath)

        val newPath = newRoot.resolve(audio.name).toFile().absolutePath
        assertEquals(1, index.relink(oldRoot.absolutePath, newRoot.toFile().absolutePath))

        assertEquals(newPath, index.playlistTracks(playlist.id).single().path)
        assertEquals(newPath, index.playHistory().single().first)
        assertTrue(index.search("Song").any { it.path == newPath })
    }

    private fun writeWav(target: java.io.File) {
        val format = AudioFormat(8_000f, 16, 1, true, false)
        val data = ByteArray(8_000)
        AudioInputStream(ByteArrayInputStream(data), format, (data.size / format.frameSize).toLong()).use { stream ->
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, target)
        }
    }
}
