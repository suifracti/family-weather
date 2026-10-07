/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.subtitle.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive

/**
 * Flexible serializer allowing schemaVersion to be parsed from either a JSON string ("1.0") or a JSON number (1).
 */
object FlexibleSchemaVersionSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("FlexibleSchemaVersion", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }

    override fun deserialize(decoder: Decoder): String {
        return when (decoder) {
            is JsonDecoder -> {
                val element = decoder.decodeJsonElement()
                if (element is JsonPrimitive) {
                    element.content
                } else {
                    element.toString()
                }
            }
            else -> decoder.decodeString()
        }
    }
}

/**
 * Manifest representation for ASR-generated video subtitles, adhering to the frozen D2.3b-A schema.
 */
@Serializable
data class SubtitleManifest(
    @Serializable(with = FlexibleSchemaVersionSerializer::class)
    val schemaVersion: String,
    val program: String,
    val episodeDate: String,
    val sourceVideoUrl: String,
    val sourceVideoSha256: String,
    val durationMs: Long,
    val subtitleOrigin: String,
    val extractionMethod: String,
    val asrModel: String,
    val generatedAt: String,
    val vttFile: String,
    val vttSha256: String,
    val cueCount: Int,
    /** Optional episode summary tied to this exact subtitle and media revision. */
    val episodeSummary: EpisodeSummary? = null,
    /** Official source, programme, type and item ID; never inferred from a matching date. */
    val sourceIdentity: String? = null,
    val asrPipelineVersion: String? = null,
    val asrModelSha256: String? = null,
    val asrWords: List<AsrWord> = emptyList(),
    val asrRawResults: List<String> = emptyList(),
    val decodedPcmSha256: String? = null,
    val asrInputRate: Int? = null,
    val asrDecoder: String? = null,
    val asrElapsedMs: Long? = null,
    val asrRuntime: String? = null,
    val asrSelection: String? = null
) {
    fun isSupportedSchemaVersion(): Boolean {
        return schemaVersion == "1" || schemaVersion.startsWith(SUPPORTED_SCHEMA_VERSION_PREFIX)
    }

    companion object {
        const val SUPPORTED_SCHEMA_VERSION_PREFIX = "1."
    }
}
