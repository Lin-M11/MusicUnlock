package musicunlock

import musicunlock.settings.AppSettings
import musicunlock.settings.AccountSnapshot
import musicunlock.settings.OutputFormat
import musicunlock.settings.SettingsRepository
import musicunlock.settings.defaultSettingsFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SettingsStoreTest {

    @Test
    fun `默认配置文件位于用户主目录`() {
        assertEquals(
            File(System.getProperty("user.home"), ".musicunlock/config"),
            defaultSettingsFile(),
        )
    }

    @Test
    fun `设置写入后重新加载仍保留`() {
        val dir = Files.createTempDirectory("musicunlock-settings").toFile()
        val file = dir.resolve("config")
        try {
            val repository = SettingsRepository(file)
            repository.update {
                it.copy(
                    outputDir = "/tmp/music",
                    dedup = true,
                    outputFormat = OutputFormat.MP3,
                    bitrateKbps = 192,
                    windowWidth = 1280,
                    windowHeight = 820,
                    neteaseCookie = "MUSIC_U=netease",
                    qqCookie = "uin=123; qqmusic_key=key",
                    neteaseAccount = AccountSnapshot("网易用户", "https://example.com/netease.png", "1"),
                    qqAccount = AccountSnapshot("QQ 用户", "https://example.com/qq.png", "123"),
                )
            }

            val reloaded = SettingsRepository(file).load()
            assertEquals("/tmp/music", reloaded.outputDir)
            assertEquals(true, reloaded.dedup)
            assertEquals(OutputFormat.MP3, reloaded.outputFormat)
            assertEquals(192, reloaded.bitrateKbps)
            assertEquals(1280, reloaded.windowWidth)
            assertEquals(820, reloaded.windowHeight)
            assertEquals("MUSIC_U=netease", reloaded.neteaseCookie)
            assertEquals("uin=123; qqmusic_key=key", reloaded.qqCookie)
            assertEquals(AccountSnapshot("网易用户", "https://example.com/netease.png", "1"), reloaded.neteaseAccount)
            assertEquals(AccountSnapshot("QQ 用户", "https://example.com/qq.png", "123"), reloaded.qqAccount)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `恢复默认会清空个性化设置和登录态`() {
        val dir = Files.createTempDirectory("musicunlock-settings").toFile()
        val file = dir.resolve("config")
        try {
            val repository = SettingsRepository(file)
            repository.update {
                it.copy(
                    dedup = true,
                    outputFormat = OutputFormat.MP3,
                    neteaseCookie = "cookie",
                    neteaseAccount = AccountSnapshot("网易用户", null, "1"),
                )
            }

            val defaults = repository.reset()
            assertEquals(AppSettings(), defaults)
            assertFalse(defaults.dedup)
            assertNull(defaults.neteaseCookie)
            assertNull(defaults.neteaseAccount)
            assertEquals(AppSettings(), SettingsRepository(file).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `损坏配置回落到默认值`() {
        val dir = Files.createTempDirectory("musicunlock-settings").toFile()
        val file = dir.resolve("config")
        try {
            file.writeText("not-json")
            assertEquals(AppSettings(), SettingsRepository(file).load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `旧配置缺少账号快照时仍可读取`() {
        val dir = Files.createTempDirectory("musicunlock-settings").toFile()
        val file = dir.resolve("config")
        try {
            file.writeText(
                """{"outputDir":"/tmp/music","dedup":true,"neteaseCookie":"MUSIC_U=old"}""",
            )
            val loaded = SettingsRepository(file).load()
            assertEquals("/tmp/music", loaded.outputDir)
            assertEquals(true, loaded.dedup)
            assertEquals("MUSIC_U=old", loaded.neteaseCookie)
            assertNull(loaded.neteaseAccount)
            assertNull(loaded.qqAccount)
        } finally {
            dir.deleteRecursively()
        }
    }
}
