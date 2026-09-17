package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import musicunlock.player.AudioPlayerService
import musicunlock.player.PlayerPlaybackState
import musicunlock.player.PlayerTrack

@Composable
internal fun PlayerBar(
    player: AudioPlayerService,
    onDownload: (PlayerTrack) -> Unit,
    onToggleLyrics: () -> Unit,
    lyricsVisible: Boolean,
    onToggleQueue: () -> Unit,
    queueVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    val snapshot by player.state.collectAsState()
    val current = snapshot.current ?: return
    val t = cleanTokens()
    val duration = snapshot.durationMillis.coerceAtLeast(0L)
    val progress = if (duration > 0L) snapshot.positionMillis.toFloat() / duration.toFloat() else 0f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(t.primarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.MusicNote, contentDescription = null, tint = t.primary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.width(150.dp)) {
            Text(current.song.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(current.song.artistText.ifBlank { "未知歌手" }, fontSize = 11.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AppIconButton(
            icon = Icons.Outlined.SkipPrevious,
            contentDescription = "上一首",
            onClick = player::previous,
            size = 28.dp,
        )
        AppIconButton(
            icon = if (snapshot.state == PlayerPlaybackState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
            contentDescription = if (snapshot.state == PlayerPlaybackState.PLAYING) "暂停" else "播放",
            onClick = player::toggle,
            primary = true,
            size = 32.dp,
        )
        AppIconButton(
            icon = Icons.Outlined.SkipNext,
            contentDescription = "下一首",
            onClick = player::next,
            size = 28.dp,
        )
        AppIconButton(
            icon = Icons.Outlined.Stop,
            contentDescription = "停止",
            onClick = player::stop,
            size = 28.dp,
        )
        AppIconButton(
            icon = Icons.Outlined.Download,
            contentDescription = "下载当前歌曲 MP3",
            onClick = { onDownload(current) },
            size = 28.dp,
        )
        AppIconButton(
            icon = Icons.AutoMirrored.Outlined.QueueMusic,
            contentDescription = if (queueVisible) "隐藏播放队列" else "显示播放队列",
            onClick = onToggleQueue,
            primary = queueVisible,
            size = 28.dp,
        )
        AppIconButton(
            icon = Icons.Outlined.Bedtime,
            contentDescription = if (snapshot.sleepRemainingMillis > 0L) "睡眠定时 ${snapshot.sleepRemainingMillis / 60_000L} 分钟" else "设置睡眠定时",
            onClick = player::cycleSleepTimer,
            primary = snapshot.sleepRemainingMillis > 0L,
            size = 28.dp,
        )
        AppIconButton(
            icon = Icons.Outlined.Subtitles,
            contentDescription = if (lyricsVisible) "隐藏歌词" else "显示歌词",
            onClick = onToggleLyrics,
            primary = lyricsVisible,
            size = 28.dp,
        )
        Text(formatPlayerTime(snapshot.positionMillis), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = t.textMuted)
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { value -> player.seekTo((duration * value).toLong()) },
            enabled = duration > 0L && snapshot.state != PlayerPlaybackState.LOADING,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = t.primary,
                activeTrackColor = t.primary,
                inactiveTrackColor = t.surfaceSoft,
            ),
        )
        Text(formatPlayerTime(duration), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = t.textMuted)
        Icon(Icons.AutoMirrored.Outlined.VolumeDown, contentDescription = null, tint = t.textMuted, modifier = Modifier.size(17.dp))
        Slider(
            value = snapshot.volume,
            onValueChange = player::setVolume,
            modifier = Modifier.width(64.dp),
            colors = SliderDefaults.colors(
                thumbColor = t.primary,
                activeTrackColor = t.primary,
                inactiveTrackColor = t.surfaceSoft,
            ),
        )
        snapshot.message?.let { message ->
            Text(
                message,
                fontSize = 10.5.sp,
                color = if (snapshot.state == PlayerPlaybackState.ERROR) t.error else t.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp),
            )
        }
    }
}

private fun formatPlayerTime(millis: Long): String {
    val totalSeconds = (millis.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
