package musicunlock.core

/**
 * 网易云 .ncm 容器使用的流密码。
 *
 * 密钥调度为标准 RC4(KSA);输出阶段为 ncm 格式特有的变体:字节 k 与
 * box[(box[i] + box[j]) & 0xff] 异或,其中 i = (k + 1) & 0xff、
 * j = (box[i] + i) & 0xff;与教科书 RC4 不同,输出时不交换 S-box。
 *
 * 参考:MIT 许可的 unlock-music 项目(src/decrypt/ncm.ts)。
 */
class NcmCipher(key: ByteArray) {

    private val box = IntArray(256)

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
            val i = (k + 1) and 0xFF
            val j = (box[i] + i) and 0xFF
            data[k] = (data[k].toInt() xor box[(box[i] + box[j]) and 0xFF]).toByte()
        }
    }
}
