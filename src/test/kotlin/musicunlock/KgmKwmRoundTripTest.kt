package musicunlock

import musicunlock.core.KgmDecoder
import musicunlock.core.KwmDecoder
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * KGM/VPR/KWM 没有官方测试向量,通过"加密端 + 解密端"往返测试验证正确性。
 */
class KgmKwmRoundTripTest {

    @Test
    fun kgmRoundTripFlac() {
        val plain = plainAudio(10000, flac = true)
        val key = randomKey17(7)
        val file = buildKgmFile(plain, KgmDecoder.KGM_HEADER, key, vpr = false)
        val result = KgmDecoder.decode(file, "test.kgm")
        assertContentEquals(plain, result.data)
        assertEquals("flac", result.ext)
    }

    @Test
    fun kgmRoundTripMp3() {
        val plain = plainAudio(7000, flac = false)
        val key = randomKey17(8)
        val file = buildKgmFile(plain, KgmDecoder.KGM_HEADER, key, vpr = false)
        val result = KgmDecoder.decode(file, "test.kgma")
        assertContentEquals(plain, result.data)
        assertEquals("mp3", result.ext)
    }

    @Test
    fun vprRoundTrip() {
        val plain = plainAudio(5000, flac = true)
        val key = randomKey17(9)
        val file = buildKgmFile(plain, KgmDecoder.VPR_HEADER, key, vpr = true)
        val result = KgmDecoder.decode(file, "test.vpr")
        assertContentEquals(plain, result.data)
        assertEquals("flac", result.ext)
    }

    @Test
    fun kwmRoundTrip() {
        val plain = plainAudio(8000, flac = false)
        val fileKey = ByteArray(8)
        Random(10).nextBytes(fileKey)

        val file = ByteArray(0x400 + plain.size)
        val magic = "yeelion-kuwo-tme".toByteArray(Charsets.US_ASCII)
        System.arraycopy(magic, 0, file, 0, magic.size)
        System.arraycopy(fileKey, 0, file, 0x18, 8)

        var keyLong = 0L
        for (i in 7 downTo 0) {
            keyLong = (keyLong shl 8) or (fileKey[i].toLong() and 0xFF)
        }
        val keyStr = java.lang.Long.toUnsignedString(keyLong)
        val trimmed = if (keyStr.length > 32) {
            keyStr.substring(0, 32)
        } else {
            buildString {
                append(keyStr)
                while (length < 32) append(keyStr)
            }.substring(0, 32)
        }
        val predefined = "MoOtOiTvINGwd2E6n0E1i7L5t2IoOoNk"
        val mask = ByteArray(32) { (predefined[it].code xor trimmed[it].code).toByte() }
        for (i in plain.indices) {
            file[0x400 + i] = (plain[i].toInt() xor mask[i % 0x20].toInt()).toByte()
        }

        val result = KwmDecoder.decode(file, "test.kwm")
        assertContentEquals(plain, result.data)
        assertEquals("mp3", result.ext)
    }

    // ---------- 构造端 ----------

    private fun plainAudio(length: Int, flac: Boolean): ByteArray {
        val p = ByteArray(length)
        val header = if (flac) {
            byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0x00, 0x00, 0x00, 0x22)
        } else {
            byteArrayOf(0x49, 0x44, 0x33, 0x04, 0x00, 0x00, 0x00)
        }
        System.arraycopy(header, 0, p, 0, header.size)
        Random(42).nextBytes(p)
        System.arraycopy(header, 0, p, 0, header.size)
        return p
    }

    private fun f(x: Int): Int = x xor ((x and 0x0F) shl 4)

    private fun getMask(pos: Int): Int {
        var offset = pos shr 4
        var value = 0
        while (offset >= 0x11) {
            value = value xor KgmDecoder.KGM_TABLE_1[offset % 272]
            offset = offset shr 4
            value = value xor KgmDecoder.KGM_TABLE_2[offset % 272]
            offset = offset shr 4
        }
        return KgmDecoder.KGM_MASK_V2_PRE_DEF[pos % 272] xor value
    }

    private fun buildKgmFile(plain: ByteArray, header: IntArray, key: ByteArray, vpr: Boolean): ByteArray {
        val headerLen = 0x2C
        val file = ByteArray(headerLen + plain.size)
        for (i in 0 until 16) file[i] = header[i].toByte()
        writeUInt32LE(file, 0x10, headerLen)
        System.arraycopy(key, 0, file, 0x1C, 16)

        val encrypted = ByteArray(plain.size)
        for (pos in plain.indices) {
            val p = plain[pos].toInt() and 0xFF
            val msk = getMask(pos)
            val fMask = f(msk)
            val fPlain = f(p xor fMask xor (if (vpr) KgmDecoder.KGM_VPR_MASK_DIFF[pos % 17] else 0))
            encrypted[pos] = ((key[pos % 17].toInt() and 0xFF) xor fPlain).toByte()
        }
        System.arraycopy(encrypted, 0, file, headerLen, plain.size)
        return file
    }

    private fun randomKey17(seed: Long): ByteArray {
        val key = ByteArray(17)
        Random(seed).nextBytes(key)
        key[16] = 0
        return key
    }

    private fun writeUInt32LE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
