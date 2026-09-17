package musicunlock.online

import musicunlock.kugou.KugouApi
import musicunlock.kuwo.KuwoApi
import musicunlock.ncm.NeteaseProvider
import musicunlock.qq.QqMusicProvider
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/** 外部平台包通过 ServiceLoader 提供工厂即可接入统一队列和界面。 */
interface OnlineMusicProviderFactory {
    fun create(): OnlineMusicProvider
}

/** 平台 id 到统一 Provider 的集中映射，供任务恢复、CLI 和跨平台功能复用。 */
object ProviderRegistry {
    private val builtIns = listOf(
        NeteaseProvider,
        QqMusicProvider,
        KugouApi,
        KuwoApi,
    )

    val all: List<OnlineMusicProvider> = loadExternal().let { external ->
        (builtIns + external.mapNotNull { runCatching { it.create() }.getOrNull() })
            .associateBy { it.platform.id }
            .values
            .toList()
    }

    private fun loadExternal(): List<OnlineMusicProviderFactory> = runCatching {
        val pluginDir = File(System.getProperty("user.home"), ".musicunlock/plugins")
        if (!pluginDir.isDirectory) return emptyList()
        val jars = pluginDir.listFiles { file -> file.isFile && file.extension.equals("jar", ignoreCase = true) }
            ?.map { it.toURI().toURL() }
            ?.toTypedArray()
            ?: return emptyList()
        val loader = URLClassLoader(jars, ProviderRegistry::class.java.classLoader)
        ServiceLoader.load(OnlineMusicProviderFactory::class.java, loader).toList()
    }.getOrDefault(emptyList())

    fun find(platformId: String): OnlineMusicProvider? =
        all.firstOrNull { it.platform.id.equals(platformId, ignoreCase = true) }

    fun require(platformId: String): OnlineMusicProvider =
        find(platformId) ?: throw IllegalArgumentException("未知音乐平台：$platformId")
}
