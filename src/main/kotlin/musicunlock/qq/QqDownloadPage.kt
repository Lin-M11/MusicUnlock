package musicunlock.qq

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.ui.cleanTokens
import musicunlock.ui.FileDialogs
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** QQ 音乐下载页：登录 → 选择歌单 → 下载 MP3（与网易云下载页交互一致）。 */
@Composable
fun QqDownloadPage(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val t = cleanTokens()

    var account by remember { mutableStateOf<QqAccount?>(null) }
    var qrImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var loginStatus by remember { mutableStateOf("") }
    var expired by remember { mutableStateOf(false) }
    var loginMode by remember { mutableStateOf(QqLoginMode.QR) }

    var playlists by remember { mutableStateOf<List<QqPlaylist>>(emptyList()) }
    val selected = remember { mutableStateListOf<Long>() }
    var loadingPlaylists by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }

    var outputDir by remember { mutableStateOf(downloadDefaultOutputDir()) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var doneCount by remember { mutableStateOf(0) }
    var failCount by remember { mutableStateOf(0) }
    val logLines = remember { mutableStateListOf<String>() }

    // 登录成功：写入账号并加载歌单
    val onLoggedIn: (QqAccount) -> Unit = { acc ->
        account = acc
        loginStatus = "登录成功"
        loadQqPlaylists(
            scope = scope,
            setPlaylists = { playlists = it },
            setLoading = { loadingPlaylists = it },
            setError = { playlistError = it },
        )
    }

    // 生成二维码
    val fetchQr: () -> Unit = {
        scope.launch {
            loginStatus = "正在获取二维码…"
            expired = false
            qrImage = null
            val result = withContext(Dispatchers.IO) {
                runCatching { QqMusicApi.qrImage() }
            }
            result.onSuccess { bytes ->
                qrImage = withContext(Dispatchers.Default) {
                    runCatching {
                        ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
                    }.getOrNull()
                }
                if (qrImage == null) {
                    loginStatus = "二维码解析失败，请重试"
                } else {
                    loginStatus = "请使用手机 QQ 扫码登录"
                }
            }.onFailure {
                loginStatus = "获取二维码失败：${it.message}"
            }
        }
    }
    LaunchedEffect(Unit) { fetchQr() }

    // 轮询扫码状态
    LaunchedEffect(account, qrImage) {
        if (account != null || qrImage == null) return@LaunchedEffect
        loginStatus = "等待扫码"
        while (isActive && account == null) {
            val poll = withContext(Dispatchers.IO) {
                runCatching { QqMusicApi.pollQr() }.getOrNull()
            }
            when (poll?.state) {
                QqLoginState.EXPIRED -> {
                    loginStatus = "二维码已失效，请刷新"
                    expired = true
                }
                QqLoginState.SCANNED -> loginStatus = "已扫码，请在手机上确认登录"
                QqLoginState.ERROR -> {
                    loginStatus = poll.message ?: "登录状态异常，请重试"
                    break
                }
                QqLoginState.SUCCESS -> {
                    loginStatus = "登录成功，正在获取账号信息…"
                    val acc = withContext(Dispatchers.IO) {
                        runCatching { QqMusicApi.finishQrLogin() }.getOrNull()
                    }
                    if (acc != null) {
                        onLoggedIn(acc)
                    } else {
                        loginStatus = "登录状态获取失败，请重试或改用 Cookie 登录"
                        expired = true
                        break
                    }
                }
                else -> loginStatus = "等待扫码"
            }
            delay(2000)
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val acc = account
        if (acc == null) {
            QqLoginCard(
                qrImage = qrImage,
                loginStatus = loginStatus,
                expired = expired,
                loginMode = loginMode,
                onLoginModeChange = { loginMode = it },
                onLoggedIn = onLoggedIn,
                onRefresh = fetchQr,
            )
        } else {
            QqAccountBar(
                account = acc,
                onLogout = {
                    scope.launch {
                        withContext(Dispatchers.IO) { QqMusicApi.logout() }
                        account = null
                        qrImage = null
                        selected.clear()
                        playlists = emptyList()
                        logLines.clear()
                        progress = 0f
                        doneCount = 0
                        failCount = 0
                        fetchQr()
                    }
                },
            )
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                QqPlaylistCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    playlists = playlists,
                    selected = selected,
                    loading = loadingPlaylists,
                    error = playlistError,
                    onToggle = { id ->
                        if (!busy) {
                            if (selected.contains(id)) selected.remove(id) else selected.add(id)
                        }
                    },
                    onSelectAll = { if (!busy) { selected.clear(); selected.addAll(playlists.map { it.id }) } },
                    onClear = { if (!busy) selected.clear() },
                    onRefresh = {
                        loadQqPlaylists(
                            scope = scope,
                            setPlaylists = { playlists = it },
                            setLoading = { loadingPlaylists = it },
                            setError = { playlistError = it },
                        )
                    },
                )
                QqDownloadRail(
                    outputDir = outputDir,
                    onOutputDirChange = { outputDir = it },
                    busy = busy,
                    progress = progress,
                    doneCount = doneCount,
                    failCount = failCount,
                    totalSelected = selected.size,
                    totalSongs = selected.sumOf { id -> playlists.firstOrNull { it.id == id }?.trackCount ?: 0 },
                    onDownload = {
                        runQqDownload(
                            scope = scope,
                            setBusy = { busy = it },
                            setProgress = { progress = it },
                            setCounts = { d, f -> doneCount = d; failCount = f },
                            log = logLines,
                            selectedIds = selected.toList(),
                            playlists = playlists,
                            outputDir = outputDir,
                        )
                    },
                    logLines = logLines,
                )
            }
        }
    }
}

