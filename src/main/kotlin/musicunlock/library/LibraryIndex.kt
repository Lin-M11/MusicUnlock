package musicunlock.library

import com.google.gson.GsonBuilder
import musicunlock.online.MusicSong
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

data class LibraryEntry(
    val path: String,
    val size: Long,
    val modifiedAt: Long,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationSeconds: Int?,
    val platform: String? = null,
    val sourceSongId: String? = null,
    val hasCover: Boolean = false,
    val hasLyrics: Boolean = false,
    val contentHash: String? = null,
    val format: String? = null,
    val bitRateKbps: Int? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val composer: String? = null,
    val isrc: String? = null,
    val lyrics: String? = null,
    val fingerprint: String? = null,
    val spectralCutoffHz: Int? = null,
    val truePeak: Double? = null,
    val dynamicRangeDb: Double? = null,
    val lastPlayedAt: Long? = null,
    val playCount: Int = 0,
    val skipCount: Int = 0,
    val isFavorite: Boolean = false,
    val rating: Int = 0,
)

enum class LibrarySort { TITLE, ARTIST, ALBUM, ADDED, BITRATE }

enum class SmartPlaylistKind {
    RECENTLY_ADDED,
    RECENTLY_PLAYED,
    MOST_PLAYED,
    FAVORITES,
    LOSSLESS,
    NEEDS_ATTENTION,
}

