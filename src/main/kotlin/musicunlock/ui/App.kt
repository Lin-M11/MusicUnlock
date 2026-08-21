package musicunlock.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pending
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.graphics.vector.ImageVector
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

@Composable
fun MusicUnlockApp() {
    var dark by remember { mutableStateOf(true) }
    MusicUnlockTheme(darkTheme = dark) {
        MainScreen(dark = dark, onToggleDark = { dark = !dark })
    }
}

fun showWindow() {
    singleWindowApplication(
        title = "MusicUnlock",
        state = WindowState(width = 960.dp, height = 700.dp),
    ) {
        MusicUnlockApp()
    }
}

@Composable
fun MainScreen(dark: Boolean, onToggleDark: () -> Unit) {
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<FileItem>() }
    var outputDir by remember { mutableStateOf(File("output").absolutePath) }
    var dedup by remember { mutableStateOf(false) }
    var converting by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var doneCount by remember { mutableStateOf(0) }
    var failCount by remember { mutableStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ---- 顶部:标题 + 主题切换 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.MusicNote, contentDescription = "MusicUnlock", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("MusicUnlock", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "多平台加密音乐格式转换 · ${Formats.supportedExtensions().size} 种格式",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onToggleDark) {
                    Icon(
                        if (dark) Icons.Outlined.DarkMode else Icons.Filled.LightMode,
                        contentDescription = if (dark) "切换到浅色模式" else "切换到深色模式",
                    )
                }
            }

            // ---- 拖拽/选择区 ----
            DropZone(
                files = files,
                onAddFiles = {
                    val selected = FileDialogs.pickFiles(
                        "选择加密音乐文件",
                        Formats.supportedExtensions(),
                    )
                    addPaths(files, selected.map { it.absolutePath })
                },
                onAddFolder = {
                    FileDialogs.pickFolder("选择文件夹")?.let { addPaths(files, listOf(it.absolutePath)) }
                },
            )

            // ---- 输出目录 ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                OutlinedTextField(
                    value = outputDir,
                    onValueChange = { outputDir = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("输出目录") },
                    singleLine = true,
                    enabled = !converting,
                )
                OutlinedButton(onClick = { FileDialogs.pickFolder("选择输出目录")?.let { outputDir = it.absolutePath } }) {
                    Text("浏览")
                }
                IconButton(onClick = { runCatching { Desktop.getDesktop().open(File(outputDir)) } }) {
                    Icon(Icons.Outlined.OpenInNew, contentDescription = "打开输出目录")
                }
            }

            // ---- 文件列表 ----
            Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (files.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "还没有添加文件\n点击上方区域选择或拖拽加密音乐文件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(vertical = 4.dp)) {
                        itemsIndexed(files) { index, item ->
                            FileRow(item, onRemove = {
                                if (!converting) files.removeAt(index)
                            })
                        }
                    }
                }
            }

            // ---- 底部:去重 + 转换 ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = dedup, onCheckedChange = { dedup = it }, enabled = !converting)
                    Text("按解密后音频内容去重", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.weight(1f))
                if (converting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = {
                        if (files.isEmpty()) return@Button
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
                ) {
                    Text(if (converting) "转换中…" else "开始转换")
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DropZone(files: SnapshotStateList<FileItem>, onAddFiles: () -> Unit, onAddFolder: () -> Unit) {
    var hovering by remember { mutableStateOf(false) }
    val borderColor = if (hovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(16.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .border(2.dp, borderColor, shape)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = object : DragAndDropTarget {
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val paths = event.filePaths()
                        if (paths.isNotEmpty()) {
                            addPaths(files, paths)
                        }
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
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Outlined.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = if (hovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "拖拽加密音乐文件/文件夹到这里",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (hovering) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddFiles) { Text("添加文件") }
                    OutlinedButton(onClick = onAddFolder) { Text("添加文件夹") }
                }
            }
        }
    }
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

@Composable
private fun FileRow(item: FileItem, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusIcon(item.status)
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            item.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
            }
        }
        StatusText(item.status)
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.DeleteSweep, contentDescription = "移除", modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatusIcon(status: FileStatus) {
    val (icon: ImageVector, tint) = when (status) {
        FileStatus.DONE -> Icons.Filled.CheckCircle to MaterialTheme.colorScheme.primary
        FileStatus.FAILED -> Icons.Filled.Error to MaterialTheme.colorScheme.error
        FileStatus.DUPLICATE -> Icons.Outlined.DeleteSweep to MaterialTheme.colorScheme.outline
        FileStatus.CONVERTING -> Icons.Filled.Pending to MaterialTheme.colorScheme.tertiary
        FileStatus.PENDING -> Icons.Filled.Pending to MaterialTheme.colorScheme.outline
    }
    Icon(icon, contentDescription = status.name, tint = tint, modifier = Modifier.size(22.dp))
}

@Composable
private fun StatusText(status: FileStatus) {
    val text = when (status) {
        FileStatus.PENDING -> "等待中"
        FileStatus.CONVERTING -> "转换中"
        FileStatus.DONE -> "已完成"
        FileStatus.FAILED -> "失败"
        FileStatus.DUPLICATE -> "重复"
    }
    val color = when (status) {
        FileStatus.DONE -> MaterialTheme.colorScheme.primary
        FileStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
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
