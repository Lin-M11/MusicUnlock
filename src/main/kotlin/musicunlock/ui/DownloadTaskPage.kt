package musicunlock.ui

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import musicunlock.online.DownloadTaskManager
import musicunlock.online.DownloadTaskRecord
import musicunlock.online.DownloadTaskState
import musicunlock.service.ConversionTaskManager
import musicunlock.service.ConversionTaskRecord
import musicunlock.service.ConversionTaskState
import java.awt.Desktop
import java.io.File
import java.util.Locale

private enum class TaskFilter { ALL, ACTIVE, FAILED, COMPLETED }

@Composable
internal fun DownloadTaskPage(
    downloadManager: DownloadTaskManager,
    conversionManager: ConversionTaskManager,
    modifier: Modifier = Modifier,
) {
    val downloadTasks by downloadManager.tasks.collectAsState()
    val conversionTasks by conversionManager.tasks.collectAsState()
    val t = cleanTokens()
    val listState = rememberLazyListState()
    var filter by remember { mutableStateOf(TaskFilter.ALL) }

    val filteredConversions = conversionTasks.filter { it.matches(filter) }.sortedByDescending { it.createdAt }
    val filteredDownloads = downloadTasks.filter { it.matches(filter) }
        .sortedWith(compareBy<DownloadTaskRecord>({ it.playlistName.orEmpty() }, { it.createdAt }))
    val activeConversions = conversionTasks.count { !it.isTerminal }
    val activeDownloads = downloadTasks.count { !it.isTerminal }
    val failedConversions = conversionTasks.count { it.state == ConversionTaskState.FAILED }
    val failedDownloads = downloadTasks.count { it.state == DownloadTaskState.FAILED }
    val completedCount = conversionTasks.count { it.state == ConversionTaskState.COMPLETED } +
        downloadTasks.count { it.state in setOf(DownloadTaskState.COMPLETED, DownloadTaskState.SKIPPED) }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${activeConversions + activeDownloads} 个进行中 · $completedCount 个已完成 · ${failedConversions + failedDownloads} 个失败",
                fontSize = 13.sp,
                color = t.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            TaskAction("全部暂停", enabled = activeConversions + activeDownloads > 0, primary = true) {
                conversionManager.pauseAll()
                downloadManager.pauseAll()
            }
            TaskAction("全部继续", enabled = conversionTasks.any { it.canResume } || downloadTasks.any { it.canResume }, primary = true) {
                conversionManager.resumeAll()
                downloadManager.resumeAll()
            }
            TaskAction("重试失败", enabled = failedConversions + failedDownloads > 0, danger = true) {
                conversionManager.retryAllFailed()
                downloadManager.retryAllFailed()
            }
            TaskAction("清理已完成", enabled = completedCount > 0) {
                conversionManager.clearCompleted()
                downloadManager.clearCompleted()
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TaskFilter.entries.forEach { choice ->
                AppChoiceChip(
                    text = choice.displayName(),
                    selected = filter == choice,
                    onClick = { filter = choice },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("${filteredConversions.size + filteredDownloads.size} 条记录", fontSize = 11.5.sp, color = t.textMuted)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(UiMetrics.CardRadius))
                .background(t.surface)
                .border(1.dp, t.cardBorder, RoundedCornerShape(UiMetrics.CardRadius)),
        ) {
            if (filteredConversions.isEmpty() && filteredDownloads.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AppEmptyIcon(Icons.Outlined.Download)
                        Spacer(Modifier.height(12.dp))
                        Text("当前筛选下没有任务", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                        Spacer(Modifier.height(4.dp))
                        Text("本地转换与在线下载的任务会统一显示在这里", fontSize = 12.sp, color = t.textMuted)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        if (filteredConversions.isNotEmpty()) {
                            item { TaskSectionHeader("本地转换", filteredConversions.size) }
                            items(filteredConversions, key = { it.id }) { task ->
                                ConversionTaskRow(task, conversionManager)
                                Box(Modifier.fillMaxWidth().height(UiMetrics.Hairline).background(t.rowDivider))
                            }
                        }
                        if (filteredDownloads.isNotEmpty()) {
                            item { TaskSectionHeader("在线下载", filteredDownloads.size) }
                            items(filteredDownloads, key = { it.id }) { task ->
                                DownloadTaskRow(task, downloadManager)
                                Box(Modifier.fillMaxWidth().height(UiMetrics.Hairline).background(t.rowDivider))
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

@Composable
private fun TaskSectionHeader(title: String, count: Int) {
    val t = cleanTokens()
    Row(
        modifier = Modifier.fillMaxWidth().background(t.surfaceSoft).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.text)
        Spacer(Modifier.width(8.dp))
        Text("$count", fontSize = 11.sp, color = t.textMuted)
    }
}

@Composable
private fun ConversionTaskRow(task: ConversionTaskRecord, manager: ConversionTaskManager) {
    val t = cleanTokens()
    val stateText = task.state.displayName()
    val stateColor = when (task.state) {
        ConversionTaskState.COMPLETED -> t.success
        ConversionTaskState.FAILED -> t.error
        ConversionTaskState.RUNNING -> t.primary
        else -> t.textSecondary
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(File(task.inputPath).name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(task.stage?.displayName(), task.outputFormat.name, stateText).distinct().joinToString(" · "),
                    fontSize = 11.5.sp,
                    color = stateColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("本地", modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(t.surfaceSoft).padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.5.sp, color = t.textSecondary)
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (task.canPause) RowAction(Icons.Outlined.Pause, "暂停") { manager.pause(task.id) }
                if (task.canResume || task.state == ConversionTaskState.FAILED) RowAction(Icons.Outlined.PlayArrow, "继续") { manager.resume(task.id) }
                if (task.canRetry) RowAction(Icons.Outlined.Refresh, "重试") { manager.retry(task.id) }
                if (!task.isTerminal) RowAction(Icons.Outlined.Cancel, "取消") { manager.cancel(task.id) }
                task.outputPath?.let { path ->
                    RowAction(Icons.Outlined.FolderOpen, "打开输出目录") { revealInFolder(File(path)) }
                }
                if (task.isTerminal) RowAction(Icons.Outlined.Delete, "移除") { manager.remove(task.id) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.surfaceSoft)) {
            Box(
                Modifier
                    .fillMaxWidth(task.progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(if (task.state == ConversionTaskState.FAILED) t.error else if (task.state == ConversionTaskState.COMPLETED) t.success else t.primary),
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(task.message.orEmpty(), fontSize = 11.sp, color = if (task.state == ConversionTaskState.FAILED) t.error else t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("${(task.progress.coerceIn(0f, 1f) * 100).toInt()}%", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = t.textMuted)
        }
    }
}

@Composable
private fun DownloadTaskRow(task: DownloadTaskRecord, manager: DownloadTaskManager) {
    val t = cleanTokens()
    val stateText = task.state.displayName()
    val stateColor = when (task.state) {
        DownloadTaskState.COMPLETED -> t.success
        DownloadTaskState.FAILED -> t.error
        DownloadTaskState.DOWNLOADING, DownloadTaskState.TRANSCODING, DownloadTaskState.TAGGING -> t.primary
        else -> t.textSecondary
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.song.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(task.song.artistText.ifBlank { null }, task.playlistName, stateText).joinToString(" · "),
                    fontSize = 11.5.sp,
                    color = stateColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(platformLabel(task.platform), modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(t.surfaceSoft).padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.5.sp, color = t.textSecondary)
            Spacer(Modifier.width(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (task.canPause) RowAction(Icons.Outlined.Pause, "暂停") { manager.pause(task.id) }
                if (task.canResume) RowAction(Icons.Outlined.PlayArrow, "继续") { manager.resume(task.id) }
                if (task.canRetry) RowAction(Icons.Outlined.Refresh, "重试") { manager.retry(task.id) }
                if (!task.isTerminal) RowAction(Icons.Outlined.Cancel, "取消") { manager.cancel(task.id) }
                task.outputPath?.let { path ->
                    RowAction(Icons.Outlined.FolderOpen, "打开输出目录") { revealInFolder(File(path)) }
                }
                if (task.isTerminal) RowAction(Icons.Outlined.Delete, "移除") { manager.remove(task.id) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.surfaceSoft)) {
            Box(
                Modifier
                    .fillMaxWidth(task.progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(
                        when (task.state) {
                            DownloadTaskState.COMPLETED -> t.success
                            DownloadTaskState.FAILED -> t.error
                            DownloadTaskState.PAUSED, DownloadTaskState.CANCELLED, DownloadTaskState.SKIPPED -> t.textMuted
                            else -> t.primary
                        },
                    ),
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(task.message.orEmpty(), fontSize = 11.sp, color = if (task.state == DownloadTaskState.FAILED) t.error else t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (task.state == DownloadTaskState.FAILED || task.state == DownloadTaskState.CANCELLED) {
                AppTextAction("320k 重试", onClick = { manager.retryWithQuality(task.id, musicunlock.settings.QualityStrategy.MP3_320) }, primary = true)
                AppTextAction("128k 重试", onClick = { manager.retryWithQuality(task.id, musicunlock.settings.QualityStrategy.SMALLEST) })
            }
            Spacer(Modifier.width(8.dp))
            Text(buildProgressText(task), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = t.textMuted)
        }
    }
}

@Composable
private fun TaskAction(text: String, enabled: Boolean, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    AppTextAction(text = text, onClick = onClick, enabled = enabled, primary = primary, danger = danger)
}

@Composable
private fun RowAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    AppIconButton(
        icon = icon,
        contentDescription = description,
        onClick = onClick,
        danger = description == "取消" || description == "移除",
        size = UiMetrics.CompactIconButtonSize,
    )
}

private fun ConversionTaskRecord.matches(filter: TaskFilter): Boolean = when (filter) {
    TaskFilter.ALL -> true
    TaskFilter.ACTIVE -> !isTerminal
    TaskFilter.FAILED -> state == ConversionTaskState.FAILED || state == ConversionTaskState.CANCELLED
    TaskFilter.COMPLETED -> state == ConversionTaskState.COMPLETED || state == ConversionTaskState.SKIPPED || state == ConversionTaskState.DUPLICATE
}

private fun DownloadTaskRecord.matches(filter: TaskFilter): Boolean = when (filter) {
    TaskFilter.ALL -> true
    TaskFilter.ACTIVE -> !isTerminal
    TaskFilter.FAILED -> state == DownloadTaskState.FAILED || state == DownloadTaskState.CANCELLED
    TaskFilter.COMPLETED -> state == DownloadTaskState.COMPLETED || state == DownloadTaskState.SKIPPED
}

private fun TaskFilter.displayName(): String = when (this) {
    TaskFilter.ALL -> "全部"
    TaskFilter.ACTIVE -> "进行中"
    TaskFilter.FAILED -> "失败"
    TaskFilter.COMPLETED -> "已完成"
}

private fun ConversionTaskState.displayName(): String = when (this) {
    ConversionTaskState.QUEUED -> "等待中"
    ConversionTaskState.RUNNING -> "处理中"
    ConversionTaskState.COMPLETED -> "已完成"
    ConversionTaskState.FAILED -> "失败"
    ConversionTaskState.PAUSED -> "已暂停"
    ConversionTaskState.CANCELLED -> "已取消"
    ConversionTaskState.SKIPPED -> "已跳过"
    ConversionTaskState.DUPLICATE -> "重复"
}

private fun musicunlock.service.ConversionStage.displayName(): String = when (this) {
    musicunlock.service.ConversionStage.VALIDATING -> "检查"
    musicunlock.service.ConversionStage.DECODING -> "解密"
    musicunlock.service.ConversionStage.TRANSCODING -> "转码"
    musicunlock.service.ConversionStage.TAGGING -> "标签"
    musicunlock.service.ConversionStage.WRITING -> "写入"
    musicunlock.service.ConversionStage.COMPLETED -> "完成"
}

private fun DownloadTaskState.displayName(): String = when (this) {
    DownloadTaskState.QUEUED -> "等待中"
    DownloadTaskState.DOWNLOADING -> "下载中"
    DownloadTaskState.TRANSCODING -> "转码中"
    DownloadTaskState.TAGGING -> "写标签"
    DownloadTaskState.COMPLETED -> "已完成"
    DownloadTaskState.FAILED -> "失败"
    DownloadTaskState.PAUSED -> "已暂停"
    DownloadTaskState.CANCELLED -> "已取消"
    DownloadTaskState.SKIPPED -> "已跳过"
}

private fun platformLabel(platform: String): String = when (platform) {
    "netease" -> "网易云"
    "qq" -> "QQ 音乐"
    "kugou" -> "酷狗"
    "kuwo" -> "酷我"
    else -> platform
}

private fun buildProgressText(task: DownloadTaskRecord): String {
    val percent = "${(task.progress.coerceIn(0f, 1f) * 100).toInt()}%"
    val speed = if (task.speedBytesPerSecond > 0) "  ${humanSpeed(task.speedBytesPerSecond)}" else ""
    val eta = task.etaSeconds?.takeIf { it > 0 }?.let { "  剩余 ${formatEta(it)}" }.orEmpty()
    return percent + speed + eta
}

private fun humanSpeed(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MB/s", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.ROOT, "%.0f KB/s", bytes / (1L shl 10).toDouble())
    else -> "$bytes B/s"
}

private fun formatEta(seconds: Long): String {
    val minutes = seconds / 60
    val rest = seconds % 60
    return if (minutes > 0) "${minutes}分${rest}秒" else "${rest}秒"
}

private fun revealInFolder(file: File) {
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file.parentFile ?: file)
        }
    }
}
