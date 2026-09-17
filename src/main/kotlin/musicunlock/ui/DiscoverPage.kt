package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.library.LibraryIndex
import musicunlock.online.DownloadTaskManager
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicSearchResult
import musicunlock.online.MusicSong
import musicunlock.online.ProviderRegistry
import musicunlock.online.SearchResultKind
import musicunlock.online.toOnlineDownloadPreferences
import musicunlock.playlist.MusicLinkKind
import musicunlock.playlist.MusicLinkResolver
import musicunlock.player.AudioPlayerService
import musicunlock.player.PlayerTrack
import musicunlock.settings.AppSettings
import musicunlock.service.TranscodeFormat
import musicunlock.sync.CrossPlatformMatch
import musicunlock.sync.CrossPlatformMatcher
import java.io.File

@Composable
internal fun DiscoverPage(
    settings: AppSettings,
    downloadManager: DownloadTaskManager,
    audioPlayer: AudioPlayerService,
    crossPlatformMatcher: CrossPlatformMatcher,
    library: LibraryIndex,
    modifier: Modifier = Modifier,
) {
    val t = cleanTokens()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var platform by remember { mutableStateOf<MusicPlatform?>(null) }
    var results by remember { mutableStateOf<List<MusicSearchResult>>(emptyList()) }
    var status by remember { mutableStateOf("输入歌名、歌手、专辑或直接粘贴分享链接") }
    val resultListState = rememberLazyListState()
    var busy by remember { mutableStateOf(false) }
    var qualityInfo by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var crossMatches by remember { mutableStateOf<Map<String, List<CrossPlatformMatch>>>(emptyMap()) }

    fun restoreSessions(): List<String> {
        val results = mutableListOf<String>()
        fun restore(platformId: String, cookie: String?) {
            if (cookie.isNullOrBlank()) return
            runCatching { ProviderRegistry.require(platformId).restoreSession(cookie) }
                .onFailure { results += "${ProviderRegistry.require(platformId).platform.displayName}登录已失效" }
        }
        restore("netease", settings.neteaseCookie)
        restore("qq", settings.qqCookie)
        restore("kugou", settings.kugouCookie)
        restore("kuwo", settings.kuwoCookie)
        return results
    }

    fun enqueueSongs(
        provider: musicunlock.online.OnlineMusicProvider,
        songs: List<MusicSong>,
        label: String,
        forceMp3: Boolean = false,
    ) {
        if (songs.isEmpty()) {
            status = "没有找到可下载的歌曲"
            return
        }
        val preferences = settings.toOnlineDownloadPreferences().let { current ->
            if (forceMp3) current.copy(
                targetFormat = TranscodeFormat.MP3,
                forceMp3 = true,
                mp3BitrateKbps = settings.bitrateKbps,
            ) else current
        }
        downloadManager.enqueueBatch(
            provider = provider,
            songs = songs,
            outputDir = File(settings.outputDir),
            preferences = preferences,
            playlistName = label,
        )
        status = if (forceMp3) "已把 ${songs.size} 首加入下载队列并转为 MP3：$label" else "已把 ${songs.size} 首加入下载队列：$label"
    }

    fun playResult(result: MusicSearchResult, allResults: List<MusicSearchResult>) {
        val songs = allResults
            .filter { it.kind == SearchResultKind.SONG && it.song != null }
            .mapNotNull { item -> item.song?.let { song -> item.platform to song } }
        val queue = songs.map { (platform, song) ->
            PlayerTrack(platformId = platform.id, song = song, quality = musicunlock.settings.QualityStrategy.MP3_320)
        }
        val index = queue.indexOfFirst { it.platformId == result.platform.id && it.song.id == result.song?.id }
        if (index >= 0) {
            audioPlayer.playQueue(queue, index)
            status = "正在播放：${result.title}"
        }
    }

    fun enqueueResult(result: MusicSearchResult) {
        val provider = ProviderRegistry.require(result.platform.id)
        when (result.kind) {
            SearchResultKind.SONG -> enqueueSongs(provider, listOfNotNull(result.song), result.title)
            SearchResultKind.PLAYLIST -> scope.launch {
                busy = true
                val songs = withContext(Dispatchers.IO) {
                    val playlist = result.playlist ?: provider.playlist(result.id) ?: return@withContext emptyList()
                    runCatching { provider.songs(playlist) }.getOrElse { emptyList() }
                }
                enqueueSongs(provider, songs, result.title)
                busy = false
            }
            SearchResultKind.ALBUM -> scope.launch {
                busy = true
                val songs = withContext(Dispatchers.IO) { runCatching { provider.album(result.id)?.second }.getOrNull().orEmpty() }
                enqueueSongs(provider, songs, result.title)
                busy = false
            }
            SearchResultKind.ARTIST -> scope.launch {
                busy = true
                val songs = withContext(Dispatchers.IO) { runCatching { provider.artistSongs(result.id, 100) }.getOrDefault(emptyList()) }
                enqueueSongs(provider, songs, result.title)
                busy = false
            }
        }
    }

    fun downloadFavorites() {
        if (busy) return
        busy = true
        status = "正在读取收藏歌单…"
        scope.launch {
            val selectedPlatform = platform
            val targets = if (selectedPlatform != null) listOf(ProviderRegistry.require(selectedPlatform.id)) else ProviderRegistry.all
            val loaded = withContext(Dispatchers.IO) {
                targets.flatMap { provider ->
                    runCatching {
                        val favorites = provider.playlists().filter {
                            it.metadata["isFavorite"] == "true" || it.name.contains("喜欢") || it.name.contains("收藏")
                        }
                        favorites.flatMap { playlist ->
                            provider.songs(playlist).map { song -> Triple(provider, song, playlist.name) }
                        }
                    }.getOrDefault(emptyList())
                }
            }
            loaded.groupBy { it.first }.forEach { (provider, values) ->
                enqueueSongs(provider, values.map { it.second }, values.firstOrNull()?.third ?: "收藏歌曲")
            }
            status = if (loaded.isEmpty()) "没有找到收藏或喜欢歌单" else "已把 ${loaded.size} 首收藏歌曲加入下载队列"
            busy = false
        }
    }

    fun execute() {
        val text = query.trim()
        if (text.isEmpty() || busy) return
        busy = true
        status = "正在读取…"
        results = emptyList()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val restoreWarnings = restoreSessions()
                val link = MusicLinkResolver.parse(text)
                if (link != null) {
                    val provider = ProviderRegistry.require(link.platform.id)
                    val loaded = runCatching {
                        when (link.kind) {
                            MusicLinkKind.SONG -> listOfNotNull(provider.song(link.id))
                            MusicLinkKind.PLAYLIST -> {
                                val playlist = provider.playlist(link.id)
                                    ?: musicunlock.online.MusicPlaylist(link.id, "分享歌单", null, 0)
                                provider.songs(playlist)
                            }
                            MusicLinkKind.ALBUM -> provider.album(link.id)?.second.orEmpty()
                            MusicLinkKind.ARTIST -> provider.artistSongs(link.id, 100)
                        }
                    }
                    if (loaded.isSuccess) {
                        listOf(MusicSearchResult(
                            platform = link.platform,
                            kind = when (link.kind) {
                                MusicLinkKind.SONG -> SearchResultKind.SONG
                                MusicLinkKind.PLAYLIST -> SearchResultKind.PLAYLIST
                                MusicLinkKind.ALBUM -> SearchResultKind.ALBUM
                                MusicLinkKind.ARTIST -> SearchResultKind.ARTIST
                            },
                            id = link.id,
                            title = if (loaded.getOrNull()?.size == 1) loaded.getOrNull()!!.first().name else "分享内容",
                            subtitle = "${loaded.getOrNull()?.size ?: 0} 首",
                            song = loaded.getOrNull()?.singleOrNull(),
                        )) to (if (restoreWarnings.isEmpty()) "链接解析成功" else "链接解析成功；${restoreWarnings.joinToString("、")}，请重新登录")
                    } else {
                        emptyList<MusicSearchResult>() to "链接解析失败：${loaded.exceptionOrNull()?.message}"
                    }
                } else {
                    val selectedPlatform = platform
                    val providers = if (selectedPlatform != null) listOf(ProviderRegistry.require(selectedPlatform.id)) else ProviderRegistry.all
                    val found = providers.flatMap { provider ->
                        runCatching { provider.search(text, 20) }.getOrDefault(emptyList())
                    }.distinctBy { "${it.kind}:${it.id}" }
                    found to buildString {
                        append("找到 ${found.size} 条结果")
                        if (restoreWarnings.isNotEmpty()) append("；${restoreWarnings.joinToString("、")}，请重新登录")
                    }
                }
            }
            val (found, message) = outcome
            results = found
            status = message
            busy = false
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(UiMetrics.CardRadius)).background(t.surface).border(1.dp, t.cardBorder, RoundedCornerShape(UiMetrics.CardRadius)).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("歌名、歌手、专辑，或粘贴任一平台分享链接", fontSize = 12.5.sp, color = t.textMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = t.surfaceSoft,
                        unfocusedContainerColor = t.surfaceSoft,
                        focusedIndicatorColor = t.primary,
                        unfocusedIndicatorColor = t.border,
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = t.text),
                    modifier = Modifier.weight(1f).height(48.dp),
                )
                DiscoverAction(if (busy) "读取中…" else "搜索 / 解析", primary = true, enabled = !busy && query.isNotBlank()) { execute() }
                DiscoverAction("收藏/喜欢", enabled = !busy) { downloadFavorites() }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                DiscoverChoice("全部", platform == null) { platform = null }
                MusicPlatform.entries.forEach { item ->
                    DiscoverChoice(item.displayName, platform == item) { platform = item }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(status, fontSize = 12.sp, color = t.textMuted)
        }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(UiMetrics.CardRadius)).background(t.surface).border(1.dp, t.cardBorder, RoundedCornerShape(UiMetrics.CardRadius)),
        ) {
            if (results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AppEmptyIcon(Icons.Outlined.Search)
                        Spacer(Modifier.height(12.dp))
                        Text("搜索歌曲、歌单、专辑和歌手", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                        Spacer(Modifier.height(4.dp))
                        Text("也可以直接粘贴任一平台的分享链接", fontSize = 12.sp, color = t.textMuted)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                LazyColumn(state = resultListState, modifier = Modifier.fillMaxSize()) {
                    items(results, key = { "${it.kind}:${it.id}" }) { result ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ResultKindBadge(result.kind)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(result.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(result.subtitle, fontSize = 11.5.sp, color = t.textMuted, maxLines = 1)
                                if (result.song != null && library.contains(result.song, result.platform.id)) {
                                    Text("已下载", fontSize = 11.sp, color = t.success)
                                }
                                qualityInfo[result.key()]?.let { Text(it, fontSize = 11.sp, color = t.textSecondary) }
                                crossMatches[result.key()]?.takeIf { it.isNotEmpty() }?.let { matches ->
                                    Text(matches.joinToString(" · ") { "${it.platform.displayName} ${it.confidence}" }, fontSize = 11.sp, color = t.textSecondary)
                                }
                            }
                            if (result.kind == SearchResultKind.SONG && result.song != null) {
                                SmallAction("播放") { playResult(result, results) }
                                SmallAction("检测音质") {
                                    scope.launch {
                                        busy = true
                                        val message = withContext(Dispatchers.IO) {
                                            runCatching {
                                                val provider = ProviderRegistry.require(result.platform.id)
                                                val source = provider.playback(result.song, settings.qualityStrategy)
                                                "音质：${source.qualityLabel ?: source.formatHint ?: "可用"}"
                                            }.getOrElse { "不可用：${it.message}" }
                                        }
                                        qualityInfo = qualityInfo + (result.key() to message)
                                        busy = false
                                    }
                                }
                                SmallAction("跨平台匹配") {
                                    scope.launch {
                                        busy = true
                                        val matches = withContext(Dispatchers.IO) { crossPlatformMatcher.find(result.song, MusicPlatform.entries.filter { it != result.platform }) }
                                        crossMatches = crossMatches + (result.key() to matches)
                                        busy = false
                                    }
                                }
                            }
                            crossMatches[result.key()]?.takeIf { it.isNotEmpty() }?.let { matches ->
                                SmallAction("下载匹配") {
                                    matches.forEach { match ->
                                        val provider = ProviderRegistry.require(match.platform.id)
                                        enqueueSongs(provider, listOf(match.song), "${result.title} · 跨平台匹配")
                                    }
                                }
                            }
                            if (result.kind == SearchResultKind.SONG && result.song != null) {
                                SmallAction("下载 MP3") {
                                    enqueueSongs(
                                        provider = ProviderRegistry.require(result.platform.id),
                                        songs = listOf(result.song),
                                        label = result.title,
                                        forceMp3 = true,
                                    )
                                }
                            }
                            SmallAction(if (result.kind == SearchResultKind.SONG) "按设置下载" else "加入队列") { enqueueResult(result) }
                        }
                        Box(Modifier.fillMaxWidth().height(UiMetrics.Hairline).background(t.rowDivider))
                    }
                }
                AppVerticalScrollbar(
                    state = resultListState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 3.dp),
                )
                }
            }
        }
    }
}

private fun MusicSearchResult.key(): String = "${platform.id}:$kind:$id"

@Composable
private fun DiscoverChoice(text: String, selected: Boolean, onClick: () -> Unit) {
    AppChoiceChip(text = text, selected = selected, onClick = onClick)
}

@Composable
private fun DiscoverAction(text: String, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    AppTextAction(
        text = text,
        onClick = onClick,
        enabled = enabled,
        filled = primary,
        outlined = !primary,
        modifier = Modifier.height(UiMetrics.ControlHeight),
    )
}

@Composable
private fun SmallAction(text: String, onClick: () -> Unit) {
    AppTextAction(text = text, onClick = onClick, primary = true)
}

@Composable
private fun ResultKindBadge(kind: SearchResultKind) {
    val t = cleanTokens()
    val text = when (kind) {
        SearchResultKind.SONG -> "单曲"
        SearchResultKind.PLAYLIST -> "歌单"
        SearchResultKind.ALBUM -> "专辑"
        SearchResultKind.ARTIST -> "歌手"
    }
    Text(text, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(t.surfaceSoft).padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 11.sp, color = t.textSecondary)
}
