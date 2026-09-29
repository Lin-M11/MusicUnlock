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

/** 音频解码器:输入整个文件,输出可写入的音频数据与元数据。加密格式在此解密,原始格式直接透传。 */
interface MusicDecoder {
    fun decode(data: ByteArray, fileName: String): MusicResult

    /**
     * 只读取判断输出格式所需的容器信息,避免增量转换时完整解密。
     * 返回扩展名(不含点)。
     */
    fun outputExtension(data: ByteArray, fileName: String): String
}
