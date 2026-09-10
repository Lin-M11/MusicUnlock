package musicunlock.settings

import java.io.File

/** 转码输出格式；原始格式只解密，不重新编码。 */
enum class OutputFormat {
    ORIGINAL,
    MP3,
}

val outputBitrates: List<Int> = listOf(128, 192, 320)

/** 接收一次纯设置变更，由调用方在最新配置上应用。 */
typealias SettingsUpdate = ((AppSettings) -> AppSettings) -> Unit

/** 应用级设置。字段带默认值，便于旧配置缺项时平滑读取。 */
data class AppSettings(
    val outputDir: String = defaultOutputDir(),
    val dedup: Boolean = false,
    val outputFormat: OutputFormat = OutputFormat.ORIGINAL,
    val bitrateKbps: Int = 320,
    val windowWidth: Int = 1120,
    val windowHeight: Int = 760,
    val neteaseCookie: String? = null,
    val qqCookie: String? = null,
)

/** 默认输出目录：优先使用用户主目录下的 Music/MusicUnlock。 */
fun defaultOutputDir(): String {
    val home = System.getProperty("user.home")
    return if (!home.isNullOrBlank()) {
        File(home, "Music/MusicUnlock").absolutePath
    } else {
        File("output").absolutePath
    }
}
