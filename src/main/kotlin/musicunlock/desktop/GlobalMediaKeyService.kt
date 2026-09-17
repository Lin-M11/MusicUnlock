package musicunlock.desktop

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import musicunlock.player.AudioPlayerService
import java.util.logging.Level
import java.util.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean

/** 注册系统级媒体键；系统拒绝权限时静默降级为窗口快捷键。 */
object GlobalMediaKeyService : NativeKeyListener {
    private val started = AtomicBoolean(false)
    private var player: AudioPlayerService? = null

    fun start(audioPlayer: AudioPlayerService) {
        player = audioPlayer
        if (started.getAndSet(true)) return
        runCatching {
            Logger.getLogger(GlobalScreen::class.java.packageName).level = Level.WARNING
            GlobalScreen.registerNativeHook()
            GlobalScreen.addNativeKeyListener(this)
        }.onFailure {
            started.set(false)
            println("全局媒体键不可用：${it.message}")
        }
    }

    fun stop() {
        if (!started.getAndSet(false)) return
        runCatching {
            GlobalScreen.removeNativeKeyListener(this)
            GlobalScreen.unregisterNativeHook()
        }
        player = null
    }

    override fun nativeKeyPressed(event: NativeKeyEvent?) {
        val current = player ?: return
        when (event?.keyCode) {
            NativeKeyEvent.VC_MEDIA_PLAY -> current.toggle()
            NativeKeyEvent.VC_MEDIA_NEXT -> current.next()
            NativeKeyEvent.VC_MEDIA_PREVIOUS -> current.previous()
            NativeKeyEvent.VC_MEDIA_STOP -> current.stop()
        }
    }
}
