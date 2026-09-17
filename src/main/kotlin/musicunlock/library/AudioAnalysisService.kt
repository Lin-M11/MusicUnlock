package musicunlock.library

import musicunlock.service.AudioTranscoder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class AudioAnalysisResult(
    val fingerprint: String,
    val spectralCutoffHz: Int?,
    val truePeak: Double?,
    val dynamicRangeDb: Double?,
    val analyzedSeconds: Int,
)

/** 基于 ffmpeg 解码 PCM，再计算声学指纹与频谱特征。 */
object AudioAnalysisService {
    private const val SAMPLE_RATE = 44_100
    private const val FINGERPRINT_SAMPLE_RATE = 11_025
    private const val WINDOW_SIZE = 1024
    private const val HOP_SIZE = 512
    private const val MAX_ANALYZE_SECONDS = 120
    private const val SPECTRUM_MAX_SECONDS = 30
    private const val FINGERPRINT_BYTES = 32

    fun analyze(file: File, maxSeconds: Int = MAX_ANALYZE_SECONDS): AudioAnalysisResult? {
        if (!file.isFile || file.length() <= 0L) return null
        val ffmpeg = AudioTranscoder.locateFfmpeg() ?: return null
        val command = listOf(
            ffmpeg,
            "-v", "error",
            "-i", file.absolutePath,
            "-t", maxSeconds.coerceIn(20, MAX_ANALYZE_SECONDS).toString(),
            "-vn",
            "-ac", "1",
            "-ar", SAMPLE_RATE.toString(),
            "-f", "s16le",
            "pipe:1",
        )
        val process = ProcessBuilder(command).apply {
            if (!System.getProperty("os.name").lowercase().contains("win")) {
                val libraryPath = File(ffmpeg).parentFile?.absolutePath
                if (!libraryPath.isNullOrBlank()) {
                    val key = if (System.getProperty("os.name").lowercase().contains("mac")) "DYLD_LIBRARY_PATH" else "LD_LIBRARY_PATH"
                    val current = environment()[key].orEmpty()
                    environment()[key] = if (current.isBlank()) libraryPath else "$libraryPath:$current"
                }
            }
        }.start()
        val bytes = process.inputStream.use { it.readBytes() }
        val errors = process.errorStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0 || bytes.size < WINDOW_SIZE * 2) {
            runCatching { process.destroyForcibly() }
            if (errors.isNotBlank()) println("音频分析失败: ${errors.lineSequence().last()}")
            return null
        }

