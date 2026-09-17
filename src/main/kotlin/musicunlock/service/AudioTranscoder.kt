package musicunlock.service

import java.io.File
import java.util.concurrent.TimeUnit

/** 本地音频转码目标格式；与持久化设置解耦，便于后续扩展输出矩阵。 */
enum class TranscodeFormat(val extension: String) {
    MP3("mp3"),
    FLAC("flac"),
    M4A("m4a"),
    OGG("ogg"),
    OPUS("opus"),
    WAV("wav"),
}

/** 通过应用内置 ffmpeg 进行音频转码。 */
object AudioTranscoder {

    /** 转码为指定码率的 MP3；成功返回 null，失败返回可直接展示的原因。 */
    fun toMp3(
        input: File,
        output: File,
        bitrateKbps: Int,
        shouldContinue: () -> Boolean = { true },
    ): String? = transcode(input, output, TranscodeFormat.MP3, bitrateKbps, shouldContinue)

    /** 转码为指定格式；无损格式会忽略码率参数。 */
    fun transcode(
        input: File,
        output: File,
        format: TranscodeFormat,
        bitrateKbps: Int = 320,
        shouldContinue: () -> Boolean = { true },
        audioFilters: List<String> = emptyList(),
    ): String? {
        val ffmpeg = locateFfmpeg()
            ?: return "当前应用包缺少适用于本机的 ffmpeg 运行库，无法输出 ${format.name}"
        val command = buildList {
            addAll(
                listOf(
                    ffmpeg,
                    "-y",
                    "-hide_banner",
                    "-loglevel", "error",
                    "-i", input.absolutePath,
                    "-vn",
                ),
            )
            if (audioFilters.isNotEmpty()) addAll(listOf("-af", audioFilters.joinToString(",")))
            when (format) {
                TranscodeFormat.MP3 -> addAll(listOf("-codec:a", "libmp3lame", "-b:a", "${bitrateKbps}k"))
                TranscodeFormat.FLAC -> addAll(listOf("-codec:a", "flac", "-compression_level", "8"))
                TranscodeFormat.M4A -> addAll(listOf("-codec:a", "aac", "-b:a", "${bitrateKbps}k", "-movflags", "+faststart"))
                TranscodeFormat.OGG -> addAll(listOf("-codec:a", "vorbis", "-strict", "-2", "-b:a", "${bitrateKbps}k"))
                TranscodeFormat.OPUS -> addAll(listOf("-codec:a", "libopus", "-b:a", "${bitrateKbps}k"))
                TranscodeFormat.WAV -> addAll(listOf("-codec:a", "pcm_s16le"))
            }
            add(output.absolutePath)
        }
        return try {
            val builder = ProcessBuilder(command).redirectErrorStream(true)
            if (!System.getProperty("os.name").lowercase().contains("win")) {
                val libraryPath = File(ffmpeg).parentFile?.absolutePath
                if (!libraryPath.isNullOrBlank()) {
                    val key = if (System.getProperty("os.name").lowercase().contains("mac")) "DYLD_LIBRARY_PATH" else "LD_LIBRARY_PATH"
                    val current = builder.environment()[key].orEmpty()
                    builder.environment()[key] = if (current.isBlank()) libraryPath else "$libraryPath:$current"
                }
            }
            val process = builder.start()
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
            var finished = false
            while (System.nanoTime() < deadline) {
                if (!shouldContinue()) {
                    process.destroyForcibly()
                    output.delete()
                    throw ConversionCancelledException("转码已暂停")
                }
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    finished = true
                    break
                }
            }
            if (!finished) {
                process.destroyForcibly()
                output.delete()
                return "ffmpeg 转码超时"
            }
            val log = process.inputStream.bufferedReader().readText().trim()
            if (process.exitValue() == 0 && output.isFile && output.length() > 0L) {
                null
            } else {
                output.delete()
                val reason = log.lineSequence().lastOrNull()?.takeIf { it.isNotBlank() }
                if (reason == null) "ffmpeg 转码失败" else "ffmpeg 转码失败：$reason"
            }
        } catch (e: ConversionCancelledException) {
            output.delete()
            throw e
        } catch (e: Exception) {
            output.delete()
            "启动 ffmpeg 失败：${e.message ?: e.toString()}"
        }
    }

    /** 返回应用内置 ffmpeg 的可执行路径。 */
    fun locateFfmpeg(): String? = BundledFfmpeg.locate()
}
