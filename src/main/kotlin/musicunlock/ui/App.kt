/*
 * MusicUnlock — GUI 设计契约 (Impeccable, seed 83a668d5, 纯净白 · 精密工具)
 *
 * THESIS: 一把覆盖四家音乐平台加密格式的精密转换工具;拒绝"播放器外壳+夸张渐变"的
 * 工具类默认,也拒绝把整屏做成营销页。
 * OWN-WORLD: 近白底 + 白色卡片 + 发丝线分隔 + 品牌橙单点强调;格式按平台家族着色
 * (NCM 橙 / QMC 靛蓝 / KGM 青绿 / KWM 紫)的徽章 + 语义状态胶囊;深色为中性炭灰,
 * 非反色。
 * STORY: 用户把加密音乐拖进来,立刻看清每个文件的格式、状态与去向,一次点击完成转换,
 * 元数据与封面原样保留;无需理解任何格式细节。
 * FIRST VIEWPORT: 左侧 228dp 工作区导航(品牌 / 本地工具 / 音乐服务 / 队列状态 / 工具)
 * -> 主内容区标题与摘要 -> 虚线拖拽区 + 转换队列卡片 -> 右侧 300dp 控制栏
 * (输出目录 / 去重 / 进度 / 打开) -> 底部状态与主转换按钮。
 * FORM: 通用浅色·精密工具;方向轮次见 .impeccable/mocks/decision/general-clean.png。
 * FINISH: unreviewed and undocumented is unfinished; this build ends with the finish
 * review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
 */
package musicunlock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.AppLinks
import musicunlock.BuildInfo
import musicunlock.core.Formats
import musicunlock.kugou.KugouApi
import musicunlock.kugou.KugouDownloadPage
import musicunlock.kuwo.KuwoApi
import musicunlock.kuwo.KuwoDownloadPage
import musicunlock.ncm.NeteaseApi
import musicunlock.online.DownloadTaskManager
import musicunlock.online.OnlineNetwork
import musicunlock.online.DownloadTaskState
import musicunlock.library.LibraryIndex
import musicunlock.library.LibraryMaintenanceService
import musicunlock.online.MusicSong
import musicunlock.online.toOnlineDownloadPreferences
import musicunlock.playlist.LocalTrack
import musicunlock.playlist.MatchOutcome
import musicunlock.playlist.PlaylistImport
import musicunlock.player.AudioPlayerService
import musicunlock.qq.QqDownloadPage
import musicunlock.qq.QqMusicApi
import musicunlock.service.ConversionTaskManager
import musicunlock.service.ConversionTaskState
import musicunlock.service.MusicConverter
import musicunlock.service.TranscodeFormat
import musicunlock.settings.AppSettings
import musicunlock.settings.DownloadExistingPolicy
import musicunlock.settings.OutputFormat
import musicunlock.settings.SettingsStore
import musicunlock.settings.SettingsUpdate
import musicunlock.settings.outputBitrates
import musicunlock.settings.usesBitrate
import musicunlock.sync.CrossPlatformMatcher
import musicunlock.sync.SubscriptionManager
import musicunlock.watch.FolderWatcherService
import musicunlock.desktop.DesktopIntegration
import musicunlock.desktop.GlobalMediaKeyService
import musicunlock.desktop.AutoStartService
import musicunlock.update.ReleaseInfo
import musicunlock.update.UpdateChecker
import musicunlock.update.UpdateDownloader
import musicunlock.update.UpdateInstaller
import musicunlock.update.currentPlatform
import java.awt.Desktop
import java.awt.Dimension
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.net.URI
import java.util.Locale
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** 单个文件的转换状态。 */
enum class FileStatus { PENDING, CONVERTING, DONE, SKIPPED, FAILED, DUPLICATE, PAUSED, CANCELLED }

class FileItem(val path: String, val name: String) {
    var status by mutableStateOf(FileStatus.PENDING)
    var message by mutableStateOf<String?>(null)
    var taskId by mutableStateOf<String?>(null)
    /** 是否勾选参与转换；歌单导入会按匹配结果重设。 */
    var selected by mutableStateOf(true)
}

private fun ConversionTaskState.toFileStatus(): FileStatus = when (this) {
    ConversionTaskState.QUEUED -> FileStatus.PENDING
    ConversionTaskState.RUNNING -> FileStatus.CONVERTING
    ConversionTaskState.COMPLETED -> FileStatus.DONE
    ConversionTaskState.SKIPPED -> FileStatus.SKIPPED
    ConversionTaskState.FAILED -> FileStatus.FAILED
    ConversionTaskState.PAUSED -> FileStatus.PAUSED
    ConversionTaskState.CANCELLED -> FileStatus.CANCELLED
    ConversionTaskState.DUPLICATE -> FileStatus.DUPLICATE
}

private fun OutputFormat.displayName(): String = when (this) {
    OutputFormat.ORIGINAL -> "原始"
    OutputFormat.MP3 -> "MP3"
    OutputFormat.FLAC -> "FLAC"
    OutputFormat.M4A -> "M4A"
    OutputFormat.OGG -> "OGG"
    OutputFormat.OPUS -> "Opus"
    OutputFormat.WAV -> "WAV"
}

private fun DownloadExistingPolicy.localDisplayName(): String = when (this) {
    DownloadExistingPolicy.SKIP -> "跳过"
    DownloadExistingPolicy.OVERWRITE -> "覆盖"
    DownloadExistingPolicy.RENAME -> "另存"
    DownloadExistingPolicy.UPGRADE -> "升级"
}

// ============================================================
//  应用入口
// ============================================================

