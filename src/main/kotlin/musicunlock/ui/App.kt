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
 * FIRST VIEWPORT: 顶部品牌行(橙渐变 logo 徽记 + 摘要胶囊 + 主题切换) -> 左侧
 * 虚线拖拽区 + 转换队列卡片 -> 右侧 300dp 控制栏(输出目录 / 去重 / 进度 / 打开) ->
 * 底部状态与主转换按钮。
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.singleWindowApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.core.Formats
import musicunlock.service.MusicConverter
import java.awt.Desktop
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DropTargetDropEvent
import java.io.File
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
fun MusicUnlockApp() {
    var dark by remember { mutableStateOf(false) }
    MusicUnlockTheme(darkTheme = dark) {
        MainScreen(dark = dark, onToggleDark = { dark = !dark })
    }
}

fun showWindow() {
    singleWindowApplication(
        title = "MusicUnlock",
        state = WindowState(width = 1120.dp, height = 760.dp),
    ) {
        MusicUnlockApp()
    }
}

@Composable
fun MainScreen(dark: Boolean, onToggleDark: () -> Unit) {
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<FileItem>() }
    var outputDir by remember { mutableStateOf(defaultOutputDir()) }
    var dedup by remember { mutableStateOf(false) }
    var converting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var doneCount by remember { mutableStateOf(0) }
    var failCount by remember { mutableStateOf(0) }
    var page by remember { mutableStateOf(0) }

    val t = cleanTokens()
    val totalBytes = files.sumOf { File(it.path).length() }
    val convertingCount = files.count { it.status == FileStatus.CONVERTING }
    val pendingCount = files.count { it.status == FileStatus.PENDING }

    Column(
        modifier = Modifier.fillMaxSize().background(t.bg).padding(horizontal = 26.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---- 顶部:品牌 + 摘要 + 主题 ----
        HeaderRow(
            dark = dark,
            onToggleDark = onToggleDark,
            formatCount = Formats.supportedExtensions().size,
            totalSize = humanSize(totalBytes),
            totalCount = files.size,
        )

        // ---- 页签:格式转换 / 网易云下载 ----
        PageTabs(page = page, onSelect = { page = it })

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
                    outputDir = outputDir,
                    onOutputDirChange = { outputDir = it },
                    dedup = dedup,
                    onDedupChange = { dedup = it },
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
                    val targets = if (dedup) dedupFiles(files) else files.toList()
                    val output = outputDir
                    scope.launch {
                        val total = targets.size
                        var processed = 0
                        targets.forEachIndexed { idx, item ->
                            item.status = FileStatus.CONVERTING
                            val ok = withContext(Dispatchers.IO) {
                                val error = MusicConverter.convertWithError(item.path, output)
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
        } else {
            DownloadPage(modifier = Modifier.weight(1f).fillMaxWidth())
        }
    }
}

// ============================================================
//  页签
// ============================================================

@Composable
private fun PageTabs(page: Int, onSelect: (Int) -> Unit) {
    val t = cleanTokens()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(t.surface)
            .border(1.dp, t.border, RoundedCornerShape(12.dp))
            .padding(4.dp),
    ) {
        TabItem(modifier = Modifier.weight(1f), label = "格式转换", selected = page == 0) { onSelect(0) }
        TabItem(modifier = Modifier.weight(1f), label = "网易云下载", selected = page == 1) { onSelect(1) }
    }
}

@Composable
private fun TabItem(modifier: Modifier, label: String, selected: Boolean, onClick: () -> Unit) {
    val t = cleanTokens()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) t.primarySoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) t.primary else t.textSecondary,
        )
    }
}

// ============================================================
//  顶部
// ============================================================

@Composable
private fun HeaderRow(
    dark: Boolean,
    onToggleDark: () -> Unit,
    formatCount: Int,
    totalSize: String,
    totalCount: Int,
) {
    val t = cleanTokens()
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Logo mark:橙渐变圆角方块 + ♪ 音符字形 + 顶部内高光(与设计稿一致)
        Box(
            modifier = Modifier
                .size(48.dp)
                .shadow(6.dp, RoundedCornerShape(14.dp), spotColor = t.primary.copy(alpha = 0.28f), ambientColor = t.primary.copy(alpha = 0.20f))
                .clip(RoundedCornerShape(14.dp))
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
                fontSize = 27.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "MusicUnlock",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                color = t.text,
            )
            Text(
                "多平台加密音乐格式转换",
                fontSize = 12.5.sp,
                color = t.textSecondary,
            )
        }
        Spacer(Modifier.weight(1f))
        Pill(text = "支持 $formatCount 种格式", emphasize = false)
        Spacer(Modifier.width(10.dp))
        Pill(text = if (totalCount > 0) "待处理 $totalSize" else "暂无文件", emphasize = true)
        Spacer(Modifier.width(10.dp))
        // 主题切换
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(t.surface)
                .border(1.dp, t.border, RoundedCornerShape(12.dp))
                .clickable(onClick = onToggleDark),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (dark) Icons.Filled.LightMode else Icons.Outlined.DarkMode,
                contentDescription = if (dark) "切换到浅色模式" else "切换到深色模式",
                tint = t.textSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
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

/** 默认输出目录:优先使用用户主目录下的 Music/MusicUnlock。 */
private fun defaultOutputDir(): String {
    val home = System.getProperty("user.home")
    return if (!home.isNullOrBlank()) {
        File(home, "Music/MusicUnlock").absolutePath
    } else {
        File("output").absolutePath
    }
}

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
