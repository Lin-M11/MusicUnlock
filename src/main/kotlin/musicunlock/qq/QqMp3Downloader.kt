package musicunlock.qq

import musicunlock.service.AudioTranscoder
import musicunlock.service.TagWriter
import java.io.File
import java.nio.file.Files

/**
 * QQ 音乐官方音频下载并统一为 MP3：
 * 320k（M800）优先，不可用降级到 128k（M500）；
 * 下载完成后写回歌名 / 歌手 / 专辑 / 封面标签。
 *
 * 当官方返回的不是 MP3（如 FLAC / M4A）时，使用应用内置 ffmpeg 转码为 MP3；
 * 转码失败时保留原音频并给出提示（仍可播放、标签仍会写回）。
 */
object QqMp3Downloader {

    /** 封面图片基础地址（albumMid 拼出 300x300 封面）。 */
    private const val COVER_BASE = "https://y.gtimg.cn/music/photo_new/T002R300x300M000"

    /** 下载单曲为 MP3 并写回标签。 */
    fun downloadAsMp3(song: QqSong, outputDir: File): QqDownloadOutcome {
        return try {
            outputDir.mkdirs()

            val ordered = linkedSetOf<Int>()
            if (song.size320 > 0) ordered.add(320)
            if (song.size128 > 0) ordered.add(128)
            if (ordered.isEmpty()) {
                // 官方未声明尺寸时兜底尝试 128k
                ordered.add(128)
            }
            var lastReason: String? = null
            var songUrl: QqSongUrlResult? = null
            for (quality in ordered) {
                val result = runCatching { QqMusicApi.songUrl(song, quality) }.getOrNull()
                if (result != null && result.ok) {
                    songUrl = result
                    break
                }
                lastReason = result?.reason ?: "获取播放地址失败（登录可能已失效）"
            }
            val url = songUrl?.url ?: return QqDownloadOutcome(
                false, null, lastReason ?: "无法获取播放地址，已跳过",
            )

            // 下载到临时文件
            val temp = File.createTempFile("musicunlock-qq-", ".part", outputDir)
            try {
                QqMusicApi.download(url, temp.toPath())

                val sourceExt = sniffExtension(temp) ?: "mp3"
                val finalFile: File
                val finalExt: String
                if (sourceExt.equals("mp3", ignoreCase = true)) {
                    finalFile = temp
                    finalExt = "mp3"
                } else {
                    val transcoded = transcodeToMp3(temp)
                    if (transcoded != null) {
                        temp.delete()
                        finalFile = transcoded
                        finalExt = "mp3"
                    } else {
                        finalFile = temp
                        finalExt = sourceExt
                    }
                }

                val base = songBaseName(song)
                val output = File(outputDir, "$base.$finalExt")
                    .let { if (it.exists()) uniqueFile(outputDir, base, finalExt) else it }
                if (finalFile != output) {
                    Files.move(finalFile.toPath(), output.toPath())
                }

                val cover = song.albumMid?.let { albumMid ->
                    QqMusicApi.downloadBytes("$COVER_BASE$albumMid.jpg")
                }
                TagWriter.embedTags(
                    audioFile = output,
                    title = song.name,
                    artist = song.artistText.ifBlank { null },
                    album = song.albumName,
                    cover = cover,
                )

                val note = if (finalExt.equals("mp3", ignoreCase = true)) "MP3" else "已下载（${finalExt.uppercase()}，ffmpeg 转码失败）"
                QqDownloadOutcome(true, output, note)
            } finally {
                if (temp.exists()) temp.delete()
            }
        } catch (e: Exception) {
            QqDownloadOutcome(false, null, e.message ?: e.toString())
        }
    }

    // ============================================================
    //  工具
    // ============================================================

    private fun songBaseName(song: QqSong): String {
        val artist = song.artistText.ifBlank { "未知歌手" }
        return sanitizeFileName("${song.name} - $artist")
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .trim()
            .trim('.')
        val max = 120
        val trimmed = if (cleaned.length > max) cleaned.substring(0, max).trim().trim('.') else cleaned
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

    /** 简单内容嗅探：读取文件头判断 MP3 / FLAC / OGG / M4A。 */
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

    /** 使用内置 ffmpeg 转码为 MP3；失败返回 null。 */
    private fun transcodeToMp3(input: File): File? {
        val output = File.createTempFile("musicunlock-qq-", ".mp3", input.parentFile)
        val error = AudioTranscoder.toMp3(input, output, 320)
        if (error == null) return output
        println("ffmpeg 转码失败: $error")
        output.delete()
        return null
    }
}
