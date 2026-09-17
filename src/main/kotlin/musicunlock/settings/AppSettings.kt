package musicunlock.settings

import java.io.File

/** 转码输出格式；原始格式只解密，不重新编码。 */
enum class OutputFormat {
    ORIGINAL,
    MP3,
    FLAC,
    M4A,
    OGG,
    OPUS,
    WAV,
}

val outputBitrates: List<Int> = listOf(128, 192, 320)

val OutputFormat.usesBitrate: Boolean
    get() = this in setOf(OutputFormat.MP3, OutputFormat.M4A, OutputFormat.OGG, OutputFormat.OPUS)

val OutputFormat.extension: String?
    get() = when (this) {
        OutputFormat.ORIGINAL -> null
        OutputFormat.MP3 -> "mp3"
        OutputFormat.FLAC -> "flac"
        OutputFormat.M4A -> "m4a"
        OutputFormat.OGG -> "ogg"
        OutputFormat.OPUS -> "opus"
        OutputFormat.WAV -> "wav"
    }

/** 在线下载遇到同名文件时的处理方式。 */
enum class DownloadExistingPolicy {
    SKIP,
    OVERWRITE,
    RENAME,
    UPGRADE,
}

/** 在线歌曲的优先音质策略。 */
enum class QualityStrategy {
    HIGHEST,
    LOSSLESS_FIRST,
    MP3_320,
    BALANCED,
    SMALLEST,
}

/** 歌词写入方式。 */
enum class LyricsMode {
    OFF,
    SIDECAR,
    EMBED,
    BOTH,
}

/** 持久化的歌单追更配置。 */
data class PlaylistSubscription(
    val id: String,
    val platform: String,
    val playlistId: String,
    val playlistName: String,
    val outputDir: String,
    val enabled: Boolean = true,
    val syncIntervalMinutes: Int = 60,
    val lastSyncAt: Long = 0L,
    val lastAttemptAt: Long = 0L,
    val lastSyncError: String? = null,
    val lastTrackCount: Int = 0,
    val quality: QualityStrategy = QualityStrategy.HIGHEST,
    val outputTemplate: String = "{artist}/{album}/{title}",
    val metadata: Map<String, String> = emptyMap(),
)

enum class SyncDestinationType { LOCAL_FOLDER, WEBDAV }

enum class SyncMode { UPLOAD_ONLY, MIRROR, TWO_WAY }

enum class SyncConflictPolicy { KEEP_NEWER, KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH }

enum class MediaServerType { PLEX, JELLYFIN, NAVIDROME, SUBSONIC }

data class MediaServerConfig(
    val id: String,
    val name: String,
    val type: MediaServerType,
    val baseUrl: String,
    val username: String? = null,
    val libraryId: String? = null,
    val enabled: Boolean = true,
)

data class LibrarySyncProfile(
    val id: String,
    val name: String,
    val destinationType: SyncDestinationType,
    val localPath: String? = null,
    val remoteUrl: String? = null,
    val remoteDir: String = "MusicUnlock",
    val username: String? = null,
    val enabled: Boolean = true,
    val mode: SyncMode = SyncMode.UPLOAD_ONLY,
    val outputFormat: OutputFormat? = null,
    val bitrateKbps: Int = 320,
    val outputTemplate: String = "{artist}/{album}/{title}",
    val mirrorDeletes: Boolean = false,
    val conflictPolicy: SyncConflictPolicy = SyncConflictPolicy.KEEP_NEWER,
)

data class AutomationRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val inputDir: String,
    val outputDir: String,
    val outputFormat: OutputFormat = OutputFormat.ORIGINAL,
    val bitrateKbps: Int = 320,
    val outputTemplate: String = "{title}",
    val existingFilePolicy: DownloadExistingPolicy = DownloadExistingPolicy.SKIP,
    val minBytes: Long = 0L,
    val maxBytes: Long? = null,
    val extensions: List<String> = emptyList(),
    val fileNamePattern: String? = null,
    val regexPattern: Boolean = false,
    val priority: Int = 0,
    val scheduleStartMinute: Int? = null,
    val scheduleEndMinute: Int? = null,
    val daysOfWeek: List<Int> = emptyList(),
    val trashSourceOnSuccess: Boolean = false,
    val webhookUrl: String? = null,
    val postCommand: String? = null,
    val postCommandArgs: List<String> = emptyList(),
)

