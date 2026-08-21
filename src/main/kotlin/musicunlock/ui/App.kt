package musicunlock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** 单个文件的转换状态。 */
enum class FileStatus { PENDING, CONVERTING, DONE, FAILED, DUPLICATE }

class FileItem(val path: String, val name: String) {
    var status by mutableStateOf(FileStatus.PENDING)
    var message by mutableStateOf<String?>(null)
}

// ============================================================
//  新拟态(Neumorphism)基础组件 —— 凸起 / 内凹 / 按下反馈
// ============================================================

/** 凸起容器:同底色 + 双阴影(亮高光 + 暗影), 可选内凹渐变。 */
@Composable
fun NeumorphicBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    pressed: Boolean = false,
    inset: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val bg = MaterialTheme.colorScheme.surface
    val t = neumorphicTokens()
    val elev = if (pressed) 2.dp else 9.dp
    val base = Modifier
        .shadow(elev, shape, ambientColor = t.shadow, spotColor = t.shadow)
        .shadow(elev, shape, ambientColor = t.highlight, spotColor = t.highlight)
        .background(bg, shape)
    Box(
        modifier = modifier.then(if (inset) base.neumorphicInset(shape, t) else base),
        content = content,
    )
}

/** 内凹渐变:顶部暗、底部亮, 营造凹陷感。 */
private fun Modifier.neumorphicInset(shape: Shape, t: NeumorphicTokens): Modifier = this
    .clip(shape)
    .drawWithCache {
        onDrawBehind {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(t.insetTop, Color.Transparent, t.insetBottom),
                    startY = 0f,
                    endY = size.height,
                ),
                size = size,
            )
        }
    }

/** 新拟态按钮:凸起常态, 按下时凹陷并收窄阴影; primary=橙色主操作。 */
@Composable
fun NeumorphicButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    shape: Shape = RoundedCornerShape(14.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val t = neumorphicTokens()
    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val elev = if (pressed) 2.dp else 8.dp
    Box(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .shadow(elev, shape, ambientColor = t.shadow, spotColor = t.shadow)
            .shadow(elev, shape, ambientColor = t.highlight, spotColor = t.highlight)
            .background(bg, shape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .then(if (pressed && !primary) Modifier.neumorphicInset(shape, t) else Modifier)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = { content() },
        )
    }
}

