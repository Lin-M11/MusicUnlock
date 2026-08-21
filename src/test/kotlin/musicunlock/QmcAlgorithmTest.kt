package musicunlock

import musicunlock.core.QmcCipher
import musicunlock.core.QmcDecoder
import musicunlock.core.QmcKey
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * 使用 unlock-music 项目自带的真实测试向量,验证 QMC 移植算法的正确性。
 */
class QmcAlgorithmTest {

    private fun load(name: String): ByteArray {
        val stream: InputStream = checkNotNull(javaClass.getResourceAsStream("/testdata/$name")) {
            "missing testdata: $name"
        }
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val len = input.read(buf)
                if (len <= 0) break
                out.write(buf, 0, len)
            }
            return out.toByteArray()
        }
    }

    @Test
    fun simpleMakeKey() {
        val key = QmcKey.simpleMakeKey(106, 8)
        val expected = byteArrayOf(0x69, 0x56, 0x46, 0x38, 0x2B, 0x20, 0x15, 0x0B)
        assertContentEquals(expected, key)
    }

    @Test
    fun deriveKeyRealFiles() {
        val cases = listOf("mflac_map", "mgg_map", "mflac0_rc4", "mflac_rc4")
        for (name in cases) {
            val raw = load("${name}_key_raw.bin")
            val expected = load("${name}_key.bin")
            val derived = QmcKey.deriveKey(raw)
            assertEquals(expected.size, derived.size, "key length mismatch: $name")
            assertContentEquals(expected, derived, "key mismatch: $name")
        }
    }

    @Test
    fun staticCipherOffset0() {
        val c = QmcCipher.StaticCipher()
        val buf = ByteArray(16)
        c.decrypt(buf, 0)
        val expected = byteArrayOf(
            0xC3.toByte(), 0x4A, 0xD6.toByte(), 0xCA.toByte(), 0x90.toByte(), 0x67, 0xF7.toByte(), 0x52,
            0xD8.toByte(), 0xA1.toByte(), 0x66, 0x62, 0x9F.toByte(), 0x5B, 0x09, 0x00,
        )
        assertContentEquals(expected, buf)
    }

    @Test
    fun staticCipherOffset0x7ff8() {
        val c = QmcCipher.StaticCipher()
        val buf = ByteArray(16)
        c.decrypt(buf, 0x7ff8)
        val expected = byteArrayOf(
            0xD8.toByte(), 0x52, 0xF7.toByte(), 0x67, 0x90.toByte(), 0xCA.toByte(), 0xD6.toByte(), 0x4A,
            0x4A, 0xD6.toByte(), 0xCA.toByte(), 0x90.toByte(), 0x67, 0xF7.toByte(), 0x52, 0xD8.toByte(),
        )
        assertContentEquals(expected, buf)
    }

    @Test
    fun mapCipherMask() {
        val key = ByteArray(256) { it.toByte() }
        val c = QmcCipher.MapCipher(key)
        val buf = ByteArray(16)
        c.decrypt(buf, 0)
        val expected = byteArrayOf(
            0xBB.toByte(), 0x7D, 0x80.toByte(), 0xBE.toByte(), 0xFF.toByte(), 0x38, 0x81.toByte(), 0xFB.toByte(),
            0xBB.toByte(), 0xFF.toByte(), 0x82.toByte(), 0x3C, 0xFF.toByte(), 0xBA.toByte(), 0x83.toByte(), 0x79,
        )
        assertContentEquals(expected, buf)
    }

    @Test
    fun mapCipherRealFile() {
        for (name in listOf("mflac_map", "mgg_map")) {
            val key = load("${name}_key.bin")
            val cipherText = load("${name}_raw.bin")
            val clearText = load("${name}_target.bin")
            QmcCipher.MapCipher(key).decrypt(cipherText, 0)
            assertContentEquals(clearText, cipherText, "map cipher mismatch: $name")
        }
    }

    @Test
    fun rc4CipherRealFile() {
        for (name in listOf("mflac0_rc4", "mflac_rc4")) {
            val key = load("${name}_key.bin")
            val cipherText = load("${name}_raw.bin")
            val clearText = load("${name}_target.bin")
            QmcCipher.RC4Cipher(key).decrypt(cipherText, 0)
            assertContentEquals(clearText, cipherText, "rc4 cipher mismatch: $name")
        }
    }

    @Test
    fun rc4CipherFirstSegment() {
        for (name in listOf("mflac0_rc4", "mflac_rc4")) {
            val key = load("${name}_key.bin")
            val raw = load("${name}_raw.bin")
            val target = load("${name}_target.bin")
            val buf = raw.copyOfRange(0, 128)
            QmcCipher.RC4Cipher(key).decrypt(buf, 0)
            assertContentEquals(target.copyOfRange(0, 128), buf, "first segment mismatch: $name")
        }
    }

    @Test
    fun rc4CipherAlignBlock() {
        for (name in listOf("mflac0_rc4", "mflac_rc4")) {
            val key = load("${name}_key.bin")
            val raw = load("${name}_raw.bin")
            val target = load("${name}_target.bin")
            val buf = raw.copyOfRange(128, 5120)
            QmcCipher.RC4Cipher(key).decrypt(buf, 128)
            assertContentEquals(target.copyOfRange(128, 5120), buf, "align block mismatch: $name")
        }
    }

    @Test
    fun rc4CipherSimpleBlock() {
        for (name in listOf("mflac0_rc4", "mflac_rc4")) {
            val key = load("${name}_key.bin")
            val raw = load("${name}_raw.bin")
            val target = load("${name}_target.bin")
            val buf = raw.copyOfRange(5120, 10240)
            QmcCipher.RC4Cipher(key).decrypt(buf, 5120)
            assertContentEquals(target.copyOfRange(5120, 10240), buf, "simple block mismatch: $name")
        }
    }

    @Test
    fun qmcDecoderRealFiles() {
        for (name in listOf("mflac0_rc4", "mflac_rc4", "mflac_map", "mgg_map", "qmc0_static")) {
            val raw = load("${name}_raw.bin")
            val suffix = load("${name}_suffix.bin")
            val full = raw + suffix
            val target = load("${name}_target.bin")

            val audio = QmcDecoder(full).decrypt()
            assertContentEquals(target, audio, "qmc decoder mismatch: $name")
        }
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }
}
