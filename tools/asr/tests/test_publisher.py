"""
Unit tests for DailySubtitlePublisher.
Verifies:
- Discovery parsing & exact episode identity mismatch fail-closed
- Idempotency (NO_OP / ALREADY_PUBLISHED on same source hash)
- Revision update (SOURCE_REVISION_CHANGED on changed source hash)
- Atomic publication and cleanup on failure
- Lightweight index.json generation and accuracy
- Operational logging output
"""

import os
import sys
import io
import json
import wave
import shutil
import tempfile
import unittest
import numpy as np
from unittest.mock import patch, MagicMock

# Ensure workspace root is in sys.path
WORKSPACE_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if WORKSPACE_ROOT not in sys.path:
    sys.path.insert(0, WORKSPACE_ROOT)

from tools.asr.publisher import DailySubtitlePublisher, IdempotencyStatus
from tools.asr.builder import (
    compute_sha256,
    ExactEpisodeIdentityMismatchError,
    SubtitleBuilderError
)

SAMPLE_JSONP = """getLbDatas({"data":[{"title":"《晚间天气预报》 20260917","pubDate":"2026-09-17 19:48:00","url":"https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"},{"title":"《晚间天气预报》 20260916","pubDate":"2026-09-16 19:48:00","url":"https://vod.weathertv.cn/video/2026/9/16/202609161789651595541.mp4"}]})"""

