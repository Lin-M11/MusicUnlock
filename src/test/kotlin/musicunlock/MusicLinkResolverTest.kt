package musicunlock

import musicunlock.online.MusicPlatform
import musicunlock.playlist.MusicLinkKind
import musicunlock.playlist.MusicLinkResolver
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicLinkResolverTest {
    @Test
    fun `parses netease playlist share link`() {
        val link = MusicLinkResolver.parse("https://music.163.com/#/playlist?id=123456")
        assertEquals(MusicPlatform.NETEASE, link?.platform)
        assertEquals(MusicLinkKind.PLAYLIST, link?.kind)
        assertEquals("123456", link?.id)
    }

    @Test
    fun `parses netease artist link`() {
        val link = MusicLinkResolver.parse("https://music.163.com/#/artist?id=6452")
        assertEquals(MusicPlatform.NETEASE, link?.platform)
        assertEquals(MusicLinkKind.ARTIST, link?.kind)
        assertEquals("6452", link?.id)
    }

    @Test
    fun `parses qq song and album links`() {
        val song = MusicLinkResolver.parse("https://y.qq.com/n/ryqq/songDetail/abcMID")
        val album = MusicLinkResolver.parse("https://y.qq.com/n/ryqq/albumDetail/albumMID")
        assertEquals(MusicLinkKind.SONG, song?.kind)
        assertEquals("abcMID", song?.id)
        assertEquals(MusicLinkKind.ALBUM, album?.kind)
        assertEquals("albumMID", album?.id)
    }

    @Test
    fun `parses kugou hash and kuwo playlist`() {
        val kugou = MusicLinkResolver.parse("https://www.kugou.com/song/#hash=ABCDEF")
        val kuwo = MusicLinkResolver.parse("https://www.kuwo.cn/playlist_detail/9876")
        assertEquals(MusicPlatform.KUGOU, kugou?.platform)
        assertEquals("ABCDEF", kugou?.id)
        assertEquals(MusicPlatform.KUWO, kuwo?.platform)
        assertEquals("9876", kuwo?.id)
    }
}
