package musicunlock.online

import musicunlock.settings.AppSettings

data class ProviderHealthSnapshot(
    val platform: MusicPlatform,
    val loggedIn: Boolean,
    val healthy: Boolean,
    val latencyMillis: Long,
    val message: String?,
    val capabilities: ProviderCapabilities,
)

/** 平台账号与接口健康检查，供设置页、守护进程和诊断使用。 */
object ProviderHealthService {
    fun check(settings: AppSettings): List<ProviderHealthSnapshot> = ProviderRegistry.all.map { provider ->
        val cookie = when (provider.platform) {
            MusicPlatform.NETEASE -> settings.neteaseCookie
            MusicPlatform.QQ -> settings.qqCookie
            MusicPlatform.KUGOU -> settings.kugouCookie
            MusicPlatform.KUWO -> settings.kuwoCookie
        }
        var account: MusicAccount? = null
        val started = System.nanoTime()
        val result = runCatching {
            if (!cookie.isNullOrBlank()) {
                account = provider.restoreSession(cookie)
            }
            provider.healthCheck().getOrThrow()
        }
        ProviderHealthSnapshot(
            platform = provider.platform,
            loggedIn = account != null,
            healthy = result.isSuccess,
            latencyMillis = (System.nanoTime() - started) / 1_000_000L,
            message = result.exceptionOrNull()?.message,
            capabilities = provider.capabilities(),
        )
    }
}
