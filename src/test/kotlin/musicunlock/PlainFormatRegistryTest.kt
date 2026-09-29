package musicunlock

import musicunlock.core.Formats
import musicunlock.core.PlainAudioDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 原始音频格式的注册表与直通解码行为。 */
class PlainFormatRegistryTest {

    private val lossless = listOf("wav", "wave", "flac", "ape", "alac", "aif", "aiff", "au", "wv", "dff")

    private val lossy = listOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wma")

    @Test
    fun commonRawFormatsAreSupported() {
        (lossless + lossy).forEach { ext ->
            assertTrue(Formats.isSupported("song.$ext"), "$ext 应作为输入格式被支持")
        }
    }

    @Test
    fun rawFormatsUsePlainDecoderCaseInsensitively() {
        assertTrue(Formats.isSupported("SONG.WAV"))
        assertSame(PlainAudioDecoder, Formats.get("song.WaV"))
        assertSame(PlainAudioDecoder, Formats.get("song.FLAC"))
    }

    @Test
    fun encryptedAndRawFormatsAreDistinguished() {
        assertTrue(Formats.isEncryptedExtension("ncm"))
        assertTrue(Formats.isEncryptedExtension("mflac"))
        assertFalse(Formats.isEncryptedExtension("wav"))
        assertFalse(Formats.isEncryptedExtension("mp3"))

        assertTrue(Formats.isPlainExtension("wav"))
        assertTrue(Formats.isPlainExtension("MP3"))
        assertFalse(Formats.isPlainExtension("ncm"))
        assertFalse(Formats.isPlainExtension("txt"))

        assertTrue(Formats.encryptedExtensions().contains("ncm"))
        assertFalse(Formats.encryptedExtensions().contains("wav"))
        assertTrue(Formats.plainExtensions().containsAll(lossless + lossy))
        assertEquals(
            Formats.encryptedExtensions().size + Formats.plainExtensions().size,
            Formats.supportedExtensions().size,
        )
    }

    @Test
    fun unsupportedExtensionsStayRejected() {
        listOf("song.txt", "song.doc", "song.zip", "song").forEach { name ->
            assertFalse(Formats.isSupported(name), "$name 不应被支持")
        }
    }

    @Test
    fun plainDecoderPassesBytesThroughAndSniffsFormat() {
        val wav = TestAudio.pcmWav()

        val result = PlainAudioDecoder.decode(wav, "tone.wav")

        assertSame(wav, result.data, "原始格式应原样透传字节")
        assertEquals("wav", result.ext)
        assertEquals("wav", PlainAudioDecoder.outputExtension(wav, "tone.wav"))
    }

    @Test
    fun sniffedContainerWinsOverFileNameExtension() {
        val flacHeader = byteArrayOf(0x66, 0x4C, 0x61, 0x43) + ByteArray(16)
        assertEquals("flac", PlainAudioDecoder.outputExtension(flacHeader, "mislabelled.wav"))
    }
}
