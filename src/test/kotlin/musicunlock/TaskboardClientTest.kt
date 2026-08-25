package musicunlock

import musicunlock.ncm.TaskboardClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** taskctl 提交逻辑测试。 */
class TaskboardClientTest {

    @Test
    fun `从 taskctl 输出解析任务标识`() {
        val json = """{"schemaVersion":2,"task":{"id":"abc","identifier":"MUS-15","title":"晴天 - 周杰伦","status":"backlog"}}"""
        assertEquals("MUS-15", TaskboardClient.parseCreatedIdentifier(json))
    }

    @Test
    fun `输出格式异常时解析返回 null`() {
        assertEquals(null, TaskboardClient.parseCreatedIdentifier("not json"))
        assertEquals(null, TaskboardClient.parseCreatedIdentifier("""{"error":"boom"}"""))
    }

    @Test
    fun `通过假 taskctl 成功提交下载任务`() {
        if (System.getProperty("os.name").startsWith("Windows")) return // 假脚本仅用于类 Unix
        val dir = java.nio.file.Files.createTempDirectory("taskctl-test").toFile()
        val fake = File(dir, "taskctl")
        fake.writeText(
            "#!/bin/sh\n" +
                "echo '{\"schemaVersion\":2,\"task\":{\"identifier\":\"MUS-15\",\"status\":\"backlog\"}}'\n",
        )
        fake.setExecutable(true)
        try {
            val result = TaskboardClient.submitDownloadTask(
                songName = "晴天",
                artist = "周杰伦",
                sourcePlaylist = "我的歌单",
                threadId = "test-thread",
                bin = fake.absolutePath,
            )
            assertTrue(result.ok)
            assertEquals("MUS-15", result.identifier)
        } finally {
            fake.delete()
            dir.delete()
        }
    }

    @Test
    fun `taskctl 不可用时返回失败结果`() {
        val result = TaskboardClient.submitDownloadTask(
            songName = "晴天",
            artist = "周杰伦",
            sourcePlaylist = "我的歌单",
            threadId = "test-thread",
            bin = "/nonexistent/taskctl",
        )
        assertTrue(!result.ok)
    }
}
