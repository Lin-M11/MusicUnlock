package musicunlock

import java.io.ByteArrayOutputStream

/**
 * 测试用的裸音频样本生成器:构造无需外部素材的 16-bit 单声道 PCM WAV,
 * 供原始格式输入链路(直通解码 -> 转码)的用例使用。
 */
object TestAudio {

    /** 生成一段 440Hz 正弦波 WAV,默认 0.3 秒 / 44.1kHz。 */
    fun pcmWav(seconds: Double = 0.3, sampleRate: Int = 44_100, frequency: Double = 440.0): ByteArray {
        val frames = (sampleRate * seconds).toInt()
        val dataSize = frames * 2
        val out = ByteArrayOutputStream(44 + dataSize)

        fun ascii(text: String) = out.write(text.toByteArray(Charsets.US_ASCII))
        fun le32(value: Int) = out.write(
            byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte(),
            ),
        )
        fun le16(value: Int) = out.write(
            byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()),
        )

        ascii("RIFF"); le32(36 + dataSize); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        ascii("data"); le32(dataSize)

        for (frame in 0 until frames) {
            val sample = (Math.sin(2.0 * Math.PI * frequency * frame / sampleRate) * 0.3 * Short.MAX_VALUE).toInt()
            le16(sample and 0xFFFF)
        }
        return out.toByteArray()
    }
}
