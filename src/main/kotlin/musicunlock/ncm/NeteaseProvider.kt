package musicunlock.ncm

import musicunlock.online.MusicAccount
import musicunlock.online.MusicPlatform
import musicunlock.online.MusicPlaylist
import musicunlock.online.MusicSong
import musicunlock.online.MusicLyrics
import musicunlock.online.MusicSearchResult
import musicunlock.online.OnlineMusicProvider
import musicunlock.online.PlaybackSource
import musicunlock.online.SearchResultKind
import musicunlock.settings.QualityStrategy
import java.nio.file.Path

/** 把网易云官方客户端适配为统一在线音乐接口。 */
object NeteaseProvider : OnlineMusicProvider {
    override val platform = MusicPlatform.NETEASE

    override fun restoreSession(cookieHeader: String): MusicAccount = NeteaseApi.restoreSession(cookieHeader).toMusicAccount()
    override fun exportSessionCookie(): String? = NeteaseApi.exportSessionCookie()
    override fun logout() = NeteaseApi.logout()
    override fun account(): MusicAccount? = NeteaseApi.account()?.toMusicAccount()

    override fun playlists(): List<MusicPlaylist> = NeteaseApi.playlists().map {
        MusicPlaylist(
            id = it.id.toString(),
            name = it.name,
            coverUrl = it.coverImgUrl,
            trackCount = it.trackCount,
        )
    }

    override fun songs(playlist: MusicPlaylist): List<MusicSong> {
        val id = playlist.id.toLongOrNull() ?: return emptyList()
        val ids = NeteaseApi.playlistTrackIds(id)
        return ids.chunked(100).flatMap { batch ->
            NeteaseApi.songDetails(batch).map { it.toMusicSong() }
        }
    }

    override fun playback(song: MusicSong): PlaybackSource = playback(song, QualityStrategy.HIGHEST)

    override fun playback(song: MusicSong, quality: QualityStrategy): PlaybackSource {
        val id = song.id.toLongOrNull() ?: throw IllegalStateException("歌曲 ID 无效")
        val rates = when (quality) {
            QualityStrategy.LOSSLESS_FIRST -> listOf(999_000, 320_000, 192_000, 128_000)
            QualityStrategy.HIGHEST -> listOf(999_000, 320_000, 192_000, 128_000)
            QualityStrategy.MP3_320 -> listOf(320_000, 192_000, 128_000)
            QualityStrategy.BALANCED -> listOf(192_000, 128_000)
            QualityStrategy.SMALLEST -> listOf(128_000, 192_000, 320_000)
        }
        val url = rates.asSequence()
            .mapNotNull { bitrate -> runCatching { NeteaseApi.songUrl(id, bitrate) }.getOrNull() }
            .firstOrNull()
            ?: throw IllegalStateException(Mp3Downloader.unavailableReason(null))
        Mp3Downloader.unavailableReason(url)?.let { throw IllegalStateException(it) }
        val lossless = (url.type ?: "").lowercase() in setOf("flac", "ape", "wav") || url.br >= 900_000
        return PlaybackSource(
            url = url.url,
            formatHint = url.type,
            qualityLabel = if (lossless) "无损" else "${url.br / 1000}k",
            bitrateKbps = url.br / 1000,
            lossless = lossless,
        )
    }

    override fun lyrics(song: MusicSong): MusicLyrics? =
        song.id.toLongOrNull()?.let(NeteaseApi::lyrics)

    override fun song(songId: String): MusicSong? =
        songId.toLongOrNull()?.let(NeteaseApi::songDetail)?.toMusicSong()

    override fun playlist(playlistId: String): MusicPlaylist? {
        val id = playlistId.toLongOrNull() ?: return null
        val playlist = NeteaseApi.playlistDetail(id) ?: return null
        return MusicPlaylist(playlist.id.toString(), playlist.name, playlist.coverImgUrl, playlist.trackCount)
    }

    override fun album(albumId: String): Pair<MusicPlaylist, List<MusicSong>>? =
        albumId.toLongOrNull()?.let(NeteaseApi::album)?.let { it.first.toMusicPlaylist() to it.second.map(NeteaseSong::toMusicSong) }

    override fun artistSongs(artistId: String, limit: Int): List<MusicSong> =
        artistId.toLongOrNull()?.let { NeteaseApi.artistSongs(it, limit) }.orEmpty().map(NeteaseSong::toMusicSong)

    override fun search(query: String, limit: Int): List<MusicSearchResult> {
        val out = mutableListOf<MusicSearchResult>()
        val songRoot = runCatching { NeteaseApi.search(query, 1, limit) }.getOrNull()
        songRoot?.getAsJsonObject("result")?.getAsJsonArray("songs")?.forEach { element ->
            NeteaseApi.parseSearchSong(element)?.let {
                out += MusicSearchResult(platform, SearchResultKind.SONG, it.id.toString(), it.name, it.artistText, it.albumPicUrl, song = it.toMusicSong())
            }
        }
        val playlistRoot = runCatching { NeteaseApi.search(query, 1000, limit) }.getOrNull()
        playlistRoot?.getAsJsonObject("result")?.getAsJsonArray("playlists")?.forEach { element ->
            runCatching {
                val item = element.asJsonObject
                val playlist = MusicPlaylist(
                    id = item.get("id").asString,
                    name = item.get("name").asString,
                    coverUrl = item.get("coverImgUrl")?.asString,
                    trackCount = item.get("trackCount")?.asInt ?: 0,
                )
                out += MusicSearchResult(platform, SearchResultKind.PLAYLIST, playlist.id, playlist.name, "${playlist.trackCount} 首", playlist.coverUrl, playlist = playlist)
            }
        }
        return out.distinctBy { "${it.kind}:${it.id}" }.take(limit)
    }

    override fun download(url: String, target: Path) = NeteaseApi.download(url, target)

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
            "Referer" to "https://music.163.com/",
        ),
        offset = offset,
        onProgress = onProgress,
        shouldContinue = shouldContinue,
    )

    override fun bytes(url: String): ByteArray? = NeteaseApi.downloadBytes(url)
}

internal fun NeteaseAccount.toMusicAccount() = MusicAccount(
    nickname = nickname,
    avatarUrl = avatarUrl,
    userId = userId.toString(),
)

internal fun NeteaseSong.toMusicSong() = MusicSong(
    id = id.toString(),
    name = name,
    artists = artists,
    albumName = albumName,
    coverUrl = albumPicUrl,
    durationSeconds = durationMillis?.let { (it / 1000.0).toInt() },
    trackNumber = trackNumber,
    discNumber = discNumber,
    year = year,
)

private fun NeteasePlaylist.toMusicPlaylist() = MusicPlaylist(
    id = id.toString(),
    name = name,
    coverUrl = coverImgUrl,
    trackCount = trackCount,
)
