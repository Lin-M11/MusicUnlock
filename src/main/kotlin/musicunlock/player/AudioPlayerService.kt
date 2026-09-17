package musicunlock.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import musicunlock.library.LibraryEntry
import musicunlock.online.MusicLyrics
import musicunlock.online.MusicSong
import musicunlock.online.ProviderRegistry
import musicunlock.service.AudioTranscoder
import musicunlock.service.TranscodeFormat
import musicunlock.settings.QualityStrategy
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.log10
import kotlin.math.pow

data class PlayerTrack(
    val platformId: String,
    val song: MusicSong,
    val quality: QualityStrategy = QualityStrategy.MP3_320,
    val localPath: String? = null,
)

data class PlaybackAudioPreferences(
    val loudnessNormalization: Boolean = false,
    val bassBoostDb: Int = 0,
    val trebleBoostDb: Int = 0,
    val fadeSeconds: Int = 2,
)

data class PlayerLyricLine(
    val timeMillis: Long,
    val text: String,
)

enum class PlayerPlaybackState { IDLE, LOADING, PLAYING, PAUSED, ERROR }

data class PlayerSnapshot(
    val current: PlayerTrack? = null,
    val queue: List<PlayerTrack> = emptyList(),
    val queueIndex: Int = -1,
    val state: PlayerPlaybackState = PlayerPlaybackState.IDLE,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val volume: Float = 0.85f,
    val message: String? = null,
    val lyrics: List<PlayerLyricLine> = emptyList(),
    val lyricIndex: Int = -1,
    val sleepRemainingMillis: Long = 0L,
)

internal interface AudioPlaybackEngine : AutoCloseable {
    fun load(file: File)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(millis: Long)
    fun setVolume(volume: Float)
    fun positionMillis(): Long
    fun durationMillis(): Long
    fun isPlaying(): Boolean
}