@Composable
fun MusicUnlockApp(onResetWindowSize: () -> Unit = {}) {
    var settings by remember { mutableStateOf(SettingsStore.load()) }
    var dark by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var resetOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    val files = remember { mutableStateListOf<FileItem>() }
    val libraryIndex = remember { LibraryIndex() }
    val audioPlayer = remember {
        AudioPlayerService(
            stateFile = musicunlock.player.defaultPlayerStateFile(),
            onCompleted = { track -> track.localPath?.let { libraryIndex.recordPlay(it, completed = true) } },
            onSkipped = { track -> track.localPath?.let { libraryIndex.recordPlay(it, completed = false) } },
            audioPreferences = {
                val current = SettingsStore.load()
                musicunlock.player.PlaybackAudioPreferences(
                    loudnessNormalization = current.playerLoudnessNormalization,
                    bassBoostDb = current.playerBassBoostDb,
                    trebleBoostDb = current.playerTrebleBoostDb,
                    fadeSeconds = current.playerFadeSeconds,
                )
            },
        )
    }
    val conversionManager = remember { ConversionTaskManager(library = libraryIndex) }
    val downloadManager = remember { DownloadTaskManager(library = libraryIndex, settingsProvider = { SettingsStore.load() }) }
    var sessionRevision by remember { mutableStateOf(0) }
    val updateSettings: SettingsUpdate = { transform -> settings = SettingsStore.update(transform) }
    DisposableEffect(audioPlayer) {
        GlobalMediaKeyService.start(audioPlayer)
        onDispose {
            GlobalMediaKeyService.stop()
            audioPlayer.close()
        }
    }
    val libraryMaintenance = remember { LibraryMaintenanceService(libraryIndex) }
    val crossPlatformMatcher = remember { CrossPlatformMatcher(libraryMaintenance) }
    val folderWatcher = remember {
        FolderWatcherService(
            settingsProvider = { SettingsStore.load() },
            conversionManager = conversionManager,
        )
    }
    val downloadTaskState by downloadManager.tasks.collectAsState()
    val activeDownloadCount = downloadTaskState.count {
        it.state in setOf(DownloadTaskState.DOWNLOADING, DownloadTaskState.TRANSCODING, DownloadTaskState.TAGGING, DownloadTaskState.QUEUED)
    }
    val subscriptionManager = remember {
        SubscriptionManager(
            settingsProvider = { SettingsStore.load() },
            updateSettings = updateSettings,
            taskManager = downloadManager,
            library = libraryIndex,
        )
    }
    LaunchedEffect(settings.proxyUrl, settings.useSystemProxy, settings.connectTimeoutSeconds, settings.downloadTimeoutSeconds) {
        OnlineNetwork.configure(settings)
    }
    LaunchedEffect(Unit) { subscriptionManager.start(this) }
    LaunchedEffect(subscriptionManager) {
        val listener = subscriptionManager.addListener { result ->
            val current = SettingsStore.load()
            if (!current.subscriptionNotifications) return@addListener
            val body = if (result.error != null) {
                "${result.playlistName}：${result.error}"
            } else {
                "${result.playlistName}：新增 ${result.added} 首，已有 ${result.skipped} 首"
            }
            DesktopIntegration.notify(if (result.error == null) "歌单追更完成" else "歌单追更失败", body)
        }
        try { awaitCancellation() } finally { listener.close() }
    }
    LaunchedEffect(Unit) { folderWatcher.start(this) }
    LaunchedEffect(settings.launchAtLogin) {
        AutoStartService.setEnabled(settings.launchAtLogin)
    }
    LaunchedEffect(downloadManager) {
        val listener = downloadManager.addListener { task ->
            val current = SettingsStore.load()
            if (current.notifyOnComplete && task.state in setOf(DownloadTaskState.COMPLETED, DownloadTaskState.FAILED, DownloadTaskState.SKIPPED)) {
                val title = when (task.state) {
                    DownloadTaskState.COMPLETED -> "下载完成"
                    DownloadTaskState.SKIPPED -> "已跳过"
                    else -> "下载失败"
                }
                DesktopIntegration.notify(title, "${task.title} - ${task.artist}")
            }
        }
        try { awaitCancellation() } finally { listener.close() }
    }
    LaunchedEffect(conversionManager) {
        val listener = conversionManager.addListener { task ->
            val current = SettingsStore.load()
            if (current.notifyOnComplete && task.state in setOf(ConversionTaskState.COMPLETED, ConversionTaskState.FAILED)) {
                DesktopIntegration.notify(
                    if (task.state == ConversionTaskState.COMPLETED) "转换完成" else "转换失败",
                    File(task.inputPath).name,
                )
            }
        }
        try { awaitCancellation() } finally { listener.close() }
    }
    LaunchedEffect(activeDownloadCount, settings.preventSleepWhileDownloading) {
        DesktopIntegration.updatePreventSleep(
            downloadManager.tasks.value.any { it.state in setOf(DownloadTaskState.DOWNLOADING, DownloadTaskState.TRANSCODING, DownloadTaskState.TAGGING) } &&
                settings.preventSleepWhileDownloading,
        )
    }
    MusicUnlockTheme(darkTheme = dark) {
        Box(Modifier.fillMaxSize()) {
            MainScreen(
                dark = dark,
                onToggleDark = { dark = !dark },
                onShowAbout = { aboutOpen = true },
                onShowReset = { resetOpen = true },
                settings = settings,
                onUpdateSettings = updateSettings,
                sessionRevision = sessionRevision,
                files = files,
                audioPlayer = audioPlayer,
                conversionManager = conversionManager,
                downloadManager = downloadManager,
                subscriptionManager = subscriptionManager,
                libraryMaintenance = libraryMaintenance,
                crossPlatformMatcher = crossPlatformMatcher,
                library = libraryIndex,
                onOpenImport = { importOpen = true },
            )
            if (importOpen) {
                PlaylistImportOverlay(
                    files = files,
                    onApplySelection = { paths -> files.forEach { it.selected = paths.contains(it.path) } },
                    onClose = { importOpen = false },
                )
            }
            if (aboutOpen) AboutOverlay(onClose = { aboutOpen = false })
            if (resetOpen) {
                ResetSettingsOverlay(
                    onClose = { resetOpen = false },
                    onConfirm = {
                        NeteaseApi.logout()
                        QqMusicApi.logout()
                        KugouApi.logout()
                        KuwoApi.logout()
                        settings = SettingsStore.reset()
                        sessionRevision++
                        onResetWindowSize()
                        resetOpen = false
                    },
                )
            }
        }
    }
}

fun showWindow() {
    val initialSettings = SettingsStore.load()
    val windowState = WindowState(
        width = initialSettings.windowWidth.dp,
        height = initialSettings.windowHeight.dp,
    )
    singleWindowApplication(
        title = "MusicUnlock",
        state = windowState,
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(980, 680)
            DesktopIntegration.installApplicationIcon(window)
        }
        LaunchedEffect(Unit) {
            while (true) {
                val settings = SettingsStore.load()
                if (settings.minimizeToTray || settings.notifyOnComplete) {
                    DesktopIntegration.installTray(
                        onShow = { window.isVisible = true },
                        onExit = { kotlin.system.exitProcess(0) },
                    )
                }
                window.defaultCloseOperation = if (settings.minimizeToTray) {
                    javax.swing.WindowConstants.HIDE_ON_CLOSE
                } else {
                    javax.swing.WindowConstants.EXIT_ON_CLOSE
                }
                delay(1_000L)
            }
        }
        LaunchedEffect(windowState.size) {
            delay(500)
            SettingsStore.update {
                it.copy(
                    windowWidth = windowState.size.width.value.toInt(),
                    windowHeight = windowState.size.height.value.toInt(),
                )
            }
        }
        MusicUnlockApp(
            onResetWindowSize = {
                window.extendedState = java.awt.Frame.NORMAL
                windowState.size = DpSize(1120.dp, 760.dp)
            },
        )
    }
}

