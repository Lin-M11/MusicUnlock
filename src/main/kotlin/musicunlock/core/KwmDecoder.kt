package musicunlock.core

/*
 * Portions Copyright (c) 2019-2023 MengYX (unlock-music), MIT License.
 * See THIRD_PARTY_NOTICES.md for the full license text.
 */

/**
 * 酷我 KWM 格式解码器。
 * 移植自 unlock-music (MIT) 的 kwm.ts。
 * 头部 16 字节魔数,0x18 处 8 字节小端密钥,0x400 起为加密音频。
 */
object KwmDecoder : MusicDecoder {

    /** "yeelion-kuwo-tme" */
    private val MAGIC_HEADER = byteArrayOf(
        0x79, 0x65, 0x65, 0x6C, 0x69, 0x6F, 0x6E, 0x2D,
        0x6B, 0x75, 0x77, 0x6F, 0x2D, 0x74, 0x6D, 0x65,
    )

    /** "yeelion-kuwo\0\0\0\0" */
    private val MAGIC_HEADER_2 = byteArrayOf(
        0x79, 0x65, 0x65, 0x6C, 0x69, 0x6F, 0x6E, 0x2D,
        0x6B, 0x75, 0x77, 0x6F, 0x00, 0x00, 0x00, 0x00,
    )

    private const val PRE_DEFINED_KEY = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk"

    override fun decode(data: ByteArray, fileName: String): MusicResult {
        if (!startsWith(data, MAGIC_HEADER) && !startsWith(data, MAGIC_HEADER_2)) {
            // 部分 kwm 文件实际是未加密的 aac,直接透传
            if ("aac" == AudioSniffer.sniff(data, null)) {
                return MusicResult(data, "aac")
            }
            throw IllegalArgumentException("not a valid kwm file")
        }
        if (data.size < 0x400) throw IllegalArgumentException("kwm file too small")

        val fileKey = data.copyOfRange(0x18, 0x20)
        val mask = createMaskFromKey(fileKey)
        val audio = data.copyOfRange(0x400, data.size)
        for (i in audio.indices) {
            audio[i] = (audio[i].toInt() xor mask[i % 0x20].toInt()).toByte()
        }

        return MusicResult(audio, AudioSniffer.sniff(audio, null))
    }

    private fun createMaskFromKey(keyBytes: ByteArray): ByteArray {
        val keyLong = readUInt64LE(keyBytes, 0)
        val keyStrTrim = trimKey(java.lang.Long.toUnsignedString(keyLong))
        val key = ByteArray(32)
        for (i in 0 until 32) {
            key[i] = (PRE_DEFINED_KEY[i].code xor keyStrTrim[i].code).toByte()
        }
        return key
    }

    private fun trimKey(keyRaw: String): String {
        val lenRaw = keyRaw.length
        if (lenRaw > 32) return keyRaw.substring(0, 32)
        if (lenRaw < 32) {
            val sb = StringBuilder(keyRaw)
            while (sb.length < 32) {
                sb.append(keyRaw)
            }
            return sb.substring(0, 32)
        }
        return keyRaw
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) {
            if (data[i] != prefix[i]) return false
        }
        return true
    }

    private fun readUInt64LE(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) {
            v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return v
    }
}
