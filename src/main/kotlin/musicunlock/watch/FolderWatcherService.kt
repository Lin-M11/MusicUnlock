package musicunlock.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import musicunlock.core.Formats
import musicunlock.service.MusicConverter
import musicunlock.settings.AppSettings
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.util.concurrent.ConcurrentHashMap

class WatchEventResult(val path: String, val success: Boolean, val message: String?)

/** 监听收件箱目录，新出现的受支持加密文件自动解密转换。 */
class FolderWatcherService(
    private val settingsProvider: () -> AppSettings,
    private val onResult: (WatchEventResult) -> Unit = {},
) {
    private val pending = ConcurrentHashMap<String, Long>()
    private val roots = mutableMapOf<WatchKey, File>()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val settings = settingsProvider()
                if (!settings.watchEnabled || settings.watchFolders.isEmpty()) {
                    delay(2_000L)
                    continue
                }
                runCatching { watchOnce(settings) }
                processPending(settings)
            }
        }
    }

    private fun watchOnce(settings: AppSettings) {
        val watchService = FileSystems.getDefault().newWatchService()
        settings.watchFolders.map(::File).filter(File::isDirectory).forEach { root ->
            registerRecursively(root, watchService)
        }
        try {
            while (true) {
                val latest = settingsProvider()
                if (!latest.watchEnabled || latest.watchFolders != settings.watchFolders) break
                val key = watchService.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (key != null) {
                    val root = roots[key] ?: key.watchable() as? File
                    if (root != null) {
                        key.pollEvents().forEach { event ->
                            val relative = event.context() as? java.nio.file.Path ?: return@forEach
                            val child = root.toPath().resolve(relative).toFile()
                            if (child.isDirectory) registerRecursively(child, watchService)
                            if (child.isFile && Formats.isSupported(child.name)) pending[child.absolutePath] = System.currentTimeMillis()
                        }
                    }
                    if (!key.reset()) roots.remove(key)
                }
                processPending(latest)
            }
        } finally {
            watchService.close()
        }
    }

    private fun processPending(settings: AppSettings) {
        val now = System.currentTimeMillis()
        val ready = pending.filterValues { now - it >= 1_200L }.keys.toList()
        ready.forEach { path ->
            val file = File(path)
            pending.remove(path)
            if (!file.isFile) return@forEach
            val error = MusicConverter.convertWithError(
                inputPath = file.absolutePath,
                outputDir = settings.outputDir,
                outputFormat = settings.outputFormat,
                bitrateKbps = settings.bitrateKbps,
                forceOverwrite = !settings.skipExisting,
            )
            onResult(WatchEventResult(path, error == null, error))
        }
    }

    private fun registerRecursively(root: File, watchService: java.nio.file.WatchService) {
        if (!root.isDirectory) return
        Files.walk(root.toPath()).use { paths ->
            paths.filter(Files::isDirectory).forEach { path ->
                val key = path.register(
                    watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                )
                roots[key] = path.toFile()
            }
        }
    }
}