@Composable
fun MainScreen(
    dark: Boolean,
    onToggleDark: () -> Unit,
    onShowAbout: () -> Unit,
    onShowReset: () -> Unit,
    settings: AppSettings,
    onUpdateSettings: SettingsUpdate,
    sessionRevision: Int,
    files: SnapshotStateList<FileItem>,
    audioPlayer: AudioPlayerService,
    conversionManager: ConversionTaskManager,
    downloadManager: DownloadTaskManager,
    subscriptionManager: SubscriptionManager,
    libraryMaintenance: LibraryMaintenanceService,
    crossPlatformMatcher: CrossPlatformMatcher,
    library: LibraryIndex,
    onOpenImport: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(0) }
    var lyricsVisible by remember { mutableStateOf(false) }
    var queueVisible by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }

    // 启动时检查一次 GitHub Releases；失败静默，不阻塞界面
    LaunchedEffect(Unit) {
        update = withContext(Dispatchers.IO) { UpdateChecker.check(BuildInfo.VERSION) }
    }
    LaunchedEffect(update?.version, settings.autoDownloadUpdates) {
        val release = update ?: return@LaunchedEffect
        if (!settings.autoDownloadUpdates || updateBusy) return@LaunchedEffect
        val asset = UpdateDownloader.selectAsset(release, currentPlatform()) ?: return@LaunchedEffect
        updateBusy = true
        val target = File(System.getProperty("user.home"), "Downloads/${asset.name}")
        updateStatus = withContext(Dispatchers.IO) {
            runCatching { UpdateDownloader.download(release, target, currentPlatform()) }
                .fold({ "已自动下载 ${it.file.name} 并完成校验" }, { "自动更新下载失败：${it.message}" })
        }
        updateBusy = false
    }

    val t = cleanTokens()
    val conversionTasks by conversionManager.tasks.collectAsState()
    val downloadTasks by downloadManager.tasks.collectAsState()
    val playerState by audioPlayer.state.collectAsState()
    val activeDownloads = downloadTasks.count { it.state !in setOf(DownloadTaskState.COMPLETED, DownloadTaskState.FAILED, DownloadTaskState.CANCELLED, DownloadTaskState.SKIPPED, DownloadTaskState.PAUSED) }
    val fileTaskIds = files.mapNotNull { it.taskId }.toSet()
    val trackedConversionTasks = conversionTasks.filter { it.id in fileTaskIds }
    val converting = trackedConversionTasks.any { it.state == ConversionTaskState.QUEUED || it.state == ConversionTaskState.RUNNING }
    val progress = if (trackedConversionTasks.isEmpty()) 0f else {
        trackedConversionTasks.sumOf { task ->
            if (task.isTerminal) 1.0 else task.progress.toDouble()
        }.toFloat() / trackedConversionTasks.size
    }
    val doneCount = trackedConversionTasks.count { it.state == ConversionTaskState.COMPLETED }
    val skippedCount = trackedConversionTasks.count { it.state == ConversionTaskState.SKIPPED }
    val failCount = trackedConversionTasks.count { it.state == ConversionTaskState.FAILED }
    val totalBytes = files.sumOf { File(it.path).length() }
    val convertingCount = conversionTasks.count { it.state == ConversionTaskState.RUNNING }
    val pendingCount = files.count { it.selected && it.status == FileStatus.PENDING }
    val selectedCount = files.count { it.selected }

    LaunchedEffect(conversionTasks) {
        val byId = conversionTasks.associateBy { it.id }
        files.forEach { item ->
            val task = item.taskId?.let(byId::get) ?: return@forEach
            item.status = task.state.toFileStatus()
            item.message = task.message
        }
    }

    Row(Modifier.fillMaxSize().background(t.bg)) {
        AppSidebar(
            page = page,
            onSelect = { page = it },
            dark = dark,
            onToggleDark = onToggleDark,
            onShowAbout = onShowAbout,
            onShowReset = onShowReset,
            totalCount = files.size,
            totalSize = humanSize(totalBytes),
            convertingCount = convertingCount,
            activeConversions = conversionTasks.count { it.state == ConversionTaskState.QUEUED || it.state == ConversionTaskState.RUNNING },
            queuedConversions = conversionTasks.count { it.state == ConversionTaskState.QUEUED },
            activeDownloads = activeDownloads,
            queuedDownloads = downloadTasks.count { it.state == DownloadTaskState.QUEUED },
            subscriptionCount = settings.subscriptions.count { it.enabled },
        )
        Box(Modifier.width(1.dp).fillMaxHeight().background(t.border))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            WorkspaceHeader(
                page = page,
                formatCount = Formats.supportedExtensions().size,
                totalSize = humanSize(totalBytes),
                totalCount = files.size,
            )

            // ---- 新版本提示(有更新时出现,可忽略) ----
            update?.let { release ->
                UpdateBanner(
                    release = release,
                    current = BuildInfo.VERSION,
                    status = updateStatus,
                    busy = updateBusy,
                    onDownload = {
                        if (!updateBusy) {
                            val asset = UpdateDownloader.selectAsset(release, currentPlatform())
                            if (asset != null) {
                                updateBusy = true
                                scope.launch {
                                    val target = File(System.getProperty("user.home"), "Downloads/${asset.name}")
                                    updateStatus = withContext(Dispatchers.IO) {
                                        runCatching { UpdateDownloader.download(release, target, currentPlatform()) }
                                            .fold({
                                                UpdateInstaller.openInstaller(it.file)
                                                "已下载 ${it.file.name} 并完成校验"
                                            }, { "下载失败：${it.message}" })
                                    }
                                    updateBusy = false
                                }
                            } else {
                                updateStatus = "当前版本没有适用于本机的安装包"
                            }
                        }
                    },
                    onDismiss = { update = null },
                )
            }

            if (page == 0) {
                // ---- 主体:左列(拖拽 + 队列) + 右侧栏 ----
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        DropZone(
                            files = files,
                            onAddFiles = {
                                val selected = FileDialogs.pickFiles("选择加密音乐文件", Formats.supportedExtensions())
                                addPaths(files, selected.map { it.absolutePath })
                            },
                            onAddFolder = {
                                FileDialogs.pickFolder("选择文件夹")?.let { addPaths(files, listOf(it.absolutePath)) }
                            },
                        )
                        QueueCard(
                            files = files,
                            converting = converting,
                            onToggleSelected = { index -> files[index].selected = !files[index].selected },
                            onSelectAll = { files.forEach { it.selected = true } },
                            onClearSelection = { files.forEach { it.selected = false } },
                            onOpenImport = onOpenImport,
                            onRemove = { index -> if (!converting) files.removeAt(index) },
                        )
                    }

                    Rail(
                        outputDir = settings.outputDir,
                        onOutputDirChange = { value -> onUpdateSettings { it.copy(outputDir = value) } },
                        dedup = settings.dedup,
                        onDedupChange = { value -> onUpdateSettings { it.copy(dedup = value) } },
                        outputFormat = settings.outputFormat,
                        onOutputFormatChange = { value -> onUpdateSettings { it.copy(outputFormat = value) } },
                        bitrateKbps = settings.bitrateKbps,
                        onBitrateChange = { value -> onUpdateSettings { it.copy(bitrateKbps = value) } },
                        outputTemplate = settings.localOutputTemplate,
                        onOutputTemplateChange = { value -> onUpdateSettings { it.copy(localOutputTemplate = value) } },
                        existingFilePolicy = settings.localExistingFilePolicy,
                        onExistingFilePolicyChange = { value ->
                            onUpdateSettings { it.copy(localExistingFilePolicy = value, skipExisting = value == DownloadExistingPolicy.SKIP) }
                        },
                        converting = converting,
                        progress = progress,
                        doneCount = doneCount,
                        skippedCount = skippedCount,
                        failCount = failCount,
                        totalCount = selectedCount,
                    )
                }

                // ---- 底部:状态 + 开始转换 ----
                Footer(
                    converting = converting,
                    convertingCount = convertingCount,
                    selectedCount = selectedCount,
                    pendingCount = pendingCount,
                    enabled = !converting && selectedCount > 0,
                    onConvert = {
                        val chosen = files.filter { it.selected && it.path.isNotBlank() }
                        if (chosen.isEmpty()) return@Footer
                        val ids = conversionManager.enqueueBatch(
                            inputPaths = chosen.map { it.path },
                            outputDir = settings.outputDir,
                            outputFormat = settings.outputFormat,
                            bitrateKbps = settings.bitrateKbps,
                            outputTemplate = settings.localOutputTemplate,
                            existingFilePolicy = settings.localExistingFilePolicy,
                            forceOverwrite = !settings.skipExisting,
                            deduplicate = settings.dedup,
                        )
                        chosen.zip(ids).forEach { (item, id) ->
                            item.taskId = id
                            item.status = FileStatus.PENDING
                            item.message = "已加入任务中心"
                        }
                    },
                )
            } else if (page == 1) {
                key(sessionRevision) {
                    DownloadPage(
                        settings = settings,
                        onUpdateSettings = onUpdateSettings,
                        downloadManager = downloadManager,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            } else if (page == 2) {
                key(sessionRevision) {
                    QqDownloadPage(
                        settings = settings,
                        onUpdateSettings = onUpdateSettings,
                        downloadManager = downloadManager,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            } else if (page == 3) {
                key(sessionRevision) {
                    KugouDownloadPage(
                        settings = settings,
                        onUpdateSettings = onUpdateSettings,
                        downloadManager = downloadManager,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            } else if (page == 4) {
                key(sessionRevision) {
                    KuwoDownloadPage(
                        settings = settings,
                        onUpdateSettings = onUpdateSettings,
                        downloadManager = downloadManager,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            } else if (page == 5) {
                DownloadTaskPage(
                    downloadManager = downloadManager,
                    conversionManager = conversionManager,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (page == 6) {
                LibraryPage(
                    settings = settings,
                    onUpdateSettings = onUpdateSettings,
                    maintenance = libraryMaintenance,
                    subscriptionManager = subscriptionManager,
                    audioPlayer = audioPlayer,
                    library = library,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (page == 7) {
                DiscoverPage(
                    settings = settings,
                    downloadManager = downloadManager,
                    audioPlayer = audioPlayer,
                    crossPlatformMatcher = crossPlatformMatcher,
                    library = library,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else {
                SettingsPage(
                    settings = settings,
                    onUpdateSettings = onUpdateSettings,
                    library = library,
                    conversionManager = conversionManager,
                    downloadManager = downloadManager,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
            if (playerState.current != null) {
                PlayerBar(
                    player = audioPlayer,
                    onToggleLyrics = { lyricsVisible = !lyricsVisible },
                    lyricsVisible = lyricsVisible,
                    onToggleQueue = { queueVisible = !queueVisible },
                    queueVisible = queueVisible,
                    onDownload = { track ->
                        val provider = musicunlock.online.ProviderRegistry.find(track.platformId)
                        if (provider != null) {
                            downloadManager.enqueue(
                                provider = provider,
                                song = track.song,
                                outputDir = File(settings.outputDir),
                                preferences = settings.toOnlineDownloadPreferences().copy(
                                    targetFormat = TranscodeFormat.MP3,
                                    forceMp3 = true,
                                    mp3BitrateKbps = settings.bitrateKbps,
                                ),
                                playlistName = "播放器下载",
                            )
                        }
                    },
                )
                if (queueVisible) {
                    PlayerQueuePanel(player = audioPlayer, modifier = Modifier.fillMaxWidth())
                }
                if (lyricsVisible) {
                    PlayerLyricsPanel(player = audioPlayer, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(
    page: Int,
    formatCount: Int,
    totalSize: String,
    totalCount: Int,
) {
    val t = cleanTokens()
    val (title, subtitle) = when (page) {
        0 -> "格式转换" to "拖入加密音乐，保留标签与封面输出标准音频"
        1 -> "网易云下载" to "登录后选择歌单，下载歌曲"
        2 -> "QQ 音乐下载" to "登录后选择歌单，下载歌曲"
        3 -> "酷狗下载" to "登录后选择收藏与创建的歌单，下载歌曲"
        4 -> "酷我下载" to "登录后选择收藏与创建的歌单，下载歌曲"
        5 -> "任务中心" to "统一查看本地转换与在线下载，随时暂停、继续和重试"
        6 -> "曲库整理" to "扫描重复与缺失元数据，批量整理并周期同步歌单"
        7 -> "搜索与链接" to "搜索四个平台，粘贴歌曲、专辑、歌手或歌单链接后直接加入下载队列"
        8 -> "设置" to "输出、下载、网络、后台、备份与更新"
        else -> "搜索与链接" to "搜索四个平台，粘贴歌曲、专辑、歌手或歌单链接后直接加入下载队列"
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(50.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                title,
                fontSize = 20.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                color = t.text,
            )
            Text(
                subtitle,
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
                color = t.textSecondary,
            )
        }
        if (page == 0) {
            Spacer(Modifier.weight(1f))
            Pill(text = "支持 $formatCount 种格式", emphasize = false)
            Spacer(Modifier.width(10.dp))
            Pill(
                text = if (totalCount > 0) "待处理 $totalSize" else "暂无文件",
                emphasize = true,
            )
        }
    }
}

// ============================================================
//  左侧工作区导航
// ============================================================

@Composable
private fun AppSidebar(
    page: Int,
    onSelect: (Int) -> Unit,
    dark: Boolean,
    onToggleDark: () -> Unit,
    onShowAbout: () -> Unit,
    onShowReset: () -> Unit,
    totalCount: Int,
    totalSize: String,
    convertingCount: Int,
    activeConversions: Int,
    queuedConversions: Int,
    activeDownloads: Int,
    queuedDownloads: Int,
    subscriptionCount: Int,
) {
    val t = cleanTokens()
    Column(
        modifier = Modifier
            .width(228.dp)
            .fillMaxHeight()
            .background(t.surface)
            .padding(vertical = 18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LogoMark(40.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "MusicUnlock",
                    fontSize = 17.5.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.15).sp,
                    color = t.text,
                )
                Text(
                    "本地转换 · 在线下载",
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp,
                    color = t.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(28.dp))
        SidebarSectionLabel("本地工具")
        Spacer(Modifier.height(8.dp))
        SidebarNavItem(
            icon = Icons.Outlined.AudioFile,
            title = "格式转换",
            detail = "本地文件",
            selected = page == 0,
            onClick = { onSelect(0) },
        )

        Spacer(Modifier.height(20.dp))
        SidebarSectionLabel("音乐服务")
        Spacer(Modifier.height(8.dp))
        SidebarNavItem(
            icon = Icons.Outlined.CloudDownload,
            title = "网易云下载",
            detail = "扫码或浏览器",
            selected = page == 1,
            onClick = { onSelect(1) },
        )
        SidebarNavItem(
            icon = Icons.AutoMirrored.Outlined.QueueMusic,
            title = "QQ 音乐下载",
            detail = "扫码或 Cookie",
            selected = page == 2,
            onClick = { onSelect(2) },
        )
        SidebarNavItem(
            icon = Icons.Outlined.Headphones,
            title = "酷狗下载",
            detail = "扫码或浏览器",
            selected = page == 3,
            onClick = { onSelect(3) },
        )
        SidebarNavItem(
            icon = Icons.Outlined.Album,
            title = "酷我下载",
            detail = "浏览器或 Cookie",
            selected = page == 4,
            onClick = { onSelect(4) },
        )

        Spacer(Modifier.height(20.dp))
        SidebarSectionLabel("任务管理")
        Spacer(Modifier.height(8.dp))
        SidebarNavItem(
            icon = Icons.Outlined.Download,
            title = "任务中心",
            detail = if (activeConversions + activeDownloads > 0 || queuedConversions + queuedDownloads > 0) {
                "${activeConversions + activeDownloads} 进行中 · ${queuedConversions + queuedDownloads} 等待"
            } else {
                "转换、下载、暂停与历史"
            },
            selected = page == 5,
            onClick = { onSelect(5) },
        )

        SidebarNavItem(
            icon = Icons.Outlined.Tune,
            title = "曲库整理",
            detail = if (subscriptionCount > 0) "$subscriptionCount 个歌单追更中" else "扫描、标签与追更",
            selected = page == 6,
            onClick = { onSelect(6) },
        )

        SidebarNavItem(
            icon = Icons.Outlined.Search,
            title = "搜索与链接",
            detail = "四平台统一搜索",
            selected = page == 7,
            onClick = { onSelect(7) },
        )
        SidebarNavItem(
            icon = Icons.Outlined.Settings,
            title = "设置",
            detail = "输出、网络、备份与更新",
            selected = page == 8,
            onClick = { onSelect(8) },
        )

        Spacer(Modifier.weight(1f))
        SidebarQueueSummary(
            totalCount = totalCount,
            totalSize = totalSize,
            convertingCount = convertingCount,
            onClick = { onSelect(0) },
        )
        HorizontalDivider(color = t.border)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SidebarUtilityButton(
                icon = Icons.Outlined.Info,
                label = "关于",
                onClick = onShowAbout,
            )
            Spacer(Modifier.weight(1f))
            SidebarUtilityButton(
                icon = if (dark) Icons.Filled.LightMode else Icons.Outlined.DarkMode,
                label = if (dark) "浅色" else "深色",
                onClick = onToggleDark,
            )
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 21.dp)) {
            SidebarUtilityButton(
                icon = Icons.Outlined.Refresh,
                label = "恢复默认设置",
                onClick = onShowReset,
            )
        }
    }
}

@Composable
private fun SidebarSectionLabel(text: String) {
    val t = cleanTokens()
    Text(
        text,
        modifier = Modifier.padding(horizontal = 18.dp),
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        color = t.textMuted,
    )
}

@Composable
private fun SidebarNavItem(
    icon: ImageVector,
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        selected -> t.primarySoft
        hovered -> t.surfaceSoft
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(
                1.dp,
                if (focused) t.primary.copy(alpha = 0.72f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (selected) t.surface else t.surfaceSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) t.primary else t.textSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 13.5.sp,
                lineHeight = 17.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) t.primary else t.text,
            )
            Text(
                detail,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                color = t.textMuted,
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(t.primary),
            )
        }
    }
}

@Composable
private fun SidebarQueueSummary(
    totalCount: Int,
    totalSize: String,
    convertingCount: Int,
    onClick: () -> Unit,
) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered) t.surfaceSoft else Color.Transparent)
            .border(
                1.dp,
                if (focused) t.primary.copy(alpha = 0.72f) else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "转换队列",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.7.sp,
                color = t.textMuted,
            )
            Spacer(Modifier.weight(1f))
            if (convertingCount > 0) {
                Text(
                    "$convertingCount 处理中",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.primary,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (totalCount > 0) "$totalCount 首 · $totalSize" else "暂无待转换文件",
            fontSize = 12.5.sp,
            fontWeight = if (totalCount > 0) FontWeight.SemiBold else FontWeight.Normal,
            color = if (totalCount > 0) t.text else t.textMuted,
        )
    }
}

@Composable
private fun SidebarUtilityButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered) t.surfaceSoft else Color.Transparent)
            .border(
                1.dp,
                if (focused) t.primary.copy(alpha = 0.72f) else Color.Transparent,
                RoundedCornerShape(9.dp),
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = t.textSecondary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = t.textSecondary,
        )
    }
}

/** 启动检查到新版本时的提示条：给出下载入口，可忽略，不阻塞任何操作。 */
@Composable
private fun UpdateBanner(
    release: ReleaseInfo,
    current: String,
    status: String?,
    busy: Boolean,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = cleanTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.primarySoft)
            .border(1.dp, t.primary.copy(alpha = 0.32f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.NewReleases,
            contentDescription = null,
            tint = t.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "发现新版本 ${release.version}",
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = t.text,
            )
            Text(
                status ?: "当前版本 $current",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = t.textSecondary,
            )
        }
        AppTextAction(if (busy) "下载中…" else "下载更新", enabled = !busy, onClick = onDownload, filled = true)
        Spacer(Modifier.width(4.dp))
        AppTextAction("发行页", onClick = { openInBrowser(release.pageUrl) })
        Spacer(Modifier.width(4.dp))
        AppTextAction("忽略", onClick = onDismiss)
    }
}

/** 在系统浏览器中打开链接；浏览器不可用时静默跳过。 */
private fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI.create(url)) }
}

/** 关于卡片：版本信息、项目简介与 GitHub 项目页入口。 */
@Composable
private fun AboutOverlay(onClose: () -> Unit) {
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(400.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.surface)
                .border(1.dp, t.border, RoundedCornerShape(18.dp))
                // 卡片内部吞掉点击,避免穿透到遮罩
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(horizontal = 26.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LogoMark(56.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                "MusicUnlock",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = t.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "版本 ${BuildInfo.VERSION}",
                fontSize = 12.5.sp,
                color = t.textSecondary,
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "多平台加密音乐格式转换工具:网易云 / QQ 音乐 / 酷狗 / 酷我,解密后输出标准音频格式,保留标签与封面。",
                fontSize = 12.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center,
                color = t.textSecondary,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(t.primary)
                    .clickable { openInBrowser(AppLinks.PROJECT_PAGE) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    tint = t.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "GitHub 项目页",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.onPrimary,
                )
            }
            Spacer(Modifier.height(6.dp))
            AppTextAction("关闭", onClick = onClose, outlined = true)
        }
    }
}

