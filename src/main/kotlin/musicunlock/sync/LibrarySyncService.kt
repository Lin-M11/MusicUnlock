package musicunlock.sync

import com.google.gson.GsonBuilder
import musicunlock.library.LibraryEntry
import musicunlock.online.OutputTemplate
import musicunlock.service.AudioTranscoder
import musicunlock.settings.LibrarySyncProfile
import musicunlock.settings.PlatformCredentialStore
import musicunlock.settings.SyncConflictPolicy
import musicunlock.settings.SyncDestinationType
import musicunlock.settings.SyncMode
import musicunlock.settings.extension
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

data class SyncFileState(
    val relativePath: String,
    val size: Long,
    val modifiedAt: Long,
    val hash: String,
    val etag: String? = null,
    val uploadedAt: Long = System.currentTimeMillis(),
)

data class SyncManifest(
    val schemaVersion: Int = 1,
    val files: Map<String, SyncFileState> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class SyncConflict(
    val relativePath: String,
    val reason: String,
)

data class SyncResult(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val skipped: Int = 0,
    val deleted: Int = 0,
    val conflicts: List<SyncConflict> = emptyList(),
    val errors: List<String> = emptyList(),
) {
    val summary: String
        get() = "上传 $uploaded · 下载 $downloaded · 跳过 $skipped · 删除 $deleted" +
            if (conflicts.isEmpty()) "" else " · 冲突 ${conflicts.size}"
}

/** 曲库到本地目录 / WebDAV 的增量同步。 */
class LibrarySyncService(
    private val credentials: PlatformCredentialStore = PlatformCredentialStore(),
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun sync(profile: LibrarySyncProfile, entries: List<LibraryEntry>): SyncResult =
        when (profile.destinationType) {
            SyncDestinationType.LOCAL_FOLDER -> syncLocal(profile, entries)
            SyncDestinationType.WEBDAV -> syncWebDav(profile, entries)
        }

    private fun syncLocal(profile: LibrarySyncProfile, entries: List<LibraryEntry>): SyncResult {
        val root = profile.localPath?.let(::File)?.absoluteFile ?: return SyncResult(errors = listOf("未配置本地同步目录"))
        root.mkdirs()
        if (!root.isDirectory) return SyncResult(errors = listOf("无法创建同步目录：${root.absolutePath}"))
        val manifestFile = File(root, MANIFEST_FILE)
        var manifest = readLocalManifest(manifestFile)
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var deleted = 0
        val conflicts = mutableListOf<SyncConflict>()
        val errors = mutableListOf<String>()
        val current = linkedMapOf<String, SyncFileState>()

        entries.filter { File(it.path).isFile }.forEach { entry ->
            runCatching {
                val relative = relativePath(profile, entry)
                val prepared = prepareSource(profile, entry)
                val target = File(root, relative)
                val sourceState = fileState(relative, prepared, manifest.files[relative]?.etag)
                val existingState = manifest.files[relative]
                val remoteFile = target.takeIf(File::isFile)
                val localChanged = existingState == null || existingState.hash != sourceState.hash || existingState.modifiedAt != sourceState.modifiedAt
                val remoteChanged = remoteFile != null && existingState != null &&
                    (remoteFile.length() != existingState.size || remoteFile.lastModified() > existingState.uploadedAt)
                when {
                    remoteChanged && localChanged -> when (profile.conflictPolicy) {
                        SyncConflictPolicy.KEEP_LOCAL -> {
                            copyAtomically(prepared, target)
                            current[relative] = sourceState.copy(uploadedAt = System.currentTimeMillis())
                            uploaded++
                            conflicts += SyncConflict(relative, "双端均有修改，已保留本地版本")
                        }
                        SyncConflictPolicy.KEEP_REMOTE -> {
                            skipped++
                            conflicts += SyncConflict(relative, "双端均有修改，已保留远端版本")
                            current[relative] = existingState.copy(size = remoteFile.length(), modifiedAt = remoteFile.lastModified())
                        }
                        SyncConflictPolicy.KEEP_NEWER -> {
                            if (prepared.lastModified() >= remoteFile.lastModified()) {
                                copyAtomically(prepared, target)
                                current[relative] = sourceState.copy(uploadedAt = System.currentTimeMillis())
                                uploaded++
                                conflicts += SyncConflict(relative, "双端均有修改，已保留较新的本地版本")
                            } else {
                                skipped++
                                conflicts += SyncConflict(relative, "双端均有修改，已保留较新的远端版本")
                                current[relative] = existingState.copy(size = remoteFile.length(), modifiedAt = remoteFile.lastModified())
                            }
                        }
                        SyncConflictPolicy.KEEP_BOTH -> {
                            val conflictTarget = uniqueConflictFile(target)
                            copyAtomically(prepared, conflictTarget)
                            current[relative] = sourceState.copy(uploadedAt = System.currentTimeMillis())
                            uploaded++
                            conflicts += SyncConflict(relative, "双端均有修改，已保留冲突副本 ${conflictTarget.name}")
                        }
                    }
                    localChanged -> {
                        copyAtomically(prepared, target)
                        current[relative] = sourceState.copy(uploadedAt = System.currentTimeMillis())
                        uploaded++
                    }
                    else -> {
                        skipped++
                        current[relative] = existingState?.copy(size = remoteFile?.length() ?: existingState.size,
                            modifiedAt = remoteFile?.lastModified() ?: existingState.modifiedAt) ?: sourceState
                    }
                }
                if (prepared != File(entry.path)) prepared.delete()
            }.onFailure { errors += "${entry.path}: ${it.message}" }
        }

        if (profile.mode == SyncMode.TWO_WAY) {
            manifest.files.filterKeys { it !in current.keys }.forEach { (relative, state) ->
                runCatching {
                    val remote = File(root, relative)
                    if (remote.isFile) {
                        val restoreRoot = File(System.getProperty("user.home"), "Music/MusicUnlock-Sync-Restore")
                        val restored = File(restoreRoot, relative)
                        copyAtomically(remote, restored)
                        downloaded++
                    }
                }.onFailure { errors += "下载 $relative: ${it.message}" }
            }
        }

        if (profile.mirrorDeletes) {
            manifest.files.keys.filter { it !in current.keys }.forEach { relative ->
                val target = File(root, relative)
                if (runCatching { target.delete() }.getOrDefault(false)) deleted++
            }
        }
        manifest = SyncManifest(files = current, updatedAt = System.currentTimeMillis())
        writeLocalManifest(manifestFile, manifest)
        return SyncResult(uploaded, downloaded, skipped, deleted, conflicts, errors)
    }

    private fun syncWebDav(profile: LibrarySyncProfile, entries: List<LibraryEntry>): SyncResult {
        val base = profile.remoteUrl?.takeIf(String::isNotBlank) ?: return SyncResult(errors = listOf("未配置 WebDAV 地址"))
        val root = joinUrl(base, profile.remoteDir)
        val rootManifestUrl = joinUrl(root, MANIFEST_FILE)
        var manifest = readRemoteManifest(profile, rootManifestUrl)
        var uploaded = 0
        var downloaded = 0
        var skipped = 0
        var deleted = 0
        val conflicts = mutableListOf<SyncConflict>()
        val errors = mutableListOf<String>()
        val current = linkedMapOf<String, SyncFileState>()

        entries.filter { File(it.path).isFile }.forEach { entry ->
            runCatching {
                val relative = relativePath(profile, entry)
                val prepared = prepareSource(profile, entry)
                val targetUrl = joinUrl(root, relative)
                val localState = fileState(relative, prepared, manifest.files[relative]?.etag)
                val previous = manifest.files[relative]
                val remoteHead = head(profile, targetUrl)
                val localChanged = previous == null || previous.hash != localState.hash || previous.modifiedAt != localState.modifiedAt
                val remoteChanged = remoteHead != null && previous != null && remoteHead.etag != null && previous.etag != remoteHead.etag
                val shouldUpload = when {
                    remoteHead == null -> true
                    remoteChanged && localChanged -> when (profile.conflictPolicy) {
                        SyncConflictPolicy.KEEP_LOCAL, SyncConflictPolicy.KEEP_BOTH -> true
                        SyncConflictPolicy.KEEP_REMOTE -> false
                        SyncConflictPolicy.KEEP_NEWER -> prepared.lastModified() >= previous.modifiedAt
                    }
                    remoteChanged -> false
                    localChanged -> true
                    else -> false
                }
                if (shouldUpload) {
                    putRemote(profile, targetUrl, prepared)
                    val etag = head(profile, targetUrl)?.etag
                    current[relative] = localState.copy(etag = etag, uploadedAt = System.currentTimeMillis())
                    uploaded++
                    if (remoteChanged && localChanged) {
                        conflicts += SyncConflict(relative, if (profile.conflictPolicy == SyncConflictPolicy.KEEP_BOTH) "已上传本地冲突副本" else "双端均有修改，按策略保留本地")
                    }
                } else {
                    if (remoteChanged && localChanged) conflicts += SyncConflict(relative, "双端均有修改，按策略保留远端")
                    if (profile.mode == SyncMode.TWO_WAY && remoteChanged) {
                        val bytes = getRemote(profile, targetUrl)
                        if (bytes != null) {
                            val restoreRoot = File(System.getProperty("user.home"), "Music/MusicUnlock-Sync-Restore")
                            val restored = File(restoreRoot, relative)
                            restored.parentFile?.mkdirs()
                            restored.writeBytes(bytes)
                            downloaded++
                        }
                    }
                    skipped++
                    current[relative] = (previous ?: localState).copy(
                        size = remoteHead?.size ?: previous?.size ?: localState.size,
                        etag = remoteHead?.etag ?: previous?.etag,
                    )
                }
                if (prepared != File(entry.path)) prepared.delete()
            }.onFailure { errors += "${entry.path}: ${it.message}" }
        }

        if (profile.mirrorDeletes) {
            manifest.files.keys.filter { it !in current.keys }.forEach { relative ->
                if (deleteRemote(profile, joinUrl(root, relative))) deleted++
            }
        }
        manifest = SyncManifest(files = current, updatedAt = System.currentTimeMillis())
        putRemoteText(profile, rootManifestUrl, gson.toJson(manifest))
        return SyncResult(uploaded, downloaded, skipped, deleted, conflicts, errors)
    }

    private fun prepareSource(profile: LibrarySyncProfile, entry: LibraryEntry): File {
        val source = File(entry.path)
        val targetFormat = profile.outputFormat
        if (targetFormat == null || targetFormat == musicunlock.settings.OutputFormat.ORIGINAL || source.extension.equals(targetFormat.extension, true)) {
            return source
        }
        val temp = Files.createTempFile("musicunlock-sync-", ".${targetFormat.extension}").toFile()
        val error = AudioTranscoder.transcode(source, temp, targetFormat.extension!!.let {
            musicunlock.service.TranscodeFormat.valueOf(it.uppercase())
        }, profile.bitrateKbps)
        if (error != null) {
            temp.delete()
            error(error)
        }
        return temp
    }

    private fun relativePath(profile: LibrarySyncProfile, entry: LibraryEntry): String {
        val song = musicunlock.online.MusicSong(
            id = entry.sourceSongId ?: entry.path,
            name = entry.title ?: File(entry.path).nameWithoutExtension,
            artists = entry.artist?.split('/', '、', ',', '；', ';')?.map(String::trim)?.filter(String::isNotBlank).orEmpty(),
            albumName = entry.album,
            coverUrl = null,
            durationSeconds = entry.durationSeconds,
            trackNumber = entry.trackNumber,
            discNumber = entry.discNumber,
            year = entry.year,
            genre = entry.genre,
        )
        val extension = profile.outputFormat?.extension ?: File(entry.path).extension
        val rendered = OutputTemplate.render(profile.outputTemplate, song, entry.platform ?: "本地")
        return "$rendered.$extension".replace('\\', '/')
    }

    private fun fileState(relative: String, file: File, etag: String?): SyncFileState = SyncFileState(
        relativePath = relative,
        size = file.length(),
        modifiedAt = file.lastModified(),
        hash = sha256(file),
        etag = etag,
    )

    private fun copyAtomically(source: File, target: File) {
        target.parentFile?.mkdirs()
        val temp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try {
            Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    private fun uniqueConflictFile(target: File): File {
        var index = 2
        while (true) {
            val candidate = File(target.parentFile, "${target.nameWithoutExtension} (冲突 $index).${target.extension}")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun readLocalManifest(file: File): SyncManifest = runCatching {
        if (file.isFile) gson.fromJson(file.readText(), SyncManifest::class.java) else SyncManifest()
    }.getOrDefault(SyncManifest())

    private fun writeLocalManifest(file: File, manifest: SyncManifest) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(manifest))
    }

    private fun readRemoteManifest(profile: LibrarySyncProfile, url: String): SyncManifest =
        getRemote(profile, url)?.toString(Charsets.UTF_8)?.let { text ->
            runCatching { gson.fromJson(text, SyncManifest::class.java) }.getOrNull()
        } ?: SyncManifest()

    private fun head(profile: LibrarySyncProfile, url: String): RemoteHead? = runCatching {
        val response = send(profile, request(profile, URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build())
        if (response.statusCode() !in 200..299) return@runCatching null
        RemoteHead(
            size = response.headers().firstValueAsLong("Content-Length").orElse(0L),
            etag = response.headers().firstValue("ETag").orElse(null),
        )
    }.getOrNull()

    private fun putRemote(profile: LibrarySyncProfile, url: String, file: File) {
        ensureDirectories(profile, URI.create(url))
        val response = send(profile, request(profile, URI.create(url)).PUT(HttpRequest.BodyPublishers.ofFile(file.toPath())).build())
        check(response.statusCode() in 200..299) { "WebDAV 上传失败（HTTP ${response.statusCode()}）" }
    }

    private fun putRemoteText(profile: LibrarySyncProfile, url: String, text: String) {
        ensureDirectories(profile, URI.create(url))
        val response = send(profile, request(profile, URI.create(url)).PUT(HttpRequest.BodyPublishers.ofString(text)).build())
        check(response.statusCode() in 200..299) { "WebDAV 清单上传失败（HTTP ${response.statusCode()}）" }
    }

    private fun getRemote(profile: LibrarySyncProfile, url: String): ByteArray? = runCatching {
        val response = send(profile, request(profile, URI.create(url)).GET().build())
        if (response.statusCode() in 200..299) response.body() else null
    }.getOrNull()

    private fun deleteRemote(profile: LibrarySyncProfile, url: String): Boolean = runCatching {
        val response = send(profile, request(profile, URI.create(url)).DELETE().build())
        response.statusCode() in 200..299 || response.statusCode() == 404
    }.getOrDefault(false)

    private fun ensureDirectories(profile: LibrarySyncProfile, uri: URI) {
        val segments = uri.path.trim('/').split('/').filter(String::isNotBlank).dropLast(1)
        var currentPath = StringBuilder()
        segments.forEach { segment ->
            currentPath.append('/').append(segment)
            val directory = URI(uri.scheme, uri.authority, currentPath.toString(), null, null)
            runCatching {
                val response = send(profile, request(profile, directory).method("MKCOL", HttpRequest.BodyPublishers.noBody()).build())
                response.statusCode()
            }
        }
    }

    private fun request(profile: LibrarySyncProfile, uri: URI): HttpRequest.Builder {
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30)).header("User-Agent", "MusicUnlock")
        val username = profile.username?.takeIf(String::isNotBlank)
        if (username != null) {
            val password = credentials.get(WEBDAV_PASSWORD).orEmpty()
            val token = Base64.getEncoder().encodeToString("$username:$password".toByteArray(StandardCharsets.UTF_8))
            builder.header("Authorization", "Basic $token")
        }
        return builder
    }

    private fun send(profile: LibrarySyncProfile, request: HttpRequest): HttpResponse<ByteArray> =
        client.send(request, HttpResponse.BodyHandlers.ofByteArray())

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun joinUrl(base: String, child: String): String {
        val normalizedBase = base.trimEnd('/')
        val normalizedChild = child.trimStart('/').replace('\\', '/')
        return "$normalizedBase/$normalizedChild"
    }

    private data class RemoteHead(val size: Long, val etag: String?)

    private companion object {
        const val MANIFEST_FILE = ".musicunlock-sync.json"
        const val WEBDAV_PASSWORD = "webdav-password"
    }
}
