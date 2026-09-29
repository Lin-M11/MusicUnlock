package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.VolumeDown
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import musicunlock.player.AudioPlayerService
import musicunlock.player.PlayerPlaybackState
import musicunlock.player.PlayerTrack
import musicunlock.player.RepeatMode

@Composable
internal fun PlayerBar(
    player: AudioPlayerService,
    onDownload: (PlayerTrack) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenQueue: () -> Unit,
    queueVisible: Boolean,
    onOpenMiniPlayer: () -> Unit,
    onToggleFavorite: (PlayerTrack) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot by player.state.collectAsState()
    val current = snapshot.current ?: return
    val t = cleanTokens()
    val duration = snapshot.durationMillis.coerceAtLeast(0L)
    val progress = if (duration > 0L) snapshot.positionMillis.toFloat() / duration.toFloat() else 0f
    var moreOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable(onClick = onOpenNowPlaying).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MediaArtwork(track = current, size = 52.dp, corner = 12.dp)
            Column(Modifier.weight(1f)) {
                Text(current.song.name, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current.song.artistText.ifBlank { "未知歌手" }, fontSize = 11.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(if (current.localPath == null) "在线" else "本地")
                        current.song.albumName?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
                    },
                    fontSize = 10.sp,
                    color = t.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AppIconButton(
                    icon = Icons.Outlined.Shuffle,
                    contentDescription = if (snapshot.shuffleEnabled) "关闭随机播放" else "随机播放",
                    onClick = player::toggleShuffle,
                    primary = snapshot.shuffleEnabled,
                    size = 28.dp,
                )
                AppIconButton(Icons.Outlined.SkipPrevious, "上一首", player::previous, size = 30.dp)
                AppIconButton(
                    icon = if (snapshot.state == PlayerPlaybackState.PLAYING) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (snapshot.state == PlayerPlaybackState.PLAYING) "暂停" else "播放",
                    onClick = player::toggle,
                    primary = true,
                    size = 36.dp,
                )
                AppIconButton(Icons.Outlined.SkipNext, "下一首", player::next, size = 30.dp)
                AppIconButton(
                    icon = if (snapshot.repeatMode == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                    contentDescription = when (snapshot.repeatMode) {
                        RepeatMode.OFF -> "开启列表循环"
                        RepeatMode.ALL -> "切换单曲循环"
                        RepeatMode.ONE -> "关闭循环"
                    },
                    onClick = player::cycleRepeatMode,
                    primary = snapshot.repeatMode != RepeatMode.OFF,
                    size = 28.dp,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(formatPlayerTime(snapshot.positionMillis), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = t.textMuted)
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
                Text(formatPlayerTime(duration), fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = t.textMuted)
            }
        }

        Row(
            modifier = Modifier.weight(1.2f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (current.localPath != null) {
                AppIconButton(
                    icon = if (current.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (current.isFavorite) "取消收藏" else "收藏",
                    onClick = { onToggleFavorite(current) },
                    primary = current.isFavorite,
                    size = 30.dp,
                )
            }
            AppIconButton(
                icon = Icons.Outlined.Subtitles,
                contentDescription = "正在播放与歌词",
                onClick = onOpenNowPlaying,
                size = 30.dp,
            )
            AppIconButton(
                icon = Icons.AutoMirrored.Outlined.QueueMusic,
                contentDescription = if (queueVisible) "关闭播放队列" else "打开播放队列",
                onClick = onOpenQueue,
                primary = queueVisible,
                size = 30.dp,
            )
            AppIconButton(
                icon = Icons.Outlined.PictureInPictureAlt,
                contentDescription = "迷你播放器",
                onClick = onOpenMiniPlayer,
                size = 30.dp,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Outlined.VolumeDown,
                    contentDescription = null,
                    tint = t.textMuted,
                    modifier = Modifier.size(17.dp),
                )
                Slider(
                    value = snapshot.volume,
                    onValueChange = player::setVolume,
                    modifier = Modifier.width(72.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = t.primary,
                        activeTrackColor = t.primary,
                        inactiveTrackColor = t.surfaceSoft,
                    ),
                )
            }
            Box {
                AppIconButton(
                    icon = Icons.Outlined.MoreHoriz,
                    contentDescription = "更多播放操作",
                    onClick = { moreOpen = true },
                    size = 30.dp,
                )
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("下载当前歌曲 MP3") },
                        onClick = { moreOpen = false; onDownload(current) },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Download, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (snapshot.sleepRemainingMillis > 0L) "关闭睡眠定时" else "设置睡眠定时") },
                        onClick = { moreOpen = false; player.cycleSleepTimer() },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Bedtime, null) },
                    )
                    DropdownMenuItem(
                        text = { Text("停止播放") },
                        onClick = { moreOpen = false; player.stop() },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Outlined.Stop, null) },
                    )
                }
            }
        }
    }
}

internal fun formatPlayerTime(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds / 60 % 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
