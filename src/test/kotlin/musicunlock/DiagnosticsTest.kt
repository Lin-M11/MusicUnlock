package musicunlock

import musicunlock.diagnostics.Diagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiagnosticsTest {
    @Test
    fun `redacts cookies tokens and signed urls`() {
        val source = "Cookie: MUSIC_U=abc123; token=xyz https://example.test/a?signature=secret"
        val redacted = Diagnostics.redact(source)
        assertFalse(redacted.contains("abc123"))
        assertFalse(redacted.contains("xyz"))
        assertFalse(redacted.contains("secret"))
        assertTrue(redacted.contains("***"))
    }

    @Test
    fun `persistent log writes redacted lines`() {
        val file = Files.createTempDirectory("musicunlock-diagnostics").resolve("app.log").toFile()
        try {
            Diagnostics.initialize(file)
            Diagnostics.log("token=secret-value")
            val text = file.readText()
            assertTrue(text.contains("token=***"))
            assertFalse(text.contains("secret-value"))
        } finally {
            Diagnostics.disablePersistence()
        }
    }
}
