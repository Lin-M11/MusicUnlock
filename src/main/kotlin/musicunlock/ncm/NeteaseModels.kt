package musicunlock.ncm

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * 网易云官方网页接口的数据模型（Gson 直接映射原始 JSON，
 * 再转换为稳定的领域对象）。仅映射本功能需要的字段。
 */
internal object NeteaseJson {
    val gson = Gson()
}

// ---- 原始响应 ----

internal class RawCode(val code: Int = 0)

internal class RawUnikeyResponse {
    var code: Int = 0
    var unikey: String? = null
}

internal class RawQrCheckResponse {
    var code: Int = 0
    var message: String? = null
}

internal class RawAccountResponse {
    var code: Int = 0
    var profile: RawProfile? = null
}

internal class RawProfile {
    var nickname: String? = null
    var avatarUrl: String? = null
    var userId: Long = 0
}

internal class RawUserPlaylistResponse {
    var code: Int = 0
    var more: Boolean = false
    var playlist: List<RawPlaylist>? = null
}

internal class RawPlaylist {
    var id: Long = 0
    var name: String? = null
    var coverImgUrl: String? = null
    var trackCount: Int = 0
}

internal class RawPlaylistDetailResponse {
    var code: Int = 0
    var playlist: RawPlaylistDetail? = null
}

internal class RawPlaylistDetail {
    var trackIds: List<RawTrackId>? = null
}

internal class RawTrackId {
    var id: Long = 0
}

internal class RawSongDetailResponse {
    var code: Int = 0
    var songs: List<RawSong>? = null
}

internal class RawSong {
    var id: Long = 0
    var name: String? = null
    var ar: List<RawArtist>? = null
    var al: RawAlbum? = null
}

internal class RawArtist {
    var name: String? = null
}

internal class RawAlbum {
    var name: String? = null
    var picUrl: String? = null
}

internal class RawUrlResponse {
    var code: Int = 0
    var data: List<RawSongUrl>? = null
}

internal class RawSongUrl {
    var id: Long = 0
    var url: String? = null
    var br: Int = 0
    var size: Long = 0
    var type: String? = null
}

// ---- 领域模型 ----

/** 已登录的网易云账号信息。 */
class NeteaseAccount(
    val nickname: String,
    val avatarUrl: String?,
    val userId: Long,
)

/** 用户歌单（含收藏）。 */
class NeteasePlaylist(
    val id: Long,
    val name: String,
    val coverImgUrl: String?,
    val trackCount: Int,
)

/** 单曲信息（来自歌单或歌曲详情）。 */
class NeteaseSong(
    val id: Long,
    val name: String,
    val artists: List<String>,
    val albumName: String?,
    val albumPicUrl: String?,
) {
    val artistText: String get() = artists.joinToString(" / ")
}

/** 歌曲播放地址（按码率请求的结果）。 */
class NeteaseSongUrl(
    val id: Long,
    val url: String,
    val br: Int,
    val type: String?,
    val size: Long,
)

/** 二维码登录轮询结果。 */
enum class QrLoginState { WAIT, SCANNED, EXPIRED, SUCCESS, UNKNOWN }

/** 二维码登录轮询结果。 */
class QrCheckResult(val state: QrLoginState, val message: String?)
