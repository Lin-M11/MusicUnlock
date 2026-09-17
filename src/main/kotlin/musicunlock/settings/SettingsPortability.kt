package musicunlock.settings

import com.google.gson.GsonBuilder
import java.io.File

/** 导出/导入不含 Cookie 的可分享配置。 */
object SettingsPortability {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun export(target: File): Boolean = runCatching {
        target.parentFile?.mkdirs()
        target.writeText(gson.toJson(SettingsStore.load().withoutSecrets()))
        true
    }.getOrDefault(false)

    fun import(source: File): AppSettings = runCatching {
        val imported = gson.fromJson(source.readText(), AppSettings::class.java)
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
    )
}
