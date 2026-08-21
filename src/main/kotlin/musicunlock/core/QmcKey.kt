package musicunlock.core

/*
 * Portions Copyright (c) 2019-2023 MengYX (unlock-music), MIT License.
 * See THIRD_PARTY_NOTICES.md for the full license text.
 */

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.abs
import kotlin.math.tan

/**
 * QQ音乐 QMC 密钥派生算法。
 * 移植自 unlock-music (MIT) 的 qmc_key.ts,包含:
 *  - "QQMusic EncV2,Key:" 前缀的双重 TEA 解密
 *  - simpleMakeKey + TEA 的密钥还原
 */
object QmcKey {

    private const val ENC_V2_PREFIX = "QQMusic EncV2,Key:"
    private const val SALT_LEN = 2
    private const val ZERO_LEN = 7

    private val MIX_KEY_1 = intArrayOf(
        0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40, 0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28,
    )
    private val MIX_KEY_2 = intArrayOf(
        0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25, 0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54,
    )

    /** 从 QMC 文件中的 raw key 派生出真正的解密密钥。 */
    fun deriveKey(raw: ByteArray): ByteArray {
        var rawDec = base64Decode(raw)
        if (rawDec.size < 16) throw IllegalArgumentException("key length is too short")
        rawDec = decryptV2Key(rawDec)

        val simpleKey = simpleMakeKey(106, 8)
        val teaKey = ByteArray(16)
        for (i in 0 until 8) {
            teaKey[i shl 1] = simpleKey[i]
            teaKey[(i shl 1) + 1] = rawDec[i]
        }
        val sub = decryptTencentTea(rawDec.copyOfRange(8, rawDec.size), teaKey)
        return rawDec.copyOfRange(0, 8) + sub
    }

    /** 生成 simple key(仅用于单元测试)。 */
    fun simpleMakeKey(salt: Int, length: Int): ByteArray {
        val keyBuf = ByteArray(length)
        for (i in 0 until length) {
            val tmp = tan(salt + i * 0.1)
            keyBuf[i] = ((abs(tmp) * 100.0).toInt() and 0xFF).toByte()
        }
        return keyBuf
    }

    /** 处理 EncV2 密钥:若以 "QQMusic EncV2,Key:" 开头,双重 TEA 解密后再 Base64 解码。 */
    private fun decryptV2Key(key: ByteArray): ByteArray {
        if (key.size < ENC_V2_PREFIX.length || !hasAsciiPrefix(key, ENC_V2_PREFIX)) return key
        var out = decryptTencentTea(key.copyOfRange(ENC_V2_PREFIX.length, key.size), MIX_KEY_1.toBytes())
        out = decryptTencentTea(out, MIX_KEY_2.toBytes())
        val keyDec = base64Decode(out)
        if (keyDec.size < 16) throw IllegalArgumentException("EncV2 key decode failed")
        return keyDec
    }

    /** 腾讯 TEA 解密(带 CBC 式 IV 处理)。 */
    private fun decryptTencentTea(inBuf: ByteArray, key: ByteArray): ByteArray {
        require(inBuf.size % 8 == 0) { "inBuf size not a multiple of the block size" }
        require(inBuf.size >= 16) { "inBuf size too small" }

        val blk = TeaCipher(key, 32)

        val tmpBuf = inBuf.copyOfRange(0, 8)
        blk.decrypt(tmpBuf, 0, tmpBuf, 0)

        val nPadLen = tmpBuf[0].toInt() and 0x7
        // 密文格式: PadLen(1byte)+Padding(var,0-7byte)+Salt(2byte)+Body(var)+Zero(7byte)
        val outLen = inBuf.size - 1 - nPadLen - SALT_LEN - ZERO_LEN
        val outBuf = ByteArray(outLen)

        val ivPrev = ByteArray(8)
        var ivCur = inBuf.copyOfRange(0, 8)
        var inBufPos = 8
        var tmpIdx = 1 + nPadLen

        // 跳过 Salt
        var i = 1
        while (i <= SALT_LEN) {
            if (tmpIdx < 8) {
                tmpIdx++
                i++
            } else {
                cryptBlock(inBuf, ivPrev, ivCur, tmpBuf, blk, inBufPos)
                inBufPos += 8
                tmpIdx = 0
            }
        }

        // 还原明文
        var outBufPos = 0
        while (outBufPos < outLen) {
            if (tmpIdx < 8) {
                outBuf[outBufPos] = (tmpBuf[tmpIdx].toInt() xor ivPrev[tmpIdx].toInt()).toByte()
                outBufPos++
                tmpIdx++
            } else {
                cryptBlock(inBuf, ivPrev, ivCur, tmpBuf, blk, inBufPos)
                inBufPos += 8
                tmpIdx = 0
            }
        }

        // 校验 Zero
        for (j in 1..ZERO_LEN) {
            if ((tmpBuf[tmpIdx].toInt() and 0xFF) != (ivPrev[tmpIdx].toInt() and 0xFF)) {
                throw IllegalStateException("zero check failed")
            }
        }
        return outBuf
    }

    private fun cryptBlock(inBuf: ByteArray, ivPrev: ByteArray, ivCur: ByteArray, tmpBuf: ByteArray, blk: TeaCipher, inBufPos: Int) {
        System.arraycopy(ivCur, 0, ivPrev, 0, 8)
        System.arraycopy(inBuf, inBufPos, ivCur, 0, 8)
        for (j in 0 until 8) {
            tmpBuf[j] = (tmpBuf[j].toInt() xor ivCur[j].toInt()).toByte()
        }
        blk.decrypt(tmpBuf, 0, tmpBuf, 0)
    }

    private fun hasAsciiPrefix(data: ByteArray, prefix: String): Boolean {
        if (data.size < prefix.length) return false
        for (i in prefix.indices) {
            if ((data[i].toInt() and 0xFF) != prefix[i].code) return false
        }
        return true
    }

    private fun IntArray.toBytes(): ByteArray {
        val out = ByteArray(size)
        for (i in indices) out[i] = this[i].toByte()
        return out
    }

    /** 宽松 Base64 解码(与 JS Buffer.from(str, 'base64') 行为对齐:忽略非法字符)。 */
    private fun base64Decode(data: ByteArray): ByteArray {
        val str = String(data, StandardCharsets.ISO_8859_1)
        return Base64.getMimeDecoder().decode(str)
    }
}
