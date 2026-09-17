package musicunlock.automation

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import musicunlock.BuildInfo
import musicunlock.library.LibraryIndex
import musicunlock.online.DownloadTaskManager
import musicunlock.online.ProviderHealthService
import musicunlock.service.ConversionTaskManager
import musicunlock.settings.AppSettings
import musicunlock.settings.SettingsStore
import musicunlock.sync.SubscriptionManager
import musicunlock.watch.FolderWatcherService
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

/** 无窗口守护进程：后台收件箱、歌单追更与仅监听本机的自动化 API。 */
class DesktopAutomationService(
    private val settingsProvider: () -> AppSettings = SettingsStore::load,
    private val forceApi: Boolean = false,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val library = LibraryIndex()
    private val conversionManager = ConversionTaskManager(library = library)
    private val downloadManager = DownloadTaskManager(library = library, settingsProvider = settingsProvider)
    private val subscriptionManager = SubscriptionManager(
        settingsProvider = settingsProvider,
        updateSettings = { transform -> SettingsStore.update(transform) },
        taskManager = downloadManager,
        library = library,
    )
    private val folderWatcher = FolderWatcherService(settingsProvider, conversionManager)
    private var api: LocalAutomationApiServer? = null

    fun start() {
        subscriptionManager.start(scope)
        folderWatcher.start(scope)
        val settings = settingsProvider()
        if (forceApi || settings.localApiEnabled) {
            val token = settings.localApiToken?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
            val server = LocalAutomationApiServer(settings.localApiPort, token, this)
            server.start()
            api = server
            println("MusicUnlock API 已启动：http://127.0.0.1:${settings.localApiPort}")
            println("X-MusicUnlock-Token: $token")
        }
    }

    fun health(): Map<String, Any?> = mapOf(
        "version" to BuildInfo.VERSION,
        "conversionTasks" to conversionManager.tasks.value.size,
        "downloadTasks" to downloadManager.tasks.value.size,
        "playlistSubscriptions" to settingsProvider().subscriptions.count { it.enabled },
        "watchFolders" to settingsProvider().watchFolders.size,
        "providers" to ProviderHealthService.check(settingsProvider()),
    )

    fun tasks(): Map<String, Any> = mapOf(
        "conversion" to conversionManager.tasks.value,
        "download" to downloadManager.tasks.value,
    )

    fun queueConversion(paths: List<String>): List<String> {
        val settings = settingsProvider()
        return conversionManager.enqueueBatch(
            inputPaths = paths,
            outputDir = settings.outputDir,
            outputFormat = settings.outputFormat,
            bitrateKbps = settings.bitrateKbps,
            outputTemplate = settings.localOutputTemplate,
            existingFilePolicy = settings.localExistingFilePolicy,
            forceOverwrite = !settings.skipExisting,
            deduplicate = settings.dedup,
        )
    }

    fun syncPlaylists() = subscriptionManager.syncAllDue().map { result ->
        mapOf(
            "id" to result.subscriptionId,
            "playlist" to result.playlistName,
            "total" to result.total,
            "added" to result.added,
            "skipped" to result.skipped,
            "error" to result.error,
        )
    }

    fun scanLibrary(): Map<String, Any?> {
        val settings = settingsProvider()
        val report = library.scan(File(settings.outputDir), hash = true)
        return mapOf(
            "indexed" to report.indexed,
            "removed" to report.removed,
            "invalid" to report.invalid,
        )
    }

    override fun close() {
        api?.close()
        scope.cancel()
    }

    companion object {
        fun runForeground(): Unit = runBlocking {
            val service = DesktopAutomationService(forceApi = true)
            service.start()
            Runtime.getRuntime().addShutdownHook(Thread { service.close() })
            awaitCancellation()
        }
    }
}

private class LocalAutomationApiServer(
    port: Int,
    private val token: String,
    private val service: DesktopAutomationService,
) : AutoCloseable {
    private val gson = Gson()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

    fun start() {
        server.executor = Executors.newFixedThreadPool(2)
        server.createContext("/health") { exchange -> handle(exchange) { service.health() } }
        server.createContext("/tasks") { exchange -> handle(exchange) { service.tasks() } }
        server.createContext("/sync") { exchange -> handle(exchange) { service.syncPlaylists() } }
        server.createContext("/scan") { exchange -> handle(exchange) { service.scanLibrary() } }
        server.createContext("/convert") { exchange ->
            handle(exchange) {
                val body = exchange.requestBody.bufferedReader().readText()
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrDefault(JsonObject())
                val paths = root.getAsJsonArray("paths")?.map { it.asString } ?: emptyList()
                mapOf("taskIds" to service.queueConversion(paths))
            }
        }
        server.start()
    }

    private inline fun handle(exchange: HttpExchange, block: () -> Any?) {
        try {
            if (exchange.requestHeaders.getFirst("X-MusicUnlock-Token") != token) {
                respond(exchange, 401, mapOf("error" to "unauthorized"))
                return
            }
            if (!exchange.requestMethod.equals("GET", ignoreCase = true) && !exchange.requestMethod.equals("POST", ignoreCase = true)) {
                respond(exchange, 405, mapOf("error" to "method not allowed"))
                return
            }
            respond(exchange, 200, block())
        } catch (error: Exception) {
            respond(exchange, 500, mapOf("error" to (error.message ?: error.toString())))
        } finally {
            exchange.close()
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: Any?) {
        val bytes = gson.toJson(body).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
    }
}
