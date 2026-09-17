package musicunlock.qq

import musicunlock.online.MusicAccount
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicPlaylist
import musicunlock.online.MusicLyrics
import musicunlock.online.MusicSearchResult
import musicunlock.online.MusicSong
import musicunlock.online.OnlineMusicProvider
import musicunlock.online.PlaybackSource
import musicunlock.online.SearchResultKind
import musicunlock.settings.QualityStrategy
import java.nio.file.Path

/** 把 QQ 音乐客户端适配为统一在线音乐接口。 */
object QqMusicProvider : OnlineMusicProvider {
    override val platform = MusicPlatform.QQ

    override fun restoreSession(cookieHeader: String): MusicAccount = QqMusicApi.restoreSession(cookieHeader).toMusicAccount()
    override fun exportSessionCookie(): String? = QqMusicApi.exportSessionCookie()
    override fun logout() = QqMusicApi.logout()
    override fun account(): MusicAccount? = QqMusicApi.account()?.toMusicAccount()

    override fun playlists(): List<MusicPlaylist> = QqMusicApi.myPlaylists().map {
        MusicPlaylist(
            id = it.id.toString(),
            name = it.name,
            coverUrl = it.coverUrl,
            trackCount = it.trackCount,
            metadata = mapOf(
                "dirId" to it.dirId.toString(),
                "isFavorite" to it.isFavorite.toString(),
            ),
        )
    }

    override fun songs(playlist: MusicPlaylist): List<MusicSong> {
        val id = playlist.id.toLongOrNull() ?: return emptyList()
        val dirId = playlist.metadata["dirId"]?.toLongOrNull() ?: 0L
        val source = QqPlaylist(id = id, dirId = dirId, name = playlist.name, coverUrl = playlist.coverUrl, trackCount = playlist.trackCount)
        return runCatching { QqMusicApi.playlistSongs(source) }
            .getOrElse { QqMusicApi.publicPlaylistSongs(playlist.id) }
            .map { it.toMusicSong() }
    }

    override fun playback(song: MusicSong): PlaybackSource = playback(song, QualityStrategy.HIGHEST)

    override fun playback(song: MusicSong, quality: QualityStrategy): PlaybackSource {
        val source = song.toQqSong()
        val requested = when (quality) {
            QualityStrategy.LOSSLESS_FIRST -> listOf(999, 320, 128)
            QualityStrategy.HIGHEST -> listOf(999, 320, 128)
            QualityStrategy.MP3_320 -> listOf(320, 128)
            QualityStrategy.BALANCED -> listOf(128, 320)
            QualityStrategy.SMALLEST -> listOf(128, 320, 999)
        }
        val results = requested.mapNotNull { value -> QqMusicApi.songUrl(source, value) }
        val result = results.firstOrNull { it.ok }
            ?: throw IllegalStateException(
                results.firstOrNull { !it.reason.isNullOrBlank() }?.reason
                    ?: "获取播放地址失败（当前账号无版权或购买权限）",
            )
        val lossless = result.quality == 999
        return PlaybackSource(
            url = result.url!!,
            formatHint = if (lossless) "flac" else "mp3",
            qualityLabel = if (lossless) "无损" else "${result.quality}k",
            bitrateKbps = if (lossless) null else result.quality,
            lossless = lossless,
        )
    }

    override fun lyrics(song: MusicSong): MusicLyrics? = QqMusicApi.lyrics(song.toQqSong())

    override fun song(songId: String): MusicSong? = QqMusicApi.songDetail(songId)?.toMusicSong()

    override fun playlist(playlistId: String): MusicPlaylist? =
        QqMusicApi.publicPlaylist(playlistId)?.toMusicPlaylist()

    override fun album(albumId: String): Pair<MusicPlaylist, List<MusicSong>>? =
        QqMusicApi.album(albumId)?.let { it.first.toMusicPlaylist() to it.second.map(QqSong::toMusicSong) }

    override fun artistSongs(artistId: String, limit: Int): List<MusicSong> =
        QqMusicApi.artistSongs(artistId, limit).map(QqSong::toMusicSong)

    override fun search(query: String, limit: Int): List<MusicSearchResult> =
        QqMusicApi.search(query, limit).map { result ->
            when (result.kind) {
                SearchResultKind.SONG -> MusicSearchResult(
                    platform,
                    SearchResultKind.SONG,
                    result.song!!.mid,
                    result.song.name,
                    result.song.artistText,
                    result.song.albumMid?.let { "https://y.gtimg.cn/music/photo_new/T002R300x300M000${it}.jpg" },
                    song = result.song.toMusicSong(),
                )
                SearchResultKind.PLAYLIST -> MusicSearchResult(
                    platform,
                    SearchResultKind.PLAYLIST,
                    result.playlist!!.id.toString(),
                    result.playlist.name,
                    "${result.playlist.trackCount} 首",
                    result.playlist.coverUrl,
                    playlist = result.playlist.toMusicPlaylist(),
                )
                else -> MusicSearchResult(platform, result.kind, result.id, result.title, result.subtitle, result.coverUrl)
            }
        }

    override fun download(url: String, target: Path) = QqMusicApi.download(url, target)

    override fun download(
        url: String,
        target: Path,
        offset: Long,
        onProgress: (Long, Long?, Long) -> Unit,
        shouldContinue: () -> Boolean,
    ) = musicunlock.online.HttpDownloader.download(
        settings = musicunlock.online.OnlineNetwork.settings(),
        url = url,
        target = target,
        headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://y.qq.com/",
        ),
        offset = offset,
        onProgress = onProgress,
        shouldContinue = shouldContinue,
    )

    override fun bytes(url: String): ByteArray? = QqMusicApi.downloadBytes(url)
}

private fun QqAccount.toMusicAccount() = MusicAccount(
    nickname = nickname,
    avatarUrl = avatarUrl,
    userId = musicid,
)

private fun QqSong.toMusicSong() = MusicSong(
    id = mid,
    name = name,
    artists = artists,
    albumName = albumName,
    coverUrl = albumMid?.let { "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg" },
    metadata = buildMap {
        put("mid", mid)
        mediaMid?.let { put("mediaMid", it) }
        albumMid?.let { put("albumMid", it) }
        put("size320", size320.toString())
        put("size128", size128.toString())
    },
)

private fun MusicSong.toQqSong() = QqSong(
    mid = metadata["mid"] ?: id,
    mediaMid = metadata["mediaMid"],
    name = name,
    artists = artists,
    albumName = albumName,
    albumMid = metadata["albumMid"],
    size320 = metadata["size320"]?.toLongOrNull() ?: 0L,
    size128 = metadata["size128"]?.toLongOrNull() ?: 0L,
)

private fun QqPlaylist.toMusicPlaylist() = MusicPlaylist(
    id = id.toString(),
    name = name,
    coverUrl = coverUrl,
    trackCount = trackCount,
    metadata = mapOf("dirId" to dirId.toString(), "isFavorite" to isFavorite.toString()),
)
