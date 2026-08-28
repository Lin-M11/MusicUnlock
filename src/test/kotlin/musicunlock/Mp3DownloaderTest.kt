package musicunlock

import musicunlock.ncm.Mp3Downloader
import musicunlock.ncm.NeteaseSong
import musicunlock.ncm.NeteaseSongUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/** 下载转码模块的纯逻辑测试（文件名清洗、歌名生成）。 */
class Mp3DownloaderTest {

    @Test
    fun `清洗文件名中的非法字符`() {
        assertEquals("晴天 _ 周杰伦", Mp3Downloader.sanitizeFileName("晴天 / 周杰伦"))
        assertEquals("a_b_c", Mp3Downloader.sanitizeFileName("a:b*c"))
        assertEquals("a_b_c_d_e", Mp3Downloader.sanitizeFileName("a\\b|c<d>e"))
        assertEquals("未命名", Mp3Downloader.sanitizeFileName("   "))
    }

    @Test
    fun `超长文件名被截断`() {
        val long = "歌".repeat(500)
        val result = Mp3Downloader.sanitizeFileName(long)
        assert(result.length <= 120)
    }

    @Test
    fun `歌名与歌手拼接为文件名`() {
        val song = NeteaseSong(id = 1L, name = "晴天", artists = listOf("周杰伦"), albumName = "叶惠美", albumPicUrl = null)
        assertEquals("晴天 - 周杰伦", Mp3Downloader.songBaseName(song))
    }

    @Test
    fun `多歌手以斜杠分隔`() {
        val song = NeteaseSong(id = 2L, name = "珊瑚海", artists = listOf("周杰伦", "Lara梁心颐"), albumName = "十一月的萧邦", albumPicUrl = null)
        assertEquals("珊瑚海 - 周杰伦 _ Lara梁心颐", Mp3Downloader.songBaseName(song))
    }

    @Test
    fun `缺失歌手时使用占位`() {
        val song = NeteaseSong(id = 3L, name = "纯音乐", artists = emptyList(), albumName = null, albumPicUrl = null)
        assertEquals("纯音乐 - 未知歌手", Mp3Downloader.songBaseName(song))
    }

    @Test
    fun `灰色歌曲无播放地址时给出明确原因`() {
        val reason = Mp3Downloader.unavailableReason(null)
        assert(reason != null && reason.contains("灰色歌曲"))
    }

    @Test
    fun `试听片段给出会员权限提示`() {
        val trial = NeteaseSongUrl(id = 1L, url = "https://m701.music.126.net/trial.mp3", br = 128000, type = "mp3", size = 602112, isTrial = true)
        val reason = Mp3Downloader.unavailableReason(trial)
        assert(reason != null && reason.contains("试听片段"))
    }

    @Test
    fun `完整地址可正常下载`() {
        val full = NeteaseSongUrl(id = 1L, url = "https://m701.music.126.net/full.mp3", br = 320000, type = "mp3", size = 8421165, isTrial = false)
        assert(Mp3Downloader.unavailableReason(full) == null)
    }
}
