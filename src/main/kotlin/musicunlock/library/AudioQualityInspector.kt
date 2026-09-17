package musicunlock.library

import org.jaudiotagger.audio.AudioFileIO
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioQualityReport(
    val path: String,
    val format: String?,
    val durationSeconds: Int?,
    val bitRateKbps: Int?,
    val effectiveBitRateKbps: Int?,
    val sampleRateHz: Int?,
    val channels: Int?,
    val peak: Double? = null,
    val rms: Double? = null,
    val clippingRatio: Double? = null,
    val score: Int,
    val issues: List<String>,
)

/** 本地音频质量体检：格式、码率、损坏和 PCM 削波检查。 */
object AudioQualityInspector {
    fun inspect(file: File): AudioQualityReport {
        val issues = mutableListOf<String>()
        val audio = runCatching { AudioFileIO.read(file) }.getOrNull()
        val header = audio?.audioHeader
        val duration = runCatching { header?.trackLength?.takeIf { it > 0 } }.getOrNull()
        val bitRate = runCatching { header?.bitRate?.filter(Char::isDigit)?.toIntOrNull() }.getOrNull()
        val format = runCatching { header?.format }.getOrNull()
        val sampleRate = runCatching { header?.sampleRateAsNumber }.getOrNull()
        val channels = runCatching { header?.channels?.filter(Char::isDigit)?.toIntOrNull() }.getOrNull()
        val effective = duration?.takeIf { it > 0 }?.let { seconds -> ((file.length() * 8L) / seconds / 1_000L).toInt() }

        if (audio == null) issues += "无法解析音频，文件可能损坏"
        if (duration == null || duration <= 0) issues += "无法读取有效时长"
        if (bitRate != null && bitRate in 1..127) issues += "码率低于 128k：${bitRate}k"
        if (isLosslessExtension(file.extension) && (bitRate ?: effective ?: Int.MAX_VALUE) < 500) issues += "疑似伪无损或升采样文件"
        if (file.length() < 64L * 1024L && (duration ?: 0) > 30) issues += "文件体积与时长不匹配"

        val pcm = inspectPcm(file)
        if (pcm != null) {
            if (pcm.clippingRatio > 0.001) issues += "检测到明显削波"
            if (pcm.peak < 0.01 && pcm.rms < 0.001) issues += "检测到长静音或空音轨"
        }

        var score = 100
        score -= issues.size * 18
        val bitrateForScore = bitRate ?: effective
        if (bitrateForScore != null) {
            score = when {
                bitrateForScore >= 900 -> score.coerceAtMost(100)
                bitrateForScore >= 320 -> score.coerceAtMost(94)
                bitrateForScore >= 192 -> score.coerceAtMost(82)
                bitrateForScore >= 128 -> score.coerceAtMost(70)
                else -> score.coerceAtMost(55)
            }
        }
        return AudioQualityReport(
            path = file.absolutePath,
            format = format,
            durationSeconds = duration,
            bitRateKbps = bitRate,
            effectiveBitRateKbps = effective,
            sampleRateHz = sampleRate,
            channels = channels,
            peak = pcm?.peak,
            rms = pcm?.rms,
            clippingRatio = pcm?.clippingRatio,
            score = score.coerceIn(0, 100),
            issues = issues,
        )
    }

    private fun inspectPcm(file: File): PcmDiagnostics? = runCatching {
        if (!setOf("wav", "wave", "aif", "aiff", "au").contains(file.extension.lowercase())) return null
        AudioSystem.getAudioInputStream(file).use { stream ->
            val format = stream.format
            if (format.sampleSizeInBits !in setOf(8, 16, 24, 32)) return@use null
            val bytesPerSample = format.sampleSizeInBits / 8
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var sumSquares = 0.0
            var count = 0L
            var peak = 0.0
            var clipped = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                var index = 0
                while (index + bytesPerSample <= read) {
                    val sample = when (bytesPerSample) {
                        2 -> ((buffer[index].toInt() and 0xff) or (buffer[index + 1].toInt() shl 8)).toShort() / 32768.0
                        1 -> ((buffer[index].toInt() and 0xff) - 128) / 128.0
                        else -> 0.0
                    }
                    val absolute = abs(sample)
                    peak = maxOf(peak, absolute)
                    if (absolute >= 0.999) clipped++
                    sumSquares += sample * sample
                    count++
                    index += bytesPerSample
                }
            }
            if (count == 0L) null else PcmDiagnostics(
                peak = peak,
                rms = sqrt(sumSquares / count),
                clippingRatio = clipped.toDouble() / count,
            )
        }
    }.getOrNull()

    private fun isLosslessExtension(extension: String): Boolean =
        extension.lowercase() in setOf("flac", "ape", "wav", "wave", "alac")

    private data class PcmDiagnostics(val peak: Double, val rms: Double, val clippingRatio: Double)
}
