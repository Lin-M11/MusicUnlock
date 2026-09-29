package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import musicunlock.library.DuplicateMatchKind
import musicunlock.library.LibraryCleanupPlan
import musicunlock.library.LibraryEntry
import java.io.File

@Composable
internal fun DuplicateReviewOverlay(
    plan: LibraryCleanupPlan,
    entriesByPath: Map<String, LibraryEntry>,
    allowLikelyDuplicates: Boolean,
    onAllowLikelyChange: (Boolean) -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
) {
    val t = cleanTokens()
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.44f)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClose,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(t.surface)
                .border(1.dp, t.cardBorder, RoundedCornerShape(20.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("重复文件审阅", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.text)
                    Text(
                        "精确重复 ${plan.exactTrackCount} 首 · 疑似重复 ${plan.likelyTrackCount} 首 · 可释放 ${humanBytes(plan.reclaimBytes)}",
                        fontSize = 11.5.sp,
                        color = t.textMuted,
                    )
                }
                if (plan.requiresConfirmation) {
                    AppChoiceChip(
                        text = "同时处理疑似重复",
                        selected = allowLikelyDuplicates,
                        onClick = { onAllowLikelyChange(!allowLikelyDuplicates) },
                    )
                }
                Spacer(Modifier.width(8.dp))
                AppTextAction("关闭", onClick = onClose, outlined = true)
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(plan.decisions) { index, decision ->
                    val keep = entriesByPath[decision.keepPath]
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(t.surfaceSoft)
                            .border(1.dp, t.border, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("重复组 ${index + 1}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.textSecondary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (decision.matchKind == DuplicateMatchKind.EXACT) "内容一致" else "疑似同一录音",
                                fontSize = 10.5.sp,
                                color = if (decision.matchKind == DuplicateMatchKind.EXACT) t.success else t.primary,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ComparisonCard(
                                label = "保留",
                                entry = keep,
                                path = decision.keepPath,
                                accent = t.success,
                                modifier = Modifier.weight(1f),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("移除 ${decision.removePaths.size} 个版本", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = t.error)
                                decision.removePaths.forEach { path ->
                                    ComparisonCard(
                                        label = "移除",
                                        entry = entriesByPath[path],
                                        path = path,
                                        accent = t.error,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                        Text(decision.reason, fontSize = 11.sp, color = t.textMuted)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("文件会先移入可撤销回收站", fontSize = 11.sp, color = t.textMuted)
                Spacer(Modifier.weight(1f))
                AppTextAction("取消", onClick = onClose, outlined = true)
                Spacer(Modifier.width(8.dp))
                AppTextAction("确认移入回收站", onClick = onApply, filled = true, danger = true)
            }
        }
    }
}

@Composable
private fun ComparisonCard(
    label: String,
    entry: LibraryEntry?,
    path: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val t = cleanTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(t.surface)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = accent)
        Text(
            entry?.title ?: File(path).nameWithoutExtension,
            fontSize = 12.sp,
            color = t.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOfNotNull(
                entry?.artist,
                entry?.format ?: File(path).extension.uppercase(),
                entry?.bitRateKbps?.let { "${it}k" },
                entry?.size?.let(::humanBytes),
                if (entry?.hasCover == true) "有封面" else "缺封面",
                if (entry?.hasLyrics == true) "有歌词" else "缺歌词",
            ).joinToString(" · "),
            fontSize = 10.sp,
            color = t.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
