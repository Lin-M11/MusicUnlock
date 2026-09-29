package musicunlock.core

import java.util.Locale

/**
 * 支持的输入音频格式注册表。
 *
 * 加密格式由各自的解密器处理,常见无损/有损原始格式由 [PlainAudioDecoder] 直通,
 * 两类格式共用同一条转换管线。
 */
object Formats {

    /** 常见无损与未压缩原始格式。 */
    private val losslessExtensions = listOf(
        "wav", "wave", "flac", "ape", "alac", "aif", "aiff", "au", "wv", "dff",
    )

    /** 常见有损原始格式。 */
    private val lossyExtensions = listOf(
        "mp3", "m4a", "aac", "ogg", "oga", "opus", "wma",
    )

    /** 需要解密的加密格式。 */
    private val encryptedDecoders: Map<String, MusicDecoder> = buildMap {
        // 网易云
        put("ncm", NcmDecoder())

        // QQ音乐 QMC 系列
        val qmcExts = listOf(
            "qmc0", "qmc2", "qmc3", "qmc4", "qmc6", "qmc8",
            "qmcflac", "qmcogg", "tkm",
            "mflac", "mflac0", "mflac1", "mflac2",
            "mgg", "mgg0", "mgg1", "mgg2", "mggl", "mmp4",
            "bkcmp3", "bkcm4a", "bkcflac", "bkcwav", "bkcape", "bkcogg", "bkcwma",
        )
        for (ext in qmcExts) put(ext, QmcDecoder())

        // 酷狗
        for (ext in listOf("kgm", "kgma", "vpr")) put(ext, KgmDecoder)

        // 酷我
        put("kwm", KwmDecoder)
    }

    /** 无需解密的原始音频格式,统一走直通解码器。 */
    private val plainDecoders: Map<String, MusicDecoder> =
        (losslessExtensions + lossyExtensions).associateWith { PlainAudioDecoder }

    private val decoders: Map<String, MusicDecoder> = encryptedDecoders + plainDecoders

    /** 是否支持该文件名(按扩展名判断,不区分大小写)。 */
    fun isSupported(fileName: String): Boolean = get(fileName) != null

    /**
     * 判断扩展名(不含点,大小写不敏感)是否需要解密。
     * 收件箱监听与自动化规则据此只处理加密音乐,不会动普通音频。
     */
    fun isEncryptedExtension(ext: String): Boolean =
        encryptedDecoders.containsKey(ext.lowercase(Locale.ROOT))

    /** 判断扩展名(不含点,大小写不敏感)是否为无需解密的原始音频。 */
    fun isPlainExtension(ext: String): Boolean =
        plainDecoders.containsKey(ext.lowercase(Locale.ROOT))

    /** 获取文件名对应的解码器;不支持时返回 null。 */
    fun get(fileName: String): MusicDecoder? {
        val ext = extOf(fileName) ?: return null
        return decoders[ext]
    }

    /** 取小写扩展名(不含点)。 */
    fun extOf(fileName: String): String? {
        val idx = fileName.lastIndexOf('.')
        if (idx < 0 || idx == fileName.length - 1) return null
        return fileName.substring(idx + 1).lowercase(Locale.ROOT)
    }

    /** 所有支持的输入扩展名(用于文件过滤器 / 帮助信息)。 */
    fun supportedExtensions(): List<String> = decoders.keys.sorted()

    /** 需要解密的加密格式扩展名。 */
    fun encryptedExtensions(): List<String> = encryptedDecoders.keys.sorted()

    /** 无需解密的原始音频扩展名。 */
    fun plainExtensions(): List<String> = plainDecoders.keys.sorted()
}
