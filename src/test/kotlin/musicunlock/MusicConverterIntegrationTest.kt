package musicunlock

import kotlinx.coroutines.runBlocking
import musicunlock.service.AudioTranscoder
import musicunlock.service.MusicConverter
import musicunlock.settings.OutputFormat
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
    fun existingMatchingOutputIsSkipped() {
        val sample = load("mflac_map_raw.bin") + load("mflac_map_suffix.bin")
        val dir = Files.createTempDirectory("musicunlock-skip-test")
        val input = dir.resolve("sample.mflac")
        val output = dir.resolve("sample.flac")
        val sentinel = "already-finished".toByteArray()
        Files.write(input, sample)
        Files.write(output, sentinel)

        val outcomes = runBlocking {
            MusicConverter.convertBatch(
                inputPaths = listOf(input.toString()),
                outputDir = dir.toString(),
            )
        }

        assertTrue(outcomes.single().skipped, "existing output should be skipped")
        assertContentEquals(sentinel, Files.readAllBytes(output))
    }

    @Test
    fun forceOverwriteReconvertsExistingOutput() {
        val sample = load("mflac_map_raw.bin") + load("mflac_map_suffix.bin")
        val target = load("mflac_map_target.bin")
        val dir = Files.createTempDirectory("musicunlock-force-test")
        val input = dir.resolve("sample.mflac")
        val output = dir.resolve("sample.flac")
        Files.write(input, sample)
        Files.write(output, "stale".toByteArray())

        val error = MusicConverter.convertWithError(
            input.toString(),
            dir.toString(),
            forceOverwrite = true,
        )

        assertNull(error, "forced conversion should succeed")
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
        val ffmpeg = AudioTranscoder.locateFfmpeg()
        assertTrue(ffmpeg != null, "应用应包含可用的内置 ffmpeg")
        val normalizedPath = ffmpeg.orEmpty().replace('\\', '/')
        assertTrue(normalizedPath.contains(".musicunlock/runtime/ffmpeg-"), "应使用应用解压的内置 ffmpeg")
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

    @Test
    fun convertBatchMatchesSerialOutputsAndReportsEveryFile() {
        val sample = load("mflac_map_raw.bin") + load("mflac_map_suffix.bin")
        val target = load("mflac_map_target.bin")
        val dir = Files.createTempDirectory("musicunlock-batch-test")
        val inputs = (1..4).map { index ->
            val input = dir.resolve("input-$index").resolve("song-$index.mflac")
            Files.createDirectories(input.parent)
            Files.write(input, sample)
            input
        }

        val started = mutableSetOf<String>()
        val finished = mutableSetOf<String>()
        val outputs = runBlocking {
            MusicConverter.convertBatch(
                inputPaths = inputs.map { it.toString() },
                outputDir = dir.resolve("parallel").toString(),
                parallelism = 4,
                onStarted = { started.add(it) },
                onFinished = { finished.add(it.inputPath) },
            )
        }

        assertEquals(inputs.map { it.toString() }.toSet(), started)
        assertEquals(inputs.map { it.toString() }.toSet(), finished)
        assertTrue(outputs.all { it.succeeded }, "all parallel conversions should succeed")
        assertEquals(inputs.map { it.toString() }, outputs.map { it.inputPath })
        inputs.forEachIndexed { index, _ ->
            val output = dir.resolve("parallel").resolve("song-${index + 1}.flac")
            assertContentEquals(target, Files.readAllBytes(output))
        }
    }

    @Test
    fun convertBatchResolvesSameOutputNameLikeSerialConversion() {
        val dir = Files.createTempDirectory("musicunlock-order-test")
        val inputs = listOf(
            dir.resolve("input-1").resolve("same-name.mflac").also { input ->
                Files.createDirectories(input.parent)
                Files.write(input, load("mflac_map_raw.bin") + load("mflac_map_suffix.bin"))
            },
            dir.resolve("input-2").resolve("same-name.qmc0").also { input ->
                Files.createDirectories(input.parent)
                Files.write(input, load("qmc0_static_raw.bin"))
            },
        )
        val serialOutput = dir.resolve("serial").toString()
        inputs.forEach { input ->
            assertNull(MusicConverter.convertWithError(input.toString(), serialOutput, OutputFormat.MP3, 192))
        }
        val expected = Files.readAllBytes(dir.resolve("serial").resolve("same-name.mp3"))

        val outcomes = runBlocking {
            MusicConverter.convertBatch(
                inputPaths = inputs.map { it.toString() },
                outputDir = dir.resolve("parallel").toString(),
                outputFormat = OutputFormat.MP3,
                bitrateKbps = 192,
                parallelism = 2,
            )
        }

        assertTrue(outcomes.all { it.succeeded }, "same-name conversions should all complete")
        assertContentEquals(expected, Files.readAllBytes(dir.resolve("parallel").resolve("same-name.mp3")))
    }

    @Test
    fun convertBatchSerializesSameOutputPath() {
        val sample = load("mflac_map_raw.bin") + load("mflac_map_suffix.bin")
        val target = load("mflac_map_target.bin")
        val dir = Files.createTempDirectory("musicunlock-race-test")
        val inputs = (1..12).map { index ->
            val input = dir.resolve("input-$index").resolve("same-name.mflac")
            Files.createDirectories(input.parent)
            Files.write(input, sample)
            input
        }

        val outputs = runBlocking {
            MusicConverter.convertBatch(
                inputPaths = inputs.map { it.toString() },
                outputDir = dir.resolve("output").toString(),
                parallelism = 8,
            )
        }

        assertTrue(outputs.all { it.succeeded }, "same-target conversions should all complete")
        val output = dir.resolve("output").resolve("same-name.flac")
        assertContentEquals(target, Files.readAllBytes(output))
        val temporaryFiles = Files.list(dir.resolve("output")).use { paths ->
            paths.filter { it.fileName.toString().startsWith("musicunlock-") }.count()
        }
        assertEquals(0L, temporaryFiles, "temporary files should be removed")
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }
}
