package musicunlock.settings

import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions

/** 配置文件读写；文件固定保存在 ~/.musicunlock/config。 */
class SettingsRepository internal constructor(
    private val configFile: File = defaultSettingsFile(),
) {
    private val lock = Any()
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    @Volatile
    private var cached: AppSettings = loadFromDisk()

    fun load(): AppSettings = cached

    fun update(transform: (AppSettings) -> AppSettings): AppSettings = synchronized(lock) {
        val next = normalize(transform(cached))
        runCatching { persist(next) }
            .onFailure { println("设置保存失败: ${it.message ?: it.toString()}") }
        cached = next
        next
    }

    fun reset(): AppSettings = update { AppSettings() }

    private fun loadFromDisk(): AppSettings {
        if (!configFile.isFile) return AppSettings()
        return runCatching {
            normalize(gson.fromJson(configFile.readText(), AppSettings::class.java))
        }.getOrElse { AppSettings() }
    }

    private fun persist(settings: AppSettings) {
        val parent = configFile.parentFile ?: File(".").absoluteFile
        parent.mkdirs()
        setDirectoryPermissions(parent)

        val temp = File.createTempFile(configFile.name, ".tmp", parent)
        try {
            temp.writeText(gson.toJson(settings))
            setFilePermissions(temp)
            val source = temp.toPath()
            val target = configFile.toPath()
            try {
                Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
            setFilePermissions(configFile)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun normalize(settings: AppSettings?): AppSettings {
        val source = settings ?: AppSettings()
        return source.copy(
            outputDir = source.outputDir.takeIf { it.isNotBlank() } ?: defaultOutputDir(),
            bitrateKbps = source.bitrateKbps.takeIf { it in outputBitrates } ?: 320,
            windowWidth = source.windowWidth.coerceAtLeast(980),
            windowHeight = source.windowHeight.coerceAtLeast(680),
            neteaseCookie = source.neteaseCookie?.trim()?.takeIf { it.isNotEmpty() },
            qqCookie = source.qqCookie?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun setDirectoryPermissions(dir: File) {
        runCatching {
            val path = dir.toPath()
            val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            if (view != null) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            }
        }
    }

    private fun setFilePermissions(file: File) {
        runCatching {
            val path = file.toPath()
            val view = Files.getFileAttributeView(path, PosixFileAttributeView::class.java)
            if (view != null) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
        }
    }
}

/** 进程内共享的设置入口。 */
object SettingsStore {
    private val repository = SettingsRepository()

    fun load(): AppSettings = repository.load()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings = repository.update(transform)

    fun reset(): AppSettings = repository.reset()
}

internal fun defaultSettingsFile(): File =
    File(System.getProperty("user.home"), ".musicunlock/config")
