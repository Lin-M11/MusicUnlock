package musicunlock.ncm

import com.google.gson.JsonParser
import java.io.File
import java.util.UUID

/** taskctl 提交结果。 */
class TaskSubmitResult(
    val ok: Boolean,
    val identifier: String?,
    val message: String,
)

/**
 * 通过 taskctl 把下载任务提交到 Codex 任务面板（musicunlock 项目，
 * 状态为「待立项」backlog）。任务包含歌曲名、歌手、来源歌单。
 *
 * 桌面应用环境没有 Codex 会话，使用会话级 UUID 作为写入归属（--thread-id）。
 */
object TaskboardClient {

    /** 当前应用会话的写入归属 id（每次启动生成一个）。 */
    fun sessionThreadId(): String = UUID.randomUUID().toString()

    /** 定位 taskctl 可执行文件：TASKCTL_BIN → PATH → macOS 打包内置路径。 */
    fun locateTaskctl(): String? {
        System.getenv("TASKCTL_BIN")?.takeIf { File(it).canExecute() }?.let { return it }
        val pathDirs = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        for (dir in pathDirs) {
            val candidate = File(dir, if (isWindows()) "taskctl.exe" else "taskctl")
            if (candidate.canExecute()) return candidate.absolutePath
        }
        val packaged = "/Applications/Codex Taskboard.app/Contents/Resources/bin/taskctl"
        if (File(packaged).canExecute()) return packaged
        return null
    }

    /**
     * 提交一条下载任务。songName / artist / sourcePlaylist 写入任务内容，
     * 状态固定为 backlog（待立项）。
     */
    fun submitDownloadTask(
        songName: String,
        artist: String,
        sourcePlaylist: String,
        threadId: String,
        bin: String? = null,
    ): TaskSubmitResult {
        val taskctl = bin ?: locateTaskctl()
            ?: return TaskSubmitResult(false, null, "未找到 taskctl，请确认已安装 Codex Taskboard")
        val title = listOf(songName, artist).filter { it.isNotBlank() }.joinToString(" - ")
        val description = buildString {
            appendLine("## 下载任务")
            appendLine("- 歌曲：${songName.ifBlank { "-" }}")
            appendLine("- 歌手：${artist.ifBlank { "-" }}")
            appendLine("- 来源歌单：${sourcePlaylist.ifBlank { "-" }}")
        }
        val command = listOf(
            taskctl, "issue", "create",
            "--project", "musicunlock",
            "--title", title,
            "--description", description,
            "--status", "backlog",
            "--thread-id", threadId,
            "--json",
        )
        return try {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0) {
                val identifier = parseCreatedIdentifier(output)
                if (identifier != null) {
                    TaskSubmitResult(true, identifier, "已提交 $identifier")
                } else {
                    TaskSubmitResult(false, null, "taskctl 返回异常：${output.take(300)}")
                }
            } else {
                TaskSubmitResult(false, null, "taskctl 提交失败：${output.take(300)}")
            }
        } catch (e: Exception) {
            TaskSubmitResult(false, null, "调用 taskctl 失败：${e.message}")
        }
    }

    /** 从 taskctl issue create 的 JSON 输出中解析任务标识（如 MUS-15）。 */
    internal fun parseCreatedIdentifier(json: String): String? {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val task = root.getAsJsonObject("task")
            task.get("identifier")?.asString
        } catch (e: Exception) {
            null
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
