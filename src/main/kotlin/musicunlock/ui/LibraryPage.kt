package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.library.LibraryAuditReport
import musicunlock.library.LibraryCleanupPlan
import musicunlock.library.LibraryCleanupService
import musicunlock.library.LibraryEntry
import musicunlock.library.LibraryExportService
import musicunlock.library.LibraryIndex
import musicunlock.library.LibraryMaintenanceService
import musicunlock.library.LibrarySort
import musicunlock.library.SmartPlaylistKind
import musicunlock.settings.AppSettings
import musicunlock.player.AudioPlayerService
import musicunlock.player.PlayerTrack
import musicunlock.settings.SettingsUpdate
import musicunlock.sync.SubscriptionManager
import java.io.File

@Composable
internal fun LibraryPage(
    settings: AppSettings,
    onUpdateSettings: SettingsUpdate,
    maintenance: LibraryMaintenanceService,
    subscriptionManager: SubscriptionManager,
    audioPlayer: AudioPlayerService,
    library: LibraryIndex,
    modifier: Modifier = Modifier,
) {
    val t = cleanTokens()
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<LibraryAuditReport?>(null) }
    var entries by remember { mutableStateOf<List<LibraryEntry>>(emptyList()) }
    var cleanupPlan by remember { mutableStateOf<LibraryCleanupPlan?>(null) }
    var allowLikelyDuplicates by remember { mutableStateOf(false) }
    var cleanupId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(LibrarySort.TITLE) }
    var smartPlaylist by remember { mutableStateOf<SmartPlaylistKind?>(null) }
    var editingEntry by remember { mutableStateOf<LibraryEntry?>(null) }
    var batchEditing by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var localPlaylists by remember { mutableStateOf(library.playlists()) }
    var selectedPlaylistId by remember { mutableStateOf<String?>(null) }
    var newPlaylistName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("扫描输出目录后可以检查重复、封面和歌词缺失") }
    var template by remember(settings.outputTemplate) { mutableStateOf(settings.outputTemplate) }
    var busy by remember { mutableStateOf(false) }
    val subscriptionListState = rememberLazyListState()
    val libraryScroll = rememberScrollState()
    val cleanupService = remember(library) { LibraryCleanupService(library) }

    fun refreshEntries() {
        scope.launch {
            entries = withContext(Dispatchers.IO) {
                smartPlaylist?.let { library.smartPlaylist(it, 5_000) } ?: library.search(search, 5_000, sort)
            }
            localPlaylists = withContext(Dispatchers.IO) { library.playlists() }
        }
    }

    LaunchedEffect(search, sort, smartPlaylist, report) {
        refreshEntries()
    }

    fun scan() {
        if (busy) return
        busy = true
        status = "正在扫描曲库…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { maintenance.scan(File(settings.outputDir), hash = true) }
            report = withContext(Dispatchers.IO) { maintenance.audit(File(settings.outputDir)) }
            entries = library.search("", 5_000, sort)
            status = "已索引 ${result.indexed} 个音频，清理 ${result.removed} 条失效记录，发现 ${report?.issues?.size ?: 0} 个问题，损坏 ${result.invalid.size} 个"
            busy = false
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(end = 8.dp).verticalScroll(libraryScroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("曲库维护") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.outputDir, modifier = Modifier.weight(1f), fontSize = 12.5.sp, color = t.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    OutlineAction("更换目录") {
                        FileDialogs.pickFolder("选择曲库目录")?.let { picked ->
                            onUpdateSettings { it.copy(outputDir = picked.absolutePath) }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    PrimaryAction(if (busy) "扫描中…" else "开始扫描", enabled = !busy) { scan() }
                    Spacer(Modifier.width(6.dp))
                    OutlineAction("深度分析") {
                        if (!busy) {
                            busy = true
                            status = "正在解码音频并生成声学指纹…"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    maintenance.scan(File(settings.outputDir), hash = true, analyze = true)
                                }
                                report = withContext(Dispatchers.IO) { maintenance.audit(File(settings.outputDir)) }
                                refreshEntries()
                                status = "深度分析完成：${result.indexed} 个文件"
                                busy = false
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(status, fontSize = 12.sp, color = t.textMuted)
                report?.let { current ->
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SummaryPill("文件 ${current.total}")
                        SummaryPill("问题 ${current.issues.size}")
                        SummaryPill("重复组 ${current.duplicateGroups.size}")
                        SummaryPill("缺封面 ${current.issues.count { it.missingCover }}")
                        SummaryPill("缺歌词 ${current.issues.count { it.missingLyrics }}")
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlineAction("修复标签与封面") {
                            scope.launch {
                                busy = true
                                val fixed = withContext(Dispatchers.IO) { maintenance.repairIssues(current.issues) }
                                report = withContext(Dispatchers.IO) { maintenance.audit(File(settings.outputDir)) }
                                status = "已修复 $fixed 个文件"
                                busy = false
                            }
                        }
                        OutlineAction("预览重复清理") {
                            cleanupPlan = cleanupService.plan(current.duplicateGroups)
                            allowLikelyDuplicates = false
                        }
                    }
                    cleanupPlan?.let { plan ->
                        Spacer(Modifier.height(10.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.surfaceSoft).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Text(
                                "精确重复 ${plan.exactTrackCount} 首 · 疑似重复 ${plan.likelyTrackCount} 首 · 可回收 ${humanBytes(plan.reclaimBytes)}",
                                fontSize = 12.sp,
                                color = t.textSecondary,
                            )
                            plan.decisions.take(5).forEach { decision ->
                                Text(
                                    "保留 ${File(decision.keepPath).name}；移除 ${decision.removePaths.joinToString("、") { File(it).name }}",
                                    fontSize = 11.sp,
                                    color = t.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (plan.requiresConfirmation) {
                                ToggleLine("同时处理疑似重复", allowLikelyDuplicates) { allowLikelyDuplicates = it }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlineAction("移入回收站") {
                                    scope.launch {
                                        busy = true
                                        val outcome = withContext(Dispatchers.IO) { cleanupService.apply(plan, allowLikelyDuplicates) }
                                        cleanupId = outcome.id
                                        report = withContext(Dispatchers.IO) { maintenance.audit(File(settings.outputDir)) }
                                        refreshEntries()
                                        status = "已移动 ${outcome.movedTracks} 首到回收站，可撤销"
                                        cleanupPlan = null
                                        busy = false
                                    }
                                }
                                OutlineAction("取消") { cleanupPlan = null }
                            }
                        }
                    }
                    cleanupId?.let { id ->
                        Spacer(Modifier.height(8.dp))
                        OutlineAction("撤销上次清理") {
                            scope.launch {
                                busy = true
                                val undo = withContext(Dispatchers.IO) { cleanupService.undo(id) }
                                report = withContext(Dispatchers.IO) { maintenance.audit(File(settings.outputDir)) }
                                refreshEntries()
                                status = if (undo.failed.isEmpty()) "已恢复 ${undo.restoreCount} 个文件" else "恢复失败 ${undo.failed.size} 个"
                                if (undo.failed.isEmpty()) cleanupId = null
                                busy = false
                            }
                        }
                    }
                }
            }

            SectionCard("批量重命名") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallTextField(template, "命名模板", Modifier.weight(1f)) { template = it }
                    OutlineAction("应用模板") {
                        val entries = library.all()
                            .filter { entry -> entry.path.startsWith(settings.outputDir) }
                        scope.launch {
                            busy = true
                            val outcome = withContext(Dispatchers.IO) { maintenance.renameByTemplate(entries, template) }
                            report = withContext(Dispatchers.IO) { maintenance.audit(File(settings.outputDir)) }
                            status = "已重命名 ${outcome.renamed} 个文件${if (outcome.failed.isEmpty()) "" else "，失败 ${outcome.failed.size} 个"}"
                            busy = false
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("{artist}/{album}/{title} · {track:02} · {disc} · {year} · {genre} · {platform} · {quality} · {bitrate}", fontSize = 11.5.sp, color = t.textMuted)
            }

            SectionCard("曲库浏览") {
                val filtered = entries
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallTextField(search, "搜索歌曲、歌手、专辑或路径", Modifier.weight(1f)) {
                        search = it
                        smartPlaylist = null
                    }
                    listOf(LibrarySort.TITLE, LibrarySort.ARTIST, LibrarySort.ALBUM, LibrarySort.ADDED).forEach { mode ->
                        AppChoiceChip(text = mode.displayName(), selected = sort == mode && smartPlaylist == null, onClick = {
                            sort = mode
                            smartPlaylist = null
                        })
                    }
                    OutlineAction("导出 M3U8") {
                        val target = FileDialogs.saveFile("导出 M3U8", "MusicUnlock-library.m3u8") ?: return@OutlineAction
                        status = "已导出 ${LibraryExportService.exportM3u8(filtered, target)} 首到 ${target.absolutePath}"
                    }
                    OutlineAction("导出 CSV") {
                        val target = FileDialogs.saveFile("导出 CSV", "MusicUnlock-library.csv") ?: return@OutlineAction
                        status = "已导出 ${LibraryExportService.exportCsv(filtered, target)} 条记录到 ${target.absolutePath}"
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("智能列表", fontSize = 11.sp, color = t.textMuted)
                    SmartPlaylistKind.entries.forEach { kind ->
                        AppChoiceChip(
                            text = kind.displayName(),
                            selected = smartPlaylist == kind,
                            onClick = { smartPlaylist = if (smartPlaylist == kind) null else kind },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlineAction("全选") { selectedPaths = filtered.mapTo(linkedSetOf()) { it.path } }
                    OutlineAction("清空") { selectedPaths = emptySet() }
                    OutlineAction("批量修复") {
                        val selected = entries.filter { it.path in selectedPaths }
                        scope.launch {
                            busy = true
                            val fixed = withContext(Dispatchers.IO) { selected.count { maintenance.repairTags(it) } }
                            refreshEntries()
                            status = "已修复 $fixed 个文件"
                            busy = false
                        }
                    }
                    OutlineAction("批量编辑") {
                        val first = entries.firstOrNull { it.path in selectedPaths }
                        if (first != null) {
                            batchEditing = true
                            editingEntry = first
                        }
                    }
                    Text("已选 ${selectedPaths.size}", fontSize = 11.sp, color = t.textMuted)
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(210.dp)) {
                    if (filtered.isEmpty()) {
                        Text("扫描后这里会显示曲库文件；输入关键词可筛选", fontSize = 12.sp, color = t.textMuted, modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(filtered, key = { _, entry -> entry.path }) { index, entry ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = entry.path in selectedPaths,
                                        onCheckedChange = { checked ->
                                            selectedPaths = if (checked) selectedPaths + entry.path else selectedPaths - entry.path
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = t.primary),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(entry.title ?: File(entry.path).nameWithoutExtension, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(
                                                entry.artist,
                                                entry.album,
                                                entry.format,
                                                entry.bitRateKbps?.let { "${it}k" },
                                                humanBytes(entry.size),
                                            ).joinToString(" · "),
                                            fontSize = 10.5.sp,
                                            color = t.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    AppIconButton(
                                        icon = Icons.Outlined.PlayArrow,
                                        contentDescription = "播放",
                                        onClick = {
                                            audioPlayer.playLibrary(filtered, index)
                                        },
                                        primary = true,
                                    )
                                    AppIconButton(
                                        icon = if (entry.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = if (entry.isFavorite) "取消收藏" else "收藏",
                                        onClick = {
                                            library.toggleFavorite(entry.path, !entry.isFavorite)
                                            refreshEntries()
                                        },
                                        danger = entry.isFavorite,
                                    )
                                    OutlineAction("修复") {
                                        scope.launch {
                                            busy = true
                                            status = if (withContext(Dispatchers.IO) { maintenance.repairTags(entry) }) "已修复 ${File(entry.path).name}" else "修复失败"
                                            refreshEntries()
                                            busy = false
                                        }
                                    }
                                    OutlineAction("编辑") { editingEntry = entry }
                                    OutlineAction("打开目录") {
                                        runCatching { java.awt.Desktop.getDesktop().open(File(entry.path).parentFile) }
                                    }
                                }
                                Box(Modifier.fillMaxWidth().height(UiMetrics.Hairline).background(t.rowDivider))
                            }
                        }
                    }
                }
            }

            SectionCard("本地歌单") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallTextField(newPlaylistName, "新建歌单名称", Modifier.weight(1f)) { newPlaylistName = it }
                    OutlineAction("新建") {
                        if (newPlaylistName.isNotBlank()) {
                            val playlist = library.createPlaylist(newPlaylistName)
                            selectedPlaylistId = playlist.id
                            localPlaylists = library.playlists()
                            newPlaylistName = ""
                            status = "已创建歌单：${playlist.name}"
                        }
                    }
                    OutlineAction("添加选中歌曲", enabled = selectedPlaylistId != null && selectedPaths.isNotEmpty()) {
                        val id = selectedPlaylistId ?: return@OutlineAction
                        val added = library.addToPlaylist(id, selectedPaths.toList())
                        localPlaylists = library.playlists()
                        status = "已添加 $added 首到本地歌单"
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (localPlaylists.isEmpty()) {
                    Text("本地歌单会保存在 SQLite 曲库中，可继续同步到设备和媒体服务器", fontSize = 12.sp, color = t.textMuted)
                } else {
                    LazyColumn(Modifier.height(132.dp)) {
                        items(localPlaylists, key = { it.id }) { playlist ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppChoiceChip(
                                    text = playlist.name,
                                    selected = selectedPlaylistId == playlist.id,
                                    onClick = { selectedPlaylistId = playlist.id },
                                )
                                Text("${library.playlistTracks(playlist.id).size} 首", fontSize = 10.5.sp, color = t.textMuted, modifier = Modifier.padding(horizontal = 8.dp))
                                Spacer(Modifier.weight(1f))
                                AppIconButton(Icons.Outlined.PlayArrow, "播放歌单", {
                                    audioPlayer.playLibrary(library.playlistTracks(playlist.id))
                                }, primary = true)
                                AppIconButton(Icons.Outlined.Delete, "删除歌单", {
                                    library.deletePlaylist(playlist.id)
                                    if (selectedPlaylistId == playlist.id) selectedPlaylistId = null
                                    localPlaylists = library.playlists()
                                }, danger = true)
                            }
                        }
                    }
                }
            }

            SectionCard("歌单追更") {

                if (settings.subscriptions.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(164.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AppEmptyIcon(Icons.Outlined.LibraryMusic)
                            Spacer(Modifier.height(12.dp))
                            Text("还没有追更的歌单", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                            Spacer(Modifier.height(4.dp))
                            Text("在任意平台勾选歌单后点击「追更选中歌单」", fontSize = 12.sp, color = t.textMuted)
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height(210.dp)) {
                    LazyColumn(state = subscriptionListState, modifier = Modifier.fillMaxSize()) {
                        items(settings.subscriptions, key = { it.id }) { subscription ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(subscription.playlistName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.text, maxLines = 1)
                                    Text(
                                        "${subscription.platform} · 每 ${subscription.syncIntervalMinutes} 分钟 · ${subscription.lastTrackCount} 首 · ${subscription.nextSyncText()}",
                                        fontSize = 11.5.sp,
                                        color = if (subscription.lastSyncError != null) t.error else t.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                OutlineAction("−间隔") {
                                    onUpdateSettings { current ->
                                        current.copy(subscriptions = current.subscriptions.map {
                                            if (it.id == subscription.id) it.copy(syncIntervalMinutes = (it.syncIntervalMinutes - 30).coerceAtLeast(5)) else it
                                        })
                                    }
                                }
                                Text("${subscription.syncIntervalMinutes}分", fontSize = 11.sp, color = t.textMuted, modifier = Modifier.padding(horizontal = 4.dp))
                                OutlineAction("+间隔") {
                                    onUpdateSettings { current ->
                                        current.copy(subscriptions = current.subscriptions.map {
                                            if (it.id == subscription.id) it.copy(syncIntervalMinutes = (it.syncIntervalMinutes + 30).coerceAtMost(10_080)) else it
                                        })
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                OutlineAction(if (subscription.enabled) "暂停" else "启用") {
                                    onUpdateSettings { current ->
                                        current.copy(subscriptions = current.subscriptions.map {
                                            if (it.id == subscription.id) it.copy(enabled = !it.enabled) else it
                                        })
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                OutlineAction("立即同步") {
                                    scope.launch {
                                        busy = true
                                        val result = withContext(Dispatchers.IO) { subscriptionManager.sync(subscription.id) }
                                        status = result.error?.let { "同步失败：$it" } ?: "已同步 ${result.playlistName}：共 ${result.total} 首，新增 ${result.added} 首，已有 ${result.skipped} 首"
                                        busy = false
                                    }
                                }
                                Spacer(Modifier.width(6.dp))
                                OutlineAction("移除") {
                                    onUpdateSettings { current ->
                                        current.copy(subscriptions = current.subscriptions.filterNot { it.id == subscription.id })
                                    }
                                }
                            }
                            Box(Modifier.fillMaxWidth().height(UiMetrics.Hairline).background(t.rowDivider))
                        }
                    }
                    AppVerticalScrollbar(
                        state = subscriptionListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 3.dp),
                    )
                    }
                }
            }
        }
        AppVerticalScrollbar(
            state = libraryScroll,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
    editingEntry?.let { entry ->
        TagEditorOverlay(
            entry = entry,
            onSave = { tags ->
                scope.launch {
                    busy = true
                    val targets = if (batchEditing) entries.filter { it.path in selectedPaths } else listOf(entry)
                    val updated = withContext(Dispatchers.IO) {
                        maintenance.updateTagsBatch(targets, tags)
                    }
                    status = if (batchEditing) "已更新 $updated / ${targets.size} 个文件" else if (updated > 0) {
                        "标签已保存：${File(entry.path).name}"
                    } else {
                        "标签保存失败"
                    }
                    entries = library.all().filter { it.path.startsWith(settings.outputDir) }
                    busy = false
                }
            },
            onClose = { editingEntry = null; batchEditing = false },
        )
    }
}

@Composable
private fun SectionCard(title: String, modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable ColumnScope.() -> Unit) {
    val t = cleanTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(UiMetrics.CardRadius))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(UiMetrics.CardRadius))
            .padding(16.dp),
    ) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun SummaryPill(text: String) {
    val t = cleanTokens()
    Text(text, modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(t.surfaceSoft).padding(horizontal = 9.dp, vertical = 6.dp), fontSize = 11.5.sp, color = t.textSecondary)
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    AppTextAction(
        text = text,
        onClick = onClick,
        enabled = enabled,
        filled = true,
        modifier = Modifier.height(36.dp),
    )
}

@Composable
private fun OutlineAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    AppTextAction(text = text, onClick = onClick, enabled = enabled, outlined = true)
}

@Composable
private fun SmallTextField(value: String, placeholder: String, modifier: Modifier, onValueChange: (String) -> Unit) {
    val t = cleanTokens()
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, fontSize = 12.sp, color = t.textMuted) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.5.sp, color = t.text),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = t.surfaceSoft,
            unfocusedContainerColor = t.surfaceSoft,
            focusedIndicatorColor = t.primary,
            unfocusedIndicatorColor = t.border,
        ),
        shape = RoundedCornerShape(UiMetrics.ControlRadius),
        modifier = modifier.height(46.dp),
    )
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val t = cleanTokens()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (value, label) ->
                    AppChoiceChip(
                        text = label,
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToggleLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = cleanTokens()
    val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (hovered) t.surfaceSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppToggle(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(9.dp))
        Text(text, fontSize = 12.5.sp, color = t.text)
    }
}

@Composable
private fun NumberField(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val t = cleanTokens()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.5.sp, color = t.text)
        listOf(-1 to "−", 1 to "+").forEach { (delta, symbol) ->
            AppTextAction(
                text = symbol,
                onClick = { onChange((value + delta).coerceIn(min, max)) },
                modifier = Modifier.width(30.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(value.toString(), modifier = Modifier.width(54.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = t.text)
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = cleanTokens().textMuted, modifier = Modifier.padding(bottom = 6.dp))
}

private fun humanBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun musicunlock.settings.PlaylistSubscription.nextSyncText(): String {
    if (lastSyncError != null) return "上次失败：$lastSyncError"
    if (lastSyncAt <= 0L) return "等待首次同步"
    val next = lastSyncAt + syncIntervalMinutes * 60_000L
    return if (next <= System.currentTimeMillis()) "即将同步" else "下次 ${java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))}"
}

private fun LibrarySort.displayName(): String = when (this) {
    LibrarySort.TITLE -> "标题"
    LibrarySort.ARTIST -> "歌手"
    LibrarySort.ALBUM -> "专辑"
    LibrarySort.ADDED -> "新增"
    LibrarySort.BITRATE -> "码率"
}

private fun SmartPlaylistKind.displayName(): String = when (this) {
    SmartPlaylistKind.RECENTLY_ADDED -> "最近新增"
    SmartPlaylistKind.RECENTLY_PLAYED -> "最近播放"
    SmartPlaylistKind.MOST_PLAYED -> "常听"
    SmartPlaylistKind.FAVORITES -> "收藏"
    SmartPlaylistKind.LOSSLESS -> "无损"
    SmartPlaylistKind.NEEDS_ATTENTION -> "待整理"
}
