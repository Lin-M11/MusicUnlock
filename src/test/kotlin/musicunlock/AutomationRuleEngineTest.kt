package musicunlock

import musicunlock.automation.AutomationRuleEngine
import musicunlock.settings.AppSettings
import musicunlock.settings.AutomationRule
import musicunlock.settings.OutputFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZonedDateTime

class AutomationRuleEngineTest {
    @Test
    fun `selects matching rule and applies its output profile`() {
        val input = java.io.File(Files.createTempDirectory("musicunlock-rule-input").toString())
        val output = java.io.File(Files.createTempDirectory("musicunlock-rule-output").toString())
        val file = input.resolve("song.ncm").also { it.writeBytes(ByteArray(2048)) }
        val rule = AutomationRule(
            id = "rule",
            name = "NCM to FLAC",
            inputDir = input.absolutePath,
            outputDir = output.absolutePath,
            outputFormat = OutputFormat.FLAC,
            outputTemplate = "{artist}/{title}",
            extensions = listOf("ncm"),
        )
        val settings = AppSettings(automationRules = listOf(rule))

        assertEquals(rule, AutomationRuleEngine.select(settings, file))
        val effective = AutomationRuleEngine.effectiveSettings(settings, rule)
        assertEquals(output.absolutePath, effective.outputDir)
        assertEquals(OutputFormat.FLAC, effective.outputFormat)
        assertEquals("{artist}/{title}", effective.localOutputTemplate)
    }

    @Test
    fun `priority pattern schedule and weekdays are honored`() {
        val input = java.io.File(Files.createTempDirectory("musicunlock-rule-advanced").toString())
        val file = input.resolve("Live-2026.ncm").also { it.writeBytes(ByteArray(2048)) }
        val low = AutomationRule("low", "low", inputDir = input.absolutePath, outputDir = input.absolutePath, priority = 1)
        val high = AutomationRule(
            id = "high",
            name = "high",
            inputDir = input.absolutePath,
            outputDir = input.absolutePath,
            priority = 10,
            fileNamePattern = "*Live*",
            regexPattern = false,
            scheduleStartMinute = 8 * 60,
            scheduleEndMinute = 10 * 60,
            daysOfWeek = listOf(3),
        )
        val settings = AppSettings(automationRules = listOf(low, high))
        val now = ZonedDateTime.parse("2026-09-16T09:00:00+08:00")
        assertEquals(high, AutomationRuleEngine.select(settings, file, now))
        assertTrue(AutomationRuleEngine.simulate(settings, file, now).contains(high))
    }

    @Test
    fun `does not match disabled or too small files`() {
        val input = java.io.File(Files.createTempDirectory("musicunlock-rule-skip").toString())
        val file = input.resolve("song.ncm").also { it.writeBytes(ByteArray(10)) }
        val rule = AutomationRule(
            id = "rule",
            name = "Large files",
            inputDir = input.absolutePath,
            outputDir = input.absolutePath,
            minBytes = 1024,
        )
        assertTrue(AutomationRuleEngine.select(AppSettings(automationRules = listOf(rule)), file) == null)
    }
}
