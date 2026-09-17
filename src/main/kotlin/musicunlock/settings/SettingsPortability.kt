package musicunlock.settings

import com.google.gson.GsonBuilder
import java.io.File
import java.net.URI

/** 导出/导入不含 Cookie 的可分享配置。 */
object SettingsPortability {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun export(target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        target.writeText(toJson())
        true
    }.getOrDefault(false)

    fun import(source: File): AppSettings = runCatching {
        importJson(source.readText())
    }.getOrElse { throw IllegalArgumentException("配置导入失败：${it.message}") }

    fun toJson(settings: AppSettings = SettingsStore.load()): String =
        gson.toJson(settings.withoutSecrets())

    fun importJson(json: String): AppSettings = runCatching {
        val imported = gson.fromJson(json, AppSettings::class.java)
            ?: throw IllegalArgumentException("配置文件为空")
        SettingsStore.update { current ->
            imported.copy(
                neteaseCookie = current.neteaseCookie,
                qqCookie = current.qqCookie,
                kugouCookie = current.kugouCookie,
                kuwoCookie = current.kuwoCookie,
                neteaseAccount = current.neteaseAccount,
                qqAccount = current.qqAccount,
                kugouAccount = current.kugouAccount,
                kuwoAccount = current.kuwoAccount,
            )
        }
    }.getOrElse { throw IllegalArgumentException("配置导入失败：${it.message}") }

    fun AppSettings.withoutSecrets(): AppSettings = copy(
        neteaseCookie = null,
        qqCookie = null,
        kugouCookie = null,
        kuwoCookie = null,
        neteaseAccount = null,
        qqAccount = null,
        kugouAccount = null,
        kuwoAccount = null,
        proxyUrl = proxyUrl?.let(::withoutUserInfo),
    )

    private fun withoutUserInfo(value: String): String = runCatching {
        val uri = URI.create(value)
        if (uri.userInfo == null) return@runCatching value
        URI(
            uri.scheme,
            null,
            uri.host,
            uri.port,
            uri.path,
            uri.query,
            uri.fragment,
        ).toString()
    }.getOrDefault(value)
}
