package musicunlock.service

import java.io.File
import java.util.concurrent.TimeUnit

/** 通过应用内置 ffmpeg 进行音频转码。 */
object AudioTranscoder {

    /** 转码为指定码率的 MP3；成功返回 null，失败返回可直接展示的原因。 */
    fun toMp3(input: File, output: File, bitrateKbps: Int): String? {
        val ffmpeg = locateFfmpeg()
            ?: return "当前应用包缺少适用于本机的 ffmpeg 运行库，无法输出 MP3"
        val command = listOf(
            ffmpeg,
            "-y",
            "-hide_banner",
            "-loglevel", "error",
            "-i", input.absolutePath,
            "-vn",
            "-codec:a", "libmp3lame",
            "-b:a", "${bitrateKbps}k",
            output.absolutePath,
        )
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(10, TimeUnit.MINUTES)
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
        } catch (e: Exception) {
            output.delete()
            "启动 ffmpeg 失败：${e.message ?: e.toString()}"
        }
    }

    /** 返回应用内置 ffmpeg 的可执行路径。 */
    fun locateFfmpeg(): String? = BundledFfmpeg.locate()
}
