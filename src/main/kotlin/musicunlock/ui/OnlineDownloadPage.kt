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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Logout
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.online.MusicAccount
import musicunlock.online.MusicPlaylist
import musicunlock.online.MusicSong
import musicunlock.online.OnlineDownloadOutcome
import musicunlock.online.DownloadTaskManager
import musicunlock.online.OnlineMusicProvider
import musicunlock.online.SubmitOutcome
import musicunlock.online.toOnlineDownloadPreferences
import musicunlock.settings.AppSettings
import musicunlock.settings.PlaylistSubscription
import musicunlock.settings.SettingsUpdate
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

private enum class OnlineBatchAction { NONE, DOWNLOAD, SUBMIT }

/**
 * 所有在线音乐平台共用的下载页：账号恢复、歌单选择、批量下载、提交、进度和日志。
 * 平台页面只提供登录界面和统一的 [OnlineMusicProvider]。
 */
@Composable
internal fun OnlineDownloadPage(
    provider: OnlineMusicProvider,
    savedCookie: String?,
    savedAccount: MusicAccount?,
    outputDir: String,
    onOutputDirChange: (String) -> Unit,
    onSessionChanged: (String?, MusicAccount?) -> Unit,
    downloadManager: DownloadTaskManager,
    settings: AppSettings,
    onUpdateSettings: SettingsUpdate,
    modifier: Modifier = Modifier,
    submitSong: (suspend (MusicSong, String) -> SubmitOutcome)? = null,
    loginContent: @Composable ((MusicAccount) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var account by remember(provider) { mutableStateOf(savedAccount) }
    var restoreFinished by remember(provider) { mutableStateOf(savedCookie.isNullOrBlank()) }
    var playlists by remember(provider) { mutableStateOf<List<MusicPlaylist>>(emptyList()) }
    val selected = remember(provider) { mutableStateListOf<String>() }
    var loadingPlaylists by remember(provider) { mutableStateOf(false) }
    var playlistError by remember(provider) { mutableStateOf<String?>(null) }
    var busyAction by remember(provider) { mutableStateOf(OnlineBatchAction.NONE) }
    var progress by remember(provider) { mutableStateOf(0f) }
    var doneCount by remember(provider) { mutableStateOf(0) }
    var failCount by remember(provider) { mutableStateOf(0) }
    val logLines = remember(provider) { mutableStateListOf<String>() }

    LaunchedEffect(provider) {
        val cookie = savedCookie?.trim().orEmpty()
        if (cookie.isNotEmpty()) {
            val result = withContext(Dispatchers.IO) {
                runCatching { provider.restoreSession(cookie) }
            }
            result.onSuccess { restored ->
                account = restored
                onSessionChanged(provider.exportSessionCookie(), restored)
            }.onFailure {
                account = null
                playlistError = "登录状态恢复失败，可重新登录"
            }
        }
        restoreFinished = true
    }

    LaunchedEffect(account) {
        val current = account ?: return@LaunchedEffect
        loadingPlaylists = true
        playlistError = null
        selected.clear()
        logLines.clear()
        val result = withContext(Dispatchers.IO) {
            runCatching { provider.playlists() }
        }
        result.onSuccess {
            playlists = it
            logLines.add(0, "已加载 ${it.size} 个歌单（${current.nickname}）")
        }.onFailure {
            playlists = emptyList()
            playlistError = "加载歌单失败：${it.message ?: "未知错误"}"
        }
        loadingPlaylists = false
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val current = account
        when {
            !restoreFinished -> RestoreSessionCard()
            current == null -> loginContent { loggedIn ->
                account = loggedIn
                selected.clear()
                playlists = emptyList()
                logLines.clear()
                onSessionChanged(provider.exportSessionCookie(), loggedIn)
            }
            else -> {
                OnlineAccountBar(
                    account = current,
                    provider = provider,
                    onLogout = {
                        scope.launch {
                            withContext(Dispatchers.IO) { runCatching { provider.logout() } }
                            account = null
                            playlists = emptyList()
                            selected.clear()
                            logLines.clear()
                            progress = 0f
                            doneCount = 0
                            failCount = 0
                            onSessionChanged(null, null)
                        }
                    },
                )
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OnlinePlaylistCard(
                        provider = provider,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        playlists = playlists,
                        selected = selected,
                        loading = loadingPlaylists,
                        error = playlistError,
                        onToggle = { id ->
                            if (busyAction == OnlineBatchAction.NONE) {
                                if (selected.contains(id)) selected.remove(id) else selected.add(id)
                            }
                        },
                        onSelectAll = {
                            if (busyAction == OnlineBatchAction.NONE) {
                                selected.clear()
                                selected.addAll(playlists.map { it.id })
                            }
                        },
                        onClear = { if (busyAction == OnlineBatchAction.NONE) selected.clear() },
                        onRefresh = {
                            if (busyAction == OnlineBatchAction.NONE) {
                                scope.launch { reloadPlaylists(provider, selected, logLines) { list, loading, error ->
                                    playlists = list
                                    loadingPlaylists = loading
                                    playlistError = error
                                } }
                            }
                        },
                    )
                    OnlineDownloadRail(
                        outputDir = outputDir,
                        onOutputDirChange = onOutputDirChange,
                        busyAction = busyAction,
                        progress = progress,
                        doneCount = doneCount,
                        failCount = failCount,
                        totalSelected = selected.size,
                        totalSongs = selected.sumOf { id ->
                            playlists.firstOrNull { it.id == id }?.trackCount ?: 0
                        },
                        canSubmit = submitSong != null,
                        subscribedCount = selected.count { id ->
                            settings.subscriptions.any { it.id == "${provider.platform.id}:$id" }
                        },
                        onSubscribe = {
                            val additions = selected.mapNotNull { id ->
                                playlists.firstOrNull { it.id == id }?.let { playlist ->
                                    PlaylistSubscription(
                                        id = "${provider.platform.id}:${playlist.id}",
                                        platform = provider.platform.id,
                                        playlistId = playlist.id,
                                        playlistName = playlist.name,
                                        outputDir = outputDir,
                                        quality = settings.qualityStrategy,
                                        outputTemplate = settings.outputTemplate,
                                        metadata = playlist.metadata,
                                    )
                                }
                            }
                            if (additions.isNotEmpty()) {
                                onUpdateSettings { current ->
                                    val ids = additions.mapTo(hashSetOf()) { it.id }
                                    current.copy(
                                        subscriptions = current.subscriptions.filterNot { it.id in ids } + additions,
                                    )
                                }
                                logLines.add(0, "已开启追更：${additions.joinToString("、") { it.playlistName }}")
                            }
                        },
                        onDownload = {
                            runOnlineBatch(
                                scope = scope,
                                provider = provider,
                                action = OnlineBatchAction.DOWNLOAD,
                                setAction = { busyAction = it },
                                setProgress = { progress = it },
                                setCounts = { done, fail -> doneCount = done; failCount = fail },
                                log = logLines,
                                selectedIds = selected.toList(),
                                playlists = playlists,
                                outputDir = outputDir,
                                downloadManager = downloadManager,
                                preferences = settings.toOnlineDownloadPreferences(),
                                submitSong = null,
                            )
                        },
                        onSubmit = {
                            submitSong?.let { submitter ->
                                runOnlineBatch(
                                    scope = scope,
                                    provider = provider,
                                    action = OnlineBatchAction.SUBMIT,
                                    setAction = { busyAction = it },
                                    setProgress = { progress = it },
                                    setCounts = { done, fail -> doneCount = done; failCount = fail },
                                    log = logLines,
                                    selectedIds = selected.toList(),
                                    playlists = playlists,
                                    outputDir = outputDir,
                                    downloadManager = downloadManager,
                                    preferences = settings.toOnlineDownloadPreferences(),
                                    submitSong = submitter,
                                )
                            }
                        },
                        logLines = logLines,
                    )
                }
            }
        }
    }
}

