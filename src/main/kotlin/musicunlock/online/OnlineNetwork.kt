package musicunlock.online

import musicunlock.settings.AppSettings
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/** 四平台 HTTP 客户端共用的超时与代理配置。 */
object OnlineNetwork {
    @Volatile
    private var fingerprint: String = ""

    @Volatile
    private var cached: HttpClient = build(AppSettings())

    @Volatile
    private var currentSettings: AppSettings = AppSettings()

    fun configure(settings: AppSettings) {
        currentSettings = settings
        configureProxyProperties(settings)
        client(settings)
    }

    fun settings(): AppSettings = currentSettings

    fun client(settings: AppSettings): HttpClient {
        val nextFingerprint = listOf(
            settings.connectTimeoutSeconds,
            settings.downloadTimeoutSeconds,
            settings.useSystemProxy,
            settings.proxyUrl.orEmpty(),
        ).joinToString("|")
        if (nextFingerprint != fingerprint) {
            synchronized(this) {
                if (nextFingerprint != fingerprint) {
                    cached = build(settings)
                    fingerprint = nextFingerprint
                }
            }
        }
        return cached
    }

    fun requestTimeout(settings: AppSettings): Duration =
        Duration.ofSeconds(settings.downloadTimeoutSeconds.coerceAtLeast(5))

    private fun configureProxyProperties(settings: AppSettings) {
        val uri = settings.proxyUrl?.takeIf(String::isNotBlank)?.let { runCatching { URI.create(it) }.getOrNull() }
        val host = uri?.host
        val port = uri?.port?.takeIf { it > 0 } ?: 8080
        val keys = listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort")
        if (!host.isNullOrBlank()) {
            System.setProperty("http.proxyHost", host)
            System.setProperty("http.proxyPort", port.toString())
            System.setProperty("https.proxyHost", host)
            System.setProperty("https.proxyPort", port.toString())
        } else if (!settings.useSystemProxy) {
            keys.forEach(System::clearProperty)
        }
    }

    private fun build(settings: AppSettings): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds.coerceAtLeast(3)))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .proxy(proxySelector(settings))
        return builder.build()
    }

    private fun proxySelector(settings: AppSettings): ProxySelector {
        val configured = settings.proxyUrl?.takeIf(String::isNotBlank)
        if (configured != null) {
            val uri = runCatching { URI.create(configured) }.getOrNull()
            val host = uri?.host
            val port = uri?.port?.takeIf { it > 0 } ?: 8080
            if (!host.isNullOrBlank()) {
                return ProxySelector.of(InetSocketAddress(host, port))
            }
        }
        return if (settings.useSystemProxy) ProxySelector.getDefault() else ProxySelector.of(null)
    }
}
