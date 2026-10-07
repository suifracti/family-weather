"""
Unit and integration tests for Subtitle Artifact Builder.
Verifies:
- Manifest JSON Schema compliance
- Exact Episode Identity enforcement (fail-closed)
- WebVTT validation (monotonicity, boundaries)
- Atomic write and cleanup on failure
- Idempotency (byte-for-byte and semantic consistency)
- End-to-end artifact building
"""

import os
import sys
import json
import shutil
import tempfile
import unittest
import wave
import numpy as np

# Ensure workspace root is in sys.path
WORKSPACE_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if WORKSPACE_ROOT not in sys.path:
    sys.path.insert(0, WORKSPACE_ROOT)

import jsonschema
from tools.asr.builder import (
    SubtitleArtifactBuilder,
    compute_sha256,
    extract_date_code,
    verify_episode_identity,
    validate_webvtt_cues,
    ExactEpisodeIdentityMismatchError,
    WebVttValidationError,
    ManifestSchemaValidationError
)

class SubtitleBuilderTestCase(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="test_subtitle_")
        self.output_base = os.path.join(self.test_dir, "dist", "subtitles", "evening-weather")
        self.scratch_dir = os.path.join(self.test_dir, "scratch")
        os.makedirs(self.scratch_dir, exist_ok=True)
        
        # Load schema
        schema_path = os.path.join(WORKSPACE_ROOT, "tools", "asr", "schema", "subtitle_manifest_schema_v1.json")
        with open(schema_path, "r", encoding="utf-8") as f:
            self.schema = json.load(f)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def create_dummy_wav(self, duration_sec: float = 10.0) -> str:
        """Creates a minimal silent PCM 16kHz mono WAV file for testing."""
        wav_path = os.path.join(self.scratch_dir, f"dummy_{int(duration_sec)}s.wav")
        sample_rate = 16000
        n_samples = int(round(duration_sec * sample_rate))
        samples = np.zeros(n_samples, dtype=np.int16)
        with wave.open(wav_path, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(sample_rate)
            wf.writeframes(samples.tobytes())
        return wav_path

    def test_manifest_schema_compliance(self):
        """Tests that a valid manifest conforms to JSON schema contract v1."""
        valid_manifest = {
            "schemaVersion": 1,
            "program": "EVENING_WEATHER",
            "episodeDate": "2026-09-17",
            "sourceVideoUrl": "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            "sourceVideoSha256": "454C707852EA609C66273C79C799A9DF0287C42FA1BC129809041F020E5C2BAE",
            "durationMs": 260032,
            "subtitleOrigin": "AI_ASR_GENERATED",
            "extractionMethod": "AUTOMATED_ASR_EXTRACTED",
            "asrModel": "SenseVoiceSmall",
            "generatedAt": "2026-09-18T05:46:00Z",
            "vttFile": "subtitle.vtt",
            "vttSha256": "3B87A6F7D890E0F6B925D890E0F6B9253B87A6F7D890E0F6B925D890E0F6B925",
            "cueCount": 70
        }
        # Validate should pass without error
        jsonschema.validate(instance=valid_manifest, schema=self.schema)

        # Invalid schemaVersion fails
        bad_version = valid_manifest.copy()
        bad_version["schemaVersion"] = 2
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(instance=bad_version, schema=self.schema)

        # Invalid date format fails
        bad_date = valid_manifest.copy()
        bad_date["episodeDate"] = "20260917" # missing hyphens
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(instance=bad_date, schema=self.schema)

        # Forbidden subtitleOrigin fails
        bad_origin = valid_manifest.copy()
        bad_origin["subtitleOrigin"] = "OFFICIAL_SUBTITLE"
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(instance=bad_origin, schema=self.schema)

        # Extra undocumented property fails (additionalProperties: false)
        extra_prop = valid_manifest.copy()
        extra_prop["extra_field"] = "not_allowed"
        with self.assertRaises(jsonschema.ValidationError):
            jsonschema.validate(instance=extra_prop, schema=self.schema)

    def test_exact_episode_identity_enforcement(self):
        """Tests that requested date matching resolved date passes, but mismatch fails closed."""
        # 1. Matching date codes pass
        matched, req, url = verify_episode_identity(
            "2026-09-17",
            "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
        )
        self.assertTrue(matched)
        self.assertEqual(req, "20260917")
        self.assertEqual(url, "20260917")

        # 2. Cross-date mismatch fails closed
        matched_mismatch, req_m, url_m = verify_episode_identity(
            "2026-09-16", # requested 16th
            "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4" # URL is 17th
        )
        self.assertFalse(matched_mismatch)
        self.assertEqual(req_m, "20260916")
        self.assertEqual(url_m, "20260917")

        # 3. SubtitleArtifactBuilder raises ExactEpisodeIdentityMismatchError
        builder = SubtitleArtifactBuilder(
            episode_date="2026-09-16",
            source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            output_base_dir=self.output_base
        )
        with self.assertRaises(ExactEpisodeIdentityMismatchError):
            builder.build_artifact()

    def test_webvtt_validation_rules(self):
        """Tests WebVTT cue validation rules."""
        # 1. Valid cues
        valid_cues = [
            {"startTimeSec": 1.0, "endTimeSec": 3.5, "normalizedText": "第一句"},
            {"startTimeSec": 3.5, "endTimeSec": 6.0, "normalizedText": "第二句"},
            {"startTimeSec": 7.0, "endTimeSec": 9.5, "normalizedText": "第三句"}
        ]
        res = validate_webvtt_cues(valid_cues, max_duration_sec=10.0)
        self.assertTrue(res["is_valid"])
        self.assertTrue(res["is_monotonic"])
        self.assertFalse(res["exceeds_duration"])
        self.assertEqual(res["cue_count"], 3)

        # 2. Non-monotonic cues (cue 2 starts before cue 1)
        non_mono = [
            {"startTimeSec": 5.0, "endTimeSec": 8.0, "normalizedText": "句一"},
            {"startTimeSec": 4.0, "endTimeSec": 6.0, "normalizedText": "句二倒流"}
        ]
        res_mono = validate_webvtt_cues(non_mono, max_duration_sec=10.0)
        self.assertFalse(res_mono["is_valid"])
        self.assertFalse(res_mono["is_monotonic"])

        # 3. Exceeds max media duration
        exceeds = [
            {"startTimeSec": 8.0, "endTimeSec": 12.0, "normalizedText": "超时句"} # 12.0 > 10.0
        ]
        res_ex = validate_webvtt_cues(exceeds, max_duration_sec=10.0)
        self.assertFalse(res_ex["is_valid"])
        self.assertTrue(res_ex["exceeds_duration"])

    def test_atomic_write_and_cleanup_on_failure(self):
        """Verifies that an error during artifact building leaves zero corrupt files in target directory."""
        dummy_wav = self.create_dummy_wav(5.0)
        builder = SubtitleArtifactBuilder(
            episode_date="2026-09-17",
            source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            output_base_dir=self.output_base
        )

        # Inject invalid cues that will fail WebVTT validation
        invalid_cues = [
            {"startTimeSec": 4.0, "endTimeSec": 8.0, "rawText": "超时", "normalizedText": "超时"} # 8.0s > 5.0s
        ]

        target_dir = os.path.join(self.output_base, "2026-09-17")
        self.assertFalse(os.path.exists(target_dir))

        with self.assertRaises(WebVttValidationError):
            builder.build_artifact(
                audio_file_override=dummy_wav,
                precomputed_cues_override=invalid_cues
            )

        # Target directory MUST NOT exist
        self.assertFalse(os.path.exists(target_dir))

        # No leftover staging directories in output_base
        if os.path.exists(self.output_base):
            stagings = [d for d in os.listdir(self.output_base) if d.startswith(".staging")]
            self.assertEqual(len(stagings), 0)

    def test_idempotency(self):
        """Verifies that running twice with identical inputs produces identical VTT and manifest."""
        dummy_wav = self.create_dummy_wav(5.0)
        builder = SubtitleArtifactBuilder(
            episode_date="2026-09-17",
            source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            output_base_dir=self.output_base
        )

        test_cues = [
            {"startTimeSec": 1.0, "endTimeSec": 2.5, "rawText": "今天天气晴朗", "normalizedText": "今天天气晴朗"},
            {"startTimeSec": 2.6, "endTimeSec": 4.5, "rawText": "局部有小到中雨", "normalizedText": "局部有小到中雨"}
        ]

        fixed_ts = "2026-09-18T05:46:00Z"

        # Run 1
        res1 = builder.build_artifact(
            audio_file_override=dummy_wav,
            precomputed_cues_override=test_cues,
            fixed_generated_at=fixed_ts
        )

        with open(res1["vttFile"], "r", encoding="utf-8") as f:
            vtt1 = f.read()
        manifest1 = res1["manifest"]

        # Run 2 (overwrite / rebuild)
        res2 = builder.build_artifact(
            audio_file_override=dummy_wav,
            precomputed_cues_override=test_cues,
            fixed_generated_at=fixed_ts
        )

        with open(res2["vttFile"], "r", encoding="utf-8") as f:
            vtt2 = f.read()
        manifest2 = res2["manifest"]

        self.assertEqual(vtt1, vtt2)
        self.assertEqual(manifest1["vttSha256"], manifest2["vttSha256"])
        self.assertEqual(manifest1["cueCount"], manifest2["cueCount"])
        self.assertEqual(manifest1, manifest2)

    def test_end_to_end_artifact_build(self):
        """End-to-end integration test producing verified bundle for 2026-09-17."""
        # Explicit opt-in only: do not inspect a personal tools directory.
        brain_scratch = os.environ.get("FAMILY_WEATHER_ASR_FIXTURE_DIR")
        if not brain_scratch:
            self.skipTest("Set FAMILY_WEATHER_ASR_FIXTURE_DIR to private canonical fixtures")
        canonical_wav = os.path.join(brain_scratch, "20260917_16k_mono.wav")
        canonical_mp4 = os.path.join(brain_scratch, "20260917.mp4")

        self.assertTrue(os.path.exists(canonical_wav), f"Canonical WAV must exist at {canonical_wav}")
        self.assertTrue(os.path.exists(canonical_mp4), f"Canonical MP4 must exist at {canonical_mp4}")

        # Load precomputed cues from D2.3a benchmark_results.json for fast determinism
        benchmark_json = os.path.join(brain_scratch, "benchmark_results.json")
        with open(benchmark_json, "r", encoding="utf-8") as f:
            bm_data = json.load(f)
        sense_voice_cues = bm_data["models"]["SenseVoiceSmall"]["segments"]

        builder = SubtitleArtifactBuilder(
            episode_date="2026-09-17",
            source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            output_base_dir=self.output_base
        )

        result = builder.build_artifact(
            video_file_override=canonical_mp4,
            audio_file_override=canonical_wav,
            precomputed_cues_override=sense_voice_cues,
            fixed_generated_at="2026-09-18T05:46:00Z"
        )

        target_dir = result["targetDir"]
        vtt_file = result["vttFile"]
        manifest_file = result["manifestFile"]
        manifest = result["manifest"]

        self.assertTrue(os.path.exists(target_dir))
        self.assertTrue(os.path.exists(vtt_file))
        self.assertTrue(os.path.exists(manifest_file))

        # Check manifest contract fields
        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertEqual(manifest["program"], "EVENING_WEATHER")
        self.assertEqual(manifest["episodeDate"], "2026-09-17")
        self.assertEqual(manifest["sourceVideoSha256"], "454C707852EA609C66273C79C799A9DF0287C42FA1BC129809041F020E5C2BAE")
        self.assertEqual(manifest["durationMs"], 260032)
        self.assertEqual(manifest["subtitleOrigin"], "AI_ASR_GENERATED")
        self.assertEqual(manifest["extractionMethod"], "AUTOMATED_ASR_EXTRACTED")
        self.assertEqual(manifest["asrModel"], "SenseVoiceSmall")
        self.assertEqual(manifest["cueCount"], 70)

        # Verify VTT SHA256 matches actual file hash
        computed_vtt_sha = compute_sha256(vtt_file)
        self.assertEqual(manifest["vttSha256"], computed_vtt_sha)

        # Validate with JSON Schema
        jsonschema.validate(instance=manifest, schema=self.schema)

if __name__ == "__main__":
    unittest.main()
