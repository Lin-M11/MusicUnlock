package musicunlock

import musicunlock.cli.MainCli
import musicunlock.diagnostics.Diagnostics
import musicunlock.ui.showWindow
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * 入口:无参数或 -v/--view 打开图形界面;
 * -c/--convert 与 -h/--help 走命令行。
 */
fun main(args: Array<String>) {
    Diagnostics.initialize()
    val cliMode = args.isNotEmpty() && args.none { it == "-v" || it == "--view" }
    if (cliMode) {
        configureCliOutput()
        exitProcess(MainCli.handle(args))
    } else {
        showWindow()
    }
}

private fun configureCliOutput() {
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
    System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8))
}

private fun exitProcess(code: Int): Nothing {
    kotlin.system.exitProcess(code)
}
