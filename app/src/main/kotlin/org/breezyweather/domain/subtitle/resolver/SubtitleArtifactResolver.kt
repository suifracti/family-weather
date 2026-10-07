/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.resolver

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.breezyweather.domain.multisource.model.DisplaySubtitleResult
import org.breezyweather.domain.multisource.model.SubtitleEvidenceEngine
import org.breezyweather.domain.multisource.model.SubtitleExtractionMethod
import org.breezyweather.domain.multisource.model.SubtitleMetadata
import org.breezyweather.domain.multisource.model.SubtitleOrigin
import org.breezyweather.domain.multisource.model.SubtitleStatus
import org.breezyweather.domain.multisource.model.SubtitleTrackEvidence
import org.breezyweather.domain.subtitle.cache.InMemorySubtitleArtifactCache
import org.breezyweather.domain.subtitle.cache.SubtitleArtifactCache
import org.breezyweather.domain.subtitle.cache.SubtitleCacheKey
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import org.breezyweather.domain.subtitle.parser.WebVttParser
import org.breezyweather.domain.subtitle.source.SubtitleArtifactSource
import org.breezyweather.domain.subtitle.source.SubtitleSourceResult
import org.breezyweather.domain.subtitle.util.Sha256Util

/**
 * Result outcomes of resolving subtitle artifacts.
 */
sealed interface SubtitleResolutionResult {

    /**
     * Subtitle was successfully resolved, integrity-verified, and validated.
     */
    data class AVAILABLE(
        val evidence: SubtitleTrackEvidence,
        val manifest: SubtitleManifest? = null,
        val fromCache: Boolean = false
    ) : SubtitleResolutionResult

    /**
     * Subtitle manifest or VTT file was not found (e.g. HTTP 404).
     */
    data class NOT_FOUND(val message: String) : SubtitleResolutionResult

    /**
     * Manifest schema, episode identity, origin, or cue timing contracts were violated.
     */
    data class INVALID_MANIFEST(val reason: String) : SubtitleResolutionResult

    /**
     * VTT SHA-256 hash does not match the manifest checksum.
     */
    data class HASH_MISMATCH(val expectedSha256: String, val actualSha256: String) : SubtitleResolutionResult

    /**
     * Network error occurred during artifact retrieval.
     */
    data class NETWORK_ERROR(val message: String, val cause: Throwable? = null) : SubtitleResolutionResult
}

/**
 * Extension to convert [SubtitleResolutionResult] into [DisplaySubtitleResult].
 * Enforces fail-closed behavior: errors never block video playback and strictly forbids
 * editorial briefs from being substituted for subtitles.
 */
fun SubtitleResolutionResult.toDisplaySubtitle(editorialBriefFallbackCandidate: String? = null): DisplaySubtitleResult {
    return when (this) {
        is SubtitleResolutionResult.AVAILABLE -> {
            SubtitleEvidenceEngine.resolveDisplaySubtitle(evidence, editorialBriefFallbackCandidate)
        }
        is SubtitleResolutionResult.NOT_FOUND -> DisplaySubtitleResult(
            isAvailable = false,
            uiLabel = "字幕暂不可用",
            cues = emptyList(),
            playbackBlocked = false,
            errorReason = "SUBTITLE_NOT_FOUND: $message"
        )
        is SubtitleResolutionResult.INVALID_MANIFEST -> DisplaySubtitleResult(
            isAvailable = false,
            uiLabel = "字幕暂不可用",
            cues = emptyList(),
            playbackBlocked = false,
            errorReason = "INVALID_MANIFEST: $reason"
        )
        is SubtitleResolutionResult.HASH_MISMATCH -> DisplaySubtitleResult(
            isAvailable = false,
            uiLabel = "字幕暂不可用",
            cues = emptyList(),
            playbackBlocked = false,
            errorReason = "HASH_MISMATCH: expected $expectedSha256, actual $actualSha256"
        )
        is SubtitleResolutionResult.NETWORK_ERROR -> DisplaySubtitleResult(
            isAvailable = false,
            uiLabel = "字幕暂不可用",
            cues = emptyList(),
            playbackBlocked = false,
            errorReason = "NETWORK_ERROR: $message"
        )
    }
}

/**
 * Resolver for subtitle artifacts. Coordinates manifest retrieval, integrity verification,
 * cue validation, and isolated caching. Operates strictly fail-closed.
 */