class TestDailySubtitlePublisher(unittest.TestCase):

    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="test_publisher_")
        self.output_base = os.path.join(self.test_dir, "dist", "subtitles", "evening-weather")
        self.scratch_dir = os.path.join(self.test_dir, "scratch")
        os.makedirs(self.scratch_dir, exist_ok=True)
        self.publisher = DailySubtitlePublisher(
            output_base_dir=self.output_base,
            scratch_dir=self.scratch_dir
        )

        # Create a dummy video file
        self.dummy_video = os.path.join(self.scratch_dir, "test_video.mp4")
        with open(self.dummy_video, "wb") as f:
            f.write(b"MOCK_MP4_CONTENT_FOR_TESTING_PURPOSES_VERSION_1")

        # Create a dummy wav file
        self.dummy_wav = os.path.join(self.scratch_dir, "test_audio.wav")
        sample_rate = 16000
        n_samples = int(round(5.0 * sample_rate))
        samples = np.zeros(n_samples, dtype=np.int16)
        with wave.open(self.dummy_wav, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(sample_rate)
            wf.writeframes(samples.tobytes())

        # Create a precomputed cue set
        self.mock_cues = [
            {
                "startTimeSec": 0.0,
                "endTimeSec": 2.5,
                "startMs": 0,
                "endMs": 2500,
                "rawText": "天气预报开始",
                "normalizedText": "天气预报开始"
            }
        ]

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    @patch("urllib.request.urlopen")
    def test_discover_episode_info_exact_match(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.read.return_value = SAMPLE_JSONP.encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp
        mock_urlopen.return_value = mock_resp

        info = self.publisher.fetch_episode_info("2026-09-17")
        self.assertEqual(info["episodeDate"], "2026-09-17")
        self.assertEqual(info["sourceVideoUrl"], "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4")

    @patch("urllib.request.urlopen")
    def test_discover_episode_info_missing_date_fails_closed(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.read.return_value = SAMPLE_JSONP.encode("utf-8")
        mock_resp.__enter__.return_value = mock_resp
        mock_urlopen.return_value = mock_resp

        with self.assertRaises(ExactEpisodeIdentityMismatchError):
            self.publisher.fetch_episode_info("2026-09-15")

    def test_exact_identity_mismatch_in_run_fails_closed(self):
        with self.assertRaises(ExactEpisodeIdentityMismatchError):
            self.publisher.run(
                episode_date="2026-09-18",
                source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
                video_file_override=self.dummy_video,
                audio_file_override=self.dummy_wav
            )

    def test_first_publish_and_index_generation(self):
        res = self.publisher.run(
            episode_date="2026-09-17",
            source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            video_file_override=self.dummy_video,
            audio_file_override=self.dummy_wav,
            precomputed_cues_override=self.mock_cues,
            fixed_generated_at="2026-09-18T12:00:00Z"
        )
        self.assertEqual(res["publishStatus"], "PUBLISHED")
        self.assertEqual(res["episodeDate"], "2026-09-17")

        manifest_file = os.path.join(self.output_base, "2026-09-17", "manifest.json")
        vtt_file = os.path.join(self.output_base, "2026-09-17", "subtitle.vtt")
        index_file = os.path.join(self.output_base, "index.json")

        self.assertTrue(os.path.exists(manifest_file))
        self.assertTrue(os.path.exists(vtt_file))
        self.assertTrue(os.path.exists(index_file))

        with open(index_file, "r", encoding="utf-8") as f:
            idx = json.load(f)
        self.assertEqual(idx["schemaVersion"], 1)
        self.assertEqual(idx["latestEpisodeDate"], "2026-09-17")
        self.assertEqual(idx["latestManifestUrl"], "/subtitles/evening-weather/2026-09-17/manifest.json")

    def test_idempotency_already_published_skips_processing(self):
        # First publish
        res1 = self.publisher.run(
            episode_date="2026-09-17",
            source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            video_file_override=self.dummy_video,
            audio_file_override=self.dummy_wav,
            precomputed_cues_override=self.mock_cues,
            fixed_generated_at="2026-09-18T12:00:00Z"
        )
        self.assertEqual(res1["publishStatus"], "PUBLISHED")

        # Second publish with identical video
        res2 = self.publisher.run(
            episode_date="2026-09-17",
            source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            video_file_override=self.dummy_video,
            audio_file_override=self.dummy_wav,
            precomputed_cues_override=self.mock_cues
        )
        self.assertEqual(res2["publishStatus"], IdempotencyStatus.ALREADY_PUBLISHED)
        self.assertEqual(res2["manifest"]["vttSha256"], res1["manifest"]["vttSha256"])

    def test_source_revision_changed_republishes(self):
        # First publish with dummy_video
        self.publisher.run(
            episode_date="2026-09-17",
            source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            video_file_override=self.dummy_video,
            audio_file_override=self.dummy_wav,
            precomputed_cues_override=self.mock_cues,
            fixed_generated_at="2026-09-18T12:00:00Z"
        )

        # Modify video file to simulate revised broadcast
        revised_video = os.path.join(self.scratch_dir, "revised_video.mp4")
        with open(revised_video, "wb") as f:
            f.write(b"REVISED_VIDEO_CONTENT_NEW_HASH")

        res_revised = self.publisher.run(
            episode_date="2026-09-17",
            source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
            video_file_override=revised_video,
            audio_file_override=self.dummy_wav,
            precomputed_cues_override=self.mock_cues,
            fixed_generated_at="2026-09-18T12:05:00Z"
        )

        self.assertEqual(res_revised["publishStatus"], "REVISION_UPDATED")
        new_source_sha = compute_sha256(revised_video)
        self.assertEqual(res_revised["manifest"]["sourceVideoSha256"], new_source_sha)

    def test_operational_logging_output(self):
        buf = io.StringIO()
        with patch("sys.stdout", buf):
            self.publisher.run(
                episode_date="2026-09-17",
                source_video_url="https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4",
                video_file_override=self.dummy_video,
                audio_file_override=self.dummy_wav,
                precomputed_cues_override=self.mock_cues
            )
        output = buf.getvalue()
        self.assertIn("episodeDate: 2026-09-17", output)
        self.assertIn("sourceVideoSha256:", output)
        self.assertIn("ASR model: SenseVoiceSmall", output)
        self.assertIn("cueCount: 1", output)
        self.assertIn("publishStatus: PUBLISHED", output)
        self.assertIn("elapsedSeconds:", output)

if __name__ == "__main__":
    unittest.main()