/** 恢复默认确认卡片，避免误触清空设置与登录状态。 */
@Composable
private fun ResetSettingsOverlay(onClose: () -> Unit, onConfirm: () -> Unit) {
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.surface)
                .border(1.dp, t.border, RoundedCornerShape(18.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(horizontal = 26.dp, vertical = 24.dp),
        ) {
            Text(
                "恢复默认设置",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = t.text,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "输出目录、去重、跳过已完成、输出格式与码率、窗口大小和登录状态都会恢复为初始值。",
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = t.textSecondary,
            )
            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppTextAction(
                    text = "取消",
                    onClick = onClose,
                    modifier = Modifier.height(UiMetrics.ControlHeight),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.primary,
                        contentColor = t.onPrimary,
                    ),
                    modifier = Modifier.height(40.dp),
                ) {
                    Text("恢复默认", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ============================================================
//  品牌与摘要
// ============================================================

@Composable
private fun Pill(text: String, emphasize: Boolean) {
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(t.surface)
            .border(1.dp, t.border, RoundedCornerShape(20.dp))
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            color = if (emphasize) t.text else t.textSecondary,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// ============================================================
//  拖拽区
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DropZone(files: SnapshotStateList<FileItem>, onAddFiles: () -> Unit, onAddFolder: () -> Unit) {
    val t = cleanTokens()
    var hovering by remember { mutableStateOf(false) }
    val borderColor = if (hovering) t.primary else t.dropBorder
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(214.dp)
            .dashedBorder(color = borderColor, cornerRadius = 16.dp, strokeWidth = 1.8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(t.surface)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = object : DragAndDropTarget {
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val paths = event.filePaths()
                        if (paths.isNotEmpty()) addPaths(files, paths)
                        return true
                    }

                    override fun onEntered(event: DragAndDropEvent) {
                        hovering = true
                    }

                    override fun onExited(event: DragAndDropEvent) {
                        hovering = false
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (hovering) t.primarySoft else t.surfaceSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.LibraryMusic,
                    contentDescription = null,
                    tint = if (hovering) t.primary else t.textSecondary,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "拖拽加密音乐文件 / 文件夹 到这里",
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (hovering) t.primary else t.text,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "NCM · QMC · MFLAC · MGG · KGM · KWM 等 ${Formats.supportedExtensions().size} 种格式，自动识别真实音频并保留标签与封面",
                fontSize = 12.5.sp,
                color = t.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(15.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(onClick = onAddFiles, text = "添加文件", icon = Icons.Outlined.Add)
                SoftButton(onClick = onAddFolder, text = "添加文件夹", icon = Icons.Outlined.LibraryMusic)
            }
        }
    }
}

// ============================================================
//  队列卡片
// ============================================================

@Composable
private fun ColumnScope.QueueCard(
    files: SnapshotStateList<FileItem>,
    converting: Boolean,
    onToggleSelected: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onOpenImport: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    val t = cleanTokens()
    val selectedCount = files.count { it.selected }
    val listState = rememberLazyListState()
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(UiMetrics.CardRadius))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(UiMetrics.CardRadius)),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "转换队列",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.text,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "已选 $selectedCount / ${files.size} 首",
                    fontSize = 12.sp,
                    color = t.textMuted,
                )
                Spacer(Modifier.weight(1f))
                AppTextAction(
                    text = "全选",
                    onClick = onSelectAll,
                    enabled = files.isNotEmpty() && selectedCount < files.size,
                    primary = true,
                )
                AppTextAction(
                    text = "清空",
                    onClick = onClearSelection,
                    enabled = selectedCount > 0,
                )
                AppTextAction(
                    text = "歌单导入",
                    onClick = onOpenImport,
                    primary = true,
                    leadingIcon = Icons.AutoMirrored.Outlined.PlaylistAdd,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(t.rowDivider))
            if (files.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.LibraryMusic,
                            contentDescription = null,
                            tint = t.textMuted.copy(alpha = 0.55f),
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "还没有添加文件\n拖拽或点击上方区域选择加密音乐文件",
                            fontSize = 13.sp,
                            color = t.textSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp,
                        )
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 6.dp),
                    ) {
                        itemsIndexed(files) { index, item ->
                            Column {
                                FileRow(
                                    item = item,
                                    converting = converting,
                                    onToggleSelected = { onToggleSelected(index) },
                                    onRemove = { onRemove(index) },
                                )
                                if (index < files.lastIndex) {
                                    Box(Modifier.fillMaxWidth().height(UiMetrics.Hairline).background(t.rowDivider))
                                }
                            }
                        }
                    }
                    AppVerticalScrollbar(
                        state = listState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 3.dp),
                    )
                }
            }
        }
    }
}

