package musicunlock.playlist

import musicunlock.online.MusicPlatform
import musicunlock.online.ProviderRegistry
import musicunlock.online.MusicSong

/** 粘贴歌单链接 → 拉取歌单曲目 → 与本地队列匹配。 */
object PlaylistImport {

    /** 歌单曲目拉取失败时抛出带可读原因的异常。 */
    fun loadTracks(input: String, platformId: String? = null): List<MusicSong> {
        val link = MusicLinkResolver.parse(input)
            ?: input.trim().takeIf { it.all(Char::isDigit) && it.length in 5..20 }?.let {
                ParsedMusicLink(MusicPlatform.NETEASE, MusicLinkKind.PLAYLIST, it, it)
            }
            ?: throw IllegalArgumentException("无法识别音乐链接，请粘贴网易云、QQ、酷狗或酷我的分享链接")
        if (platformId != null && !link.platform.id.equals(platformId, ignoreCase = true)) {
            throw IllegalArgumentException("链接属于 ${link.platform.displayName}，与当前平台不一致")
        }
        val provider = ProviderRegistry.require(link.platform.id)
        val songs = when (link.kind) {
            MusicLinkKind.PLAYLIST -> {
                val playlist = provider.playlist(link.id)
                    ?: musicunlock.online.MusicPlaylist(link.id, "", null, 0)
                provider.songs(playlist)
            }
            MusicLinkKind.SONG -> listOfNotNull(provider.song(link.id))
            MusicLinkKind.ALBUM -> provider.album(link.id)?.second.orEmpty()
            MusicLinkKind.ARTIST -> provider.artistSongs(link.id, 100)
        }
        if (songs.isEmpty()) throw IllegalStateException("链接没有可读取的曲目，可能是私密歌单、链接已失效或平台接口暂不可用")
        return songs
    }

    /** 拉取歌单并匹配本地文件。 */
    fun importAndMatch(input: String, files: List<LocalTrack>): MatchOutcome =
        TrackMatcher.match(loadTracks(input), files)

    /** 歌单曲目数量与匹配结果的一句话摘要。 */
    fun summary(outcome: MatchOutcome): String =
        "命中 ${outcome.matched.size} 首 · 未命中 ${outcome.unmatched.size} 首"
}
