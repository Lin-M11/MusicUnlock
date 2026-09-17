package musicunlock

import musicunlock.library.AudioQualityInspector
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

class AudioQualityInspectorTest {
    @Test
    fun `detects silent pcm audio`() {
        val dir = Files.createTempDirectory("musicunlock-quality")
        val file = dir.resolve("silence.wav").toFile()
        val format = AudioFormat(8_000f, 16, 1, true, false)
        val data = ByteArray(16_000)
        AudioInputStream(ByteArrayInputStream(data), format, (data.size / format.frameSize).toLong()).use { stream ->
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file)
        }

        val report = AudioQualityInspector.inspect(file)

        assertTrue(report.issues.any { it.contains("静音") })
        assertTrue(report.score < 100)
    }
}