/** 搜索结果的常驻播放器：缓存远程音频并统一解码为 Java Sound 可播放的 PCM WAV。 */
class AudioPlayerService internal constructor(
    private val cacheDir: File = defaultPlayerCacheDir(),
    private val engine: AudioPlaybackEngine = JavaSoundPlaybackEngine(),
    private val providerResolver: (String) -> musicunlock.online.OnlineMusicProvider? = ProviderRegistry::find,
    private val stateFile: File? = null,
    private val onCompleted: (PlayerTrack) -> Unit = {},
    private val onSkipped: (PlayerTrack) -> Unit = {},
    private val audioPreferences: () -> PlaybackAudioPreferences = { PlaybackAudioPreferences() },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(PlayerSnapshot())
    val state: StateFlow<PlayerSnapshot> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var monitorJob: Job? = null
    private var generation = 0L
    private var closing = false
    private var sleepTimerEndAt = 0L

    init {
        restoreState()
    }

    @Synchronized
    fun playQueue(tracks: List<PlayerTrack>, index: Int = 0) {
        if (tracks.isEmpty()) return
        playAt(tracks, index.coerceIn(0, tracks.lastIndex))
    }

    @Synchronized
    fun playLibrary(entries: List<LibraryEntry>, index: Int = 0) {
        val tracks = entries.filter { File(it.path).isFile }.map { entry ->
            PlayerTrack(
                platformId = entry.platform ?: "local",
                quality = QualityStrategy.MP3_320,
                localPath = entry.path,
                song = MusicSong(
                    id = entry.sourceSongId ?: entry.path,
                    name = entry.title ?: File(entry.path).nameWithoutExtension,
                    artists = entry.artist?.split('/', '、', ',', '；', ';')?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
                    albumName = entry.album,
                    coverUrl = null,
                    durationSeconds = entry.durationSeconds,
                    trackNumber = entry.trackNumber,
                    discNumber = entry.discNumber,
                    year = entry.year,
                    genre = entry.genre,
                    composer = entry.composer,
                    isrc = entry.isrc,
                    metadata = mapOf("localPath" to entry.path),
                ),
            )
        }
        if (tracks.isNotEmpty()) playQueue(tracks, index.coerceIn(0, tracks.lastIndex))
    }

    @Synchronized
    fun playFiles(files: List<File>, index: Int = 0) {
        val tracks = files.filter(File::isFile).map { file ->
            PlayerTrack(
                platformId = "local",
                localPath = file.absolutePath,
                song = MusicSong(
                    id = file.absolutePath,
                    name = file.nameWithoutExtension,
                    artists = emptyList(),
                    albumName = null,
                    coverUrl = null,
                    metadata = mapOf("localPath" to file.absolutePath),
                ),
            )
        }
        if (tracks.isNotEmpty()) playQueue(tracks, index.coerceIn(0, tracks.lastIndex))
    }

    @Synchronized
    fun toggle() {
        val snapshot = mutableState.value
        when (snapshot.state) {
            PlayerPlaybackState.PLAYING -> {
                engine.pause()
                mutableState.value = snapshot.copy(state = PlayerPlaybackState.PAUSED, positionMillis = engine.positionMillis())
                persistState()
            }
            PlayerPlaybackState.PAUSED -> {
                engine.play()
                mutableState.value = snapshot.copy(state = PlayerPlaybackState.PLAYING)
                persistState()
                startMonitor()
            }
            PlayerPlaybackState.ERROR, PlayerPlaybackState.IDLE -> {
                val current = snapshot.current ?: return
                playAt(if (snapshot.queue.isEmpty()) listOf(current) else snapshot.queue, snapshot.queueIndex.coerceAtLeast(0))
            }
            PlayerPlaybackState.LOADING -> Unit
        }
    }

    @Synchronized
    fun next() {
        val snapshot = mutableState.value
        if (snapshot.queue.isEmpty()) return
        snapshot.current?.let { runCatching { onSkipped(it) } }
        val nextIndex = if (snapshot.queueIndex + 1 < snapshot.queue.size) snapshot.queueIndex + 1 else 0
        playAt(snapshot.queue, nextIndex)
    }

    @Synchronized
    fun previous() {
        val snapshot = mutableState.value
        if (snapshot.queue.isEmpty()) return
        if (engine.positionMillis() > 5_000L) {
            engine.seekTo(0L)
            mutableState.value = snapshot.copy(positionMillis = 0L)
            return
        }
        val previousIndex = if (snapshot.queueIndex > 0) snapshot.queueIndex - 1 else snapshot.queue.lastIndex
        playAt(snapshot.queue, previousIndex)
    }

    @Synchronized
    fun seekTo(millis: Long) {
        val snapshot = mutableState.value
        if (snapshot.current == null) return
        engine.seekTo(millis.coerceIn(0L, snapshot.durationMillis.coerceAtLeast(0L)))
        mutableState.value = snapshot.copy(positionMillis = engine.positionMillis())
        persistState()
    }

    @Synchronized
    fun moveInQueue(index: Int, delta: Int) {
        val snapshot = mutableState.value
        val target = index + delta
        if (index !in snapshot.queue.indices || target !in snapshot.queue.indices) return
        val queue = snapshot.queue.toMutableList()
        val item = queue.removeAt(index)
        queue.add(target, item)
        val currentIndex = when {
            snapshot.queueIndex == index -> target
            index < snapshot.queueIndex && target >= snapshot.queueIndex -> snapshot.queueIndex - 1
            index > snapshot.queueIndex && target <= snapshot.queueIndex -> snapshot.queueIndex + 1
            else -> snapshot.queueIndex
        }
        mutableState.value = snapshot.copy(queue = queue, queueIndex = currentIndex)
        persistState()
    }

    @Synchronized
    fun removeFromQueue(index: Int) {
        val snapshot = mutableState.value
        if (index !in snapshot.queue.indices) return
        if (snapshot.queue.size == 1) {
            stop()
            return
        }
        val queue = snapshot.queue.toMutableList().also { it.removeAt(index) }
        val currentIndex = when {
            index < snapshot.queueIndex -> snapshot.queueIndex - 1
            index == snapshot.queueIndex -> snapshot.queueIndex.coerceAtMost(queue.lastIndex)
            else -> snapshot.queueIndex
        }
        if (index == snapshot.queueIndex) {
            playAt(queue, currentIndex)
        } else {
            mutableState.value = snapshot.copy(queue = queue, queueIndex = currentIndex)
            persistState()
        }
    }

    @Synchronized
    fun jumpToQueue(index: Int) {
        val snapshot = mutableState.value
        if (index in snapshot.queue.indices) playAt(snapshot.queue, index)
    }

    @Synchronized
    fun cycleSleepTimer() {
        val currentMinutes = ((sleepTimerEndAt - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000L).toInt()
        val nextMinutes = when {
            currentMinutes < 15 -> 15
            currentMinutes < 30 -> 30
            currentMinutes < 60 -> 60
            else -> 0
        }
        sleepTimerEndAt = if (nextMinutes == 0) 0L else System.currentTimeMillis() + nextMinutes * 60_000L
        mutableState.value = mutableState.value.copy(sleepRemainingMillis = (sleepTimerEndAt - System.currentTimeMillis()).coerceAtLeast(0L))
        persistState()
    }

    @Synchronized
    fun setVolume(volume: Float) {
        val normalized = volume.coerceIn(0f, 1f)
        engine.setVolume(normalized)
        mutableState.value = mutableState.value.copy(volume = normalized)
        persistState()
    }

    @Synchronized
    fun stop() {
        generation++
        loadJob?.cancel()
        monitorJob?.cancel()
        engine.stop()
        sleepTimerEndAt = 0L
        mutableState.value = PlayerSnapshot(volume = mutableState.value.volume)
        persistState()
    }

    @Synchronized
    private fun playAt(tracks: List<PlayerTrack>, index: Int) {
        if (closing) return
        val token = ++generation
        loadJob?.cancel()
        monitorJob?.cancel()
        engine.stop()
        val track = tracks[index]
        mutableState.value = PlayerSnapshot(
            current = track,
            queue = tracks,
            queueIndex = index,
            state = PlayerPlaybackState.LOADING,
            volume = mutableState.value.volume,
            message = "正在准备播放",
            lyrics = loadLyrics(track),
        )
        loadJob = scope.launch {
            runCatching { loadTrack(track) }
                .onSuccess { wav ->
                    if (token != generation) return@onSuccess
                    engine.load(wav)
                    engine.setVolume(mutableState.value.volume)
                    engine.play()
                    mutableState.value = mutableState.value.copy(
                        state = PlayerPlaybackState.PLAYING,
                        positionMillis = 0L,
                        durationMillis = engine.durationMillis(),
                        message = null,
                        lyricIndex = -1,
                    )
                    persistState()
                    startMonitor()
                }
                .onFailure { error ->
                    if (token != generation) return@onFailure
                    mutableState.value = mutableState.value.copy(
                        state = PlayerPlaybackState.ERROR,
                        message = error.message ?: error.toString(),
                    )
                }
        }
    }

    private fun loadTrack(track: PlayerTrack): File {
        cacheDir.mkdirs()
        val cached = File(cacheDir, track.cacheKey() + ".wav")
        if (cached.isFile && cached.length() > 0L) return cached

        val local = track.localPath?.let(::File)?.takeIf(File::isFile)
        val downloaded = File(cacheDir, track.cacheKey() + ".source")
        val source = if (local == null) {
            val provider = providerResolver(track.platformId) ?: error("未找到播放平台：${track.platformId}")
            provider.playback(track.song, track.quality)
        } else null
        if (local != null) {
            Files.copy(local.toPath(), downloaded.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            downloaded.delete()
            providerResolver(track.platformId)!!.download(source!!.url, downloaded.toPath())
        }
        if (!downloaded.isFile || downloaded.length() == 0L) {
            downloaded.delete()
            error("播放地址没有返回音频数据")
        }

        val temporary = File(cacheDir, track.cacheKey() + ".tmp.wav")
        try {
            if (local != null && local.extension.lowercase() in setOf("wav", "wave", "aif", "aiff", "au")) {
                Files.move(
                    downloaded.toPath(),
                    temporary.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                val error = AudioTranscoder.transcode(
                    input = downloaded,
                    output = temporary,
                    format = TranscodeFormat.WAV,
                    audioFilters = playbackFilters(track),
                )
                if (error != null) error(error)
            }
            try {
                Files.move(
                    temporary.toPath(),
                    cached.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            downloaded.delete()
            temporary.delete()
        }
        return cached
    }

    private fun playbackFilters(track: PlayerTrack): List<String> {
        val preferences = audioPreferences()
        return buildList {
            if (preferences.loudnessNormalization) add("loudnorm=I=-14:LRA=11:TP=-1.5")
            if (preferences.bassBoostDb != 0) add("equalizer=f=100:t=q:w=1:g=${preferences.bassBoostDb}")
            if (preferences.trebleBoostDb != 0) add("equalizer=f=10000:t=q:w=1:g=${preferences.trebleBoostDb}")
            val fade = preferences.fadeSeconds.coerceIn(0, 12)
            if (fade > 0) {
                add("afade=t=in:st=0:d=$fade")
                val duration = track.song.durationSeconds?.takeIf { it > fade * 2 }
                if (duration != null) add("afade=t=out:st=${duration - fade}:d=$fade")
            }
        }
    }

    private fun loadLyrics(track: PlayerTrack): List<PlayerLyricLine> {
        val local = track.localPath?.let(::File)
        val text = when {
            local != null -> File(local.parentFile, "${local.nameWithoutExtension}.lrc").takeIf(File::isFile)?.readText()
            else -> runCatching { providerResolver(track.platformId)?.lyrics(track.song) }.getOrNull()?.let { lyrics ->
                listOfNotNull(lyrics.original, lyrics.translated, lyrics.romanized).joinToString("\n")
            }
        }
        return parseLrc(text)
    }

    private fun parseLrc(text: String?): List<PlayerLyricLine> {
        if (text.isNullOrBlank()) return emptyList()
        val timePattern = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")
        return text.lineSequence().mapNotNull { line ->
            val matches = timePattern.findAll(line).toList()
            if (matches.isEmpty()) return@mapNotNull null
            val lyric = timePattern.replace(line, "").trim()
            if (lyric.isBlank()) return@mapNotNull null
            matches.lastOrNull()?.let { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                PlayerLyricLine(minutes * 60_000L + seconds * 1_000L + fraction, lyric)
            }
        }.sortedBy { it.timeMillis }.toList()
    }

    private fun restoreState() {
        val file = stateFile ?: return
        val snapshot = runCatching {
            if (!file.isFile) return
            com.google.gson.Gson().fromJson(file.readText(), PlayerSnapshot::class.java)
        }.getOrNull() ?: return
        mutableState.value = snapshot.copy(
            state = PlayerPlaybackState.IDLE,
            positionMillis = 0L,
            message = if (snapshot.current == null) null else "已恢复上次播放队列",
        )
    }

    private fun persistState() {
        val file = stateFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File.createTempFile(file.name, ".tmp", file.parentFile)
            temp.writeText(com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(mutableState.value))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                delay(250L)
                val snapshot = mutableState.value
                if (snapshot.state != PlayerPlaybackState.PLAYING) continue
                val position = engine.positionMillis()
                val duration = engine.durationMillis().takeIf { it > 0L } ?: snapshot.durationMillis
                if (duration > 0L && !engine.isPlaying() && position >= duration - 300L) {
                    snapshot.current?.let { runCatching { onCompleted(it) } }
                    next()
                    return@launch
                }
                val remainingSleep = if (sleepTimerEndAt > 0L) (sleepTimerEndAt - System.currentTimeMillis()).coerceAtLeast(0L) else 0L
                if (sleepTimerEndAt > 0L && remainingSleep == 0L) {
                    sleepTimerEndAt = 0L
                    stop()
                    return@launch
                }
                val lyricIndex = snapshot.lyrics.indexOfLast { it.timeMillis <= position }
                mutableState.value = snapshot.copy(
                    positionMillis = position,
                    durationMillis = duration,
                    lyricIndex = lyricIndex,
                    sleepRemainingMillis = remainingSleep,
                )
                if (System.currentTimeMillis() % 2_000L < 260L) persistState()
            }
        }
    }

    override fun close() {
        synchronized(this) {
            closing = true
            generation++
            loadJob?.cancel()
            monitorJob?.cancel()
            engine.close()
            mutableState.value = PlayerSnapshot()
        }
        scope.cancel()
    }
}

private class JavaSoundPlaybackEngine : AudioPlaybackEngine {
    private var clip: Clip? = null

    override fun load(file: File) {
        closeClip()
        val stream = AudioSystem.getAudioInputStream(file)
        stream.use {
            val next = AudioSystem.getClip()
            next.open(it)
            clip = next
        }
    }

    override fun play() {
        clip?.start()
    }

    override fun pause() {
        clip?.stop()
    }

    override fun stop() {
        clip?.let {
            it.stop()
            it.microsecondPosition = 0L
        }
    }

    override fun seekTo(millis: Long) {
        clip?.microsecondPosition = millis.coerceAtLeast(0L) * 1_000L
    }

    override fun setVolume(volume: Float) {
        val current = clip ?: return
        if (!current.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
        val control = current.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
        val safe = volume.coerceIn(0.0001f, 1f)
        val gain = (20.0 * log10(safe.toDouble())).toFloat()
            .coerceIn(control.minimum, control.maximum)
        control.value = gain
    }

    override fun positionMillis(): Long = clip?.microsecondPosition?.div(1_000L) ?: 0L

    override fun durationMillis(): Long = clip?.microsecondLength?.div(1_000L) ?: 0L

    override fun isPlaying(): Boolean = clip?.isRunning == true

    override fun close() {
        closeClip()
    }

    private fun closeClip() {
        clip?.let {
            runCatching { it.stop() }
            runCatching { it.flush() }
            runCatching { it.close() }
        }
        clip = null
    }
}

private fun PlayerTrack.cacheKey(): String {
    val raw = "$platformId:${song.id}:${quality.name}:${song.durationSeconds ?: 0}:${localPath ?: ""}"
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

fun defaultPlayerCacheDir(): File = File(System.getProperty("user.home"), ".musicunlock/player-cache")

fun defaultPlayerStateFile(): File = File(System.getProperty("user.home"), ".musicunlock/player-state.json")
