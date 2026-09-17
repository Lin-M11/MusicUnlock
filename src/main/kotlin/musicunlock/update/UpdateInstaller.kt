package musicunlock.update

import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** 打开或安排当前平台安装包在应用退出后完成升级。 */
object UpdateInstaller {
    fun openInstaller(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "安装包不存在：${file.absolutePath}" }
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
        } else {
            error("当前系统不支持打开安装包")
        }
    }

    /** macOS 使用后台脚本替换 /Applications 中的应用；其他平台交给系统安装器。 */
    fun installAfterExit(file: File, applicationPath: File = File("/Applications/MusicUnlock.app")): Result<Unit> = runCatching {
        require(file.isFile) { "安装包不存在：${file.absolutePath}" }
        when {
            System.getProperty("os.name").lowercase().contains("mac") && file.extension.equals("dmg", ignoreCase = true) -> {
                val script = createMacInstallerScript(file, applicationPath)
                ProcessBuilder("bash", script.absolutePath)
                    .redirectOutput(File("/tmp/musicunlock-update.log"))
                    .redirectErrorStream(true)
                    .start()
            }
            else -> openInstaller(file).getOrThrow()
        }
    }

    private fun createMacInstallerScript(dmg: File, applicationPath: File): File {
        val script = File.createTempFile("musicunlock-update-", ".sh")
        val currentPid = ProcessHandle.current().pid()
        val dmgArg = shellQuote(dmg.absolutePath)
        val applicationArg = shellQuote(applicationPath.absolutePath)
        val parentArg = shellQuote(applicationPath.absoluteFile.parent.orEmpty())
        script.writeText(
            """
            #!/bin/bash
            set -e
            while kill -0 $currentPid 2>/dev/null; do sleep 0.5; done
            MOUNT=${'$'}(mktemp -d /tmp/musicunlock-update-mount.XXXXXX)
            cleanup() { hdiutil detach "${'$'}MOUNT" >/dev/null 2>&1 || true; }
            trap cleanup EXIT
            hdiutil attach $dmgArg -nobrowse -readonly -mountpoint "${'$'}MOUNT" >/dev/null
            SOURCE="${'$'}MOUNT/MusicUnlock.app"
            if [ ! -d "${'$'}SOURCE" ]; then
              SOURCE="${'$'}(find "${'$'}MOUNT" -maxdepth 2 -name 'MusicUnlock.app' -type d | head -n 1)"
            fi
            [ -d "${'$'}SOURCE" ] || { echo 'DMG 中未找到 MusicUnlock.app'; exit 1; }
            mkdir -p $parentArg
            rm -rf $applicationArg
            /usr/bin/ditto "${'$'}SOURCE" $applicationArg
            cleanup
            trap - EXIT
            open $applicationArg
            """.trimIndent() + "\n",
        )
        runCatching {
            Files.setPosixFilePermissions(
                script.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }
        return script
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
