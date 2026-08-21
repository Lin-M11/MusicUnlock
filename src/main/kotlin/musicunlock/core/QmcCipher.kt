package musicunlock.core

/*
 * Portions Copyright (c) 2019-2023 MengYX (unlock-music), MIT License.
 * See THIRD_PARTY_NOTICES.md for the full license text.
 */

/**
 * QQ音乐 QMC 流密码:Static / Map / RC4 三种。
 * 移植自 unlock-music (MIT) 的 qmc_cipher.ts。
 */
object QmcCipher {

    /** 静态掩码盒(256 字节) */
    private val STATIC_CIPHER_BOX = intArrayOf(
        0x77, 0x48, 0x32, 0x73, 0xDE, 0xF2, 0xC0, 0xC8, 0x95, 0xEC, 0x30, 0xB2, 0x51, 0xC3, 0xE1, 0xA0,
        0x9E, 0xE6, 0x9D, 0xCF, 0xFA, 0x7F, 0x14, 0xD1, 0xCE, 0xB8, 0xDC, 0xC3, 0x4A, 0x67, 0x93, 0xD6,
        0x28, 0xC2, 0x91, 0x70, 0xCA, 0x8D, 0xA2, 0xA4, 0xF0, 0x08, 0x61, 0x90, 0x7E, 0x6F, 0xA2, 0xE0,
        0xEB, 0xAE, 0x3E, 0xB6, 0x67, 0xC7, 0x92, 0xF4, 0x91, 0xB5, 0xF6, 0x6C, 0x5E, 0x84, 0x40, 0xF7,
        0xF3, 0x1B, 0x02, 0x7F, 0xD5, 0xAB, 0x41, 0x89, 0x28, 0xF4, 0x25, 0xCC, 0x52, 0x11, 0xAD, 0x43,
        0x68, 0xA6, 0x41, 0x8B, 0x84, 0xB5, 0xFF, 0x2C, 0x92, 0x4A, 0x26, 0xD8, 0x47, 0x6A, 0x7C, 0x95,
        0x61, 0xCC, 0xE6, 0xCB, 0xBB, 0x3F, 0x47, 0x58, 0x89, 0x75, 0xC3, 0x75, 0xA1, 0xD9, 0xAF, 0xCC,
        0x08, 0x73, 0x17, 0xDC, 0xAA, 0x9A, 0xA2, 0x16, 0x41, 0xD8, 0xA2, 0x06, 0xC6, 0x8B, 0xFC, 0x66,
        0x34, 0x9F, 0xCF, 0x18, 0x23, 0xA0, 0x0A, 0x74, 0xE7, 0x2B, 0x27, 0x70, 0x92, 0xE9, 0xAF, 0x37,
        0xE6, 0x8C, 0xA7, 0xBC, 0x62, 0x65, 0x9C, 0xC2, 0x08, 0xC9, 0x88, 0xB3, 0xF3, 0x43, 0xAC, 0x74,
        0x2C, 0x0F, 0xD4, 0xAF, 0xA1, 0xC3, 0x01, 0x64, 0x95, 0x4E, 0x48, 0x9F, 0xF4, 0x35, 0x78, 0x95,
        0x7A, 0x39, 0xD6, 0x6A, 0xA0, 0x6D, 0x40, 0xE8, 0x4F, 0xA8, 0xEF, 0x11, 0x1D, 0xF3, 0x1B, 0x3F,
        0x3F, 0x07, 0xDD, 0x6F, 0x5B, 0x19, 0x30, 0x19, 0xFB, 0xEF, 0x0E, 0x37, 0xF0, 0x0E, 0xCD, 0x16,
        0x49, 0xFE, 0x53, 0x47, 0x13, 0x1A, 0xBD, 0xA4, 0xF1, 0x40, 0x19, 0x60, 0x0E, 0xED, 0x68, 0x09,
        0x06, 0x5F, 0x4D, 0xCF, 0x3D, 0x1A, 0xFE, 0x20, 0x77, 0xE4, 0xD9, 0xDA, 0xF9, 0xA4, 0x2B, 0x76,
        0x1C, 0x71, 0xDB, 0x00, 0xBC, 0xFD, 0x0C, 0x6C, 0xA5, 0x47, 0xF7, 0xF6, 0x00, 0x79, 0x4A, 0x11,
    )

    /** 流密码接口。 */
    interface StreamCipher {
        /** 原地解密 buf,offset 为音频内的全局偏移。 */
        fun decrypt(buf: ByteArray, offset: Int)
    }

    /** 静态掩码密码(老版本 qmc0 无密钥文件)。 */
    class StaticCipher : StreamCipher {

        override fun decrypt(buf: ByteArray, offset: Int) {
            for (i in buf.indices) {
                buf[i] = (buf[i].toInt() xor getMask(offset + i)).toByte()
            }
        }

        private fun getMask(offset: Int): Int {
            var off = offset
            if (off > 0x7FFF) off %= 0x7FFF
            return STATIC_CIPHER_BOX[(off * off + 27) and 0xFF]
        }
    }

