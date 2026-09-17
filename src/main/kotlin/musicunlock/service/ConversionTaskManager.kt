package musicunlock.service

import com.google.gson.GsonBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import musicunlock.library.LibraryIndex
import musicunlock.library.ManagedLibraryTrashService
import musicunlock.settings.DownloadExistingPolicy
import musicunlock.settings.OutputFormat
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 本地转换任务可直接展示的状态。 */
enum class ConversionTaskState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    PAUSED,
    CANCELLED,
    SKIPPED,
    DUPLICATE,
}

/** 本地转换任务的错误分类，便于任务中心给出对应操作。 */
enum class ConversionErrorKind {
    INPUT,
    UNSUPPORTED,
    DECODE,
    TRANSCODE,
    DISK,
    CANCELLED,
    UNKNOWN,
}

/** 可持久化的本地转换任务。 */
data class ConversionTaskRecord(
    val id: String,
    val inputPath: String,
    val outputDir: String,
    val outputFormat: OutputFormat = OutputFormat.ORIGINAL,
    val bitrateKbps: Int = 320,
    val outputTemplate: String = "{title}",
    val existingFilePolicy: DownloadExistingPolicy = DownloadExistingPolicy.SKIP,
    val forceOverwrite: Boolean = false,
    val deduplicate: Boolean = false,
    val trashSourceOnSuccess: Boolean = false,
    val state: ConversionTaskState = ConversionTaskState.QUEUED,
    val stage: ConversionStage? = null,
    val progress: Float = 0f,
    val contentHash: String? = null,
    val outputPath: String? = null,
    val message: String? = null,
    val errorKind: ConversionErrorKind? = null,
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isTerminal: Boolean
        get() = state in setOf(
            ConversionTaskState.COMPLETED,
            ConversionTaskState.FAILED,
            ConversionTaskState.CANCELLED,
            ConversionTaskState.SKIPPED,
            ConversionTaskState.DUPLICATE,
        )

    val canPause: Boolean
        get() = state == ConversionTaskState.QUEUED || state == ConversionTaskState.RUNNING

    val canResume: Boolean
        get() = state == ConversionTaskState.PAUSED

    val canRetry: Boolean
        get() = state == ConversionTaskState.FAILED || state == ConversionTaskState.CANCELLED
}

/** UI 或其他调用方消费的不可变任务快照。 */
data class ConversionTaskSnapshot(
    val id: String,
    val inputPath: String,
    val outputDir: String,
    val state: ConversionTaskState,
    val stage: ConversionStage?,
    val progress: Float,
    val attempts: Int,
    val message: String?,
    val errorKind: ConversionErrorKind?,
    val outputFile: File?,
)

/** 可将任务执行替换为测试实现、但不改变队列语义的转换入口。 */
fun interface ConversionExecutor {
    fun execute(
        task: ConversionTaskRecord,
        onProgress: (Float, ConversionStage) -> Unit,
        shouldContinue: () -> Boolean,
    ): ConversionOutcome
}

/**
 * 应用级本地转换队列。任务会持久化，关闭应用后未完成任务可在下次启动续跑；
 * 暂停、取消和重试都由队列统一管理，转换核心只负责单个文件。
 */