/** 接收一次纯设置变更，由调用方在最新配置上应用。 */
typealias SettingsUpdate = ((AppSettings) -> AppSettings) -> Unit

/** 可持久化的账号资料，用于登录态恢复时立即展示昵称与头像。 */
data class AccountSnapshot(
    val nickname: String,
    val avatarUrl: String?,
    val userId: String,
)

/** 应用级设置。字段带默认值，便于旧配置缺项时平滑读取。 */
data class AppSettings(
    val outputDir: String = defaultOutputDir(),
    val dedup: Boolean = false,
    val skipExisting: Boolean = true,
    val outputFormat: OutputFormat = OutputFormat.ORIGINAL,
    val bitrateKbps: Int = 320,
    val localOutputTemplate: String = "{title}",
    val localExistingFilePolicy: DownloadExistingPolicy = DownloadExistingPolicy.SKIP,
    val windowWidth: Int = 1120,
    val windowHeight: Int = 760,
    val neteaseCookie: String? = null,
    val qqCookie: String? = null,
    val kugouCookie: String? = null,
    val kuwoCookie: String? = null,
    val neteaseAccount: AccountSnapshot? = null,
    val qqAccount: AccountSnapshot? = null,
    val kugouAccount: AccountSnapshot? = null,
    val kuwoAccount: AccountSnapshot? = null,
    val downloadConcurrency: Int = 2,
    val downloadRetryCount: Int = 3,
    val downloadRetryDelayMillis: Long = 1_000,
    val downloadSpeedLimitKbps: Int = 0,
    val downloadTimeoutSeconds: Long = 30,
    val connectTimeoutSeconds: Long = 10,
    val proxyUrl: String? = null,
    val qualityStrategy: QualityStrategy = QualityStrategy.HIGHEST,
    val existingFilePolicy: DownloadExistingPolicy = DownloadExistingPolicy.SKIP,
    val outputTemplate: String = "{artist}/{album}/{title}",
    val lyricsMode: LyricsMode = LyricsMode.EMBED,
    val writeCoverSidecar: Boolean = false,
    val writePlaylistM3u8: Boolean = false,
    val subscriptions: List<PlaylistSubscription> = emptyList(),
    val watchFolders: List<String> = emptyList(),
    val watchEnabled: Boolean = false,
    val minimizeToTray: Boolean = false,
    val notifyOnComplete: Boolean = true,
    val preventSleepWhileDownloading: Boolean = true,
    val useSystemProxy: Boolean = true,
    val launchAtLogin: Boolean = false,
    val subscriptionNotifications: Boolean = true,
    val autoDownloadUpdates: Boolean = false,
    val automationRules: List<AutomationRule> = emptyList(),
    val librarySyncProfiles: List<LibrarySyncProfile> = emptyList(),
    val mediaServers: List<MediaServerConfig> = emptyList(),
    val playerLoudnessNormalization: Boolean = false,
    val playerBassBoostDb: Int = 0,
    val playerTrebleBoostDb: Int = 0,
    val playerFadeSeconds: Int = 2,
    val localApiEnabled: Boolean = false,
    val localApiPort: Int = 17_893,
    val localApiToken: String? = null,
    val webdavUrl: String? = null,
    val webdavUsername: String? = null,
    val webdavRemoteFile: String = "MusicUnlock-backup.zip",
)

/** 默认输出目录：优先使用用户主目录下的 Music/MusicUnlock。 */
fun defaultOutputDir(): String {
    val home = System.getProperty("user.home")
    return if (!home.isNullOrBlank()) {
        File(home, "Music/MusicUnlock").absolutePath
    } else {
        File("output").absolutePath
    }
}
