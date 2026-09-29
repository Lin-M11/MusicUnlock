package musicunlock.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import musicunlock.online.ProviderRegistry
import musicunlock.player.PlayerTrack
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

@Composable
internal fun MediaArtwork(
    track: PlayerTrack?,
    size: Dp,
    corner: Dp = 12.dp,
) {
    val t = cleanTokens()
    val coverUrl = track?.song?.coverUrl
    val providerId = track?.platformId
    val localCover = remember(track?.localPath) {
        track?.localPath?.let(::File)?.parentFile?.let { directory ->
            listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png")
                .map { File(directory, it) }
                .firstOrNull(File::isFile)
        }
    }
    var bitmap by remember(coverUrl, localCover?.absolutePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(coverUrl, localCover?.absolutePath) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = when {
                    localCover?.isFile == true -> localCover.readBytes()
                    !coverUrl.isNullOrBlank() && !providerId.isNullOrBlank() ->
                        ProviderRegistry.find(providerId)?.bytes(coverUrl)
                    else -> null
                }
                bytes?.let { ImageIO.read(ByteArrayInputStream(it))?.toComposeImageBitmap() }
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(corner)).background(t.primarySoft),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.size(size).clip(RoundedCornerShape(corner)),
            )
        } else {
            Icon(
                Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = t.primary,
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}