// ============================================================
//  登录卡片
// ============================================================

private enum class QqLoginMode { QR, BROWSER }

@Composable
private fun QqLoginCard(
    qrImage: ImageBitmap?,
    loginStatus: String,
    expired: Boolean,
    loginMode: QqLoginMode,
    onLoginModeChange: (QqLoginMode) -> Unit,
    onLoggedIn: (QqAccount) -> Unit,
    onRefresh: () -> Unit,
) {
    val t = cleanTokens()
    val scope = rememberCoroutineScope()
    var cookieText by remember { mutableStateOf("") }
    var cookieStatus by remember { mutableStateOf("") }
    var cookieBusy by remember { mutableStateOf(false) }
    var browserStatus by remember { mutableStateOf("") }
    var browserBusy by remember { mutableStateOf(false) }

    fun doCookieLogin() {
        if (cookieText.isBlank()) {
            cookieStatus = "请粘贴 Cookie"
            return
        }
        scope.launch {
            cookieBusy = true
            cookieStatus = "正在登录…"
            val result = withContext(Dispatchers.IO) {
                runCatching { QqMusicApi.loginWithCookie(cookieText.trim()) }
            }
            result
                .onSuccess { onLoggedIn(it) }
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
                QqBrowserLogin.login(
                    onStatus = { browserStatus = it },
                    onResult = {
                        browserStatus = "登录成功"
                        onLoggedIn(it)
                    },
                    onError = { browserStatus = it },
                )
            }
            browserBusy = false
        }
    }

    fun isBrowserError(status: String): Boolean =
        status.startsWith("未找到") ||
            status.startsWith("无法") ||
            status.startsWith("浏览器登录失败") ||
            status.startsWith("等待登录超时")

    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = t.surfaceSoft,
        unfocusedContainerColor = t.surfaceSoft,
        focusedIndicatorColor = t.primary,
        unfocusedIndicatorColor = t.border,
        focusedTextColor = t.text,
        unfocusedTextColor = t.text,
        cursorColor = t.primary,
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(t.surface)
                .border(1.dp, t.cardBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("登录 QQ 音乐", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            Spacer(Modifier.height(6.dp))
            Text(
                "登录后可读取你的歌单并生成下载任务",
                fontSize = 12.5.sp,
                color = t.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(t.surfaceSoft)
                    .padding(3.dp),
            ) {
                QqLoginModeTab("扫码登录", selected = loginMode == QqLoginMode.QR, modifier = Modifier.weight(1f)) { onLoginModeChange(QqLoginMode.QR) }
                QqLoginModeTab("浏览器登录", selected = loginMode == QqLoginMode.BROWSER, modifier = Modifier.weight(1f)) { onLoginModeChange(QqLoginMode.BROWSER) }
            }
            Spacer(Modifier.height(18.dp))
            if (loginMode == QqLoginMode.QR) {
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(t.surfaceSoft)
                        .border(1.dp, t.border, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (qrImage != null) {
                        Image(
                            bitmap = qrImage!!,
                            contentDescription = "登录二维码",
                            filterQuality = FilterQuality.Medium,
                            modifier = Modifier.size(232.dp),
                        )
                    } else {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = t.textMuted, modifier = Modifier.size(48.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.primarySoft,
                        contentColor = t.primary,
                    ),
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("刷新二维码", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (loginStatus.isBlank()) "使用手机 QQ 扫一扫登录" else loginStatus,
                    fontSize = 12.5.sp,
                    color = if (loginStatus.startsWith("获取二维码失败") || loginStatus.startsWith("登录状态获取失败") || loginStatus.startsWith("二维码已失效")) t.error else t.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "扫码后请在手机上确认登录",
                    fontSize = 12.sp,
                    color = t.textMuted,
                    textAlign = TextAlign.Center,
                )
            } else {
                Button(
                    onClick = { doBrowserLogin() },
                    enabled = !browserBusy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.primary,
                        contentColor = t.onPrimary,
                        disabledContainerColor = t.surfaceSoft,
                        disabledContentColor = t.textMuted,
                    ),
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    if (browserBusy) {
                        Text("请稍候…", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Outlined.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("打开浏览器自动登录", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (browserStatus.isBlank()) "将自动打开本机浏览器进入 QQ 音乐登录页，登录成功后自动读取登录态" else browserStatus,
                    fontSize = 12.5.sp,
                    color = if (isBrowserError(browserStatus)) t.error else t.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = t.rowDivider)
                Spacer(Modifier.height(14.dp))
                Text(
                    "浏览器不可用时，可手动粘贴 Cookie：",
                    fontSize = 12.5.sp,
                    color = t.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = cookieText,
                    onValueChange = { cookieText = it },
                    placeholder = { Text("uin=xxx; qm_keyst=yyy; qqmusic_key=zzz; …", fontSize = 12.sp, color = t.textMuted) },
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { doCookieLogin() },
                    enabled = !cookieBusy,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.primarySoft,
                        contentColor = t.primary,
                        disabledContainerColor = t.surfaceSoft,
                        disabledContentColor = t.textMuted,
                    ),
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                ) {
                    if (cookieBusy) {
                        Text("请稍候…", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Cookie 登录", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (cookieStatus.isBlank()) "登录后 Cookie 仅保存在本次会话内" else cookieStatus,
                    fontSize = 12.5.sp,
                    color = if (cookieStatus.startsWith("登录失败") || cookieStatus.startsWith("请")) t.error else t.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun QqLoginModeTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = cleanTokens()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) t.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) t.onPrimary else t.textSecondary,
        )
    }
}

// ============================================================
//  账号栏 / 歌单卡片 / 右侧下载栏
// ============================================================

@Composable
private fun QqAccountBar(account: QqAccount, onLogout: () -> Unit) {
    val t = cleanTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QqRemoteImage(
            url = account.avatarUrl,
            size = 40.dp,
            corner = 20.dp,
            placeholder = Icons.Outlined.Person,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(account.nickname, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            Text("已登录 QQ 音乐", fontSize = 12.sp, color = t.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        QqSoftActionButton(text = "退出登录", icon = Icons.Outlined.Logout, onClick = onLogout)
    }
}

@Composable
private fun QqPlaylistCard(
    modifier: Modifier,
    playlists: List<QqPlaylist>,
    selected: List<Long>,
    loading: Boolean,
    error: String?,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
) {
    val t = cleanTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("我的歌单", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            Spacer(Modifier.width(10.dp))
            Text("已选 ${selected.size} 个", fontSize = 12.5.sp, color = t.textSecondary)
            Spacer(Modifier.weight(1f))
            QqSoftActionButton(text = "全选", icon = null, onClick = onSelectAll)
            Spacer(Modifier.width(8.dp))
            QqSoftActionButton(text = "清空", icon = null, onClick = onClear)
            Spacer(Modifier.width(8.dp))
            QqSoftActionButton(text = "刷新", icon = Icons.Outlined.Refresh, onClick = onRefresh)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.rowDivider))
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("正在加载歌单…", fontSize = 13.sp, color = t.textSecondary)
            }
            error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error, fontSize = 13.sp, color = t.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    QqSoftActionButton(text = "重新加载", icon = Icons.Outlined.Refresh, onClick = onRefresh)
                }
            }
            playlists.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.LibraryMusic, null, tint = t.textMuted.copy(alpha = 0.55f), modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("没有可下载的歌单\n点击「刷新」重试", fontSize = 13.sp, color = t.textSecondary, textAlign = TextAlign.Center)
                }
            }
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(playlists) { index, playlist ->
                    QqPlaylistRow(
                        playlist = playlist,
                        checked = selected.contains(playlist.id),
                        onToggle = { onToggle(playlist.id) },
                    )
                    if (index < playlists.lastIndex) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(t.rowDivider))
                    }
                }
            }
        }
    }
}

