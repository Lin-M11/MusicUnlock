package musicunlock

import musicunlock.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 启动更新检查的解析与版本比较测试（返回体结构取自 GitHub releases/latest）。 */
class UpdateCheckerTest {

    @Test
    fun `构建版本号来自 Gradle`() {
        assertTrue(BuildInfo.VERSION.matches(Regex("""\d+\.\d+(\.\d+)?""")))
    }

    @Test
    fun `解析最新发行版本与页面地址`() {
        val json = """{"tag_name":"v1.2.0","html_url":"https://github.com/Lin-M11/MusicUnlock/releases/tag/v1.2.0","draft":false,"prerelease":false}"""
        val release = UpdateChecker.parseLatest(json)
        assertEquals("1.2.0", release?.version)
        assertEquals("https://github.com/Lin-M11/MusicUnlock/releases/tag/v1.2.0", release?.pageUrl)
    }

    @Test
    fun `缺少页面地址时回落到发行页`() {
        val release = UpdateChecker.parseLatest("""{"tag_name":"1.2.0"}""")
        assertEquals("1.2.0", release?.version)
        assertEquals(AppLinks.RELEASES_PAGE, release?.pageUrl)
    }

    @Test
    fun `返回体异常时视为无更新`() {
        assertNull(UpdateChecker.parseLatest("not json"))
        assertNull(UpdateChecker.parseLatest("""{"message":"Not Found"}"""))
        assertNull(UpdateChecker.parseLatest("""{"tag_name":"  "}"""))
    }

    @Test
    fun `仅当远端版本更高时提示`() {
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("1.1.0", "2.0.0"))
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.10.0"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.0.9"))
    }

    @Test
    fun `版本号比较忽略前导 v 与后缀`() {
        assertEquals(0, UpdateChecker.compareVersions("v1.1.0", "1.1.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.1", "1.1.0"))
        assertEquals(0, UpdateChecker.compareVersions("1.1.0", "1.1.0-beta.1"))
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.1.1") < 0)
        assertTrue(UpdateChecker.compareVersions("1.1.0", "1.0.9") > 0)
    }

    @Test
    fun `接口不可达时静默返回无更新`() {
        assertNull(UpdateChecker.check("1.0.0", "http://127.0.0.1:1/releases/latest"))
    }

    @Test
    fun `接口地址指向本仓库的最新发行`() {
        assertEquals("https://api.github.com/repos/Lin-M11/MusicUnlock/releases/latest", UpdateChecker.apiUrl)
    }
}
