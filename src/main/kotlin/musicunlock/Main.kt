package musicunlock

import musicunlock.cli.MainCli
import musicunlock.ui.showWindow
import javax.swing.SwingUtilities

/**
 * 入口:无参数或 -v/--view 打开图形界面;
 * -c/--convert 与 -h/--help 走命令行。
 */
fun main(args: Array<String>) {
    val cliMode = args.any { it == "-c" || it == "--convert" || it == "-h" || it == "--help" }
    if (cliMode) {
        exitProcess(MainCli.handle(args))
    } else {
        SwingUtilities.invokeLater {
            showWindow()
        }
    }
}

private fun exitProcess(code: Int): Nothing {
    kotlin.system.exitProcess(code)
}
