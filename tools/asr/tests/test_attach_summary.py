import json
import hashlib
import os
import sys
import tempfile
import unittest

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.asr.attach_summary import attach_summary


class AttachSummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.episode_dir = self.temp_dir.name
        self.vtt = (
            "WEBVTT\n\n"
            "1\n00:00:10.000 --> 00:00:11.000\n第一条字幕\n\n"
            "2\n00:00:20.000 --> 00:00:21.000\n第二条字幕\n\n"
            "3\n00:00:30.000 --> 00:00:31.000\n第三条字幕\n\n"
        )
        vtt_path = os.path.join(self.episode_dir, "subtitle.vtt")
        with open(vtt_path, "w", encoding="utf-8") as output:
            output.write(self.vtt)
        with open(vtt_path, "rb") as source:
            vtt_sha = hashlib.sha256(source.read()).hexdigest().upper()
        self.manifest = {
            "schemaVersion": 1,
            "program": "EVENING_WEATHER",
            "episodeDate": "2026-09-23",
            "sourceVideoUrl": "https://vod.weathertv.cn/video/2026/9/23/202609231234.mp4",
            "sourceVideoSha256": "A" * 64,
            "durationMs": 60000,
            "subtitleOrigin": "AI_ASR_GENERATED",
            "extractionMethod": "AUTOMATED_ASR_EXTRACTED",
            "asrModel": "SenseVoiceSmall",
            "generatedAt": "2026-09-23T00:00:00Z",
            "vttFile": "subtitle.vtt",
            "vttSha256": vtt_sha,
            "cueCount": 3,
        }
        with open(os.path.join(self.episode_dir, "manifest.json"), "w", encoding="utf-8") as output:
            json.dump(self.manifest, output)
        self.items = [
            {"startMs": 10000, "endMs": 11000, "text": "节目内容一。"},
            {"startMs": 20000, "endMs": 21000, "text": "节目内容二。"},
            {"startMs": 30000, "endMs": 31000, "text": "节目内容三。"},
        ]

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_attaches_summary_bound_to_exact_artifact(self):
        attached = attach_summary(self.episode_dir, "2026-09-23", self.items)
        self.assertEqual(attached["episodeDate"], "2026-09-23")
        self.assertEqual(attached["subtitleVttSha256"], self.manifest["vttSha256"])
        self.assertEqual(attached["items"][1]["startMs"], 20000)

    def test_rejects_wrong_episode_and_untraceable_time_range(self):
        with self.assertRaisesRegex(ValueError, "requested date"):
            attach_summary(self.episode_dir, "2026-09-22", self.items)
        invalid_items = [*self.items]
        invalid_items[0] = {"startMs": 12000, "endMs": 13000, "text": "没有对应字幕的总结"}
        with self.assertRaisesRegex(ValueError, "does not overlap"):
            attach_summary(self.episode_dir, "2026-09-23", invalid_items)


if __name__ == "__main__":
    unittest.main()
