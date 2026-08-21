package musicunlock.core

/**
 * 解密结果:包含解密后的音频数据、探测到的真实扩展名,
 * 以及 NCM 特有的元数据(歌名/歌手/专辑/封面)。
 */
class MusicResult(
    val data: ByteArray,
    val ext: String,
    val musicName: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val cover: ByteArray? = null,
)

/** 加密音乐格式解码器:输入整个加密文件,输出解密后的音频与元数据。 */
interface MusicDecoder {
    fun decode(data: ByteArray, fileName: String): MusicResult
}
