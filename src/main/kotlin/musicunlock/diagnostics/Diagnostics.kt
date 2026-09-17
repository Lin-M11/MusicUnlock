package musicunlock.diagnostics

import musicunlock.BuildInfo
import musicunlock.service.AudioTranscoder
import musicunlock.settings.SettingsStore
import java.io.File
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

/** 内存日志、敏感信息脱敏和导出诊断快照。 */
object Diagnostics {
    private const val MAX_LINES = 800
    private val logs = ArrayDeque<String>()
    private val sequence = AtomicInteger()

    fun log(message: String) {
        val line = "${Instant.now()}  ${redact(message)}"
        synchronized(logs) {
            logs.addFirst(line)
            while (logs.size > MAX_LINES) logs.removeLast()
        }
        sequence.incrementAndGet()
    }

    fun recentLines(limit: Int = 300): List<String> = synchronized(logs) {
        logs.take(limit)
    }

    fun snapshot(): String {
        val settings = SettingsStore.load()
        return buildString {
            appendLine("MusicUnlock 诊断信息")
            appendLine("版本: ${BuildInfo.VERSION}")
            appendLine("系统: ${System.getProperty("os.name")} ${System.getProperty("os.version")} ${System.getProperty("os.arch")}")
            appendLine("Java: ${System.getProperty("java.version")} / ${System.getProperty("java.vendor")}")
            appendLine("FFmpeg: ${AudioTranscoder.locateFfmpeg() ?: "不可用"}")
            appendLine("输出目录: ${settings.outputDir}")
            appendLine("并发: ${settings.downloadConcurrency}")
            appendLine("代理: ${settings.proxyUrl ?: if (settings.useSystemProxy) "系统代理" else "未启用"}")
            appendLine("登录状态: 网易云=${settings.neteaseCookie != null} QQ=${settings.qqCookie != null} 酷狗=${settings.kugouCookie != null} 酷我=${settings.kuwoCookie != null}")
            appendLine()
            appendLine("最近日志:")
            if (logs.isEmpty()) appendLine("(无)") else logs.forEach { appendLine(it) }
        }
    }

    fun export(target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        target.writeText(snapshot())
        true
    }.getOrDefault(false)

    fun redact(text: String): String {
        var value = text
        value = Regex("""(?i)(Cookie|Authorization)\s*:\s*[^;\r\n]+""").replace(value) { "${it.groupValues[1]}: ***" }
        value = Regex("""(?i)\b(MUSIC_U|__csrf|p_skey|skey|qrsig|sid|dfid|[a-z0-9_]*(?:token|key|authst|musickey))\s*=\s*[^;\s&]+""")
            .replace(value) { "${it.groupValues[1]}=***" }
        value = Regex("""(?i)([?&](?:token|key|auth|signature|authst|musickey)=)[^&\s]+""")
            .replace(value) { "${it.groupValues[1]}***" }
        return value
    }
}