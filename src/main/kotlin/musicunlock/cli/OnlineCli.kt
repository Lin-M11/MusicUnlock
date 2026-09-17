package musicunlock.cli

import com.google.gson.Gson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import musicunlock.library.LibraryIndex
import musicunlock.online.DownloadTaskManager
import musicunlock.online.DownloadTaskState
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicSong
import musicunlock.online.OnlineNetwork
import musicunlock.online.ProviderRegistry
import musicunlock.online.SearchResultKind
import musicunlock.online.toOnlineDownloadPreferences
import musicunlock.playlist.MusicLinkKind
import musicunlock.playlist.MusicLinkResolver
import musicunlock.settings.AppSettings
import musicunlock.settings.QualityStrategy
import musicunlock.settings.SettingsStore
import java.io.File
import java.nio.file.Files

data class OnlineCliOptions(
    val url: String? = null,
    val playlistId: String? = null,
    val search: String? = null,
    val platform: String? = null,
    val outputDir: String = "output",
    val quality: QualityStrategy? = null,
    val outputTemplate: String? = null,
    val json: Boolean = false,
    val downloadSearchResults: Boolean = false,
    val favorites: Boolean = false,
    val limit: Int = 20,
    val retries: Int? = null,
    val rateLimitKbps: Int? = null,
    val proxy: String? = null,
    val timeoutSeconds: Long? = null,
    val cookie: String? = null,
    val forceMp3: Boolean = false,
)

/** 无界面的在线下载 / 搜索入口。 */
object OnlineCliRunner {
    private val gson = Gson()

    fun run(options: OnlineCliOptions): Int {
        val output = File(options.outputDir).absoluteFile.apply { mkdirs() }
        if (!output.isDirectory) {
            print(options, "无法创建输出目录：${output.absolutePath}")
            return 1
        }
        var settings = SettingsStore.load().copy(
            outputDir = output.absolutePath,
            qualityStrategy = options.quality ?: SettingsStore.load().qualityStrategy,
            outputTemplate = options.outputTemplate ?: SettingsStore.load().outputTemplate,
            downloadRetryCount = options.retries ?: SettingsStore.load().downloadRetryCount,
            downloadSpeedLimitKbps = options.rateLimitKbps ?: SettingsStore.load().downloadSpeedLimitKbps,
            proxyUrl = options.proxy ?: SettingsStore.load().proxyUrl,
            downloadTimeoutSeconds = options.timeoutSeconds ?: SettingsStore.load().downloadTimeoutSeconds,
        )
        val platformOption = options.platform?.let { id ->
            MusicPlatform.entries.firstOrNull { it.id.equals(id, ignoreCase = true) || it.displayName.contains(id) }
                ?: run { print(options, "未知平台：$id"); return 1 }
        }
        if (options.cookie != null) {
            val platform = platformOption ?: run { print(options, "--cookie 需要同时指定 --platform"); return 1 }
            settings = when (platform) {
                MusicPlatform.NETEASE -> settings.copy(neteaseCookie = options.cookie)
                MusicPlatform.QQ -> settings.copy(qqCookie = options.cookie)
                MusicPlatform.KUGOU -> settings.copy(kugouCookie = options.cookie)
                MusicPlatform.KUWO -> settings.copy(kuwoCookie = options.cookie)
            }
        }

        OnlineNetwork.configure(settings)
        val providers = platformOption?.let { listOf(ProviderRegistry.require(it.id)) } ?: ProviderRegistry.all
        providers.forEach { provider ->
            val cookie = when (provider.platform) {
                MusicPlatform.NETEASE -> settings.neteaseCookie
                MusicPlatform.QQ -> settings.qqCookie
                MusicPlatform.KUGOU -> settings.kugouCookie
                MusicPlatform.KUWO -> settings.kuwoCookie
            }
            if (!cookie.isNullOrBlank()) runCatching { provider.restoreSession(cookie) }
        }

        if (options.search != null && !options.downloadSearchResults) {
            val results = providers.flatMap { provider -> runCatching { provider.search(options.search, options.limit) }.getOrDefault(emptyList()) }
                .take(options.limit)
            if (options.json) {
                println(gson.toJson(mapOf(
                    "event" to "search",
                    "count" to results.size,
                    "results" to results.map {
                        mapOf(
                            "platform" to it.platform.id,
                            "kind" to it.kind.name.lowercase(),
                            "id" to it.id,
                            "title" to it.title,
                            "subtitle" to it.subtitle,
                        )
                    },
                )))
            } else {
                results.forEach { result -> println("${result.platform.displayName}\t${result.kind.name.lowercase()}\t${result.title}\t${result.subtitle}\t${result.id}") }
            }
            return 0
        }

        val songs = resolveSongs(options, providers)
        if (songs.isEmpty()) {
            print(options, "没有找到可下载的歌曲")
            return 1
        }
        if (options.json) {
            println(gson.toJson(mapOf("event" to "resolved", "count" to songs.size)))
        } else {
            println("准备下载 ${songs.size} 首到 ${output.absolutePath}")
        }

        val temp = Files.createTempFile("musicunlock-cli-tasks", ".json").toFile()
        val manager = DownloadTaskManager(
            library = LibraryIndex(File(temp.parentFile, "library-${System.nanoTime()}.json")),
            settingsProvider = { settings },
            taskFile = temp,
        )
        val preferences = settings.toOnlineDownloadPreferences().let {
            if (options.forceMp3) it.copy(forceMp3 = true, mp3BitrateKbps = settings.bitrateKbps) else it
        }
        val ids = ManagerBatchQueue.enqueue(manager, songs, output, preferences)
        val finalSettings = settings
        val listener = manager.addListener { snapshot ->
            if (!options.json || snapshot.id !in ids) return@addListener
            runCatching {
                println(gson.toJson(mapOf(
                    "event" to "progress",
                    "id" to snapshot.id,
                    "state" to snapshot.state.name.lowercase(),
                    "progress" to snapshot.progress,
                    "speed" to snapshot.speedBytesPerSecond,
                    "eta" to snapshot.etaSeconds,
                    "message" to snapshot.message,
                    "file" to snapshot.outputFile?.absolutePath,
                )))
            }
        }
        runBlocking {
            manager.tasks.first { tasks -> ids.all { id -> tasks.firstOrNull { it.id == id }?.isTerminal == true } }
        }
        listener.close()
        val tasks = manager.tasks.value.filter { it.id in ids }
        val success = tasks.count { it.state in setOf(DownloadTaskState.COMPLETED, DownloadTaskState.SKIPPED) }
        val failed = tasks.size - success
        print(options, "下载完成：成功 $success · 失败 $failed")
        temp.delete()
        return if (failed == 0) 0 else 1
    }

