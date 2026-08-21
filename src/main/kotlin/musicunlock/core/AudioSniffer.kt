package musicunlock.core

/**
 * 音频真实格式探测:通过文件头魔数判断解密后音频的类型。
 */
object AudioSniffer {

    private val MP3_HEADER = byteArrayOf(0x49, 0x44, 0x33)          // "ID3"
    private val FLAC_HEADER = byteArrayOf(0x66, 0x4C, 0x61, 0x43)   // "fLaC"
    private val OGG_HEADER = byteArrayOf(0x4F, 0x67, 0x67, 0x53)    // "OggS"
    private val M4A_HEADER = byteArrayOf(0x66, 0x74, 0x79, 0x70)    // "ftyp"
    private val WAV_HEADER = byteArrayOf(0x52, 0x49, 0x46, 0x46)    // "RIFF"
    private val WMA_HEADER = byteArrayOf(
        0x30, 0x26, 0xB2.toByte(), 0x75, 0x8E.toByte(), 0x66, 0xCF.toByte(), 0x11,
        0xA6.toByte(), 0xD9.toByte(), 0x00, 0xAA.toByte(), 0x00, 0x62, 0xCE.toByte(), 0x6C,
    )
    private val AAC_HEADER = byteArrayOf(0xFF.toByte(), 0xF1.toByte())
    private val DFF_HEADER = byteArrayOf(0x46, 0x52, 0x4D, 0x38)     // "FRM8"

    private fun hasPrefix(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }

    private fun hasPrefixAt(data: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (data.size < offset + prefix.size) return false
        for (i in prefix.indices) {
            if (data[offset + i] != prefix[i]) return false
        }
        return true
    }

    /**
     * 探测音频格式。
     *
     * @param data        音频数据
     * @param fallbackExt 无法识别时的兜底扩展名
     * @return 扩展名(不含点)
     */
    fun sniff(data: ByteArray, fallbackExt: String?): String {
        if (hasPrefix(data, MP3_HEADER)) return "mp3"
        if (hasPrefix(data, FLAC_HEADER)) return "flac"
        if (hasPrefix(data, OGG_HEADER)) return "ogg"
        if (hasPrefixAt(data, 4, M4A_HEADER)) return "m4a"
        if (hasPrefix(data, WAV_HEADER)) return "wav"
        if (hasPrefix(data, WMA_HEADER)) return "wma"
        // MP3 帧同步字(裸 MP3 无 ID3 头)
        if (data.size >= 2 && (data[0].toInt() and 0xFF) == 0xFF && (data[1].toInt() and 0xE0) == 0xE0) return "mp3"
        if (hasPrefix(data, AAC_HEADER)) return "aac"
        if (hasPrefix(data, DFF_HEADER)) return "dff"
        return fallbackExt ?: "mp3"
    }
}
