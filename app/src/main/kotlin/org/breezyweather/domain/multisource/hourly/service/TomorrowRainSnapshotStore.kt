/*
 * This file is part of Breezy Weather.
 */

package org.breezyweather.domain.multisource.hourly.service

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchState
import org.breezyweather.domain.multisource.hourly.presentation.SourceFetchStatus
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainPresentation
import org.breezyweather.domain.multisource.hourly.presentation.TomorrowRainSnapshot
import org.breezyweather.domain.multisource.location.LocationRole
import org.breezyweather.domain.multisource.location.TargetLocation
import org.breezyweather.domain.multisource.model.EvidenceAvailability
import org.breezyweather.domain.multisource.model.ForecastEvidence
import org.breezyweather.domain.multisource.model.ForecastHorizonType
import org.breezyweather.domain.multisource.model.PrecipitationPhase
import org.breezyweather.domain.multisource.model.PrecipitationProbabilityValue
import org.breezyweather.domain.multisource.model.PrecipitationValue
import org.breezyweather.domain.multisource.model.SourceIdentity
import org.breezyweather.domain.multisource.model.UnderlyingModel
import org.breezyweather.domain.multisource.model.WeatherCondition
import org.breezyweather.domain.multisource.model.WeatherProvider

/** Minimal persistent hand-off for the orchard forecast. */
internal class TomorrowRainSnapshotStore(
    private val storage: SnapshotCacheStorage,
) {
    fun save(snapshot: TomorrowRainSnapshot) {
        if (snapshot.evidences.isEmpty() && snapshot.temperatureEvidences.isEmpty()) return
        val newRecord = snapshot.toRecord()
        val retained = readEnvelope().records
            .filterNot {
                it.canonicalLocationId == newRecord.canonicalLocationId &&
                    it.targetDateLocal == newRecord.targetDateLocal
            }
            .plus(newRecord)
            .sortedByDescending { it.fetchedAtEpochMs }
            .distinctBy { it.targetDateLocal }
            .take(MAX_TARGET_DATES)
        storage.write(Json.encodeToString(CacheEnvelope(records = retained)))
    }

    fun load(
        expectedLocation: TargetLocation,
        expectedTargetDate: String,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): TomorrowRainSnapshot? {
        if (expectedLocation.role != LocationRole.AGRICULTURAL_PRIMARY) return null
        val record = readEnvelope().records.firstOrNull {
            it.version == CACHE_VERSION &&
                it.canonicalLocationId == expectedLocation.canonicalLocationId &&
                it.targetDateLocal == expectedTargetDate &&
                it.timeZoneId == expectedLocation.timeZoneId
        } ?: return null
        if (record.fetchedAtEpochMs <= 0L) return null

        return runCatching {
            val evidences = record.evidences.mapNotNull(PersistedEvidence::toEvidence)
            if (evidences.isEmpty()) return null
            TomorrowRainPresentation.buildSnapshot(
                targetLocation = expectedLocation,
                targetDateLocal = expectedTargetDate,
                rawEvidences = evidences,
                sourceStatuses = record.sourceStatuses.mapNotNull(PersistedSourceStatus::toStatus),
                fetchedAtEpochMs = record.fetchedAtEpochMs,
                recoveredFromStorage = true,
                nowEpochMs = nowEpochMs,
            ).takeIf { it.evidences.isNotEmpty() || it.temperatureEvidences.isNotEmpty() }
        }.getOrNull()
    }

    private fun readEnvelope(): CacheEnvelope {
        val raw = storage.read()?.takeIf(String::isNotBlank) ?: return CacheEnvelope()
        return runCatching { Json.decodeFromString<CacheEnvelope>(raw) }.getOrDefault(CacheEnvelope())
    }

    companion object {
        // v3 separates fetch/publication and temperature instants from rain intervals.
        internal const val CACHE_VERSION = 3
        private const val MAX_TARGET_DATES = 2
        private const val PREF_NAME = "tomorrow_rain_snapshot_v1"
        private const val PREF_KEY = "snapshots"

        fun from(context: Context): TomorrowRainSnapshotStore {
            val preferences = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            return TomorrowRainSnapshotStore(
                object : SnapshotCacheStorage {
                    override fun read(): String? = preferences.getString(PREF_KEY, null)
                    override fun write(value: String) {
                        preferences.edit().putString(PREF_KEY, value).apply()
                    }
                }
            )
        }
    }
}

