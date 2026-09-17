package musicunlock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.ncm.NeteaseAccount
import musicunlock.ncm.NeteaseApi
import musicunlock.ncm.NeteaseProvider
import musicunlock.ncm.QrLoginState
import musicunlock.ncm.TaskboardClient
import musicunlock.ncm.toMusicAccount
import musicunlock.online.BrowserCookieLogin
import musicunlock.online.BrowserCookieLoginConfig
import musicunlock.online.SubmitOutcome
import musicunlock.settings.AccountSnapshot
import musicunlock.online.DownloadTaskManager
import musicunlock.settings.AppSettings
import musicunlock.settings.SettingsUpdate
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException

/** 网易云下载页：登录逻辑保留扫码/短信/浏览器三种入口，下载与歌单部分复用统一在线页。 */
@Composable
fun DownloadPage(
    settings: AppSettings,
    onUpdateSettings: SettingsUpdate,
    downloadManager: DownloadTaskManager,
    modifier: Modifier = Modifier,
) {
    OnlineDownloadPage(
        provider = NeteaseProvider,
        savedCookie = settings.neteaseCookie,
        savedAccount = settings.neteaseAccount?.toMusicAccount(),
        outputDir = settings.outputDir,
        onOutputDirChange = { value -> onUpdateSettings { it.copy(outputDir = value) } },
        onSessionChanged = { cookie, account ->
            onUpdateSettings { it.copy(neteaseCookie = cookie, neteaseAccount = account?.toSnapshot()) }
        },
        downloadManager = downloadManager,
        settings = settings,
        onUpdateSettings = onUpdateSettings,
        modifier = modifier,
        submitSong = { song, playlistName ->
            val result = TaskboardClient.submitDownloadTask(
                songName = song.name,
                artist = song.artistText,
                sourcePlaylist = playlistName,
                threadId = TaskboardClient.sessionThreadId() ?: "",
            )
            SubmitOutcome(result.ok, if (result.ok) result.identifier.orEmpty() else result.message)
        },
    ) { onLoggedIn ->
        NeteaseLoginCard(onLoggedIn = { onLoggedIn(it.toMusicAccount()) })
    }
}

private fun NeteaseAccount.toSnapshot(): AccountSnapshot = AccountSnapshot(
    nickname = nickname,
    avatarUrl = avatarUrl,
    userId = userId.toString(),
)
// ============================================================
//  登录卡片
// ============================================================

