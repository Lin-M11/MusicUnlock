package musicunlock.cli

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import musicunlock.core.Formats
import musicunlock.automation.DesktopAutomationService
import musicunlock.library.LibraryExportService
import musicunlock.library.LibraryIndex
import musicunlock.library.AudioQualityInspector
import musicunlock.online.DownloadTaskManager
import musicunlock.online.DownloadTaskState
import musicunlock.online.toTranscodeFormat
import musicunlock.online.ProviderHealthService
import musicunlock.service.ConversionTaskManager
import musicunlock.service.MusicConverter
import musicunlock.settings.DownloadExistingPolicy
import musicunlock.settings.OutputFormat
import musicunlock.settings.QualityStrategy
import musicunlock.settings.SettingsStore
import musicunlock.sync.WebDavSyncService
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
        var outputFormat = OutputFormat.ORIGINAL
        var dryRun = false
        var listTasks = false
        var resumeTasks = false
        var libraryScan = false
        var libraryExport: String? = null
        var daemon = false
        var inspectPath: String? = null
        var webdavBackup = false
        var webdavRestore = false
        var webdavPassword: String? = null
        var providerHealth = false

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
                "--format" -> {
                    if (i + 1 >= args.size) { println("缺少输出格式"); return 1 }
                    outputFormat = parseOutputFormat(args[++i]) ?: run {
                        println("输出格式必须是 original/mp3/flac/m4a/ogg/opus/wav")
                        return 1
                    }
                }
                "--dry-run" -> dryRun = true
                "--tasks" -> listTasks = true
                "--resume" -> resumeTasks = true
                "--library-scan" -> libraryScan = true
                "--library-export" -> {
                    if (i + 1 >= args.size) { println("缺少导出格式：csv 或 m3u8"); return 1 }
                    libraryExport = args[++i].lowercase()
                    if (libraryExport !in setOf("csv", "m3u8")) {
                        println("曲库导出格式必须是 csv 或 m3u8")
                        return 1
                    }
                }
                "--daemon", "--serve" -> daemon = true
                "--inspect" -> {
                    if (i + 1 >= args.size) { println("缺少体检文件或目录"); return 1 }
                    inspectPath = args[++i]
                }
                "--webdav-backup" -> webdavBackup = true
                "--webdav-restore" -> webdavRestore = true
                "--webdav-password" -> {
                    if (i + 1 >= args.size) { println("缺少 WebDAV 密码"); return 1 }
                    webdavPassword = args[++i]
                }
                "--provider-health" -> providerHealth = true
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

        if (daemon) {
            DesktopAutomationService.runForeground()
            return 0
        }

        if (providerHealth) {
            ProviderHealthService.check(SettingsStore.load()).forEach { health ->
                println("${health.platform.displayName}\t登录=${health.loggedIn}\t状态=${if (health.healthy) "正常" else "异常"}\t延迟=${health.latencyMillis}ms\t能力=${health.capabilities}")
                health.message?.let { println("  $it") }
            }
            return 0
        }

        if (webdavBackup || webdavRestore) {
            val settings = SettingsStore.load()
            val service = WebDavSyncService()
            val password = webdavPassword ?: System.getenv("MUSICUNLOCK_WEBDAV_PASSWORD")
            val result = runCatching {
                if (webdavBackup) service.backup(settings, password) else service.restore(settings, password)
            }
            return result.fold(
                { println("${it.message}：${it.remoteUrl}"); 0 },
                { println("WebDAV 操作失败：${it.message}"); 1 },
            )
        }

        if (listTasks) {
            println("下载任务：${musicunlock.online.defaultTaskFile().absolutePath}")
            println("转换任务：${musicunlock.service.defaultConversionTaskFile().absolutePath}")
            val downloadJson = musicunlock.online.defaultTaskFile().takeIf(File::isFile)?.readText() ?: "[]"
            val conversionJson = musicunlock.service.defaultConversionTaskFile().takeIf(File::isFile)?.readText() ?: "[]"
            println("""{"download":$downloadJson,"conversion":$conversionJson}""")
            return 0
        }

        if (resumeTasks) {
            val conversionManager = ConversionTaskManager()
            val downloadManager = DownloadTaskManager(settingsProvider = { musicunlock.settings.SettingsStore.load() })
            runBlocking {
                conversionManager.tasks.first { tasks -> tasks.all { it.isTerminal || it.state == musicunlock.service.ConversionTaskState.PAUSED } }
                downloadManager.tasks.first { tasks -> tasks.all { it.isTerminal || it.state == DownloadTaskState.PAUSED } }
            }
            val conversionTasks = conversionManager.tasks.value
            val downloadTasks = downloadManager.tasks.value
            val conversionFailed = conversionTasks.count { it.state == musicunlock.service.ConversionTaskState.FAILED }
            val downloadFailed = downloadTasks.count { it.state == DownloadTaskState.FAILED }
            println("恢复完成：转换 ${conversionTasks.size} 个（失败 $conversionFailed），下载 ${downloadTasks.size} 个（失败 $downloadFailed）")
            return if (conversionFailed + downloadFailed == 0) 0 else 1
        }

        if (libraryScan || libraryExport != null) {
            val directory = File(outputDirArg ?: musicunlock.settings.defaultOutputDir())
            val index = LibraryIndex()
            return if (libraryExport != null) {
                val extension = if (libraryExport == "csv") "csv" else "m3u8"
                val target = File(outputDirArg ?: ".", "MusicUnlock-library.$extension")
                val count = if (libraryExport == "csv") {
                    LibraryExportService.exportCsv(index.all(), target)
                } else {
                    LibraryExportService.exportM3u8(index.all(), target)
                }
                println("已导出 $count 条记录到 ${target.absolutePath}")
                0
            } else {
                val report = index.scan(directory, hash = false)
                println("扫描完成：索引 ${report.indexed} 个，清理 ${report.removed} 条，损坏 ${report.invalid.size} 个")
                if (report.invalid.isEmpty()) 0 else 1
            }
        }

        inspectPath?.let { path ->
            val files = mutableListOf<File>()
            MusicConverter.listAllFiles(files, File(path))
            if (files.isEmpty()) {
                val direct = File(path)
                if (direct.isFile) files += direct
            }
            val reports = files.map { AudioQualityInspector.inspect(it) }
            reports.forEach { report ->
                println("${report.score}  ${report.path}")
                println("  格式=${report.format ?: "未知"} 码率=${report.bitRateKbps ?: report.effectiveBitRateKbps ?: 0}k 采样率=${report.sampleRateHz ?: 0}Hz 声道=${report.channels ?: 0}")
                if (report.issues.isNotEmpty()) println("  问题：${report.issues.joinToString("；")}")
            }
            return if (reports.any { it.score < 60 }) 1 else 0
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
                    targetFormat = if (onlineMp3) musicunlock.service.TranscodeFormat.MP3 else outputFormat.toTranscodeFormat(),
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
        if (dryRun) {
            println("待转换文件：${selected.size} 个，输出格式：${outputFormat.name.lowercase()}，输出目录：$outputDir")
            selected.forEach { println("  ${it.absolutePath}") }
            return 0
        }
        val outcomes = runBlocking {
            MusicConverter.convertBatch(
                inputPaths = selected.map { it.absolutePath },
                outputDir = outputDir,
                outputFormat = outputFormat,
                outputTemplate = onlineTemplate ?: "{title}",
                existingFilePolicy = if (forceOverwrite) DownloadExistingPolicy.OVERWRITE else DownloadExistingPolicy.SKIP,
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

    private fun parseOutputFormat(value: String): OutputFormat? = when (value.lowercase().removePrefix(".")) {
        "original", "source", "原始" -> OutputFormat.ORIGINAL
        "mp3" -> OutputFormat.MP3
        "flac" -> OutputFormat.FLAC
        "m4a", "aac", "alac" -> OutputFormat.M4A
        "ogg", "vorbis" -> OutputFormat.OGG
        "opus" -> OutputFormat.OPUS
        "wav" -> OutputFormat.WAV
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
        println("--format [original|mp3|flac|m4a|ogg|opus|wav] : conversion output format")
        println("--dry-run                        : list conversion plan without writing files")
        println("--tasks                          : print persisted conversion and download tasks as JSON")
        println("--resume                         : resume persisted conversion and download tasks")
        println("--library-scan                   : scan output directory into the local library index")
        println("--library-export [csv|m3u8]      : export the current library index")
        println("--daemon,--serve                 : run background watcher, playlist sync and local API")
        println("--inspect [path]                 : inspect audio quality, corruption and clipping")
        println("--webdav-backup|--webdav-restore : upload or restore the full backup over WebDAV")
        println("--provider-health                : check provider sessions, capabilities and latency")
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
        println("  MusicUnlock -c ~/Music -o ~/Music/flac --format flac --template '{artist}/{album}/{title}'")
        println("  MusicUnlock --tasks")
        println("  MusicUnlock --resume")
    }
}
