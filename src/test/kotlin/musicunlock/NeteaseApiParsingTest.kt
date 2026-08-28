package musicunlock

import musicunlock.ncm.NeteaseApi
import musicunlock.ncm.QrLoginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 网易云官方接口 JSON 解析测试（使用官方接口真实返回结构的样本）。 */
class NeteaseApiParsingTest {

    @Test
    fun `解析二维码轮询状态`() {
        assertEquals(QrLoginState.WAIT, NeteaseApi.parseQrCheck("""{"code":801,"message":"等待扫码"}""").state)
        assertEquals(QrLoginState.SCANNED, NeteaseApi.parseQrCheck("""{"code":802,"message":"已扫码未确认"}""").state)
        assertEquals(QrLoginState.EXPIRED, NeteaseApi.parseQrCheck("""{"code":800,"message":"二维码已失效"}""").state)
        assertEquals(QrLoginState.SUCCESS, NeteaseApi.parseQrCheck("""{"code":803,"message":"授权登录成功"}""").state)
        assertEquals(QrLoginState.UNKNOWN, NeteaseApi.parseQrCheck("""{"code":-460,"message":"oops"}""").state)
    }

    @Test
    fun `未登录时账号接口返回 null`() {
        assertNull(NeteaseApi.parseAccount("""{"code":200,"account":null,"profile":null}"""))
    }

    @Test
    fun `解析登录账号信息`() {
        val account = NeteaseApi.parseAccount(
            """{"code":200,"account":{"id":1},"profile":{"nickname":"测试用户","avatarUrl":"https://p1.music.126.net/avatar.jpg","userId":123456}}""",
        )
        assertEquals("测试用户", account?.nickname)
        assertEquals("https://p1.music.126.net/avatar.jpg", account?.avatarUrl)
        assertEquals(123456L, account?.userId)
    }

    @Test
    fun `解析用户歌单列表与翻页标记`() {
        val json = """
            {"code":200,"more":true,"playlist":[
              {"id":1,"name":"我喜欢的音乐","coverImgUrl":"http://p1.music.126.net/a.jpg","trackCount":127},
              {"id":2,"name":"","coverImgUrl":null,"trackCount":0},
              {"id":3,"name":"旅行歌单","coverImgUrl":"https://p1.music.126.net/b.jpg","trackCount":20}
            ]}
        """.trimIndent()
        val (playlists, more) = NeteaseApi.parseUserPlaylists(json)
        assertTrue(more)
        assertEquals(2, playlists.size)
        assertEquals(1L, playlists[0].id)
        assertEquals("我喜欢的音乐", playlists[0].name)
        assertEquals("http://p1.music.126.net/a.jpg", playlists[0].coverImgUrl)
        assertEquals(127, playlists[0].trackCount)
        assertEquals("旅行歌单", playlists[1].name)
    }

    @Test
    fun `解析歌单详情中的歌曲 id 列表`() {
        val json = """{"code":200,"playlist":{"id":3778678,"trackIds":[{"id":3399839173},{"id":3399839174}]}}"""
        val ids = NeteaseApi.parsePlaylistTrackIds(json)
        assertEquals(listOf(3399839173L, 3399839174L), ids)
    }

    @Test
    fun `解析歌曲详情`() {
        val json = """
            {"code":200,"songs":[
              {"id":3399839173,"name":"甲乙丙丁 (你我怎么两清)","ar":[{"id":8753,"name":"李佳薇"}],"al":{"id":384452271,"name":"甲乙丙丁","picUrl":"https://p1.music.126.net/c.jpg"}},
              {"id":2,"name":"","ar":[],"al":null}
            ]}
        """.trimIndent()
        val songs = NeteaseApi.parseSongDetails(json)
        assertEquals(1, songs.size)
        assertEquals(3399839173L, songs[0].id)
        assertEquals("甲乙丙丁 (你我怎么两清)", songs[0].name)
        assertEquals(listOf("李佳薇"), songs[0].artists)
        assertEquals("甲乙丙丁", songs[0].albumName)
        assertEquals("https://p1.music.126.net/c.jpg", songs[0].albumPicUrl)
    }

    @Test
    fun `解析播放地址并转换为 https`() {
        val json = """{"code":200,"data":[{"id":3399839173,"url":"http://m701.music.126.net/xxx.mp3","br":320000,"size":8421165,"type":"mp3"}]}"""
        val url = NeteaseApi.parseSongUrl(json, 3399839173L, 320000)
        assertEquals("https://m701.music.126.net/xxx.mp3", url?.url)
        assertEquals(320000, url?.br)
        assertEquals("mp3", url?.type)
    }

    @Test
    fun `播放地址不可用时返回 null`() {
        val json = """{"code":200,"data":[{"id":1,"url":null,"br":320000,"size":0,"type":"mp3"}]}"""
        assertNull(NeteaseApi.parseSongUrl(json, 1L, 320000))
    }

    @Test
    fun `带 freeTrialInfo 的地址标记为试听片段`() {
        val json = """{"code":200,"data":[{"id":1,"url":"http://m701.music.126.net/trial.mp3","br":128000,"size":602112,"type":"mp3","freeTrialInfo":{"start":0,"end":60000}}]}"""
        val url = NeteaseApi.parseSongUrl(json, 1L, 128000)
        assertTrue(url?.isTrial == true)
    }

    @Test
    fun `无 freeTrialInfo 的地址不是试听片段`() {
        val json = """{"code":200,"data":[{"id":1,"url":"http://m701.music.126.net/full.mp3","br":320000,"size":8421165,"type":"mp3"}]}"""
        val url = NeteaseApi.parseSongUrl(json, 1L, 320000)
        assertTrue(url?.isTrial == false)
    }
}
