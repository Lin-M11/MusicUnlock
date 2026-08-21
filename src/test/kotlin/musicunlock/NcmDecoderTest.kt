package musicunlock

import musicunlock.core.NcmCipher
import musicunlock.core.NcmDecoder
import musicunlock.core.MusicResult
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * NCM 解码器往返测试:按公开 .ncm 容器规范构造加密文件,
 * 用 NcmDecoder 解密,应还原原始音频、元数据与封面。
 */
class NcmDecoderTest {

    @Test
    fun roundTripFlacWithMetadata() {
        val audio = randomAudio(300_000, byteArrayOf(0x66, 0x4C, 0x61, 0x43)) // "fLaC"
        val cover = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val json = """{"musicName":"Test Song","artist":[["Test Artist"]],"album":"Test Album","format":"flac"}"""

        val result: MusicResult = NcmDecoder().decode(buildNcm(audio, json, cover), "test.ncm")

        assertEquals("flac", result.ext)
        assertEquals("Test Song", result.musicName)
        assertEquals("Test Artist", result.artist)
        assertEquals("Test Album", result.album)
        assertContentEquals(audio, result.data)
        assertContentEquals(cover, result.cover!!)
    }

    @Test
    fun roundTripMp3WithoutCover() {
        val audio = randomAudio(100_000, byteArrayOf(0x49, 0x44, 0x33)) // "ID3"
        val json = """{"musicName":"No Cover","artist":[["A"]],"album":"B","format":"mp3"}"""

        val result = NcmDecoder().decode(buildNcm(audio, json, ByteArray(0)), "test.ncm")

        assertEquals("mp3", result.ext)
        assertEquals("No Cover", result.musicName)
        assertContentEquals(audio, result.data)
        assertNull(result.cover)
    }

    @Test
    fun emptyMetadataDefaultsToMp3() {
        val audio = randomAudio(64_000, byteArrayOf(0x49, 0x44, 0x33))
        val result = NcmDecoder().decode(withEmptyMetadata(audio), "test.ncm")
        assertEquals("mp3", result.ext)
        assertNull(result.musicName)
        assertContentEquals(audio, result.data)
    }

    @Test
    fun firstArtistHandlesMultipleArtists() {
        val audio = randomAudio(10_000, byteArrayOf(0x49, 0x44, 0x33))
        val json = """{"musicName":"Duet","artist":[["First","Second"]],"format":"mp3"}"""
        val result = NcmDecoder().decode(buildNcm(audio, json, ByteArray(0)), "t.ncm")
        assertEquals("First", result.artist)
    }

    @Test
    fun metadataMayOmitArtistAndAlbum() {
        val audio = randomAudio(10_000, byteArrayOf(0x49, 0x44, 0x33))
        val json = """{"musicName":"Only Name","format":"mp3"}"""
        val result = NcmDecoder().decode(buildNcm(audio, json, ByteArray(0)), "t.ncm")
        assertEquals("Only Name", result.musicName)
        assertNull(result.artist)
        assertNull(result.album)
    }

    @Test(expected = Exception::class)
    fun badMagicRejected() {
        val junk = ByteArray(128)
        Random(1).nextBytes(junk)
        NcmDecoder().decode(junk, "bad.ncm")
    }

    // ---------- 构造端(按公开规范) ----------

    private fun buildNcm(audio: ByteArray, metaJson: String, cover: ByteArray): ByteArray {
        val rc4Key = ByteArray(16)
        Random(7).nextBytes(rc4Key)

        val out = ByteArrayOutputStream()
        out.write("CTENFDAM".toByteArray(StandardCharsets.US_ASCII))
        out.write(byteArrayOf(0, 0))

        val keyBlock = aesEcbEncrypt(KEY_PREFIX + rc4Key, CORE_KEY)
        for (i in keyBlock.indices) keyBlock[i] = (keyBlock[i].toInt() xor 0x64).toByte()
        writeIntLE(out, keyBlock.size)
        out.write(keyBlock)

        val metaEncrypted = aesEcbEncrypt(
            (MUSIC_PREFIX + metaJson.toByteArray(StandardCharsets.UTF_8)),
            META_KEY,
        )
        val metaBlock = META_PREFIX + Base64.getEncoder().encode(metaEncrypted)
        for (i in metaBlock.indices) metaBlock[i] = (metaBlock[i].toInt() xor 0x63).toByte()
        writeIntLE(out, metaBlock.size)
        out.write(metaBlock)

        out.write(ByteArray(4)) // CRC
        out.write(ByteArray(5)) // gap

        writeIntLE(out, cover.size)
        out.write(cover)

        val encryptedAudio = audio.copyOf()
        NcmCipher(rc4Key).decrypt(encryptedAudio, 0, encryptedAudio.size)
        out.write(encryptedAudio)
        return out.toByteArray()
    }

