package musicunlock.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.backup.AppBackupService
import musicunlock.desktop.AutoStartService
import musicunlock.diagnostics.Diagnostics
import musicunlock.library.LibraryIndex
import musicunlock.sync.LibrarySyncService
import musicunlock.sync.MediaServerIntegrationService
import musicunlock.online.DownloadTaskManager
import musicunlock.service.ConversionTaskManager
import musicunlock.settings.AppSettings
import musicunlock.settings.AutomationRule
import musicunlock.settings.DownloadExistingPolicy
import musicunlock.settings.LibrarySyncProfile
import musicunlock.settings.MediaServerConfig
import musicunlock.settings.MediaServerType
import musicunlock.settings.PlatformCredentialStore
import musicunlock.settings.LyricsMode
import musicunlock.settings.OutputFormat
import musicunlock.settings.QualityStrategy
import musicunlock.settings.SettingsPortability
import musicunlock.settings.SettingsUpdate
import musicunlock.settings.SyncConflictPolicy
import musicunlock.settings.SyncDestinationType
import musicunlock.settings.SyncMode
import musicunlock.settings.extension
import musicunlock.settings.outputBitrates
import musicunlock.settings.usesBitrate
import musicunlock.sync.WebDavSyncService
import musicunlock.update.ReleaseInfo
import musicunlock.update.UpdateChecker
import musicunlock.update.UpdateDownloader
import musicunlock.update.UpdateInstaller
import musicunlock.update.currentPlatform
import java.awt.Desktop
import java.io.File
import java.util.UUID

