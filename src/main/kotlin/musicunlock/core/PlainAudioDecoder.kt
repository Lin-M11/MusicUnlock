package musicunlock.core

/**
 * 普通(未加密)音频的直通解码器:不做解密,只回传原始字节与探测到的真实格式。
 *
 * 让 WAV / FLAC / APE / MP3 等常见原始格式与加密格式共用同一条转换管线,
 * 因此原始格式同样可以按输出设置转码、套用命名模板并参与内容去重。
 */
object PlainAudioDecoder : MusicDecoder {

    /** 探测容器信息所需的头部字节数,避免增量转换时读取整个文件。 */
    private const val PROBE_BYTES = 64

    override fun decode(data: ByteArray, fileName: String): MusicResult {
        if (data.isEmpty()) throw IllegalArgumentException("音频文件为空")
        return MusicResult(data, AudioSniffer.sniff(data, Formats.extOf(fileName)))
    }

    override fun outputExtension(data: ByteArray, fileName: String): String =
        AudioSniffer.sniff(data.take(PROBE_BYTES).toByteArray(), Formats.extOf(fileName))
}