@Composable
private fun QqPlaylistRow(playlist: QqPlaylist, checked: Boolean, onToggle: () -> Unit) {
    val t = cleanTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = t.primary,
                uncheckedColor = t.border,
            ),
        )
        QqRemoteImage(
            url = playlist.coverUrl,
            size = 44.dp,
            corner = 10.dp,
            placeholder = Icons.Outlined.LibraryMusic,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                playlist.name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = t.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (playlist.trackCount > 0) "${playlist.trackCount} 首" else "曲目数未知",
                fontSize = 12.sp,
                color = t.textMuted,
            )
        }
    }
}

@Composable
private fun QqDownloadRail(
    outputDir: String,
    onOutputDirChange: (String) -> Unit,
    busy: Boolean,
    progress: Float,
    doneCount: Int,
    failCount: Int,
    totalSelected: Int,
    totalSongs: Int,
    onDownload: () -> Unit,
    logLines: List<String>,
) {
    val t = cleanTokens()
    Column(
        modifier = Modifier.width(300.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        QqRailLabel("输出目录")
        QqOutputDirField(
            path = outputDir,
            onBrowse = { FileDialogs.pickFolder("选择下载目录")?.let { onOutputDirChange(it.absolutePath) } },
        )

        Button(
            onClick = onDownload,
            enabled = !busy && totalSelected > 0,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.primary,
                contentColor = t.onPrimary,
                disabledContainerColor = t.surfaceSoft,
                disabledContentColor = t.textMuted,
            ),
            modifier = Modifier.fillMaxWidth().height(44.dp),
        ) {
            Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (totalSongs > 0) "下载 MP3（${totalSongs} 首）" else "下载 MP3", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        QqRailLabel(if (!busy) "进度" else "正在下载 MP3…")
        if (totalSongs > 0 || busy) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QqProgressBar(modifier = Modifier.fillMaxWidth().height(10.dp), progress = progress)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (!busy) "成功 $doneCount · 失败 $failCount"
                        else "成功 $doneCount · 失败 $failCount · ${(progress * 100).toInt()}%",
                        fontSize = 12.5.sp,
                        color = t.textSecondary,
                    )
                }
            }
        } else {
            Text("勾选歌单后开始下载", fontSize = 12.5.sp, color = t.textMuted)
        }

        QqRailLabel("记录")
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(t.surfaceSoft)
                .border(1.dp, t.border, RoundedCornerShape(12.dp))
                .padding(10.dp),
        ) {
            if (logLines.isEmpty()) {
                Text("暂无记录", fontSize = 12.sp, color = t.textMuted)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    logLines.forEach { line ->
                        Text(
                            line,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                line.contains("成功") || line.contains("已完成") -> t.success
                                line.contains("失败") || line.contains("跳过") -> t.error
                                else -> t.textSecondary
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QqOutputDirField(path: String, onBrowse: () -> Unit) {
    val t = cleanTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface)
            .border(1.dp, t.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            path,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = t.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(10.dp))
        QqSoftActionButton(text = "浏览…", icon = null, onClick = onBrowse)
    }
}

@Composable
private fun QqRailLabel(text: String) {
    val t = cleanTokens()
    Text(
        text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
        color = t.textSecondary,
    )
}

@Composable
private fun QqProgressBar(modifier: Modifier, progress: Float) {
    val t = cleanTokens()
    Box(modifier = modifier.clip(RoundedCornerShape(5.dp)).background(t.surfaceSoft)) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(5.dp))
                .background(t.primary),
        )
    }
}

