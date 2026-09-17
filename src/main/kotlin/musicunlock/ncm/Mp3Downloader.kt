package musicunlock.ncm

import musicunlock.online.OnlineMp3Downloader
import java.io.File

/** 单曲下载结果。保留旧类型以兼容现有调用方与测试。 */
class DownloadOutcome(
    val ok: Boolean,
    val file: File?,
    val message: String,
)

/**
 * 网易云下载兼容层。
 *
 * 实际下载、转码和标签写回统一由 [OnlineMp3Downloader] 完成；这里仅保留
 * 网易云特有的播放地址可用性判断和旧调用入口。
 */
object Mp3Downloader {

    private const val GRAY_SONG_MESSAGE = "获取播放地址失败（灰色歌曲：无版权或已下架，无法下载）"

    fun downloadAsMp3(song: NeteaseSong, outputDir: File): DownloadOutcome {
        val result = OnlineMp3Downloader.download(NeteaseProvider, song.toMusicSong(), outputDir)
        return DownloadOutcome(result.ok, result.file, result.message)
    }

    internal fun unavailableReason(url: NeteaseSongUrl?): String? = when {
        url == null -> GRAY_SONG_MESSAGE
        url.isTrial -> "仅可获取试听片段（当前账号无会员/数字专辑权限），已跳过下载"
        else -> null
    }

    internal fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .trim()
            .trim('.')
        val trimmed = if (cleaned.length > 120) cleaned.substring(0, 120).trim().trim('.') else cleaned
        return trimmed.ifBlank { "未命名" }
    }

    internal fun songBaseName(song: NeteaseSong): String {
        val artist = song.artistText.ifBlank { "未知歌手" }
        return sanitizeFileName("${song.name} - $artist")
    }
}