        val samples = ShortArray(bytes.size / 2) { index ->
            ((bytes[index * 2].toInt() and 0xff) or (bytes[index * 2 + 1].toInt() shl 8)).toShort()
        }
        if (samples.size < WINDOW_SIZE) return null
        val fingerprint = fingerprint(downsample(samples, SAMPLE_RATE / FINGERPRINT_SAMPLE_RATE), FINGERPRINT_SAMPLE_RATE)
        val spectrum = averageSpectrum(samples, SAMPLE_RATE * SPECTRUM_MAX_SECONDS)
        val cutoff = spectralCutoff(spectrum)
        val peak = samples.maxOf { abs(it.toInt()) }.toDouble() / 32768.0
        val rms = sqrt(samples.sumOf { it.toDouble() * it.toDouble() } / samples.size.coerceAtLeast(1)) / 32768.0
        val dynamicRange = if (rms > 0.0 && peak > 0.0) 20.0 * log10(peak / rms) else null
        return AudioAnalysisResult(
            fingerprint = fingerprint,
            spectralCutoffHz = cutoff,
            truePeak = peak.takeIf { it.isFinite() },
            dynamicRangeDb = dynamicRange?.takeIf { it.isFinite() },
            analyzedSeconds = samples.size / SAMPLE_RATE,
        )
    }

    private fun downsample(samples: ShortArray, factor: Int): ShortArray {
        if (factor <= 1) return samples
        val output = ShortArray(samples.size / factor)
        for (index in output.indices) {
            var sum = 0L
            val start = index * factor
            for (offset in 0 until factor) sum += samples[start + offset]
            output[index] = (sum / factor).toShort()
        }
        return output
    }

    fun similarity(left: String?, right: String?): Double {
        if (left.isNullOrBlank() || right.isNullOrBlank() || left.length != right.length) return 0.0
        val a = runCatching { left.chunked(2).map { it.toInt(16) } }.getOrNull() ?: return 0.0
        val b = runCatching { right.chunked(2).map { it.toInt(16) } }.getOrNull() ?: return 0.0
        var equalBits = 0
        a.indices.forEach { index -> equalBits += 8 - Integer.bitCount(a[index] xor b[index]) }
        return equalBits.toDouble() / (a.size * 8.0)
    }

    private fun fingerprint(samples: ShortArray, sampleRate: Int): String {
        val bits = ByteArray(FINGERPRINT_BYTES)
        var frame = 0
        var start = 0
        while (start + WINDOW_SIZE <= samples.size) {
            val peaks = dominantFrequencies(samples, start, sampleRate)
            if (peaks.size >= 2) {
                for (i in 0 until peaks.size - 1) {
                    for (j in i + 1 until peaks.size) {
                        val pair = "${peaks[i] / 32}:${peaks[j] / 32}:${frame / 8}"
                        val hash = pair.hashCode()
                        val byteIndex = (hash and Int.MAX_VALUE) % bits.size
                        val bit = 1 shl ((hash ushr 8) and 7)
                        bits[byteIndex] = (bits[byteIndex].toInt() or bit).toByte()
                    }
                }
            }
            frame++
            start += HOP_SIZE
        }
        return bits.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun dominantFrequencies(samples: ShortArray, start: Int, sampleRate: Int): List<Int> {
        val real = DoubleArray(WINDOW_SIZE)
        val imag = DoubleArray(WINDOW_SIZE)
        for (i in 0 until WINDOW_SIZE) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (WINDOW_SIZE - 1))
            real[i] = samples[start + i] / 32768.0 * window
        }
        fft(real, imag)
        val minBin = max(1, (120.0 * WINDOW_SIZE / sampleRate).toInt())
        val maxBin = min(WINDOW_SIZE / 2 - 1, (5_000.0 * WINDOW_SIZE / sampleRate).toInt())
        return (minBin..maxBin)
            .map { bin -> bin to (real[bin] * real[bin] + imag[bin] * imag[bin]) }
            .sortedByDescending { it.second }
            .take(5)
            .map { (bin, _) -> (bin * sampleRate / WINDOW_SIZE) }
            .distinct()
    }

    private fun averageSpectrum(samples: ShortArray, sampleLimit: Int = samples.size): DoubleArray {
        val average = DoubleArray(WINDOW_SIZE / 2)
        var count = 0
        var start = 0
        val end = min(samples.size, sampleLimit)
        while (start + WINDOW_SIZE <= end) {
            val real = DoubleArray(WINDOW_SIZE)
            val imag = DoubleArray(WINDOW_SIZE)
            for (i in 0 until WINDOW_SIZE) {
                val window = 0.5 - 0.5 * cos(2.0 * PI * i / (WINDOW_SIZE - 1))
                real[i] = samples[start + i] / 32768.0 * window
            }
            fft(real, imag)
            for (bin in average.indices) average[bin] += sqrt(real[bin] * real[bin] + imag[bin] * imag[bin])
            count++
            start += HOP_SIZE
        }
        if (count == 0) return average
        for (index in average.indices) average[index] /= count
        return average
    }

    private fun spectralCutoff(spectrum: DoubleArray): Int? {
        if (spectrum.isEmpty()) return null
        val maxMagnitude = spectrum.maxOrNull() ?: return null
        if (maxMagnitude <= 0.0) return null
        val threshold = maxMagnitude * 0.0025
        val last = spectrum.indexOfLast { it >= threshold }
        if (last <= 0) return null
        return last * SAMPLE_RATE / WINDOW_SIZE
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLengthReal = cos(angle)
            val wLengthImag = sin(angle)
            var start = 0
            while (start < n) {
                var wReal = 1.0
                var wImag = 0.0
                for (i in 0 until length / 2) {
                    val uReal = real[start + i]
                    val uImag = imag[start + i]
                    val vReal = real[start + i + length / 2] * wReal - imag[start + i + length / 2] * wImag
                    val vImag = real[start + i + length / 2] * wImag + imag[start + i + length / 2] * wReal
                    real[start + i] = uReal + vReal
                    imag[start + i] = uImag + vImag
                    real[start + i + length / 2] = uReal - vReal
                    imag[start + i + length / 2] = uImag - vImag
                    val nextReal = wReal * wLengthReal - wImag * wLengthImag
                    wImag = wReal * wLengthImag + wImag * wLengthReal
                    wReal = nextReal
                }
                start += length
            }
            length = length shl 1
        }
    }
}
