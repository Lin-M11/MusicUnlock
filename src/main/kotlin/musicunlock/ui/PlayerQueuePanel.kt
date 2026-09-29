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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Subtitles
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import musicunlock.player.AudioPlayerService
import musicunlock.player.PlayerTrack

private enum class QueueTab { UPCOMING, HISTORY }

@Composable
internal fun PlayerQueueOverlay(
    player: AudioPlayerService,
    onClose: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onSaveQueue: (List<PlayerTrack>) -> Unit,
    onToggleFavorite: (PlayerTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by player.state.collectAsState()
    val t = cleanTokens()
    var tab by remember { mutableStateOf(QueueTab.UPCOMING) }
    Box(
        modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.22f)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClose,
        ),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Column(
            modifier = Modifier
                .padding(end = 22.dp, bottom = 100.dp)
                .width(450.dp)
                .heightIn(min = 360.dp, max = 620.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.surface)
                .border(1.dp, t.cardBorder, RoundedCornerShape(18.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("播放队列", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = t.text)
                    Text(
                        "${snapshot.queue.size} 首 · 接下来 ${(snapshot.queue.size - snapshot.queueIndex - 1).coerceAtLeast(0)} 首",
                        fontSize = 11.sp,
                        color = t.textMuted,
                    )
                }
                AppIconButton(Icons.Outlined.Subtitles, "打开正在播放", onOpenNowPlaying, size = 30.dp)
                Spacer(Modifier.width(6.dp))
                AppTextAction("关闭", onClick = onClose, outlined = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                AppChoiceChip(text = "当前队列", selected = tab == QueueTab.UPCOMING, onClick = { tab = QueueTab.UPCOMING })
                AppChoiceChip(text = "播放历史", selected = tab == QueueTab.HISTORY, onClick = { tab = QueueTab.HISTORY })
                Spacer(Modifier.weight(1f))
                if (tab == QueueTab.UPCOMING) {
                    AppTextAction("清空待播", onClick = player::clearUpcoming, outlined = true)
                } else {
                    AppTextAction("清空历史", onClick = player::clearHistory, outlined = true)
                }
            }
            if (tab == QueueTab.UPCOMING) {
                if (snapshot.queue.isEmpty()) {
                    QueueEmpty("播放队列为空")
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(snapshot.queue) { index, track ->
                            QueueTrackRow(
                                player = player,
                                track = track,
                                index = index,
                                current = index == snapshot.queueIndex,
                                onToggleFavorite = onToggleFavorite,
                            )
                        }
                    }
                }
            } else {
                if (snapshot.history.isEmpty()) {
                    QueueEmpty("还没有播放历史")
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(snapshot.history.reversed()) { index, track ->
                            HistoryTrackRow(
                                track = track,
                                recent = index == 0,
                                onToggleFavorite = onToggleFavorite,
                                onAddToQueue = { player.addToQueue(listOf(it)) },
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppTextAction(
                    text = "保存为歌单",
                    onClick = { onSaveQueue(snapshot.queue) },
                    filled = true,
                    enabled = snapshot.queue.isNotEmpty(),
                )
                Spacer(Modifier.weight(1f))
                Text("拖动排序将在后续版本支持", fontSize = 10.5.sp, color = t.textMuted)
            }
        }
    }
}

@Composable
private fun QueueTrackRow(
    player: AudioPlayerService,
    track: PlayerTrack,
    index: Int,
    current: Boolean,
    onToggleFavorite: (PlayerTrack) -> Unit,
) {
    val t = cleanTokens()
    Row(
        modifier = Modifier.fillMaxWidth().clickable { player.jumpToQueue(index) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtwork(track, 34.dp, 9.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.song.name,
                fontSize = 12.5.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (current) t.primary else t.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${track.song.artistText.ifBlank { "未知歌手" }} · ${if (track.localPath == null) "在线" else "本地"}",
                fontSize = 10.5.sp,
                color = t.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (track.localPath != null) {
            AppIconButton(
                icon = if (track.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (track.isFavorite) "取消收藏" else "收藏",
                onClick = { onToggleFavorite(track) },
                primary = track.isFavorite,
                size = 28.dp,
            )
        }
        AppIconButton(Icons.Outlined.KeyboardArrowUp, "上移", { player.moveInQueue(index, -1) }, enabled = index > 0, size = 28.dp)
        AppIconButton(Icons.Outlined.KeyboardArrowDown, "下移", { player.moveInQueue(index, 1) }, enabled = index < player.state.value.queue.lastIndex, size = 28.dp)
        AppIconButton(Icons.Outlined.PlayArrow, "播放", { player.jumpToQueue(index) }, primary = true, size = 28.dp)
        AppIconButton(Icons.Outlined.Delete, "移除", { player.removeFromQueue(index) }, danger = true, size = 28.dp)
    }
}

@Composable
private fun HistoryTrackRow(
    track: PlayerTrack,
    recent: Boolean,
    onToggleFavorite: (PlayerTrack) -> Unit,
    onAddToQueue: (PlayerTrack) -> Unit,
) {
    val t = cleanTokens()
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        MediaArtwork(track, 34.dp, 9.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(track.song.name, fontSize = 12.5.sp, color = if (recent) t.primary else t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.song.artistText.ifBlank { "未知歌手" }, fontSize = 10.5.sp, color = t.textMuted, maxLines = 1)
        }
        if (track.localPath != null) {
            AppIconButton(
                icon = if (track.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (track.isFavorite) "取消收藏" else "收藏",
                onClick = { onToggleFavorite(track) },
                primary = track.isFavorite,
                size = 28.dp,
            )
        }
        AppIconButton(Icons.AutoMirrored.Outlined.PlaylistAdd, "重新加入队列", { onAddToQueue(track) }, size = 28.dp)
    }
}

@Composable
private fun QueueEmpty(text: String) {
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = cleanTokens().textMuted)
    }
}
