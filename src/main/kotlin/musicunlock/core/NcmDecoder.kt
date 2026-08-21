package musicunlock.core

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 .ncm 容器解码器。
 *
 * .ncm 是私有容器格式,内部封装普通音频流(mp3/flac/ogg/m4a/...)、元数据与封面。
 * 字节布局与两个固定的 AES-128 密钥均为公开格式常量,被多个开源项目
 * (unlock-music、ncmdump 等)文档化。
 *
 * 容器布局(所有长度均为小端无符号 32 位):
 * <pre>
 *   magic   "CTENFDAM" + 2 字节 gap      (10 字节)
 *   keyLen  key 块长度
 *   key     异或 0x64,AES-128-ECB 用 CORE_KEY 解密,
 *           去掉前缀 "neteasecloudmusic"   -> RC4 密钥
 *   metaLen 元数据块长度
 *   meta    异或 0x63,去掉 "163 key(Don't modify):",
 *           base64 解码,AES-128-ECB 用 META_KEY 解密,
 *           去掉前缀 "music:"             -> JSON 元数据
 *   crc     (4 字节,跳过)
 *   gap     (5 字节,跳过)
 *   imgLen  封面长度
 *   img     封面数据
 *   audio   音乐数据,用 RC4 流解密(见 NcmCipher)
 * </pre>
 *
 * 本实现基于上述公开格式规范编写,算法与 MIT 许可的 unlock-music 一致,
 * 不包含任何无许可证项目的代码。
 */
class NcmDecoder : MusicDecoder {

    private val gson = Gson()

    override fun decode(data: ByteArray, fileName: String): MusicResult {
        NcmReader(data).use { reader ->
            val header = reader.readBytes(10)
            if (!header.copyOf(8).contentEquals(MAGIC)) {
                throw IOException("Not an NCM file: bad magic header")
            }

            val rc4Key = readKey(reader)
            val meta = readMetadata(reader)
            val cover = readCover(reader)
            val audio = readAudio(reader, rc4Key)

            return MusicResult(
                data = audio,
                ext = normalizeExt(meta.format),
                musicName = meta.musicName,
                artist = meta.firstArtist(),
                album = meta.album,
                cover = cover.takeIf { it.isNotEmpty() },
            )
        }
    }

    private fun readKey(reader: NcmReader): ByteArray {
        val length = reader.readIntLE()
        val block = reader.readBytes(length)
        for (i in block.indices) block[i] = (block[i].toInt() xor 0x64).toByte()
        val unwrapped = aesEcbDecrypt(block, CORE_KEY)
        if (unwrapped.size < KEY_PREFIX.size) throw IOException("NCM key block too short")
        return unwrapped.copyOfRange(KEY_PREFIX.size, unwrapped.size)
    }

    private fun readMetadata(reader: NcmReader): NcmMetadata {
        val length = reader.readIntLE()
        val meta = NcmMetadata()
        if (length == 0) {
            reader.skip(9) // 无元数据块,仍需跳过 CRC + gap
            return meta
        }
        val block = reader.readBytes(length)
        reader.skip(9) // 元数据块之后固定有 4 字节 CRC 与 5 字节 gap

        for (i in block.indices) block[i] = (block[i].toInt() xor 0x63).toByte()
        if (block.size < META_PREFIX.size) throw IOException("NCM metadata block too short")
        val encoded = block.copyOfRange(META_PREFIX.size, block.size)

        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw IOException("NCM metadata is not valid base64", e)
        }
        val unwrapped = aesEcbDecrypt(decoded, META_KEY)
        if (unwrapped.size < MUSIC_PREFIX.size) throw IOException("NCM metadata payload too short")
        val json = String(unwrapped, MUSIC_PREFIX.size, unwrapped.size - MUSIC_PREFIX.size, StandardCharsets.UTF_8)
        return try {
            gson.fromJson(json, NcmMetadata::class.java) ?: meta
        } catch (e: JsonSyntaxException) {
            throw IOException("NCM metadata is not valid JSON", e)
        }
    }

    private fun readCover(reader: NcmReader): ByteArray {
        val length = reader.readIntLE()
        return if (length > 0) reader.readBytes(length) else ByteArray(0)
    }

    private fun readAudio(reader: NcmReader, rc4Key: ByteArray): ByteArray {
        val cipher = NcmCipher(rc4Key)
        val audio = ByteArrayOutputStream()
        val buffer = ByteArray(0x8000)
        while (true) {
            val read = reader.read(buffer)
            if (read <= 0) break
            cipher.decrypt(buffer, 0, read)
            audio.write(buffer, 0, read)
        }
        return audio.toByteArray()
    }

    private fun aesEcbDecrypt(ciphertext: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(ciphertext)
    }

    private fun normalizeExt(format: String?): String {
        if (format.isNullOrEmpty()) return "mp3"
        val ext = if (format.startsWith(".")) format.substring(1) else format
        return ext.lowercase()
    }

    /** 小端读取器(作用于内存中的容器数据)。 */
    private class NcmReader(private val data: ByteArray) : AutoCloseable {
        private val input = ByteArrayInputStream(data)

        fun readIntLE(): Int {
            val b0 = input.read()
            val b1 = input.read()
            val b2 = input.read()
            val b3 = input.read()
            if (b0 or b1 or b2 or b3 < 0) throw EOFException("NCM file truncated")
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        fun readBytes(length: Int): ByteArray {
            val bytes = ByteArray(length)
            var off = 0
            while (off < length) {
                val read = input.read(bytes, off, length - off)
                if (read < 0) throw EOFException("NCM file truncated")
                off += read
            }
            return bytes
        }

        fun read(buffer: ByteArray): Int = input.read(buffer)

        fun skip(n: Int) {
            val skipped = input.skip(n.toLong())
            if (skipped < n) throw EOFException("NCM file truncated")
        }

        override fun close() {
            input.close()
        }
    }

    companion object {
        private val MAGIC = "CTENFDAM".toByteArray(StandardCharsets.US_ASCII)

        /** 解开 RC4 流密钥的 AES-128 密钥(格式常量)。 */
        private val CORE_KEY = byteArrayOf(
            0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F,
            0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57,
        )

        /** 解开元数据 JSON 的 AES-128 密钥(格式常量)。 */
        private val META_KEY = byteArrayOf(
            0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21,
            0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28,
        )

        private val KEY_PREFIX = "neteasecloudmusic".toByteArray(StandardCharsets.US_ASCII)
        private val META_PREFIX = "163 key(Don't modify):".toByteArray(StandardCharsets.US_ASCII)
        private val MUSIC_PREFIX = "music:".toByteArray(StandardCharsets.US_ASCII)
    }
}
