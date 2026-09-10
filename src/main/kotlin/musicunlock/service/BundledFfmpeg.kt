package musicunlock.service

import musicunlock.BuildInfo
import java.io.File
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 定位应用随包分发的 ffmpeg，并在首次使用时解压到用户缓存目录。 */
object BundledFfmpeg {

    @Volatile
    private var resolved: File? = null

    fun locate(): String? {
        resolved?.takeIf(::isUsable)?.let { return it.absolutePath }
        return synchronized(this) {
            resolved?.takeIf(::isUsable)?.absolutePath ?: resolveBundled()?.absolutePath
        }
    }

    private fun resolveBundled(): File? {
        val platform = platformName() ?: return null
        val executableName = if (platform.startsWith("windows")) "ffmpeg.exe" else "ffmpeg"
        val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
            ?: File(".").absolutePath
        val cacheDir = File(home, ".musicunlock/runtime/ffmpeg-${BuildInfo.FFMPEG_VERSION}/$platform")
        val executable = File(cacheDir, executableName)
        val completeMarker = File(cacheDir, ".complete")
        if (isUsable(executable) && completeMarker.isFile) {
            resolved = executable
            return executable
        }

        val resourcePath = "org/bytedeco/ffmpeg/$platform/$executableName"
        val resource = BundledFfmpeg::class.java.classLoader.getResource(resourcePath) ?: return null
        val connection = resource.openConnection() as? JarURLConnection ?: return null
        connection.useCaches = false
        val parent = cacheDir.parentFile ?: File(home, ".musicunlock/runtime")
        parent.mkdirs()
        val temp = File(parent, "${cacheDir.name}.tmp-${UUID.randomUUID()}")
        temp.mkdirs()

        try {
            val jar = connection.jarFile
            try {
                val prefix = "org/bytedeco/ffmpeg/$platform/"
                var copied = 0
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
                    val relative = entry.name.removePrefix(prefix)
                    if (relative.isBlank()) continue
                    val target = File(temp, relative)
                    target.parentFile?.mkdirs()
                    jar.getInputStream(entry).use { input ->
                        Files.copy(input, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                    copied++
                }
                if (copied == 0) return null
            } finally {
                runCatching { jar.close() }
            }

            val extractedExecutable = File(temp, executableName)
            extractedExecutable.setExecutable(true, false)
            completeMarkerFor(temp).writeText(BuildInfo.FFMPEG_VERSION)
            if (cacheDir.exists()) cacheDir.deleteRecursively()
            try {
                Files.move(
                    temp.toPath(),
                    cacheDir.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: Exception) {
                if (!cacheDir.exists()) {
                    Files.move(temp.toPath(), cacheDir.toPath())
                }
            }
            val installed = File(cacheDir, executableName)
            if (!isUsable(installed) || !completeMarkerFor(cacheDir).isFile) return null
            resolved = installed
            return installed
        } catch (e: Exception) {
            println("内置 ffmpeg 解压失败: ${e.message ?: e.toString()}")
            return null
        } finally {
            if (temp.exists()) temp.deleteRecursively()
        }
    }

    private fun completeMarkerFor(dir: File): File = File(dir, ".complete")

    private fun isUsable(file: File): Boolean =
        file.isFile && (isWindows() || file.canExecute())

    private fun platformName(): String? {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("mac") && (arch == "aarch64" || arch == "arm64") -> "macosx-arm64"
            os.contains("mac") -> "macosx-x86_64"
            os.contains("win") -> "windows-x86_64"
            (os.contains("linux") || os.contains("nux")) &&
                (arch == "aarch64" || arch == "arm64") -> "linux-arm64"
            os.contains("linux") || os.contains("nux") -> "linux-x86_64"
            else -> null
        }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
}
