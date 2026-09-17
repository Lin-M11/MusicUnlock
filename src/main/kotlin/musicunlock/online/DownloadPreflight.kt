package musicunlock.online

import musicunlock.settings.QualityStrategy
import java.io.File

enum class DownloadPreflightIssueKind {
    AUTH,
    RIGHTS,
    NETWORK,
    UNAVAILABLE,
    DISK,
    UNKNOWN,
}

data class DownloadPreflightItem(
    val song: MusicSong,
    val available: Boolean,
    val qualityLabel: String? = null,
    val format: String? = null,
    val bitrateKbps: Int? = null,
    val lossless: Boolean = false,
    val estimatedBytes: Long? = null,
    val issueKind: DownloadPreflightIssueKind? = null,
    val message: String? = null,
)

data class DownloadPreflightReport(
    val items: List<DownloadPreflightItem>,
    val totalEstimatedBytes: Long,
    val unknownSizeCount: Int,
    val outputFreeBytes: Long?,
    val enoughSpace: Boolean?,
) {
    val availableCount: Int get() = items.count { it.available }
    val unavailableCount: Int get() = items.size - availableCount
}

/** 在真正入队前检查播放地址、音质和输出空间；不会写入任何音频文件。 */
object DownloadPreflight {
    fun inspect(
        provider: OnlineMusicProvider,
        songs: List<MusicSong>,
        outputDir: File,
        quality: QualityStrategy,
    ): DownloadPreflightReport {
        val items = songs.map { song -> inspectOne(provider, song, quality) }
        val estimated = items.mapNotNull { it.estimatedBytes }.sum()
        val free = runCatching {
            outputDir.absoluteFile.let { dir ->
                generateSequence(dir) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace
            }
        }.getOrNull()
        val known = items.count { it.estimatedBytes != null }
        return DownloadPreflightReport(
            items = items,
            totalEstimatedBytes = estimated,
            unknownSizeCount = items.size - known,
            outputFreeBytes = free,
            enoughSpace = free?.let { free >= estimated },
        )
    }

    private fun inspectOne(
        provider: OnlineMusicProvider,
        song: MusicSong,
        quality: QualityStrategy,
    ): DownloadPreflightItem = runCatching {
        val source = provider.playback(song, quality)
        DownloadPreflightItem(
            song = song,
            available = true,
            qualityLabel = source.qualityLabel,
            format = source.formatHint,
            bitrateKbps = source.bitrateKbps,
            lossless = source.lossless,
            estimatedBytes = source.contentLengthBytes ?: estimateBytes(source, song.durationSeconds),
        )
    }.getOrElse { error ->
        val message = error.message ?: error.toString()
        DownloadPreflightItem(
            song = song,
            available = false,
            issueKind = classify(message),
            message = message,
        )
    }

    private fun estimateBytes(source: PlaybackSource, durationSeconds: Int?): Long? {
        val bitrate = source.bitrateKbps?.takeIf { it > 0 } ?: return null
        val duration = durationSeconds?.takeIf { it > 0 } ?: return null
        return bitrate.toLong() * 1_000L * duration / 8L
    }

    private fun classify(message: String): DownloadPreflightIssueKind {
        val lower = message.lowercase()
        return when {
            lower.contains("登录") || lower.contains("cookie") || lower.contains("token") || lower.contains("401") || lower.contains("403") -> DownloadPreflightIssueKind.AUTH
            lower.contains("版权") || lower.contains("会员") || lower.contains("付费") || lower.contains("试听") || lower.contains("下架") -> DownloadPreflightIssueKind.RIGHTS
            lower.contains("超时") || lower.contains("timeout") || lower.contains("network") || lower.contains("连接") -> DownloadPreflightIssueKind.NETWORK
            lower.contains("空间") || lower.contains("磁盘") || lower.contains("permission") || lower.contains("read-only") -> DownloadPreflightIssueKind.DISK
            lower.contains("无可用") || lower.contains("不可用") || lower.contains("未找到") -> DownloadPreflightIssueKind.UNAVAILABLE
            else -> DownloadPreflightIssueKind.UNKNOWN
        }
    }
}
