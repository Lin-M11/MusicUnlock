package musicunlock.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import musicunlock.core.Formats
import musicunlock.core.MusicDecoder
import musicunlock.core.MusicResult
import musicunlock.online.MusicSong
import musicunlock.online.OutputTemplate
import musicunlock.settings.DownloadExistingPolicy
import musicunlock.settings.OutputFormat
import musicunlock.settings.extension
import musicunlock.settings.usesBitrate
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** 单文件转换当前所处的阶段，供任务中心展示与持久化。 */
enum class ConversionStage {
    VALIDATING,
    DECODING,
    TRANSCODING,
    TAGGING,
    WRITING,
    COMPLETED,
}

/** 用户暂停或取消任务时，由转换核心抛出的协作式停止信号。 */
class ConversionCancelledException(message: String = "转换已暂停") : RuntimeException(message)

/** 单个文件的批量转换结果。 */
data class ConversionOutcome(
    val inputPath: String,
    val error: String?,
    val skipped: Boolean = false,
    val outputPath: String? = null,
) {
    val succeeded: Boolean get() = error == null
}

/**
 * 统一转换服务:根据扩展名选择解码器,解密后写入输出目录。
 * 对 NCM 额外写回元数据(歌名/歌手/专辑/封面)。
 */
object MusicConverter {

    private val outputLocks = Array(64) { Any() }

