/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.cache

import org.breezyweather.domain.multisource.model.SubtitleTrackEvidence
import java.util.concurrent.ConcurrentHashMap

/**
 * Unique cache key for subtitle evidence.
 * Strictly binds program, episodeDate, and VTT SHA-256 hash.
 * This guarantees complete isolation between episodes and prevents stale or mismatched artifacts from being reused.
 */
data class SubtitleCacheKey(
    val program: String,
    val episodeDate: String,
    val vttSha256: String
)

/**
 * Cache abstraction for verified subtitle tracks.
 */
interface SubtitleArtifactCache {
    suspend fun get(key: SubtitleCacheKey): SubtitleTrackEvidence?
    suspend fun put(key: SubtitleCacheKey, evidence: SubtitleTrackEvidence)
    suspend fun clear()
    suspend fun size(): Int
}

/**
 * In-memory thread-safe implementation of [SubtitleArtifactCache].
 */
class InMemorySubtitleArtifactCache : SubtitleArtifactCache {

    private val storage = ConcurrentHashMap<SubtitleCacheKey, SubtitleTrackEvidence>()

    override suspend fun get(key: SubtitleCacheKey): SubtitleTrackEvidence? {
        return storage[key]
    }

    override suspend fun put(key: SubtitleCacheKey, evidence: SubtitleTrackEvidence) {
        storage[key] = evidence
    }

    override suspend fun clear() {
        storage.clear()
    }

    override suspend fun size(): Int {
        return storage.size
    }
}

/**
 * Two-tier cache (L1 in-memory, L2 disk) for subtitle artifacts.
 * Guarantees that verified VTT files are reused across activity lifecycles and process restarts.
 */
class DiskSubtitleArtifactCache(
    private val baseCacheDir: java.io.File
) : SubtitleArtifactCache {

    private val memoryStorage = ConcurrentHashMap<SubtitleCacheKey, SubtitleTrackEvidence>()

    override suspend fun get(key: SubtitleCacheKey): SubtitleTrackEvidence? {
        // 1. Check L1 memory
        memoryStorage[key]?.let { return it }

        // 2. Check L2 disk
        val file = getDiskFile(key)
        if (!file.exists() || file.length() == 0L) {
            return null
        }

        return try {
            val vttContent = file.readText(Charsets.UTF_8)
            val sha256 = org.breezyweather.domain.subtitle.util.Sha256Util.calculateSha256(vttContent)
            if (!sha256.equals(key.vttSha256, ignoreCase = true)) {
                file.delete()
                return null
            }

            val cues = org.breezyweather.domain.subtitle.parser.WebVttParser.parse(vttContent)
            if (cues.isEmpty()) {
                file.delete()
                return null
            }

            val metadata = org.breezyweather.domain.multisource.model.SubtitleMetadata(
                origin = org.breezyweather.domain.multisource.model.SubtitleOrigin.AI_ASR_GENERATED,
                extractionMethod = org.breezyweather.domain.multisource.model.SubtitleExtractionMethod.AUTOMATED_ASR_EXTRACTED,
                sourceAudioAuthority = "CHINA_WEATHER_OFFICIAL_VIDEO",
                modelName = "whisper-large-v3",
                generatedAt = "",
                isOfficial = false
            )
            val evidence = SubtitleTrackEvidence(
                episodeDate = key.episodeDate,
                metadata = metadata,
                status = org.breezyweather.domain.multisource.model.SubtitleStatus.AVAILABLE,
                cues = cues,
                vttContent = vttContent
            )
            memoryStorage[key] = evidence
            evidence
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun put(key: SubtitleCacheKey, evidence: SubtitleTrackEvidence) {
        memoryStorage[key] = evidence
        val content = evidence.vttContent ?: return
        try {
            val file = getDiskFile(key)
            file.parentFile?.mkdirs()
            if (!file.exists() || file.length() == 0L) {
                file.writeText(content, Charsets.UTF_8)
            }
        } catch (_: Exception) {
        }
    }

    override suspend fun clear() {
        memoryStorage.clear()
        try {
            val dir = java.io.File(baseCacheDir, "subtitles")
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        } catch (_: Exception) {
        }
    }

    override suspend fun size(): Int {
        return memoryStorage.size
    }

    fun getDiskFile(key: SubtitleCacheKey): java.io.File {
        val progSlug = key.program.lowercase().replace('_', '-')
        return java.io.File(baseCacheDir, "subtitles/$progSlug/${key.episodeDate}/${key.vttSha256}.vtt")
    }
}

/**
 * Process-scoped provider for [SubtitleArtifactCache].
 */
object DefaultSubtitleArtifactCache {
    private var instance: SubtitleArtifactCache? = null

    @Synchronized
    fun getInstance(cacheDir: java.io.File): SubtitleArtifactCache {
        return instance ?: DiskSubtitleArtifactCache(cacheDir).also { instance = it }
    }

    @Synchronized
    fun resetForTests() {
        instance = null
    }
}