@Composable
internal fun SettingsPage(
    settings: AppSettings,
    onUpdateSettings: SettingsUpdate,
    library: LibraryIndex,
    conversionManager: ConversionTaskManager,
    downloadManager: DownloadTaskManager,
    modifier: Modifier = Modifier,
) {
    val t = cleanTokens()
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var status by remember { mutableStateOf("设置会自动保存；登录 Cookie 始终排除在可分享备份之外") }
    var busy by remember { mutableStateOf(false) }
    var localTemplate by remember(settings.localOutputTemplate) { mutableStateOf(settings.localOutputTemplate) }
    var downloadTemplate by remember(settings.outputTemplate) { mutableStateOf(settings.outputTemplate) }
    var proxy by remember(settings.proxyUrl) { mutableStateOf(settings.proxyUrl.orEmpty()) }
    var relinkFrom by remember { mutableStateOf("") }
    var relinkTo by remember { mutableStateOf("") }
    var update by remember { mutableStateOf<ReleaseInfo?>(null) }
    var apiToken by remember(settings.localApiToken) { mutableStateOf(settings.localApiToken.orEmpty()) }
    var webdavUrl by remember(settings.webdavUrl) { mutableStateOf(settings.webdavUrl.orEmpty()) }
    var webdavUsername by remember(settings.webdavUsername) { mutableStateOf(settings.webdavUsername.orEmpty()) }
    var webdavPassword by remember { mutableStateOf("") }
    var webdavRemoteFile by remember(settings.webdavRemoteFile) { mutableStateOf(settings.webdavRemoteFile) }
    var editingRule by remember { mutableStateOf<AutomationRule?>(null) }
    val librarySync = remember { LibrarySyncService() }
    val mediaServers = remember { MediaServerIntegrationService() }
    var mediaServerType by remember { mutableStateOf(MediaServerType.PLEX) }
    var mediaServerName by remember { mutableStateOf("Home Media Server") }
    var mediaServerUrl by remember { mutableStateOf("") }
    var mediaServerUsername by remember { mutableStateOf("") }
    var mediaServerSecret by remember { mutableStateOf("") }

    fun exportBackup() {
        val target = FileDialogs.saveFile("导出完整备份", "MusicUnlock-backup.zip") ?: return
        status = runCatching { AppBackupService(library = library).export(target, includeSettings = true, includeLibrary = true, includeTasks = true) }
            .fold({ "备份已导出：${it.file.absolutePath}" }, { "备份失败：${it.message}" })
    }

    fun importBackup() {
        val source = FileDialogs.pickFile("导入完整备份", listOf("zip")) ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AppBackupService(library = library).restore(
                        source = source,
                        restoreSettings = true,
                        restoreLibrary = true,
                        restoreTasks = true,
                    )
                }
            }
            status = result.fold(
                {
                    conversionManager.reload()
                    downloadManager.reload()
                    onUpdateSettings { musicunlock.settings.SettingsStore.load() }
                    "恢复完成：曲库 ${it.libraryImported} 条，任务文件 ${it.taskFilesRestored} 个"
                },
                { "恢复失败：${it.message}" },
            )
            busy = false
        }
    }

    Box(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard("输出与转码") {
                SettingsLabel("默认输出目录")
                SettingsPathField(
                    path = settings.outputDir,
                    onBrowse = { FileDialogs.pickFolder("选择输出目录")?.let { onUpdateSettings { current -> current.copy(outputDir = it.absolutePath) } } },
                    onOpen = { runCatching { Desktop.getDesktop().open(File(settings.outputDir)) } },
                )
                SettingsSpacer()
                SettingsLabel("本地命名模板")
                SettingsTextField(localTemplate, "{title}") {
                    localTemplate = it
                    onUpdateSettings { current -> current.copy(localOutputTemplate = it) }
                }
                Text("{title} · {artist} · {album} · {track:02} · {year} · {platform}", fontSize = 10.5.sp, color = t.textMuted)
                SettingsSpacer()
                SettingsLabel("默认输出格式")
                ChoiceRows(OutputFormat.entries.toList(), settings.outputFormat, { it.localDisplayName() }) { value ->
                    onUpdateSettings { current -> current.copy(outputFormat = value) }
                }
                if (settings.outputFormat.usesBitrate) {
                    SettingsSpacer()
                    SettingsLabel("码率")
                    ChoiceRows(outputBitrates, settings.bitrateKbps, { "${it}k" }) { value ->
                        onUpdateSettings { current -> current.copy(bitrateKbps = value) }
                    }
                }
                SettingsSpacer()
                ToggleRow("按解密后音频内容去重", settings.dedup) { value ->
                    onUpdateSettings { current -> current.copy(dedup = value) }
                }
                SettingsSpacer()
                SettingsLabel("本地同名文件")
                ChoiceRows(
                    listOf(DownloadExistingPolicy.SKIP, DownloadExistingPolicy.RENAME, DownloadExistingPolicy.OVERWRITE),
                    settings.localExistingFilePolicy,
                    { it.localDisplayName() },
                ) { value ->
                    onUpdateSettings { current -> current.copy(localExistingFilePolicy = value, skipExisting = value == DownloadExistingPolicy.SKIP) }
                }
            }

            SettingsCard("在线下载") {
                SettingsLabel("在线命名模板")
                SettingsTextField(downloadTemplate, "{artist}/{album}/{title}") {
                    downloadTemplate = it
                    onUpdateSettings { current -> current.copy(outputTemplate = it) }
                }
                SettingsSpacer()
                SettingsLabel("优先音质")
                ChoiceRows(QualityStrategy.entries.toList(), settings.qualityStrategy, { it.displayName() }) { value ->
                    onUpdateSettings { current -> current.copy(qualityStrategy = value) }
                }
                SettingsSpacer()
                SettingsLabel("同名文件策略")
                ChoiceRows(DownloadExistingPolicy.entries.toList(), settings.existingFilePolicy, { it.displayName() }) { value ->
                    onUpdateSettings { current -> current.copy(existingFilePolicy = value) }
                }
                SettingsSpacer()
                SettingsLabel("歌词与附加文件")
                ChoiceRows(LyricsMode.entries.toList(), settings.lyricsMode, { it.displayName() }) { value ->
                    onUpdateSettings { current -> current.copy(lyricsMode = value) }
                }
                ToggleRow("生成独立封面文件", settings.writeCoverSidecar) { value -> onUpdateSettings { it.copy(writeCoverSidecar = value) } }
                ToggleRow("为歌单生成 M3U8", settings.writePlaylistM3u8) { value -> onUpdateSettings { it.copy(writePlaylistM3u8 = value) } }
                SettingsSpacer()
                NumberSetting("并发下载数", settings.downloadConcurrency, 1, 16) { value -> onUpdateSettings { it.copy(downloadConcurrency = value) } }
                NumberSetting("失败重试次数", settings.downloadRetryCount, 0, 20) { value -> onUpdateSettings { it.copy(downloadRetryCount = value) } }
                NumberSetting("单任务限速 KB/s（0 不限）", settings.downloadSpeedLimitKbps, 0, 1_000_000) { value -> onUpdateSettings { it.copy(downloadSpeedLimitKbps = value) } }
                NumberSetting("请求超时（秒）", settings.downloadTimeoutSeconds.toInt(), 5, 3_600) { value -> onUpdateSettings { it.copy(downloadTimeoutSeconds = value.toLong()) } }
                NumberSetting("连接超时（秒）", settings.connectTimeoutSeconds.toInt(), 3, 300) { value -> onUpdateSettings { it.copy(connectTimeoutSeconds = value.toLong()) } }
            }

            SettingsCard("网络") {
                SettingsLabel("固定 HTTP 代理")
                SettingsTextField(proxy, "http://127.0.0.1:7890", fill = true) {
                    proxy = it
                    onUpdateSettings { current -> current.copy(proxyUrl = it.trim().takeIf(String::isNotBlank)) }
                }
                ToggleRow("跟随系统代理", settings.useSystemProxy) { value -> onUpdateSettings { it.copy(useSystemProxy = value) } }
            }

            SettingsCard("桌面与后台") {
                ToggleRow("监听文件夹自动转换", settings.watchEnabled) { value -> onUpdateSettings { it.copy(watchEnabled = value) } }
                settings.watchFolders.forEach { folder ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(folder, modifier = Modifier.weight(1f), fontSize = 11.5.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        SettingsAction("移除") { onUpdateSettings { current -> current.copy(watchFolders = current.watchFolders - folder) } }
                    }
                }
                SettingsAction("添加监听文件夹") {
                    FileDialogs.pickFolder("选择自动转换文件夹")?.let { picked ->
                        onUpdateSettings { current -> current.copy(watchFolders = (current.watchFolders + picked.absolutePath).distinct()) }
                    }
                }
                SettingsSpacer()
                SettingsLabel("自动化规则")
                settings.automationRules.forEach { rule ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(rule.name, fontSize = 12.sp, color = t.text, maxLines = 1)
                            Text("${rule.inputDir} → ${rule.outputDir} · ${rule.outputFormat.name}", fontSize = 10.5.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        SettingsAction("编辑") { editingRule = rule }
                        SettingsAction("移除") {
                            onUpdateSettings { current -> current.copy(automationRules = current.automationRules.filterNot { it.id == rule.id }) }
                        }
                    }
                }
                SettingsAction("添加默认规则") {
                    val input = settings.watchFolders.firstOrNull() ?: settings.outputDir
                    val rule = AutomationRule(
                        id = UUID.randomUUID().toString(),
                        name = "默认转换规则",
                        inputDir = input,
                        outputDir = settings.outputDir,
                        outputFormat = settings.outputFormat,
                        bitrateKbps = settings.bitrateKbps,
                        outputTemplate = settings.localOutputTemplate,
                        existingFilePolicy = settings.localExistingFilePolicy,
                        extensions = musicunlock.core.Formats.supportedExtensions(),
                    )
                    onUpdateSettings { current -> current.copy(automationRules = current.automationRules + rule) }
                }
                SettingsSpacer()
                ToggleRow("守护进程启用本机 API", settings.localApiEnabled) { value -> onUpdateSettings { it.copy(localApiEnabled = value) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsTextField(apiToken, "API Token", Modifier.weight(1f)) { value ->
                        apiToken = value
                        onUpdateSettings { it.copy(localApiToken = value.trim().takeIf(String::isNotEmpty)) }
                    }
                    SettingsAction("生成") {
                        apiToken = UUID.randomUUID().toString()
                        onUpdateSettings { it.copy(localApiToken = apiToken) }
                    }
                }
                NumberSetting("API 端口", settings.localApiPort, 1024, 65_535) { value -> onUpdateSettings { it.copy(localApiPort = value) } }
                SettingsSpacer()
                SettingsLabel("WebDAV 备份")
                SettingsTextField(webdavUrl, "https://dav.example.com/remote.php/dav/files/user/", fill = true) { value ->
                    webdavUrl = value
                    onUpdateSettings { it.copy(webdavUrl = value.trim().takeIf(String::isNotEmpty)) }
                }
                SettingsTextField(webdavUsername, "用户名", fill = true) { value ->
                    webdavUsername = value
                    onUpdateSettings { it.copy(webdavUsername = value.trim().takeIf(String::isNotEmpty)) }
                }
                SettingsTextField(webdavPassword, "密码（仅写入系统凭据库）", fill = true) { webdavPassword = it }
                SettingsTextField(webdavRemoteFile, "远程文件名", fill = true) { value ->
                    webdavRemoteFile = value
                    onUpdateSettings { it.copy(webdavRemoteFile = value.trim().takeIf(String::isNotEmpty) ?: "MusicUnlock-backup.zip") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAction("上传备份") {
                        status = runCatching { WebDavSyncService().backup(settings, webdavPassword.ifBlank { null }).message }
                            .getOrElse { "WebDAV 备份失败：${it.message}" }
                    }
                    SettingsAction("恢复备份") {
                        status = runCatching { WebDavSyncService().restore(settings, webdavPassword.ifBlank { null }).message }
                            .getOrElse { "WebDAV 恢复失败：${it.message}" }
                    }
                }
                SettingsSpacer()
                ToggleRow("开机自动启动", settings.launchAtLogin) { value ->
                    val result = AutoStartService.setEnabled(value)
                    if (result.isSuccess) onUpdateSettings { it.copy(launchAtLogin = value) } else status = "开机启动设置失败：${result.exceptionOrNull()?.message}"
                }
                ToggleRow("下载完成时通知", settings.notifyOnComplete) { value -> onUpdateSettings { it.copy(notifyOnComplete = value) } }
                ToggleRow("歌单追更结果通知", settings.subscriptionNotifications) { value -> onUpdateSettings { it.copy(subscriptionNotifications = value) } }
                ToggleRow("下载时阻止系统休眠", settings.preventSleepWhileDownloading) { value -> onUpdateSettings { it.copy(preventSleepWhileDownloading = value) } }
                ToggleRow("关闭窗口后驻留托盘", settings.minimizeToTray) { value -> onUpdateSettings { it.copy(minimizeToTray = value) } }
                ToggleRow("自动下载正式版本更新", settings.autoDownloadUpdates) { value -> onUpdateSettings { it.copy(autoDownloadUpdates = value) } }
                SettingsSpacer()
                SettingsLabel("播放器音频处理")
                ToggleRow("响度归一化", settings.playerLoudnessNormalization) { value -> onUpdateSettings { it.copy(playerLoudnessNormalization = value) } }
                NumberSetting("低频增强 dB", settings.playerBassBoostDb, -12, 12) { value -> onUpdateSettings { it.copy(playerBassBoostDb = value) } }
                NumberSetting("高频增强 dB", settings.playerTrebleBoostDb, -12, 12) { value -> onUpdateSettings { it.copy(playerTrebleBoostDb = value) } }
                NumberSetting("淡入淡出秒数", settings.playerFadeSeconds, 0, 12) { value -> onUpdateSettings { it.copy(playerFadeSeconds = value) } }
            }

            SettingsCard("设备与曲库同步") {
                settings.librarySyncProfiles.forEach { profile ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = t.text, maxLines = 1)
                            Text(
                                listOfNotNull(
                                    profile.localPath ?: profile.remoteUrl,
                                    profile.mode.syncModeLabel(),
                                    profile.conflictPolicy.conflictLabel(),
                                    profile.outputFormat?.name ?: "原始格式",
                                ).joinToString(" · "),
                                fontSize = 10.5.sp,
                                color = t.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SettingsAction(if (profile.mode == SyncMode.UPLOAD_ONLY) "仅上传" else if (profile.mode == SyncMode.MIRROR) "镜像" else "双向") {
                            onUpdateSettings { current ->
                                current.copy(librarySyncProfiles = current.librarySyncProfiles.map {
                                    if (it.id == profile.id) it.copy(mode = it.mode.next()) else it
                                })
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        SettingsAction("冲突: ${profile.conflictPolicy.conflictLabel()}") {
                            onUpdateSettings { current ->
                                current.copy(librarySyncProfiles = current.librarySyncProfiles.map {
                                    if (it.id == profile.id) it.copy(conflictPolicy = it.conflictPolicy.next()) else it
                                })
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        SettingsAction("同步", primary = true, enabled = !busy) {
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { librarySync.sync(profile, library.all()) }
                                }
                                status = result.fold({ "同步完成：${it.summary}" }, { "同步失败：${it.message}" })
                                busy = false
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        SettingsAction("移除") {
                            onUpdateSettings { current -> current.copy(librarySyncProfiles = current.librarySyncProfiles.filterNot { it.id == profile.id }) }
                        }
                    }
                }
                SettingsSpacer()
                SettingsLabel("媒体服务器刷新")
                settings.mediaServers.forEach { server ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name, fontSize = 12.sp, color = t.text, maxLines = 1)
                            Text("${server.type.name} · ${server.baseUrl}", fontSize = 10.5.sp, color = t.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        SettingsAction("触发扫描", primary = true) {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { mediaServers.trigger(server) }
                                status = if (result.success) "${server.name}：${result.message}" else "${server.name}：${result.message}"
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                        SettingsAction("移除") {
                            onUpdateSettings { current -> current.copy(mediaServers = current.mediaServers.filterNot { it.id == server.id }) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MediaServerType.entries.forEach { type ->
                        AppChoiceChip(type.displayName(), type == mediaServerType, { mediaServerType = type })
                    }
                }
                SettingsTextField(mediaServerName, "服务器名称", fill = true) { mediaServerName = it }
                SettingsTextField(mediaServerUrl, "服务器地址，如 http://127.0.0.1:8096", fill = true) { mediaServerUrl = it }
                SettingsTextField(mediaServerUsername, "用户名（Subsonic 需要）", fill = true) { mediaServerUsername = it }
                SettingsTextField(mediaServerSecret, "Token / API Key / 密码（写入系统凭据库）", fill = true) { mediaServerSecret = it }
                SettingsAction("添加媒体服务器", enabled = mediaServerUrl.isNotBlank()) {
                    val id = UUID.randomUUID().toString()
                    if (mediaServerSecret.isNotBlank()) PlatformCredentialStore().put("media-server:$id", mediaServerSecret)
                    val server = MediaServerConfig(
                        id = id,
                        name = mediaServerName.trim().ifBlank { mediaServerType.displayName() },
                        type = mediaServerType,
                        baseUrl = mediaServerUrl.trim(),
                        username = mediaServerUsername.trim().takeIf(String::isNotEmpty),
                    )
                    onUpdateSettings { current -> current.copy(mediaServers = current.mediaServers + server) }
                    mediaServerSecret = ""
                }
                SettingsSpacer()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAction("添加本地同步") {
                        FileDialogs.pickFolder("选择同步目标目录")?.let { folder ->
                            val profile = LibrarySyncProfile(
                                id = UUID.randomUUID().toString(),
                                name = folder.name.ifBlank { "本地同步" },
                                destinationType = SyncDestinationType.LOCAL_FOLDER,
                                localPath = folder.absolutePath,
                                mode = SyncMode.MIRROR,
                            )
                            onUpdateSettings { current -> current.copy(librarySyncProfiles = current.librarySyncProfiles + profile) }
                        }
                    }
                    SettingsAction("添加 WebDAV 同步", enabled = settings.webdavUrl != null) {
                        val profile = LibrarySyncProfile(
                            id = UUID.randomUUID().toString(),
                            name = "WebDAV 曲库",
                            destinationType = SyncDestinationType.WEBDAV,
                            remoteUrl = settings.webdavUrl,
                            remoteDir = "MusicUnlock",
                            username = settings.webdavUsername,
                            mode = SyncMode.TWO_WAY,
                        )
                        onUpdateSettings { current -> current.copy(librarySyncProfiles = current.librarySyncProfiles + profile) }
                    }
                }
            }

            SettingsCard("备份、迁移与诊断") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAction("导出完整备份", primary = true, onClick = ::exportBackup)
                    SettingsAction("导入完整备份", enabled = !busy, onClick = ::importBackup)
                }
                SettingsSpacer()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsAction("导出设置") {
                        val target = FileDialogs.saveFile("导出设置", "MusicUnlock-settings.json") ?: return@SettingsAction
                        status = if (SettingsPortability.export(target)) "设置已导出" else "设置导出失败"
                    }
                    SettingsAction("导入设置") {
                        val source = FileDialogs.pickFile("导入设置", listOf("json")) ?: return@SettingsAction
                        status = runCatching {
                            SettingsPortability.import(source)
                            onUpdateSettings { musicunlock.settings.SettingsStore.load() }
                            "设置已导入"
                        }.getOrElse { it.message ?: "设置导入失败" }
                    }
                }
                SettingsSpacer()
                SettingsLabel("曲库路径迁移")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsTextField(relinkFrom, "旧曲库根目录", Modifier.weight(1f)) { relinkFrom = it }
                    SettingsAction("选择") { FileDialogs.pickFolder("选择旧曲库目录")?.let { relinkFrom = it.absolutePath } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsTextField(relinkTo, "新曲库根目录", Modifier.weight(1f)) { relinkTo = it }
                    SettingsAction("选择") { FileDialogs.pickFolder("选择新曲库目录")?.let { relinkTo = it.absolutePath } }
                }
                SettingsAction("重新关联曲库", enabled = relinkFrom.isNotBlank() && relinkTo.isNotBlank()) {
                    val changed = library.relink(relinkFrom, relinkTo)
                    status = "已迁移 $changed 条曲库记录"
                }
                SettingsSpacer()
                SettingsAction("导出诊断日志") {
                    val target = FileDialogs.saveFile("导出诊断日志", "MusicUnlock-diagnostics.txt") ?: return@SettingsAction
                    status = if (Diagnostics.export(target)) "诊断日志已导出：${target.absolutePath}" else "诊断日志导出失败"
                }
            }

            SettingsCard("应用更新") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsAction("检查更新", enabled = !busy) {
                        busy = true
                        scope.launch {
                            update = withContext(Dispatchers.IO) { UpdateChecker.check(musicunlock.BuildInfo.VERSION) }
                            status = if (update == null) "当前已是最新正式版本" else "发现新版本 ${update?.version}"
                            busy = false
                        }
                    }
                    update?.let { release ->
                        SettingsAction("下载安装包", primary = true) {
                            val asset = UpdateDownloader.selectAsset(release, currentPlatform())
                            if (asset == null) {
                                status = "当前版本没有适用于本机的安装包"
                            } else {
                                busy = true
                                scope.launch {
                                    val target = File(System.getProperty("user.home"), "Downloads/${asset.name}")
                                    status = withContext(Dispatchers.IO) {
                                        runCatching { UpdateDownloader.download(release, target, currentPlatform()) }
                                            .fold({
                                                UpdateInstaller.installAfterExit(it.file).getOrThrow()
                                                kotlin.system.exitProcess(0)
                                            }, { "更新下载失败：${it.message}" })
                                    }
                                    busy = false
                                }
                            }
                        }
                    }
                }
                Text(status, fontSize = 12.sp, color = t.textSecondary, modifier = Modifier.padding(top = 8.dp))
            }
        }
        }
        AppVerticalScrollbar(state = scroll, modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 4.dp))
        editingRule?.let { rule ->
            AutomationRuleEditorOverlay(
                initial = rule,
                onSave = { updated ->
                    onUpdateSettings { current ->
                        current.copy(automationRules = current.automationRules.map { if (it.id == updated.id) updated else it })
                    }
                    editingRule = null
                    status = "自动化规则已保存：${updated.name}"
                },
                onClose = { editingRule = null },
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val t = cleanTokens()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(UiMetrics.CardRadius))
            .background(t.surface)
            .border(1.dp, t.cardBorder, RoundedCornerShape(UiMetrics.CardRadius))
            .padding(16.dp),
    ) {
        Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.text)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun SettingsPathField(path: String, onBrowse: () -> Unit, onOpen: () -> Unit) {
    val t = cleanTokens()
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.surfaceSoft).border(1.dp, t.border, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(path, modifier = Modifier.weight(1f), fontSize = 12.5.sp, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SettingsAction("浏览", onClick = onBrowse)
        SettingsAction("打开", onClick = onOpen)
    }
}

