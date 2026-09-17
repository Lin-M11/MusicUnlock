package musicunlock.automation

import musicunlock.settings.AppSettings
import musicunlock.settings.AutomationRule
import java.io.File
import java.time.ZonedDateTime

/** 自动化规则匹配：目录、扩展名、大小、名称模式、时间窗口和优先级。 */
object AutomationRuleEngine {
    fun select(settings: AppSettings, file: File, now: ZonedDateTime = ZonedDateTime.now()): AutomationRule? =
        settings.automationRules
            .withIndex()
            .filter { (_, rule) -> matches(rule, file, now) }
            .sortedWith(compareByDescending<IndexedValue<AutomationRule>> { it.value.priority }.thenBy { it.index })
            .firstOrNull()
            ?.value

    fun matchAll(settings: AppSettings, file: File, now: ZonedDateTime = ZonedDateTime.now()): List<AutomationRule> =
        settings.automationRules.filter { matches(it, file, now) }

    fun matches(rule: AutomationRule, file: File, now: ZonedDateTime = ZonedDateTime.now()): Boolean {
        if (!rule.enabled || !file.isFile) return false
        if (file.length() < rule.minBytes.coerceAtLeast(0L)) return false
        if (rule.maxBytes != null && file.length() > rule.maxBytes) return false
        val absolute = file.absoluteFile.toPath().normalize()
        val root = File(rule.inputDir).absoluteFile.toPath().normalize()
        if (!absolute.startsWith(root)) return false
        val extension = file.extension.lowercase()
        if (rule.extensions.isNotEmpty() && extension !in rule.extensions.map { it.lowercase() }) return false
        if (!rule.fileNamePattern.isNullOrBlank()) {
            val pattern = rule.fileNamePattern
            val matchesName = if (rule.regexPattern) {
                runCatching { Regex(pattern).containsMatchIn(file.name) }.getOrDefault(false)
            } else {
                val regex = pattern
                    .replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
                runCatching { Regex("^$regex$", RegexOption.IGNORE_CASE).matches(file.name) }.getOrDefault(false)
            }
            if (!matchesName) return false
        }
        if (rule.daysOfWeek.isNotEmpty() && now.dayOfWeek.value !in rule.daysOfWeek) return false
        if (rule.scheduleStartMinute != null && rule.scheduleEndMinute != null) {
            val minute = now.hour * 60 + now.minute
            val start = rule.scheduleStartMinute
            val end = rule.scheduleEndMinute
            val inWindow = if (start <= end) minute in start..end else minute >= start || minute <= end
            if (!inWindow) return false
        }
        return true
    }

    fun simulate(settings: AppSettings, file: File, now: ZonedDateTime = ZonedDateTime.now()): List<AutomationRule> =
        matchAll(settings, file, now)

    fun effectiveSettings(base: AppSettings, rule: AutomationRule?): AppSettings {
        if (rule == null) return base
        return base.copy(
            outputDir = rule.outputDir,
            outputFormat = rule.outputFormat,
            bitrateKbps = rule.bitrateKbps,
            localOutputTemplate = rule.outputTemplate,
            localExistingFilePolicy = rule.existingFilePolicy,
        )
    }
}
