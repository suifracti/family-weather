package org.breezyweather.domain.subtitle.local

/** An explicit backend identity; failed SenseVoice never implicitly selects Vosk. */
enum class AsrBackend(val id: String, val model: String, val sha256: String, val pipeline: String) {
    VOSK("vosk", LocalEpisodeArtifacts.MODEL, LocalEpisodeArtifacts.MODEL_SHA256, AndroidVideoTranscriber.PIPELINE_VERSION),
    SENSEVOICE("sensevoice", "sense-voice-int8-2024-07-17",
        "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        "android-pcm16-intmono-sv-native-lowpass-window40-v2");

    companion object {
        fun fromId(id: String) = entries.firstOrNull { it.id == id } ?: error("Unknown ASR backend: $id")
        fun fromModel(model: String) = entries.firstOrNull { it.model == model } ?: error("Unknown ASR model: $model")
    }
}
