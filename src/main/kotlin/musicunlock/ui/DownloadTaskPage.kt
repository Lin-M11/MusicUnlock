package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import java.util.Locale

@Composable
internal fun DownloadTaskPage(
    manager: DownloadTaskManager,
    modifier: Modifier = Modifier,
) {
    val tasks by manager.tasks.collectAsState()
    val t = cleanTokens()
    val active = tasks.count { !it.isTerminal }
    val failed = tasks.count { it.state == DownloadTaskState.FAILED }
    val completed = tasks.count { it.state == DownloadTaskState.COMPLETED }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$active 个进行中 · $completed 个已完成 · $failed 个失败", fontSize = 13.sp, color = t.textSecondary)
            Spacer(Modifier.weight(1f))
            TaskAction("全部暂停", enabled = active > 0) { manager.pauseAll() }
            TaskAction("全部继续", enabled = tasks.any { it.canResume }) { manager.resumeAll() }
            TaskAction("重试失败", enabled = failed > 0) { manager.retryAllFailed() }
            TaskAction("清理已完成", enabled = completed > 0) { manager.clearCompleted() }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(t.surface)
                .border(1.dp, t.cardBorder, RoundedCornerShape(14.dp)),
        ) {
            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Download, contentDescription = null, tint = t.textMuted, modifier = Modifier.size(38.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("暂无在线下载任务", fontSize = 14.sp, color = t.textSecondary)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(tasks, key = { it.id }) { task ->
                        DownloadTaskRow(task, manager)
                        Box(Modifier.fillMaxWidth().height(1.dp).background(t.rowDivider))
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(task: DownloadTaskRecord, manager: DownloadTaskManager) {
    val t = cleanTokens()
    val stateText = when (task.state) {
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
            Text(task.platform, fontSize = 11.5.sp, color = t.textMuted)
            Spacer(Modifier.width(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (task.canPause) {
                    RowAction(Icons.Outlined.Pause, "暂停") { manager.pause(task.id) }
                }
                if (task.canResume || task.state == DownloadTaskState.FAILED) {
                    RowAction(Icons.Outlined.PlayArrow, "继续或重试") { manager.resume(task.id) }
                }
                if (task.state == DownloadTaskState.FAILED || task.state == DownloadTaskState.CANCELLED) {
                    RowAction(Icons.Outlined.Refresh, "重试") { manager.retry(task.id) }
                }
                if (task.canCancel) {
                    RowAction(Icons.Outlined.Cancel, "取消") { manager.cancel(task.id) }
                }
                if (task.isTerminal) {
                    RowAction(Icons.Outlined.Delete, "移除") { manager.remove(task.id) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.surfaceSoft)) {
            Box(
                Modifier
                    .fillMaxWidth(task.progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(if (task.state == DownloadTaskState.FAILED) t.error else t.primary),
            )
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                task.message.orEmpty(),
                fontSize = 11.sp,
                color = if (task.state == DownloadTaskState.FAILED) t.error else t.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                buildProgressText(task),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = t.textMuted,
            )
        }
    }
}

@Composable
private fun TaskAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val t = cleanTokens()
    Text(
        text,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp),
        fontSize = 12.5.sp,
        color = if (enabled) t.primary else t.textMuted,
    )
}

@Composable
private fun RowAction(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val t = cleanTokens()
    Box(
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = t.textSecondary, modifier = Modifier.size(16.dp))
    }
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
