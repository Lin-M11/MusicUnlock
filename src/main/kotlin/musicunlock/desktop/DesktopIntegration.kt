package musicunlock.desktop

import java.awt.Color
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.Taskbar
import java.awt.Window
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.geom.Path2D
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

    fun installApplicationIcon(window: Window) {
        val image = applicationIcon(256)
        window.iconImages = listOf(image)
        if (Taskbar.isTaskbarSupported()) {
            runCatching { Taskbar.getTaskbar().setIconImage(image) }
        }
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

    fun applicationIcon(side: Int = 1024): BufferedImage {
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val inset = side * 96f / 1024f
            val tileSide = side - inset * 2f
            val radius = side * 208f / 1024f
            val tile = java.awt.geom.RoundRectangle2D.Float(inset, inset, tileSide, tileSide, radius, radius)
            graphics.paint = GradientPaint(0f, 0f, Color(0x34, 0x2A, 0x26), side.toFloat(), side.toFloat(), Color(0x15, 0x11, 0x0F))
            graphics.fill(tile)
            graphics.color = Color(255, 255, 255, 18)
            graphics.stroke = java.awt.BasicStroke((side * 4f / 1024f).coerceAtLeast(1f))
            graphics.draw(tile)

            val scale = side / 1024f
            graphics.paint = GradientPaint(294f * scale, 322f * scale, Color(0xFF, 0xFD, 0xFC), 730f * scale, 714f * scale, Color(0xF2, 0xE8, 0xE1))
            graphics.stroke = java.awt.BasicStroke(96f * scale, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            graphics.draw(appMPath(scale))
            graphics.paint = GradientPaint(438f * scale, 548f * scale, Color(0xFF, 0x8A, 0x3D), 586f * scale, 622f * scale, Color(0xEA, 0x58, 0x0C))
            graphics.stroke = java.awt.BasicStroke(34f * scale, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            graphics.draw(appAccentPath(scale))
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun trayImage(): BufferedImage {
        val side = 64
        val image = BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val scale = side / 24f * 1.3f
            graphics.color = Color(0xEA, 0x58, 0x0C)
            graphics.stroke = java.awt.BasicStroke(2.1f * scale, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            graphics.draw(menuBarMPath(scale, side))
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun appMPath(scale: Float): Path2D.Float {
        fun x(value: Float) = value * scale
        fun y(value: Float) = value * scale
        return Path2D.Float().apply {
            moveTo(x(294f), y(714f))
            lineTo(x(294f), y(365f))
            curveTo(x(294f), y(336f), x(331f), y(322f), x(351f), y(343f))
            lineTo(x(454f), y(446f))
            curveTo(x(488f), y(480f), x(536f), y(480f), x(570f), y(446f))
            lineTo(x(673f), y(343f))
            curveTo(x(693f), y(322f), x(730f), y(336f), x(730f), y(365f))
            lineTo(x(730f), y(714f))
        }
    }

    private fun appAccentPath(scale: Float): Path2D.Float {
        fun x(value: Float) = value * scale
        fun y(value: Float) = value * scale
        return Path2D.Float().apply {
            moveTo(x(438f), y(548f))
            lineTo(x(512f), y(622f))
            lineTo(x(586f), y(548f))
        }
    }

    private fun menuBarMPath(scale: Float, side: Int): Path2D.Float {
        fun x(value: Float) = side / 2f + (value - 12f) * scale
        fun y(value: Float) = side / 2f + (value - 12f) * scale
        return Path2D.Float().apply {
            moveTo(x(6.9f), y(16.75f))
            lineTo(x(6.9f), y(8.55f))
            curveTo(x(6.9f), y(7.87f), x(7.76f), y(7.55f), x(8.23f), y(8.04f))
            lineTo(x(10.64f), y(10.45f))
            curveTo(x(11.44f), y(11.25f), x(12.56f), y(11.25f), x(13.36f), y(10.45f))
            lineTo(x(15.77f), y(8.04f))
            curveTo(x(16.24f), y(7.55f), x(17.1f), y(7.87f), x(17.1f), y(8.55f))
            lineTo(x(17.1f), y(16.75f))
        }
    }

    private fun os(): String = System.getProperty("os.name").lowercase()
}
