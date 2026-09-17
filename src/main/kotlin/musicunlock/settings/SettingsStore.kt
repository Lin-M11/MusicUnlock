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
    private val credentialStore: CredentialStore = FileCredentialStore(
        File(configFile.parentFile ?: File("."), "credentials"),
    ),
) {
    private val lock = Any()
    private val secretCache = mutableMapOf<String, String?>()
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
            val parsed = normalize(gson.fromJson(configFile.readText(), AppSettings::class.java))
            val stored = mapOf(
                "netease" to credentialStore.get("netease"),
                "qq" to credentialStore.get("qq"),
                "kugou" to credentialStore.get("kugou"),
                "kuwo" to credentialStore.get("kuwo"),
            )
            val migrated = parsed.copy(
                neteaseCookie = stored["netease"] ?: parsed.neteaseCookie,
                qqCookie = stored["qq"] ?: parsed.qqCookie,
                kugouCookie = stored["kugou"] ?: parsed.kugouCookie,
                kuwoCookie = stored["kuwo"] ?: parsed.kuwoCookie,
            )
            secretCache.putAll(
                mapOf(
                    "netease" to migrated.neteaseCookie,
                    "qq" to migrated.qqCookie,
                    "kugou" to migrated.kugouCookie,
                    "kuwo" to migrated.kuwoCookie,
                ),
            )
            if (migrated.neteaseCookie != stored["netease"]) credentialStore.put("netease", migrated.neteaseCookie)
            if (migrated.qqCookie != stored["qq"]) credentialStore.put("qq", migrated.qqCookie)
            if (migrated.kugouCookie != stored["kugou"]) credentialStore.put("kugou", migrated.kugouCookie)
            if (migrated.kuwoCookie != stored["kuwo"]) credentialStore.put("kuwo", migrated.kuwoCookie)
            migrated
        }.getOrElse { AppSettings() }
    }

    private fun persist(settings: AppSettings) {
        persistSecret("netease", settings.neteaseCookie)
        persistSecret("qq", settings.qqCookie)
        persistSecret("kugou", settings.kugouCookie)
        persistSecret("kuwo", settings.kuwoCookie)
        val persistedSettings = settings.copy(
            neteaseCookie = null,
            qqCookie = null,
            kugouCookie = null,
            kuwoCookie = null,
        )
        val parent = configFile.parentFile ?: File(".").absoluteFile
        parent.mkdirs()
        setDirectoryPermissions(parent)

        val temp = File.createTempFile(configFile.name, ".tmp", parent)
        try {
            temp.writeText(gson.toJson(persistedSettings))
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

    private fun persistSecret(key: String, value: String?) {
        if (secretCache.containsKey(key) && secretCache[key] == value) return
        credentialStore.put(key, value)
        secretCache[key] = value
    }

    private fun normalize(settings: AppSettings?): AppSettings {
        val source = settings ?: AppSettings()
        return source.copy(
            outputDir = source.outputDir.takeIf { it.isNotBlank() } ?: defaultOutputDir(),
            bitrateKbps = source.bitrateKbps.takeIf { it in outputBitrates } ?: 320,
            localOutputTemplate = source.localOutputTemplate.trim().takeIf { it.isNotEmpty() } ?: "{title}",
            localExistingFilePolicy = if (!source.skipExisting && source.localExistingFilePolicy == DownloadExistingPolicy.SKIP) {
                DownloadExistingPolicy.OVERWRITE
            } else {
                source.localExistingFilePolicy
            },
            windowWidth = source.windowWidth.coerceAtLeast(980),
            windowHeight = source.windowHeight.coerceAtLeast(680),
            neteaseCookie = source.neteaseCookie?.trim()?.takeIf { it.isNotEmpty() },
            qqCookie = source.qqCookie?.trim()?.takeIf { it.isNotEmpty() },
            kugouCookie = source.kugouCookie?.trim()?.takeIf { it.isNotEmpty() },
            kuwoCookie = source.kuwoCookie?.trim()?.takeIf { it.isNotEmpty() },
            neteaseAccount = normalizeAccount(source.neteaseAccount),
            qqAccount = normalizeAccount(source.qqAccount),
            kugouAccount = normalizeAccount(source.kugouAccount),
            kuwoAccount = normalizeAccount(source.kuwoAccount),
            downloadConcurrency = source.downloadConcurrency.coerceIn(1, 16),
            downloadRetryCount = source.downloadRetryCount.coerceIn(0, 20),
            downloadRetryDelayMillis = source.downloadRetryDelayMillis.coerceIn(100L, 60_000L),
            downloadSpeedLimitKbps = source.downloadSpeedLimitKbps.coerceIn(0, 1_000_000),
            downloadTimeoutSeconds = source.downloadTimeoutSeconds.coerceIn(5L, 3_600L),
            connectTimeoutSeconds = source.connectTimeoutSeconds.coerceIn(3L, 300L),
            proxyUrl = source.proxyUrl?.trim()?.takeIf { it.isNotEmpty() },
            outputTemplate = source.outputTemplate.trim().takeIf { it.isNotEmpty() } ?: "{artist}/{album}/{title}",
            subscriptions = source.subscriptions
                .filter { it.id.isNotBlank() && it.platform.isNotBlank() && it.playlistId.isNotBlank() }
                .map {
                    it.copy(
                        outputDir = it.outputDir.takeIf(String::isNotBlank) ?: source.outputDir,
                        syncIntervalMinutes = it.syncIntervalMinutes.coerceIn(5, 10_080),
                        lastTrackCount = it.lastTrackCount.coerceAtLeast(0),
                    )
                },
            watchFolders = source.watchFolders.map(String::trim).filter(String::isNotEmpty).distinct(),
        )
    }

    private fun normalizeAccount(account: AccountSnapshot?): AccountSnapshot? {
        val source = account ?: return null
        val nickname = source.nickname.trim()
        val userId = source.userId.trim()
        if (nickname.isEmpty() || userId.isEmpty()) return null
        return source.copy(
            nickname = nickname,
            avatarUrl = source.avatarUrl?.trim()?.takeIf { it.isNotEmpty() },
            userId = userId,
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
    private val repository = SettingsRepository(credentialStore = PlatformCredentialStore())

    fun load(): AppSettings = repository.load()

    fun update(transform: (AppSettings) -> AppSettings): AppSettings = repository.update(transform)

    fun reset(): AppSettings = repository.reset()
}

internal fun defaultSettingsFile(): File =
    File(System.getProperty("user.home"), ".musicunlock/config")
