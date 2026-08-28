package musicunlock.ncm

import musicunlock.service.TagWriter
import java.io.File
import java.nio.file.Files

/** 单曲下载结果。 */
class DownloadOutcome(
    val ok: Boolean,
    val file: File?,
    val message: String,
)

/**
 * 官方音频下载并统一转码为 MP3：
 * 高码率优先（320k → 192k → 128k），不可用时逐级降级；
 * 下载完成后写回歌名 / 歌手 / 专辑 / 封面标签。
 *
 * 当官方返回的不是 MP3（如 FLAC）时，使用系统 ffmpeg 转码为 MP3；
 * 未安装 ffmpeg 时保留原音频并给出提示（仍可播放、标签仍会写回）。
 */
object Mp3Downloader {

    private val BITRATES = listOf(320_000, 192_000, 128_000)
    private const val FALLBACK_EXT = "mp3"
    private const val GRAY_SONG_MESSAGE = "获取播放地址失败（灰色歌曲：无版权或已下架，无法下载）"

    /** 下载单曲为 MP3 并写回标签。 */
    fun downloadAsMp3(song: NeteaseSong, outputDir: File): DownloadOutcome {
        return try {
            outputDir.mkdirs()

            // 1. 高码率优先，不可用降级；试听片段与灰色歌曲直接给出明确原因
            val songUrl = BITRATES.asSequence()
                .mapNotNull { br -> runCatching { NeteaseApi.songUrl(song.id, br) }.getOrNull() }
                .firstOrNull()
                ?: return DownloadOutcome(false, null, GRAY_SONG_MESSAGE)
            unavailableReason(songUrl)?.let { return DownloadOutcome(false, null, it) }

            // 2. 下载到临时文件
            val temp = File.createTempFile("musicunlock-", ".part", outputDir)
            try {
                NeteaseApi.download(songUrl.url, temp.toPath())

                // 3. 判断真实音频格式：源已是 MP3 直接使用；否则用 ffmpeg 转码；无 ffmpeg 保留原格式
                val sourceExt = audioExtension(songUrl, temp)
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
                        // 无 ffmpeg：保留原音频，仍写回标签
                        finalFile = temp
                        finalExt = sourceExt
                    }
                }

                // 4. 目标文件名：歌名 - 歌手.mp3
                val base = songBaseName(song)
                val output = File(outputDir, "$base.$finalExt")
                    .let { if (it.exists()) uniqueFile(outputDir, base, finalExt) else it }
                if (finalFile != output) {
                    Files.move(finalFile.toPath(), output.toPath())
                }

                // 5. 写回标签与封面
                val cover = song.albumPicUrl?.let { NeteaseApi.downloadBytes(it) }
                TagWriter.embedTags(
                    audioFile = output,
                    title = song.name,
                    artist = song.artistText.ifBlank { null },
                    album = song.albumName,
                    cover = cover,
                )

                val note = if (finalExt.equals("mp3", ignoreCase = true)) "MP3" else "已下载（${finalExt.uppercase()}，未安装 ffmpeg 未转码）"
                DownloadOutcome(true, output, note)
            } finally {
                if (temp.exists()) temp.delete()
            }
        } catch (e: Exception) {
            DownloadOutcome(false, null, e.message ?: e.toString())
        }
    }

    /**
     * 播放地址不可用时的明确原因；返回 null 表示地址可用可正常下载。
     * - 地址为 null：灰色歌曲（无版权/已下架），登录会员也无法获取
     * - 地址为试听片段：会员/数字专辑歌曲，当前账号无对应权限，仅返回试听
     */
    internal fun unavailableReason(url: NeteaseSongUrl?): String? = when {
        url == null -> GRAY_SONG_MESSAGE
        url.isTrial -> "仅可获取试听片段（当前账号无会员/数字专辑权限），已跳过下载"
        else -> null
    }

    /** 探测下载内容的真实扩展名：优先取接口返回的 type，其次按内容嗅探。 */
    private fun audioExtension(url: NeteaseSongUrl, file: File): String {
        val type = url.type?.lowercase()?.takeIf { it.matches(Regex("[a-z0-9]+")) }
        if (type != null && type != "url") return type
        return sniffExtension(file) ?: "mp3"
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

    /** 使用系统 ffmpeg 转码为 MP3；失败或未安装返回 null。 */
    private fun transcodeToMp3(input: File): File? {
        val ffmpeg = locateFfmpeg() ?: return null
        val output = File.createTempFile("musicunlock-", ".mp3", input.parentFile)
        return try {
            val command = listOf(
                ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-i", input.absolutePath,
                "-vn", "-codec:a", "libmp3lame", "-b:a", "320k",
                output.absolutePath,
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0 && output.exists() && output.length() > 0) {
                output
            } else {
                println("ffmpeg 转码失败: $log")
                output.delete()
                null
            }
        } catch (e: Exception) {
            output.delete()
            null
        }
    }

    /** 查找 ffmpeg：优先环境变量 FFMPEG_BIN，其次 PATH，最后常见安装路径。 */
    fun locateFfmpeg(): String? {
        System.getenv("FFMPEG_BIN")?.takeIf { File(it).canExecute() }?.let { return it }
        val pathDirs = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        for (dir in pathDirs) {
            val candidate = File(dir, if (isWindows()) "ffmpeg.exe" else "ffmpeg")
            if (candidate.canExecute()) return candidate.absolutePath
        }
        if (isMac()) {
            for (p in listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg")) {
                if (File(p).canExecute()) return p
            }
        }
        return null
    }

    // ============================================================
    //  工具
    // ============================================================

    internal fun songBaseName(song: NeteaseSong): String {
        val artist = song.artistText.ifBlank { "未知歌手" }
        return sanitizeFileName("${song.name} - $artist")
    }

    internal fun sanitizeFileName(name: String): String {
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

    private fun isMp3(file: File): Boolean = extensionOf(file).equals("mp3", ignoreCase = true)

    private fun extensionOf(file: File): String {
        val name = file.name
        val idx = name.lastIndexOf('.')
        return if (idx > 0 && idx < name.length - 1) name.substring(idx + 1) else FALLBACK_EXT
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun isMac(): Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
}
