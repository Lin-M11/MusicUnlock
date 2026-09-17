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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import musicunlock.library.EditableTags
import musicunlock.library.LibraryEntry
import java.io.File

@Composable
internal fun TagEditorOverlay(
    entry: LibraryEntry,
    onSave: (EditableTags) -> Unit,
    onClose: () -> Unit,
) {
    val t = cleanTokens()
    var title by remember(entry.path) { mutableStateOf(entry.title.orEmpty()) }
    var artist by remember(entry.path) { mutableStateOf(entry.artist.orEmpty()) }
    var album by remember(entry.path) { mutableStateOf(entry.album.orEmpty()) }
    var albumArtist by remember(entry.path) { mutableStateOf(entry.albumArtist.orEmpty()) }
    var trackNumber by remember(entry.path) { mutableStateOf(entry.trackNumber?.toString().orEmpty()) }
    var discNumber by remember(entry.path) { mutableStateOf(entry.discNumber?.toString().orEmpty()) }
    var year by remember(entry.path) { mutableStateOf(entry.year?.toString().orEmpty()) }
    var genre by remember(entry.path) { mutableStateOf(entry.genre.orEmpty()) }
    var composer by remember(entry.path) { mutableStateOf(entry.composer.orEmpty()) }
    var isrc by remember(entry.path) { mutableStateOf(entry.isrc.orEmpty()) }
    var lyrics by remember(entry.path) { mutableStateOf(entry.lyrics.orEmpty()) }
    var rating by remember(entry.path) { mutableStateOf(entry.rating) }
    var favorite by remember(entry.path) { mutableStateOf(entry.isFavorite) }
    var coverName by remember(entry.path) { mutableStateOf<String?>(null) }
    var coverBytes by remember(entry.path) { mutableStateOf<ByteArray?>(null) }
    val scroll = rememberScrollState()

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)).clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onClose,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 760.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.surface)
                .border(1.dp, t.border, RoundedCornerShape(18.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("完整标签与歌词", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.text)
                    Text(entry.path, fontSize = 11.sp, color = t.textMuted, maxLines = 1)
                }
                AppTextAction("关闭", onClick = onClose, outlined = true)
            }
            Box(Modifier.weight(1f, fill = false).verticalScroll(scroll)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TagField("标题", title) { title = it }
                    TagField("歌手", artist) { artist = it }
                    TagField("专辑", album) { album = it }
                    TagField("专辑歌手", albumArtist) { albumArtist = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TagField("音轨号", trackNumber, Modifier.weight(1f)) { trackNumber = it.filter(Char::isDigit).take(3) }
                        TagField("碟号", discNumber, Modifier.weight(1f)) { discNumber = it.filter(Char::isDigit).take(2) }
                        TagField("年份", year, Modifier.weight(1f)) { year = it.filter(Char::isDigit).take(4) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TagField("流派", genre, Modifier.weight(1f)) { genre = it }
                        TagField("作曲", composer, Modifier.weight(1f)) { composer = it }
                    }
                    TagField("ISRC", isrc) { isrc = it }
                    TagTextArea("歌词 LRC", lyrics) { lyrics = it }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("评分", fontSize = 12.sp, color = t.textSecondary)
                        (0..5).forEach { score ->
                            AppChoiceChip(
                                text = if (score == 0) "无" else "★$score",
                                selected = rating == score,
                                onClick = { rating = score },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        AppToggle(checked = favorite, onCheckedChange = { favorite = it })
                        Text(if (favorite) "已收藏" else "收藏", fontSize = 12.sp, color = t.textSecondary)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(coverName ?: "保留现有封面", modifier = Modifier.weight(1f), fontSize = 11.5.sp, color = t.textMuted)
                        AppTextAction(
                            text = "选择封面",
                            outlined = true,
                            onClick = {
                                FileDialogs.pickFile("选择封面图片", listOf("jpg", "jpeg", "png"))?.let { file: File ->
                                    coverBytes = file.readBytes()
                                    coverName = file.name
                                }
                            },
                        )
                        if (coverBytes != null) AppTextAction("移除选择", onClick = { coverBytes = null; coverName = null })
                    }
                    if (!entry.fingerprint.isNullOrBlank() || entry.spectralCutoffHz != null) {
                        val analysisText = buildList {
                            entry.fingerprint?.takeIf(String::isNotBlank)?.let { add("指纹 ${it.take(12)}…") }
                            entry.spectralCutoffHz?.let { add("频谱截止 ${it / 1000.0} kHz") }
                            entry.dynamicRangeDb?.let { add("动态范围 %.1f dB".format(it)) }
                        }.joinToString(" · ")
                        Text(analysisText, fontSize = 11.sp, color = t.textMuted)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                AppTextAction("取消", onClick = onClose, modifier = Modifier.height(UiMetrics.ControlHeight))
                Spacer(Modifier.width(8.dp))
                AppTextAction(
                    text = "保存标签",
                    filled = true,
                    modifier = Modifier.height(UiMetrics.ControlHeight),
                    onClick = {
                        onSave(
                            EditableTags(
                                title = title,
                                artist = artist,
                                album = album,
                                albumArtist = albumArtist,
                                trackNumber = trackNumber.toIntOrNull(),
                                discNumber = discNumber.toIntOrNull(),
                                year = year.toIntOrNull(),
                                genre = genre,
                                composer = composer,
                                isrc = isrc,
                                lyrics = lyrics,
                                cover = coverBytes,
                                rating = rating,
                                favorite = favorite,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun TagField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onValueChange: (String) -> Unit) {
    val t = cleanTokens()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.5.sp) },
        singleLine = true,
        shape = RoundedCornerShape(UiMetrics.ControlRadius),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = t.surfaceSoft,
            unfocusedContainerColor = t.surfaceSoft,
            focusedIndicatorColor = t.primary,
            unfocusedIndicatorColor = t.border,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = t.text),
        modifier = modifier,
    )
}

@Composable
private fun TagTextArea(label: String, value: String, onValueChange: (String) -> Unit) {
    val t = cleanTokens()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.5.sp) },
        minLines = 6,
        maxLines = 14,
        shape = RoundedCornerShape(UiMetrics.ControlRadius),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = t.surfaceSoft,
            unfocusedContainerColor = t.surfaceSoft,
            focusedIndicatorColor = t.primary,
            unfocusedIndicatorColor = t.border,
        ),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = t.text),
        modifier = Modifier.fillMaxWidth(),
    )
}
