package musicunlock

import musicunlock.service.AudioTranscoder
import musicunlock.service.MusicConverter
import musicunlock.settings.OutputFormat
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 端到端测试:用 unlock-music 的真实样本构造 .mflac 加密文件,
 * 走 MusicConverter 完整管道(格式识别 -> 解密 -> 输出文件)。
 */
class MusicConverterIntegrationTest {

    private fun load(name: String): ByteArray {
        val stream = checkNotNull(javaClass.getResourceAsStream("/testdata/$name")) { "missing $name" }
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val len = input.read(buf)
                if (len <= 0) break
                out.write(buf, 0, len)
            }
            return out.toByteArray()
        }
    }

    @Test
    fun convertMflacEndToEnd() {
        val raw = load("mflac_map_raw.bin")
        val suffix = load("mflac_map_suffix.bin")
        val target = load("mflac_map_target.bin")
        val full = raw + suffix

        val dir = Files.createTempDirectory("musicunlock-test")
        val input = dir.resolve("sample.mflac")
        Files.write(input, full)

        val error = MusicConverter.convertWithError(input.toString(), dir.toString())
        assertNull(error, "conversion should succeed")

        val output = dir.resolve("sample.flac")
        assertTrue(Files.exists(output), "output file should exist")
        assertContentEquals(target, Files.readAllBytes(output))
    }

    @Test
    fun unsupportedExtensionRejected() {
        val dir = Files.createTempDirectory("musicunlock-test2")
        val input = dir.resolve("sample.txt")
        Files.write(input, byteArrayOf(1, 2, 3))
        val error = MusicConverter.convertWithError(input.toString(), dir.toString())
        assertTrue(error != null, "txt should be rejected")
    }

    @Test
    fun convertMflacToMp3EndToEnd() {
        assumeTrue("本机未安装 ffmpeg，跳过 MP3 转码测试", AudioTranscoder.locateFfmpeg() != null)
        val raw = load("mflac_map_raw.bin")
        val suffix = load("mflac_map_suffix.bin")
        val dir = Files.createTempDirectory("musicunlock-mp3-test")
        val input = dir.resolve("sample.mflac")
        Files.write(input, raw + suffix)

        val error = MusicConverter.convertWithError(
            input.toString(),
            dir.toString(),
            OutputFormat.MP3,
            192,
        )

        assertNull(error, "MP3 conversion should succeed")
        val output = dir.resolve("sample.mp3")
        assertTrue(Files.exists(output), "MP3 output should exist")
        assertTrue(Files.size(output) > 0L, "MP3 output should not be empty")
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }
}
