package musicunlock.qq

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

/**
 * 自动浏览器登录（与网易云浏览器登录体验一致）：
 * 启动本机 Chrome 系浏览器打开 QQ 音乐官方登录页，用户完成登录后，
 * 通过 Chrome DevTools Protocol 自动读取 Cookie（含播放票据
 * qm_keyst / qqmusic_key）并完成登录。
 *
 * 适用场景：扫码登录被风控拦截或不想扫码时，官方网页在真实浏览器中
 * 可正常完成登录。
 */
object QqBrowserLogin {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private data class CdpCookie(val name: String, val domain: String, val value: String)

    private const val LOGIN_URL = "https://y.qq.com/"

    /** QQ 音乐登录需要优先携带的 Cookie（与网页端一致）。 */
    private val QQ_COOKIE_PRIORITY = listOf(
        "uin", "qqmusic_uin", "wxuin", "login_type", "qm_keyst", "qqmusic_key",
        "music_key", "wxskey", "p_skey", "skey",
    )

    /** 检测本机可用的 Chrome 系浏览器可执行文件。 */
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

    private fun onPath(name: String): Boolean = try {
        ProcessBuilder("which", name).start().let { it.waitFor() == 0 }
    } catch (e: Exception) {
        false
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun launchBrowser(browser: String, port: Int, userDataDir: String): Process {
        val cmd = listOf(
            browser,
            "--remote-debugging-port=$port",
            "--user-data-dir=$userDataDir",
            "--no-first-run",
            "--no-default-browser-check",
            LOGIN_URL,
        )
        return ProcessBuilder(cmd).start()
    }

    /**
     * 自动浏览器登录。阻塞直至成功/失败/超时，通过回调上报进度。
     *
     * @param onStatus 进度文本（工作线程回调）
     * @param onResult 登录成功（账号信息）
     * @param onError  失败原因
     */
    fun login(
        onStatus: (String) -> Unit,
        onResult: (QqAccount) -> Unit,
        onError: (String) -> Unit,
    ) {
        val browser = findBrowser()
        if (browser == null) {
            onError("未找到 Chrome/Edge 浏览器，请改用 Cookie 登录")
            return
        }
        val port = freePort()
        val userDataDir = Files.createTempDirectory("qqmusic-browser-")
        val process = try {
            launchBrowser(browser, port, userDataDir.toString())
        } catch (e: Exception) {
            onError("无法启动浏览器：${e.message}")
            return
        }
        try {
            onStatus("已打开浏览器，请在浏览器中登录 QQ 音乐（可扫码登录）…")
            waitForDevTools(port, 30_000)
            onStatus("等待登录，检测到登录后会自动读取…")
            val deadline = System.currentTimeMillis() + 5 * 60_000
            while (System.currentTimeMillis() < deadline) {
                val cookies = readQqCookies(port)
                if (playbackReady(cookies)) {
                    onStatus("已检测到登录，正在读取账号…")
                    val header = buildHeader(cookies)
                    val acc = QqMusicApi.loginWithCookie(header)
                    onResult(acc)
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

    /** 读取 QQ 音乐 / qq.com 域下的全部 Cookie（含 HttpOnly）。 */
    private fun readQqCookies(port: Int): List<CdpCookie> {
        val wsUrl = findPageWs(port) ?: return emptyList()
        return cdpCookies(wsUrl).filter { isQqDomain(it.domain) }
    }

    private fun findPageWs(port: Int): String? = try {
        val resp = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/json")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val arr = JsonParser.parseString(resp.body()).asJsonArray
        arr.firstOrNull { el ->
            el.asJsonObject.get("type")?.asString == "page"
        }?.asJsonObject?.get("webSocketDebuggerUrl")?.asString
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
                    if (last) webSocket.request(1)
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
                val chunk = queue.poll(2, TimeUnit.SECONDS) ?: continue
                sb.append(chunk)
                val parsed = try {
                    val obj = JsonParser.parseString(sb.toString()).asJsonObject
                    if (obj.has("id") && obj.get("id").asInt == 1) {
                        val result = obj.getAsJsonObject("result") ?: return emptyList()
                        val arr = result.getAsJsonArray("cookies") ?: return emptyList()
                        arr.mapNotNull { el ->
                            val o = el.asJsonObject
                            val name = o.get("name")?.asString ?: return@mapNotNull null
                            val domain = o.get("domain")?.asString ?: ""
                            val value = o.get("value")?.asString ?: ""
                            CdpCookie(name, domain, value)
                        }
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
                if (parsed != null) return parsed
            }
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isQqDomain(domain: String): Boolean {
        val d = domain.removePrefix(".").lowercase()
        return d == "qq.com" || d.endsWith(".qq.com")
    }

    /** 是否已具备可下载的登录态：uin + 播放票据 qm_keyst / qqmusic_key。 */
    private fun playbackReady(cookies: List<CdpCookie>): Boolean {
        val map = cookies.associate { it.name to it.value }
        val rawUin = if (map["login_type"] == "2") {
            map["wxuin"] ?: map["uin"]
        } else {
            map["uin"] ?: map["qqmusic_uin"] ?: map["wxuin"]
        }
        val uin = rawUin?.filter { it.isDigit() }
        val key = map["qm_keyst"] ?: map["qqmusic_key"] ?: map["music_key"]
        return uin != null && uin.isNotEmpty() && key != null && key.isNotEmpty()
    }

    /** 按网页端顺序拼装 Cookie 头。 */
    private fun buildHeader(cookies: List<CdpCookie>): String {
        val picked = LinkedHashMap<String, String>()
        cookies.forEach { (name, _, value) ->
            if (value.isNotEmpty()) picked.putIfAbsent(name, value)
        }
        val ordered = mutableListOf<Pair<String, String>>()
        for (name in QQ_COOKIE_PRIORITY) {
            picked.remove(name)?.let { ordered.add(name to it) }
        }
        picked.forEach { (k, v) -> ordered.add(k to v) }
        return ordered.joinToString("; ") { (k, v) -> "$k=$v" }
    }
}