@Composable
private fun NeteaseLoginCard(onLoggedIn: (NeteaseAccount) -> Unit) {
    val t = cleanTokens()
    val scope = rememberCoroutineScope()
    var loginMode by remember { mutableStateOf(LoginMethod.QR) }
    var qrKey by remember { mutableStateOf<String?>(null) }
    var qrImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var loginStatus by remember { mutableStateOf("") }
    var expired by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var smsStatus by remember { mutableStateOf("") }
    var smsBusy by remember { mutableStateOf(false) }
    var countdown by remember { mutableStateOf(0) }
    var cookieText by remember { mutableStateOf("") }
    var cookieStatus by remember { mutableStateOf("") }
    var cookieBusy by remember { mutableStateOf(false) }
    var browserStatus by remember { mutableStateOf("") }
    var browserBusy by remember { mutableStateOf(false) }

    fun fetchQr() {
        scope.launch {
            loginStatus = "正在获取二维码…"
            expired = false
            qrImage = null
            qrKey = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.qrKey() }.getOrElse {
                    loginStatus = "获取二维码失败：${it.message}"
                    null
                }
            }
        }
    }

    LaunchedEffect(Unit) { fetchQr() }

    LaunchedEffect(qrKey) {
        val key = qrKey ?: return@LaunchedEffect
        expired = false
        qrImage = withContext(Dispatchers.Default) {
            qrBitmap("https://music.163.com/login?codekey=$key", 232, t.text.toArgb(), t.surface.toArgb())
                ?.toComposeImageBitmap()
        }
        loginStatus = "等待扫码"
        while (isActive) {
            val result = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.qrCheck(key) }.getOrNull()
            }
            when (result?.state) {
                QrLoginState.EXPIRED -> {
                    loginStatus = "二维码已失效，请刷新"
                    expired = true
                    break
                }
                QrLoginState.SCANNED -> loginStatus = "已扫码，请在手机上确认登录"
                QrLoginState.RISK -> {
                    loginStatus = "扫码已确认，但账号触发安全验证，无法完成扫码登录。请改用短信或浏览器登录。"
                    expired = true
                    break
                }
                QrLoginState.SUCCESS -> {
                    val account = withContext(Dispatchers.IO) {
                        runCatching { NeteaseApi.account() }.getOrNull()
                    }
                    if (account != null) onLoggedIn(account) else loginStatus = "登录状态获取失败，请重试"
                    break
                }
                else -> loginStatus = "等待扫码"
            }
            delay(2_000)
        }
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            countdown -= 1
        }
    }

    fun sendCode() {
        if (phone.length != 11) {
            smsStatus = "请输入 11 位手机号"
            return
        }
        scope.launch {
            smsBusy = true
            smsStatus = "正在发送验证码…"
            val result = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.sendSmsCode(phone) }
            }
            result.onSuccess {
                smsStatus = it
                countdown = 60
            }.onFailure { smsStatus = "发送失败：${it.message}" }
            smsBusy = false
        }
    }

    fun doSmsLogin() {
        if (phone.length != 11) {
            smsStatus = "请输入 11 位手机号"
            return
        }
        if (smsCode.isBlank()) {
            smsStatus = "请输入短信验证码"
            return
        }
        scope.launch {
            smsBusy = true
            smsStatus = "正在登录…"
            val result = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.loginWithSms(phone, smsCode) }
            }
            result.onSuccess(onLoggedIn)
                .onFailure { smsStatus = "登录失败：${it.message}" }
            smsBusy = false
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
                runCatching { NeteaseApi.loginWithCookie(cookieText.trim()) }
            }
            result.onSuccess(onLoggedIn)
                .onFailure { cookieStatus = "登录失败：${it.message}" }
            cookieBusy = false
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
                        loginUrl = "https://music.163.com/#/login",
                        userDataPrefix = "ncm-browser-",
                        acceptsDomain = { domain ->
                            val value = domain.removePrefix(".").lowercase()
                            value == "163.com" || value.endsWith(".163.com") || value.endsWith(".music.163.com")
                        },
                        isReady = { cookies -> !cookies["MUSIC_U"].isNullOrBlank() },
                    ),
                    onStatus = { browserStatus = it },
                    onCookies = { cookie ->
                        val result = runCatching { NeteaseApi.loginWithCookie(cookie) }
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

    LoginCardFrame(
        title = "登录网易云",
        subtitle = "登录后可读取你的歌单并生成下载任务",
        tabs = {
            LoginMethodTab("扫码", loginMode == LoginMethod.QR, Modifier.weight(1f)) { loginMode = LoginMethod.QR }
            LoginMethodTab("短信", loginMode == LoginMethod.SMS, Modifier.weight(1f)) { loginMode = LoginMethod.SMS }
            LoginMethodTab("浏览器", loginMode == LoginMethod.BROWSER, Modifier.weight(1f)) { loginMode = LoginMethod.BROWSER }
        },
    ) {
        when (loginMode) {
            LoginMethod.QR -> QrLoginPanel(
                qrImage = qrImage,
                status = loginStatus,
                statusError = expired || loginStatus.startsWith("获取二维码失败") || loginStatus.startsWith("登录状态获取失败"),
                defaultStatus = "使用网易云音乐 App 扫码登录",
                footer = "二维码失效后点击刷新",
                onRefresh = ::fetchQr,
            )
            LoginMethod.SMS -> {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    placeholder = { Text("手机号", fontSize = 13.sp, color = t.textMuted) },
                    colors = smsFieldColors(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = smsCode,
                        onValueChange = { smsCode = it.filter(Char::isDigit).take(6) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        placeholder = { Text("短信验证码", fontSize = 13.sp, color = t.textMuted) },
                        colors = smsFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = ::sendCode,
                        enabled = !smsBusy && countdown == 0,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = t.primarySoft, contentColor = t.primary),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text(if (countdown > 0) "${countdown}s 后重发" else "发送验证码", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = ::doSmsLogin,
                    enabled = !smsBusy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = t.primary, contentColor = t.onPrimary),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text(if (smsBusy) "请稍候…" else "登录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    smsStatus.ifBlank { "验证码将发送到你的网易云绑定手机号" },
                    fontSize = 12.5.sp,
                    color = if (smsStatus.startsWith("登录失败") || smsStatus.startsWith("发送失败") || smsStatus.startsWith("请输入")) t.error else t.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
            LoginMethod.BROWSER -> BrowserLoginPanel(
                browserStatus = browserStatus,
                browserBusy = browserBusy,
                browserError = browserStatus.startsWith("未找到") || browserStatus.startsWith("浏览器登录失败"),
                browserDefaultStatus = "将打开浏览器进入网易云登录页，登录成功后自动读取登录态",
                onBrowserLogin = ::doBrowserLogin,
                cookieText = cookieText,
                onCookieTextChange = { cookieText = it },
                cookieStatus = cookieStatus,
                cookieBusy = cookieBusy,
                cookieError = cookieStatus.startsWith("登录失败") || cookieStatus.startsWith("请"),
                cookiePlaceholder = "MUSIC_U=xxx; NMTID=yyy; …",
                cookieDefaultStatus = "浏览器不可用时，可粘贴包含 MUSIC_U 的完整 Cookie",
                onCookieLogin = ::doCookieLogin,
            )
        }
    }
}

@Composable
private fun smsFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = cleanTokens().surfaceSoft,
    unfocusedContainerColor = cleanTokens().surfaceSoft,
    focusedIndicatorColor = cleanTokens().primary,
    unfocusedIndicatorColor = cleanTokens().border,
    focusedTextColor = cleanTokens().text,
    unfocusedTextColor = cleanTokens().text,
    cursorColor = cleanTokens().primary,
)
