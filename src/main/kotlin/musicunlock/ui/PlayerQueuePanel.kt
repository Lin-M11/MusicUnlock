package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import musicunlock.player.AudioPlayerService

@Composable
internal fun PlayerQueuePanel(
    player: AudioPlayerService,
    modifier: Modifier = Modifier,
) {
    val snapshot by player.state.collectAsState()
    val t = cleanTokens()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(170.dp)
            .clip(RoundedCornerShape(UiMetrics.CardRadius))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(UiMetrics.CardRadius))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text("播放队列 · ${snapshot.queue.size} 首", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.textSecondary)
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(snapshot.queue) { index, track ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { player.jumpToQueue(index) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${index + 1}",
                        Modifier.width(24.dp),
                        fontSize = 10.5.sp,
                        color = if (index == snapshot.queueIndex) t.primary else t.textMuted,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(track.song.name, fontSize = 12.sp, color = if (index == snapshot.queueIndex) t.primary else t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.song.artistText.ifBlank { if (track.localPath == null) "在线歌曲" else "本地文件" }, fontSize = 10.sp, color = t.textMuted, maxLines = 1)
                    }
                    AppIconButton(Icons.Outlined.KeyboardArrowUp, "上移", { player.moveInQueue(index, -1) }, enabled = index > 0, size = UiMetrics.CompactIconButtonSize)
                    AppIconButton(Icons.Outlined.KeyboardArrowDown, "下移", { player.moveInQueue(index, 1) }, enabled = index < snapshot.queue.lastIndex, size = UiMetrics.CompactIconButtonSize)
                    AppIconButton(Icons.Outlined.PlayArrow, "播放", { player.jumpToQueue(index) }, primary = true, size = UiMetrics.CompactIconButtonSize)
                    AppIconButton(Icons.Outlined.Delete, "移除", { player.removeFromQueue(index) }, danger = true, size = UiMetrics.CompactIconButtonSize)
                }
            }
        }
    }
}
