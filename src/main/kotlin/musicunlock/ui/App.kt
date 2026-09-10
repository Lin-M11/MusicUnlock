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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.AppLinks
import musicunlock.BuildInfo
import musicunlock.core.Formats
import musicunlock.ncm.NeteaseApi
import musicunlock.qq.QqDownloadPage
import musicunlock.qq.QqMusicApi
import musicunlock.service.MusicConverter
import musicunlock.settings.AppSettings
import musicunlock.settings.OutputFormat
import musicunlock.settings.SettingsStore
import musicunlock.settings.SettingsUpdate
import musicunlock.settings.outputBitrates
import musicunlock.update.ReleaseInfo
import musicunlock.update.UpdateChecker
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
enum class FileStatus { PENDING, CONVERTING, DONE, FAILED, DUPLICATE }

class FileItem(val path: String, val name: String) {
    var status by mutableStateOf(FileStatus.PENDING)
    var message by mutableStateOf<String?>(null)
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
    var sessionRevision by remember { mutableStateOf(0) }
    val updateSettings: SettingsUpdate = { transform -> settings = SettingsStore.update(transform) }
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
            )
            if (aboutOpen) AboutOverlay(onClose = { aboutOpen = false })
            if (resetOpen) {
                ResetSettingsOverlay(
                    onClose = { resetOpen = false },
                    onConfirm = {
                        NeteaseApi.logout()
                        QqMusicApi.logout()
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
) {
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<FileItem>() }
    var converting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var doneCount by remember { mutableStateOf(0) }
    var failCount by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }
    var update by remember { mutableStateOf<ReleaseInfo?>(null) }

    // 启动时检查一次 GitHub Releases；失败静默，不阻塞界面
    LaunchedEffect(Unit) {
        update = withContext(Dispatchers.IO) { UpdateChecker.check(BuildInfo.VERSION) }
    }

    val t = cleanTokens()
    val totalBytes = files.sumOf { File(it.path).length() }
    val convertingCount = files.count { it.status == FileStatus.CONVERTING }
    val pendingCount = files.count { it.status == FileStatus.PENDING }

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
                        converting = converting,
                        progress = progress,
                        doneCount = doneCount,
                        failCount = failCount,
                        totalCount = files.size,
                    )
                }

                // ---- 底部:状态 + 开始转换 ----
                Footer(
                    converting = converting,
                    convertingCount = convertingCount,
                    pendingCount = pendingCount,
                    enabled = !converting && files.isNotEmpty(),
                    onConvert = {
                        if (files.isEmpty()) return@Footer
                        converting = true
                        doneCount = 0
                        failCount = 0
                        progress = 0f
                        val targets = if (settings.dedup) dedupFiles(files) else files.toList()
                        val output = settings.outputDir
                        val outputFormat = settings.outputFormat
                        val bitrateKbps = settings.bitrateKbps
                        scope.launch {
                            val total = targets.size
                            var processed = 0
                            targets.forEachIndexed { idx, item ->
                                item.status = FileStatus.CONVERTING
                                val ok = withContext(Dispatchers.IO) {
                                    val error = MusicConverter.convertWithError(
                                        item.path,
                                        output,
                                        outputFormat,
                                        bitrateKbps,
                                    )
                                    if (error == null) {
                                        true
                                    } else {
                                        item.message = error
                                        false
                                    }
                                }
                                item.status = if (ok) FileStatus.DONE else FileStatus.FAILED
                                if (ok) doneCount++ else failCount++
                                processed++
                                progress = processed.toFloat() / total
                            }
                            converting = false
                        }
                    },
                )
            } else if (page == 1) {
                key(sessionRevision) {
                    DownloadPage(
                        settings = settings,
                        onUpdateSettings = onUpdateSettings,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
            } else {
                key(sessionRevision) {
                    QqDownloadPage(
                        settings = settings,
                        onUpdateSettings = onUpdateSettings,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
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
        else -> "QQ 音乐下载" to "登录后选择歌单，下载歌曲"
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
            icon = Icons.Outlined.LibraryMusic,
            title = "网易云下载",
            detail = "扫码或浏览器",
            selected = page == 1,
            onClick = { onSelect(1) },
        )
        SidebarNavItem(
            icon = Icons.Outlined.MusicNote,
            title = "QQ 音乐下载",
            detail = "扫码或 Cookie",
            selected = page == 2,
            onClick = { onSelect(2) },
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
            .hoverable(interaction)
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
    Column(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (hovered) t.surfaceSoft else Color.Transparent)
            .hoverable(interaction)
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
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (hovered) t.surfaceSoft else Color.Transparent)
            .hoverable(interaction)
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
private fun UpdateBanner(release: ReleaseInfo, current: String, onDismiss: () -> Unit) {
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
                "当前版本 $current",
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = t.textSecondary,
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(t.primary)
                .clickable { openInBrowser(release.pageUrl) }
                .padding(horizontal = 13.dp, vertical = 7.dp),
        ) {
            Text("前往下载", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.onPrimary)
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 11.dp, vertical = 7.dp),
        ) {
            Text("忽略", fontSize = 12.5.sp, color = t.textSecondary)
        }
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
                    Icons.Outlined.OpenInNew,
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
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text("关闭", fontSize = 12.5.sp, color = t.textSecondary)
            }
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
                "输出目录、去重、输出格式与码率、窗口大小和登录状态都会恢复为初始值。",
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
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text("取消", fontSize = 13.sp, color = t.textSecondary)
                }
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

/** 品牌徽记:橙渐变圆角方块 + ♪ 音符字形 + 顶部内高光(与设计稿一致)。 */
@Composable
private fun LogoMark(side: Dp) {
    val t = cleanTokens()
    val corner = side * 14f / 48f
    Box(
        modifier = Modifier
            .size(side)
            .shadow(6.dp, RoundedCornerShape(corner), spotColor = t.primary.copy(alpha = 0.28f), ambientColor = t.primary.copy(alpha = 0.20f))
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(Color(0xFFFF8A3D), t.primary)))
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.45f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "♪",
            fontSize = (side.value * 27f / 48f).sp,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

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
    onRemove: (Int) -> Unit,
) {
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(14.dp)),
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
                Spacer(Modifier.weight(1f))
                Text(
                    "${files.size} 首 · ${humanSize(files.sumOf { File(it.path).length() })}",
                    fontSize = 12.5.sp,
                    color = t.textMuted,
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
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    itemsIndexed(files) { index, item ->
                        Column {
                            FileRow(
                                item = item,
                                converting = converting,
                                onRemove = { onRemove(index) },
                            )
                            if (index < files.lastIndex) {
                                Box(Modifier.fillMaxWidth().height(1.dp).background(t.rowDivider))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
//  文件行
// ============================================================

@Composable
private fun FileRow(item: FileItem, converting: Boolean, onRemove: () -> Unit) {
    val t = cleanTokens()
    val ext = item.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable(enabled = !converting, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "移除",
                tint = if (converting) t.textMuted.copy(alpha = 0.4f) else t.textMuted,
                modifier = Modifier.size(17.dp),
            )
        }
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
        FileStatus.FAILED -> Triple("失败", t.errorSoft, t.error)
        FileStatus.DUPLICATE -> Triple("重复", t.dupBg, t.dupFg)
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
    converting: Boolean,
    progress: Float,
    doneCount: Int,
    failCount: Int,
    totalCount: Int,
) {
    val t = cleanTokens()
    Column(
        modifier = Modifier.width(300.dp),
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
        RailLabel("输出格式")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(
                text = "原始格式",
                selected = outputFormat == OutputFormat.ORIGINAL,
                enabled = !converting,
                onClick = { onOutputFormatChange(OutputFormat.ORIGINAL) },
            )
            ChoiceChip(
                text = "MP3",
                selected = outputFormat == OutputFormat.MP3,
                enabled = !converting,
                onClick = { onOutputFormatChange(OutputFormat.MP3) },
            )
        }
        if (outputFormat == OutputFormat.MP3) {
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
        RailLabel("进度")
        if (totalCount > 0) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GradientProgress(
                    modifier = Modifier.fillMaxWidth().height(10.dp),
                    progress = progress,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "成功 $doneCount · 失败 $failCount",
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
}

@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) t.primarySoft else t.surfaceSoft)
            .border(1.dp, if (selected) t.primary.copy(alpha = 0.5f) else t.border, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (!enabled) t.textMuted else if (selected) t.primary else t.textSecondary,
        )
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
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(t.surfaceSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = t.textSecondary,
        )
    }
}

// ============================================================
//  底部
// ============================================================

@Composable
private fun Footer(
    converting: Boolean,
    convertingCount: Int,
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
            if (converting) "转换中 $convertingCount 首 · 待处理 $pendingCount 首"
            else if (pendingCount > 0) "待处理 $pendingCount 首"
            else "已就绪",
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
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(t.surface)
            .border(1.dp, t.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, contentDescription = null, tint = t.textSecondary, modifier = Modifier.size(15.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.text)
    }
}

/** 设计稿同款小开关: 40x22 胶囊, 白色圆钮。 */
@Composable
private fun CleanSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val t = cleanTokens()
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (checked) t.primary else t.surfaceSoft)
            .border(1.dp, if (checked) Color.Transparent else t.border, RoundedCornerShape(11.dp))
            .clickable(enabled = enabled, onClick = { onChange(!checked) })
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                .shadow(if (checked) 1.dp else 0.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
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

private fun dedupFiles(files: List<FileItem>): List<FileItem> {
    val ordered = files.sortedBy { if (it.name.matches(Regex(".*\\(\\d+\\).*\\.[a-zA-Z0-9]+$"))) 1 else 0 }
    val seen = HashSet<String>()
    val unique = mutableListOf<FileItem>()
    for (item in ordered) {
        val hash = try {
            MusicConverter.audioSha256(item.path)
        } catch (e: Exception) {
            item.status = FileStatus.FAILED
            item.message = "计算音频哈希失败: ${e.message}"
            continue
        } ?: continue
        if (seen.add(hash)) {
            unique.add(item)
        } else {
            item.status = FileStatus.DUPLICATE
        }
    }
    return unique
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
