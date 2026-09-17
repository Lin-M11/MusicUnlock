package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import musicunlock.player.AudioPlayerService

@Composable
internal fun PlayerLyricsPanel(
    player: AudioPlayerService,
    modifier: Modifier = Modifier,
) {
    val snapshot by player.state.collectAsState()
    val t = cleanTokens()
    val listState = rememberLazyListState()
    LaunchedEffect(snapshot.lyricIndex) {
        if (snapshot.lyricIndex >= 0) listState.animateScrollToItem(snapshot.lyricIndex)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(166.dp)
            .clip(RoundedCornerShape(UiMetrics.CardRadius))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(UiMetrics.CardRadius))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text("歌词", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.textSecondary)
        if (snapshot.lyrics.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                Text("当前歌曲没有可用的同步歌词", fontSize = 12.sp, color = t.textMuted)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                itemsIndexed(snapshot.lyrics) { index, line ->
                    Text(
                        line.text,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        textAlign = TextAlign.Center,
                        fontSize = if (index == snapshot.lyricIndex) 14.sp else 12.5.sp,
                        fontWeight = if (index == snapshot.lyricIndex) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            index == snapshot.lyricIndex -> t.primary
                            kotlin.math.abs(index - snapshot.lyricIndex) <= 1 -> t.text
                            else -> t.textMuted
                        },
                    )
                }
            }
        }
    }
}
