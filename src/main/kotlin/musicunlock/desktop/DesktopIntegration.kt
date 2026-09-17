package musicunlock.desktop

import java.awt.AWTException
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference

/** 托盘、桌面通知和下载期间防休眠。 */
object DesktopIntegration {
    private val trayIcon = AtomicReference<TrayIcon?>()
    private val inhibitor = AtomicReference<Process?>()

    fun installTray(onShow: () -> Unit, onExit: () -> Unit): Boolean {
        if (!SystemTray.isSupported()) return false
        if (trayIcon.get() != null) return true
        return runCatching {
            val image = trayImage()
            val icon = TrayIcon(image, "MusicUnlock").apply {
                isImageAutoSize = true
                addActionListener { onShow() }
                popupMenu = PopupMenu().apply {
                    add(MenuItem("显示 MusicUnlock").apply { addActionListener { onShow() } })
                    addSeparator()
                    add(MenuItem("退出").apply { addActionListener { onExit() } })
                }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon.set(icon)
            true
        }.getOrDefault(false)
    }

    fun notify(title: String, body: String) {
        val icon = trayIcon.get() ?: return
        runCatching { icon.displayMessage(title, body, TrayIcon.MessageType.INFO) }
    }

    fun updatePreventSleep(required: Boolean) {
        if (required) startInhibitor() else stopInhibitor()
    }

    private fun startInhibitor() {
        if (inhibitor.get()?.isAlive == true) return
        val command = when {
            os().contains("mac") -> listOf("caffeinate", "-dimsu", "-t", "86400")
            os().contains("win") -> listOf(
                "powershell", "-NoProfile", "-Command",
                """
                Add-Type -Namespace Win32 -Name Power -MemberDefinition '[DllImport("kernel32.dll")] public static extern uint SetThreadExecutionState(uint esFlags);';
                [Win32.Power]::SetThreadExecutionState(0x80000003) | Out-Null;
                while (${'$'}true) { Start-Sleep -Seconds 30 }
                """.trimIndent(),
            )
            else -> listOf("systemd-inhibit", "--what=idle:sleep", "--why=MusicUnlock downloading", "--mode=block", "sleep", "infinity")
        }
        runCatching {
            inhibitor.set(ProcessBuilder(command).redirectErrorStream(true).start())
        }
    }

    private fun stopInhibitor() {
        val process = inhibitor.getAndSet(null) ?: return
        runCatching {
            process.destroy()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun trayImage(): BufferedImage {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.color = Color(0xEA, 0x58, 0x0C)
            graphics.fillRoundRect(4, 4, 56, 56, 18, 18)
            graphics.color = Color.WHITE
            graphics.font = Font("SansSerif", Font.BOLD, 40)
            graphics.drawString("♪", 13, 48)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun os(): String = System.getProperty("os.name").lowercase()
}