class SubtitleArtifactResolver(
    private val source: SubtitleArtifactSource,
    private val cache: SubtitleArtifactCache = InMemorySubtitleArtifactCache(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    suspend fun resolve(
        program: String,
        episodeDate: String,
        maxDurationMs: Long? = null
    ): SubtitleResolutionResult = withContext(dispatcher) {
        // 1. Fetch manifest from source
        val manifestResult = source.fetchManifest(program, episodeDate)
        val manifest = when (manifestResult) {
            is SubtitleSourceResult.Success -> manifestResult.data
            is SubtitleSourceResult.NotFound -> return@withContext SubtitleResolutionResult.NOT_FOUND(manifestResult.message)
            is SubtitleSourceResult.NetworkError -> return@withContext SubtitleResolutionResult.NETWORK_ERROR(manifestResult.message, manifestResult.cause)
            is SubtitleSourceResult.MalformedData -> return@withContext SubtitleResolutionResult.INVALID_MANIFEST("Malformed manifest JSON: ${manifestResult.message}")
        }

        // 2. Validate exact episode identity
        val normManifestProg = manifest.program.uppercase().replace('-', '_')
        val normRequestedProg = program.uppercase().replace('-', '_')
        if (normManifestProg != normRequestedProg) {
            return@withContext SubtitleResolutionResult.INVALID_MANIFEST(
                "Program identity mismatch: requested '$program' but manifest declared '${manifest.program}'"
            )
        }
        if (manifest.episodeDate != episodeDate) {
            return@withContext SubtitleResolutionResult.INVALID_MANIFEST(
                "Episode date identity mismatch: requested '$episodeDate' but manifest declared '${manifest.episodeDate}'"
            )
        }

        // 3. Validate manifest schemaVersion
        if (!manifest.isSupportedSchemaVersion()) {
            return@withContext SubtitleResolutionResult.INVALID_MANIFEST(
                "Unsupported schemaVersion: '${manifest.schemaVersion}', expected prefix '${SubtitleManifest.SUPPORTED_SCHEMA_VERSION_PREFIX}' or '1'"
            )
        }

        // 4. Validate subtitleOrigin == AI_ASR_GENERATED
        val isAiAsr = manifest.subtitleOrigin.equals(SubtitleOrigin.AI_ASR_GENERATED.id, ignoreCase = true) ||
                manifest.subtitleOrigin.equals(SubtitleOrigin.AI_ASR_GENERATED.name, ignoreCase = true)
        if (!isAiAsr) {
            return@withContext SubtitleResolutionResult.INVALID_MANIFEST(
                "Invalid subtitleOrigin: '${manifest.subtitleOrigin}', strictly expected '${SubtitleOrigin.AI_ASR_GENERATED.name}'"
            )
        }

        // 5. Check cache with strict key isolation (program + episodeDate + vttSha256)
        val cacheKey = SubtitleCacheKey(program, episodeDate, manifest.vttSha256)
        val cached = cache.get(cacheKey)
        if (cached != null && cached.status == SubtitleStatus.AVAILABLE) {
            return@withContext SubtitleResolutionResult.AVAILABLE(
                evidence = cached,
                manifest = manifest,
                fromCache = true
            )
        }

        // 6. Fetch VTT content
        val vttResult = source.fetchVtt(program, episodeDate, manifest.vttFile)
        val vttContent = when (vttResult) {
            is SubtitleSourceResult.Success -> vttResult.data
            is SubtitleSourceResult.NotFound -> return@withContext SubtitleResolutionResult.NOT_FOUND(vttResult.message)
            is SubtitleSourceResult.NetworkError -> return@withContext SubtitleResolutionResult.NETWORK_ERROR(vttResult.message, vttResult.cause)
            is SubtitleSourceResult.MalformedData -> return@withContext SubtitleResolutionResult.INVALID_MANIFEST("Malformed VTT content: ${vttResult.message}")
        }

        // 7. Validate VTT SHA-256
        val calculatedSha256 = Sha256Util.calculateSha256(vttContent)
        if (!calculatedSha256.equals(manifest.vttSha256, ignoreCase = true)) {
            // Fail-closed: Never cache or display corrupted/mismatched VTT
            return@withContext SubtitleResolutionResult.HASH_MISMATCH(
                expectedSha256 = manifest.vttSha256,
                actualSha256 = calculatedSha256
            )
        }

        // 8. Parse VTT syntax and cue validity
        val cues = try {
            WebVttParser.parse(vttContent, model = manifest.asrModel)
        } catch (e: Exception) {
            return@withContext SubtitleResolutionResult.INVALID_MANIFEST("VTT syntax parse failure: ${e.message}")
        }

        if (cues.isEmpty()) {
            return@withContext SubtitleResolutionResult.INVALID_MANIFEST("VTT contained zero valid cues")
        }

        if (manifest.cueCount > 0 && cues.size != manifest.cueCount) {
            return@withContext SubtitleResolutionResult.INVALID_MANIFEST(
                "Cue count mismatch: manifest specified ${manifest.cueCount} cues, parsed ${cues.size}"
            )
        }

        // 9. Duration boundary and monotonicity check
        val effectiveDuration = maxDurationMs ?: manifest.durationMs
        val validation = SubtitleEvidenceEngine.validateCues(cues, effectiveDuration)
        if (!validation.isValid) {
            return@withContext SubtitleResolutionResult.INVALID_MANIFEST(
                "Cue boundary/monotonicity validation failed: ${validation.validationErrors.joinToString("; ")}"
            )
        }

        // 10. Construct evidence, store in cache, return AVAILABLE
        val metadata = SubtitleMetadata(
            origin = SubtitleOrigin.AI_ASR_GENERATED,
            extractionMethod = SubtitleExtractionMethod.AUTOMATED_ASR_EXTRACTED,
            sourceAudioAuthority = "CHINA_WEATHER_OFFICIAL_VIDEO",
            modelName = manifest.asrModel,
            generatedAt = manifest.generatedAt,
            isOfficial = false
        )

        val evidence = SubtitleTrackEvidence(
            episodeDate = episodeDate,
            metadata = metadata,
            status = SubtitleStatus.AVAILABLE,
            cues = cues,
            vttContent = vttContent
        )

        cache.put(cacheKey, evidence)

        SubtitleResolutionResult.AVAILABLE(
            evidence = evidence,
            manifest = manifest,
            fromCache = false
        )
    }
}
