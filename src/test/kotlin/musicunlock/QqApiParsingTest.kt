package musicunlock

import musicunlock.qq.QqMusicApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QQ 音乐官方接口 JSON 解析测试（使用官方接口真实返回结构的样本）。 */
class QqApiParsingTest {

    @Test
    fun `解析 ptqrlogin 回调`() {
        val waiting = QqMusicApi.parseQrCallback("""ptuiCB('66','0','','0','二维码未失效。', '')""")
        assertEquals(66, waiting.code)

        val expired = QqMusicApi.parseQrCallback("""ptuiCB('65','0','','0','二维码已失效。', '')""")
        assertEquals(65, expired.code)

        val success = QqMusicApi.parseQrCallback(
            """ptuiCB('0','0','https://ssl.ptlogin2.graph.qq.com/check_sig?pttype=1&uin=12345&service=ptqrlogin&nodirect=0&ptsigx=abcDEF&s_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump','0','登录成功！', '')""",
        )
        assertEquals(0, success.code)
        assertNotNull(success.url)
        assertTrue(success.url!!.contains("ptsigx=abcDEF"))
        assertTrue(success.url!!.contains("uin=12345"))
    }

    @Test
    fun `hash33 生成 ptqrtoken`() {
        // 与 QQ 网页端实现一致：逐字符累加并保留低 31 位
        assertEquals(0, QqMusicApi.hash33(""))
        assertTrue(QqMusicApi.hash33("6ff65f719f420c453345c4c1cd9d1af5") > 0)
        // 同一输入结果稳定
        assertEquals(QqMusicApi.hash33("6ff65f719f420c45"), QqMusicApi.hash33("6ff65f719f420c45"))
    }

    @Test
    fun `解析 QQLogin 登录票据`() {
        val raw = """
            {"code":0,"ts":123,"QQConnectLogin.LoginServer":{"code":0,"data":{
              "musicid":"10001","musickey":"K3YMUSICKEY","nickname":"测试用户","logo":"https://q.qlogo.cn/head.png"
            }}}
        """.trimIndent()
        val parsed = QqMusicApi.parseQQLoginResult(raw)
        assertNotNull(parsed)
        assertEquals("10001", parsed?.musicid)
        assertEquals("K3YMUSICKEY", parsed?.musickey)
        assertEquals("测试用户", parsed?.nickname)
        assertEquals("https://q.qlogo.cn/head.png", parsed?.avatarUrl)
    }

    @Test
    fun `解析我的歌单列表`() {
        val raw = """
            {"music.musicasset.PlaylistBaseRead":{"code":0,"data":{"v_playlist":[
              {"tid":201,"dirId":201,"dirName":"我喜欢的音乐","picUrl":"https://y.gtimg.cn/a.jpg","songNum":88},
              {"tid":3001,"dirId":0,"dirName":"华语金曲","picUrl":"https://y.gtimg.cn/b.jpg","songNum":45},
              {"tid":3002,"dirId":0,"dirName":"","picUrl":null,"songNum":0}
            ]}}}
        """.trimIndent()
        val playlists = QqMusicApi.parseMyPlaylists(raw)
        assertEquals(2, playlists.size)
        assertEquals("我喜欢的音乐", playlists[0].name)
        assertTrue(playlists[0].isFavorite)
        assertEquals(88, playlists[0].trackCount)
        assertEquals(3001L, playlists[1].id)
        assertEquals("华语金曲", playlists[1].name)
        assertEquals(45, playlists[1].trackCount)
    }

    @Test
    fun `解析歌单歌曲一页`() {
        val raw = """
            {"music.srfDissInfo.DissInfo":{"code":0,"data":{
              "total_song_num":310,
              "songlist":[
                {"mid":"003UblNw1eaRkY","name":"千千阕歌","singer":[{"mid":"000JvETZ3tOrPR","name":"陈慧娴"}],
                 "album":{"mid":"004ZK8Ea2KV5in","name":"百花齐放"},
                 "file":{"media_mid":"000LKSIZ3RRnLG","size_320mp3":11908277,"size_128mp3":4763678}},
                {"mid":"","name":"空行","singer":[],"album":null,"file":null},
                {"mid":"003X","name":null,"singer":[],"album":null,"file":null}
              ]}}}
        """.trimIndent()
        val (songs, total) = QqMusicApi.parseSonglistPage(raw)
        assertEquals(310, total)
        assertEquals(1, songs.size)
        assertEquals("003UblNw1eaRkY", songs[0].mid)
        assertEquals("000LKSIZ3RRnLG", songs[0].mediaMid)
        assertEquals("千千阕歌", songs[0].name)
        assertEquals(listOf("陈慧娴"), songs[0].artists)
        assertEquals("百花齐放", songs[0].albumName)
        assertEquals("004ZK8Ea2KV5in", songs[0].albumMid)
        assertEquals(11908277L, songs[0].size320)
        assertEquals(4763678L, songs[0].size128)
    }

