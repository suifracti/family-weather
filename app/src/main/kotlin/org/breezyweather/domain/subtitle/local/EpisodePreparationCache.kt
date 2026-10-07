package org.breezyweather.domain.subtitle.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.breezyweather.domain.subtitle.util.Sha256Util
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URI
import java.time.LocalDate
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.security.MessageDigest

@Serializable
data class EpisodePreparationRequest(
    val program: String,
    val episodeDate: String,
    val videoUrl: String,
    val sourceIdentity: String? = null,
    val asrBackend: String = "sensevoice"
) {
    init {
        AsrBackend.fromId(asrBackend)
        require(program in setOf("EVENING_WEATHER", "CHINA_WEATHER_LIANBO"))
        LocalDate.parse(episodeDate)
        val uri = URI(videoUrl)
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null &&
            (host == "weathertv.cn" || host.endsWith(".weathertv.cn") ||
                host == "weather.com.cn" || host.endsWith(".weather.com.cn") ||
                host.endsWith(".cctv.com") || host.endsWith(".cntv.cn")))
        if (program == "CHINA_WEATHER_LIANBO") {
            require(sourceIdentity?.matches(Regex("weather_com_cn\\|CHINA_WEATHER_LIANBO\\|3M\\|\\d+")) == true)
        }
    }

    val key: String get() = Sha256Util.calculateSha256("$program|$episodeDate|$videoUrl|$sourceIdentity")
}