data class LibraryPlaylist(
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

class LibraryScanReport(
    val indexed: Int,
    val removed: Int,
    val invalid: List<String>,
)

class LibraryImportReport(
    val imported: Int,
    val skippedMissing: Int,
    val skippedInvalid: Int,
)

/** SQLite 曲库索引：支持全文搜索、播放历史、标签字段、声学指纹和本地歌单。 */
class LibraryIndex(
    private val indexFile: File = defaultLibraryIndexFile(),
) {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val lock = Any()
    private val databaseFile: File = if (indexFile.extension.lowercase() in setOf("sqlite", "db")) {
        indexFile
    } else {
        File(indexFile.parentFile ?: File("."), "${indexFile.nameWithoutExtension}.sqlite")
    }
    private var ftsAvailable = true

    init {
        Class.forName("org.sqlite.JDBC")
        synchronized(lock) {
            databaseFile.absoluteFile.parentFile?.mkdirs()
            withConnection { connection ->
                configure(connection)
                createSchema(connection)
                migrateLegacyJson(connection)
            }
        }
    }

    fun all(): List<LibraryEntry> = synchronized(lock) {
        withConnection { connection ->
            connection.prepareStatement("SELECT * FROM tracks ORDER BY COALESCE(artist, ''), COALESCE(album, ''), COALESCE(title, '')").use { statement ->
                statement.executeQuery().entries()
            }
        }
    }

    fun get(path: String): LibraryEntry? = synchronized(lock) {
        withConnection { connection ->
            connection.prepareStatement("SELECT * FROM tracks WHERE path = ? LIMIT 1").use { statement ->
                statement.setString(1, path)
                statement.executeQuery().use { result -> if (result.next()) result.entry() else null }
            }
        }
    }

    fun find(song: MusicSong, platform: String? = null): List<LibraryEntry> = synchronized(lock) {
        val exact = withConnection { connection ->
            val sql = if (platform == null) {
                "SELECT * FROM tracks WHERE source_song_id = ?"
            } else {
                "SELECT * FROM tracks WHERE source_song_id = ? AND platform = ?"
            }
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, song.id)
                if (platform != null) statement.setString(2, platform)
                statement.executeQuery().entries()
            }
        }
        if (exact.isNotEmpty()) return@synchronized exact
        val title = normalize(song.name)
        val artists = song.artists.map(::normalize).filter(String::isNotBlank)
        withConnection { connection ->
            connection.prepareStatement("SELECT * FROM tracks WHERE title IS NOT NULL").use { statement ->
                statement.executeQuery().entries().filter { entry ->
                    normalize(entry.title.orEmpty()) == title &&
                        (artists.isEmpty() || artists.any { normalize(entry.artist.orEmpty()).contains(it) }) &&
                        (song.durationSeconds == null || entry.durationSeconds == null ||
                            kotlin.math.abs(song.durationSeconds - entry.durationSeconds) <= 3)
                }
            }
        }
    }

    fun contains(song: MusicSong, platform: String? = null): Boolean = find(song, platform).isNotEmpty()

    fun search(query: String, limit: Int = 1_000, sort: LibrarySort = LibrarySort.TITLE): List<LibraryEntry> = synchronized(lock) {
        val safeLimit = limit.coerceIn(1, 20_000)
        if (query.isBlank()) return@synchronized sorted(sort, safeLimit)
        val ftsQuery = query.trim().split(Regex("\\s+")).joinToString(" ") { "\"${it.replace("\"", "\"\"")}\"*" }
        val fts = runCatching {
            withConnection { connection ->
                connection.prepareStatement(
                    """
                    SELECT t.* FROM tracks_fts f
                    JOIN tracks t ON t.rowid = f.rowid
                    WHERE tracks_fts MATCH ?
                    ORDER BY bm25(tracks_fts)
                    LIMIT ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, ftsQuery)
                    statement.setInt(2, safeLimit)
                    statement.executeQuery().entries()
                }
            }
        }.getOrElse {
            ftsAvailable = false
            emptyList()
        }
        if (fts.isNotEmpty()) return@synchronized fts
        val pattern = "%${query.lowercase()}%"
        withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT * FROM tracks
                WHERE lower(COALESCE(title, '') || ' ' || COALESCE(artist, '') || ' ' || COALESCE(album, '') || ' ' || COALESCE(path, '')) LIKE ?
                ORDER BY COALESCE(artist, ''), COALESCE(album, ''), COALESCE(title, '')
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, pattern)
                statement.setInt(2, safeLimit)
                statement.executeQuery().entries()
            }
        }
    }

    fun smartPlaylist(kind: SmartPlaylistKind, limit: Int = 1_000): List<LibraryEntry> = synchronized(lock) {
        val safe = limit.coerceIn(1, 20_000)
        val sql = when (kind) {
            SmartPlaylistKind.RECENTLY_ADDED -> "SELECT * FROM tracks ORDER BY modified_at DESC LIMIT ?"
            SmartPlaylistKind.RECENTLY_PLAYED -> "SELECT * FROM tracks WHERE last_played_at IS NOT NULL ORDER BY last_played_at DESC LIMIT ?"
            SmartPlaylistKind.MOST_PLAYED -> "SELECT * FROM tracks WHERE play_count > 0 ORDER BY play_count DESC, last_played_at DESC LIMIT ?"
            SmartPlaylistKind.FAVORITES -> "SELECT * FROM tracks WHERE is_favorite = 1 ORDER BY COALESCE(artist, ''), COALESCE(title, '') LIMIT ?"
            SmartPlaylistKind.LOSSLESS -> "SELECT * FROM tracks WHERE lower(COALESCE(format, '')) IN ('flac','wav','ape','alac','aiff') ORDER BY COALESCE(artist, ''), COALESCE(album, ''), track_number LIMIT ?"
            SmartPlaylistKind.NEEDS_ATTENTION -> "SELECT * FROM tracks WHERE title IS NULL OR artist IS NULL OR has_cover = 0 OR has_lyrics = 0 ORDER BY modified_at DESC LIMIT ?"
        }
        withConnection { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setInt(1, safe)
                statement.executeQuery().entries()
            }
        }
    }

    fun upsert(
        file: File,
        platform: String? = null,
        sourceSongId: String? = null,
        hash: Boolean = false,
        analyze: Boolean = false,
    ): LibraryEntry? = synchronized(lock) {
        val entry = readEntry(file, platform, sourceSongId, hash, analyze) ?: return@synchronized null
        withConnection { connection ->
            connection.autoCommit = false
            try {
                put(connection, entry)
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
        entry
    }

    fun updateMetadata(entry: LibraryEntry): Boolean = synchronized(lock) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                UPDATE tracks SET
                    title=?, artist=?, album=?, album_artist=?, track_number=?, disc_number=?,
                    year=?, genre=?, composer=?, isrc=?, lyrics=?, has_lyrics=?
                WHERE path=?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, entry.title)
                statement.setString(2, entry.artist)
                statement.setString(3, entry.album)
                statement.setString(4, entry.albumArtist)
                statement.setObject(5, entry.trackNumber, Types.INTEGER)
                statement.setObject(6, entry.discNumber, Types.INTEGER)
                statement.setObject(7, entry.year, Types.INTEGER)
                statement.setString(8, entry.genre)
                statement.setString(9, entry.composer)
                statement.setString(10, entry.isrc)
                statement.setString(11, entry.lyrics)
                statement.setInt(12, if (entry.hasLyrics || !entry.lyrics.isNullOrBlank()) 1 else 0)
                statement.setString(13, entry.path)
                statement.executeUpdate() > 0
            }
        }
    }

    fun toggleFavorite(path: String, favorite: Boolean? = null): Boolean = synchronized(lock) {
        withConnection { connection ->
            val sql = if (favorite == null) {
                "UPDATE tracks SET is_favorite = CASE is_favorite WHEN 0 THEN 1 ELSE 0 END WHERE path = ?"
            } else {
                "UPDATE tracks SET is_favorite = ? WHERE path = ?"
            }
            connection.prepareStatement(sql).use { statement ->
                if (favorite == null) {
                    statement.setString(1, path)
                } else {
                    statement.setInt(1, if (favorite) 1 else 0)
                    statement.setString(2, path)
                }
                statement.executeUpdate() > 0
            }
        }
    }

    fun setRating(path: String, rating: Int): Boolean = synchronized(lock) {
        withConnection { connection ->
            connection.prepareStatement("UPDATE tracks SET rating = ? WHERE path = ?").use { statement ->
                statement.setInt(1, rating.coerceIn(0, 5))
                statement.setString(2, path)
                statement.executeUpdate() > 0
            }
        }
    }

    fun recordPlay(path: String, completed: Boolean = true) = synchronized(lock) {
        val now = System.currentTimeMillis()
        withConnection { connection ->
            connection.autoCommit = false
            try {
                connection.prepareStatement(
                    if (completed) {
                        "UPDATE tracks SET play_count = play_count + 1, last_played_at = ? WHERE path = ?"
                    } else {
                        "UPDATE tracks SET skip_count = skip_count + 1, last_played_at = ? WHERE path = ?"
                    },
                ).use { statement ->
                    statement.setLong(1, now)
                    statement.setString(2, path)
                    statement.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO play_history(path, played_at, completed) VALUES(?, ?, ?)").use { statement ->
                    statement.setString(1, path)
                    statement.setLong(2, now)
                    statement.setInt(3, if (completed) 1 else 0)
                    statement.executeUpdate()
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun playHistory(limit: Int = 200): List<Pair<String, Long>> = synchronized(lock) {
        withConnection { connection ->
            connection.prepareStatement("SELECT path, played_at FROM play_history ORDER BY played_at DESC LIMIT ?").use { statement ->
                statement.setInt(1, limit.coerceIn(1, 10_000))
                statement.executeQuery().use { result ->
                    buildList { while (result.next()) add(result.getString("path") to result.getLong("played_at")) }
                }
            }
        }
    }

    fun playlists(): List<LibraryPlaylist> = synchronized(lock) {
        withConnection { connection ->
            connection.prepareStatement("SELECT id, name, description, created_at, updated_at FROM playlists ORDER BY updated_at DESC").use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(LibraryPlaylist(
                                id = result.getString("id"),
                                name = result.getString("name"),
                                description = result.getString("description").orEmpty(),
                                createdAt = result.getLong("created_at"),
                                updatedAt = result.getLong("updated_at"),
                            ))
                        }
                    }
                }
            }
        }
    }

    fun createPlaylist(name: String, description: String = ""): LibraryPlaylist = synchronized(lock) {
        val playlist = LibraryPlaylist(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "未命名歌单" },
            description = description.trim(),
        )
        withConnection { connection ->
            connection.prepareStatement("INSERT INTO playlists(id, name, description, created_at, updated_at) VALUES(?, ?, ?, ?, ?)").use { statement ->
                statement.setString(1, playlist.id)
                statement.setString(2, playlist.name)
                statement.setString(3, playlist.description)
                statement.setLong(4, playlist.createdAt)
                statement.setLong(5, playlist.updatedAt)
                statement.executeUpdate()
            }
        }
        playlist
    }

    fun deletePlaylist(id: String) = synchronized(lock) {
        withConnection { connection -> connection.prepareStatement("DELETE FROM playlists WHERE id = ?").use { it.setString(1, id); it.executeUpdate() } }
        Unit
    }

    fun addToPlaylist(playlistId: String, paths: List<String>): Int = synchronized(lock) {
        withConnection { connection ->
            connection.autoCommit = false
            try {
                var added = 0
                paths.distinct().forEach { path ->
                    val position = connection.prepareStatement("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_tracks WHERE playlist_id = ?").use { statement ->
                        statement.setString(1, playlistId)
                        statement.executeQuery().use { if (it.next()) it.getInt(1) else 0 }
                    }
                    connection.prepareStatement("INSERT OR IGNORE INTO playlist_tracks(playlist_id, path, position) VALUES(?, ?, ?)").use { statement ->
                        statement.setString(1, playlistId)
                        statement.setString(2, path)
                        statement.setInt(3, position)
                        added += statement.executeUpdate()
                    }
                }
                connection.prepareStatement("UPDATE playlists SET updated_at = ? WHERE id = ?").use { statement ->
                    statement.setLong(1, System.currentTimeMillis())
                    statement.setString(2, playlistId)
                    statement.executeUpdate()
                }
                connection.commit()
                added
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
    }

    fun playlistTracks(playlistId: String): List<LibraryEntry> = synchronized(lock) {
        withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT t.* FROM playlist_tracks p
                JOIN tracks t ON t.path = p.path
                WHERE p.playlist_id = ?
                ORDER BY p.position
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, playlistId)
                statement.executeQuery().entries()
            }
        }
    }

    fun remove(path: String) = synchronized(lock) {
        withConnection { connection ->
            connection.prepareStatement("DELETE FROM tracks WHERE path = ?").use { statement ->
                statement.setString(1, path)
                statement.executeUpdate()
            }
        }
        Unit
    }

    fun scan(directory: File, hash: Boolean = false, analyze: Boolean = false): LibraryScanReport = synchronized(lock) {
        if (!directory.isDirectory) return@synchronized LibraryScanReport(0, 0, listOf(directory.absolutePath))
        val found = linkedMapOf<String, LibraryEntry>()
        val invalid = mutableListOf<String>()
        val extensions = setOf("mp3", "flac", "ogg", "oga", "m4a", "mp4", "wav", "aac", "opus", "ape", "wma", "aif", "aiff")
        Files.walk(directory.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }.forEach { path ->
                val file = path.toFile()
                if (file.extension.lowercase() !in extensions) return@forEach
                val entry = readEntry(file, null, null, hash, analyze)
                if (entry == null) invalid += file.absolutePath else found[entry.path] = entry
            }
        }
        val existingUnderRoot = withConnection { connection ->
            connection.prepareStatement("SELECT path FROM tracks WHERE path = ? OR path LIKE ?").use { statement ->
                statement.setString(1, directory.absolutePath)
                statement.setString(2, "${directory.absolutePath}${File.separator}%")
                statement.executeQuery().use { result -> buildList { while (result.next()) add(result.getString(1)) } }
            }
        }
        val removedPaths = existingUnderRoot.filterNot { it in found }
        withConnection { connection ->
            connection.autoCommit = false
            try {
                found.values.forEach { put(connection, it) }
                connection.prepareStatement("DELETE FROM tracks WHERE path = ?").use { statement ->
                    removedPaths.forEach { path -> statement.setString(1, path); statement.addBatch() }
                    statement.executeBatch()
                }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
        LibraryScanReport(found.size, removedPaths.size, invalid)
    }

    fun exportJson(): String = gson.toJson(all())

    fun importFrom(source: File, merge: Boolean = true, onlyExisting: Boolean = true): LibraryImportReport {
        if (!source.isFile) throw IllegalArgumentException("曲库索引不存在：${source.absolutePath}")
        return importJson(source.readText(), merge, onlyExisting)
    }

    fun importJson(json: String, merge: Boolean = true, onlyExisting: Boolean = true): LibraryImportReport = synchronized(lock) {
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, LibraryEntry::class.java).type
        val parsed = gson.fromJson<List<LibraryEntry>>(json, type).orEmpty()
        val valid = parsed.filter { it.path.isNotBlank() }
        val existing = valid.filter { !onlyExisting || File(it.path).isFile }
        val skippedMissing = valid.size - existing.size
        if (!merge) {
            withConnection { connection -> connection.createStatement().use { it.executeUpdate("DELETE FROM tracks") } }
        }
        var imported = 0
        withConnection { connection ->
            connection.autoCommit = false
            try {
                existing.forEach { entry -> put(connection, entry); imported++ }
                connection.commit()
            } catch (error: Exception) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }
        LibraryImportReport(imported, skippedMissing, 0)
    }

    fun relink(oldRoot: String, newRoot: String, dryRun: Boolean = false): Int = synchronized(lock) {
        val oldPath = File(oldRoot).absoluteFile.toPath().normalize()
        val newPath = File(newRoot).absoluteFile.toPath().normalize()
        val updates = all().mapNotNull { entry ->
            val current = File(entry.path).absoluteFile.toPath().normalize()
            if (!current.startsWith(oldPath)) null
            else entry.path to newPath.resolve(oldPath.relativize(current)).normalize().toString()
        }
        if (!dryRun && updates.isNotEmpty()) {
            withConnection { connection ->
                connection.autoCommit = false
                try {
                    connection.createStatement().use { it.execute("PRAGMA defer_foreign_keys = ON") }
                    connection.prepareStatement("UPDATE tracks SET path = ? WHERE path = ?").use { statement ->
                        updates.forEach { (old, new) ->
                            statement.setString(1, new)
                            statement.setString(2, old)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.prepareStatement("UPDATE playlist_tracks SET path = ? WHERE path = ?").use { statement ->
                        updates.forEach { (old, new) ->
                            statement.setString(1, new)
                            statement.setString(2, old)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.prepareStatement("UPDATE play_history SET path = ? WHERE path = ?").use { statement ->
                        updates.forEach { (old, new) ->
                            statement.setString(1, new)
                            statement.setString(2, old)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                    connection.commit()
                } catch (error: Exception) {
                    connection.rollback()
                    throw error
                } finally {
                    connection.autoCommit = true
                }
            }
        }
        updates.size
    }

    fun removeMissing(): Int = synchronized(lock) {
        val missing = all().filterNot { File(it.path).isFile }.map { it.path }
        if (missing.isNotEmpty()) {
            withConnection { connection ->
                connection.prepareStatement("DELETE FROM tracks WHERE path = ?").use { statement ->
                    missing.forEach { path -> statement.setString(1, path); statement.addBatch() }
                    statement.executeBatch()
                }
            }
        }
        missing.size
    }

    fun duplicateGroups(): List<List<LibraryEntry>> = synchronized(lock) {
        val remaining = all().toMutableList()
        val groups = mutableListOf<List<LibraryEntry>>()
        val byHash = remaining.filter { !it.contentHash.isNullOrBlank() }.groupBy { it.contentHash!! }
        byHash.values.filter { it.size > 1 }.forEach { groups += it }
        val consumed = groups.flatten().mapTo(hashSetOf()) { it.path }
        remaining.removeIf { it.path in consumed }
        val byFingerprint = mutableListOf<List<LibraryEntry>>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            if (seed.fingerprint.isNullOrBlank()) continue
            val group = mutableListOf(seed)
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (candidate.fingerprint.isNullOrBlank()) continue
                val durationCompatible = seed.durationSeconds == null || candidate.durationSeconds == null ||
                    kotlin.math.abs(seed.durationSeconds - candidate.durationSeconds) <= 8
                if (durationCompatible && AudioAnalysisService.similarity(seed.fingerprint, candidate.fingerprint) >= 0.88) {
                    group += candidate
                    iterator.remove()
                }
            }
            if (group.size > 1) byFingerprint += group
        }
        groups += byFingerprint
        groups
    }

    private fun sorted(sort: LibrarySort, limit: Int): List<LibraryEntry> {
        val order = when (sort) {
            LibrarySort.TITLE -> "COALESCE(title, ''), COALESCE(artist, '')"
            LibrarySort.ARTIST -> "COALESCE(artist, ''), COALESCE(album, ''), COALESCE(title, '')"
            LibrarySort.ALBUM -> "COALESCE(album, ''), COALESCE(disc_number, 0), COALESCE(track_number, 0), COALESCE(title, '')"
            LibrarySort.ADDED -> "modified_at DESC"
            LibrarySort.BITRATE -> "COALESCE(bit_rate_kbps, 0) DESC, COALESCE(artist, ''), COALESCE(title, '')"
        }
        return withConnection { connection ->
            connection.prepareStatement("SELECT * FROM tracks ORDER BY $order LIMIT ?").use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().entries()
            }
        }
    }

    private fun readEntry(file: File, platform: String?, songId: String?, hash: Boolean, analyze: Boolean): LibraryEntry? = runCatching {
        val audio = AudioFileIO.read(file)
        val tag = audio.tag
        val old = get(file.absolutePath)
        val custom = runCatching { tag?.getFirst(FieldKey.CUSTOM1) }.getOrNull()
        val customParts = custom?.split(':', limit = 2).orEmpty()
        val custom2 = runCatching { tag?.getFirst(FieldKey.CUSTOM2) }.getOrNull()
        val lyrics = runCatching { tag?.getFirst(FieldKey.LYRICS) }.getOrNull()?.takeIf(String::isNotBlank)
            ?: File(file.parentFile, "${file.nameWithoutExtension}.lrc").takeIf(File::isFile)?.readText()?.takeIf(String::isNotBlank)
        val analysis = if (analyze) AudioAnalysisService.analyze(file) else null
        LibraryEntry(
            path = file.absolutePath,
            size = file.length(),
            modifiedAt = file.lastModified(),
            title = runCatching { tag?.getFirst(FieldKey.TITLE) }.getOrNull()?.takeIf(String::isNotBlank),
            artist = runCatching { tag?.getFirst(FieldKey.ARTIST) }.getOrNull()?.takeIf(String::isNotBlank),
            album = runCatching { tag?.getFirst(FieldKey.ALBUM) }.getOrNull()?.takeIf(String::isNotBlank),
            durationSeconds = runCatching { audio.audioHeader.trackLength }.getOrNull()?.takeIf { it > 0 },
            platform = platform ?: customParts.getOrNull(0)?.takeIf(String::isNotBlank) ?: old?.platform,
            sourceSongId = songId ?: customParts.getOrNull(1)?.takeIf(String::isNotBlank) ?: old?.sourceSongId,
            hasCover = runCatching { tag?.firstArtwork != null }.getOrDefault(false),
            hasLyrics = !lyrics.isNullOrBlank(),
            contentHash = if (hash || analyze) sha256(file) else old?.contentHash,
            format = runCatching { audio.audioHeader.format }.getOrNull()?.takeIf(String::isNotBlank),
            bitRateKbps = runCatching { audio.audioHeader.bitRate }.getOrNull()
                ?.filter(Char::isDigit)
                ?.toIntOrNull()
                ?.takeIf { it > 0 },
            albumArtist = runCatching { tag?.getFirst(FieldKey.ALBUM_ARTIST) }.getOrNull()?.takeIf(String::isNotBlank),
            trackNumber = runCatching { tag?.getFirst(FieldKey.TRACK) }.getOrNull()?.filter(Char::isDigit)?.toIntOrNull(),
            discNumber = runCatching { tag?.getFirst(FieldKey.DISC_NO) }.getOrNull()?.filter(Char::isDigit)?.toIntOrNull(),
            year = runCatching { tag?.getFirst(FieldKey.YEAR) }.getOrNull()?.filter(Char::isDigit)?.take(4)?.toIntOrNull(),
            genre = runCatching { tag?.getFirst(FieldKey.GENRE) }.getOrNull()?.takeIf(String::isNotBlank),
            composer = runCatching { tag?.getFirst(FieldKey.COMPOSER) }.getOrNull()?.takeIf(String::isNotBlank),
            isrc = runCatching { tag?.getFirst(FieldKey.ISRC) }.getOrNull()?.takeIf(String::isNotBlank),
            lyrics = lyrics,
            fingerprint = analysis?.fingerprint ?: old?.fingerprint,
            spectralCutoffHz = analysis?.spectralCutoffHz ?: old?.spectralCutoffHz,
            truePeak = analysis?.truePeak ?: old?.truePeak,
            dynamicRangeDb = analysis?.dynamicRangeDb ?: old?.dynamicRangeDb,
            lastPlayedAt = old?.lastPlayedAt,
            playCount = old?.playCount ?: 0,
            skipCount = old?.skipCount ?: 0,
            isFavorite = when {
                custom2 == "favorite=1" -> true
                custom2 == "favorite=0" -> false
                else -> old?.isFavorite ?: false
            },
            rating = runCatching { tag?.getFirst(FieldKey.RATING) }.getOrNull()?.filter(Char::isDigit)?.toIntOrNull()
                ?: old?.rating
                ?: 0,
        )
    }.getOrNull()

    private fun put(connection: Connection, entry: LibraryEntry) {
        connection.prepareStatement(
            """
            INSERT INTO tracks(
                path,size,modified_at,title,artist,album,duration_seconds,platform,source_song_id,
                has_cover,has_lyrics,content_hash,format,bit_rate_kbps,album_artist,track_number,disc_number,
                year,genre,composer,isrc,lyrics,fingerprint,spectral_cutoff_hz,true_peak,dynamic_range_db,
                last_played_at,play_count,skip_count,is_favorite,rating
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT(path) DO UPDATE SET
                size=excluded.size, modified_at=excluded.modified_at, title=excluded.title, artist=excluded.artist,
                album=excluded.album, duration_seconds=excluded.duration_seconds, platform=excluded.platform,
                source_song_id=excluded.source_song_id, has_cover=excluded.has_cover, has_lyrics=excluded.has_lyrics,
                content_hash=COALESCE(excluded.content_hash, tracks.content_hash), format=excluded.format,
                bit_rate_kbps=excluded.bit_rate_kbps, album_artist=excluded.album_artist, track_number=excluded.track_number,
                disc_number=excluded.disc_number, year=excluded.year, genre=excluded.genre, composer=excluded.composer,
                isrc=excluded.isrc, lyrics=excluded.lyrics, fingerprint=COALESCE(excluded.fingerprint, tracks.fingerprint),
                spectral_cutoff_hz=COALESCE(excluded.spectral_cutoff_hz, tracks.spectral_cutoff_hz),
                true_peak=COALESCE(excluded.true_peak, tracks.true_peak),
                dynamic_range_db=COALESCE(excluded.dynamic_range_db, tracks.dynamic_range_db),
                last_played_at=COALESCE(excluded.last_played_at, tracks.last_played_at),
                play_count=MAX(tracks.play_count, excluded.play_count),
                skip_count=MAX(tracks.skip_count, excluded.skip_count),
                is_favorite=MAX(tracks.is_favorite, excluded.is_favorite),
                rating=MAX(tracks.rating, excluded.rating)
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            statement.setString(index++, entry.path)
            statement.setLong(index++, entry.size)
            statement.setLong(index++, entry.modifiedAt)
            statement.setString(index++, entry.title)
            statement.setString(index++, entry.artist)
            statement.setString(index++, entry.album)
            statement.setObject(index++, entry.durationSeconds, Types.INTEGER)
            statement.setString(index++, entry.platform)
            statement.setString(index++, entry.sourceSongId)
            statement.setInt(index++, if (entry.hasCover) 1 else 0)
            statement.setInt(index++, if (entry.hasLyrics) 1 else 0)
            statement.setString(index++, entry.contentHash)
            statement.setString(index++, entry.format)
            statement.setObject(index++, entry.bitRateKbps, Types.INTEGER)
            statement.setString(index++, entry.albumArtist)
            statement.setObject(index++, entry.trackNumber, Types.INTEGER)
            statement.setObject(index++, entry.discNumber, Types.INTEGER)
            statement.setObject(index++, entry.year, Types.INTEGER)
            statement.setString(index++, entry.genre)
            statement.setString(index++, entry.composer)
            statement.setString(index++, entry.isrc)
            statement.setString(index++, entry.lyrics)
            statement.setString(index++, entry.fingerprint)
            statement.setObject(index++, entry.spectralCutoffHz, Types.INTEGER)
            statement.setObject(index++, entry.truePeak, Types.REAL)
            statement.setObject(index++, entry.dynamicRangeDb, Types.REAL)
            statement.setObject(index++, entry.lastPlayedAt, Types.BIGINT)
            statement.setInt(index++, entry.playCount)
            statement.setInt(index++, entry.skipCount)
            statement.setInt(index++, if (entry.isFavorite) 1 else 0)
            statement.setInt(index, entry.rating)
            statement.executeUpdate()
        }
    }

    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS tracks(
                    path TEXT PRIMARY KEY,
                    size INTEGER NOT NULL DEFAULT 0,
                    modified_at INTEGER NOT NULL DEFAULT 0,
                    title TEXT, artist TEXT, album TEXT, duration_seconds INTEGER,
                    platform TEXT, source_song_id TEXT,
                    has_cover INTEGER NOT NULL DEFAULT 0, has_lyrics INTEGER NOT NULL DEFAULT 0,
                    content_hash TEXT, format TEXT, bit_rate_kbps INTEGER,
                    album_artist TEXT, track_number INTEGER, disc_number INTEGER, year INTEGER,
                    genre TEXT, composer TEXT, isrc TEXT, lyrics TEXT,
                    fingerprint TEXT, spectral_cutoff_hz INTEGER, true_peak REAL, dynamic_range_db REAL,
                    last_played_at INTEGER, play_count INTEGER NOT NULL DEFAULT 0,
                    skip_count INTEGER NOT NULL DEFAULT 0, is_favorite INTEGER NOT NULL DEFAULT 0,
                    rating INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracks_title ON tracks(title)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracks_artist ON tracks(artist)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracks_album ON tracks(album)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracks_hash ON tracks(content_hash)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracks_fingerprint ON tracks(fingerprint)")
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tracks_played ON tracks(last_played_at)")
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS play_history(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    path TEXT NOT NULL,
                    played_at INTEGER NOT NULL,
                    completed INTEGER NOT NULL DEFAULT 1
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS playlists(
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS playlist_tracks(
                    playlist_id TEXT NOT NULL,
                    path TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    PRIMARY KEY(playlist_id, path),
                    FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                    FOREIGN KEY(path) REFERENCES tracks(path) ON UPDATE CASCADE ON DELETE CASCADE
                )
                """.trimIndent(),
            )
        }
        ftsAvailable = runCatching {
            connection.createStatement().use { statement ->
                statement.executeUpdate("CREATE VIRTUAL TABLE IF NOT EXISTS tracks_fts USING fts5(path UNINDEXED, title, artist, album, album_artist, genre, composer, isrc)")
                statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS tracks_ai AFTER INSERT ON tracks BEGIN INSERT INTO tracks_fts(rowid,path,title,artist,album,album_artist,genre,composer,isrc) VALUES(new.rowid,new.path,new.title,new.artist,new.album,new.album_artist,new.genre,new.composer,new.isrc); END")
                statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS tracks_ad AFTER DELETE ON tracks BEGIN DELETE FROM tracks_fts WHERE rowid=old.rowid; END")
                statement.executeUpdate("CREATE TRIGGER IF NOT EXISTS tracks_au AFTER UPDATE ON tracks BEGIN UPDATE tracks_fts SET path=new.path,title=new.title,artist=new.artist,album=new.album,album_artist=new.album_artist,genre=new.genre,composer=new.composer,isrc=new.isrc WHERE rowid=new.rowid; END")
                if (count(connection, "SELECT COUNT(*) FROM tracks_fts") == 0L && count(connection, "SELECT COUNT(*) FROM tracks") > 0L) {
                    statement.executeUpdate("INSERT INTO tracks_fts(rowid,path,title,artist,album,album_artist,genre,composer,isrc) SELECT rowid,path,title,artist,album,album_artist,genre,composer,isrc FROM tracks")
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun configure(connection: Connection) {
        connection.createStatement().use { statement ->
            runCatching { statement.execute("PRAGMA journal_mode=WAL") }
            runCatching { statement.execute("PRAGMA synchronous=NORMAL") }
            runCatching { statement.execute("PRAGMA busy_timeout=5000") }
            runCatching { statement.execute("PRAGMA foreign_keys=ON") }
        }
    }

    private fun migrateLegacyJson(connection: Connection) {
        if (count(connection, "SELECT COUNT(*) FROM tracks") > 0L) return
        if (!indexFile.isFile || indexFile.extension.lowercase() != "json") return
        val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, LibraryEntry::class.java).type
        val entries = runCatching { gson.fromJson<List<LibraryEntry>>(indexFile.readText(), type).orEmpty() }.getOrDefault(emptyList())
        if (entries.isEmpty()) return
        connection.autoCommit = false
        try {
            entries.filter { it.path.isNotBlank() }.forEach { put(connection, it) }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun count(connection: Connection, sql: String): Long = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result -> if (result.next()) result.getLong(1) else 0L }
    }

    private fun sortedEntries(result: ResultSet): List<LibraryEntry> = result.entries()

    private fun ResultSet.entries(): List<LibraryEntry> = buildList { while (next()) add(entry()) }

    private fun ResultSet.entry(): LibraryEntry = LibraryEntry(
        path = getString("path"),
        size = getLong("size"),
        modifiedAt = getLong("modified_at"),
        title = getString("title"),
        artist = getString("artist"),
        album = getString("album"),
        durationSeconds = getIntOrNull("duration_seconds"),
        platform = getString("platform"),
        sourceSongId = getString("source_song_id"),
        hasCover = getInt("has_cover") != 0,
        hasLyrics = getInt("has_lyrics") != 0,
        contentHash = getString("content_hash"),
        format = getString("format"),
        bitRateKbps = getIntOrNull("bit_rate_kbps"),
        albumArtist = getString("album_artist"),
        trackNumber = getIntOrNull("track_number"),
        discNumber = getIntOrNull("disc_number"),
        year = getIntOrNull("year"),
        genre = getString("genre"),
        composer = getString("composer"),
        isrc = getString("isrc"),
        lyrics = getString("lyrics"),
        fingerprint = getString("fingerprint"),
        spectralCutoffHz = getIntOrNull("spectral_cutoff_hz"),
        truePeak = getDoubleOrNull("true_peak"),
        dynamicRangeDb = getDoubleOrNull("dynamic_range_db"),
        lastPlayedAt = getLongOrNull("last_played_at"),
        playCount = getInt("play_count"),
        skipCount = getInt("skip_count"),
        isFavorite = getInt("is_favorite") != 0,
        rating = getInt("rating"),
    )

    private fun ResultSet.getIntOrNull(name: String): Int? = runCatching { getObject(name)?.let { getInt(name) } }.getOrNull()
    private fun ResultSet.getLongOrNull(name: String): Long? = runCatching { getObject(name)?.let { getLong(name) } }.getOrNull()
    private fun ResultSet.getDoubleOrNull(name: String): Double? = runCatching { getObject(name)?.let { getDouble(name) } }.getOrNull()

    private fun <T> withConnection(block: (Connection) -> T): T = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use(block)

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

    private fun normalize(value: String): String = buildString {
        value.lowercase().forEach { ch -> if (ch.isLetterOrDigit()) append(ch) }
    }
}

fun defaultLibraryIndexFile(): File = File(System.getProperty("user.home"), ".musicunlock/library.json")
