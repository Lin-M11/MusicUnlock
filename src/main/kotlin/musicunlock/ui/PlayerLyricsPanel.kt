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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import musicunlock.player.AudioPlayerService
import musicunlock.player.PlaybackAudioPreferences
import musicunlock.player.PlayerTrack
import musicunlock.settings.SettingsUpdate

private data class AudioPreset(
    val name: String,
    val bass: Int,
    val treble: Int,
    val loudness: Boolean,
)

@Composable
internal fun NowPlayingOverlay(
    player: AudioPlayerService,
    audioPreferences: PlaybackAudioPreferences,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onDownload: (PlayerTrack) -> Unit,
    onToggleFavorite: (PlayerTrack) -> Unit,
    onImportLyrics: () -> Unit,
    onUpdateSettings: SettingsUpdate,
    modifier: Modifier = Modifier,
) {
    val snapshot by player.state.collectAsState()
    val current = snapshot.current ?: return
    val t = cleanTokens()
    val listState = rememberLazyListState()
    val leftScroll = rememberScrollState()
    LaunchedEffect(snapshot.lyricIndex) {
        // 列表首项是占位 Spacer，歌词行下标比 LazyColumn 下标小 1。
        if (snapshot.lyricIndex >= 0) listState.animateScrollToItem(snapshot.lyricIndex + 1)
    }

    Box(
        modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClose,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(t.surface)
                .border(1.dp, t.cardBorder, RoundedCornerShape(22.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(
                modifier = Modifier.width(310.dp).fillMaxSize().verticalScroll(leftScroll),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MediaArtwork(current, 236.dp, 22.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        current.song.name,
                        fontSize = 23.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = t.text,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current.song.artistText.ifBlank { "未知歌手" },
                        fontSize = 13.sp,
                        color = t.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    current.song.albumName?.takeIf(String::isNotBlank)?.let {
                        Text(it, fontSize = 11.5.sp, color = t.textMuted, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (current.localPath != null) {
                        AppTextAction(
                            text = if (current.isFavorite) "已收藏" else "收藏",
                            onClick = { onToggleFavorite(current) },
                            outlined = true,
                            leadingIcon = if (current.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        )
                    }
                    AppTextAction("下载", onClick = { onDownload(current) }, outlined = true, leadingIcon = Icons.Outlined.Download)
                    AppTextAction("队列", onClick = onOpenQueue, outlined = true, leadingIcon = Icons.AutoMirrored.Outlined.QueueMusic)
                }
                NowPlayingAudioSettings(snapshot.playbackSpeed, audioPreferences, player, onUpdateSettings)
            }

            Column(Modifier.weight(1f).fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("正在播放", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = t.text)
                        Text("点击歌词可跳转播放位置", fontSize = 11.sp, color = t.textMuted)
                    }
                    AppTextAction("导入 LRC", onClick = onImportLyrics, outlined = true, leadingIcon = Icons.Outlined.Lyrics)
                    Spacer(Modifier.width(8.dp))
                    AppTextAction("关闭", onClick = onClose, filled = true)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("歌词偏移", fontSize = 11.sp, color = t.textMuted)
                    AppTextAction("−0.5s", onClick = { player.adjustLyricOffset(-500L) }, outlined = true)
                    Text(
                        "${snapshot.lyricOffsetMillis / 1000.0}s",
                        fontSize = 11.5.sp,
                        color = if (snapshot.lyricOffsetMillis == 0L) t.textMuted else t.primary,
                    )
                    AppTextAction("+0.5s", onClick = { player.adjustLyricOffset(500L) }, outlined = true)
                    AppTextAction("重置", onClick = player::resetLyricOffset, outlined = true)
                }
                Spacer(Modifier.height(10.dp))
                if (snapshot.lyrics.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            AppEmptyIcon(Icons.Outlined.LibraryMusic, 60.dp)
                            Text("当前歌曲没有可用歌词", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                            Text("可以导入同名 LRC，或为本地文件创建歌词文件", fontSize = 12.sp, color = t.textMuted)
                            AppTextAction("导入 LRC", onClick = onImportLyrics, filled = true)
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        item { Spacer(Modifier.height(180.dp)) }
                        itemsIndexed(snapshot.lyrics) { index, line ->
                            val active = index == snapshot.lyricIndex
                            Text(
                                line.text,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { player.seekTo(line.timeMillis - snapshot.lyricOffsetMillis) }
                                    .padding(horizontal = 20.dp, vertical = 8.dp),
                                textAlign = TextAlign.Center,
                                fontSize = if (active) 20.sp else 15.sp,
                                lineHeight = if (active) 27.sp else 22.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    active -> t.primary
                                    kotlin.math.abs(index - snapshot.lyricIndex) <= 2 -> t.text
                                    else -> t.textMuted.copy(alpha = 0.76f)
                                },
                            )
                        }
                        item { Spacer(Modifier.height(220.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingAudioSettings(
    playbackSpeed: Float,
    audioPreferences: PlaybackAudioPreferences,
    player: AudioPlayerService,
    onUpdateSettings: SettingsUpdate,
) {
    val t = cleanTokens()
    val presets = listOf(
        AudioPreset("原声", 0, 0, false),
        AudioPreset("人声", -1, 2, false),
        AudioPreset("流行", 2, 1, false),
        AudioPreset("摇滚", 4, 3, false),
        AudioPreset("重低音", 6, 0, false),
        AudioPreset("夜间", 0, 0, true),
    )
    val activePreset = presets.firstOrNull { preset ->
        preset.bass == audioPreferences.bassBoostDb &&
            preset.treble == audioPreferences.trebleBoostDb &&
            preset.loudness == audioPreferences.loudnessNormalization
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.surfaceSoft).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Outlined.Speed, null, tint = t.textSecondary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text("播放速度", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.textSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                AppChoiceChip(
                    text = if (speed == 1f) "1.0x" else "${speed}x",
                    selected = kotlin.math.abs(playbackSpeed - speed) < 0.01f,
                    onClick = { player.setPlaybackSpeed(speed) },
                )
            }
        }
        Text("均衡器预设", fontSize = 11.sp, color = t.textMuted)
        presets.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { preset ->
                    AppChoiceChip(
                        text = preset.name,
                        selected = preset == activePreset,
                        onClick = {
                            onUpdateSettings {
                                it.copy(
                                    playerBassBoostDb = preset.bass,
                                    playerTrebleBoostDb = preset.treble,
                                    playerLoudnessNormalization = preset.loudness,
                                )
                            }
                            player.reloadCurrentAudio()
                        },
                    )
                }
            }
        }
    }
}
