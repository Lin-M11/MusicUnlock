package musicunlock.online

import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import musicunlock.diagnostics.Diagnostics
import musicunlock.library.LibraryIndex
import musicunlock.settings.AppSettings
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/** 应用级在线下载队列。任务状态会持久化，应用重启后未完成任务自动续跑。 */
class DownloadTaskManager(
    private val library: LibraryIndex = LibraryIndex(),
    private val settingsProvider: () -> AppSettings,
    private val taskFile: File = defaultTaskFile(),
    private val providerResolver: (String) -> OnlineMusicProvider? = ProviderRegistry::find,
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val lastPersist = ConcurrentHashMap<String, Long>()
    private val listeners = mutableListOf<(DownloadTaskSnapshot) -> Unit>()

    private val mutableTasks = MutableStateFlow(loadTasks())
    val tasks: StateFlow<List<DownloadTaskRecord>> = mutableTasks.asStateFlow()

    init {
        mutableTasks.value = mutableTasks.value.map { task ->
            if (!task.isTerminal && task.state != DownloadTaskState.PAUSED) {
                task.copy(state = DownloadTaskState.QUEUED, message = "应用重启后继续下载", updatedAt = System.currentTimeMillis())
            } else task
        }
        persist(force = true)
        pump()
    }

    fun enqueue(
        provider: OnlineMusicProvider,
        song: MusicSong,
        outputDir: File,
        preferences: DownloadPreferences,
        playlistName: String? = null,
        subscriptionId: String? = null,
    ): String {
        if (library.contains(song, provider.platform.id) && preferences.existingFilePolicy == musicunlock.settings.DownloadExistingPolicy.SKIP) {
            val id = UUID.randomUUID().toString()
            addTask(
                DownloadTaskRecord(
                    id = id,
                    platform = provider.platform.id,
                    song = song,
                    outputDir = outputDir.absolutePath,
                    preferences = preferences,
                    playlistName = playlistName,
                    subscriptionId = subscriptionId,
                    state = DownloadTaskState.SKIPPED,
                    message = "本地曲库中已有该歌曲",
                ),
            )
            return id
        }
        val id = UUID.randomUUID().toString()
        addTask(
            DownloadTaskRecord(
                id = id,
                platform = provider.platform.id,
                song = song,
                outputDir = outputDir.absolutePath,
                preferences = preferences,
                playlistName = playlistName,
                subscriptionId = subscriptionId,
            ),
        )
        pump()
        return id
    }

    fun enqueueBatch(
        provider: OnlineMusicProvider,
        songs: List<MusicSong>,
        outputDir: File,
        preferences: DownloadPreferences,
        playlistName: String? = null,
        subscriptionId: String? = null,
    ): List<String> = songs.map { enqueue(provider, it, outputDir, preferences, playlistName, subscriptionId) }

    fun pause(id: String) {
        val task = find(id) ?: return
        if (!task.canPause) return
        update(id) { it.copy(state = DownloadTaskState.PAUSED, message = "已暂停，可随时继续", speedBytesPerSecond = 0L, updatedAt = System.currentTimeMillis()) }
        activeJobs.remove(id)?.cancel()
        pump()
    }

    fun resume(id: String) {
        val task = find(id) ?: return
        if (!task.canResume && task.state != DownloadTaskState.FAILED) return
        update(id) { it.copy(state = DownloadTaskState.QUEUED, message = "等待继续", errorKind = null, updatedAt = System.currentTimeMillis()) }
        pump()
    }

    fun cancel(id: String) {
        val task = find(id) ?: return
        if (!task.canCancel) return
        update(id) { it.copy(state = DownloadTaskState.CANCELLED, message = "已取消", speedBytesPerSecond = 0L, updatedAt = System.currentTimeMillis()) }
        val job = activeJobs.remove(id)
        if (job != null) {
            job.cancel()
            scope.launch { job.join(); PartialDownloadStore.discard(File(task.outputDir), id) }
        } else {
            PartialDownloadStore.discard(File(task.outputDir), id)
        }
        pump()
    }

    fun retry(id: String) {
        val task = find(id) ?: return
        if (!task.canRetry && task.state != DownloadTaskState.PAUSED) return
        update(id) { it.copy(state = DownloadTaskState.QUEUED, attempts = 0, message = "等待重试", errorKind = null, updatedAt = System.currentTimeMillis()) }
        pump()
    }

    fun retryWithQuality(id: String, quality: musicunlock.settings.QualityStrategy) {
        val task = find(id) ?: return
        if (!task.canRetry && task.state != DownloadTaskState.PAUSED) return
        PartialDownloadStore.discard(File(task.outputDir), id)
        update(id) {
            it.copy(
                preferences = it.preferences.copy(quality = quality),
                state = DownloadTaskState.QUEUED,
                attempts = 0,
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = null,
                speedBytesPerSecond = 0L,
                etaSeconds = null,
                message = "已切换音频质量并重新排队",
                errorKind = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
        pump()
    }

    fun pauseAll() {
        mutableTasks.value.filter { it.canPause }.forEach { pause(it.id) }
    }

    fun resumeAll() {
        mutableTasks.value.filter { it.canResume }.forEach { resume(it.id) }
    }

    fun retryAllFailed() {
        mutableTasks.value.filter { it.state == DownloadTaskState.FAILED }.forEach { retry(it.id) }
    }

    fun clearCompleted() {
        updateTasks { tasks -> tasks.filterNot { it.state in setOf(DownloadTaskState.COMPLETED, DownloadTaskState.SKIPPED) } }
    }

    fun remove(id: String) {
        val task = find(id) ?: return
        if (!task.isTerminal) cancel(id)
        PartialDownloadStore.discard(File(task.outputDir), id)
        updateTasks { tasks -> tasks.filterNot { it.id == id } }
    }

    fun addListener(listener: (DownloadTaskSnapshot) -> Unit): AutoCloseable {
        synchronized(listeners) { listeners += listener }
        return AutoCloseable { synchronized(listeners) { listeners.remove(listener) } }
    }

    /** 从磁盘重新加载任务，用于完整备份恢复后立即生效。 */
    fun reload() {
        val jobs = activeJobs.values.toList()
        jobs.forEach { it.cancel() }
        runBlocking { jobs.forEach { it.join() } }
        activeJobs.clear()
        val loaded = loadTasks().map { task ->
            if (!task.isTerminal && task.state != DownloadTaskState.PAUSED) {
                task.copy(state = DownloadTaskState.QUEUED, message = "已恢复任务", updatedAt = System.currentTimeMillis())
            } else {
                task
            }
        }
        synchronized(lock) { mutableTasks.value = loaded }
        persist(force = true)
        pump()
    }

    fun snapshot(task: DownloadTaskRecord): DownloadTaskSnapshot = DownloadTaskSnapshot(
        id = task.id,
        platform = task.platform,
        title = task.song.name,
        artist = task.song.artistText,
        playlistName = task.playlistName,
        state = task.state,
        progress = task.progress,
        downloadedBytes = task.downloadedBytes,
        totalBytes = task.totalBytes,
        speedBytesPerSecond = task.speedBytesPerSecond,
        etaSeconds = task.etaSeconds,
        attempts = task.attempts,
        message = task.message,
        errorKind = task.errorKind,
        outputFile = task.outputPath?.let(::File),
        qualityLabel = task.qualityLabel,
    )

    private fun pump() {
        synchronized(lock) {
            val settings = settingsProvider()
            val limit = settings.downloadConcurrency.coerceAtLeast(1)
            val queued = mutableTasks.value.filter { it.state == DownloadTaskState.QUEUED }
            for (task in queued) {
                if (activeJobs.size >= limit) break
                if (activeJobs.containsKey(task.id)) continue
                val job = scope.launch { execute(task.id) }
                activeJobs[task.id] = job
                job.invokeOnCompletion { activeJobs.remove(task.id); pump() }
            }
        }
    }

    private suspend fun execute(id: String) {
        var task = find(id) ?: return
        val provider = providerResolver(task.platform)
        if (provider == null) {
            update(id) { it.copy(state = DownloadTaskState.FAILED, message = "未知平台：${task.platform}", errorKind = DownloadErrorKind.UNKNOWN) }
            return
        }

        var lastAttempt = task.attempts
        while (true) {
            if (task.state == DownloadTaskState.PAUSED || task.state == DownloadTaskState.CANCELLED) return
            lastAttempt++
            update(id) { it.copy(attempts = lastAttempt, state = DownloadTaskState.DOWNLOADING, message = if (lastAttempt > 1) "第 $lastAttempt 次尝试" else "正在下载") }
            try {
                val startedAt = System.nanoTime()
                val result = OnlineDownloadEngine.execute(
                    provider = provider,
                    song = task.song,
                    outputDir = File(task.outputDir),
                    preferences = task.preferences,
                    settings = settingsProvider(),
                    playlistName = task.playlistName,
                    library = library,
                    onState = { state, message -> update(id) { it.copy(state = state, message = message, updatedAt = System.currentTimeMillis()) } },
                    onProgress = { downloaded, total, bps ->
                        val rate = if (bps > 0) bps else {
                            val elapsed = max(1L, (System.nanoTime() - startedAt) / 1_000_000_000L)
                            downloaded / elapsed
                        }
                        val eta = if (rate > 0 && total != null) ((total - downloaded).coerceAtLeast(0L) / rate) else null
                        update(id, persist = false) {
                            it.copy(
                                state = DownloadTaskState.DOWNLOADING,
                                progress = if (total != null && total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else it.progress,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                speedBytesPerSecond = rate,
                                etaSeconds = eta,
                                updatedAt = System.currentTimeMillis(),
                            )
                        }
                    },
                    shouldContinue = {
                        val current = find(id)
                        current != null && current.state !in setOf(DownloadTaskState.PAUSED, DownloadTaskState.CANCELLED)
                    },
                    seed = id,
                )
                val terminalState = if (result.skipped) DownloadTaskState.SKIPPED else DownloadTaskState.COMPLETED
                update(id) {
                    it.copy(
                        state = terminalState,
                        progress = 1f,
                        speedBytesPerSecond = 0L,
                        etaSeconds = 0L,
                        outputPath = result.file.absolutePath,
                        qualityLabel = result.qualityLabel,
                        message = result.message ?: "已完成",
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                return
            } catch (e: CancellationException) {
                val current = find(id)
                if (current?.state == DownloadTaskState.PAUSED || current?.state == DownloadTaskState.CANCELLED) return
                if (lastAttempt <= task.preferences.retryCount) {
                    delay(backoff(task.preferences, lastAttempt))
                    continue
                }
                update(id) { it.copy(state = DownloadTaskState.FAILED, message = "任务已中断", errorKind = DownloadErrorKind.UNKNOWN) }
                return
            } catch (e: Exception) {
                val currentState = find(id)?.state
                if (currentState == DownloadTaskState.PAUSED || currentState == DownloadTaskState.CANCELLED) return
                val classified = classify(e)
                val retryable = classified.retryable && lastAttempt <= task.preferences.retryCount
                if (retryable) {
                    update(id) {
                        it.copy(
                            state = DownloadTaskState.QUEUED,
                            message = "${classified.userMessage}，稍后自动重试",
                            errorKind = classified.kind,
                            speedBytesPerSecond = 0L,
                        )
                    }
                    delay(backoff(task.preferences, lastAttempt))
                    task = find(id) ?: return
                    continue
                }
                update(id) {
                    it.copy(
                        state = DownloadTaskState.FAILED,
                        message = classified.userMessage,
                        errorKind = classified.kind,
                        speedBytesPerSecond = 0L,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                return
            }
        }
    }

    private fun backoff(preferences: DownloadPreferences, attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 8)
        return (preferences.retryDelayMillis * (1L shl exponent)).coerceAtMost(60_000L)
    }

    private fun classify(error: Exception): ClassifiedDownloadException {
        if (error is ClassifiedDownloadException) return error
        val message = error.message.orEmpty()
        val lower = message.lowercase()
        return when {
            lower.contains("登录") || lower.contains("cookie") || lower.contains("token") || lower.contains("unauthorized") ->
                ClassifiedDownloadException(DownloadErrorKind.AUTH, "登录状态已失效，请重新登录", false, error)
            lower.contains("版权") || lower.contains("付费") || lower.contains("会员") || lower.contains("下架") ||
                lower.contains("试听") || lower.contains("无权限") ->
                ClassifiedDownloadException(DownloadErrorKind.RIGHTS, message.ifBlank { "当前账号无下载权限" }, false, error)
            lower.contains("空间") || lower.contains("磁盘") || lower.contains("read-only") || lower.contains("permission denied") ->
                ClassifiedDownloadException(DownloadErrorKind.DISK, "无法写入输出目录：$message", false, error)
            lower.contains("timeout") || lower.contains("connect") || lower.contains("network") || lower.contains("http 5") ->
                ClassifiedDownloadException(DownloadErrorKind.NETWORK, message.ifBlank { "网络请求失败" }, true, error)
            else -> ClassifiedDownloadException(DownloadErrorKind.UNKNOWN, message.ifBlank { error.toString() }, true, error)
        }
    }

    private fun addTask(task: DownloadTaskRecord) {
        synchronized(lock) { mutableTasks.value = mutableTasks.value + task }
        persist(force = true)
        notify(task)
    }

    private fun find(id: String): DownloadTaskRecord? = mutableTasks.value.firstOrNull { it.id == id }

    private fun update(id: String, persist: Boolean = true, transform: (DownloadTaskRecord) -> DownloadTaskRecord) {
        var updated: DownloadTaskRecord? = null
        val previous = find(id)
        synchronized(lock) {
            mutableTasks.value = mutableTasks.value.map { task ->
                if (task.id != id) task else transform(task).also { updated = it }
            }
        }
        updated?.let { task ->
            if (task.message != null && (previous?.state != task.state || previous.message != task.message)) {
                Diagnostics.log("下载任务 ${task.platform}/${task.song.name}: ${task.message}")
            }
            if (persist && shouldPersist(task)) persist(force = false)
            notify(task)
            if (task.state == DownloadTaskState.COMPLETED) appendPlaylistEntry(task)
            if (task.state == DownloadTaskState.QUEUED) pump()
        }
    }

    private fun updateTasks(transform: (List<DownloadTaskRecord>) -> List<DownloadTaskRecord>) {
        synchronized(lock) { mutableTasks.value = transform(mutableTasks.value) }
        persist(force = true)
    }

    private fun notify(task: DownloadTaskRecord) {
        val snapshot = snapshot(task)
        synchronized(listeners) { listeners.toList().forEach { runCatching { it(snapshot) } } }
    }

    private fun appendPlaylistEntry(task: DownloadTaskRecord) {
        if (!task.preferences.writePlaylistM3u8 || task.playlistName.isNullOrBlank() || task.outputPath.isNullOrBlank()) return
        runCatching {
            val output = File(task.outputDir)
            val playlistFile = File(output, "${OutputTemplate.sanitizeSegment(task.playlistName)}.m3u8")
            val relative = File(task.outputPath).relativeTo(output).invariantSeparatorsPath
            val lines = playlistFile.takeIf(File::isFile)?.readLines()?.toMutableList() ?: mutableListOf("#EXTM3U")
            if (relative !in lines) lines += relative
            playlistFile.parentFile?.mkdirs()
            playlistFile.writeText(lines.joinToString(System.lineSeparator(), postfix = System.lineSeparator()))
        }
    }

    private fun shouldPersist(task: DownloadTaskRecord): Boolean {
        if (task.isTerminal || task.state in setOf(DownloadTaskState.PAUSED, DownloadTaskState.QUEUED)) return true
        val now = System.currentTimeMillis()
        val previous = lastPersist[task.id] ?: 0L
        if (now - previous >= 1_000L) {
            lastPersist[task.id] = now
            return true
        }
        return false
    }

    private fun persist(force: Boolean) {
        if (!force && mutableTasks.value.isEmpty()) return
        runCatching {
            taskFile.parentFile?.mkdirs()
            val temp = File.createTempFile(taskFile.name, ".tmp", taskFile.parentFile)
            temp.writeText(gson.toJson(mutableTasks.value))
            Files.move(
                temp.toPath(),
                taskFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun loadTasks(): List<DownloadTaskRecord> = runCatching {
        if (!taskFile.isFile) return emptyList()
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, DownloadTaskRecord::class.java).type
        gson.fromJson<List<DownloadTaskRecord>>(taskFile.readText(), type).orEmpty()
    }.getOrDefault(emptyList())
}

fun defaultTaskFile(): File = File(System.getProperty("user.home"), ".musicunlock/download-tasks.json")