    @Test
    fun `解析播放地址成功`() {
        val raw = """
            {"code":0,"vkey.GetVkeyServer":{"code":0,"data":{
              "midurlinfo":[{"songmid":"003UblNw1eaRkY","filename":"M800003UblNw1eaRkY003UblNw1eaRkY.mp3",
                "purl":"M800003UblNw1eaRkY003UblNw1eaRkY.mp3?guid=abc&vkey=VVV&uin=10001&fromtag=3","result":0}]}}}
        """.trimIndent()
        val result = QqMusicApi.parseSongUrl(raw)
        assertTrue(result.ok)
        assertEquals("https://isure.stream.qqmusic.qq.com/M800003UblNw1eaRkY003UblNw1eaRkY.mp3?guid=abc&vkey=VVV&uin=10001&fromtag=3", result.url)
        assertNull(result.reason)
    }

    @Test
    fun `VIP 专属歌曲给出明确原因`() {
        val raw = """
            {"code":0,"vkey.GetVkeyServer":{"code":0,"data":{
              "midurlinfo":[{"songmid":"0039MnYb0qxYhV","filename":"M8000039MnYb0qxYhV0039MnYb0qxYhV.mp3",
                "purl":"","uiAlert":41,"pneedbuy":1,"result":22}]}}}
        """.trimIndent()
        val result = QqMusicApi.parseSongUrl(raw)
        assertTrue(!result.ok)
        assertTrue(result.reason!!.contains("VIP"))
    }

    @Test
    fun `数字专辑歌曲给出购买提示`() {
        val raw = """
            {"code":0,"vkey.GetVkeyServer":{"code":0,"data":{
              "midurlinfo":[{"songmid":"0039MnYb0qxYhV","filename":"M800x.mp3","purl":"",
                "uiAlert":0,"pneedbuy":1,"result":22}]}}}
        """.trimIndent()
        val result = QqMusicApi.parseSongUrl(raw)
        assertTrue(!result.ok)
        assertTrue(result.reason!!.contains("购买"))
    }

    @Test
    fun `播放地址接口拒绝时给出返回码`() {
        // 接口对非法/过期请求返回模块错误码且不带 data，需给出可诊断的信息
        val raw = """{"code":0,"vkey.GetVkeyServer":{"code":500003,"subcode":860100005}}"""
        val result = QqMusicApi.parseSongUrl(raw)
        assertTrue(!result.ok)
        assertTrue(result.reason!!.contains("500003"))
    }

    @Test
    fun `解析主页资料`() {
        val raw = """
            {"code":0,"data":{"creator":{"uin":0,"encrypt_uin":"oivFoeo5NKCq7n**","nick":"[ 承蒙时光不弃 ]","headpic":"https://q.qlogo.cn/h.jpg"},
              "mymusic":[{"id":201,"num0":88}]}}
        """.trimIndent()
        val profile = QqMusicApi.parseHomepageProfile(raw)
        assertNotNull(profile)
        assertEquals("[ 承蒙时光不弃 ]", profile?.nickname)
        assertEquals("https://q.qlogo.cn/h.jpg", profile?.avatarUrl)
        assertEquals("oivFoeo5NKCq7n**", profile?.encryptUin)
        assertEquals(88, profile?.favoriteCount)
    }

    @Test
    fun `解析 Cookie 头`() {
        val cookies = QqMusicApi.parseCookieHeader("uin=10001; qm_keyst=K1; qqmusic_key=K2; empty= ;")
        assertEquals("10001", cookies["uin"])
        assertEquals("K1", cookies["qm_keyst"])
        assertEquals("K2", cookies["qqmusic_key"])
        assertNull(cookies["empty"])
    }
}
