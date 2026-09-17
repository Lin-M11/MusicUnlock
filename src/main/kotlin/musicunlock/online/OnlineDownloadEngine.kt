package musicunlock.online

import musicunlock.library.LibraryIndex
import musicunlock.service.AudioTranscoder
import musicunlock.service.AudioTagData
import musicunlock.service.TagWriter
import musicunlock.settings.AppSettings
import musicunlock.settings.DownloadExistingPolicy
import musicunlock.settings.LyricsMode
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class DownloadExecutionResult(
    val file: File,
    val qualityLabel: String?,
    val skipped: Boolean = false,
    val message: String? = null,
)

/** 单个在线歌曲的下载流水线：解析地址、下载、转码、歌词、标签和曲库登记。 */
object OnlineDownloadEngine {
    private val outputLocks = Array(64) { Any() }

    fun execute(
        provider: OnlineMusicProvider,
        song: MusicSong,
        outputDir: File,
        preferences: DownloadPreferences,
        settings: AppSettings,
        playlistName: String? = null,
        library: LibraryIndex,
        onState: (DownloadTaskState, String?) -> Unit,
        onProgress: (downloaded: Long, total: Long?, bytesPerSecond: Long) -> Unit,
        shouldContinue: () -> Boolean,
        seed: String,
    ): DownloadExecutionResult {
        OnlineNetwork.configure(settings)
        outputDir.mkdirs()
        check(outputDir.isDirectory) { "无法创建输出目录：${outputDir.absolutePath}" }
        if (preferences.existingFilePolicy == DownloadExistingPolicy.SKIP && library.contains(song, provider.platform.id)) {
            onState(DownloadTaskState.SKIPPED, "本地曲库中已有该歌曲")
            return DownloadExecutionResult(File(outputDir, song.name), null, skipped = true, message = "曲库中已有该歌曲")
        }

        onState(DownloadTaskState.DOWNLOADING, "正在解析播放地址")
        val source = provider.playback(song, preferences.quality)
        val part = File(outputDir, ".musicunlock-$seed.part")
        provider.download(
            url = source.url,
            target = part.toPath(),
            offset = part.length(),
            onProgress = onProgress,
            shouldContinue = shouldContinue,
        )
        if (!shouldContinue()) throw ClassifiedDownloadException(DownloadErrorKind.CANCELLED, "下载已暂停", true)
        if (!part.isFile || part.length() == 0L) {
            throw ClassifiedDownloadException(DownloadErrorKind.DECODE, "下载结果为空", true)
        }

        val sourceExt = sourceAudioExtension(part)
        var audio = part
        var finalExt = sourceExt
        onState(DownloadTaskState.TRANSCODING, "正在处理音频")
        if (preferences.forceMp3 && !sourceExt.equals("mp3", ignoreCase = true)) {
            val mp3 = File(outputDir, ".musicunlock-$seed.mp3")
            val error = AudioTranscoder.toMp3(part, mp3, preferences.mp3BitrateKbps)
            if (error != null) {
                mp3.delete()
                throw ClassifiedDownloadException(DownloadErrorKind.DECODE, error, true)
            }
            audio = mp3
            finalExt = "mp3"
        }

        val template = preferences.outputTemplate.ifBlank {
            if (preferences.forceMp3) "{artist} - {title}" else "{artist}/{album}/{title}"
        }
        val rendered = OutputTemplate.render(
            template = template,
            song = song,
            platform = provider.platform.displayName,
            playlistName = playlistName,
            qualityLabel = source.qualityLabel,
            bitrateKbps = source.bitrateKbps,
        )
        val requested = File(outputDir, "$rendered.$finalExt")
        val destination = resolveDestination(requested, source, preferences.existingFilePolicy)
        if (destination == null) {
            deleteTemp(part, audio)
            onState(DownloadTaskState.SKIPPED, "曲库中已有该歌曲")
            return DownloadExecutionResult(requested, source.qualityLabel, skipped = true, message = "已存在，已跳过")
        }

        onState(DownloadTaskState.TAGGING, "正在写入标签与封面")
        val lyrics = runCatching { provider.lyrics(song) }.getOrNull()
        val cover = song.coverUrl?.let { runCatching { provider.bytes(it) }.getOrNull() }
        val target = moveIntoPlace(audio, destination)
        val tagWritten = TagWriter.embed(
            target,
            AudioTagData(
                title = song.name,
                artist = song.artistText,
                album = song.albumName,
                trackNumber = song.trackNumber,
                discNumber = song.discNumber,
                year = song.year,
                genre = song.genre,
                composer = song.composer,
                isrc = song.isrc,
                lyrics = lyrics?.let(TagWriter::mergedLyrics),
                cover = cover,
                platform = provider.platform.id,
                sourceSongId = song.id,
                quality = source.qualityLabel,
            ),
        )
        if (!tagWritten) {
            onState(DownloadTaskState.TAGGING, "音频已下载，但部分标签写入失败")
        }
        if (lyrics != null && preferences.lyricsMode !in setOf(LyricsMode.OFF)) {
            TagWriter.writeLyrics(target, lyrics, preferences.lyricsMode)
        }
        if (preferences.writeCoverSidecar && cover != null && cover.isNotEmpty()) {
            TagWriter.writeCoverSidecar(target, cover)
        }
        library.upsert(
            file = target,
            platform = provider.platform.id,
            sourceSongId = song.id,
            hash = false,
        )
        deleteTemp(part, audio, target)
        val finalQuality = if (preferences.forceMp3 && !sourceExt.equals("mp3", ignoreCase = true)) {
            val sourceLabel = source.qualityLabel ?: sourceExt.uppercase()
            "MP3 ${preferences.mp3BitrateKbps}k（源 $sourceLabel）"
        } else {
            source.qualityLabel ?: finalExt.uppercase()
        }
        return DownloadExecutionResult(target, finalQuality, message = finalQuality)
    }

