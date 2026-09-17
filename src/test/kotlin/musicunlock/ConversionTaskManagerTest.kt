package musicunlock

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import musicunlock.service.ConversionCancelledException
import musicunlock.service.ConversionExecutor
import musicunlock.service.ConversionOutcome
import musicunlock.service.ConversionStage
import musicunlock.service.ConversionTaskManager
import musicunlock.service.ConversionTaskState
import musicunlock.settings.OutputFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ConversionTaskManagerTest {
    @Test
    fun `paused conversion can resume and complete`() = runBlocking {
        val dir = Files.createTempDirectory("musicunlock-conversion-task")
        val input = dir.resolve("song.ncm").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val output = dir.resolve("song.flac")
        val calls = AtomicInteger()
        val manager = ConversionTaskManager(
            taskFile = dir.resolve("tasks.json").toFile(),
            maxParallelism = { 1 },
            executor = ConversionExecutor { task, onProgress, shouldContinue ->
                onProgress(0.4f, ConversionStage.DECODING)
                if (calls.incrementAndGet() == 1) {
                    while (shouldContinue()) Thread.sleep(10L)
                    throw ConversionCancelledException()
                }
                ConversionOutcome(task.inputPath, null, outputPath = output.toString())
            },
        )

        val id = manager.enqueue(
            inputPath = input.toString(),
            outputDir = dir.toString(),
            outputFormat = OutputFormat.ORIGINAL,
        )
        withTimeout(15_000L) {
            manager.tasks.first { tasks -> tasks.first { it.id == id }.state == ConversionTaskState.RUNNING }
        }
        manager.pause(id)
        withTimeout(15_000L) {
            manager.tasks.first { tasks -> tasks.first { it.id == id }.state == ConversionTaskState.PAUSED }
        }

        manager.resume(id)
        val completed = withTimeout(15_000L) {
            manager.tasks.first { tasks -> tasks.first { it.id == id }.state == ConversionTaskState.COMPLETED }
        }.first { it.id == id }

        assertEquals(2, calls.get())
        assertEquals(output.toString(), completed.outputPath)
    }
}
