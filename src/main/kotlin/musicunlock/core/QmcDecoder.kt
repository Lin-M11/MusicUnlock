package musicunlock.core

/*
 * Portions Copyright (c) 2019-2023 MengYX (unlock-music), MIT License.
 * See THIRD_PARTY_NOTICES.md for the full license text.
 */

/**
 * QQ音乐 QMC 系列格式解码器(qmc0/qmc3/qmcflac/qmcogg/mflac/mgg 等)。
 * 移植自 unlock-music (MIT) 的 qmc.ts 的 QmcDecoder。
 * 兼容三种容器:
 *  - 尾部 "QTag" 标签(v2 新版)
 *  - 尾部 4 字节小端密钥长度(v1 老版)
 *  - 无密钥文件(静态掩码)
 */
class QmcDecoder : MusicDecoder {

    private val file: ByteArray
    private val size: Int
    private var audioSize: Int
    private var cipher: QmcCipher.StreamCipher?

    constructor() {
        this.file = ByteArray(0)
        this.size = 0
        this.audioSize = 0
        this.cipher = null
    }

    constructor(file: ByteArray) {
        this.file = file
        this.size = file.size
        this.audioSize = 0
        this.cipher = null
        searchKey()
    }

    private fun searchKey() {
        if (size < 4) throw IllegalArgumentException("QMC file too small")
        val last4Str = String(file.copyOfRange(size - 4, size), Charsets.US_ASCII)
        when (last4Str) {
            "STag" -> throw IllegalStateException("文件中没有写入密钥,无法解锁,请降级App并重试")
            "QTag" -> {
                val keySize = readUInt32BE(file, size - 8)
                audioSize = (size - keySize - 8).toInt()
                if (audioSize <= 0) throw IllegalArgumentException("invalid QMC audio size")
                val rawKey = file.copyOfRange(audioSize, size - 8)
                val keyEnd = rawKey.indexOf(BYTE_COMMA)
                if (keyEnd < 0) throw IllegalArgumentException("invalid key: search raw key failed")
                setCipher(rawKey.copyOfRange(0, keyEnd))
            }
            else -> {
                val keySize = readUInt32LE(file, size - 4)
                if (keySize < 0x400) {
                    audioSize = (size - keySize - 4).toInt()
                    if (audioSize <= 0) throw IllegalArgumentException("invalid QMC audio size")
                    setCipher(file.copyOfRange(audioSize, size - 4))
                } else {
                    audioSize = size
                    cipher = QmcCipher.StaticCipher()
                }
            }
        }
    }

    private fun setCipher(keyRaw: ByteArray) {
        val keyDec = QmcKey.deriveKey(keyRaw)
        cipher = if (keyDec.size > 300) {
            QmcCipher.RC4Cipher(keyDec)
        } else {
            QmcCipher.MapCipher(keyDec)
        }
    }

    fun decrypt(): ByteArray {
        val cipher = cipher ?: throw IllegalStateException("no cipher found")
        if (audioSize <= 0) throw IllegalStateException("invalid audio size")
        val audioBuf = file.copyOfRange(0, audioSize)
        cipher.decrypt(audioBuf, 0)
        return audioBuf
    }

    override fun decode(data: ByteArray, fileName: String): MusicResult {
        val audio = QmcDecoder(data).decrypt()
        return MusicResult(audio, AudioSniffer.sniff(audio, null))
    }

    private fun ByteArray.indexOf(value: Int): Int {
        for (i in indices) {
            if (this[i].toInt() and 0xFF == value) return i
        }
        return -1
    }

    private fun readUInt32BE(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    private fun readUInt32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    companion object {
        private const val BYTE_COMMA = ','.code
    }
}
