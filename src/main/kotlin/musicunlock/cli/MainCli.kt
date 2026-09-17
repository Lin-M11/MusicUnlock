package musicunlock.cli

import kotlinx.coroutines.runBlocking
import musicunlock.core.Formats
import musicunlock.service.MusicConverter
import musicunlock.settings.QualityStrategy
import java.io.File

/**
 * 命令行入口:
 *   -c,--convert [path] ...  转换 path 下的所有加密音乐文件
 *   -o,--output [dir]        指定输出目录(默认 ./output)
 *   -j,--jobs [n]            并发转换数(默认 CPU 核数)
 *   -d,--dedup               按解密后音频内容去重
 *   -f,--force               强制重转并覆盖已有输出
 *   -v,--view                打开图形界面(默认)
 *   -h,--help                帮助
 */
object MainCli {

    fun handle(args: Array<String>): Int {
        val inputs = mutableListOf<String>()
        var outputDirArg: String? = null
        var parallelism: Int? = null
        var dedup = false
        var forceOverwrite = false
        var onlineUrl: String? = null
        var onlinePlaylist: String? = null
        var onlineSearch: String? = null
        var onlinePlatform: String? = null
        var onlineQuality: QualityStrategy? = null
        var onlineTemplate: String? = null
        var onlineJson = false
        var downloadSearchResults = false
        var favorites = false
        var onlineLimit = 20
        var onlineRetries: Int? = null
        var onlineRateLimit: Int? = null
        var onlineProxy: String? = null
        var onlineTimeout: Long? = null
        var onlineCookie: String? = null
        var onlineMp3 = false

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
                "-f", "--force" -> forceOverwrite = true
                "-j", "--jobs" -> {
                    if (i + 1 >= args.size) {
                        println("缺少 -j/--jobs 的并发数")
                        return 1
                    }
                    val value = args[++i].toIntOrNull()
                    if (value == null || value < 1) {
                        println("-j/--jobs 必须是大于 0 的整数")
                        return 1
                    }
                    parallelism = value
                }
                "-u", "--url", "--link" -> {
                    if (i + 1 >= args.size) { println("缺少链接参数"); return 1 }
                    onlineUrl = args[++i]
                }
                "-p", "--playlist" -> {
                    if (i + 1 >= args.size) { println("缺少歌单 ID"); return 1 }
                    onlinePlaylist = args[++i]
                }
                "--search" -> {
                    if (i + 1 >= args.size) { println("缺少搜索关键词"); return 1 }
                    onlineSearch = args[++i]
                }
                "--platform" -> {
                    if (i + 1 >= args.size) { println("缺少平台参数"); return 1 }
                    onlinePlatform = args[++i]
                }
                "--quality" -> {
                    if (i + 1 >= args.size) { println("缺少音质参数"); return 1 }
                    onlineQuality = parseQuality(args[++i])
                    if (onlineQuality == null) { println("音质必须是 highest/lossless/320/balanced/smallest"); return 1 }
                }
                "--template" -> {
                    if (i + 1 >= args.size) { println("缺少模板参数"); return 1 }
                    onlineTemplate = args[++i]
                }
                "--json" -> onlineJson = true
                "--download" -> downloadSearchResults = true
                "--favorites" -> favorites = true
                "--limit" -> {
                    val value = args.getOrNull(++i)?.toIntOrNull()
                    if (value == null || value < 1) { println("--limit 必须是大于 0 的整数"); return 1 }
                    onlineLimit = value
                }
                "--retries" -> {
                    val value = args.getOrNull(++i)?.toIntOrNull()
                    if (value == null || value < 0) { println("--retries 必须是非负整数"); return 1 }
                    onlineRetries = value
                }
                "--rate-limit" -> {
                    val value = args.getOrNull(++i)?.toIntOrNull()
                    if (value == null || value < 0) { println("--rate-limit 必须是非负整数"); return 1 }
                    onlineRateLimit = value
                }
                "--proxy" -> {
                    if (i + 1 >= args.size) { println("缺少代理地址"); return 1 }
                    onlineProxy = args[++i]
                }
                "--timeout" -> {
                    val value = args.getOrNull(++i)?.toLongOrNull()
                    if (value == null || value < 5) { println("--timeout 必须大于等于 5 秒"); return 1 }
                    onlineTimeout = value
                }
                "--cookie" -> {
                    if (i + 1 >= args.size) { println("缺少 Cookie"); return 1 }
                    onlineCookie = args[++i]
                }
                "--mp3" -> onlineMp3 = true
                "-h", "--help" -> {
                    printHelp()
                    return 0
                }
                else -> inputs.add(args[i])
            }
            i++
        }

        if (onlineUrl != null || onlinePlaylist != null || onlineSearch != null || favorites) {
            return OnlineCliRunner.run(
                OnlineCliOptions(
                    url = onlineUrl,
                    playlistId = onlinePlaylist,
                    search = onlineSearch,
                    platform = onlinePlatform,
                    outputDir = outputDirArg ?: "output",
                    quality = onlineQuality,
                    outputTemplate = onlineTemplate,
                    json = onlineJson,
                    downloadSearchResults = downloadSearchResults,
                    favorites = favorites,
                    limit = onlineLimit,
                    retries = onlineRetries,
                    rateLimitKbps = onlineRateLimit,
                    proxy = onlineProxy,
                    timeoutSeconds = onlineTimeout,
                    cookie = onlineCookie,
                    forceMp3 = onlineMp3,
                ),
            )
        }

        if (inputs.isEmpty()) {
            println("请至少指定一个文件或文件夹路径")
            println("用法: java -jar MusicUnlock.jar -c [path] ... [-o outputDir] [-j jobs] [-d] [-f]")
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
        val jobs = parallelism ?: MusicConverter.defaultParallelism()
        println("并发转换数: $jobs")
        val outcomes = runBlocking {
            MusicConverter.convertBatch(
                inputPaths = selected.map { it.absolutePath },
                outputDir = outputDir,
                forceOverwrite = forceOverwrite,
                parallelism = jobs,
            )
        }
        val success = outcomes.count { it.succeeded }
        val failed = outcomes.size - success
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

    private fun parseQuality(value: String): QualityStrategy? = when (value.lowercase()) {
        "highest", "high", "最高" -> QualityStrategy.HIGHEST
        "lossless", "flac", "无损" -> QualityStrategy.LOSSLESS_FIRST
        "320", "320k", "mp3" -> QualityStrategy.MP3_320
        "balanced", "均衡" -> QualityStrategy.BALANCED
        "smallest", "small", "最小" -> QualityStrategy.SMALLEST
        else -> null
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
        println("-j,--jobs [n]                    : parallel conversion count(default CPU cores)")
        println("-d,--dedup                       : skip duplicate files by content hash")
        println("-f,--force                       : overwrite existing output files")
        println("-u,--url [link]                  : download a song/playlist/album/artist share link")
        println("--playlist [id]                  : download one playlist id")
        println("--search [keyword]               : search without downloading")
        println("--search [keyword] --download    : search and download song results")
        println("--platform [netease|qq|kugou|kuwo] : restrict online operations to one platform")
        println("--favorites                      : download favorite/collection playlists")
        println("--quality [highest|lossless|320|balanced|smallest]")
        println("--template [pattern]             : file naming template")
        println("--json                           : emit JSON progress and results")
        println("--retries [n] --rate-limit [KB/s] --timeout [seconds] --proxy [url]")
        println("--cookie [header]                : use a one-off login cookie with --platform")
        println("--mp3                            : transcode online results to MP3")
        println("-h,-help                         : Help about any command")
        println()
        println("Example:")
        println("  MusicUnlock -c ~/Music -o ~/Music/mp3 -j 8 -d -f")
    }
}
