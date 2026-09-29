package musicunlock.automation

import com.google.gson.GsonBuilder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class AutomationRunRecord(
    val id: String = UUID.randomUUID().toString(),
    val ruleId: String?,
    val ruleName: String,
    val inputPath: String,
    val outputPath: String? = null,
    val success: Boolean,
    val message: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 自动化执行历史，滚动保留最近 200 条记录。 */
class AutomationHistoryStore(
    private val file: File = defaultAutomationHistoryFile(),
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Synchronized
    fun all(limit: Int = 200): List<AutomationRunRecord> = load().take(limit.coerceIn(1, 200))

    @Synchronized
    fun add(record: AutomationRunRecord): AutomationRunRecord {
        val next = (listOf(record) + load()).take(200)
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(file.name, ".tmp", file.parentFile)
        try {
            temp.writeText(gson.toJson(next))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temp.delete()
        }
        return record
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun load(): List<AutomationRunRecord> = runCatching {
        if (!file.isFile) emptyList()
        else gson.fromJson(file.readText(), Array<AutomationRunRecord>::class.java).toList()
    }.getOrDefault(emptyList())
}

fun defaultAutomationHistoryFile(): File =
    File(System.getProperty("user.home"), ".musicunlock/automation-history.json")
