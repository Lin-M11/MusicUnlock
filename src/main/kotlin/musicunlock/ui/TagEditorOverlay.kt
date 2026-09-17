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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import musicunlock.library.LibraryEntry
import java.io.File

@Composable
internal fun TagEditorOverlay(
    entry: LibraryEntry,
    onSave: (title: String, artist: String, album: String, year: Int?, genre: String, composer: String, isrc: String, lyrics: String, cover: ByteArray?) -> Unit,
    onClose: () -> Unit,
) {
    val t = cleanTokens()
    var title by remember(entry.path) { mutableStateOf(entry.title.orEmpty()) }
    var artist by remember(entry.path) { mutableStateOf(entry.artist.orEmpty()) }
    var album by remember(entry.path) { mutableStateOf(entry.album.orEmpty()) }
    var year by remember(entry.path) { mutableStateOf("") }
    var genre by remember(entry.path) { mutableStateOf("") }
    var composer by remember(entry.path) { mutableStateOf("") }
    var isrc by remember(entry.path) { mutableStateOf("") }
    var lyrics by remember(entry.path) { mutableStateOf("") }
    var coverName by remember(entry.path) { mutableStateOf<String?>(null) }
    var coverBytes by remember(entry.path) { mutableStateOf<ByteArray?>(null) }

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
                .width(520.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(t.surface)
                .border(1.dp, t.border, RoundedCornerShape(18.dp))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("编辑标签", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = t.text)
            Text(entry.path, fontSize = 11.sp, color = t.textMuted, maxLines = 1)
            TagField("标题", title) { title = it }
            TagField("歌手", artist) { artist = it }
            TagField("专辑", album) { album = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TagField("年份", year, Modifier.weight(1f)) { year = it.filter(Char::isDigit).take(4) }
                TagField("流派", genre, Modifier.weight(1f)) { genre = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TagField("作曲", composer, Modifier.weight(1f)) { composer = it }
                TagField("ISRC", isrc, Modifier.weight(1f)) { isrc = it }
            }
            TagTextArea("歌词 LRC", lyrics) { lyrics = it }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(coverName ?: "保留现有封面", modifier = Modifier.weight(1f), fontSize = 11.5.sp, color = t.textMuted)
                AppTextAction(
                    text = "选择封面",
                    onClick = {
                        FileDialogs.pickFile("选择封面图片", listOf("jpg", "jpeg", "png"))?.let { file: File ->
                            coverBytes = file.readBytes()
                            coverName = file.name
                        }
                    },
                    outlined = true,
                )
                if (coverBytes != null) AppTextAction("移除选择", onClick = { coverBytes = null; coverName = null })
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AppTextAction("取消", onClick = onClose, outlined = true)
                Spacer(Modifier.width(8.dp))
                AppTextAction(
                    "保存",
                    onClick = {
                        onSave(title, artist, album, year.toIntOrNull(), genre, composer, isrc, lyrics, coverBytes)
                        onClose()
                    },
                    filled = true,
                )
            }
        }
    }
}

@Composable
private fun TagTextArea(label: String, value: String, onChange: (String) -> Unit) {
    val t = cleanTokens()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        minLines = 3,
        maxLines = 5,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, color = t.text),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = t.surfaceSoft,
            unfocusedContainerColor = t.surfaceSoft,
            focusedIndicatorColor = t.primary,
            unfocusedIndicatorColor = t.border,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 82.dp, max = 120.dp),
    )
}

@Composable
private fun TagField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit,
) {
    val t = cleanTokens()
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = t.text),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = t.surfaceSoft,
            unfocusedContainerColor = t.surfaceSoft,
            focusedIndicatorColor = t.primary,
            unfocusedIndicatorColor = t.border,
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.height(52.dp),
    )
}
