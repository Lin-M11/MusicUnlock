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
import musicunlock.settings.OutputFormat
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** 单个文件的批量转换结果。 */
data class ConversionOutcome(
    val inputPath: String,
    val error: String?,
    val skipped: Boolean = false,
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
                                convert(inputPath, outputDir, outputFormat, bitrateKbps, forceOverwrite)
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
    ): String? = convert(inputPath, outputDir, outputFormat, bitrateKbps, forceOverwrite).error

    private fun convert(
        inputPath: String,
        outputDir: String,
        outputFormat: OutputFormat,
        bitrateKbps: Int,
        forceOverwrite: Boolean,
    ): ConversionOutcome {
        return try {
            val input = File(inputPath)
            if (!input.isFile) return ConversionOutcome(inputPath, "不是有效的文件: $inputPath")

            val decoder = Formats.get(input.name) ?: run {
                println("不支持的格式,已跳过: $inputPath")
                return ConversionOutcome(inputPath, "不支持的格式: $inputPath")
            }

            val data = Files.readAllBytes(input.toPath())
            val outputDirectory = File(outputDir).apply { mkdirs() }
            val outputExt = when (outputFormat) {
                OutputFormat.ORIGINAL -> decoder.outputExtension(data, input.name)
                OutputFormat.MP3 -> "mp3"
            }
            val outName = baseName(input.name) + "." + outputExt
            val output = File(outputDirectory, outName)

            if (!forceOverwrite && output.isFile) {
                println("跳过已完成文件: ${output.absolutePath}")
                return ConversionOutcome(inputPath, null, skipped = true)
            }

            val result = decoder.decode(data, input.name)
            val decoded = File.createTempFile("musicunlock-", ".${result.ext}", outputDirectory)
            var transcoded: File? = null

            try {
                Files.write(decoded.toPath(), result.data)
                val source = when (outputFormat) {
                    OutputFormat.ORIGINAL -> decoded
                    OutputFormat.MP3 -> {
                        val target = File.createTempFile("musicunlock-", ".mp3", outputDirectory)
                        transcoded = target
                        val error = AudioTranscoder.toMp3(decoded, target, bitrateKbps)
                        if (error != null) return ConversionOutcome(inputPath, error)
                        target
                    }
                }
                withOutputLock(output) {
                    Files.move(
                        source.toPath(),
                        output.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                    if (result.musicName != null || result.artist != null || result.album != null || result.cover != null) {
                        TagWriter.embed(output, result)
                    }
                }
            } finally {
                decoded.delete()
                transcoded?.delete()
            }

            println("转换成功文件: ${output.absolutePath}")
            ConversionOutcome(inputPath, null)
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

    private fun <T> withOutputLock(output: File, block: () -> T): T {
        val key = output.toPath().toAbsolutePath().normalize()
        val lock = outputLocks[(key.hashCode() and Int.MAX_VALUE) % outputLocks.size]
        return synchronized(lock) { block() }
    }
}
