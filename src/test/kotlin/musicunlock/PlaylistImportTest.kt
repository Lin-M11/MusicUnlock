package musicunlock

import musicunlock.online.MusicSong
import musicunlock.playlist.LocalTrack
import musicunlock.playlist.PlaylistImport
import musicunlock.playlist.PlaylistLink
import musicunlock.playlist.TrackMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 歌单链接解析与本地文件匹配测试。 */
class PlaylistImportTest {

    @Test
    fun `解析网页与移动端歌单链接`() {
        assertEquals(123456L, PlaylistLink.parseNeteaseId("https://music.163.com/playlist?id=123456"))
        assertEquals(123456L, PlaylistLink.parseNeteaseId("https://music.163.com/#/playlist?id=123456&userid=99"))
        assertEquals(123456L, PlaylistLink.parseNeteaseId("https://music.163.com/m/playlist?id=123456"))
        assertEquals(123456L, PlaylistLink.parseNeteaseId("https://music.163.com/playlist/123456"))
        assertEquals(123456L, PlaylistLink.parseNeteaseId("123456"))
    }

    @Test
    fun `解析带分享文案的链接`() {
        val shared = "分享歌单「深夜电台」 https://music.163.com/playlist?id=987654321&userid=1 来自网易云音乐"
        assertEquals(987654321L, PlaylistLink.parseNeteaseId(shared))
    }

    @Test
    fun `非歌单链接返回空`() {
        assertNull(PlaylistLink.parseNeteaseId("https://music.163.com/song?id=123456"))
        assertNull(PlaylistLink.parseNeteaseId("https://example.com/playlist?id=123456"))
        assertNull(PlaylistLink.parseNeteaseId("随便一段文字"))
        assertNull(PlaylistLink.parseNeteaseId(""))
    }

    @Test
    fun `曲目匹配本地文件名`() {
        val songs = listOf(
            song("晴天", "周杰伦"),
            song("夜曲", "周杰伦"),
            song("稻香", "周杰伦"),
        )
        val files = listOf(
            LocalTrack("/m/晴天 - 周杰伦.ncm", "晴天 - 周杰伦.ncm"),
            LocalTrack("/m/夜曲.ncm", "夜曲.ncm"),
            LocalTrack("/m/周杰伦-稻香.flac", "周杰伦-稻香.flac"),
        )
        val outcome = TrackMatcher.match(songs, files)
        assertEquals(3, outcome.matched.size)
        assertTrue(outcome.unmatched.isEmpty())
        assertEquals(
            setOf("/m/晴天 - 周杰伦.ncm", "/m/夜曲.ncm", "/m/周杰伦-稻香.flac"),
            outcome.matchedPaths,
        )
    }

    @Test
    fun `同名不同歌手不算命中`() {
        val songs = listOf(song("晴天", "周杰伦"))
        val files = listOf(LocalTrack("/m/晴天 - 某某翻唱.ncm", "晴天 - 某某翻唱.ncm"))
        val outcome = TrackMatcher.match(songs, files)
        assertEquals(0, outcome.matched.size)
        assertEquals(listOf("晴天"), outcome.unmatched.map { it.name })
    }

    @Test
    fun `标题被包含但文件名写的是别的歌时不算命中`() {
        val songs = listOf(song("晴天", "周杰伦"))
        val files = listOf(LocalTrack("/m/晴天娃娃 - 周杰伦.ncm", "晴天娃娃 - 周杰伦.ncm"))
        val outcome = TrackMatcher.match(songs, files)
        assertEquals(0, outcome.matched.size)
    }

    @Test
    fun `忽略括号后缀与去重编号`() {
        val songs = listOf(song("晴天 (Live)", "周杰伦"), song("夜曲", "周杰伦"))
        val files = listOf(
            LocalTrack("/m/晴天 (1).ncm", "晴天 (1).ncm"),
            LocalTrack("/m/夜曲 (Live版) - 周杰伦.ncm", "夜曲 (Live版) - 周杰伦.ncm"),
        )
        val outcome = TrackMatcher.match(songs, files)
        assertEquals(2, outcome.matched.size)
        assertEquals(setOf("/m/晴天 (1).ncm", "/m/夜曲 (Live版) - 周杰伦.ncm"), outcome.matchedPaths)
    }

    @Test
    fun `多词歌名与带编号的文件名都能命中`() {
        val songs = listOf(song("Always Online", "林俊杰"), song("我怀念的", "孙燕姿"))
        val files = listOf(
            LocalTrack("/m/Always Online - 林俊杰.ncm", "Always Online - 林俊杰.ncm"),
            LocalTrack("/m/03 我怀念的.ncm", "03 我怀念的.ncm"),
        )
        val outcome = TrackMatcher.match(songs, files)
        assertEquals(2, outcome.matched.size)
        assertEquals(setOf("/m/Always Online - 林俊杰.ncm", "/m/03 我怀念的.ncm"), outcome.matchedPaths)
    }

    @Test
    fun `同一首歌的多个本地文件都被勾选`() {
        val songs = listOf(song("晴天", "周杰伦"))
        val files = listOf(
            LocalTrack("/m/晴天 - 周杰伦.ncm", "晴天 - 周杰伦.ncm"),
            LocalTrack("/m/晴天 - 周杰伦.qmcflac", "晴天 - 周杰伦.qmcflac"),
        )
        val outcome = TrackMatcher.match(songs, files)
        assertEquals(2, outcome.matchedPaths.size)
    }

    @Test
    fun `摘要包含命中与未命中数量`() {
        val outcome = TrackMatcher.match(
            listOf(song("晴天", "周杰伦"), song("不存在的歌", "某人")),
            listOf(LocalTrack("/m/晴天 - 周杰伦.ncm", "晴天 - 周杰伦.ncm")),
        )
        assertEquals("命中 1 首 · 未命中 1 首", PlaylistImport.summary(outcome))
    }

    private fun song(name: String, artist: String) = MusicSong(
        id = name,
        name = name,
        artists = listOf(artist),
        albumName = null,
        coverUrl = null,
    )
}