class ConversionTaskManager(
    private val taskFile: File = defaultConversionTaskFile(),
    private val maxParallelism: () -> Int = { MusicConverter.defaultParallelism() },
    private val library: LibraryIndex? = null,
    private val executor: ConversionExecutor = ConversionExecutor { task, onProgress, shouldContinue ->
        MusicConverter.convertOne(
            inputPath = task.inputPath,
            outputDir = task.outputDir,
            outputFormat = task.outputFormat,
            bitrateKbps = task.bitrateKbps,
            forceOverwrite = task.forceOverwrite,
            outputTemplate = task.outputTemplate,
            existingFilePolicy = task.existingFilePolicy,
            onProgress = onProgress,
            shouldContinue = shouldContinue,
        )
    },
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val executionTokens = ConcurrentHashMap<String, Long>()
    private val lastPersist = ConcurrentHashMap<String, Long>()
    private val listeners = mutableListOf<(ConversionTaskSnapshot) -> Unit>()

    private val mutableTasks = MutableStateFlow(loadTasks())
    val tasks: StateFlow<List<ConversionTaskRecord>> = mutableTasks.asStateFlow()

    init {
        mutableTasks.value = mutableTasks.value.map { task ->
            if (!task.isTerminal && task.state != ConversionTaskState.PAUSED) {
                task.copy(
                    state = ConversionTaskState.QUEUED,
                    stage = null,
                    message = "应用重启后继续转换",
                    updatedAt = System.currentTimeMillis(),
                )
            } else {
                task
            }
        }
        persist(force = true)
        pump()
    }

    fun enqueue(
        inputPath: String,
        outputDir: String,
        outputFormat: OutputFormat = OutputFormat.ORIGINAL,
        bitrateKbps: Int = 320,
        outputTemplate: String = "{title}",
        existingFilePolicy: DownloadExistingPolicy = DownloadExistingPolicy.SKIP,
        forceOverwrite: Boolean = false,
        deduplicate: Boolean = false,
        trashSourceOnSuccess: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        addTask(
            ConversionTaskRecord(
                id = id,
                inputPath = File(inputPath).absolutePath,
                outputDir = File(outputDir).absolutePath,
                outputFormat = outputFormat,
                bitrateKbps = bitrateKbps,
                outputTemplate = outputTemplate,
                existingFilePolicy = existingFilePolicy,
                forceOverwrite = forceOverwrite,
                deduplicate = deduplicate,
                trashSourceOnSuccess = trashSourceOnSuccess,
            ),
        )
        pump()
        return id
    }

    fun enqueueBatch(
        inputPaths: List<String>,
        outputDir: String,
        outputFormat: OutputFormat = OutputFormat.ORIGINAL,
        bitrateKbps: Int = 320,
        outputTemplate: String = "{title}",
        existingFilePolicy: DownloadExistingPolicy = DownloadExistingPolicy.SKIP,
        forceOverwrite: Boolean = false,
        deduplicate: Boolean = false,
        trashSourceOnSuccess: Boolean = false,
    ): List<String> = inputPaths.map { path ->
        enqueue(path, outputDir, outputFormat, bitrateKbps, outputTemplate, existingFilePolicy, forceOverwrite, deduplicate, trashSourceOnSuccess)
    }

    fun pause(id: String) {
        val task = find(id) ?: return
        if (!task.canPause) return
        invalidateExecution(id)
        update(id) {
            it.copy(
                state = ConversionTaskState.PAUSED,
                stage = null,
                message = "已暂停，可随时继续",
                updatedAt = System.currentTimeMillis(),
            )
        }
        activeJobs.remove(id)?.cancel()
        pump()
    }

    fun resume(id: String) {
        val task = find(id) ?: return
        if (!task.canResume && task.state != ConversionTaskState.FAILED) return
        update(id) {
            it.copy(
                state = ConversionTaskState.QUEUED,
                stage = null,
                message = "等待继续",
                errorKind = null,
                updatedAt = System.currentTimeMillis(),
            )
        }
        pump()
    }

    fun cancel(id: String) {
        val task = find(id) ?: return
        if (task.isTerminal) return
        invalidateExecution(id)
        update(id) {
            it.copy(
                state = ConversionTaskState.CANCELLED,
                stage = null,
                progress = 0f,
                message = "已取消",
                errorKind = ConversionErrorKind.CANCELLED,
                updatedAt = System.currentTimeMillis(),
            )
        }
        activeJobs.remove(id)?.cancel()
        pump()
    }

    fun retry(id: String) {
        val task = find(id) ?: return
        if (!task.canRetry) return
        invalidateExecution(id)
        update(id) {
            it.copy(
                state = ConversionTaskState.QUEUED,
                stage = null,
                progress = 0f,
                attempts = 0,
                message = "等待重试",
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
        mutableTasks.value.filter { it.canRetry }.forEach { retry(it.id) }
    }

    fun clearCompleted() {
        updateTasks { tasks ->
            tasks.filterNot { it.state in setOf(ConversionTaskState.COMPLETED, ConversionTaskState.SKIPPED, ConversionTaskState.DUPLICATE) }
        }
    }

    fun remove(id: String) {
        val task = find(id) ?: return
        if (!task.isTerminal) cancel(id)
        invalidateExecution(id)
        updateTasks { tasks -> tasks.filterNot { it.id == id } }
    }

    fun addListener(listener: (ConversionTaskSnapshot) -> Unit): AutoCloseable {
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
            if (!task.isTerminal && task.state != ConversionTaskState.PAUSED) {
                task.copy(state = ConversionTaskState.QUEUED, stage = null, message = "已恢复任务", updatedAt = System.currentTimeMillis())
            } else {
                task
            }
        }
        synchronized(lock) { mutableTasks.value = loaded }
        persist(force = true)
        pump()
    }

    fun snapshot(task: ConversionTaskRecord): ConversionTaskSnapshot = ConversionTaskSnapshot(
        id = task.id,
        inputPath = task.inputPath,
        outputDir = task.outputDir,
        state = task.state,
        stage = task.stage,
        progress = task.progress,
        attempts = task.attempts,
        message = task.message,
        errorKind = task.errorKind,
        outputFile = task.outputPath?.let(::File),
    )

    private fun pump() {
        synchronized(lock) {
            val limit = maxParallelism().coerceAtLeast(1)
            val queued = mutableTasks.value.filter { it.state == ConversionTaskState.QUEUED }
            for (task in queued) {
                if (activeJobs.size >= limit) break
                if (activeJobs.containsKey(task.id)) continue
                val token = executionTokens.merge(task.id, 1L, Long::plus) ?: 1L
                val job = scope.launch { execute(task.id, token) }
                activeJobs[task.id] = job
                job.invokeOnCompletion {
                    activeJobs.remove(task.id, job)
                    pump()
                }
            }
        }
    }

    private fun execute(id: String, token: Long) {
        val initial = find(id) ?: return
        val attempts = initial.attempts + 1
        update(id) {
            it.copy(
                state = ConversionTaskState.RUNNING,
                stage = ConversionStage.VALIDATING,
                progress = 0f,
                attempts = attempts,
                message = if (attempts > 1) "第 $attempts 次尝试" else "正在准备转换",
                errorKind = null,
                updatedAt = System.currentTimeMillis(),
            )
        }

        try {
            if (initial.deduplicate && initial.contentHash == null) {
                val hash = MusicConverter.audioSha256(initial.inputPath)
                    ?: throw IllegalArgumentException("无法读取待转换音频内容")
                val duplicate = synchronized(lock) {
                    val match = mutableTasks.value.firstOrNull {
                        it.id != id && it.contentHash == hash && it.state != ConversionTaskState.CANCELLED
                    }
                    if (match == null) {
                        if (isCurrentExecution(id, token)) update(id, persist = true) { it.copy(contentHash = hash) }
                    }
                    match
                }
                if (duplicate != null) {
                    if (!isCurrentExecution(id, token)) return
                    update(id) {
                        it.copy(
                            state = ConversionTaskState.DUPLICATE,
                            progress = 1f,
                            stage = ConversionStage.COMPLETED,
                            contentHash = hash,
                            message = "与 ${File(duplicate.inputPath).name} 内容相同，已去重",
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                    return
                }
            }

            val outcome = executor.execute(
                task = find(id) ?: return,
                onProgress = { progress, stage ->
                    if (!isCurrentExecution(id, token)) return@execute
                    update(id, persist = false) {
                        it.copy(
                            state = ConversionTaskState.RUNNING,
                            stage = stage,
                            progress = progress.coerceIn(0f, 1f),
                            message = stage.message(),
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                },
                shouldContinue = {
                    val current = find(id)
                    current != null && current.state != ConversionTaskState.PAUSED && current.state != ConversionTaskState.CANCELLED
                },
            )

            val state = when {
                outcome.error != null -> ConversionTaskState.FAILED
                outcome.skipped -> ConversionTaskState.SKIPPED
                else -> ConversionTaskState.COMPLETED
            }
            if (!isCurrentExecution(id, token)) return
            update(id) {
                it.copy(
                    state = state,
                    stage = if (state == ConversionTaskState.COMPLETED || state == ConversionTaskState.SKIPPED) ConversionStage.COMPLETED else it.stage,
                    progress = if (state == ConversionTaskState.FAILED) it.progress else 1f,
                    outputPath = outcome.outputPath,
                    message = outcome.error ?: if (outcome.skipped) "输出文件已存在，已跳过" else "转换完成",
                    errorKind = outcome.error?.let(::classifyMessage),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            if (state == ConversionTaskState.COMPLETED || state == ConversionTaskState.SKIPPED) {
                outcome.outputPath?.let { path -> runCatching { library?.upsert(File(path), hash = false) } }
                if (state == ConversionTaskState.COMPLETED && find(id)?.trashSourceOnSuccess == true) {
                    runCatching { ManagedLibraryTrashService().move(File(initial.inputPath), "converted-${id}") }
                }
            }
        } catch (_: ConversionCancelledException) {
            if (!isCurrentExecution(id, token)) return
            val current = find(id)
            if (current?.state != ConversionTaskState.PAUSED && current?.state != ConversionTaskState.CANCELLED) {
                update(id) {
                    it.copy(
                        state = ConversionTaskState.FAILED,
                        stage = null,
                        message = "转换已中断",
                        errorKind = ConversionErrorKind.CANCELLED,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
        } catch (_: CancellationException) {
            // 队列状态已由 pause/cancel/replace 写入，协程取消不覆盖它。
        } catch (e: Exception) {
            if (!isCurrentExecution(id, token)) return
            val current = find(id)
            if (current?.state != ConversionTaskState.PAUSED && current?.state != ConversionTaskState.CANCELLED) {
                update(id) {
                    it.copy(
                        state = ConversionTaskState.FAILED,
                        stage = null,
                        message = e.message ?: e.toString(),
                        errorKind = classify(e),
                        updatedAt = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    private fun addTask(task: ConversionTaskRecord) {
        synchronized(lock) { mutableTasks.value = mutableTasks.value + task }
        persist(force = true)
        notify(task)
    }

    private fun find(id: String): ConversionTaskRecord? = mutableTasks.value.firstOrNull { it.id == id }

    private fun update(
        id: String,
        persist: Boolean = true,
        transform: (ConversionTaskRecord) -> ConversionTaskRecord,
    ) {
        var updated: ConversionTaskRecord? = null
        synchronized(lock) {
            mutableTasks.value = mutableTasks.value.map { task ->
                if (task.id != id) task else transform(task).also { updated = it }
            }
        }
        updated?.let { task ->
            if (persist && shouldPersist(task)) persist(force = false)
            notify(task)
        }
    }

    private fun updateTasks(transform: (List<ConversionTaskRecord>) -> List<ConversionTaskRecord>) {
        synchronized(lock) { mutableTasks.value = transform(mutableTasks.value) }
        persist(force = true)
    }

    private fun notify(task: ConversionTaskRecord) {
        val snapshot = snapshot(task)
        synchronized(listeners) { listeners.toList().forEach { runCatching { it(snapshot) } } }
    }

    private fun shouldPersist(task: ConversionTaskRecord): Boolean {
        if (task.isTerminal || task.state in setOf(ConversionTaskState.PAUSED, ConversionTaskState.QUEUED)) return true
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

    private fun loadTasks(): List<ConversionTaskRecord> = runCatching {
        if (!taskFile.isFile) return emptyList()
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, ConversionTaskRecord::class.java).type
        gson.fromJson<List<ConversionTaskRecord>>(taskFile.readText(), type).orEmpty()
    }.getOrDefault(emptyList())

    private fun classify(error: Throwable): ConversionErrorKind = classifyMessage(error.message.orEmpty())

    private fun invalidateExecution(id: String) {
        executionTokens.merge(id, 1L, Long::plus)
    }

    private fun isCurrentExecution(id: String, token: Long): Boolean = executionTokens[id] == token

    private fun classifyMessage(rawMessage: String): ConversionErrorKind {
        val message = rawMessage.lowercase()
        return when {
            message.contains("暂停") || message.contains("取消") -> ConversionErrorKind.CANCELLED
            message.contains("不支持") -> ConversionErrorKind.UNSUPPORTED
            message.contains("不是有效") || message.contains("不存在") -> ConversionErrorKind.INPUT
            message.contains("ffmpeg") || message.contains("转码") -> ConversionErrorKind.TRANSCODE
            message.contains("空间") || message.contains("磁盘") || message.contains("permission") || message.contains("read-only") -> ConversionErrorKind.DISK
            message.contains("decode") || message.contains("解密") || message.contains("损坏") -> ConversionErrorKind.DECODE
            else -> ConversionErrorKind.UNKNOWN
        }
    }
}

private fun ConversionStage.message(): String = when (this) {
    ConversionStage.VALIDATING -> "正在检查输入文件"
    ConversionStage.DECODING -> "正在解密音频"
    ConversionStage.TRANSCODING -> "正在转码"
    ConversionStage.TAGGING -> "正在写入标签与封面"
    ConversionStage.WRITING -> "正在写入输出文件"
    ConversionStage.COMPLETED -> "转换完成"
}

fun defaultConversionTaskFile(): File =
    File(System.getProperty("user.home"), ".musicunlock/conversion-tasks.json")
