package musicunlock.service

import musicunlock.core.MusicResult
import musicunlock.online.MusicLyrics
import musicunlock.settings.LyricsMode
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** 一次写入的完整标签集合。 */
data class AudioTagData(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val composer: String? = null,
    val isrc: String? = null,
    val lyrics: String? = null,
    val cover: ByteArray? = null,
    val platform: String? = null,
    val sourceSongId: String? = null,
    val quality: String? = null,
    val rating: Int? = null,
    val favorite: Boolean? = null,
)

/**
 * 写回音频元数据与封面(jaudiotagger)。
 * 写入失败只返回 false,由调用方决定是否告警,不应影响音频文件本身。
 */
object TagWriter {

    /** 将 NCM 容器中的元数据与封面写回音频文件。 */
    fun embed(audioFile: File, result: MusicResult): Boolean {
        return embedTags(
            audioFile = audioFile,
            title = result.musicName,
            artist = result.artist,
            album = result.album,
            cover = result.cover,
        )
    }

    /** 兼容旧调用入口。 */
    fun embedTags(
        audioFile: File,
        title: String?,
        artist: String?,
        album: String?,
        cover: ByteArray?,
    ): Boolean = embed(audioFile, AudioTagData(title = title, artist = artist, album = album, cover = cover))

    /** 将完整标签写回音频文件;成功返回 true,失败返回 false(不抛异常)。 */
    fun embed(audioFile: File, tags: AudioTagData): Boolean {
        return try {
            val audio = AudioFileIO.read(audioFile)
            val tag = audio.tag ?: audio.createDefaultTag()

            set(tag, FieldKey.ALBUM, tags.album)
            set(tag, FieldKey.TITLE, tags.title)
            set(tag, FieldKey.ARTIST, tags.artist)
            set(tag, FieldKey.ALBUM_ARTIST, tags.albumArtist ?: tags.artist)
            set(tag, FieldKey.TRACK, tags.trackNumber?.toString())
            set(tag, FieldKey.DISC_NO, tags.discNumber?.toString())
            set(tag, FieldKey.YEAR, tags.year?.toString())
            set(tag, FieldKey.GENRE, tags.genre)
            set(tag, FieldKey.COMPOSER, tags.composer)
            set(tag, FieldKey.ISRC, tags.isrc)
            set(tag, FieldKey.LYRICS, tags.lyrics)
            set(tag, FieldKey.QUALITY, tags.quality)
            if (tags.rating != null) {
                runCatching {
                    if (tags.rating > 0) tag.setField(FieldKey.RATING, tags.rating.toString())
                    else tag.deleteField(FieldKey.RATING)
                }
            }
            set(tag, FieldKey.CUSTOM2, tags.favorite?.let { if (it) "favorite=1" else "favorite=0" })
            if (!tags.platform.isNullOrBlank() || !tags.sourceSongId.isNullOrBlank()) {
                set(tag, FieldKey.CUSTOM1, listOfNotNull(tags.platform, tags.sourceSongId).joinToString(":"))
            }

            tags.cover?.takeIf { it.isNotEmpty() }?.let { bytes ->
                runCatching {
                    val image = ImageIO.read(ByteArrayInputStream(bytes))
                    if (image != null) {
                        val picture = MetadataBlockDataPicture(
                            bytes,
                            0,
                            mimeTypeOf(bytes),
                            "",
                            image.width,
                            image.height,
                            if (image.colorModel.hasAlpha()) 32 else 24,
                            0,
                        )
                        val artwork = ArtworkFactory.createArtworkFromMetadataBlockDataPicture(picture)
                        tag.setField(tag.createField(artwork))
                    }
                }
            }

            AudioFileIO.write(audio)
            true
        } catch (e: Exception) {
            println("警告: 写入元数据失败(不影响音频): ${audioFile.name} - ${e.message}")
            false
        }
    }

    fun writeLyrics(audioFile: File, lyrics: MusicLyrics?, mode: LyricsMode): Boolean {
        if (lyrics == null || lyrics.isEmpty || mode == LyricsMode.OFF) return false
        var ok = true
        if (mode == LyricsMode.SIDECAR || mode == LyricsMode.BOTH) {
            ok = ok && runCatching { writeLyricsFile(audioFile, lyrics) }.getOrDefault(false)
        }
        if (mode == LyricsMode.EMBED || mode == LyricsMode.BOTH) {
            ok = ok && embed(audioFile, AudioTagData(lyrics = mergedLyrics(lyrics)))
        }
        return ok
    }

    fun writeLyricsFile(audioFile: File, lyrics: MusicLyrics): Boolean = runCatching {
        val lrc = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
        lrc.writeText(mergedLyrics(lyrics))
        true
    }.getOrDefault(false)

    fun writeCoverSidecar(audioFile: File, cover: ByteArray?): Boolean = runCatching {
        if (cover == null || cover.isEmpty()) return false
        val target = File(audioFile.parentFile, "cover${coverExtension(cover)}")
        target.writeBytes(cover)
        true
    }.getOrDefault(false)

    fun mergedLyrics(lyrics: MusicLyrics): String = buildString {
        if (lyrics.original.isNotBlank()) appendLine(lyrics.original.trim())
        if (!lyrics.translated.isNullOrBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine("// 翻译")
            appendLine(lyrics.translated.trim())
        }
        if (!lyrics.romanized.isNullOrBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine("// 音译")
            appendLine(lyrics.romanized.trim())
        }
    }.trim()

    private fun set(tag: Tag, key: FieldKey, value: String?) {
        if (value.isNullOrBlank()) return
        runCatching { tag.setField(key, value) }
    }

    private fun mimeTypeOf(albumImage: ByteArray): String = when {
        albumImage.size >= 8 && albumImage[0] == 0x89.toByte() && albumImage[1] == 0x50.toByte() -> "image/png"
        albumImage.size >= 3 && albumImage[0] == 0xFF.toByte() && albumImage[1] == 0xD8.toByte() -> "image/jpeg"
        else -> mimeType(albumImage)
    }

    private fun coverExtension(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> ".png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> ".jpg"
        else -> ".img"
    }

    private fun mimeType(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        else -> "application/octet-stream"
    }
}
