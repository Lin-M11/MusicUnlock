package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import musicunlock.kugou.KugouDownloadPage
import musicunlock.kuwo.KuwoDownloadPage
import musicunlock.library.LibraryIndex
import musicunlock.online.DownloadTaskManager
import musicunlock.player.AudioPlayerService
import musicunlock.qq.QqDownloadPage
import musicunlock.settings.AppSettings
import musicunlock.settings.SettingsUpdate
import musicunlock.sync.CrossPlatformMatcher

/** 「在线下载」的二级页签：四个平台与统一搜索共用同一批下载目标与设置。 */
internal enum class OnlineTab(val label: String) {
    NETEASE("网易云"),
    QQ("QQ 音乐"),
    KUGOU("酷狗"),
    KUWO("酷我"),
    SEARCH("搜索与链接"),
}

/**
 * 在线下载页签容器：一次只组合当前页签的内容，
 * 切换页签或登录态变化时重建，避免同时发起多份会话恢复与歌单加载。
 */
@Composable
internal fun OnlineDownloadTabs(
    sessionRevision: Int,
    settings: AppSettings,
    onUpdateSettings: SettingsUpdate,
    downloadManager: DownloadTaskManager,
    audioPlayer: AudioPlayerService,
    crossPlatformMatcher: CrossPlatformMatcher,
    library: LibraryIndex,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(OnlineTab.NETEASE) }
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OnlineTabBar(selected = tab, onSelect = { tab = it })
        key(sessionRevision, tab) {
            when (tab) {
                OnlineTab.NETEASE -> DownloadPage(
                    settings = settings,
                    onUpdateSettings = onUpdateSettings,
                    downloadManager = downloadManager,
                    modifier = Modifier.fillMaxSize(),
                )
                OnlineTab.QQ -> QqDownloadPage(
                    settings = settings,
                    onUpdateSettings = onUpdateSettings,
                    downloadManager = downloadManager,
                    modifier = Modifier.fillMaxSize(),
                )
                OnlineTab.KUGOU -> KugouDownloadPage(
                    settings = settings,
                    onUpdateSettings = onUpdateSettings,
                    downloadManager = downloadManager,
                    modifier = Modifier.fillMaxSize(),
                )
                OnlineTab.KUWO -> KuwoDownloadPage(
                    settings = settings,
                    onUpdateSettings = onUpdateSettings,
                    downloadManager = downloadManager,
                    modifier = Modifier.fillMaxSize(),
                )
                OnlineTab.SEARCH -> DiscoverPage(
                    settings = settings,
                    downloadManager = downloadManager,
                    audioPlayer = audioPlayer,
                    crossPlatformMatcher = crossPlatformMatcher,
                    library = library,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun OnlineTabBar(selected: OnlineTab, onSelect: (OnlineTab) -> Unit) {
    val t = cleanTokens()
    Row(
        modifier = Modifier
            .wrapContentWidth()
            .clip(RoundedCornerShape(UiMetrics.CardRadius))
            .background(t.surfaceSoft)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OnlineTab.entries.forEach { item ->
            OnlineTabButton(
                label = item.label,
                selected = item == selected,
                onClick = { onSelect(item) },
            )
        }
    }
}

@Composable
private fun OnlineTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = cleanTokens()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        selected -> t.surface
        hovered -> t.surface.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(
                if (selected) {
                    Modifier.border(1.dp, t.border, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) t.text else t.textSecondary,
        )
    }
}
