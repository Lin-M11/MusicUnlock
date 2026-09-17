package musicunlock.online

import musicunlock.settings.DownloadExistingPolicy
import musicunlock.settings.LyricsMode
import musicunlock.settings.QualityStrategy
import musicunlock.settings.OutputFormat
import musicunlock.service.TranscodeFormat
import java.io.File

/** 用户可选的下载音质策略。 */
data class DownloadPreferences(
    val quality: QualityStrategy = QualityStrategy.HIGHEST,
    val existingFilePolicy: DownloadExistingPolicy = DownloadExistingPolicy.SKIP,
    val outputTemplate: String = "{artist}/{album}/{title}",
    val lyricsMode: LyricsMode = LyricsMode.EMBED,
    val writeCoverSidecar: Boolean = false,
    val writePlaylistM3u8: Boolean = false,
    val concurrency: Int = 2,
    val retryCount: Int = 3,
    val retryDelayMillis: Long = 1_000,
    val speedLimitKbps: Int = 0,
    val requestTimeoutSeconds: Long = 30,
    val connectTimeoutSeconds: Long = 10,
    val proxyUrl: String? = null,
    val targetFormat: TranscodeFormat? = null,
    val forceMp3: Boolean = false,
    val mp3BitrateKbps: Int = 320,
)

/** 平台下载任务的持久化状态。 */
enum class DownloadTaskState {
    QUEUED,
    DOWNLOADING,
    TRANSCODING,
    TAGGING,
    COMPLETED,
    FAILED,
    PAUSED,
    CANCELLED,
    SKIPPED,
}

/** 错误分类用于决定是否自动重试以及界面应展示的建议。 */
enum class DownloadErrorKind {
    AUTH,
    RIGHTS,
    NETWORK,
    SERVER,
    DISK,
    DECODE,
    CANCELLED,
    UNKNOWN,
}

class ClassifiedDownloadException(
    val kind: DownloadErrorKind,
    val userMessage: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : Exception(userMessage, cause)

/** 可序列化的下载任务。 */
data class DownloadTaskRecord(
    val id: String,
    val platform: String,
    val song: MusicSong,
    val outputDir: String,
    val preferences: DownloadPreferences = DownloadPreferences(),
    val playlistName: String? = null,
    val subscriptionId: String? = null,
    val fallbackAttempted: Boolean = false,
    val state: DownloadTaskState = DownloadTaskState.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val speedBytesPerSecond: Long = 0,
    val etaSeconds: Long? = null,
    val attempts: Int = 0,
    val message: String? = null,
    val errorKind: DownloadErrorKind? = null,
    val outputPath: String? = null,
    val qualityLabel: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isTerminal: Boolean
        get() = state in setOf(
            DownloadTaskState.COMPLETED,
            DownloadTaskState.FAILED,
            DownloadTaskState.CANCELLED,
            DownloadTaskState.SKIPPED,
        )

    val canPause: Boolean
        get() = state in setOf(DownloadTaskState.QUEUED, DownloadTaskState.DOWNLOADING, DownloadTaskState.TRANSCODING, DownloadTaskState.TAGGING)

    val canResume: Boolean
        get() = state == DownloadTaskState.PAUSED

    val canCancel: Boolean
        get() = !isTerminal && state != DownloadTaskState.CANCELLED

    val canRetry: Boolean
        get() = state == DownloadTaskState.FAILED || state == DownloadTaskState.CANCELLED
}

/** 下载任务管理器对外暴露的不可变快照。 */
data class DownloadTaskSnapshot(
    val id: String,
    val platform: String,
    val title: String,
    val artist: String,
    val playlistName: String?,
    val state: DownloadTaskState,
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long?,
    val attempts: Int,
    val message: String?,
    val errorKind: DownloadErrorKind?,
    val outputFile: File?,
    val qualityLabel: String?,
)

/** 歌词结果。original 为原文 LRC，其余字段为可选补充。 */
data class MusicLyrics(
    val original: String,
    val translated: String? = null,
    val romanized: String? = null,
) {
    val isEmpty: Boolean
        get() = original.isBlank() && translated.isNullOrBlank() && romanized.isNullOrBlank()
}

/** 播放地址同时携带音质提示，便于界面和文件模板展示。 */
data class PlaybackSource(
    val url: String,
    val formatHint: String? = null,
    val qualityLabel: String? = null,
    val bitrateKbps: Int? = null,
    val lossless: Boolean = false,
    val contentLengthBytes: Long? = null,
)

fun musicunlock.settings.AppSettings.toOnlineDownloadPreferences(): DownloadPreferences =
    toDownloadPreferences(
        forceMp3 = outputFormat == OutputFormat.MP3,
        targetFormat = outputFormat.toTranscodeFormat(),
    )

fun musicunlock.settings.AppSettings.toDownloadPreferences(
    forceMp3: Boolean = true,
    targetFormat: TranscodeFormat? = if (forceMp3) TranscodeFormat.MP3 else null,
): DownloadPreferences =
    DownloadPreferences(
        quality = qualityStrategy,
        existingFilePolicy = existingFilePolicy,
        outputTemplate = outputTemplate,
        lyricsMode = lyricsMode,
        writeCoverSidecar = writeCoverSidecar,
        writePlaylistM3u8 = writePlaylistM3u8,
        concurrency = downloadConcurrency,
        retryCount = downloadRetryCount,
        retryDelayMillis = downloadRetryDelayMillis,
        speedLimitKbps = downloadSpeedLimitKbps,
        requestTimeoutSeconds = downloadTimeoutSeconds,
        connectTimeoutSeconds = connectTimeoutSeconds,
        proxyUrl = proxyUrl,
        targetFormat = targetFormat,
        forceMp3 = forceMp3,
        mp3BitrateKbps = bitrateKbps,
    )

fun OutputFormat.toTranscodeFormat(): TranscodeFormat? = when (this) {
    OutputFormat.ORIGINAL -> null
    OutputFormat.MP3 -> TranscodeFormat.MP3
    OutputFormat.FLAC -> TranscodeFormat.FLAC
    OutputFormat.M4A -> TranscodeFormat.M4A
    OutputFormat.OGG -> TranscodeFormat.OGG
    OutputFormat.OPUS -> TranscodeFormat.OPUS
    OutputFormat.WAV -> TranscodeFormat.WAV
}
