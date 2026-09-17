package musicunlock.settings

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Base64

/** 登录 Cookie 的持久化接口。 */
interface CredentialStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun clear()
}

/** 测试及不支持系统凭据库时的本地回退实现。 */
class FileCredentialStore(private val file: File) : CredentialStore {
    private val lock = Any()

    override fun get(key: String): String? = synchronized(lock) {
        read().decoded()[key]
    }

    override fun put(key: String, value: String?): Unit = synchronized(lock) {
        val values = read().decoded()
        if (value.isNullOrBlank()) values.remove(key) else values[key] = value
        write(values)
    }

    override fun clear(): Unit = synchronized(lock) {
        file.delete()
        Unit
    }

    private fun read(): String = runCatching { file.takeIf(File::isFile)?.readText().orEmpty() }.getOrDefault("")

    private fun write(values: Map<String, String>) {
        file.parentFile?.mkdirs()
        val temp = File.createTempFile(file.name, ".tmp", file.parentFile)
        temp.writeText(values.entries.joinToString("\n") { (key, value) ->
            "${Base64.getUrlEncoder().encodeToString(key.toByteArray())}.${Base64.getUrlEncoder().encodeToString(value.toByteArray())}"
        })
        runCatching {
            Files.setPosixFilePermissions(temp.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun String.decoded(): MutableMap<String, String> = lineSequence().mapNotNull { line ->
        val index = line.indexOf('.')
        if (index <= 0) return@mapNotNull null
        runCatching {
            String(Base64.getUrlDecoder().decode(line.substring(0, index))) to
                String(Base64.getUrlDecoder().decode(line.substring(index + 1)))
        }.getOrNull()
    }.toMap().toMutableMap()
}

/** 优先使用 macOS Keychain、Linux Secret Service、Windows DPAPI。 */
class PlatformCredentialStore : CredentialStore {
    private val fallback = FileCredentialStore(File(System.getProperty("user.home"), ".musicunlock/credentials"))
    private val cache = mutableMapOf<String, String?>()

    override fun get(key: String): String? = synchronized(cache) {
        if (cache.containsKey(key)) return cache[key]
        val value = when {
            isMac() -> macGet(key)
            isLinux() -> linuxGet(key)
            isWindows() -> windowsGet(key)
            else -> null
        } ?: fallback.get(key)
        cache[key] = value
        value
    }

    override fun put(key: String, value: String?): Unit = synchronized(cache) {
        cache[key] = value
        val systemStoreSucceeded = when {
            isMac() -> if (value.isNullOrBlank()) macDelete(key) else macPut(key, value)
            isLinux() -> if (value.isNullOrBlank()) linuxDelete(key) else linuxPut(key, value)
            isWindows() -> if (value.isNullOrBlank()) windowsDelete(key) else windowsPut(key, value)
            else -> false
        }
        if (value.isNullOrBlank() || !systemStoreSucceeded) fallback.put(key, value)
        else fallback.put(key, null)
        Unit
    }

    override fun clear(): Unit = synchronized(cache) {
        cache.clear()
        if (isMac()) macDelete(null)
        fallback.clear()
        Unit
    }

    private fun macGet(key: String): String? {
        val (ok, output) = runCommand(listOf("security", "find-generic-password", "-a", account(key), "-s", SERVICE, "-w"))
        return output.trim().takeIf { ok && it.isNotBlank() }
    }

    private fun macPut(key: String, value: String): Boolean =
        runCommand(listOf("security", "add-generic-password", "-U", "-a", account(key), "-s", SERVICE, "-w", value)).first

    private fun macDelete(key: String?): Boolean {
        val keys = key?.let(::listOf) ?: listOf("netease", "qq", "kugou", "kuwo")
        return keys.map { runCommand(listOf("security", "delete-generic-password", "-a", account(it), "-s", SERVICE)).first }.any { it }
    }

    private fun linuxGet(key: String): String? {
        val (ok, output) = runCommand(listOf("secret-tool", "lookup", "service", SERVICE, "key", key))
        return output.trim().takeIf { ok && it.isNotBlank() }
    }

    private fun linuxPut(key: String, value: String): Boolean = runCommand(
        listOf("secret-tool", "store", "--label=MusicUnlock $key", "service", SERVICE, "key", key),
        input = value,
    ).first

    private fun linuxDelete(key: String): Boolean =
        runCommand(listOf("secret-tool", "clear", "service", SERVICE, "key", key)).first

    private fun windowsGet(key: String): String? {
        val path = windowsPath(key).replace("'", "''")
        val script = "Add-Type -AssemblyName System.Security; " +
            "${'$'}p=[Convert]::FromBase64String((Get-Content -Raw '$path')); " +
            "${'$'}b=[System.Security.Cryptography.ProtectedData]::Unprotect(${'$'}p,${'$'}null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); " +
            "[Text.Encoding]::UTF8.GetString(${'$'}b)"
        val (ok, output) = runCommand(listOf("powershell", "-NoProfile", "-Command", script))
        return output.trim().takeIf { ok && it.isNotBlank() }
    }

    private fun windowsPut(key: String, value: String): Boolean {
        val path = windowsPath(key).replace("'", "''")
        val escapedValue = value.replace("'", "''")
        val script = "New-Item -ItemType Directory -Force -Path '${File(path).parent}' | Out-Null; " +
            "Add-Type -AssemblyName System.Security; " +
            "${'$'}b=[Text.Encoding]::UTF8.GetBytes('$escapedValue'); " +
            "${'$'}p=[System.Security.Cryptography.ProtectedData]::Protect(${'$'}b,${'$'}null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); " +
            "[Convert]::ToBase64String(${'$'}p) | Set-Content -Encoding utf8 '$path'"
        return runCommand(listOf("powershell", "-NoProfile", "-Command", script)).first
    }

    private fun windowsDelete(key: String): Boolean {
        return runCommand(listOf("powershell", "-NoProfile", "-Command", "Remove-Item -Force -ErrorAction SilentlyContinue '${windowsPath(key).replace("'", "''")}'")).first
    }

    private fun account(key: String): String = "${System.getProperty("user.name", "user")}:$key"

    private fun windowsPath(key: String): String = File(System.getProperty("user.home"), ".musicunlock/$key.cred").absolutePath

    private fun isMac() = System.getProperty("os.name").lowercase().contains("mac")
    private fun isWindows() = System.getProperty("os.name").lowercase().contains("win")
    private fun isLinux() = System.getProperty("os.name").lowercase().contains("linux")

    private fun runCommand(command: List<String>, input: String? = null): Pair<Boolean, String> = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (input != null) process.outputStream.bufferedWriter().use { it.write(input) }
        val output = process.inputStream.bufferedReader().readText()
        (process.waitFor() == 0) to output
    }.getOrDefault(false to "")
}

private const val SERVICE = "com.musicunlock.app"
