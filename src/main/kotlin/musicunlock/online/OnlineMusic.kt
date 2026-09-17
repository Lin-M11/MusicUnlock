package musicunlock.online

import java.nio.file.Files
import java.nio.file.Path

/** 在线下载支持的平台。界面文案和设置持久化都从这里取，避免各页面各写一份。 */
enum class MusicPlatform(
    val id: String,
    val displayName: String,
    val loginSubtitle: String,
) {
    NETEASE("netease", "网易云", "登录后可读取你的歌单并生成下载任务"),
    QQ("qq", "QQ 音乐", "登录后可读取你的歌单并生成下载任务"),
    KUGOU("kugou", "酷狗音乐", "登录后可读取收藏与创建的歌单"),
    KUWO("kuwo", "酷我音乐", "登录后可读取收藏与创建的歌单"),
}

/** 平台无关的账号信息。 */
data class MusicAccount(
    val nickname: String,
    val avatarUrl: String?,
    val userId: String,
)

/** 平台无关的歌单信息，metadata 用于保存平台特有的拉取参数。 */
data class MusicPlaylist(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val metadata: Map<String, String> = emptyMap(),
)

/** 平台无关的歌曲信息，metadata 用于保存 hash、mid 等平台特有字段。 */
data class MusicSong(
    val id: String,
    val name: String,
    val artists: List<String>,
    val albumName: String?,
    val coverUrl: String?,
    val metadata: Map<String, String> = emptyMap(),
    val durationSeconds: Int? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val composer: String? = null,
    val isrc: String? = null,
) {
    val artistText: String get() = artists.joinToString(" / ")
}

/** 二维码登录轮询状态。 */
enum class OnlineQrState { WAIT, SCANNED, EXPIRED, SUCCESS, ERROR }

/** 二维码登录轮询结果。 */
data class OnlineQrResult(val state: OnlineQrState, val message: String? = null)

/** 搜索结果类型。 */
enum class SearchResultKind { SONG, PLAYLIST, ALBUM, ARTIST }

data class MusicSearchResult(
    val platform: MusicPlatform,
    val kind: SearchResultKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val coverUrl: String? = null,
    val song: MusicSong? = null,
    val playlist: MusicPlaylist? = null,
)

/** 单曲下载结果。 */
class OnlineDownloadOutcome(
    val ok: Boolean,
    val file: java.io.File?,
    val message: String,
)

/** 提交下载任务的结果；只有接入任务面板的平台需要实现。 */
data class SubmitOutcome(val ok: Boolean, val message: String)

data class ProviderCapabilities(
    val search: Boolean = true,
    val playlists: Boolean = true,
    val lyrics: Boolean = true,
    val resumeDownload: Boolean = false,
    val lossless: Boolean = true,
    val directPlayback: Boolean = true,
)

/**
 * 在线音乐平台统一接口。
 *
 * 页面只依赖这组方法；各平台负责把官方接口映射为统一模型，并可保留
 * 自己的签名、Cookie、二维码和错误文案。
 */
interface OnlineMusicProvider {
    val platform: MusicPlatform

    fun capabilities(): ProviderCapabilities = ProviderCapabilities()

    fun healthCheck(): Result<Unit> = runCatching { account() }

    fun restoreSession(cookieHeader: String): MusicAccount
    fun exportSessionCookie(): String?
    fun logout()
    fun account(): MusicAccount?

    fun playlists(): List<MusicPlaylist>
    fun songs(playlist: MusicPlaylist): List<MusicSong>
    fun playback(song: MusicSong): PlaybackSource

    /** 按指定音质优先级解析播放地址；平台默认沿用原有自动降级逻辑。 */
    fun playback(song: MusicSong, quality: musicunlock.settings.QualityStrategy): PlaybackSource = playback(song)

    /** 获取歌词；平台不支持时返回 null。 */
    fun lyrics(song: MusicSong): MusicLyrics? = null

    /** 搜索歌曲、歌单、专辑和歌手。 */
    fun search(query: String, limit: Int = 30): List<MusicSearchResult> = emptyList()

    /** 获取单曲详情。 */
    fun song(songId: String): MusicSong? = null

    /** 获取歌单详情。 */
    fun playlist(playlistId: String): MusicPlaylist? = null

    /** 获取专辑及曲目。 */
    fun album(albumId: String): Pair<MusicPlaylist, List<MusicSong>>? = null

    /** 获取歌手热门歌曲。 */
    fun artistSongs(artistId: String, limit: Int = 100): List<MusicSong> = emptyList()

    /** 按平台要求下载到指定路径；失败时抛出异常。 */
    fun download(url: String, target: Path)

    /**
     * 支持断点续传和进度回调的下载入口。
     * 未覆盖的平台会回退到原下载方法；实现了该方法的平台可上报真实进度。
     */
    fun download(
        url: String,
        target: Path,
        offset: Long,
        onProgress: (downloaded: Long, total: Long?, bytesPerSecond: Long) -> Unit,
        shouldContinue: () -> Boolean,
    ) {
        if (offset > 0) throw ClassifiedDownloadException(
            DownloadErrorKind.NETWORK,
            "当前平台暂不支持从已有进度继续下载",
            true,
        )
        download(url, target)
        val size = Files.size(target)
        onProgress(size, size, 0L)
    }

    /** 读取头像、歌单封面等远程图片；失败时返回 null。 */
    fun bytes(url: String): ByteArray?
}