    private fun resolveDestination(
        requested: File,
        source: PlaybackSource,
        policy: DownloadExistingPolicy,
    ): File? {
        requested.parentFile?.mkdirs()
        if (!requested.exists()) return requested
        return when (policy) {
            DownloadExistingPolicy.SKIP -> null
            DownloadExistingPolicy.OVERWRITE -> requested
            DownloadExistingPolicy.RENAME -> uniqueFile(requested)
            DownloadExistingPolicy.UPGRADE -> if (shouldUpgrade(source, requested)) requested else null
        }
    }

    private fun shouldUpgrade(source: PlaybackSource, existing: File): Boolean {
        if (!source.lossless && (source.bitrateKbps ?: 0) <= 0) return false
        val existingRate = runCatching {
            org.jaudiotagger.audio.AudioFileIO.read(existing).audioHeader.bitRate
        }.getOrNull()?.toIntOrNull() ?: 0
        val desired = source.bitrateKbps ?: if (source.lossless) Int.MAX_VALUE else 0
        return desired > existingRate || (source.lossless && existing.extension.lowercase() != "flac")
    }

    private fun uniqueFile(requested: File): File {
        var index = 2
        while (true) {
            val candidate = File(requested.parentFile, "${requested.nameWithoutExtension} ($index).${requested.extension}")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun moveIntoPlace(source: File, target: File): File {
        val lock = outputLocks[(target.absolutePath.hashCode() and Int.MAX_VALUE) % outputLocks.size]
        return synchronized(lock) {
            target.parentFile?.mkdirs()
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
            target
        }
    }

    private fun sourceAudioExtension(file: File): String {
        return sniffExtension(file)
            ?: throw ClassifiedDownloadException(DownloadErrorKind.DECODE, "下载内容不是有效音频，可能被服务端限制或返回了错误页", true)
    }

    private fun sniffExtension(file: File): String? = runCatching {
        val head = file.inputStream().use { input ->
            val buffer = ByteArray(12)
            val read = input.read(buffer)
            buffer.copyOf(maxOf(0, read))
        }
        when {
            head.size >= 4 && head[0] == 0x66.toByte() && head[1] == 0x4C.toByte() &&
                head[2] == 0x61.toByte() && head[3] == 0x43.toByte() -> "flac"
            head.size >= 4 && head[0] == 0x4F.toByte() && head[1] == 0x67.toByte() &&
                head[2] == 0x67.toByte() && head[3] == 0x53.toByte() -> "ogg"
            head.size >= 12 && head[4] == 0x66.toByte() && head[5] == 0x74.toByte() &&
                head[6] == 0x79.toByte() && head[7] == 0x70.toByte() -> "m4a"
            head.size >= 3 && head[0] == 0x49.toByte() && head[1] == 0x44.toByte() &&
                head[2] == 0x33.toByte() -> "mp3"
            head.size >= 4 && head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
                head[2] == 0x46.toByte() && head[3] == 0x46.toByte() -> "wav"
            head.size >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            else -> null
        }
    }.getOrNull()

    private fun deleteTemp(vararg files: File) {
        files.forEach { file ->
            if (file.name.startsWith(".musicunlock-")) runCatching { file.delete() }
        }
    }
}
