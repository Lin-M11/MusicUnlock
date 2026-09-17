package musicunlock.kugou

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.online.BrowserCookieLogin
import musicunlock.online.BrowserCookieLoginConfig
import musicunlock.online.MusicAccount
import musicunlock.online.OnlineQrState
import musicunlock.online.DownloadTaskManager
import musicunlock.settings.AppSettings
import musicunlock.settings.SettingsUpdate
import musicunlock.ui.BrowserLoginPanel
import musicunlock.ui.LoginCardFrame
import musicunlock.ui.LoginMethod
import musicunlock.ui.LoginMethodTab
import musicunlock.ui.OnlineDownloadPage
import musicunlock.ui.QrLoginPanel
import musicunlock.ui.cleanTokens
import musicunlock.ui.qrBitmap
import musicunlock.ui.toMusicAccount
import musicunlock.ui.toSnapshot

@Composable
fun KugouDownloadPage(
    settings: AppSettings,
    onUpdateSettings: SettingsUpdate,
    downloadManager: DownloadTaskManager,
    modifier: Modifier = Modifier,
) {
    OnlineDownloadPage(
        provider = KugouApi,
        savedCookie = settings.kugouCookie,
        savedAccount = settings.kugouAccount?.toMusicAccount(),
        outputDir = settings.outputDir,
        onOutputDirChange = { value -> onUpdateSettings { it.copy(outputDir = value) } },
        onSessionChanged = { cookie, account ->
            onUpdateSettings { it.copy(kugouCookie = cookie, kugouAccount = account?.toSnapshot()) }
        },
        downloadManager = downloadManager,
        settings = settings,
        onUpdateSettings = onUpdateSettings,
        modifier = modifier,
    ) { onLoggedIn ->
        KugouLoginCard(onLoggedIn = onLoggedIn)
    }
}

@Composable
private fun KugouLoginCard(onLoggedIn: (MusicAccount) -> Unit) {
    val t = cleanTokens()
    val scope = rememberCoroutineScope()
    var loginMode by remember { mutableStateOf(LoginMethod.QR) }
    var qrKey by remember { mutableStateOf<String?>(null) }
    var qrImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var loginStatus by remember { mutableStateOf("") }
    var expired by remember { mutableStateOf(false) }
    var browserStatus by remember { mutableStateOf("") }
    var browserBusy by remember { mutableStateOf(false) }
    var cookieText by remember { mutableStateOf("") }
    var cookieStatus by remember { mutableStateOf("") }
    var cookieBusy by remember { mutableStateOf(false) }

    fun fetchQr() {
        scope.launch {
            loginStatus = "正在获取二维码…"
            expired = false
            qrImage = null
            qrKey = withContext(Dispatchers.IO) {
                runCatching { KugouApi.qrKey() }.getOrElse {
                    loginStatus = "获取二维码失败：${it.message}"
                    null
                }
            }
        }
    }

    LaunchedEffect(Unit) { fetchQr() }

    LaunchedEffect(qrKey) {
        val key = qrKey ?: return@LaunchedEffect
        qrImage = withContext(Dispatchers.Default) {
            qrBitmap(
                content = "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=$key",
                size = 232,
                fg = t.text.toArgb(),
                bg = t.surface.toArgb(),
            )?.toComposeImageBitmap()
        }
        loginStatus = "等待扫码"
        while (isActive) {
            val result = withContext(Dispatchers.IO) {
                runCatching { KugouApi.qrCheck(key) }.getOrNull()
            }
            when (result?.state) {
                OnlineQrState.WAIT -> loginStatus = result.message ?: "等待扫码"
                OnlineQrState.SCANNED -> loginStatus = result.message ?: "已扫码，请在手机上确认登录"
                OnlineQrState.EXPIRED -> {
                    loginStatus = result.message ?: "二维码已失效，请刷新"
                    expired = true
                    break
                }
                OnlineQrState.SUCCESS -> {
                    val account = KugouApi.account()
                    if (account != null) onLoggedIn(account) else loginStatus = "登录成功，但读取账号失败"
                    break
                }
                OnlineQrState.ERROR -> {
                    loginStatus = result.message ?: "登录状态异常，请刷新后重试"
                    expired = true
                    break
                }
                null -> loginStatus = "登录状态查询失败，请刷新后重试"
            }
            delay(2_000)
        }
    }

    fun doBrowserLogin() {
        if (browserBusy) return
        browserBusy = true
        browserStatus = "正在启动浏览器…"
        scope.launch {
            withContext(Dispatchers.IO) {
                BrowserCookieLogin.login(
                    config = BrowserCookieLoginConfig(
                        loginUrl = "https://www.kugou.com/",
                        userDataPrefix = "kugou-browser-",
                        acceptsDomain = { domain ->
                            val value = domain.removePrefix(".").lowercase()
                            value == "kugou.com" || value.endsWith(".kugou.com")
                        },
                        isReady = { cookies ->
                            !cookies["token"].isNullOrBlank() && !cookies["userid"].isNullOrBlank()
                        },
                    ),
                    onStatus = { browserStatus = it },
                    onCookies = { cookie ->
                        val result = runCatching { KugouApi.loginWithCookie(cookie) }
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
                runCatching { KugouApi.loginWithCookie(cookieText.trim()) }
            }
            result.onSuccess { onLoggedIn(it) }
                .onFailure { cookieStatus = "登录失败：${it.message}" }
            cookieBusy = false
        }
    }

    LoginCardFrame(
        title = "登录酷狗音乐",
        subtitle = "登录后可读取收藏与创建的歌单",
        tabs = {
            LoginMethodTab("扫码", loginMode == LoginMethod.QR, Modifier.weight(1f)) { loginMode = LoginMethod.QR }
            LoginMethodTab("浏览器", loginMode == LoginMethod.BROWSER, Modifier.weight(1f)) { loginMode = LoginMethod.BROWSER }
        },
    ) {
        when (loginMode) {
            LoginMethod.QR -> QrLoginPanel(
                qrImage = qrImage,
                status = loginStatus,
                statusError = expired || loginStatus.startsWith("获取二维码失败"),
                defaultStatus = "使用酷狗音乐客户端扫码登录",
                footer = "二维码约 2 分钟有效；失效后点击刷新",
                onRefresh = ::fetchQr,
            )
            LoginMethod.BROWSER -> BrowserLoginPanel(
                browserStatus = browserStatus,
                browserBusy = browserBusy,
                browserError = browserStatus.startsWith("未找到") || browserStatus.startsWith("浏览器登录失败"),
                browserDefaultStatus = "将打开浏览器进入酷狗登录页，登录成功后自动读取登录态",
                onBrowserLogin = ::doBrowserLogin,
                cookieText = cookieText,
                onCookieTextChange = { cookieText = it },
                cookieStatus = cookieStatus,
                cookieBusy = cookieBusy,
                cookieError = cookieStatus.startsWith("登录失败") || cookieStatus.startsWith("请"),
                cookiePlaceholder = "token=xxx; userid=yyy; dfid=zzz; …",
                cookieDefaultStatus = "浏览器不可用时，可粘贴包含 token、userid 的完整 Cookie",
                onCookieLogin = ::doCookieLogin,
            )
            LoginMethod.SMS -> Unit
        }
    }
}
