package musicunlock.core

/**
 * .ncm 容器内嵌的元数据(即 "music:" JSON 载荷)。
 * 服务端返回的其它字段会被忽略。
 */
class NcmMetadata {
    var musicName: String? = null
    var artist: Array<Array<String>>? = null
    var album: String? = null
    var format: String? = null

    /** 第一个歌手组的第一个歌手;缺省时返回 null。 */
    fun firstArtist(): String? = artist?.firstOrNull()?.firstOrNull()
}
