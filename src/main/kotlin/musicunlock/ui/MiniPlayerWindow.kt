package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
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
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import musicunlock.player.AudioPlayerService
import musicunlock.player.PlayerPlaybackState

@Composable
internal fun MiniPlayerWindow(
    player: AudioPlayerService,
    visible: Boolean,
    onClose: () -> Unit,
) {
    if (!visible) return
    val state: WindowState = rememberWindowState(width = 380.dp, height = 112.dp)
    val snapshot by player.state.collectAsState()
    val current = snapshot.current ?: return
    val t = cleanTokens()
    Window(
        onCloseRequest = onClose,
        title = "MusicUnlock 迷你播放器",
        state = state,
        resizable = false,
        alwaysOnTop = true,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().background(t.bg).border(1.dp, t.border).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MediaArtwork(current, 70.dp, 14.dp)
            Column(Modifier.weight(1f)) {
                Text(current.song.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.song.artistText.ifBlank { "未知歌手" }, fontSize = 11.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatPlayerTime(snapshot.positionMillis)} / ${formatPlayerTime(snapshot.durationMillis)}",
                    fontSize = 10.sp,
                    color = t.textMuted,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(Icons.Outlined.SkipPrevious, "上一首", player::previous, size = 28.dp)
                AppIconButton(
                    icon = if (snapshot.state == PlayerPlaybackState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (snapshot.state == PlayerPlaybackState.PLAYING) "暂停" else "播放",
                    onClick = player::toggle,
                    primary = true,
                    size = 32.dp,
                )
                AppIconButton(Icons.Outlined.SkipNext, "下一首", player::next, size = 28.dp)
                AppIconButton(Icons.Outlined.Close, "关闭迷你播放器", onClose, size = 26.dp)
            }
        }
    }
}