@Composable
private fun QqSoftActionButton(text: String, icon: ImageVector?, onClick: () -> Unit) {
    val t = cleanTokens()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(t.surfaceSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = t.textSecondary, modifier = Modifier.size(13.dp))
        }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = t.textSecondary)
    }
}

@Composable
private fun QqRemoteImage(url: String?, size: Dp, corner: Dp, placeholder: ImageVector) {
    val t = cleanTokens()
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                QqMusicApi.downloadBytes(url)?.let { bytes ->
                    ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
                }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(t.surfaceSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.size(size).clip(RoundedCornerShape(corner)),
            )
        } else {
            Icon(placeholder, contentDescription = null, tint = t.textMuted.copy(alpha = 0.6f), modifier = Modifier.size(size * 0.45f))
        }
    }
}

// ============================================================
//  批量下载与工具
// ============================================================

private fun runQqDownload(
    scope: kotlinx.coroutines.CoroutineScope,
    setBusy: (Boolean) -> Unit,
    setProgress: (Float) -> Unit,
    setCounts: (Int, Int) -> Unit,
    log: MutableList<String>,
    selectedIds: List<Long>,
    playlists: List<QqPlaylist>,
    outputDir: String,
) {
    scope.launch {
        if (selectedIds.isEmpty()) return@launch
        setBusy(true)
        setProgress(0f)
        setCounts(0, 0)
        log.add(0, "开始下载 MP3…")

        var done = 0
        var fail = 0
        var processed = 0
        val total = selectedIds.sumOf { id -> playlists.firstOrNull { it.id == id }?.trackCount ?: 0 }.coerceAtLeast(1)

        try {
            for (id in selectedIds) {
                val playlist = playlists.firstOrNull { it.id == id } ?: continue
                val songs = withContext(Dispatchers.IO) {
                    runCatching { QqMusicApi.playlistSongs(playlist) }.getOrElse {
                        log.add(0, "失败：歌单「${playlist.name}」加载歌曲失败 - ${it.message}")
                        emptyList()
                    }
                }
                if (songs.isEmpty()) {
                    log.add(0, "歌单「${playlist.name}」无可用歌曲")
                    continue
                }
                for (song in songs) {
                    val ok = withContext(Dispatchers.IO) {
                        val outcome = QqMp3Downloader.downloadAsMp3(song, File(outputDir))
                        if (outcome.ok) {
                            log.add(0, "已完成：${song.name} → ${outcome.file?.name}")
                        } else {
                            log.add(0, "失败：${song.name} - ${outcome.message}")
                        }
                        outcome.ok
                    }
                    if (ok) done++ else fail++
                    processed++
                    setCounts(done, fail)
                    setProgress(processed.toFloat() / total)
                }
            }
            log.add(0, "下载完成：成功 $done · 失败 $fail")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.add(0, "批量下载中断：${e.message}")
        } finally {
            setBusy(false)
        }
    }
}

private fun loadQqPlaylists(
    scope: kotlinx.coroutines.CoroutineScope,
    setPlaylists: (List<QqPlaylist>) -> Unit,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
) {
    scope.launch {
        setLoading(true)
        setError(null)
        val result = withContext(Dispatchers.IO) {
            runCatching { QqMusicApi.myPlaylists() }
        }
        result.onSuccess {
            setPlaylists(it)
            setLoading(false)
        }.onFailure {
            setPlaylists(emptyList())
            setLoading(false)
            val message = it.message ?: "未知错误"
            setError(if (it is QqAuthExpiredException) "登录已失效，请退出后重新登录" else "加载歌单失败：$message")
        }
    }
}

private fun downloadDefaultOutputDir(): String {
    val home = System.getProperty("user.home")
    return if (!home.isNullOrBlank()) {
        File(home, "Music/MusicUnlock").absolutePath
    } else {
        File("output").absolutePath
    }
}
