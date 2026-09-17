package musicunlock.kuwo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.online.BrowserCookieLogin
import musicunlock.online.BrowserCookieLoginConfig
import musicunlock.online.MusicAccount
import musicunlock.online.DownloadTaskManager
import musicunlock.settings.AppSettings
import musicunlock.settings.SettingsUpdate
import musicunlock.ui.BrowserLoginPanel
import musicunlock.ui.LoginCardFrame
import musicunlock.ui.LoginMethodTab
import musicunlock.ui.OnlineDownloadPage
import musicunlock.ui.toMusicAccount
import musicunlock.ui.toSnapshot

@Composable
fun KuwoDownloadPage(
    settings: AppSettings,
    onUpdateSettings: SettingsUpdate,
    downloadManager: DownloadTaskManager,
    modifier: Modifier = Modifier,
) {
    OnlineDownloadPage(
        provider = KuwoApi,
        savedCookie = settings.kuwoCookie,
        savedAccount = settings.kuwoAccount?.toMusicAccount(),
        outputDir = settings.outputDir,
        onOutputDirChange = { value -> onUpdateSettings { it.copy(outputDir = value) } },
        onSessionChanged = { cookie, account ->
            onUpdateSettings { it.copy(kuwoCookie = cookie, kuwoAccount = account?.toSnapshot()) }
        },
        downloadManager = downloadManager,
        settings = settings,
        onUpdateSettings = onUpdateSettings,
        modifier = modifier,
    ) { onLoggedIn ->
        KuwoLoginCard(onLoggedIn = onLoggedIn)
    }
}

@Composable
private fun KuwoLoginCard(onLoggedIn: (MusicAccount) -> Unit) {
    val scope = rememberCoroutineScope()
    var browserStatus by remember { mutableStateOf("") }
    var browserBusy by remember { mutableStateOf(false) }
    var cookieText by remember { mutableStateOf("") }
    var cookieStatus by remember { mutableStateOf("") }
    var cookieBusy by remember { mutableStateOf(false) }

    fun doBrowserLogin() {
        if (browserBusy) return
        browserBusy = true
        browserStatus = "正在启动浏览器…"
        scope.launch {
            withContext(Dispatchers.IO) {
                BrowserCookieLogin.login(
                    config = BrowserCookieLoginConfig(
                        loginUrl = "https://www.kuwo.cn/",
                        userDataPrefix = "kuwo-browser-",
                        acceptsDomain = { domain ->
                            val value = domain.removePrefix(".").lowercase()
                            value == "kuwo.cn" || value.endsWith(".kuwo.cn")
                        },
                        isReady = { cookies ->
                            val userId = cookies["userid"] ?: cookies["t3kwid"] ?: cookies["uid"]
                            val sid = cookies["sid"] ?: cookies["websid"]
                            !userId.isNullOrBlank() && !sid.isNullOrBlank()
                        },
                    ),
                    onStatus = { browserStatus = it },
                    onCookies = { cookie ->
                        val result = runCatching { KuwoApi.loginWithCookie(cookie) }
                        result.onSuccess {
                            browserStatus = "登录成功"
                            onLoggedIn(it)
                        }.onFailure { browserStatus = "浏览器登录失败：${it.message}" }
                    },
                    onError = { browserStatus = it },
                )
            }
            browserBusy = false
        }
    }

    fun doCookieLogin() {
        if (cookieText.isBlank()) {
            cookieStatus = "请粘贴 Cookie"
            return
        }
        scope.launch {
            cookieBusy = true
            cookieStatus = "正在登录…"
            val result = withContext(Dispatchers.IO) {
                runCatching { KuwoApi.loginWithCookie(cookieText.trim()) }
            }
            result.onSuccess { onLoggedIn(it) }
                .onFailure { cookieStatus = "登录失败：${it.message}" }
            cookieBusy = false
        }
    }

    LoginCardFrame(
        title = "登录酷我音乐",
        subtitle = "登录后可读取收藏与创建的歌单",
        tabs = {
            LoginMethodTab("浏览器", true, Modifier.weight(1f)) {}
        },
    ) {
        BrowserLoginPanel(
            browserStatus = browserStatus,
            browserBusy = browserBusy,
            browserError = browserStatus.startsWith("未找到") || browserStatus.startsWith("浏览器登录失败"),
            browserDefaultStatus = "将打开浏览器进入酷我登录页，登录成功后自动读取登录态",
            onBrowserLogin = ::doBrowserLogin,
            cookieText = cookieText,
            onCookieTextChange = { cookieText = it },
            cookieStatus = cookieStatus,
            cookieBusy = cookieBusy,
            cookieError = cookieStatus.startsWith("登录失败") || cookieStatus.startsWith("请"),
            cookiePlaceholder = "userid=xxx; sid=yyy; kw_token=zzz; …",
            cookieDefaultStatus = "浏览器不可用时，可粘贴包含 userid、sid 的完整 Cookie",
            onCookieLogin = ::doCookieLogin,
        )
    }
}
