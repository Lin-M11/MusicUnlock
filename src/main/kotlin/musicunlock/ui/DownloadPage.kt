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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.ncm.Mp3Downloader
import musicunlock.ncm.NeteaseAccount
import musicunlock.ncm.NeteaseApi
import musicunlock.ncm.NeteasePlaylist
import musicunlock.ncm.QrLoginState
import musicunlock.ncm.TaskboardClient
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.coroutines.cancellation.CancellationException

/** 当前进行的批量操作。 */
private enum class BatchAction { NONE, DOWNLOAD, SUBMIT }

/** 网易云下载页：扫码登录 → 选择歌单 → 下载 MP3 / 提交下载任务到任务面板。 */
@Composable
fun DownloadPage(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val t = cleanTokens()

    var account by remember { mutableStateOf<NeteaseAccount?>(null) }
    var qrKey by remember { mutableStateOf<String?>(null) }
    var qrImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var loginStatus by remember { mutableStateOf("") }
    var expired by remember { mutableStateOf(false) }

    var playlists by remember { mutableStateOf<List<NeteasePlaylist>>(emptyList()) }
    val selected = remember { mutableStateListOf<Long>() }
    var loadingPlaylists by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }

    var outputDir by remember { mutableStateOf(downloadDefaultOutputDir()) }
    var busyAction by remember { mutableStateOf(BatchAction.NONE) }
    var progress by remember { mutableStateOf(0f) }
    var doneCount by remember { mutableStateOf(0) }
    var failCount by remember { mutableStateOf(0) }
    val logLines = remember { mutableStateListOf<String>() }

    // 生成二维码
    LaunchedEffect(qrKey) {
        val key = qrKey ?: return@LaunchedEffect
        expired = false
        qrImage = withContext(Dispatchers.Default) {
            qrBitmap("https://music.163.com/login?codekey=$key", 232, t.text.toArgb(), t.surface.toArgb())
                ?.toComposeImageBitmap()
        }
    }

    // 轮询扫码状态
    LaunchedEffect(qrKey) {
        val key = qrKey ?: return@LaunchedEffect
        loginStatus = "等待扫码"
        while (isActive && account == null) {
            val result = withContext(Dispatchers.IO) {
                runCatching { NeteaseApi.qrCheck(key) }.getOrNull()
            }
            when (result?.state) {
                QrLoginState.EXPIRED -> {
                    loginStatus = "二维码已失效，请刷新"
                    expired = true
                }
                QrLoginState.SCANNED -> loginStatus = "已扫码，请在手机上确认登录"
                QrLoginState.SUCCESS -> {
                    val acc = withContext(Dispatchers.IO) {
                        runCatching { NeteaseApi.account() }.getOrNull()
                    }
                    if (acc != null) {
                        account = acc
                        loginStatus = "登录成功"
                        loadPlaylists(
                            scope = scope,
                            setPlaylists = { playlists = it },
                            setLoading = { loadingPlaylists = it },
                            setError = { playlistError = it },
                        )
                    } else {
                        loginStatus = "登录状态获取失败，请重试"
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
            LoginCard(
                qrImage = qrImage,
                loginStatus = loginStatus,
                expired = expired,
                onRefresh = {
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
                },
            )
        } else {
            AccountBar(
                account = acc,
                onLogout = {
                    scope.launch {
                        withContext(Dispatchers.IO) { NeteaseApi.logout() }
                        account = null
                        qrKey = null
                        qrImage = null
                        selected.clear()
                        playlists = emptyList()
                        logLines.clear()
                        progress = 0f
                        doneCount = 0
                        failCount = 0
                    }
                },
            )
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PlaylistCard(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    playlists = playlists,
                    selected = selected,
                    loading = loadingPlaylists,
                    error = playlistError,
                    onToggle = { id ->
                        if (busyAction == BatchAction.NONE) {
                            if (selected.contains(id)) selected.remove(id) else selected.add(id)
                        }
                    },
                    onSelectAll = { if (busyAction == BatchAction.NONE) { selected.clear(); selected.addAll(playlists.map { it.id }) } },
                    onClear = { if (busyAction == BatchAction.NONE) selected.clear() },
                    onRefresh = {
                        loadPlaylists(
                            scope = scope,
                            setPlaylists = { playlists = it },
                            setLoading = { loadingPlaylists = it },
                            setError = { playlistError = it },
                        )
                    },
                )
                DownloadRail(
                    outputDir = outputDir,
                    onOutputDirChange = { outputDir = it },
                    busyAction = busyAction,
                    progress = progress,
                    doneCount = doneCount,
                    failCount = failCount,
                    totalSelected = selected.size,
                    totalSongs = selected.sumOf { id -> playlists.firstOrNull { it.id == id }?.trackCount ?: 0 },
                    onDownload = {
                        runBatch(
                            scope = scope,
                            action = BatchAction.DOWNLOAD,
                            setBusy = { busyAction = it },
                            setProgress = { progress = it },
                            setCounts = { d, f -> doneCount = d; failCount = f },
                            log = logLines,
                            selectedIds = selected.toList(),
                            playlists = playlists,
                            outputDir = outputDir,
                            threadId = null,
                        )
                    },
                    onSubmit = {
                        runBatch(
                            scope = scope,
                            action = BatchAction.SUBMIT,
                            setBusy = { busyAction = it },
                            setProgress = { progress = it },
                            setCounts = { d, f -> doneCount = d; failCount = f },
                            log = logLines,
                            selectedIds = selected.toList(),
                            playlists = playlists,
                            outputDir = outputDir,
                            threadId = TaskboardClient.sessionThreadId(),
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

@Composable
private fun LoginCard(
    qrImage: ImageBitmap?,
    loginStatus: String,
    expired: Boolean,
    onRefresh: () -> Unit,
) {
    val t = cleanTokens()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(t.surface)
                .border(1.dp, t.cardBorder, RoundedCornerShape(16.dp))
                .padding(horizontal = 28.dp, vertical = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "扫码登录网易云",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "登录后可读取你的歌单并生成下载任务",
                fontSize = 12.5.sp,
                color = t.textSecondary,
            )
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .size(248.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(t.surfaceSoft)
                    .border(1.dp, t.border, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (qrImage != null) {
                    Image(
                        bitmap = qrImage,
                        contentDescription = "登录二维码",
                        modifier = Modifier.size(232.dp),
                    )
                } else {
                    Icon(
                        Icons.Outlined.MusicNote,
                        contentDescription = null,
                        tint = t.textMuted.copy(alpha = 0.5f),
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                if (loginStatus.isBlank()) "点击下方按钮获取二维码" else loginStatus,
                fontSize = 13.sp,
                fontWeight = if (expired) FontWeight.Medium else FontWeight.Normal,
                color = if (expired) t.error else t.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onRefresh,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = t.primary,
                    contentColor = t.onPrimary,
                ),
                modifier = Modifier.height(44.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (qrImage == null) "获取二维码" else "刷新二维码", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "使用网易云 App「扫一扫」完成登录，登录态仅保存在本次会话内",
                fontSize = 11.5.sp,
                color = t.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ============================================================
//  账号栏
// ============================================================

@Composable
private fun AccountBar(account: NeteaseAccount, onLogout: () -> Unit) {
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
        RemoteImage(
            url = account.avatarUrl,
            size = 40.dp,
            corner = 20.dp,
            placeholder = Icons.Outlined.Person,
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(account.nickname, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            Text("已登录网易云", fontSize = 12.sp, color = t.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        SoftActionButton(text = "退出登录", icon = Icons.Outlined.Logout, onClick = onLogout)
    }
}

// ============================================================
//  歌单卡片
// ============================================================

@Composable
private fun PlaylistCard(
    modifier: Modifier,
    playlists: List<NeteasePlaylist>,
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
            SoftActionButton(text = "全选", icon = null, onClick = onSelectAll)
            Spacer(Modifier.width(8.dp))
            SoftActionButton(text = "清空", icon = null, onClick = onClear)
            Spacer(Modifier.width(8.dp))
            SoftActionButton(text = "刷新", icon = Icons.Outlined.Refresh, onClick = onRefresh)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.rowDivider))
        when {
            loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("正在加载歌单…", fontSize = 13.sp, color = t.textSecondary)
            }
            error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(error, fontSize = 13.sp, color = t.error, textAlign = TextAlign.Center)
            }
            playlists.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.LibraryMusic, null, tint = t.textMuted.copy(alpha = 0.55f), modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("没有可下载的歌单\n点击右上角「刷新」重试", fontSize = 13.sp, color = t.textSecondary, textAlign = TextAlign.Center)
                }
            }
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(playlists) { index, playlist ->
                    PlaylistRow(
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
private fun PlaylistRow(playlist: NeteasePlaylist, checked: Boolean, onToggle: () -> Unit) {
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
        RemoteImage(
            url = playlist.coverImgUrl,
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
            Text("${playlist.trackCount} 首", fontSize = 12.sp, color = t.textMuted)
        }
    }
}

// ============================================================
//  右侧栏：输出目录 / 下载 / 提交任务 / 进度 / 日志
// ============================================================

@Composable
private fun DownloadRail(
    outputDir: String,
    onOutputDirChange: (String) -> Unit,
    busyAction: BatchAction,
    progress: Float,
    doneCount: Int,
    failCount: Int,
    totalSelected: Int,
    totalSongs: Int,
    onDownload: () -> Unit,
    onSubmit: () -> Unit,
    logLines: List<String>,
) {
    val t = cleanTokens()
    Column(
        modifier = Modifier.width(300.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RailLabel("输出目录")
        OutputDirField(
            path = outputDir,
            onBrowse = { FileDialogs.pickFolder("选择下载目录")?.let { onOutputDirChange(it.absolutePath) } },
        )

        Button(
            onClick = onDownload,
            enabled = busyAction == BatchAction.NONE && totalSelected > 0,
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
            Text("下载 MP3（${totalSongs} 首）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onSubmit,
            enabled = busyAction == BatchAction.NONE && totalSelected > 0,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.surface,
                contentColor = t.text,
                disabledContainerColor = t.surfaceSoft,
                disabledContentColor = t.textMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .border(1.dp, if (busyAction == BatchAction.NONE && totalSelected > 0) t.border else t.surfaceSoft, RoundedCornerShape(12.dp)),
        ) {
            Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("生成下载任务并提交", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }

        RailLabel(if (busyAction == BatchAction.NONE) "进度" else if (busyAction == BatchAction.DOWNLOAD) "正在下载 MP3…" else "正在提交任务…")
        if (totalSongs > 0 || busyAction != BatchAction.NONE) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressBar(modifier = Modifier.fillMaxWidth().height(10.dp), progress = progress)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (busyAction == BatchAction.NONE) "成功 $doneCount · 失败 $failCount"
                        else "成功 $doneCount · 失败 $failCount · ${(progress * 100).toInt()}%",
                        fontSize = 12.5.sp,
                        color = t.textSecondary,
                    )
                }
            }
        } else {
            Text("勾选歌单后开始下载或提交", fontSize = 12.5.sp, color = t.textMuted)
        }

        RailLabel("记录")
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
                Text(
                    "暂无记录",
                    fontSize = 12.sp,
                    color = t.textMuted,
                )
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
                            color = if (line.contains("成功") || line.contains("已完成")) t.success else if (line.contains("失败")) t.error else t.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutputDirField(path: String, onBrowse: () -> Unit) {
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
        SoftActionButton(text = "浏览…", icon = null, onClick = onBrowse)
    }
}

@Composable
private fun RailLabel(text: String) {
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
private fun ProgressBar(modifier: Modifier, progress: Float) {
    val t = cleanTokens()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(t.surfaceSoft),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(5.dp))
                .background(Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color(0xFFFF8A3D), t.primary))),
        )
    }
}

@Composable
private fun SoftActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector?, onClick: () -> Unit) {
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

// ============================================================
//  远程图片
// ============================================================

@Composable
private fun RemoteImage(
    url: String?,
    size: Dp,
    corner: Dp,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val t = cleanTokens()
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                NeteaseApi.downloadBytes(url)?.let { bytes ->
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
//  批量执行
// ============================================================

private fun runBatch(
    scope: kotlinx.coroutines.CoroutineScope,
    action: BatchAction,
    setBusy: (BatchAction) -> Unit,
    setProgress: (Float) -> Unit,
    setCounts: (Int, Int) -> Unit,
    log: MutableList<String>,
    selectedIds: List<Long>,
    playlists: List<NeteasePlaylist>,
    outputDir: String,
    threadId: String?,
) {
    scope.launch {
        if (selectedIds.isEmpty()) return@launch
        setBusy(action)
        setProgress(0f)
        setCounts(0, 0)
        log.add(0, if (action == BatchAction.DOWNLOAD) "开始下载 MP3…" else "开始提交下载任务…")

        var done = 0
        var fail = 0
        var processed = 0
        val total = selectedIds.sumOf { id -> playlists.firstOrNull { it.id == id }?.trackCount ?: 0 }.coerceAtLeast(1)
        val thread = threadId

        try {
            for (id in selectedIds) {
                val playlist = playlists.firstOrNull { it.id == id }
                val playlistName = playlist?.name ?: "未知歌单"
                val trackIds = withContext(Dispatchers.IO) {
                    runCatching { NeteaseApi.playlistTrackIds(id) }.getOrElse { emptyList() }
                }
                val songs = mutableListOf<musicunlock.ncm.NeteaseSong>()
                trackIds.chunked(100).forEach { batch ->
                    val details = withContext(Dispatchers.IO) {
                        runCatching { NeteaseApi.songDetails(batch) }.getOrElse { emptyList() }
                    }
                    songs.addAll(details)
                }
                if (songs.isEmpty()) {
                    log.add(0, "歌单「$playlistName」无可用歌曲")
                    continue
                }
                for (song in songs) {
                    val ok = withContext(Dispatchers.IO) {
                        if (action == BatchAction.DOWNLOAD) {
                            val outcome = Mp3Downloader.downloadAsMp3(song, File(outputDir))
                            if (outcome.ok) {
                                log.add(0, "已完成：${song.name} → ${outcome.file?.name}")
                            } else {
                                log.add(0, "失败：${song.name} - ${outcome.message}")
                            }
                            outcome.ok
                        } else {
                            val result = TaskboardClient.submitDownloadTask(
                                songName = song.name,
                                artist = song.artistText,
                                sourcePlaylist = playlistName,
                                threadId = thread ?: "",
                            )
                            if (result.ok) {
                                log.add(0, "成功：${song.name} → ${result.identifier}")
                            } else {
                                log.add(0, "失败：${song.name} - ${result.message}")
                            }
                            result.ok
                        }
                    }
                    if (ok) done++ else fail++
                    processed++
                    setCounts(done, fail)
                    setProgress(processed.toFloat() / total)
                }
            }
            log.add(0, if (action == BatchAction.DOWNLOAD) "下载完成：成功 $done · 失败 $fail" else "提交完成：成功 $done · 失败 $fail")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.add(0, "批量操作中断：${e.message}")
        } finally {
            setBusy(BatchAction.NONE)
        }
    }
}

// ============================================================
//  工具
// ============================================================

/** 下载页默认输出目录：用户主目录下的 Music/MusicUnlock。 */
private fun downloadDefaultOutputDir(): String {
    val home = System.getProperty("user.home")
    return if (!home.isNullOrBlank()) {
        File(home, "Music/MusicUnlock").absolutePath
    } else {
        File("output").absolutePath
    }
}

private fun qrBitmap(content: String, size: Int, fg: Int, bg: Int): BufferedImage? {
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (matrix[x, y]) fg else bg)
            }
        }
        image
    } catch (e: Exception) {
        null
    }
}

private fun loadPlaylists(
    scope: kotlinx.coroutines.CoroutineScope,
    setPlaylists: (List<NeteasePlaylist>) -> Unit,
    setLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
) {
    scope.launch {
        setLoading(true)
        setError(null)
        val result = withContext(Dispatchers.IO) {
            runCatching { NeteaseApi.playlists() }
        }
        result.onSuccess {
            setPlaylists(it)
            setLoading(false)
        }.onFailure {
            setPlaylists(emptyList())
            setLoading(false)
            setError("加载歌单失败：${it.message}")
        }
    }
}
