package musicunlock.desktop

import java.io.File

/** 跨平台开机启动管理。开发态使用当前 Java 命令，打包态使用当前应用可执行文件。 */
object AutoStartService {
    fun setEnabled(enabled: Boolean): Result<Unit> = runCatching {
        val command = currentCommand()
        require(command.isNotEmpty()) { "无法确定应用启动命令" }
        when {
            isMac() -> setMacEnabled(enabled, command)
            isWindows() -> setWindowsEnabled(enabled, command)
            else -> setLinuxEnabled(enabled, command)
        }
    }

    fun isEnabled(): Boolean = runCatching {
        when {
            isMac() -> macPlist().isFile
            isWindows() -> windowsRead()?.contains("MusicUnlock") == true
            else -> linuxDesktop().isFile
        }
    }.getOrDefault(false)

    private fun currentCommand(): List<String> {
        val processCommand = ProcessHandle.current().info().command().orElse(null)
        val javaHome = System.getProperty("java.home")?.let(::File)
        val javaExecutable = javaHome?.resolve(if (isWindows()) "bin/java.exe" else "bin/java")?.absolutePath
        return when {
            processCommand == null -> emptyList()
            processCommand.substringAfterLast('/').startsWith("java") ||
                processCommand.substringAfterLast('\\').startsWith("java") -> buildList {
                add(processCommand)
                val classPath = System.getProperty("java.class.path").orEmpty()
                if (classPath.isNotBlank()) addAll(listOf("-cp", classPath, "musicunlock.MainKt"))
            }.takeIf { it.size >= 4 }
            else -> listOf(processCommand)
        }.orEmpty().ifEmpty {
            val classPath = System.getProperty("java.class.path").orEmpty()
            if (javaExecutable != null && classPath.isNotBlank()) {
                listOf(javaExecutable, "-cp", classPath, "musicunlock.MainKt")
            } else {
                emptyList()
            }
        }
    }

    private fun setMacEnabled(enabled: Boolean, command: List<String>) {
        val plist = macPlist()
        if (!enabled) {
            runCommand(listOf("launchctl", "unload", "-w", plist.absolutePath))
            plist.delete()
            return
        }
        plist.parentFile?.mkdirs()
        val args = command.joinToString("") { "        <string>${xmlEscape(it)}</string>\n" }
        plist.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>Label</key><string>com.musicunlock.app</string>
              <key>ProgramArguments</key>
              <array>
            $args  </array>
              <key>RunAtLoad</key><true/>
            </dict>
            </plist>
            """.trimIndent(),
        )
        runCommand(listOf("launchctl", "load", "-w", plist.absolutePath))
    }

    private fun setLinuxEnabled(enabled: Boolean, command: List<String>) {
        val desktop = linuxDesktop()
        if (!enabled) {
            desktop.delete()
            return
        }
        desktop.parentFile?.mkdirs()
        val exec = command.joinToString(" ") { shellEscape(it) }
        desktop.writeText(
            """
            [Desktop Entry]
            Type=Application
            Name=MusicUnlock
            Comment=多平台加密音乐格式转换工具
            Exec=$exec
            Terminal=false
            X-GNOME-Autostart-enabled=true
            """.trimIndent(),
        )
    }

    private fun setWindowsEnabled(enabled: Boolean, command: List<String>) {
        if (!enabled) {
            runCommand(listOf("reg", "delete", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "MusicUnlock", "/f"))
            return
        }
        val executable = command.joinToString(" ")
        runCommand(listOf("reg", "add", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "MusicUnlock", "/t", "REG_SZ", "/d", executable, "/f"))
    }

    private fun windowsRead(): String? = runCommand(
        listOf("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "/v", "MusicUnlock"),
        capture = true,
    ).second

    private fun runCommand(command: List<String>, capture: Boolean = false): Pair<Int, String?> = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor() to output.takeIf { capture }
    }.getOrDefault(-1 to null)

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun shellEscape(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun macPlist() = File(System.getProperty("user.home"), "Library/LaunchAgents/com.musicunlock.app.plist")
    private fun linuxDesktop() = File(System.getProperty("user.home"), ".config/autostart/musicunlock.desktop")
    private fun isMac() = System.getProperty("os.name").lowercase().contains("mac")
    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
}