// ============================================================
//  文件行
// ============================================================

@Composable
private fun FileRow(
    item: FileItem,
    converting: Boolean,
    onToggleSelected: () -> Unit,
    onRemove: () -> Unit,
) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (!converting && hovered) t.surfaceSoft.copy(alpha = 0.62f) else Color.Transparent)
            .hoverable(interaction, enabled = !converting)
            .pointerHoverIcon(if (converting) PointerIcon.Default else PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !converting,
                onClick = onToggleSelected,
            )
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QueueCheckbox(checked = item.selected, enabled = !converting, onToggle = onToggleSelected)
        ExtBadge(ext = ext)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
                color = t.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.message != null) {
                Text(
                    item.message.orEmpty(),
                    fontSize = 11.5.sp,
                    color = t.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            humanSize(File(item.path).length()),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = t.textMuted,
        )
        if (item.status == FileStatus.CONVERTING) {
            Spacer(Modifier.width(6.dp))
            IndeterminateGradientProgress(
                modifier = Modifier.width(130.dp).height(8.dp),
            )
        }
        Spacer(Modifier.width(4.dp))
        StatusChip(item.status)
        Spacer(Modifier.width(2.dp))
        AppIconButton(
            icon = Icons.Outlined.Close,
            contentDescription = "移除",
            onClick = onRemove,
            enabled = !converting,
            danger = true,
        )
    }
}

