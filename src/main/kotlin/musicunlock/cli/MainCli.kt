package musicunlock.cli

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import musicunlock.core.Formats
import musicunlock.service.MusicConverter
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 命令行入口:
 *   -c,--convert [path] ...  转换 path 下的所有加密音乐文件
 *   -o,--output [dir]        指定输出目录(默认 ./output)
 *   -d,--dedup               按解密后音频内容去重
 *   -v,--view                打开图形界面(默认)
 *   -h,--help                帮助
 */
object MainCli {

    fun handle(args: Array<String>): Int {
        val inputs = mutableListOf<String>()
        var outputDirArg: String? = null
        var dedup = false

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "-o", "--output", "--output-dir" -> {
                    if (i + 1 < args.size) {
                        outputDirArg = args[++i]
                    } else {
                        println("缺少 -o/--output 的目录参数")
                        return 1
                    }
                }
                "-d", "--dedup" -> dedup = true
                "-h", "--help" -> {
                    printHelp()
                    return 0
                }
                else -> inputs.add(args[i])
            }
            i++
        }

        if (inputs.isEmpty()) {
            println("请至少指定一个文件或文件夹路径")
            println("用法: java -jar MusicUnlock.jar -c [path] ... [-o outputDir] [-d]")
            return 1
        }

        // 确定输出目录
        val outputPath = File(outputDirArg ?: "output").also {
            if (!it.isDirectory) it.mkdirs()
        }
        if (!outputPath.isDirectory) {
            println("无法创建输出目录: ${outputPath.absolutePath}")
            return 1
        }
        println("Output dir is set to: ${outputPath.absolutePath}")

        // 收集所有加密音乐文件
        val files = mutableListOf<File>()
        for (param in inputs) {
            MusicConverter.listAllFiles(files, File(param))
        }
        if (files.isEmpty()) {
            println("没有找到支持的加密音乐文件")
            return 1
        }
        println("找到 ${files.size} 个加密音乐文件")

        val selected = if (dedup) dedup(files) else files
        if (selected.size != files.size) {
            println("去重后剩余 ${selected.size} 个文件")
        }

        val outputDir = outputPath.absolutePath
        val pool = Executors.newFixedThreadPool(minOf(8, selected.size.coerceAtLeast(1)))
        var success = 0
        var failed = 0
        val futures = selected.map { f ->
            pool.submit<Boolean> {
                val error = MusicConverter.convertWithError(f.absolutePath, outputDir)
                if (error == null) success++ else failed++
                error == null
            }
        }
        futures.forEach { it.get() }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.MINUTES)
        println("所有任务执行完成, 成功: $success, 失败: $failed")
        return if (failed == 0) 0 else 1
    }

    /** 按解密后音频 SHA-256 去重,优先保留不带 "(N)" 后缀的文件。 */
    private fun dedup(files: MutableList<File>): List<File> {
        files.sortBy { if (it.name.matches(Regex(".*\\(\\d+\\).*\\.[a-zA-Z0-9]+$"))) 1 else 0 }
        val seen = HashSet<String>()
        val unique = mutableListOf<File>()
        for (f in files) {
            val hash = try {
                MusicConverter.audioSha256(f.absolutePath)
            } catch (e: Exception) {
                println("计算音频哈希失败,已跳过: ${f.absolutePath}")
                continue
            } ?: continue
            if (seen.add(hash)) {
                unique.add(f)
            } else {
                println("跳过重复文件(音频内容相同): ${f.absolutePath}")
            }
        }
        return unique
    }

    fun printHelp() {
        println("MusicUnlock - 多平台加密音乐格式转换工具 (Kotlin + Compose Multiplatform)")
        println("支持格式: ${Formats.supportedExtensions().joinToString(" / ")}")
        println()
        println("Usage: MusicUnlock [command]")
        println("If don't add command, there will open MusicUnlock GUI directly")
        println("[Command List]")
        println("-v,-view                         : open MusicUnlock GUI(default command)")
        println("-c,--convert [path] ...          : convert encrypted music files in path")
        println("                                  (支持文件或文件夹,可多个路径)")
        println("-o,--output [dir]                : custom output directory(default ./output)")
        println("-d,--dedup                       : skip duplicate files by content hash")
        println("-h,-help                         : Help about any command")
        println()
        println("Example:")
        println("  MusicUnlock -c ~/Music -o ~/Music/mp3 -d")
    }
}