@Composable
private fun RestoreSessionCard() {
    val t = cleanTokens()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("正在恢复登录状态…", fontSize = 13.sp, color = t.textSecondary)
    }
}

@Composable
private fun OnlineAccountBar(
    account: MusicAccount,
    provider: OnlineMusicProvider,
    onLogout: () -> Unit,
) {
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
        OnlineRemoteImage(
            provider = provider,
            url = account.avatarUrl,
            size = 40.dp,
            corner = 20.dp,
            placeholder = Icons.Outlined.Person,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(account.nickname, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            Text("已登录 · ${account.userId}", fontSize = 12.sp, color = t.textSecondary)
        }
        Button(
            onClick = onLogout,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.surfaceSoft,
                contentColor = t.text,
            ),
            modifier = Modifier.height(36.dp),
        ) {
            Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text("退出登录", fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun OnlinePlaylistCard(
    provider: OnlineMusicProvider,
    playlists: List<MusicPlaylist>,
    selected: List<String>,
    loading: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = cleanTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(14.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.LibraryMusic, contentDescription = null, tint = t.textSecondary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(8.dp))
            Text("我的歌单", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            if (playlists.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text("${playlists.size} 个", fontSize = 12.sp, color = t.textMuted)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "全选",
                modifier = Modifier.clickable(enabled = playlists.isNotEmpty(), onClick = onSelectAll).padding(6.dp),
                fontSize = 12.5.sp,
                color = if (playlists.isEmpty()) t.textMuted else t.primary,
            )
            Text(
                "清空",
                modifier = Modifier.clickable(enabled = selected.isNotEmpty(), onClick = onClear).padding(6.dp),
                fontSize = 12.5.sp,
                color = if (selected.isEmpty()) t.textMuted else t.textSecondary,
            )
            Icon(
                Icons.Outlined.Refresh,
                contentDescription = "刷新",
                tint = t.textSecondary,
                modifier = Modifier.size(18.dp).clickable(enabled = !loading, onClick = onRefresh),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(t.rowDivider))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在加载歌单…", fontSize = 13.sp, color = t.textSecondary)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(error, fontSize = 13.sp, color = t.error, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp))
            }
            playlists.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有可下载的歌单\n点击右上角「刷新」重试", fontSize = 13.sp, color = t.textSecondary, textAlign = TextAlign.Center)
            }
            else -> LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(playlists) { index, playlist ->
                    OnlinePlaylistRow(
                        provider = provider,
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
private fun OnlinePlaylistRow(
    provider: OnlineMusicProvider,
    playlist: MusicPlaylist,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val t = cleanTokens()
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = t.primary, uncheckedColor = t.border),
        )
        OnlineRemoteImage(
            provider = provider,
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
private fun OnlineDownloadRail(
    outputDir: String,
    onOutputDirChange: (String) -> Unit,
    busyAction: OnlineBatchAction,
    progress: Float,
    doneCount: Int,
    failCount: Int,
    totalSelected: Int,
    totalSongs: Int,
    canSubmit: Boolean,
    subscribedCount: Int,
    onSubscribe: () -> Unit,
    onDownload: () -> Unit,
    onSubmit: () -> Unit,
    logLines: List<String>,
) {
    val t = cleanTokens()
    Column(modifier = Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OnlineRailLabel("输出目录")
        OnlineOutputDirField(
            path = outputDir,
            onBrowse = { FileDialogs.pickFolder("选择下载目录")?.let { onOutputDirChange(it.absolutePath) } },
        )

        Button(
            onClick = onDownload,
            enabled = busyAction == OnlineBatchAction.NONE && totalSelected > 0,
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
            Text(if (totalSongs > 0) "加入下载队列（${totalSongs} 首）" else "加入下载队列", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onSubscribe,
            enabled = busyAction == OnlineBatchAction.NONE && totalSelected > 0,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.surface,
                contentColor = t.text,
                disabledContainerColor = t.surfaceSoft,
                disabledContentColor = t.textMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .border(
                    1.dp,
                    if (busyAction == OnlineBatchAction.NONE && totalSelected > 0) t.border else t.surfaceSoft,
                    RoundedCornerShape(12.dp),
                ),
        ) {
            Text(
                if (subscribedCount > 0) "已追更 $subscribedCount / $totalSelected 个歌单" else "追更选中歌单",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (canSubmit) {
            Button(
                onClick = onSubmit,
                enabled = busyAction == OnlineBatchAction.NONE && totalSelected > 0,
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
                    .border(
                        1.dp,
                        if (busyAction == OnlineBatchAction.NONE && totalSelected > 0) t.border else t.surfaceSoft,
                        RoundedCornerShape(12.dp),
                    ),
            ) {
                Icon(Icons.Outlined.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("生成下载任务并提交", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        OnlineRailLabel(
            when (busyAction) {
                OnlineBatchAction.NONE -> "进度"
                OnlineBatchAction.DOWNLOAD -> "正在加入下载队列…"
                OnlineBatchAction.SUBMIT -> "正在提交任务…"
            },
        )
        if (totalSongs > 0 || busyAction != OnlineBatchAction.NONE) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OnlineProgressBar(modifier = Modifier.fillMaxWidth().height(10.dp), progress = progress)
                Text(
                    if (busyAction == OnlineBatchAction.NONE) "成功 $doneCount · 失败 $failCount"
                    else "成功 $doneCount · 失败 $failCount · ${(progress * 100).toInt()}%",
                    fontSize = 12.5.sp,
                    color = t.textSecondary,
                )
            }
        } else {
            Text(if (canSubmit) "勾选歌单后开始下载或提交" else "勾选歌单后开始下载", fontSize = 12.5.sp, color = t.textMuted)
        }

        OnlineRailLabel("记录")
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
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    logLines.forEach { line ->
                        Text(
                            line,
                            fontSize = 11.5.sp,
                            lineHeight = 17.sp,
                            color = if (line.startsWith("失败")) t.error else t.textSecondary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlineRailLabel(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cleanTokens().textSecondary)
}

@Composable
private fun OnlineOutputDirField(path: String, onBrowse: () -> Unit) {
    val t = cleanTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surfaceSoft)
            .border(1.dp, t.border, RoundedCornerShape(12.dp))
            .clickable(onClick = onBrowse)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(path, fontSize = 12.5.sp, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("选择", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.primary)
    }
}

@Composable
private fun OnlineProgressBar(modifier: Modifier = Modifier, progress: Float) {
    val t = cleanTokens()
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(t.surfaceSoft)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .background(Brush.horizontalGradient(listOf(androidx.compose.ui.graphics.Color(0xFFFF8A3D), t.primary))),
        )
    }
}

@Composable
private fun OnlineRemoteImage(
    provider: OnlineMusicProvider?,
    url: String?,
    size: Dp,
    corner: Dp,
    placeholder: androidx.compose.ui.graphics.vector.ImageVector,
) {
    val t = cleanTokens()
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = if (url.isNullOrBlank() || provider == null) null else withContext(Dispatchers.IO) {
            runCatching {
                provider.bytes(url)?.let { ImageIO.read(ByteArrayInputStream(it))?.toComposeImageBitmap() }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(corner)).background(t.surfaceSoft),
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

private fun runOnlineBatch(
    scope: kotlinx.coroutines.CoroutineScope,
    provider: OnlineMusicProvider,
    action: OnlineBatchAction,
    setAction: (OnlineBatchAction) -> Unit,
    setProgress: (Float) -> Unit,
    setCounts: (Int, Int) -> Unit,
    log: MutableList<String>,
    selectedIds: List<String>,
    playlists: List<MusicPlaylist>,
    outputDir: String,
    downloadManager: DownloadTaskManager,
    preferences: musicunlock.online.DownloadPreferences,
    submitSong: (suspend (MusicSong, String) -> SubmitOutcome)?,
) {
    scope.launch {
        if (selectedIds.isEmpty()) return@launch
        setAction(action)
        setProgress(0f)
        setCounts(0, 0)
        log.add(0, if (action == OnlineBatchAction.DOWNLOAD) "正在加入下载队列…" else "开始生成下载任务…")

        var done = 0
        var fail = 0
        var processed = 0
        val expected = selectedIds.sumOf { id -> playlists.firstOrNull { it.id == id }?.trackCount ?: 0 }.coerceAtLeast(1)

        try {
            for (id in selectedIds) {
                val playlist = playlists.firstOrNull { it.id == id } ?: continue
                val songs = withContext(Dispatchers.IO) {
                    runCatching { provider.songs(playlist) }.getOrElse {
                        log.add(0, "失败：歌单「${playlist.name}」加载歌曲失败 - ${it.message}")
                        emptyList()
                    }
                }
                if (songs.isEmpty()) {
                    log.add(0, "歌单「${playlist.name}」无可用歌曲")
                    continue
                }
                for (song in songs) {
                    val outcome = withContext(Dispatchers.IO) {
                        when (action) {
                            OnlineBatchAction.DOWNLOAD -> {
                                downloadManager.enqueue(
                                    provider = provider,
                                    song = song,
                                    outputDir = File(outputDir),
                                    preferences = preferences,
                                    playlistName = playlist.name,
                                )
                                log.add(0, "已加入队列：${song.name}")
                                true
                            }
                            OnlineBatchAction.SUBMIT -> {
                                val result = submitSong?.invoke(song, playlist.name)
                                    ?: SubmitOutcome(false, "当前平台不支持任务提交")
                                log.add(0, if (result.ok) "成功：${song.name} → ${result.message}" else "失败：${song.name} - ${result.message}")
                                result.ok
                            }
                            OnlineBatchAction.NONE -> false
                        }
                    }
                    if (outcome) done++ else fail++
                    processed++
                    setCounts(done, fail)
                    setProgress((processed.toFloat() / expected).coerceAtMost(1f))
                }
            }
            log.add(
                0,
                if (action == OnlineBatchAction.DOWNLOAD) "已加入下载队列：$done 首 · 失败 $fail"
                else "提交完成：成功 $done · 失败 $fail",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.add(0, "批量操作中断：${e.message}")
        } finally {
            setAction(OnlineBatchAction.NONE)
        }
    }
}

private fun logDownload(outcome: OnlineDownloadOutcome, song: MusicSong, log: MutableList<String>) {
    if (outcome.ok) {
        log.add(0, "已完成：${song.name} → ${outcome.file?.name}")
    } else {
        log.add(0, "失败：${song.name} - ${outcome.message}")
    }
}

private suspend fun reloadPlaylists(
    provider: OnlineMusicProvider,
    selected: MutableList<String>,
    log: MutableList<String>,
    update: (List<MusicPlaylist>, Boolean, String?) -> Unit,
) {
    update(emptyList(), true, null)
    val result = withContext(Dispatchers.IO) {
        runCatching { provider.playlists() }
    }
    result.onSuccess {
        selected.clear()
        log.add(0, "已刷新歌单：${it.size} 个")
        update(it, false, null)
    }.onFailure {
        update(emptyList(), false, "加载歌单失败：${it.message ?: "未知错误"}")
    }
}