@Composable
private fun ExtBadge(ext: String) {
    val t = cleanTokens()
    val (bg, fg) = when {
        ext == "ncm" -> t.ncmBg to t.ncmFg
        ext == "kgm" || ext == "kgma" || ext == "vpr" -> t.kgmBg to t.kgmFg
        ext == "kwm" -> t.kwmBg to t.kwmFg
        else -> t.qmcBg to t.qmcFg
    }
    Box(
        modifier = Modifier
            .width(66.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            ext.uppercase(Locale.ROOT),
            modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
            textAlign = TextAlign.Center,
            fontSize = 10.5.sp,
            lineHeight = 10.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            fontFamily = FontFamily.Monospace,
            color = fg,
        )
    }
}

@Composable
private fun StatusChip(status: FileStatus) {
    val t = cleanTokens()
    val (text, bg, fg) = when (status) {
        FileStatus.PENDING -> Triple("等待中", t.waitBg, t.waitFg)
        FileStatus.CONVERTING -> Triple("转换中", t.primarySoft, t.primary)
        FileStatus.DONE -> Triple("已完成", t.successSoft, t.success)
        FileStatus.SKIPPED -> Triple("已跳过", t.waitBg, t.waitFg)
        FileStatus.FAILED -> Triple("失败", t.errorSoft, t.error)
        FileStatus.DUPLICATE -> Triple("重复", t.dupBg, t.dupFg)
        FileStatus.PAUSED -> Triple("已暂停", t.waitBg, t.waitFg)
        FileStatus.CANCELLED -> Triple("已取消", t.waitBg, t.waitFg)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            modifier = Modifier.wrapContentSize(Alignment.Center),
            textAlign = TextAlign.Center,
            fontSize = 11.5.sp,
            lineHeight = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

// ============================================================
//  右侧栏
// ============================================================

@Composable
private fun Rail(
    outputDir: String,
    onOutputDirChange: (String) -> Unit,
    dedup: Boolean,
    onDedupChange: (Boolean) -> Unit,
    outputFormat: OutputFormat,
    onOutputFormatChange: (OutputFormat) -> Unit,
    bitrateKbps: Int,
    onBitrateChange: (Int) -> Unit,
    outputTemplate: String,
    onOutputTemplateChange: (String) -> Unit,
    existingFilePolicy: DownloadExistingPolicy,
    onExistingFilePolicyChange: (DownloadExistingPolicy) -> Unit,
    converting: Boolean,
    progress: Float,
    doneCount: Int,
    skippedCount: Int,
    failCount: Int,
    totalCount: Int,
) {
    val t = cleanTokens()
    val scrollState = rememberScrollState()
    Box(modifier = Modifier.width(300.dp).fillMaxHeight()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(end = 8.dp).verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        RailLabel("输出目录")
        Field(
            path = outputDir,
            actions = listOf(
                "浏览…" to {
                    FileDialogs.pickFolder("选择输出目录")?.let { onOutputDirChange(it.absolutePath) }
                },
                "打开" to {
                    runCatching { Desktop.getDesktop().open(File(outputDir)) }
                },
            ),
        )
        RailLabel("选项")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            CleanSwitch(
                checked = dedup,
                enabled = !converting,
                onChange = onDedupChange,
            )
            Text(
                "按解密后音频内容去重",
                fontSize = 13.5.sp,
                color = t.text,
            )
        }
        Spacer(Modifier.height(10.dp))
        RailLabel("同名文件")
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(
                DownloadExistingPolicy.SKIP,
                DownloadExistingPolicy.RENAME,
                DownloadExistingPolicy.OVERWRITE,
            ).chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { policy ->
                        ChoiceChip(
                            text = policy.localDisplayName(),
                            selected = existingFilePolicy == policy,
                            enabled = !converting,
                            onClick = { onExistingFilePolicyChange(policy) },
                        )
                    }
                }
            }
        }
        RailLabel("输出格式")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputFormat.entries.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { format ->
                        ChoiceChip(
                            text = format.displayName(),
                            selected = outputFormat == format,
                            enabled = !converting,
                            onClick = { onOutputFormatChange(format) },
                        )
                    }
                }
            }
        }
        if (outputFormat.usesBitrate) {
            RailLabel("码率")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                outputBitrates.forEach { value ->
                    ChoiceChip(
                        text = "${value}k",
                        selected = bitrateKbps == value,
                        enabled = !converting,
                        onClick = { onBitrateChange(value) },
                    )
                }
            }
        }
        RailLabel("本地命名模板")
        OutlinedTextField(
            value = outputTemplate,
            onValueChange = onOutputTemplateChange,
            enabled = !converting,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = t.text),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = t.surfaceSoft,
                unfocusedContainerColor = t.surfaceSoft,
                focusedIndicatorColor = t.primary,
                unfocusedIndicatorColor = t.border,
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp),
        )
        Text(
            "{title} · {artist} · {album} · {track:02} · {year} · {platform}",
            fontSize = 10.5.sp,
            lineHeight = 15.sp,
            color = t.textMuted,
        )
        RailLabel("进度")
        if (totalCount > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientProgress(
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    progress = progress,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "完成 $doneCount · 跳过 $skippedCount · 失败 $failCount",
                        fontSize = 12.5.sp,
                        color = t.textSecondary,
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = t.text,
                    )
                }
            }
        } else {
            Text(
                "添加文件后开始转换",
                fontSize = 12.5.sp,
                color = t.textMuted,
            )
        }
    }
    AppVerticalScrollbar(
        state = scrollState,
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
    }
}

