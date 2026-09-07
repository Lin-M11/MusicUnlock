package musicunlock.qq

/**
 * QQ 音乐下载功能的数据模型。
 *
 * 官方接口的原始 JSON 由 [QqMusicApi] 解析（映射逻辑集中在 API 内便于测试），
 * 这里只定义 UI 与下载器使用的稳定领域对象。
 */

/** 已登录的 QQ 音乐账号信息。 */
class QqAccount(
    val nickname: String,
    val avatarUrl: String?,
    val musicid: String,
)

/** 用户歌单（创建的歌单；dirId==201 表示「我喜欢的音乐」）。 */
class QqPlaylist(
    val id: Long,
    val dirId: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
) {
    /** 「我喜欢的音乐」是 dirId 201 的特殊歌单，拉取方式与其他歌单不同。 */
    val isFavorite: Boolean get() = dirId == 201L
}

/** 单曲信息（来自歌单详情）。 */
class QqSong(
    val mid: String,
    val mediaMid: String?,
    val name: String,
    val artists: List<String>,
    val albumName: String?,
    val albumMid: String?,
    /** 各档 MP3 在官方接口中声明的体积；0 表示该档不存在。 */
    val size320: Long,
    val size128: Long,
) {
    val artistText: String get() = artists.joinToString(" / ")
}

/** 请求播放地址的结果：url 与 reason 二选一。 */
class QqSongUrlResult(
    val url: String?,
    val reason: String?,
) {
    val ok: Boolean get() = url != null
}

/** 二维码登录轮询状态。 */
enum class QqLoginState { WAIT, SCANNED, EXPIRED, SUCCESS, ERROR }

/** 二维码登录轮询结果。 */
class QqQrResult(val state: QqLoginState, val message: String?)

/** 下载单曲的结果。 */
class QqDownloadOutcome(
    val ok: Boolean,
    val file: java.io.File?,
    val message: String,
)
