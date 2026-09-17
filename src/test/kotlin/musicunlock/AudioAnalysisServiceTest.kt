package musicunlock

import musicunlock.library.AudioAnalysisService
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Random
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class AudioAnalysisServiceTest {
    @Test
    fun `keeps full spectrum resolution for quality analysis`() {
        val dir = Files.createTempDirectory("musicunlock-analysis")
        val file = dir.resolve("noise.wav").toFile()
        val format = AudioFormat(44_100f, 16, 1, true, false)
        val data = ByteArray(44_100 * 2 * 2)
        val random = Random(42)
        for (index in data.indices step 2) {
            val sample = random.nextInt(65_536) - 32_768
            data[index] = (sample and 0xff).toByte()
            data[index + 1] = ((sample ushr 8) and 0xff).toByte()
        }
        AudioInputStream(ByteArrayInputStream(data), format, (data.size / format.frameSize).toLong()).use { stream ->
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file)
        }

        val report = AudioAnalysisService.analyze(file, maxSeconds = 20)

        assertNotNull(report)
        assertTrue((report?.spectralCutoffHz ?: 0) > 15_000)
    }
}
