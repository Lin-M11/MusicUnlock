package musicunlock.core

import java.util.Locale

/**
 * 支持的加密音乐格式注册表。
 */
object Formats {

    private val decoders: Map<String, MusicDecoder> = buildMap {
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

    /** 是否支持该文件名(按扩展名判断,不区分大小写)。 */
    fun isSupported(fileName: String): Boolean = get(fileName) != null

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

    /** 所有支持的扩展名(用于文件过滤器 / 帮助信息)。 */
    fun supportedExtensions(): List<String> = decoders.keys.sorted()
}