@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AppChoiceChip(text = text, selected = selected, enabled = enabled, onClick = onClick)
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
private fun Field(path: String, actions: List<Pair<String, () -> Unit>>) {
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
        actions.forEach { (label, action) ->
            ActionPill(label = label, onClick = action)
            Spacer(Modifier.width(6.dp))
        }
    }
}

/** 小灰胶囊动作钮(浏览…/打开), 对应设计稿 .mini2。 */
@Composable
private fun ActionPill(label: String, onClick: () -> Unit) {
    AppTextAction(text = label, onClick = onClick)
}

// ============================================================
//  底部
// ============================================================

@Composable
private fun Footer(
    converting: Boolean,
    convertingCount: Int,
    selectedCount: Int,
    pendingCount: Int,
    enabled: Boolean,
    onConvert: () -> Unit,
) {
    val t = cleanTokens()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (converting) "转换中 $convertingCount 首 · 已选 $selectedCount 首"
            else if (pendingCount > 0) "已选 $selectedCount 首 · 待处理 $pendingCount 首"
            else if (selectedCount > 0) "已选 $selectedCount 首"
            else "勾选要转换的文件后开始",
            fontSize = 13.sp,
            color = t.textSecondary,
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onConvert,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = t.primary,
                contentColor = t.onPrimary,
                disabledContainerColor = t.surfaceSoft,
                disabledContentColor = t.textMuted,
            ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
            modifier = Modifier
                .height(48.dp)
                .shadow(6.dp, RoundedCornerShape(12.dp), spotColor = t.primary.copy(alpha = 0.30f), ambientColor = t.primary.copy(alpha = 0.22f)),
        ) {
            Text(
                if (converting) "转换中…" else "开始转换",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// ============================================================
//  小组件
// ============================================================

@Composable
private fun SoftButton(onClick: () -> Unit, text: String, icon: ImageVector) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .height(UiMetrics.ControlHeight)
            .clip(RoundedCornerShape(UiMetrics.ControlRadius))
            .background(if (hovered) t.surfaceSoft else t.surface)
            .border(
                1.dp,
                if (focused) t.primary else if (hovered) t.dropBorder else t.border,
                RoundedCornerShape(UiMetrics.ControlRadius),
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (hovered) t.text else t.textSecondary, modifier = Modifier.size(15.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.text)
    }
}

/** 设计稿同款小开关：统一使用应用级切换控件。 */
@Composable
private fun CleanSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    AppToggle(checked = checked, enabled = enabled, onCheckedChange = onChange)
}

/** 渐变进度条(设计稿同款 90deg 橙渐变)。 */
@Composable
private fun GradientProgress(modifier: Modifier, progress: Float) {
    val t = cleanTokens()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(5.dp))
            .background(t.surfaceSoft),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(5.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF8A3D), t.primary))),
        )
    }
}

/** 转换中的不确定渐变条: 0→100% 循环扫过。 */
@Composable
private fun IndeterminateGradientProgress(modifier: Modifier) {
    val t = cleanTokens()
    val transition = rememberInfiniteTransition(label = "rowProgress")
    val p by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
        label = "rowProgress",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(t.surfaceSoft),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(p)
                .clip(RoundedCornerShape(4.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFF8A3D), t.primary))),
        )
    }
}