    private fun resolveSongs(options: OnlineCliOptions, providers: List<musicunlock.online.OnlineMusicProvider>): List<Pair<musicunlock.online.OnlineMusicProvider, MusicSong>> {
        options.url?.let { input ->
            val link = MusicLinkResolver.parse(input) ?: return emptyList()
            val provider = ProviderRegistry.require(link.platform.id)
            val songs = when (link.kind) {
                MusicLinkKind.SONG -> listOfNotNull(provider.song(link.id))
                MusicLinkKind.PLAYLIST -> {
                    val playlist = provider.playlist(link.id) ?: musicunlock.online.MusicPlaylist(link.id, "分享歌单", null, 0)
                    provider.songs(playlist)
                }
                MusicLinkKind.ALBUM -> provider.album(link.id)?.second.orEmpty()
                MusicLinkKind.ARTIST -> provider.artistSongs(link.id, 100)
            }
            return songs.map { provider to it }
        }
        options.playlistId?.let { id ->
            return providers.flatMap { provider ->
                val playlist = provider.playlist(id) ?: musicunlock.online.MusicPlaylist(id, "歌单", null, 0)
                runCatching { provider.songs(playlist) }.getOrDefault(emptyList()).map { provider to it }
            }
        }
        if (options.favorites) {
            return providers.flatMap { provider ->
                runCatching {
                    val favorites = provider.playlists().filter {
                        it.metadata["isFavorite"] == "true" || it.name.contains("喜欢") || it.name.contains("收藏")
                    }
                    favorites.flatMap { playlist ->
                        provider.songs(playlist).map { provider to it }
                    }
                }.getOrDefault(emptyList())
            }
        }
        options.search?.let { query ->
            return providers.flatMap { provider ->
                runCatching { provider.search(query, options.limit) }.getOrDefault(emptyList())
                    .filter { it.kind == SearchResultKind.SONG }
                    .mapNotNull { result -> result.song?.let { provider to it } }
            }.take(options.limit)
        }
        return emptyList()
    }

    private fun print(options: OnlineCliOptions, message: String) {
        if (options.json) println(gson.toJson(mapOf("event" to "message", "message" to message))) else println(message)
    }
}

private object ManagerBatchQueue {
    fun enqueue(
        manager: DownloadTaskManager,
        songs: List<Pair<musicunlock.online.OnlineMusicProvider, MusicSong>>,
        output: File,
        preferences: musicunlock.online.DownloadPreferences,
    ): Set<String> {
        val byProvider = songs.groupBy({ it.first }, { it.second })
        return byProvider.flatMap { (provider, values) ->
            manager.enqueueBatch(
                provider = provider,
                songs = values,
                outputDir = output,
                preferences = preferences,
            )
        }.toSet()
    }
}
