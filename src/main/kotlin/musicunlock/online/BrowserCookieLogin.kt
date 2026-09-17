package musicunlock.online

import com.google.gson.JsonParser
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** 浏览器登录配置：平台只需要提供登录页、Cookie 域和登录完成条件。 */
data class BrowserCookieLoginConfig(
    val loginUrl: String,
    val userDataPrefix: String,
    val acceptsDomain: (String) -> Boolean,
    val isReady: (Map<String, String>) -> Boolean,
)

/**
 * 通用 Chrome/Edge 浏览器登录。
 *
 * 启动临时浏览器配置打开平台登录页，通过 CDP 读取浏览器 Cookie。这样各平台
 * 的浏览器登录、进程清理和调试端口管理不再重复实现。
 */
object BrowserCookieLogin {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private data class CdpCookie(val name: String, val domain: String, val value: String)

    fun findBrowser(): String? {
        val os = System.getProperty("os.name").lowercase()
        val candidates = when {
            os.contains("mac") -> listOf(
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
                "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
            )
            os.contains("win") -> listOf(
                System.getenv("LOCALAPPDATA")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                System.getenv("PROGRAMFILES")?.let { "$it\\Google\\Chrome\\Application\\chrome.exe" },
                System.getenv("LOCALAPPDATA")?.let { "$it\\Microsoft\\Edge\\Application\\msedge.exe" },
                "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            ).filterNotNull()
            else -> listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser", "microsoft-edge")
        }
        return candidates.firstOrNull { path ->
            if (path.contains('/') || path.contains('\\')) Files.exists(Path.of(path)) else onPath(path)
        }
    }

    fun login(
        config: BrowserCookieLoginConfig,
        onStatus: (String) -> Unit,
        onCookies: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val browser = findBrowser()
        if (browser == null) {
            onError("未找到 Chrome/Edge 浏览器，请使用下方手动粘贴 Cookie 登录")
            return
        }
        val port = freePort()
        val userDataDir = Files.createTempDirectory(config.userDataPrefix)
        val process = try {
            launchBrowser(browser, port, userDataDir.toString(), config.loginUrl)
        } catch (e: Exception) {
            onError("无法启动浏览器：${e.message}")
            return
        }
        try {
            onStatus("已打开浏览器，请在页面中完成登录…")
            waitForDevTools(port, 20_000)
            val deadline = System.currentTimeMillis() + 5 * 60_000
            while (System.currentTimeMillis() < deadline) {
                val cookies = readCookies(port, config.acceptsDomain)
                if (cookies.isNotEmpty() && config.isReady(cookies)) {
                    onStatus("已获取登录状态，正在读取账号…")
                    onCookies(cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" })
                    return
                }
                Thread.sleep(2_000)
            }
            onError("等待登录超时（5 分钟），请重试")
        } catch (e: Exception) {
            onError("浏览器登录失败：${e.message}")
        } finally {
            process.destroy()
            runCatching { Thread.sleep(500) }
            process.destroyForcibly()
            runCatching { userDataDir.toFile().deleteRecursively() }
        }
    }

    private fun onPath(name: String): Boolean = try {
        ProcessBuilder("which", name).start().let { it.waitFor() == 0 }
    } catch (e: Exception) {
        false
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun launchBrowser(browser: String, port: Int, userDataDir: String, loginUrl: String): Process {
        return ProcessBuilder(
            browser,
            "--remote-debugging-port=$port",
            "--user-data-dir=$userDataDir",
            "--no-first-run",
            "--no-default-browser-check",
            loginUrl,
        ).start()
    }

    private fun waitForDevTools(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (devToolsUp(port)) return
            Thread.sleep(500)
        }
        throw IllegalStateException("浏览器调试端口未就绪")
    }

    private fun devToolsUp(port: Int): Boolean = try {
        val resp = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json/version")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        resp.statusCode() == 200
    } catch (e: Exception) {
        false
    }

    private fun readCookies(port: Int, acceptsDomain: (String) -> Boolean): Map<String, String> {
        val wsUrl = findPageWs(port) ?: return emptyMap()
        return cdpCookies(wsUrl)
            .filter { acceptsDomain(it.domain) && it.value.isNotEmpty() }
            .fold(LinkedHashMap()) { acc, cookie -> acc.putIfAbsent(cookie.name, cookie.value); acc }
    }

    private fun findPageWs(port: Int): String? = try {
        val resp = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val arr = JsonParser.parseString(resp.body()).asJsonArray
        arr.firstOrNull { it.asJsonObject.get("type")?.asString == "page" }
            ?.asJsonObject?.get("webSocketDebuggerUrl")?.asString
    } catch (e: Exception) {
        null
    }

    private fun cdpCookies(wsUrl: String): List<CdpCookie> {
        return try {
            val queue = LinkedBlockingQueue<String>()
            val listener = object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) {
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    queue.add(data.toString())
                    webSocket.request(1)
                    return null
                }
            }
            val ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), listener)
                .get(10, TimeUnit.SECONDS)
            ws.sendText("""{"id":1,"method":"Network.getAllCookies"}""", true).get(10, TimeUnit.SECONDS)

            val deadline = System.currentTimeMillis() + 10_000
            val sb = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                sb.append(queue.poll(2, TimeUnit.SECONDS) ?: continue)
                val parsed = runCatching {
                    val root = JsonParser.parseString(sb.toString()).asJsonObject
                    if (!root.has("id") || root.get("id").asInt != 1) return@runCatching null
                    root.getAsJsonObject("result")?.getAsJsonArray("cookies")?.mapNotNull { element ->
                        val o = element.asJsonObject
                        val name = o.get("name")?.asString ?: return@mapNotNull null
                        CdpCookie(
                            name = name,
                            domain = o.get("domain")?.asString ?: "",
                            value = o.get("value")?.asString ?: "",
                        )
                    }
                }.getOrNull()
                if (parsed != null) return parsed
            }
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