    /** Map 掩码密码(密钥长度 &lt;= 300 时使用)。 */
    class MapCipher(key: ByteArray) : StreamCipher {

        private val key: IntArray
        private val n: Int

        init {
            require(key.isNotEmpty()) { "qmc/cipher_map: invalid key size" }
            this.key = IntArray(key.size) { key[it].toInt() and 0xFF }
            this.n = key.size
        }

        override fun decrypt(buf: ByteArray, offset: Int) {
            for (i in buf.indices) {
                buf[i] = (buf[i].toInt() xor getMask(offset + i)).toByte()
            }
        }

        private fun getMask(offset: Int): Int {
            var off = offset
            if (off > 0x7FFF) off %= 0x7FFF
            val idx = (off * off + 71214) % n
            return rotate(key[idx], idx and 0x7)
        }

        private fun rotate(value: Int, bits: Int): Int {
            val rotate = (bits + 4) % 8
            val left = value shl rotate
            val right = value shr rotate
            return (left or right) and 0xFF
        }
    }

    /** RC4 密码(密钥长度 &gt; 300 时使用),分段处理。 */
    class RC4Cipher(key: ByteArray) : StreamCipher {

        private val s: IntArray
        private val n: Int
        private val key: IntArray
        private val hash: Int

        init {
            require(key.isNotEmpty()) { "invalid key size" }
            this.n = key.size
            this.key = IntArray(n) { key[it].toInt() and 0xFF }

            // init seed box
            this.s = IntArray(n) { it and 0xFF }
            var j = 0
            for (i in 0 until n) {
                j = (s[i] + j + this.key[i % n]) % n
                val t = s[i]
                s[i] = s[j]
                s[j] = t
            }

            // init hash base
            var hashTmp = 1
            for (i in 0 until n) {
                val value = this.key[i]
                if (value == 0) continue
                val product = (hashTmp.toLong() and 0xFFFFFFFFL) * value
                val nextHash = product.toInt()
                if (nextHash == 0 || Integer.compareUnsigned(nextHash, hashTmp) <= 0) break
                hashTmp = nextHash
            }
            this.hash = hashTmp
        }

        override fun decrypt(buf: ByteArray, offset: Int) {
            var toProcess = buf.size
            var processed = 0
            var off = offset

            // Initial segment
            if (off < FIRST_SEGMENT_SIZE) {
                val lenSegment = minOf(buf.size, FIRST_SEGMENT_SIZE - off)
                encFirstSegment(buf, 0, lenSegment, off)
                toProcess -= lenSegment
                processed += lenSegment
                off += lenSegment
                if (toProcess == 0) return
            }

            // align segment
            if (off % SEGMENT_SIZE != 0) {
                val lenSegment = minOf(SEGMENT_SIZE - (off % SEGMENT_SIZE), toProcess)
                encASegment(buf, processed, processed + lenSegment, off)
                toProcess -= lenSegment
                processed += lenSegment
                off += lenSegment
                if (toProcess == 0) return
            }

            // batch process segments
            while (toProcess > SEGMENT_SIZE) {
                encASegment(buf, processed, processed + SEGMENT_SIZE, off)
                toProcess -= SEGMENT_SIZE
                processed += SEGMENT_SIZE
                off += SEGMENT_SIZE
            }

            // last segment
            if (toProcess > 0) {
                encASegment(buf, processed, processed + toProcess, off)
            }
        }

        private fun encFirstSegment(buf: ByteArray, start: Int, end: Int, offset: Int) {
            for (i in start until end) {
                val idx = getSegmentKeyDouble(offset + i - start)
                var xor = 0
                if (!idx.isNaN()) {
                    xor = key[idx.toInt() % n]
                }
                buf[i] = (buf[i].toInt() xor xor).toByte()
            }
        }

        private fun encASegment(buf: ByteArray, start: Int, end: Int, offset: Int) {
            val s = this.s.copyOf()

            val segKey = getSegmentKeyDouble(offset / SEGMENT_SIZE)
            if (segKey.isNaN()) {
                // JS 语义: skipLen 为 NaN,循环条件恒为假,该段不解密
                return
            }
            val skipLen = (offset % SEGMENT_SIZE) + segKey.toInt() % n

            var j = 0
            var k = 0
            for (i in -skipLen until end - start) {
                j = (j + 1) % n
                k = (s[j] + k) % n
                val t = s[k]
                s[k] = s[j]
                s[j] = t

                if (i >= 0) {
                    buf[start + i] = (buf[start + i].toInt() xor s[(s[j] + s[k]) % n]).toByte()
                }
            }
        }

        /** 返回可能为 NaN 的 segment key(与 JS 的 Infinity % N 语义对齐)。 */
        private fun getSegmentKeyDouble(id: Int): Double {
            val seed = key[id % n]
            if (seed == 0) return Double.NaN
            val idx = Math.floor(((hash.toLong() and 0xFFFFFFFFL).toDouble() / ((id + 1) * seed)) * 100.0)
            return idx % n
        }

        companion object {
            private const val FIRST_SEGMENT_SIZE = 0x80
            private const val SEGMENT_SIZE = 5120
        }
    }
}
