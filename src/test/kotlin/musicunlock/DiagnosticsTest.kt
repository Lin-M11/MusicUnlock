package musicunlock

import musicunlock.diagnostics.Diagnostics
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
