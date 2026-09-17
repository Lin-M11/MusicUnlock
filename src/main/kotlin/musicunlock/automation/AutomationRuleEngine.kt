package musicunlock.automation

import musicunlock.settings.AppSettings
import musicunlock.settings.AutomationRule
import java.io.File

/** 根据监听目录、扩展名和文件大小选择第一条匹配的自动转换规则。 */
object AutomationRuleEngine {
    fun select(settings: AppSettings, file: File): AutomationRule? {
        val absolute = file.absoluteFile.toPath().normalize()
        return settings.automationRules.firstOrNull { rule ->
            if (!rule.enabled || file.length() < rule.minBytes.coerceAtLeast(0L)) return@firstOrNull false
            val extension = file.extension.lowercase()
            if (rule.extensions.isNotEmpty() && extension !in rule.extensions) return@firstOrNull false
            val root = File(rule.inputDir).absoluteFile.toPath().normalize()
            absolute.startsWith(root)
        }
    }

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
