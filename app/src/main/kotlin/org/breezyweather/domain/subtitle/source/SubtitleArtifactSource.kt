/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.breezyweather.domain.subtitle.model.SubtitleManifest
import java.io.IOException

/**
 * Result abstraction for fetching remote subtitle artifacts.
 */
sealed interface SubtitleSourceResult<out T> {
    data class Success<T>(val data: T) : SubtitleSourceResult<T>
    data class NotFound(val message: String) : SubtitleSourceResult<Nothing>
    data class NetworkError(val message: String, val cause: Throwable? = null) : SubtitleSourceResult<Nothing>
    data class MalformedData(val message: String, val cause: Throwable? = null) : SubtitleSourceResult<Nothing>
}

/**
 * Remote source for retrieving subtitle manifests and VTT assets.
 */
interface SubtitleArtifactSource {
    suspend fun fetchManifest(program: String, episodeDate: String): SubtitleSourceResult<SubtitleManifest>
    suspend fun fetchVtt(program: String, episodeDate: String, vttFileOrUrl: String): SubtitleSourceResult<String>
}

/**
 * Observable statistics tracking remote network requests for subtitle artifacts.
 */
object SubtitleNetworkStats {
    val manifestRequestCount = java.util.concurrent.atomic.AtomicInteger(0)
    val vttRequestCount = java.util.concurrent.atomic.AtomicInteger(0)

    fun reset() {
        manifestRequestCount.set(0)
        vttRequestCount.set(0)
    }
}

/**
 * HTTP implementation of [SubtitleArtifactSource] using an injectable baseUrl.
 * Strictly avoids hardcoding production server domains.
 */
class HttpSubtitleArtifactSource(
    val baseUrl: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SubtitleArtifactSource {

    override suspend fun fetchManifest(program: String, episodeDate: String): SubtitleSourceResult<SubtitleManifest> =
        withContext(Dispatchers.IO) {
            val url = buildManifestUrl(program, episodeDate)
            val request = Request.Builder().url(url).build()
            SubtitleNetworkStats.manifestRequestCount.incrementAndGet()

            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 404) {
                        return@withContext SubtitleSourceResult.NotFound("Manifest not found (404) at $url")
                    }
                    if (!response.isSuccessful) {
                        return@withContext SubtitleSourceResult.NetworkError("HTTP error ${response.code} when fetching manifest at $url")
                    }

                    val body = response.body.string()

                    try {
                        val manifest = json.decodeFromString<SubtitleManifest>(body)
                        SubtitleSourceResult.Success(manifest)
                    } catch (e: Exception) {
                        SubtitleSourceResult.MalformedData("Failed to parse manifest JSON: ${e.message}", e)
                    }
                }
            } catch (e: IOException) {
                SubtitleSourceResult.NetworkError("Network I/O failure fetching manifest: ${e.message}", e)
            } catch (e: Exception) {
                SubtitleSourceResult.NetworkError("Unexpected failure fetching manifest: ${e.message}", e)
            }
        }

    override suspend fun fetchVtt(
        program: String,
        episodeDate: String,
        vttFileOrUrl: String
    ): SubtitleSourceResult<String> = withContext(Dispatchers.IO) {
        val url = buildVttUrl(program, episodeDate, vttFileOrUrl)
        val request = Request.Builder().url(url).build()
        SubtitleNetworkStats.vttRequestCount.incrementAndGet()

        try {
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@withContext SubtitleSourceResult.NotFound("VTT file not found (404) at $url")
                }
                if (!response.isSuccessful) {
                    return@withContext SubtitleSourceResult.NetworkError("HTTP error ${response.code} when fetching VTT at $url")
                }

                val body = response.body.string()

                SubtitleSourceResult.Success(body)
            }
        } catch (e: IOException) {
            SubtitleSourceResult.NetworkError("Network I/O failure fetching VTT: ${e.message}", e)
        } catch (e: Exception) {
            SubtitleSourceResult.NetworkError("Unexpected failure fetching VTT: ${e.message}", e)
        }
    }

    private fun buildManifestUrl(program: String, episodeDate: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        val programSlug = program.lowercase().replace('_', '-')
        return "$cleanBase/subtitles/$programSlug/$episodeDate/manifest.json"
    }

    private fun buildVttUrl(program: String, episodeDate: String, vttFileOrUrl: String): String {
        if (vttFileOrUrl.startsWith("http://") || vttFileOrUrl.startsWith("https://")) {
            return vttFileOrUrl
        }
        val cleanBase = baseUrl.trimEnd('/')
        val programSlug = program.lowercase().replace('_', '-')
        return "$cleanBase/subtitles/$programSlug/$episodeDate/$vttFileOrUrl"
    }
}
