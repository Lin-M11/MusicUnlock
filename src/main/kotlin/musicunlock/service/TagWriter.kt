package musicunlock.service

import musicunlock.core.MusicResult
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.flac.metadatablock.MetadataBlockDataPicture
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.ArtworkFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 将 NCM 容器中的元数据与封面写回音频文件(jaudiotagger)。
 */
object TagWriter {

    fun embed(audioFile: File, result: MusicResult) {
        val audio = AudioFileIO.read(audioFile)
        val tag = audio.tag ?: audio.createDefaultTag()

        result.album?.let { tag.setField(FieldKey.ALBUM, it) }
        result.musicName?.let { tag.setField(FieldKey.TITLE, it) }
        result.artist?.let { tag.setField(FieldKey.ARTIST, it) }

        result.cover?.takeIf { it.isNotEmpty() }?.let { cover ->
            val image = ImageIO.read(ByteArrayInputStream(cover))
            if (image != null) {
                val picture = MetadataBlockDataPicture(
                    cover,
                    0,
                    mimeTypeOf(cover),
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

        AudioFileIO.write(audio)
    }

    private fun mimeTypeOf(albumImage: ByteArray): String {
        // PNG 文件头
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (albumImage.size > 8) {
            for (i in 0 until 8) {
                if (albumImage[i] != png[i]) return "image/jpg"
            }
        }
        return "image/png"
    }
}
