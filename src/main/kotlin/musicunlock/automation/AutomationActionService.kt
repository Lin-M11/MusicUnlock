package musicunlock.automation

import com.google.gson.Gson
import musicunlock.settings.AutomationRule
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/** 自动化规则命中后的 Webhook 与外部命令动作。 */
class AutomationActionService(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    fun afterMatch(rule: AutomationRule, input: File, outputDir: File, outputFile: File? = null): List<String> {
        val messages = mutableListOf<String>()
        rule.webhookUrl?.takeIf(String::isNotBlank)?.let { url ->
            runCatching { postWebhook(url, rule, input, outputDir, outputFile) }
                .onFailure { messages += "Webhook 失败：${it.message}" }
        }
        if (!rule.postCommand.isNullOrBlank()) {
            runCatching {
                val replacements = mapOf(
                    "{input}" to input.absolutePath,
                    "{inputDir}" to input.parentFile?.absolutePath.orEmpty(),
                    "{outputDir}" to outputDir.absolutePath,
                    "{output}" to outputFile?.absolutePath.orEmpty(),
                    "{name}" to input.nameWithoutExtension,
                    "{extension}" to input.extension,
                    "{rule}" to rule.name,
                )
                fun expand(value: String): String = replacements.entries.fold(value) { acc, (key, replacement) ->
                    acc.replace(key, replacement)
                }
                val command = buildList {
                    add(expand(rule.postCommand!!))
                    rule.postCommandArgs.forEach { add(expand(it)) }
                }
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                val finished = process.waitFor(30, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    error("命令执行超时")
                }
                val output = process.inputStream.bufferedReader().readText().trim()
                if (process.exitValue() != 0) error(output.ifBlank { "退出码 ${process.exitValue()}" })
            }.onFailure { messages += "后置命令失败：${it.message}" }
        }
        return messages
    }

    private fun postWebhook(url: String, rule: AutomationRule, input: File, outputDir: File, outputFile: File?) {
        val body = Gson().toJson(
            mapOf(
                "event" to "automation.completed",
                "ruleId" to rule.id,
                "ruleName" to rule.name,
                "inputPath" to input.absolutePath,
                "outputDir" to outputDir.absolutePath,
                "outputPath" to outputFile?.absolutePath,
                "fileName" to input.name,
                "size" to input.length(),
                "timestamp" to System.currentTimeMillis(),
            ),
        )
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()}" }
    }
}