/** 队列勾选框:18dp 圆角方块,勾选后填充品牌橙。 */
@Composable
private fun QueueCheckbox(checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (checked) t.primary else t.surface)
            .border(1.dp, if (checked) Color.Transparent else t.border, RoundedCornerShape(5.dp))
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .clickable(enabled = enabled, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "已勾选",
                tint = t.onPrimary,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

/**
 * 歌单导入:粘贴四平台歌曲/歌单/专辑/歌手链接,读取曲目并与本地队列匹配,
 * 确认后按匹配结果勾选队列文件,未命中的曲目逐条列出。
 */
@Composable
private fun PlaylistImportOverlay(
    files: List<FileItem>,
    onApplySelection: (Set<String>) -> Unit,
    onClose: () -> Unit,
) {
    val t = cleanTokens()
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<MatchOutcome?>(null) }
    val resultListState = rememberLazyListState()
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = t.surfaceSoft,
        unfocusedContainerColor = t.surfaceSoft,
        focusedIndicatorColor = t.primary,
        unfocusedIndicatorColor = t.border,
        focusedTextColor = t.text,
        unfocusedTextColor = t.text,
        cursorColor = t.primary,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClose,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(18.dp))
                .background(t.surface)
                .border(1.dp, t.border, RoundedCornerShape(18.dp))
                // 卡片内部吞掉点击,避免穿透到遮罩
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Outlined.PlaylistAdd,
                    contentDescription = null,
                    tint = t.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text("歌单导入", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.text)
                Spacer(Modifier.weight(1f))
                AppIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    onClick = onClose,
                    size = UiMetrics.CompactIconButtonSize,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "粘贴网易云、QQ、酷狗或酷我链接，自动勾选队列中对应的本地文件。",
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                color = t.textSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        error = null
                    },
                    placeholder = {
                        Text(
                            "https://music.163.com/playlist?id=… 或任一平台分享链接",
                            fontSize = 12.sp,
                            color = t.textMuted,
                        )
                    },
                    singleLine = true,
                    colors = fieldColors,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            val snapshot = files.map { LocalTrack(it.path, it.name) }
                            val result = withContext(Dispatchers.IO) {
                                runCatching { PlaylistImport.importAndMatch(link, snapshot) }
                            }
                            result.onSuccess { outcome = it }.onFailure {
                                outcome = null
                                error = it.message ?: "读取歌单失败"
                            }
                            busy = false
                        }
                    },
                    enabled = !busy && link.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.primary,
                        contentColor = t.onPrimary,
                        disabledContainerColor = t.surfaceSoft,
                        disabledContentColor = t.textMuted,
                    ),
                    modifier = Modifier.height(48.dp),
                ) {
                    Text(
                        if (busy) "读取中…" else "读取歌单",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error.orEmpty(), fontSize = 12.5.sp, color = t.error)
            }

            val result = outcome
            if (result == null) {
                Spacer(Modifier.weight(1f))
                Text(
                    if (files.isEmpty()) "队列里还没有文件，先添加要转换的本地文件" else "队列共 ${files.size} 首，读取歌单后按名称匹配",
                    fontSize = 12.5.sp,
                    color = t.textMuted,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.weight(1f))
            } else {
                Spacer(Modifier.height(14.dp))
                Text(
                    "命中 ${result.matched.size} 首 · 未命中 ${result.unmatched.size} 首",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = t.text,
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = resultListState, modifier = Modifier.fillMaxSize()) {
                    if (result.matched.isNotEmpty()) {
                        item { ImportSectionLabel("已命中 (${result.matched.size})", t.textSecondary) }
                        items(result.matched) { match ->
                            ImportTrackRow(
                                song = match.song,
                                detail = "→ ${match.filePaths.first().substringAfterLast('/')}",
                                hit = true,
                            )
                        }
                    }
                    if (result.unmatched.isNotEmpty()) {
                        item { ImportSectionLabel("未命中 (${result.unmatched.size})", t.textMuted) }
                        items(result.unmatched) { song ->
                            ImportTrackRow(song = song, detail = song.artistText, hit = false)
                        }
                    }
                }
                AppVerticalScrollbar(
                    state = resultListState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 3.dp),
                )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "未命中的曲目不在队列中，可先添加对应本地文件后再导入",
                    fontSize = 11.5.sp,
                    color = t.textMuted,
                )
                Spacer(Modifier.weight(1f))
                AppTextAction(
                    text = "取消",
                    onClick = onClose,
                    modifier = Modifier.height(UiMetrics.ControlHeight),
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        result?.let { onApplySelection(it.matchedPaths) }
                        onClose()
                    },
                    enabled = (result?.matchedPaths?.isNotEmpty() ?: false),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = t.primary,
                        contentColor = t.onPrimary,
                        disabledContainerColor = t.surfaceSoft,
                        disabledContentColor = t.textMuted,
                    ),
                    modifier = Modifier.height(42.dp),
                ) {
                    Text(
                        "仅勾选命中的 ${result?.matchedPaths?.size ?: 0} 首",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportSectionLabel(text: String, color: Color) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ImportTrackRow(song: MusicSong, detail: String, hit: Boolean) {
    val t = cleanTokens()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (hit) t.successSoft else t.errorSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (hit) Icons.Outlined.Check else Icons.Outlined.Close,
                contentDescription = null,
                tint = if (hit) t.success else t.error,
                modifier = Modifier.size(12.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = t.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                fontSize = 11.5.sp,
                color = t.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (hit) "命中" else "未命中",
            fontSize = 11.5.sp,
            color = if (hit) t.success else t.error,
        )
    }
}

/** 虚线圆角边框(拖拽区)。 */
private fun Modifier.dashedBorder(
    color: Color,
    cornerRadius: Dp = 16.dp,
    strokeWidth: Dp = 1.5.dp,
    dash: Dp = 8.dp,
    gap: Dp = 6.dp,
): Modifier = this.drawBehind {
    val stroke = strokeWidth.toPx()
    val radius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx())
    val path = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(size.toRect(), radius))
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx())),
        ),
    )
}

// ============================================================
//  工具函数
// ============================================================

private fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.1f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun addPaths(files: SnapshotStateList<FileItem>, paths: List<String>) {
    val existing = files.map { it.path }.toHashSet()
    for (path in paths) {
        val f = File(path)
        if (f.isFile) {
            if (existing.add(f.absolutePath)) files.add(FileItem(f.absolutePath, f.name))
        } else if (f.isDirectory) {
            val collected = mutableListOf<File>()
            MusicConverter.listAllFiles(collected, f)
            for (file in collected) {
                if (existing.add(file.absolutePath)) files.add(FileItem(file.absolutePath, file.name))
            }
        }
    }
}

/** 从 Compose 拖拽事件中提取文件路径列表(读取底层 AWT 事件)。 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.filePaths(): List<String> {
    val transferable = when (val native = nativeEvent) {
        is DropTargetDropEvent -> native.transferable
        else -> null
    } ?: return emptyList()
    return try {
        @Suppress("UNCHECKED_CAST")
        (transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.mapNotNull { it as? File }?.map { it.absolutePath }
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}