    /** 默认按 CPU 核数并发处理文件。 */
    fun defaultParallelism(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /**
     * 并发转换多个文件。回调和状态更新沿用调用方协程上下文，
     * 解码与转码任务在 IO 调度器执行。
     */
    suspend fun convertBatch(
        inputPaths: List<String>,
        outputDir: String,
        outputFormat: OutputFormat = OutputFormat.ORIGINAL,
        bitrateKbps: Int = 320,
        forceOverwrite: Boolean = false,
        outputTemplate: String = "{title}",
        existingFilePolicy: DownloadExistingPolicy? = null,
        parallelism: Int = defaultParallelism(),
        onStarted: suspend (String) -> Unit = {},
        onFinished: suspend (ConversionOutcome) -> Unit = {},
    ): List<ConversionOutcome> {
        if (inputPaths.isEmpty()) return emptyList()

        val semaphore = Semaphore(parallelism.coerceAtLeast(1))
        val results = arrayOfNulls<ConversionOutcome>(inputPaths.size)
        val indexedInputs = inputPaths.mapIndexed { index, path -> index to path }
        return coroutineScope {
            indexedInputs.groupBy { serializationKey(it.second) }.values.map { group ->
                async {
                    semaphore.withPermit {
                        group.forEach { (index, inputPath) ->
                            onStarted(inputPath)
                            val outcome = withContext(Dispatchers.IO) {
                                convertOne(
                                    inputPath = inputPath,
                                    outputDir = outputDir,
                                    outputFormat = outputFormat,
                                    bitrateKbps = bitrateKbps,
                                    forceOverwrite = forceOverwrite,
                                    outputTemplate = outputTemplate,
                                    existingFilePolicy = existingFilePolicy,
                                )
                            }
                            results[index] = outcome
                            onFinished(outcome)
                        }
                    }
                }
            }.awaitAll()
            results.map { checkNotNull(it) }
        }
    }

    /**
     * 转换单个加密音乐文件,成功返回 null,失败返回错误信息。
     */
    fun convertWithError(
        inputPath: String,
        outputDir: String,
        outputFormat: OutputFormat = OutputFormat.ORIGINAL,
        bitrateKbps: Int = 320,
        forceOverwrite: Boolean = false,
    ): String? = convertOne(inputPath, outputDir, outputFormat, bitrateKbps, forceOverwrite).error

    /**
     * 转换单个文件。任务中心可传入 [shouldContinue] 实现暂停/取消，
     * 并通过 [onProgress] 接收粗粒度阶段进度。输出文件只会在完成标签写入后
     * 以原子移动方式进入最终目录，失败或取消不会留下半成品。
     */
    fun convertOne(
        inputPath: String,
        outputDir: String,
        outputFormat: OutputFormat,
        bitrateKbps: Int,
        forceOverwrite: Boolean,
        outputTemplate: String = "{title}",
        existingFilePolicy: DownloadExistingPolicy? = null,
        onProgress: (Float, ConversionStage) -> Unit = { _, _ -> },
        shouldContinue: () -> Boolean = { true },
    ): ConversionOutcome {
        return try {
            checkContinuing(shouldContinue)
            onProgress(0.02f, ConversionStage.VALIDATING)
            val input = File(inputPath)
            if (!input.isFile) return ConversionOutcome(inputPath, "不是有效的文件: $inputPath")

            val decoder = Formats.get(input.name) ?: run {
                println("不支持的格式,已跳过: $inputPath")
                return ConversionOutcome(inputPath, "不支持的格式: $inputPath")
            }

            val data = Files.readAllBytes(input.toPath())
            val outputDirectory = File(outputDir).apply { mkdirs() }
            val outputExt = outputFormat.extension ?: decoder.outputExtension(data, input.name)
            val policy = existingFilePolicy ?: if (forceOverwrite) DownloadExistingPolicy.OVERWRITE else DownloadExistingPolicy.SKIP
            val preliminaryOutput = if (outputTemplate.trim() == "{title}") {
                File(outputDirectory, "${baseName(input.name)}.$outputExt")
            } else {
                null
            }
            if (preliminaryOutput != null && preliminaryOutput.isFile &&
                policy in setOf(DownloadExistingPolicy.SKIP, DownloadExistingPolicy.UPGRADE)
            ) {
                println("跳过已完成文件: ${preliminaryOutput.absolutePath}")
                onProgress(1f, ConversionStage.COMPLETED)
                return ConversionOutcome(inputPath, null, skipped = true, outputPath = preliminaryOutput.absolutePath)
            }

            checkContinuing(shouldContinue)
            onProgress(0.12f, ConversionStage.DECODING)
            val result = decoder.decode(data, input.name)
            checkContinuing(shouldContinue)
            val decoded = File.createTempFile("musicunlock-", ".${result.ext}", outputDirectory)
            var transcoded: File? = null
            var finalOutput: File? = null

            try {
                Files.write(decoded.toPath(), result.data)
                onProgress(0.52f, ConversionStage.DECODING)
                val source = if (outputFormat == OutputFormat.ORIGINAL) {
                    decoded
                } else {
                    checkContinuing(shouldContinue)
                    onProgress(0.58f, ConversionStage.TRANSCODING)
                    val target = File.createTempFile("musicunlock-", ".$outputExt", outputDirectory)
                    transcoded = target
                    val error = AudioTranscoder.transcode(
                        input = decoded,
                        output = target,
                        format = outputFormat.toTranscodeFormat(),
                        bitrateKbps = bitrateKbps,
                        shouldContinue = shouldContinue,
                    )
                    if (error != null) return ConversionOutcome(inputPath, error)
                    target
                }
                checkContinuing(shouldContinue)
                onProgress(0.82f, ConversionStage.TAGGING)
                if (result.musicName != null || result.artist != null || result.album != null || result.cover != null) {
                    TagWriter.embed(source, result)
                }
                checkContinuing(shouldContinue)
                val song = result.toMusicSong(input.name)
                val rendered = if (outputTemplate.trim() == "{title}") {
                    baseName(input.name)
                } else {
                    OutputTemplate.render(
                        template = outputTemplate,
                        song = song,
                        platform = "本地",
                        qualityLabel = if (outputFormat == OutputFormat.ORIGINAL) result.ext.uppercase() else outputFormat.name,
                        bitrateKbps = bitrateKbps.takeIf { outputFormat.usesBitrate },
                    )
                }
                val requestedOutput = File(outputDirectory, "$rendered.$outputExt")
                val output = resolveDestination(requestedOutput, policy)
                if (output == null) {
                    println("输出文件已存在: ${requestedOutput.absolutePath}")
                    onProgress(1f, ConversionStage.COMPLETED)
                    return ConversionOutcome(inputPath, null, skipped = true, outputPath = requestedOutput.absolutePath)
                }
                onProgress(0.94f, ConversionStage.WRITING)
                withOutputLock(output) {
                    moveIntoPlace(source, output)
                }
                finalOutput = output
            } finally {
                decoded.delete()
                transcoded?.delete()
            }

            val output = checkNotNull(finalOutput)
            onProgress(1f, ConversionStage.COMPLETED)
            println("转换成功文件: ${output.absolutePath}")
            ConversionOutcome(inputPath, null, outputPath = output.absolutePath)
        } catch (e: ConversionCancelledException) {
            throw e
        } catch (e: Exception) {
            println("转换失败文件: $inputPath")
            e.printStackTrace()
            ConversionOutcome(inputPath, e.message ?: e.toString())
        }
    }

    /**
     * 计算解密后音频的 SHA-256(用于内容去重)。
     * 必须对解密后的音频而非加密文件做哈希,否则同名歌曲因元数据不同会被误判为不同。
     */
    fun audioSha256(inputPath: String): String? {
        val input = File(inputPath)
        val decoder = Formats.get(input.name) ?: return null
        val data = Files.readAllBytes(input.toPath())
        val result = decoder.decode(data, input.name)
        val digest = MessageDigest.getInstance("SHA-256").digest(result.data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 递归收集 path 下的所有受支持加密音乐文件。 */
    fun listAllFiles(files: MutableList<File>, file: File) {
        if (!file.isDirectory) {
            if (Formats.isSupported(file.name)) files.add(file)
            return
        }
        file.listFiles()?.forEach { listAllFiles(files, it) }
    }

    private fun baseName(fileName: String): String {
        val idx = fileName.lastIndexOf('.')
        return if (idx > 0) fileName.substring(0, idx) else fileName
    }

    private fun serializationKey(inputPath: String): String =
        baseName(File(inputPath).name).lowercase()

    private fun MusicResult.toMusicSong(fileName: String): MusicSong = MusicSong(
        id = fileName,
        name = musicName?.takeIf(String::isNotBlank) ?: baseName(fileName),
        artists = artist?.split('/', '、', ',', '；', ';')?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
        albumName = album?.takeIf(String::isNotBlank),
        coverUrl = null,
    )

    private fun OutputFormat.toTranscodeFormat(): TranscodeFormat = when (this) {
        OutputFormat.ORIGINAL -> error("原始格式不需要转码")
        OutputFormat.MP3 -> TranscodeFormat.MP3
        OutputFormat.FLAC -> TranscodeFormat.FLAC
        OutputFormat.M4A -> TranscodeFormat.M4A
        OutputFormat.OGG -> TranscodeFormat.OGG
        OutputFormat.OPUS -> TranscodeFormat.OPUS
        OutputFormat.WAV -> TranscodeFormat.WAV
    }

    private fun resolveDestination(requested: File, policy: DownloadExistingPolicy): File? {
        if (!requested.exists()) return requested
        return when (policy) {
            DownloadExistingPolicy.SKIP, DownloadExistingPolicy.UPGRADE -> null
            DownloadExistingPolicy.OVERWRITE -> requested
            DownloadExistingPolicy.RENAME -> uniqueFile(requested)
        }
    }

    private fun uniqueFile(requested: File): File {
        var index = 2
        while (true) {
            val candidate = File(requested.parentFile, "${requested.nameWithoutExtension} ($index).${requested.extension}")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun checkContinuing(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) throw ConversionCancelledException()
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun <T> withOutputLock(output: File, block: () -> T): T {
        val key = output.toPath().toAbsolutePath().normalize()
        val lock = outputLocks[(key.hashCode() and Int.MAX_VALUE) % outputLocks.size]
        return synchronized(lock) { block() }
    }
}
