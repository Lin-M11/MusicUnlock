package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.diagnostics.Diagnostics
import musicunlock.library.LibraryAuditReport
import musicunlock.library.LibraryMaintenanceService
import musicunlock.settings.AppSettings
import musicunlock.settings.DownloadExistingPolicy
import musicunlock.settings.LyricsMode
import musicunlock.settings.QualityStrategy
import musicunlock.settings.SettingsPortability
import musicunlock.settings.SettingsUpdate
import musicunlock.sync.SubscriptionManager
import java.io.File

@Composable
internal fun LibraryPage(
    settings: AppSettings,
    onUpdateSettings: SettingsUpdate,
    maintenance: LibraryMaintenanceService,
    subscriptionManager: SubscriptionManager,
    modifier: Modifier = Modifier,
) {
    val t = cleanTokens()
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<LibraryAuditReport?>(null) }
    var status by remember { mutableStateOf("扫描输出目录后可以检查重复、封面和歌词缺失") }
    var template by remember(settings.outputTemplate) { mutableStateOf(settings.outputTemplate) }
    var proxy by remember(settings.proxyUrl) { mutableStateOf(settings.proxyUrl.orEmpty()) }
    var busy by remember { mutableStateOf(false) }

    fun scan() {
        if (busy) return
        busy = true
        status = "正在扫描曲库…"
        scope.launch {
            val result = withContext(Dispatchers.IO) { maintenance.scan(File(settings.outputDir), hash = true) }
            report = withContext(Dispatchers.IO) { maintenance.audit(File(settings.outputDir)) }
            status = "已索引 ${result.indexed} 个音频，清理 ${result.removed} 条失效记录，发现 ${report?.issues?.size ?: 0} 个问题，损坏 ${result.invalid.size} 个"
            busy = false
        }
    }

    Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight(),
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
                        OutlineAction("删除重复副本") {
                            scope.launch {
                                busy = true
                                val removed = withContext(Dispatchers.IO) { maintenance.removeDuplicates(current.duplicateGroups) }
                                report = withContext(Dispatchers.IO) { maintenance.audit(File(settings.outputDir)) }
                                status = "已删除 $removed 个重复文件"
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
                        val entries = musicunlock.library.LibraryIndex().all()
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

            SectionCard("歌单追更", modifier = Modifier.weight(1f)) {
                if (settings.subscriptions.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("在任意平台勾选歌单后点击「追更选中歌单」", fontSize = 13.sp, color = t.textMuted)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(settings.subscriptions, key = { it.id }) { subscription ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(subscription.playlistName, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.text, maxLines = 1)
                                    Text(
                                        "${subscription.platform} · 每 ${subscription.syncIntervalMinutes} 分钟 · ${subscription.lastTrackCount} 首",
                                        fontSize = 11.5.sp,
                                        color = t.textMuted,
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
                            Box(Modifier.fillMaxWidth().height(1.dp).background(t.rowDivider))
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("下载与整理偏好") {
                SettingLabel("输出模板")
                SmallTextField(template, "文件命名模板", Modifier.fillMaxWidth()) {
                    template = it
                    onUpdateSettings { current -> current.copy(outputTemplate = it) }
                }
                Spacer(Modifier.height(12.dp))
                SettingLabel("音质策略")
                ChoiceRow(
                    options = QualityStrategy.entries.map { it to it.displayName() },
                    selected = settings.qualityStrategy,
                ) { onUpdateSettings { current -> current.copy(qualityStrategy = it) } }
                Spacer(Modifier.height(12.dp))
                SettingLabel("同名文件")
                ChoiceRow(
                    options = DownloadExistingPolicy.entries.map { it to it.displayName() },
                    selected = settings.existingFilePolicy,
                ) { onUpdateSettings { current -> current.copy(existingFilePolicy = it) } }
                Spacer(Modifier.height(12.dp))
                SettingLabel("歌词")
                ChoiceRow(
                    options = LyricsMode.entries.map { it to it.displayName() },
                    selected = settings.lyricsMode,
                ) { onUpdateSettings { current -> current.copy(lyricsMode = it) } }
                Spacer(Modifier.height(12.dp))
                ToggleLine("生成 cover 封面文件", settings.writeCoverSidecar) { value ->
                    onUpdateSettings { it.copy(writeCoverSidecar = value) }
                }
                ToggleLine("生成 M3U8 播放列表", settings.writePlaylistM3u8) { value ->
                    onUpdateSettings { it.copy(writePlaylistM3u8 = value) }
                }
            }

            SectionCard("下载队列") {
                NumberField("并发任务", settings.downloadConcurrency, 1, 16) { value ->
                    onUpdateSettings { it.copy(downloadConcurrency = value) }
                }
                NumberField("失败重试次数", settings.downloadRetryCount, 0, 20) { value ->
                    onUpdateSettings { it.copy(downloadRetryCount = value) }
                }
                NumberField("限速 KB/s（0 不限）", settings.downloadSpeedLimitKbps, 0, 1_000_000) { value ->
                    onUpdateSettings { it.copy(downloadSpeedLimitKbps = value) }
                }
                NumberField("请求超时秒数", settings.downloadTimeoutSeconds.toInt(), 5, 3_600) { value ->
                    onUpdateSettings { it.copy(downloadTimeoutSeconds = value.toLong()) }
                }
                Spacer(Modifier.height(8.dp))
                SettingLabel("代理地址")
                SmallTextField(proxy, "http://127.0.0.1:7890", Modifier.fillMaxWidth()) { value ->
                    proxy = value
                    onUpdateSettings { it.copy(proxyUrl = value.trim().takeIf(String::isNotBlank)) }
                }
                ToggleLine("自动使用系统代理", settings.useSystemProxy) { value ->
                    onUpdateSettings { it.copy(useSystemProxy = value) }
                }
            }

            SectionCard("桌面体验") {
                ToggleLine("监听文件夹自动转换", settings.watchEnabled) { value ->
                    onUpdateSettings { it.copy(watchEnabled = value) }
                }
                if (settings.watchFolders.isNotEmpty()) {
                    settings.watchFolders.forEach { folder ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(folder, modifier = Modifier.weight(1f), fontSize = 11.5.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            OutlineAction("移除") {
                                onUpdateSettings { current -> current.copy(watchFolders = current.watchFolders - folder) }
                            }
                        }
                    }
                }
                OutlineAction("添加监听文件夹") {
                    FileDialogs.pickFolder("选择自动转换文件夹")?.let { picked ->
                        onUpdateSettings { current -> current.copy(watchFolders = (current.watchFolders + picked.absolutePath).distinct()) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleLine("下载完成后通知", settings.notifyOnComplete) { value ->
                    onUpdateSettings { it.copy(notifyOnComplete = value) }
                }
                ToggleLine("下载时阻止系统休眠", settings.preventSleepWhileDownloading) { value ->
                    onUpdateSettings { it.copy(preventSleepWhileDownloading = value) }
                }
                ToggleLine("关闭窗口后驻留托盘", settings.minimizeToTray) { value ->
                    onUpdateSettings { it.copy(minimizeToTray = value) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    OutlineAction("导出设置") {
                        val target = FileDialogs.saveFile("导出设置", "MusicUnlock-settings.json")
                        if (target != null) status = if (SettingsPortability.export(target)) "设置已导出" else "设置导出失败"
                    }
                    OutlineAction("导入设置") {
                        FileDialogs.pickFile("导入设置", listOf("json"))?.let { source ->
                            status = runCatching { SettingsPortability.import(source); "设置已导入" }.getOrElse { it.message ?: "设置导入失败" }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlineAction("导出诊断日志") {
                    val target = FileDialogs.saveFile("导出诊断日志", "MusicUnlock-diagnostics.txt")
                    if (target != null) {
                        status = if (Diagnostics.export(target)) "诊断日志已导出：${target.absolutePath}" else "诊断日志导出失败"
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, modifier: Modifier = Modifier.fillMaxWidth(), content: @Composable ColumnScope.() -> Unit) {
    val t = cleanTokens()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(14.dp))
            .padding(14.dp),
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
    val t = cleanTokens()
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = t.primary, contentColor = t.onPrimary),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.height(36.dp),
    ) { Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun OutlineAction(text: String, onClick: () -> Unit) {
    val t = cleanTokens()
    Box(
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).border(1.dp, t.border, RoundedCornerShape(9.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 7.dp),
    ) { Text(text, fontSize = 12.sp, color = t.textSecondary) }
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
        shape = RoundedCornerShape(9.dp),
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
                    val active = value == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (active) t.primarySoft else t.surfaceSoft)
                            .border(1.dp, if (active) t.primary.copy(alpha = 0.45f) else t.border, RoundedCornerShape(8.dp))
                            .clickable { onSelect(value) }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, fontSize = 11.5.sp, color = if (active) t.primary else t.textSecondary) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ToggleLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = cleanTokens()
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.width(36.dp).height(20.dp).clip(RoundedCornerShape(10.dp))
                .background(if (checked) t.primary else t.border)
                .padding(2.dp),
        ) {
            Box(
                Modifier.fillMaxHeight().width(16.dp).clip(RoundedCornerShape(8.dp)).background(t.surface)
                    .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart),
            )
        }
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
            Box(
                modifier = Modifier.width(28.dp).height(28.dp).clip(RoundedCornerShape(7.dp)).background(t.surfaceSoft).clickable {
                    onChange((value + delta).coerceIn(min, max))
                },
                contentAlignment = Alignment.Center,
            ) { Text(symbol, fontSize = 15.sp, color = t.textSecondary) }
            Spacer(Modifier.width(5.dp))
        }
        Text(value.toString(), modifier = Modifier.width(54.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = t.text)
    }
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = cleanTokens().textMuted, modifier = Modifier.padding(bottom = 6.dp))
}

private fun QualityStrategy.displayName(): String = when (this) {
    QualityStrategy.HIGHEST -> "最高"
    QualityStrategy.LOSSLESS_FIRST -> "无损优先"
    QualityStrategy.MP3_320 -> "320k"
    QualityStrategy.BALANCED -> "均衡"
    QualityStrategy.SMALLEST -> "最小"
}

private fun DownloadExistingPolicy.displayName(): String = when (this) {
    DownloadExistingPolicy.SKIP -> "跳过"
    DownloadExistingPolicy.OVERWRITE -> "覆盖"
    DownloadExistingPolicy.RENAME -> "另存"
    DownloadExistingPolicy.UPGRADE -> "升级"
}

private fun LyricsMode.displayName(): String = when (this) {
    LyricsMode.OFF -> "关闭"
    LyricsMode.SIDECAR -> "LRC"
    LyricsMode.EMBED -> "嵌入"
    LyricsMode.BOTH -> "两者"
}
