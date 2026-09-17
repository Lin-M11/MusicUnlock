package musicunlock.update

import java.awt.Desktop
import java.io.File

/** 打开已下载并校验的安装包，由操作系统继续完成安装流程。 */
object UpdateInstaller {
    fun openInstaller(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "安装包不存在：${file.absolutePath}" }
        require(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) { "当前系统不支持打开安装包" }
        Desktop.getDesktop().open(file)
    }
}