/** Owns only this feature's dated folders and explicitly registered ASR cache files. */
internal class EpisodePreparationCache(
    private val root: File,
    private val artifactRoot: File,
    private val today: () -> LocalDate = { LocalDate.now() },
    private val legacySubtitleRoot: File = File(artifactRoot.parentFile, "subtitles")
) {
    @Serializable
    data class Record(
        val request: EpisodePreparationRequest,
        val preparedOn: String,
        val artifactName: String,
        val directoryDay: String = preparedOn,
        val mediaLength: Long = 0,
        val mediaSha256: String? = null,
        val etag: String? = null,
        val lastModified: String? = null,
        val artifactNames: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val leases = mutableMapOf<String, Int>()
    private val reservations = mutableMapOf<String, Long>()

    fun currentDay(): String = today().toString()
    fun directory(request: EpisodePreparationRequest, day: String): File {
        LocalDate.parse(day)
        return File(root, "$day-${request.key}")
    }

    @Synchronized
    fun retain(request: EpisodePreparationRequest, day: String): Closeable {
        val name = directory(request, day).name
        leases[name] = (leases[name] ?: 0) + 1
        var released = false
        return Closeable {
            synchronized(this) {
                if (!released) {
                    released = true
                    val count = (leases[name] ?: 1) - 1
                    if (count == 0) leases.remove(name) else leases[name] = count
                }
            }
        }
    }

    @Synchronized fun inUse(request: EpisodePreparationRequest, day: String) =
        (leases[directory(request, day).name] ?: 0) > 0

    @Synchronized
    fun begin(request: EpisodePreparationRequest, day: String, artifactName: String): Record {
        require(artifactName.matches(Regex("[0-9a-f]{64}\\.json")))
        val existing = read(request, day)
        return (existing?.copy(request = request, artifactName = artifactName,
            artifactNames = (existing.artifactNames + existing.artifactName + artifactName).distinct())
            ?: Record(request, day, artifactName, artifactNames = listOf(artifactName))).also(::write)
    }

    @Synchronized fun read(request: EpisodePreparationRequest, day: String = currentDay()): Record? =
        (listOf(directory(request, day)) + root.listFiles().orEmpty().filter { it.isDirectory })
            .firstNotNullOfOrNull { folder -> readRecord(folder)?.takeIf { it.request.key == request.key && it.preparedOn == day } }

    @Synchronized fun completed(request: EpisodePreparationRequest, day: String = currentDay()): Record? =
        read(request, day)?.takeIf { record ->
            val media = File(directory(request, record.directoryDay), "media.mp4")
            record.mediaLength in 1..PlaybackVideoCapture.MAX_BYTES && media.isFile && media.length() == record.mediaLength
        }

    @Synchronized
    fun saveMedia(record: Record, capture: PlaybackVideoCapture): File {
        reserve(record.request, record.directoryDay, capture.file.length())
        val destination = File(directory(record.request, record.directoryDay), "media.mp4")
        capture.persistCompleted(destination)
        write(record.copy(mediaLength = destination.length(), mediaSha256 = mediaHash(destination), etag = capture.etag,
            lastModified = capture.lastModified))
        return destination
    }

    @Synchronized fun verifyRecordedMedia(record: Record): Boolean {
        val file = File(directory(record.request, record.directoryDay), "media.mp4")
        val valid = record.mediaSha256 != null && file.isFile && file.length() == record.mediaLength &&
            mediaHash(file) == record.mediaSha256
        if (!valid) write(record.copy(mediaLength = 0, mediaSha256 = null, etag = null, lastModified = null))
        return valid
    }

    @Synchronized fun renew(record: Record, preparedOn: String): Record =
        record.copy(preparedOn = preparedOn).also(::write)

    /** Bound the combined retained/in-flight feature media, including an actively leased previous day. */
    @Synchronized fun reserve(request: EpisodePreparationRequest, day: String, size: Long) {
        val name = directory(request, day).name
        val folders = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        val names = folders.map { it.name }.toSet() + reservations.keys
        val used = names.filter { it != name }.sumOf { other ->
            maxOf(readRecord(File(root, other))?.mediaLength ?: 0, reservations[other] ?: 0)
        }
        if (size !in 1..PlaybackVideoCapture.MAX_BYTES || used + size > PlaybackVideoCapture.MAX_BYTES) {
            throw IOException("Preparation cache capacity limit exceeded")
        }
        reservations[name] = size
    }

    @Synchronized fun releaseReservation(request: EpisodePreparationRequest, day: String) {
        reservations.remove(directory(request, day).name)
    }

    @Synchronized
    fun cleanupExpired() {
        val current = currentDay()
        val folders = root.listFiles()?.filter { it.isDirectory &&
            it.name.matches(Regex("\\d{4}-\\d{2}-\\d{2}-[0-9a-f]{64}")) }.orEmpty()
        for (folder in folders) {
            val record = readRecord(folder)
            val day = record?.preparedOn ?: folder.name.take(10)
            if (day == current || (leases[folder.name] ?: 0) > 0) continue
            val artifactNames = record?.let { (it.artifactNames + it.artifactName).distinct() }.orEmpty()
            // Another current/leased day may use the same exact episode's subtitle cache.
            val shared = artifactNames.filter { artifactName -> folders.any { other ->
                other != folder && other.exists() && readRecord(other)?.let { otherRecord ->
                    artifactName in (otherRecord.artifactNames + otherRecord.artifactName) &&
                        (otherRecord.preparedOn == current || (leases[other.name] ?: 0) > 0)
                } == true
            } }
            if (deleteOwnedFolder(folder)) for (artifactName in artifactNames - shared.toSet()) {
                if (!artifactName.matches(Regex("[0-9a-f]{64}\\.json"))) continue
                listOf(artifactName, "$artifactName.bak", "$artifactName.new").forEach { name ->
                    val artifact = File(artifactRoot, name)
                    if (artifact.parentFile.canonicalFile == artifactRoot.canonicalFile) {
                        if (name == artifactName) deleteRegisteredSidecars(artifact)
                        artifact.delete()
                    }
                }
            }
        }
        cleanupLegacy(folders.filter { it.exists() }, current)
    }

    private fun readRecord(folder: File): Record? = try {
        json.decodeFromString<Record>(File(folder, "prepared.json").readText())
    } catch (_: Exception) { null }

    private fun mediaHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun write(record: Record) {
        val folder = directory(record.request, record.directoryDay).apply { mkdirs() }
        val target = File(folder, "prepared.json")
        val temporary = File(folder, "prepared.json.new")
        temporary.writeText(json.encodeToString(record))
        if (target.exists()) check(target.delete()) { "Cannot replace preparation metadata" }
        check(temporary.renameTo(target)) { "Cannot save preparation cache metadata" }
    }

    private fun deleteRegisteredSidecars(artifact: File) {
        try {
            val manifest = json.parseToJsonElement(artifact.readText()).jsonObject["manifest"]?.jsonObject ?: return
            val program = manifest["program"]?.jsonPrimitive?.content ?: return
            val date = manifest["episodeDate"]?.jsonPrimitive?.content ?: return
            val hash = manifest["vttSha256"]?.jsonPrimitive?.content ?: return
            if (program !in setOf("EVENING_WEATHER", "CHINA_WEATHER_LIANBO") ||
                !hash.matches(Regex("[0-9a-fA-F]{64}"))) return
            LocalDate.parse(date)
            setOf(program.lowercase().replace('_', '-'), "evening-weather").forEach { slug ->
                val file = File(legacySubtitleRoot, "$slug/$date/$hash.vtt")
                if (file.canonicalPath.startsWith(legacySubtitleRoot.canonicalPath + File.separator)) file.delete()
            }
        } catch (_: Exception) { }
    }

    private fun cleanupLegacy(folders: List<File>, current: String) {
        val protectedArtifacts = folders.flatMap { folder -> readRecord(folder)?.takeIf {
            it.preparedOn == current || (leases[folder.name] ?: 0) > 0
        }?.let { it.artifactNames + it.artifactName }.orEmpty() }.toSet()
        val activeCaptures = PlaybackVideoCapture.activeFiles()
        artifactRoot.listFiles().orEmpty().forEach { file ->
            if (file.name.matches(Regex("playback-\\d+\\.mp4")) && file.canonicalFile !in activeCaptures) {
                file.delete() // Unowned byte captures are interrupted temporary work, never retained programmes.
                return@forEach
            }
            if (!file.name.matches(Regex("[0-9a-f]{64}\\.json")) || file.name in protectedArtifacts) return@forEach
            try {
                val manifest = json.parseToJsonElement(file.readText()).jsonObject["manifest"]?.jsonObject ?: return@forEach
                fun value(name: String) = manifest[name]?.jsonPrimitive?.content
                val program = value("program") ?: return@forEach
                if (program !in setOf("EVENING_WEATHER", "CHINA_WEATHER_LIANBO") ||
                    value("subtitleOrigin") != "AI_ASR_GENERATED" || value("asrModel") !in AsrBackend.entries.map { it.model }
                ) return@forEach
                val generatedAt = value("generatedAt") ?: return@forEach
                val day = try { Instant.parse(generatedAt).atZone(ZoneId.systemDefault()).toLocalDate() }
                catch (_: Exception) {
                    LocalDateTime.parse(generatedAt, DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss")).toLocalDate()
                }
                if (day.toString() == current) return@forEach
                val date = value("episodeDate") ?: return@forEach
                LocalDate.parse(date)
                val hash = value("vttSha256") ?: return@forEach
                if (!hash.matches(Regex("[0-9a-fA-F]{64}"))) return@forEach
                if (file.delete()) {
                    File(artifactRoot, file.name + ".bak").delete()
                    File(artifactRoot, file.name + ".new").delete()
                    setOf(program.lowercase().replace('_', '-'), "evening-weather").forEach { slug ->
                        val vtt = File(legacySubtitleRoot, "$slug/$date/$hash.vtt")
                        if (vtt.canonicalPath.startsWith(legacySubtitleRoot.canonicalPath + File.separator)) {
                            vtt.delete()
                            if (vtt.parentFile.listFiles()?.isEmpty() == true) vtt.parentFile.delete()
                        }
                    }
                }
            } catch (_: Exception) { /* Unknown files/dates are not evidence of an expired local preparation. */ }
        }
    }

    private fun deleteOwnedFolder(folder: File): Boolean {
        val ownedRoot = root.canonicalFile
        if (folder.canonicalFile.parentFile != ownedRoot) return false
        fun delete(file: File): Boolean {
            if (!file.canonicalPath.startsWith(folder.canonicalPath + File.separator) && file != folder) return false
            if (file.isDirectory && file.listFiles()?.all(::delete) == false) return false
            return file.delete() || !file.exists()
        }
        return delete(folder)
    }
}
