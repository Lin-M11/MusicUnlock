package musicunlock.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import musicunlock.library.LibraryIndex
import musicunlock.online.DownloadTaskManager
import musicunlock.online.ProviderRegistry
import musicunlock.online.toOnlineDownloadPreferences
import musicunlock.settings.AppSettings
import musicunlock.settings.PlaylistSubscription
import java.io.File

class SubscriptionSyncResult(
    val subscriptionId: String,
    val playlistName: String,
    val total: Int,
    val added: Int,
    val skipped: Int,
    val error: String? = null,
)

/** 歌单追更：每次拉取远端曲目，只把本地曲库缺失的歌曲加入下载队列。 */
class SubscriptionManager(
    private val settingsProvider: () -> AppSettings,
    private val updateSettings: musicunlock.settings.SettingsUpdate,
    private val taskManager: DownloadTaskManager,
    private val library: LibraryIndex = LibraryIndex(),
) {
    fun due(now: Long = System.currentTimeMillis()): List<PlaylistSubscription> {
        val settings = settingsProvider()
        return settings.subscriptions.filter { subscription ->
            subscription.enabled && now - subscription.lastSyncAt >= subscription.syncIntervalMinutes * 60_000L
        }
    }

    fun sync(subscriptionId: String): SubscriptionSyncResult {
        val settings = settingsProvider()
        val subscription = settings.subscriptions.firstOrNull { it.id == subscriptionId }
            ?: return SubscriptionSyncResult(subscriptionId, "", 0, 0, 0, "未找到追更配置")
        val provider = ProviderRegistry.find(subscription.platform)
            ?: return SubscriptionSyncResult(subscriptionId, subscription.playlistName, 0, 0, 0, "未知平台")
        return runCatching {
            val playlist = provider.playlist(subscription.playlistId)
                ?: musicunlock.online.MusicPlaylist(
                    id = subscription.playlistId,
                    name = subscription.playlistName,
                    coverUrl = null,
                    trackCount = subscription.lastTrackCount,
                    metadata = subscription.metadata,
                )
            val songs = provider.songs(playlist)
            val missing = songs.filterNot { library.contains(it, subscription.platform) }
            val preferences = settings.toOnlineDownloadPreferences().copy(
                quality = subscription.quality,
                outputTemplate = subscription.outputTemplate,
                existingFilePolicy = musicunlock.settings.DownloadExistingPolicy.SKIP,
            )
            taskManager.enqueueBatch(
                provider = provider,
                songs = missing,
                outputDir = File(subscription.outputDir),
                preferences = preferences,
                playlistName = playlist.name,
                subscriptionId = subscription.id,
            )
            updateSettings { current ->
                current.copy(
                    subscriptions = current.subscriptions.map {
                        if (it.id == subscription.id) {
                            it.copy(
                                playlistName = playlist.name,
                                lastSyncAt = System.currentTimeMillis(),
                                lastTrackCount = songs.size,
                                metadata = playlist.metadata.ifEmpty { it.metadata },
                            )
                        } else it
                    },
                )
            }
            SubscriptionSyncResult(subscription.id, playlist.name, songs.size, missing.size, songs.size - missing.size)
        }.getOrElse {
            SubscriptionSyncResult(subscriptionId, subscription.playlistName, 0, 0, 0, it.message ?: it.toString())
        }
    }

    fun syncAllDue(): List<SubscriptionSyncResult> = due().map { sync(it.id) }

    /** 应用运行期间启动调度器；每分钟检查一次到期任务。 */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                syncAllDue()
                delay(60_000L)
            }
        }
    }
}
