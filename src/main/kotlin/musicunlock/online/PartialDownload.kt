package musicunlock.online

import com.google.gson.GsonBuilder
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 分片旁边保存的源信息，用于避免把不同音质或不同播放源的数据拼接在一起。 */
data class PartialDownloadMetadata(
    val fingerprint: String,
    val sourceFormat: String? = null,
    val qualityLabel: String? = null,
    val bitrateKbps: Int? = null,
    val lossless: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class PartialDownloadState(
    val part: File,
    val metadataFile: File,
    val offset: Long,
    val metadata: PartialDownloadMetadata,
)

/**
 * 准备可续传分片。已有分片只有在源指纹一致时才会复用；
 * 旧版本留下的无元数据分片也会被丢弃，避免静默拼接损坏文件。
 */
object PartialDownloadStore {
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    fun prepare(outputDir: File, seed: String, source: PlaybackSource): PartialDownloadState {
        outputDir.mkdirs()
        val part = File(outputDir, ".musicunlock-$seed.part")
        val metadataFile = File(outputDir, ".musicunlock-$seed.part.meta.json")
        val metadata = PartialDownloadMetadata(
            fingerprint = source.resumeFingerprint(),
            sourceFormat = source.formatHint,
            qualityLabel = source.qualityLabel,
            bitrateKbps = source.bitrateKbps,
            lossless = source.lossless,
        )
        val existing = read(metadataFile)
        if (part.isFile && (existing?.fingerprint != metadata.fingerprint || part.length() <= 0L)) {
            part.delete()
        }
        if (!part.isFile) metadataFile.delete()
        write(metadataFile, metadata)
        return PartialDownloadState(
            part = part,
            metadataFile = metadataFile,
            offset = part.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L,
            metadata = metadata,
        )
    }

    fun complete(state: PartialDownloadState) {
        state.metadataFile.delete()
    }

    fun discard(outputDir: File, seed: String) {
        File(outputDir, ".musicunlock-$seed.part").delete()
        File(outputDir, ".musicunlock-$seed.part.meta.json").delete()
    }

    private fun read(file: File): PartialDownloadMetadata? = runCatching {
        if (!file.isFile) return null
        gson.fromJson(file.readText(), PartialDownloadMetadata::class.java)
    }.getOrNull()

    private fun write(file: File, metadata: PartialDownloadMetadata) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            temp.writeText(gson.toJson(metadata))
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }
}

fun PlaybackSource.resumeFingerprint(): String {
    val identity = runCatching { URI(url).path }.getOrNull()?.takeIf(String::isNotBlank)
        ?: url.substringBefore('?')
    return listOf(
        identity,
        formatHint.orEmpty(),
        qualityLabel.orEmpty(),
        bitrateKbps?.toString().orEmpty(),
        contentLengthBytes?.toString().orEmpty(),
        lossless.toString(),
    ).joinToString("|")
}
