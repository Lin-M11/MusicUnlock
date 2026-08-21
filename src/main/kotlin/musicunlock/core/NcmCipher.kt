package musicunlock.core

/**
 * 网易云 .ncm 容器使用的流密码。
 *
 * 密钥调度为标准 RC4(KSA);输出阶段为 ncm 格式特有的变体:字节 k 与
 * box[(box[i] + box[j]) & 0xff] 异或,其中 i = (cur + 1) & 0xff、
 * j = (box[i] + i) & 0xff;与教科书 RC4 不同,输出时不交换 S-box。
 *
 * 注意:流位置(position)必须在整个音频流上连续推进,不能在每次
 * decrypt 调用时重置,否则分块解密会反复使用同一段密钥流。
 *
 * 参考:MIT 许可的 unlock-music 项目(src/decrypt/ncm.ts)。
 */
class NcmCipher(key: ByteArray) {

    private val box = IntArray(256)

    /** 当前流位置(0..255),跨 decrypt 调用持续递增。 */
    private var position = 0

    init {
        require(key.isNotEmpty()) { "NCM RC4 key must not be empty" }
        for (i in 0 until 256) box[i] = i
        var j = 0
        for (i in 0 until 256) {
            j = (j + box[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
            val swap = box[i]
            box[i] = box[j]
            box[j] = swap
        }
    }

    /** 原地异或解密 data[offset, offset+length);流密码对称,加密/解密相同。 */
    fun decrypt(data: ByteArray, offset: Int, length: Int) {
        val end = offset + length
        for (k in offset until end) {
            position = (position + 1) and 0xFF
            val i = position
            val j = (box[i] + i) and 0xFF
            data[k] = (data[k].toInt() xor box[(box[i] + box[j]) and 0xFF]).toByte()
        }
    }
}