/** 新拟态圆形图标按钮(主题切换等)。 */
@Composable
fun NeumorphicIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String,
    icon: ImageVector,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val t = neumorphicTokens()
    val elev = if (pressed) 2.dp else 8.dp
    Box(
        modifier = modifier
            .size(44.dp)
            .shadow(elev, CircleShape, ambientColor = t.shadow, spotColor = t.shadow)
            .shadow(elev, CircleShape, ambientColor = t.highlight, spotColor = t.highlight)
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .then(if (pressed) Modifier.neumorphicInset(CircleShape, t) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** 内凹单行文本输入框(输出目录)。 */
@Composable
fun NeumorphicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String,
    enabled: Boolean = true,
) {
    NeumorphicBox(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        inset = true,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 7.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
    }
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
        state = WindowState(width = 980.dp, height = 720.dp),
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

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // ---- 顶部:品牌 + 主题切换 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeumorphicBox(shape = CircleShape, modifier = Modifier.size(52.dp)) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp).align(Alignment.Center),
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        "MusicUnlock",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "多平台加密音乐格式转换 · ${Formats.supportedExtensions().size} 种格式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                NeumorphicIconButton(
                    onClick = onToggleDark,
                    contentDescription = if (dark) "切换到浅色模式" else "切换到深色模式",
                    icon = if (dark) Icons.Filled.LightMode else Icons.Outlined.DarkMode,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }

            // ---- 拖拽/选择区 ----
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

            // ---- 输出目录 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NeumorphicTextField(
                    value = outputDir,
                    onValueChange = { outputDir = it },
                    modifier = Modifier.weight(1f),
                    label = "输出目录",
                    enabled = !converting,
                )
                NeumorphicButton(onClick = {
                    FileDialogs.pickFolder("选择输出目录")?.let { outputDir = it.absolutePath }
                }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("浏览", style = MaterialTheme.typography.labelLarge)
                }
                NeumorphicIconButton(
                    onClick = { runCatching { Desktop.getDesktop().open(File(outputDir)) } },
                    contentDescription = "打开输出目录",
                    icon = Icons.AutoMirrored.Outlined.OpenInNew,
                )
            }

            // ---- 文件列表(内凹容器) ----
            NeumorphicBox(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                inset = true,
            ) {
                if (files.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.LibraryMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(40.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "还没有添加文件\n拖拽或点击上方区域选择加密音乐文件",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(files) { index, item ->
                            FileRow(item, onRemove = {
                                if (!converting) files.removeAt(index)
                            })
                        }
                    }
                }
            }

            // ---- 底部:去重 + 进度 + 转换 ----
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = dedup,
                        onCheckedChange = { dedup = it },
                        enabled = !converting,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "按解密后音频内容去重",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (converting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                if (doneCount > 0 || failCount > 0) {
                    Text(
                        "成功 $doneCount · 失败 $failCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                NeumorphicButton(
                    onClick = {
                        if (files.isEmpty()) return@NeumorphicButton
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
                    enabled = !converting && files.isNotEmpty(),
                    primary = true,
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        if (converting) "转换中…" else "开始转换",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 默认输出目录:优先使用用户主目录下的 Music/MusicUnlock,
 * 避免打包应用以 / 为工作目录时无法写入相对路径 "output"。
 */
private fun defaultOutputDir(): String {
    val home = System.getProperty("user.home")
    return if (!home.isNullOrBlank()) {
        File(home, "Music/MusicUnlock").absolutePath
    } else {
        File("output").absolutePath
    }
}

// ============================================================
//  拖拽区
// ============================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DropZone(files: SnapshotStateList<FileItem>, onAddFiles: () -> Unit, onAddFolder: () -> Unit) {
    var hovering by remember { mutableStateOf(false) }
    val borderColor = if (hovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    NeumorphicBox(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
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
        shape = RoundedCornerShape(22.dp),
        pressed = hovering,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
                .border(1.dp, borderColor.copy(alpha = if (hovering) 1f else 0.7f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Icon(
                    Icons.Outlined.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(42.dp),
                    tint = if (hovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "拖拽加密音乐文件 / 文件夹到这里",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (hovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeumorphicButton(onClick = onAddFiles) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("添加文件", style = MaterialTheme.typography.labelLarge)
                        }
                        NeumorphicButton(onClick = onAddFolder) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("添加文件夹", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
//  文件列表
// ============================================================

@Composable
private fun FileRow(item: FileItem, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusIcon(item.status)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.message?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        StatusChip(item.status)
        NeumorphicIconButton(
            onClick = onRemove,
            contentDescription = "移除",
            icon = Icons.Outlined.DeleteSweep,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusIcon(status: FileStatus) {
    val (icon: ImageVector, tint) = when (status) {
        FileStatus.DONE -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.secondary
        FileStatus.FAILED -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        FileStatus.DUPLICATE -> Icons.Outlined.DeleteSweep to MaterialTheme.colorScheme.onSurfaceVariant
        FileStatus.CONVERTING -> Icons.Filled.Pending to MaterialTheme.colorScheme.primary
        FileStatus.PENDING -> Icons.Filled.Pending to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = status.name, tint = tint, modifier = Modifier.size(22.dp))
}

@Composable
private fun StatusChip(status: FileStatus) {
    val (text, bg, fg) = when (status) {
        FileStatus.PENDING -> Triple("等待中", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
        FileStatus.CONVERTING -> Triple("转换中", MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), MaterialTheme.colorScheme.primary)
        FileStatus.DONE -> Triple("已完成", MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f), MaterialTheme.colorScheme.secondary)
        FileStatus.FAILED -> Triple("失败", MaterialTheme.colorScheme.error.copy(alpha = 0.14f), MaterialTheme.colorScheme.error)
        FileStatus.DUPLICATE -> Triple("重复", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

// ============================================================
//  工具函数
// ============================================================

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