@Composable
private fun SettingsTextField(value: String, placeholder: String, modifier: Modifier = Modifier.fillMaxWidth(), fill: Boolean = true, onValueChange: (String) -> Unit) {
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
private fun <T> ChoiceRows(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                row.forEach { value ->
                    AppChoiceChip(text = label(value), selected = value == selected, onClick = { onSelect(value) })
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val t = cleanTokens()
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.5.sp, color = t.text)
        AppToggle(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberSetting(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val t = cleanTokens()
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.5.sp, color = t.text)
        SettingsAction("−") { onChange((value - 1).coerceIn(min, max)) }
        Text(value.toString(), modifier = Modifier.width(62.dp), fontSize = 12.sp, color = t.text, fontWeight = FontWeight.Medium)
        SettingsAction("+") { onChange((value + 1).coerceIn(min, max)) }
    }
}

@Composable
private fun SettingsAction(text: String, enabled: Boolean = true, primary: Boolean = false, onClick: () -> Unit) {
    AppTextAction(text = text, onClick = onClick, enabled = enabled, primary = primary, outlined = !primary)
}

@Composable
private fun SettingsLabel(text: String) {
    Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = cleanTokens().textMuted, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SettingsSpacer() {
    Spacer(Modifier.height(12.dp))
}

private fun MediaServerType.displayName(): String = when (this) {
    MediaServerType.PLEX -> "Plex"
    MediaServerType.JELLYFIN -> "Jellyfin"
    MediaServerType.NAVIDROME -> "Navidrome"
    MediaServerType.SUBSONIC -> "Subsonic"
}

private fun SyncMode.syncModeLabel(): String = when (this) {
    SyncMode.UPLOAD_ONLY -> "仅上传"
    SyncMode.MIRROR -> "镜像"
    SyncMode.TWO_WAY -> "双向"
}

private fun SyncMode.next(): SyncMode = when (this) {
    SyncMode.UPLOAD_ONLY -> SyncMode.MIRROR
    SyncMode.MIRROR -> SyncMode.TWO_WAY
    SyncMode.TWO_WAY -> SyncMode.UPLOAD_ONLY
}

private fun SyncConflictPolicy.conflictLabel(): String = when (this) {
    SyncConflictPolicy.KEEP_NEWER -> "较新"
    SyncConflictPolicy.KEEP_LOCAL -> "本地"
    SyncConflictPolicy.KEEP_REMOTE -> "远端"
    SyncConflictPolicy.KEEP_BOTH -> "保留双份"
}

private fun SyncConflictPolicy.next(): SyncConflictPolicy = when (this) {
    SyncConflictPolicy.KEEP_NEWER -> SyncConflictPolicy.KEEP_LOCAL
    SyncConflictPolicy.KEEP_LOCAL -> SyncConflictPolicy.KEEP_REMOTE
    SyncConflictPolicy.KEEP_REMOTE -> SyncConflictPolicy.KEEP_BOTH
    SyncConflictPolicy.KEEP_BOTH -> SyncConflictPolicy.KEEP_NEWER
}

private fun OutputFormat.localDisplayName(): String = when (this) {
    OutputFormat.ORIGINAL -> "原始格式"
    else -> name
}

private fun DownloadExistingPolicy.localDisplayName(): String = when (this) {
    DownloadExistingPolicy.SKIP -> "跳过"
    DownloadExistingPolicy.OVERWRITE -> "覆盖"
    DownloadExistingPolicy.RENAME -> "另存"
    DownloadExistingPolicy.UPGRADE -> "升级"
}

private fun DownloadExistingPolicy.displayName(): String = localDisplayName()

private fun QualityStrategy.displayName(): String = when (this) {
    QualityStrategy.HIGHEST -> "最高"
    QualityStrategy.LOSSLESS_FIRST -> "无损优先"
    QualityStrategy.MP3_320 -> "320k"
    QualityStrategy.BALANCED -> "均衡"
    QualityStrategy.SMALLEST -> "最小"
}

private fun LyricsMode.displayName(): String = when (this) {
    LyricsMode.OFF -> "关闭"
    LyricsMode.SIDECAR -> "LRC 文件"
    LyricsMode.EMBED -> "嵌入音频"
    LyricsMode.BOTH -> "两者"
}
