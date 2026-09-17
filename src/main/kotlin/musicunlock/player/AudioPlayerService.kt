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
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(PlayerSnapshot())
    val state: StateFlow<PlayerSnapshot> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var monitorJob: Job? = null
    private var generation = 0L
    private var closing = false

    @Synchronized
    fun playQueue(tracks: List<PlayerTrack>, index: Int = 0) {
        if (tracks.isEmpty()) return
        playAt(tracks, index.coerceIn(0, tracks.lastIndex))
    }

    @Synchronized
    fun toggle() {
        val snapshot = mutableState.value
        when (snapshot.state) {
            PlayerPlaybackState.PLAYING -> {
                engine.pause()
                mutableState.value = snapshot.copy(state = PlayerPlaybackState.PAUSED, positionMillis = engine.positionMillis())
            }
            PlayerPlaybackState.PAUSED -> {
                engine.play()
                mutableState.value = snapshot.copy(state = PlayerPlaybackState.PLAYING)
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
    }

    @Synchronized
    fun setVolume(volume: Float) {
        val normalized = volume.coerceIn(0f, 1f)
        engine.setVolume(normalized)
        mutableState.value = mutableState.value.copy(volume = normalized)
    }

    @Synchronized
    fun stop() {
        generation++
        loadJob?.cancel()
        monitorJob?.cancel()
        engine.stop()
        mutableState.value = PlayerSnapshot(volume = mutableState.value.volume)
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
                    )
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

        val provider = providerResolver(track.platformId) ?: error("未找到播放平台：${track.platformId}")
        val source = provider.playback(track.song, track.quality)
        val downloaded = File(cacheDir, track.cacheKey() + ".source")
        downloaded.delete()
        provider.download(source.url, downloaded.toPath())
        if (!downloaded.isFile || downloaded.length() == 0L) {
            downloaded.delete()
            error("播放地址没有返回音频数据")
        }

        val temporary = File(cacheDir, track.cacheKey() + ".tmp.wav")
        try {
            if (source.formatHint.equals("wav", ignoreCase = true)) {
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
                    next()
                    return@launch
                }
                mutableState.value = snapshot.copy(positionMillis = position, durationMillis = duration)
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
    val raw = "$platformId:${song.id}:${quality.name}:${song.durationSeconds ?: 0}"
    return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

fun defaultPlayerCacheDir(): File = File(System.getProperty("user.home"), ".musicunlock/player-cache")
