package musicunlock.core

/*
 * Portions Copyright (c) 2019-2023 MengYX (unlock-music), MIT License.
 * Portions Copyright 2015 The Go Authors (BSD-3-Clause).
 * See THIRD_PARTY_NOTICES.md for the full license texts.
 */

/**
 * TEA (Tiny Encryption Algorithm) 实现。
 * 移植自 unlock-music (MIT) 的 tea.ts,该实现源自 golang.org/x/crypto/tea。
 * 解密使用偶数轮(128 位密钥,64 位块),语义与 JS 版本逐位对齐。
 */
class TeaCipher(key: ByteArray, private val rounds: Int) {

    private val k0: Int
    private val k1: Int
    private val k2: Int
    private val k3: Int

    init {
        require(key.size == 16) { "incorrect key size: ${key.size}" }
        require(rounds % 2 == 0) { "odd number of rounds specified" }
        k0 = readIntBE(key, 0)
        k1 = readIntBE(key, 4)
        k2 = readIntBE(key, 8)
        k3 = readIntBE(key, 12)
    }

    /** 解密一个 8 字节块。 */
    fun decrypt(dst: ByteArray, dstOff: Int, src: ByteArray, srcOff: Int) {
        var v0 = readIntBE(src, srcOff)
        var v1 = readIntBE(src, srcOff + 4)
        var sum = (DELTA * rounds) / 2
        for (i in 0 until rounds / 2) {
            v1 -= ((v0 shl 4) + k2) xor (v0 + sum).toInt() xor ((v0 ushr 5) + k3)
            v0 -= ((v1 shl 4) + k0) xor (v1 + sum).toInt() xor ((v1 ushr 5) + k1)
            sum -= DELTA
        }
        writeIntBE(dst, dstOff, v0)
        writeIntBE(dst, dstOff + 4, v1)
    }

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun writeIntBE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v ushr 24).toByte()
        b[off + 1] = (v ushr 16).toByte()
        b[off + 2] = (v ushr 8).toByte()
        b[off + 3] = v.toByte()
    }

    companion object {
        /** TEA 密钥调度常量 */
        private const val DELTA = 0x9E3779B9L
    }
}