internal interface SnapshotCacheStorage {
    fun read(): String?
    fun write(value: String)
}

@Serializable
private data class CacheEnvelope(
    val records: List<PersistedSnapshot> = emptyList(),
)

@Serializable
private data class PersistedSnapshot(
    val version: Int,
    val canonicalLocationId: String,
    val targetDateLocal: String,
    val timeZoneId: String,
    val fetchedAtEpochMs: Long,
    val evidences: List<PersistedEvidence>,
    val sourceStatuses: List<PersistedSourceStatus>,
)

@Serializable
private data class PersistedSourceStatus(
    val sourceId: String,
    val displayName: String,
    val state: String,
    val evidenceCount: Int,
) {
    fun toStatus(): SourceFetchStatus? {
        val parsedState = SourceFetchState.entries.firstOrNull { it.name == state } ?: return null
        return SourceFetchStatus(sourceId, displayName, parsedState, evidenceCount.coerceAtLeast(0))
    }
}

@Serializable
private data class PersistedEvidence(
    val canonicalLocationId: String,
    val provider: String,
    val model: String,
    val resolvedPhysicalModel: String?,
    val issuedAtEpochMs: Long,
    val validFromEpochMs: Long,
    val validToEpochMs: Long,
    val modelRunInitializationEpochMs: Long?,
    val modelRunId: String?,
    val weatherText: String?,
    val weatherCode: String?,
    val weatherIsRain: Boolean?,
    val weatherPhase: String?,
    val probabilityPercentage: Int?,
    val probabilityAvailability: String,
    val probabilityRawValue: String?,
    val probabilityCanonicalRatio: Double?,
    val amountValueMm: Double?,
    val amountAvailability: String,
    val amountUnit: String,
    val intensityValueMm: Double?,
    val intensityAvailability: String,
    val intensityUnit: String,
    val probabilityEventDefinition: String?,
    val freshnessSeconds: Long,
    val rawSource: String,
    val provenanceDetail: String,
    val horizonType: String,
    val fetchedAtEpochMs: Long? = null,
    val sourceUpdatedAtEpochMs: Long? = null,
    val temperatureCelsius: Double? = null,
    val temperatureAtEpochMs: Long? = null,
    val spatialResolutionDetail: String = "范围未提供",
    val temporalResolutionDetail: String = "时间分辨率未提供",
    val resolvedLatitude: Double? = null,
    val resolvedLongitude: Double? = null,
    val precipitationPeriodKnown: Boolean = true,
) {
    fun toEvidence(): ForecastEvidence? = runCatching {
        val parsedProvider = WeatherProvider.entries.first { it.name == provider }
        val parsedModel = UnderlyingModel.entries.first { it.name == model }
        val parsedResolved = resolvedPhysicalModel?.let { name ->
            UnderlyingModel.entries.firstOrNull { it.name == name }
        }
        val probabilityState = EvidenceAvailability.entries.first { it.name == probabilityAvailability }
        val amountState = EvidenceAvailability.entries.first { it.name == amountAvailability }
        val intensityState = EvidenceAvailability.entries.first { it.name == intensityAvailability }
        ForecastEvidence(
            canonicalLocationId = canonicalLocationId,
            sourceIdentity = SourceIdentity.resolve(parsedProvider, parsedModel, parsedResolved),
            issuedAtEpochMs = issuedAtEpochMs,
            fetchedAtEpochMs = fetchedAtEpochMs,
            sourceUpdatedAtEpochMs = sourceUpdatedAtEpochMs,
            temperatureCelsius = temperatureCelsius?.takeIf { it.isFinite() && it in -100.0..70.0 },
            temperatureAtEpochMs = temperatureAtEpochMs,
            spatialResolutionDetail = spatialResolutionDetail,
            temporalResolutionDetail = temporalResolutionDetail,
            resolvedLatitude = resolvedLatitude,
            resolvedLongitude = resolvedLongitude,
            precipitationPeriodKnown = precipitationPeriodKnown,
            validFromEpochMs = validFromEpochMs,
            validToEpochMs = validToEpochMs,
            modelRunInitializationEpochMs = modelRunInitializationEpochMs,
            modelRunId = modelRunId,
            weatherCondition = weatherText?.let {
                WeatherCondition(
                    text = it,
                    code = weatherCode,
                    isRainCondition = weatherIsRain ?: false,
                    precipitationPhase = weatherPhase?.let { phase ->
                        PrecipitationPhase.entries.firstOrNull { entry -> entry.name == phase }
                    } ?: PrecipitationPhase.UNKNOWN,
                )
            },
            precipitationProbability = PrecipitationProbabilityValue(
                percentage = probabilityPercentage,
                availability = probabilityState,
                rawDiscreteValue = probabilityRawValue,
                canonicalRatio = probabilityCanonicalRatio,
            ),
            precipitationAmount = PrecipitationValue(amountValueMm, amountState, amountUnit),
            precipitationIntensity = PrecipitationValue(intensityValueMm, intensityState, intensityUnit),
            probabilityEventDefinition = probabilityEventDefinition,
            freshnessSeconds = freshnessSeconds,
            rawSource = rawSource,
            provenanceDetail = provenanceDetail,
            horizonType = ForecastHorizonType.entries.first { it.name == horizonType },
        )
    }.getOrNull()
}

