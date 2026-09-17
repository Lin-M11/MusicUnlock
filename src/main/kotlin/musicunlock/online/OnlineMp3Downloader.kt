package musicunlock.online

import musicunlock.service.AudioTranscoder
import musicunlock.service.TagWriter
import java.io.File
import java.nio.file.Files

/**
 * 在线歌曲下载的统一实现：解析播放地址、下载、必要时转码、写回标签与封面。
 * 各平台只提供地址解析和下载通道，不再各自维护一套下载器。
 */
object OnlineMp3Downloader {

    fun download(provider: OnlineMusicProvider, song: MusicSong, outputDir: File): OnlineDownloadOutcome {
        return try {
            outputDir.mkdirs()
            val source = provider.playback(song)
            val temp = File.createTempFile("musicunlock-online-", ".part", outputDir)
            try {
                provider.download(source.url, temp.toPath())
                val sourceExt = audioExtension(source, temp)
                val (finalFile, finalExt) = transcodeIfNeeded(temp, sourceExt)

                val base = songBaseName(song)
                val output = File(outputDir, "$base.$finalExt")
                    .let { if (it.exists()) uniqueFile(outputDir, base, finalExt) else it }
                if (finalFile != output) Files.move(finalFile.toPath(), output.toPath())

                val cover = song.coverUrl?.let { runCatching { provider.bytes(it) }.getOrNull() }
                TagWriter.embedTags(
                    audioFile = output,
                    title = song.name,
                    artist = song.artistText.ifBlank { null },
                    album = song.albumName,
                    cover = cover,
                )

                val note = if (finalExt.equals("mp3", ignoreCase = true)) {
                    "MP3"
                } else {
                    "已下载（${finalExt.uppercase()}，ffmpeg 转码失败）"
                }
                OnlineDownloadOutcome(true, output, note)
            } finally {
                if (temp.exists()) temp.delete()
            }
        } catch (e: Exception) {
            OnlineDownloadOutcome(false, null, e.message ?: e.toString())
        }
    }

    private fun transcodeIfNeeded(temp: File, sourceExt: String): Pair<File, String> {
        if (sourceExt.equals("mp3", ignoreCase = true)) return temp to "mp3"
        val transcoded = File.createTempFile("musicunlock-online-", ".mp3", temp.parentFile)
        val error = AudioTranscoder.toMp3(temp, transcoded, 320)
        if (error == null) {
            temp.delete()
            return transcoded to "mp3"
        }
        println("ffmpeg 转码失败: $error")
        transcoded.delete()
        return temp to sourceExt
    }

    private fun audioExtension(source: PlaybackSource, file: File): String {
        val hint = source.formatHint
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[a-z0-9]+")) && it != "url" }
        if (hint != null) return hint
        return sniffExtension(file) ?: "mp3"
    }

    private fun sniffExtension(file: File): String? {
        return try {
            val head = Files.newInputStream(file.toPath()).use { input ->
                val buf = ByteArray(12)
                val read = input.read(buf)
                buf.copyOf(maxOf(0, read))
            }
            when {
                head.size >= 4 && head[0] == 0x66.toByte() && head[1] == 0x4C.toByte() &&
                    head[2] == 0x61.toByte() && head[3] == 0x43.toByte() -> "flac"
                head.size >= 4 && head[0] == 0x4F.toByte() && head[1] == 0x67.toByte() &&
                    head[2] == 0x67.toByte() && head[3] == 0x53.toByte() -> "ogg"
                head.size >= 12 && head[4] == 0x66.toByte() && head[5] == 0x74.toByte() &&
                    head[6] == 0x79.toByte() && head[7] == 0x70.toByte() -> "m4a"
                head.size >= 3 && head[0] == 0x49.toByte() && head[1] == 0x44.toByte() &&
                    head[2] == 0x33.toByte() -> "mp3"
                head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun songBaseName(song: MusicSong): String {
        val artist = song.artistText.ifBlank { "未知歌手" }
        return sanitizeFileName("${song.name} - $artist")
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .trim()
            .trim('.')
        val trimmed = if (cleaned.length > 120) cleaned.substring(0, 120).trim().trim('.') else cleaned
        return trimmed.ifBlank { "未命名" }
    }

    private fun uniqueFile(dir: File, base: String, ext: String): File {
        var n = 2
        while (true) {
            val candidate = File(dir, "$base ($n).$ext")
            if (!candidate.exists()) return candidate
            n++
        }
    }
}
