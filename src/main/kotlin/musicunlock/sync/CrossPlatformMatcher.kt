package musicunlock.sync

import musicunlock.library.LibraryMaintenanceService
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicSong
import musicunlock.online.ProviderRegistry
import musicunlock.online.SearchResultKind

data class CrossPlatformMatch(
    val platform: MusicPlatform,
    val song: MusicSong,
    val confidence: String,
)

/** 用 ISRC、歌名、歌手和时长在另一平台寻找等价歌曲。 */
class CrossPlatformMatcher(
    private val maintenance: LibraryMaintenanceService = LibraryMaintenanceService(),
) {
    fun find(source: MusicSong, targets: List<MusicPlatform> = MusicPlatform.entries): List<CrossPlatformMatch> {
        if (targets.isEmpty()) return emptyList()
        val query = listOf(source.name, source.artistText).filter(String::isNotBlank).joinToString(" ")
        return targets.mapNotNull { platform ->
            val provider = ProviderRegistry.find(platform.id) ?: return@mapNotNull null
            val candidates = runCatching { provider.search(query, 30) }.getOrDefault(emptyList())
                .filter { it.kind == SearchResultKind.SONG }
                .mapNotNull { it.song }
            val matched = maintenance.matchAcrossPlatform(source, candidates) ?: return@mapNotNull null
            val confidence = when {
                !source.isrc.isNullOrBlank() && matched.isrc.equals(source.isrc, ignoreCase = true) -> "ISRC"
                source.durationSeconds != null && matched.durationSeconds != null -> "歌名/歌手/时长"
                else -> "歌名/歌手"
            }
            CrossPlatformMatch(platform, matched, confidence)
        }
    }
}