private fun TomorrowRainSnapshot.toRecord() = PersistedSnapshot(
    version = TomorrowRainSnapshotStore.CACHE_VERSION,
    canonicalLocationId = targetLocation.canonicalLocationId,
    targetDateLocal = targetDateLocal,
    timeZoneId = timeZoneId,
    fetchedAtEpochMs = fetchedAtEpochMs,
    evidences = (evidences + temperatureEvidences).distinct().map(ForecastEvidence::toPersisted),
    sourceStatuses = sourceStatuses.map {
        PersistedSourceStatus(it.sourceId, it.displayName, it.state.name, it.evidenceCount)
    },
)

private fun ForecastEvidence.toPersisted() = PersistedEvidence(
    canonicalLocationId = canonicalLocationId,
    provider = provider.name,
    model = underlyingModel.name,
    resolvedPhysicalModel = resolvedPhysicalModel?.name,
    issuedAtEpochMs = issuedAtEpochMs,
    fetchedAtEpochMs = fetchedAtEpochMs,
    sourceUpdatedAtEpochMs = sourceUpdatedAtEpochMs,
    temperatureCelsius = temperatureCelsius,
    temperatureAtEpochMs = temperatureAtEpochMs,
    spatialResolutionDetail = spatialResolutionDetail,
    temporalResolutionDetail = temporalResolutionDetail,
    resolvedLatitude = resolvedLatitude,
    resolvedLongitude = resolvedLongitude,
    precipitationPeriodKnown = precipitationPeriodKnown,
    validFromEpochMs = validFromEpochMs,
    validToEpochMs = validToEpochMs,
    modelRunInitializationEpochMs = modelRunInitializationEpochMs,
    modelRunId = modelRunId,
    weatherText = weatherCondition?.text,
    weatherCode = weatherCondition?.code,
    weatherIsRain = weatherCondition?.isRainCondition,
    weatherPhase = weatherCondition?.precipitationPhase?.name,
    probabilityPercentage = precipitationProbability.percentage,
    probabilityAvailability = precipitationProbability.availability.name,
    probabilityRawValue = precipitationProbability.rawDiscreteValue,
    probabilityCanonicalRatio = precipitationProbability.canonicalRatio,
    amountValueMm = precipitationAmount.valueMm,
    amountAvailability = precipitationAmount.availability.name,
    amountUnit = precipitationAmount.unit,
    intensityValueMm = precipitationIntensity.valueMm,
    intensityAvailability = precipitationIntensity.availability.name,
    intensityUnit = precipitationIntensity.unit,
    probabilityEventDefinition = probabilityEventDefinition,
    freshnessSeconds = freshnessSeconds,
    rawSource = rawSource,
    provenanceDetail = provenanceDetail,
    horizonType = horizonType.name,
)