    /** metaLen=0 的 NCM:magic + key + metaLen(0) + crc + gap + imgLen(0) + audio */
    private fun withEmptyMetadata(audio: ByteArray): ByteArray {
        val rc4Key = ByteArray(16)
        Random(7).nextBytes(rc4Key)

        val out = ByteArrayOutputStream()
        out.write("CTENFDAM".toByteArray(StandardCharsets.US_ASCII))
        out.write(byteArrayOf(0, 0))

        val keyBlock = aesEcbEncrypt(KEY_PREFIX + rc4Key, CORE_KEY)
        for (i in keyBlock.indices) keyBlock[i] = (keyBlock[i].toInt() xor 0x64).toByte()
        writeIntLE(out, keyBlock.size)
        out.write(keyBlock)

        writeIntLE(out, 0)
        out.write(ByteArray(4)) // CRC
        out.write(ByteArray(5)) // gap
        writeIntLE(out, 0)

        val encryptedAudio = audio.copyOf()
        NcmCipher(rc4Key).decrypt(encryptedAudio, 0, encryptedAudio.size)
        out.write(encryptedAudio)
        return out.toByteArray()
    }

    private fun randomAudio(length: Int, header: ByteArray): ByteArray {
        val audio = ByteArray(length)
        Random(42).nextBytes(audio)
        System.arraycopy(header, 0, audio, 0, header.size)
        return audio
    }

    private fun aesEcbEncrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(plaintext)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private operator fun ByteArray.plus(other: ByteArray): ByteArray {
        val out = ByteArray(size + other.size)
        System.arraycopy(this, 0, out, 0, size)
        System.arraycopy(other, 0, out, size, other.size)
        return out
    }

    companion object {
        private val CORE_KEY = byteArrayOf(
            0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
            0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57,
        )
        private val META_KEY = byteArrayOf(
            0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
            0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28,
        )
        private val KEY_PREFIX = "neteasecloudmusic".toByteArray(StandardCharsets.US_ASCII)
        private val META_PREFIX = "163 key(Don't modify):".toByteArray(StandardCharsets.US_ASCII)
        private val MUSIC_PREFIX = "music:".toByteArray(StandardCharsets.US_ASCII)
    }
    /**
     * 回归测试:像 NcmDecoder.readAudio 那样用独立缓冲区 + offset=0 分块解密,
     * 结果必须与单次解密一致。流密码位置必须在整个音频上连续推进,不能每次
     * 调用都从 0 重新开始,否则后续块会复用同一段密钥流。
     */
    @Test
    fun cipherChunkedDecryptionWithFreshBuffersMatchesSinglePass() {
        val key = ByteArray(16)
        Random(7).nextBytes(key)
        val data = ByteArray(100_000)
        Random(42).nextBytes(data)

        val encrypted = data.copyOf()
        NcmCipher(key).decrypt(encrypted, 0, encrypted.size)

        val decrypted = ByteArray(data.size)
        val cipher = NcmCipher(key)
        val buffer = ByteArray(100) // 故意不用 256 的整数倍
        var done = 0
        while (done < data.size) {
            val n = minOf(buffer.size, data.size - done)
            System.arraycopy(encrypted, done, buffer, 0, n)
            cipher.decrypt(buffer, 0, n)
            System.arraycopy(buffer, 0, decrypted, done, n)
            done += n
        }

        assertContentEquals(data, decrypted)
    }

}