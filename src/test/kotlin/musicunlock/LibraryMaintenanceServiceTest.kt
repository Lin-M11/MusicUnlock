package musicunlock

import musicunlock.library.EditableTagField
import musicunlock.library.EditableTags
import musicunlock.library.LibraryEntry
import musicunlock.library.LibraryIndex
import musicunlock.library.LibraryMaintenanceService
import musicunlock.library.isInsideDirectory
import musicunlock.service.AudioTagData
import musicunlock.service.TagWriter
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 标签写入的字段范围语义：未勾选的字段必须保持原样。 */
class LibraryMaintenanceServiceTest {

    @Test
    fun `empty field selection leaves tags untouched`() {
        val file = wav("untouched.wav")
        TagWriter.embed(file, AudioTagData(title = "原始标题"))

        val applied = service().updateTags(entry(file), EditableTags(title = "新标题", artist = "新歌手"), emptySet())

        assertFalse("未勾选字段时不应写入", applied)
        assertEquals("原始标题", field(file, FieldKey.TITLE))
    }

    @Test
    fun `unselected fields survive a write`() {
        val file = wav("partial.wav")
        TagWriter.embed(file, AudioTagData(title = "原始标题", artist = "原始歌手"))

        val applied = service().updateTags(
            entry(file),
            EditableTags(title = "新标题", artist = ""),
            setOf(EditableTagField.TITLE),
        )

        assertTrue(applied)
        assertEquals("新标题", field(file, FieldKey.TITLE))
        assertEquals("原始歌手", field(file, FieldKey.ARTIST))
    }

    @Test
    fun `favorite toggles and can be cleared`() {
        val file = wav("favorite.wav")
        val service = service()
        val entry = entry(file)

        service.updateTags(entry, EditableTags(favorite = true), setOf(EditableTagField.FAVORITE))
        assertEquals("favorite=1", field(file, FieldKey.CUSTOM2))
        assertEquals(true, service.snapshotTags(entry)?.favorite)

        // 标记缺失时快照给出 null，撤销必须能据此把标记删掉而不是留着旧值
        service.updateTags(entry, EditableTags(favorite = null), setOf(EditableTagField.FAVORITE))
        assertNull(field(file, FieldKey.CUSTOM2))
        assertEquals(false, service.snapshotTags(entry)?.favorite)
    }

    @Test
    fun `directory containment respects separator boundaries`() {
        val root = Files.createTempDirectory("musicunlock-library").toFile()
        val inside = File(root, "album/song.mp3")
        val sibling = File(root.parentFile, "${root.name}2/song.mp3")

        assertTrue(isInsideDirectory(inside.path, root))
        assertTrue(isInsideDirectory(root.path, root))
        assertFalse(isInsideDirectory(sibling.path, root))
        assertFalse(isInsideDirectory(File(root.parentFile, "other.mp3").path, root))
    }

    private fun service() = LibraryMaintenanceService(
        LibraryIndex(Files.createTempDirectory("musicunlock-index").resolve("library.sqlite").toFile()),
    )

    private fun wav(name: String): File =
        File(Files.createTempDirectory("musicunlock-tags").toFile(), name)
            .also { it.writeBytes(TestAudio.pcmWav()) }

    private fun entry(file: File) = LibraryEntry(
        path = file.absolutePath,
        size = file.length(),
        modifiedAt = file.lastModified(),
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationSeconds = 1,
        format = "WAV",
    )

    private fun field(file: File, key: FieldKey): String? =
        runCatching { AudioFileIO.read(file).tag?.getFirst(key) }.getOrNull()?.takeIf(String::isNotBlank)
}
