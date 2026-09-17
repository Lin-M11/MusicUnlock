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
import musicunlock.library.AudioQualityInspector
import musicunlock.library.LibrarySort
import musicunlock.library.SmartPlaylistKind
import musicunlock.library.LibraryIndex
import musicunlock.online.DownloadTaskManager
import musicunlock.online.ProviderHealthService
import musicunlock.service.ConversionTaskManager
import musicunlock.settings.AppSettings
import musicunlock.settings.SettingsStore
import musicunlock.sync.LibrarySyncService
import musicunlock.sync.MediaServerIntegrationService
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
    private val librarySync = LibrarySyncService()
    private val mediaServers = MediaServerIntegrationService()
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

    fun searchLibrary(query: String, limit: Int = 500, sort: LibrarySort = LibrarySort.TITLE): List<Map<String, Any?>> =
        library.search(query, limit, sort).map { it.toApiMap() }

    fun smartLibrary(kind: String, limit: Int = 500): List<Map<String, Any?>> {
        val smart = runCatching { SmartPlaylistKind.valueOf(kind.uppercase()) }.getOrDefault(SmartPlaylistKind.RECENTLY_ADDED)
        return library.smartPlaylist(smart, limit).map { it.toApiMap() }
    }

    fun playlists(): List<Map<String, Any?>> = library.playlists().map {
        mapOf("id" to it.id, "name" to it.name, "description" to it.description, "trackCount" to library.playlistTracks(it.id).size)
    }

    fun createPlaylist(name: String, description: String = ""): Map<String, Any?> = library.createPlaylist(name, description).let {
        mapOf("id" to it.id, "name" to it.name)
    }

    fun addPlaylistTracks(playlistId: String, paths: List<String>): Map<String, Any?> =
        mapOf("added" to library.addToPlaylist(playlistId, paths))

    fun inspectQuality(paths: List<String>): List<Map<String, Any?>> = paths.map { path ->
        val report = AudioQualityInspector.inspect(File(path))
        mapOf(
            "path" to report.path,
            "format" to report.format,
            "bitrateKbps" to report.bitRateKbps,
            "effectiveBitrateKbps" to report.effectiveBitRateKbps,
            "spectralCutoffHz" to report.spectralCutoffHz,
            "dynamicRangeDb" to report.dynamicRangeDb,
            "score" to report.score,
            "issues" to report.issues,
        )
    }

    fun simulateAutomation(path: String): List<Map<String, Any?>> {
        val file = File(path)
        return AutomationRuleEngine.simulate(settingsProvider(), file).map {
            mapOf("id" to it.id, "name" to it.name, "priority" to it.priority, "outputDir" to it.outputDir, "format" to it.outputFormat.name)
        }
    }

    fun syncProfile(id: String): Map<String, Any?> {
        val profile = settingsProvider().librarySyncProfiles.firstOrNull { it.id == id }
            ?: error("未找到同步配置：$id")
        return librarySync.sync(profile, library.all()).let {
            mapOf(
                "uploaded" to it.uploaded,
                "downloaded" to it.downloaded,
                "skipped" to it.skipped,
                "deleted" to it.deleted,
                "conflicts" to it.conflicts,
                "errors" to it.errors,
                "summary" to it.summary,
            )
        }
    }

    fun refreshMediaServer(id: String): Map<String, Any?> {
        val server = settingsProvider().mediaServers.firstOrNull { it.id == id }
            ?: error("未找到媒体服务器：$id")
        val result = mediaServers.trigger(server)
        return mapOf("server" to result.serverName, "success" to result.success, "message" to result.message)
    }

    fun openApi(): Map<String, Any?> = openApiDocument(settingsProvider())

    fun mcp(request: JsonObject): Map<String, Any?> {
        val id = request.get("id")
        val method = request.get("method")?.asString.orEmpty()
        return when (method) {
            "initialize" -> rpc(id, mapOf(
                "protocolVersion" to "2025-06-18",
                "capabilities" to mapOf("tools" to emptyMap<String, Any>()),
                "serverInfo" to mapOf("name" to "MusicUnlock", "version" to BuildInfo.VERSION),
            ))
            "tools/list" -> rpc(id, mapOf("tools" to mcpTools()))
            "tools/call" -> {
                val params = request.getAsJsonObject("params") ?: JsonObject()
                val name = params.get("name")?.asString.orEmpty()
                val arguments = params.getAsJsonObject("arguments") ?: JsonObject()
                rpc(id, callMcpTool(name, arguments))
            }
            else -> mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to -32601, "message" to "Method not found: $method"))
        }
    }

    private fun callMcpTool(name: String, arguments: JsonObject): Map<String, Any?> {
        val result: Any? = when (name) {
            "health" -> health()
            "tasks" -> tasks()
            "search_library" -> searchLibrary(arguments.get("query")?.asString.orEmpty(), arguments.get("limit")?.asInt ?: 500)
            "smart_library" -> smartLibrary(arguments.get("kind")?.asString ?: "RECENTLY_ADDED", arguments.get("limit")?.asInt ?: 500)
            "scan_library" -> scanLibrary()
            "convert" -> queueConversion(arguments.getAsJsonArray("paths")?.map { it.asString }.orEmpty())
            "quality_inspect" -> inspectQuality(arguments.getAsJsonArray("paths")?.map { it.asString }.orEmpty())
            "automation_simulate" -> simulateAutomation(arguments.get("path")?.asString.orEmpty())
            "sync_profile" -> syncProfile(arguments.get("id")?.asString.orEmpty())
            "refresh_media_server" -> refreshMediaServer(arguments.get("id")?.asString.orEmpty())
            "list_playlists" -> playlists()
            else -> error("Unknown tool: $name")
        }
        return mapOf("content" to listOf(mapOf("type" to "text", "text" to Gson().toJson(result))), "isError" to false)
    }

    private fun mcpTools(): List<Map<String, Any?>> = listOf(
        mcpTool("health", "读取服务、平台和后台任务健康状态", emptyMap()),
        mcpTool("tasks", "读取本地转换与在线下载任务", emptyMap()),
        mcpTool("search_library", "搜索 SQLite 曲库", mapOf("query" to stringSchema(), "limit" to integerSchema())),
        mcpTool("smart_library", "读取智能播放列表", mapOf("kind" to stringSchema(), "limit" to integerSchema())),
        mcpTool("scan_library", "扫描输出目录并更新曲库索引", emptyMap()),
        mcpTool("convert", "把文件加入本地转换队列", mapOf("paths" to arraySchema())),
        mcpTool("quality_inspect", "检查音频质量与伪无损风险", mapOf("paths" to arraySchema())),
        mcpTool("automation_simulate", "模拟自动化规则匹配", mapOf("path" to stringSchema())),
        mcpTool("sync_profile", "执行配置好的设备或 WebDAV 同步", mapOf("id" to stringSchema())),
        mcpTool("refresh_media_server", "触发 Plex、Jellyfin、Navidrome 或 Subsonic 扫描", mapOf("id" to stringSchema())),
        mcpTool("list_playlists", "列出本地歌单", emptyMap()),
    )

    private fun mcpTool(name: String, description: String, properties: Map<String, Any?>): Map<String, Any?> = mapOf(
        "name" to name,
        "description" to description,
        "inputSchema" to mapOf("type" to "object", "properties" to properties),
    )

    private fun stringSchema() = mapOf("type" to "string")
    private fun integerSchema() = mapOf("type" to "integer", "minimum" to 1)
    private fun arraySchema() = mapOf("type" to "array", "items" to mapOf("type" to "string"))

    private fun rpc(id: com.google.gson.JsonElement?, result: Any?): Map<String, Any?> = mapOf("jsonrpc" to "2.0", "id" to id, "result" to result)

    private fun musicunlock.library.LibraryEntry.toApiMap(): Map<String, Any?> = mapOf(
        "path" to path,
        "title" to title,
        "artist" to artist,
        "album" to album,
        "durationSeconds" to durationSeconds,
        "format" to format,
        "bitrateKbps" to bitRateKbps,
        "fingerprint" to fingerprint,
        "spectralCutoffHz" to spectralCutoffHz,
        "isFavorite" to isFavorite,
        "rating" to rating,
        "playCount" to playCount,
    )

    override fun close() {
        api?.close()
        scope.cancel()
    }

    companion object {
        fun openApiDocument(settings: AppSettings): Map<String, Any?> = mapOf(
            "openapi" to "3.1.0",
            "info" to mapOf("title" to "MusicUnlock Local API", "version" to BuildInfo.VERSION),
            "servers" to listOf(mapOf("url" to "http://127.0.0.1:${settings.localApiPort}")),
            "paths" to mapOf(
                "/health" to mapOf("get" to mapOf("summary" to "服务与平台健康状态")),
                "/tasks" to mapOf("get" to mapOf("summary" to "转换与下载任务")),
                "/library/search" to mapOf("get" to mapOf("summary" to "SQLite 曲库搜索")),
                "/library/smart" to mapOf("get" to mapOf("summary" to "智能播放列表")),
                "/quality" to mapOf("post" to mapOf("summary" to "音频质量体检")),
                "/automation/simulate" to mapOf("post" to mapOf("summary" to "模拟自动化规则")),
                "/sync/profile" to mapOf("post" to mapOf("summary" to "执行曲库同步")),
                "/media/refresh" to mapOf("post" to mapOf("summary" to "触发 Plex/Jellyfin/Navidrome/Subsonic 扫描")),
                "/mcp" to mapOf("post" to mapOf("summary" to "MCP JSON-RPC 接口")),
            ),
            "components" to mapOf(
                "securitySchemes" to mapOf(
                    "token" to mapOf("type" to "apiKey", "in" to "header", "name" to "X-MusicUnlock-Token"),
                ),
            ),
        )

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
                val root = exchange.jsonBody()
                val paths = root.getAsJsonArray("paths")?.map { it.asString } ?: emptyList()
                mapOf("taskIds" to service.queueConversion(paths))
            }
        }
        server.createContext("/openapi.json") { exchange -> handle(exchange) { service.openApi() } }
        server.createContext("/library/search") { exchange ->
            handle(exchange) {
                service.searchLibrary(
                    exchange.queryParameter("q").orEmpty(),
                    exchange.queryParameter("limit")?.toIntOrNull() ?: 500,
                )
            }
        }
        server.createContext("/library/smart") { exchange ->
            handle(exchange) {
                service.smartLibrary(
                    exchange.queryParameter("kind") ?: "RECENTLY_ADDED",
                    exchange.queryParameter("limit")?.toIntOrNull() ?: 500,
                )
            }
        }
        server.createContext("/playlists") { exchange -> handle(exchange) { service.playlists() } }
        server.createContext("/quality") { exchange ->
            handle(exchange) {
                service.inspectQuality(exchange.jsonBody().getAsJsonArray("paths")?.map { it.asString }.orEmpty())
            }
        }
        server.createContext("/automation/simulate") { exchange ->
            handle(exchange) { service.simulateAutomation(exchange.jsonBody().get("path")?.asString.orEmpty()) }
        }
        server.createContext("/sync/profile") { exchange ->
            handle(exchange) { service.syncProfile(exchange.jsonBody().get("id")?.asString.orEmpty()) }
        }
        server.createContext("/media/refresh") { exchange ->
            handle(exchange) { service.refreshMediaServer(exchange.jsonBody().get("id")?.asString.orEmpty()) }
        }
        server.createContext("/mcp") { exchange -> handle(exchange) { service.mcp(exchange.jsonBody()) } }
        server.start()
    }

    private fun HttpExchange.jsonBody(): JsonObject = runCatching {
        JsonParser.parseString(requestBody.bufferedReader().readText()).asJsonObject
    }.getOrDefault(JsonObject())

    private fun HttpExchange.queryParameter(name: String): String? =
        requestURI.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

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
