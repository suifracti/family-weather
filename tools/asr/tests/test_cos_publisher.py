"""Mocked COS/HTTP coverage for the D2.3d1 artifact publishing boundary."""

import hashlib
import io
import json
import os
import shutil
import sys
import tempfile
import types
import unittest
import traceback
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch
from urllib.parse import urlparse

from tools.asr.builder import compute_sha256
from tools.asr.publisher import DailySubtitlePublisher, IdempotencyStatus, main
from tools.asr.publishing import (
    INDEX_KEY,
    IndexUpdateRequiresSingleWriterError,
    IndexWriteOutcomeUnknownError,
    LocalStaticPublisher,
    PublicHttpResponse,
    PublisherConfigurationError,
    RevisionConflictError,
    TencentCosPublisher,
    ArtifactValidationError,
)


DATE = "2026-09-17"
VIDEO_URL = "https://vod.weathertv.cn/video/2026/9/17/202609171789651595542.mp4"
BUCKET = "breezy-subtitles-1250000000"
REGION = "ap-guangzhou"


class FakeCosClient:
    def __init__(self, store, fail_put_counts=None):
        self.store = store
        self.calls = []
        self.fail_put_counts = dict(fail_put_counts or {})

    def put_object(self, **kwargs):
        key = kwargs["Key"]
        self.calls.append(kwargs.copy())
        remaining = self.fail_put_counts.get(key, 0)
        if remaining:
            self.fail_put_counts[key] = remaining - 1
            raise RuntimeError("simulated upload failure")
        if key in self.store and str(kwargs.get("ForbidOverwrite", "")).lower() == "true":
            error = RuntimeError("FileAlreadyExists synthetic conflict")
            error.status_code = 409
            error.error_code = "FileAlreadyExists"
            raise error
        body = kwargs["Body"]
        if hasattr(body, "read"):
            body = body.read()
        self.store[key] = (
            bytes(body),
            {
                "Content-Type": kwargs["ContentType"],
                "Cache-Control": kwargs["CacheControl"],
                "Content-Disposition": kwargs["ContentDisposition"],
            },
        )
        return {"ETag": hashlib.md5(bytes(body)).hexdigest(), "x-cos-request-id": "mock-request"}


class CosPublisherTestCase(unittest.TestCase):
    def setUp(self):
        self.test_dir = Path(tempfile.mkdtemp(prefix="test_cos_publisher_"))
        self.output_root = self.test_dir / "dist" / "subtitles" / "evening-weather"
        self.store = {}
        self.forbidden = False
        self.public_overrides = {}
        self.bad_vtt_readback = False
        self.transient_get_counts = {}
        self.public_urls = []
        self.write_artifact(DATE)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def write_artifact(self, episode_date, vtt_text=None, source_content=None, manifest_date=None):
        vtt = (vtt_text or "WEBVTT\n\n1\n00:00:00.000 --> 00:00:01.000\n天气预报开始\n").encode("utf-8")
        source_bytes = source_content or f"video-{episode_date}".encode("utf-8")
        source_sha = hashlib.sha256(source_bytes).hexdigest().upper()
        manifest = {
            "schemaVersion": 1,
            "program": "EVENING_WEATHER",
            "episodeDate": manifest_date or episode_date,
            "sourceVideoUrl": VIDEO_URL.replace("20260917", episode_date.replace("-", "")),
            "sourceVideoSha256": source_sha,
            "durationMs": 1000,
            "subtitleOrigin": "AI_ASR_GENERATED",
            "extractionMethod": "AUTOMATED_ASR_EXTRACTED",
            "asrModel": "SenseVoiceSmall",
            "generatedAt": "2026-09-18T12:00:00Z",
            "vttFile": "subtitle.vtt",
            "vttSha256": hashlib.sha256(vtt).hexdigest().upper(),
            "cueCount": 1,
        }
        episode_dir = self.output_root / episode_date
        episode_dir.mkdir(parents=True, exist_ok=True)
        (episode_dir / "subtitle.vtt").write_bytes(vtt)
        (episode_dir / "manifest.json").write_bytes(
            json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8")
        )

    def public_get(self, url, timeout):
        self.public_urls.append((url, timeout))
        if self.forbidden:
            return PublicHttpResponse(403, {"Content-Type": "application/xml"}, b"forbidden")
        key = urlparse(url).path.lstrip("/")
        transient_count = self.transient_get_counts.get(key, 0)
        if transient_count:
            self.transient_get_counts[key] = transient_count - 1
            return PublicHttpResponse(503, {"Content-Type": "application/xml"}, b"retry")
        if key in self.public_overrides:
            return self.public_overrides[key]
        if key not in self.store:
            return PublicHttpResponse(404, {}, b"")
        body, headers = self.store[key]
        if self.bad_vtt_readback and key.endswith("/subtitle.vtt"):
            return PublicHttpResponse(200, headers, b"WEBVTT\ncorrupted readback\n")
        return PublicHttpResponse(200, headers, body)

    def make_publisher(self, client=None):
        return TencentCosPublisher(
            client=client or FakeCosClient(self.store),
            bucket=BUCKET,
            region=REGION,
            public_get=self.public_get,
            retry_backoff_seconds=0,
            sleep_fn=lambda _: None,
            state_dir=str(self.test_dir / "publish-state"),
        )

    def test_initial_upload_uses_exact_keys_order_and_response_metadata(self):
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)

        result = publisher.publish(str(self.output_root), DATE)

        self.assertEqual(
            [call["Key"] for call in client.calls],
            [
                f"subtitles/evening-weather/{DATE}/subtitle.vtt",
                f"subtitles/evening-weather/{DATE}/manifest.json",
                INDEX_KEY,
            ],
        )
        self.assertEqual(result["remoteStatus"], "PUBLISHED")
        self.assertEqual(client.calls[0]["ContentType"], "text/vtt; charset=utf-8")
        self.assertEqual(client.calls[1]["ContentType"], "application/json; charset=utf-8")
        self.assertEqual(client.calls[2]["CacheControl"], "no-cache, max-age=60")
        self.assertEqual(client.calls[0]["ContentDisposition"], "inline")
        self.assertEqual(client.calls[0]["ForbidOverwrite"], "true")
        self.assertEqual(client.calls[1]["ForbidOverwrite"], "true")
        self.assertEqual(client.calls[2]["ForbidOverwrite"], "true")
        self.assertIn(
            f"https://{BUCKET}.cos.{REGION}.myqcloud.com/subtitles/evening-weather/",
            result["publicUrls"]["manifest"],
        )
        self.assertNotIn("subtitles/subtitles", result["publicUrls"]["manifest"])
        self.assertEqual(result["responseHeaders"]["vtt"]["Content-Type"], "text/vtt; charset=utf-8")
        self.assertEqual(
            result["artifactHashes"]["vttSha256"],
            hashlib.sha256(self.output_root.joinpath(DATE, "subtitle.vtt").read_bytes()).hexdigest().upper(),
        )

    def test_local_publisher_needs_no_cos_credentials(self):
        result = LocalStaticPublisher().publish(str(self.output_root), DATE)
        self.assertEqual(result["localStatus"], "LOCAL_ARTIFACT_PUBLISHED")
        index = json.loads((self.output_root / "index.json").read_text(encoding="utf-8"))
        self.assertEqual(index["latestEpisodeDate"], DATE)
        self.assertEqual(index["latestManifestUrl"], f"/subtitles/evening-weather/{DATE}/manifest.json")

    def test_local_noop_still_attempts_remote_without_download_or_asr(self):
        scratch = self.test_dir / "scratch"
        scratch.mkdir()
        video_file = scratch / "video.mp4"
        video_file.write_bytes(b"existing video")
        publisher = DailySubtitlePublisher(
            output_base_dir=str(self.output_root),
            scratch_dir=str(scratch),
        )
        audio_file = scratch / "audio.wav"
        import wave

        with wave.open(str(audio_file), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(b"\x00\x00" * 16000)
        cues = [{
            "startTimeSec": 0.0,
            "endTimeSec": 0.5,
            "startMs": 0,
            "endMs": 500,
            "rawText": "天气预报开始",
            "normalizedText": "天气预报开始",
        }]
        # Build the existing local artifact once.  The second call must take
        # the local ALREADY_PUBLISHED branch and still publish to COS.
        publisher.run(
            episode_date=DATE,
            source_video_url=VIDEO_URL,
            video_file_override=str(video_file),
            audio_file_override=str(audio_file),
            precomputed_cues_override=cues,
            fixed_generated_at="2026-09-18T12:00:00Z",
        )
        client = FakeCosClient(self.store)
        remote = self.make_publisher(client)
        with patch.object(publisher, "download_video", side_effect=AssertionError("downloaded")):
            result = publisher.run(
                episode_date=DATE,
                source_video_url=VIDEO_URL,
                video_file_override=str(video_file),
                remote_publisher=remote,
            )
        self.assertEqual(result["localPublishStatus"], IdempotencyStatus.ALREADY_PUBLISHED)
        self.assertEqual(result["remotePublishStatus"], "PUBLISHED")
        self.assertEqual([call["Key"] for call in client.calls][-1], INDEX_KEY)

    def test_failed_vtt_upload_can_be_retried_without_manifest_or_index(self):
        vtt_key = f"subtitles/evening-weather/{DATE}/subtitle.vtt"
        client = FakeCosClient(self.store, {vtt_key: 1})
        publisher = self.make_publisher(client)

        with self.assertRaisesRegex(Exception, "COS_UPLOAD_FAILED"):
            publisher.publish(str(self.output_root), DATE)
        self.assertNotIn(f"subtitles/evening-weather/{DATE}/manifest.json", self.store)
        self.assertNotIn(INDEX_KEY, self.store)

        result = publisher.publish(str(self.output_root), DATE)
        self.assertEqual(result["remoteStatus"], "PUBLISHED")
        self.assertIn(INDEX_KEY, self.store)

    def test_manifest_upload_failure_and_vtt_readback_failure_do_not_submit_index(self):
        manifest_key = f"subtitles/evening-weather/{DATE}/manifest.json"
        client = FakeCosClient(self.store, {manifest_key: 1})
        publisher = self.make_publisher(client)
        with self.assertRaisesRegex(Exception, "COS_UPLOAD_FAILED"):
            publisher.publish(str(self.output_root), DATE)
        self.assertNotIn(INDEX_KEY, self.store)

        self.store.clear()
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)
        self.bad_vtt_readback = True
        with self.assertRaisesRegex(Exception, "PUBLIC_VTT_READBACK_BYTES_MISMATCH"):
            publisher.publish(str(self.output_root), DATE)
        self.assertNotIn(INDEX_KEY, self.store)
        self.assertNotIn(manifest_key, self.store)

    def test_public_get_403_blocks_upload(self):
        self.forbidden = True
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)
        with self.assertRaisesRegex(Exception, "PUBLIC_GET_FORBIDDEN"):
            publisher.publish(str(self.output_root), DATE)
        self.assertEqual(client.calls, [])

    def test_public_get_retries_transient_status_with_bounded_attempts(self):
        manifest_key = f"subtitles/evening-weather/{DATE}/manifest.json"
        self.transient_get_counts[manifest_key] = 2
        client = FakeCosClient(self.store)
        result = self.make_publisher(client).publish(str(self.output_root), DATE)
        self.assertEqual(result["remoteStatus"], "PUBLISHED")
        manifest_gets = [url for url, _ in self.public_urls if url.endswith(manifest_key)]
        self.assertGreaterEqual(len(manifest_gets), 3)

    def test_public_response_content_type_is_checked_before_upload(self):
        manifest_key = f"subtitles/evening-weather/{DATE}/manifest.json"
        self.public_overrides[manifest_key] = PublicHttpResponse(
            200,
            {"Content-Type": "text/plain", "Content-Disposition": "inline"},
            b"not-json",
        )
        client = FakeCosClient(self.store)
        with self.assertRaisesRegex(Exception, "PUBLIC_RESPONSE_CONTENT_TYPE_MISMATCH"):
            self.make_publisher(client).publish(str(self.output_root), DATE)
        self.assertEqual(client.calls, [])

    def test_local_hash_and_date_validation_fail_closed(self):
        manifest_path = self.output_root / DATE / "manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["vttSha256"] = "0" * 64
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        with self.assertRaisesRegex(ArtifactValidationError, "VTT_HASH_MISMATCH"):
            self.make_publisher().publish(str(self.output_root), DATE)

        self.write_artifact(DATE, manifest_date="2026-09-18")
        with self.assertRaisesRegex(ArtifactValidationError, "MANIFEST_DATE_MISMATCH"):
            self.make_publisher().publish(str(self.output_root), DATE)

    def test_index_is_pinned_to_validated_date_not_local_newer_date(self):
        self.write_artifact("2026-09-18")
        client = FakeCosClient(self.store)
        result = self.make_publisher(client).publish(str(self.output_root), DATE)
        self.assertEqual(result["index"]["latestEpisodeDate"], DATE)
        index_body = self.store[INDEX_KEY][0]
        index = json.loads(index_body.decode("utf-8"))
        self.assertEqual(index["latestEpisodeDate"], DATE)
        self.assertNotIn("2026-09-18", index["latestManifestUrl"])

    def test_same_content_keeps_current_index_without_rewriting_it(self):
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)
        publisher.publish(str(self.output_root), DATE)
        client.calls.clear()

        result = publisher.publish(str(self.output_root), DATE)

        self.assertEqual(result["remoteStatus"], "ALREADY_PUBLISHED")
        self.assertEqual(result["indexAction"], "ALREADY_CURRENT")
        self.assertEqual(client.calls, [])

    def test_index_update_requires_single_writer_and_saves_exact_snapshot(self):
        old_index = {
            "schemaVersion": 1,
            "latestEpisodeDate": "2026-09-16",
            "latestManifestUrl": "/subtitles/evening-weather/2026-09-16/manifest.json",
            "updatedAt": "2026-09-16T12:00:00Z",
            "unrelatedMetadata": {"owner": "weather-team"},
        }
        old_bytes = json.dumps(old_index, ensure_ascii=False).encode("utf-8")
        headers = {
            "Content-Type": "application/json; charset=utf-8",
            "Cache-Control": "no-cache, max-age=60",
            "Content-Disposition": "inline",
        }
        self.store[INDEX_KEY] = (old_bytes, headers)

        blocked_client = FakeCosClient(self.store)
        with self.assertRaisesRegex(
            IndexUpdateRequiresSingleWriterError,
            "INDEX_UPDATE_REQUIRES_SINGLE_WRITER_CONFIRMATION",
        ):
            self.make_publisher(blocked_client).publish(str(self.output_root), DATE)
        self.assertEqual(blocked_client.calls, [])
        self.assertEqual(self.store[INDEX_KEY][0], old_bytes)

        client = FakeCosClient(self.store)
        publisher = TencentCosPublisher(
            client=client,
            bucket=BUCKET,
            region=REGION,
            public_get=self.public_get,
            retry_backoff_seconds=0,
            sleep_fn=lambda _: None,
            single_writer_confirmed=True,
            state_dir=str(self.test_dir / "publish-state"),
        )
        result = publisher.publish(str(self.output_root), DATE)
        self.assertEqual(
            result["index"]["unrelatedMetadata"],
            {"owner": "weather-team"},
        )
        self.assertEqual(Path(result["indexBackupPath"]).read_bytes(), old_bytes)
        candidate_bytes = Path(result["indexCandidatePath"]).read_bytes()
        self.assertEqual(candidate_bytes, self.store[INDEX_KEY][0])

    def test_index_readback_failure_reports_unknown_outcome(self):
        self.public_overrides[INDEX_KEY] = PublicHttpResponse(404, {}, b"")
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)

        with self.assertRaisesRegex(
            IndexWriteOutcomeUnknownError,
            "INDEX_WRITE_OUTCOME_UNKNOWN",
        ):
            publisher.publish(str(self.output_root), DATE)

        self.assertIn(INDEX_KEY, self.store)
        self.assertEqual([call["Key"] for call in client.calls][-1], INDEX_KEY)

    def test_same_day_different_content_is_revision_conflict(self):
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)
        publisher.publish(str(self.output_root), DATE)
        client.calls.clear()
        self.write_artifact(DATE, vtt_text="WEBVTT\n\n1\n00:00:00.000 --> 00:00:01.000\n不同内容\n", source_content=b"revised")

        with self.assertRaisesRegex(RevisionConflictError, "REVISION_CONFLICT"):
            publisher.publish(str(self.output_root), DATE)
        self.assertEqual(client.calls, [])
        self.assertEqual(json.loads(self.store[INDEX_KEY][0].decode("utf-8"))["latestEpisodeDate"], DATE)

    def test_remote_manifest_wrong_date_is_revision_conflict(self):
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)
        publisher.publish(str(self.output_root), DATE)
        manifest_key = f"subtitles/evening-weather/{DATE}/manifest.json"
        bad_manifest = json.loads(self.store[manifest_key][0].decode("utf-8"))
        bad_manifest["episodeDate"] = "2026-09-18"
        self.store[manifest_key] = (
            json.dumps(bad_manifest, ensure_ascii=False, indent=2).encode("utf-8"),
            self.store[manifest_key][1],
        )
        client.calls.clear()

        with self.assertRaisesRegex(RevisionConflictError, "remote manifest date"):
            publisher.publish(str(self.output_root), DATE)
        self.assertEqual(client.calls, [])

    def test_remote_manifest_compares_all_client_visible_fields(self):
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)
        publisher.publish(str(self.output_root), DATE)
        manifest_key = f"subtitles/evening-weather/{DATE}/manifest.json"
        bad_manifest = json.loads(self.store[manifest_key][0].decode("utf-8"))
        bad_manifest["cueCount"] = 99
        self.store[manifest_key] = (
            json.dumps(bad_manifest, ensure_ascii=False, indent=2).encode("utf-8"),
            self.store[manifest_key][1],
        )
        client.calls.clear()

        with self.assertRaisesRegex(RevisionConflictError, "manifest fields differ"):
            publisher.publish(str(self.output_root), DATE)
        self.assertEqual(client.calls, [])
        self.assertEqual(
            json.loads(self.store[INDEX_KEY][0].decode("utf-8"))["latestEpisodeDate"],
            DATE,
        )

    def test_new_manifest_readback_must_match_exact_bytes_before_index(self):
        class MutatingManifestClient(FakeCosClient):
            def put_object(self, **kwargs):
                response = super().put_object(**kwargs)
                if kwargs["Key"].endswith("/manifest.json"):
                    body, headers = self.store[kwargs["Key"]]
                    changed = json.loads(body.decode("utf-8"))
                    changed["cueCount"] = 99
                    self.store[kwargs["Key"]] = (
                        json.dumps(changed, ensure_ascii=False, indent=2).encode("utf-8"),
                        headers,
                    )
                return response

        client = MutatingManifestClient(self.store)
        with self.assertRaisesRegex(Exception, "PUBLIC_MANIFEST_READBACK_BYTES_MISMATCH"):
            self.make_publisher(client).publish(str(self.output_root), DATE)
        self.assertNotIn(INDEX_KEY, self.store)

    def test_older_backfill_preserves_verified_newer_remote_index(self):
        self.write_artifact("2026-09-18")
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)
        publisher.publish(str(self.output_root), "2026-09-18")
        client.calls.clear()

        result = publisher.publish(str(self.output_root), DATE)

        self.assertEqual(result["indexAction"], "PRESERVED_NEWER")
        self.assertEqual(result["index"]["latestEpisodeDate"], "2026-09-18")
        self.assertNotIn(INDEX_KEY, [call["Key"] for call in client.calls])
        self.assertEqual(
            json.loads(self.store[INDEX_KEY][0].decode("utf-8"))["latestEpisodeDate"],
            "2026-09-18",
        )

    def test_local_backfill_preserves_newer_index(self):
        self.write_artifact("2026-09-18")
        destination = self.test_dir / "local-static"
        publisher = LocalStaticPublisher(str(destination))
        publisher.publish(str(self.output_root), "2026-09-18")

        result = publisher.publish(str(self.output_root), DATE)

        self.assertEqual(result["indexAction"], "PRESERVED_NEWER")
        self.assertEqual(
            json.loads((destination / "index.json").read_text(encoding="utf-8"))["latestEpisodeDate"],
            "2026-09-18",
        )

    def test_invalid_remote_index_is_not_used_or_overwritten(self):
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)
        publisher.publish(str(self.output_root), DATE)
        original_index = self.store[INDEX_KEY]
        self.store[INDEX_KEY] = (
            b'{"schemaVersion":1,"latestEpisodeDate":"2026-09-18",'
            b'"latestManifestUrl":"/wrong/path","updatedAt":"now"}',
            original_index[1],
        )
        bad_index = self.store[INDEX_KEY][0]
        client.calls.clear()

        with self.assertRaisesRegex(Exception, "PUBLIC_INDEX_INVALID"):
            publisher.publish(str(self.output_root), DATE)
        self.assertEqual(client.calls, [])
        self.assertEqual(self.store[INDEX_KEY][0], bad_index)

    def test_index_target_must_be_publicly_verified_before_preserving_pointer(self):
        client = FakeCosClient(self.store)
        publisher = self.make_publisher(client)
        publisher.publish(str(self.output_root), DATE)
        original_headers = self.store[INDEX_KEY][1]
        unuploaded_index = json.dumps(
            {
                "schemaVersion": 1,
                "latestEpisodeDate": "2026-09-18",
                "latestManifestUrl": "/subtitles/evening-weather/2026-09-18/manifest.json",
                "updatedAt": "2026-09-18T12:00:00Z",
            },
            ensure_ascii=False,
            indent=2,
        ).encode("utf-8")
        self.store[INDEX_KEY] = (unuploaded_index, original_headers)
        client.calls.clear()

        with self.assertRaisesRegex(Exception, "PUBLIC_INDEX_TARGET_UNVERIFIED"):
            publisher.publish(str(self.output_root), DATE)
        self.assertEqual(client.calls, [])
        self.assertEqual(self.store[INDEX_KEY][0], unuploaded_index)

    def test_racing_writer_conflict_does_not_overwrite_complete_date(self):
        vtt_key = f"subtitles/evening-weather/{DATE}/subtitle.vtt"
        manifest_key = f"subtitles/evening-weather/{DATE}/manifest.json"

        class RacingClient(FakeCosClient):
            def __init__(self, store):
                super().__init__(store)
                self.raced = False
                self.other_vtt = b"WEBVTT\n\n1\n00:00:00.000 --> 00:00:01.000\nother writer\n"

            def put_object(self, **kwargs):
                if kwargs["Key"] == vtt_key and not self.raced:
                    self.raced = True
                    self.store[vtt_key] = (
                        self.other_vtt,
                        {
                            "Content-Type": "text/vtt; charset=utf-8",
                            "Cache-Control": "public, max-age=31536000, immutable",
                            "Content-Disposition": "inline",
                        },
                    )
                    self.store[manifest_key] = (
                        b"{}",
                        {
                            "Content-Type": "application/json; charset=utf-8",
                            "Cache-Control": "public, max-age=31536000, immutable",
                            "Content-Disposition": "inline",
                        },
                    )
                return super().put_object(**kwargs)

        client = RacingClient(self.store)
        with self.assertRaisesRegex(RevisionConflictError, "remote VTT bytes differ"):
            self.make_publisher(client).publish(str(self.output_root), DATE)
        self.assertEqual(self.store[vtt_key][0], client.other_vtt)
        self.assertNotIn(INDEX_KEY, self.store)

    def test_pinned_sdk_adapter_sends_real_forbid_overwrite_header(self):
        captured = {}

        class FakeConfig:
            def uri(self, *, bucket, path):
                return f"https://{bucket}.cos.{REGION}.myqcloud.com/{path}"

        class FakeAuth:
            def __init__(self, config, key):
                captured["authArgs"] = (config, key)

        class LowLevelClient:
            def __init__(self):
                self._conf = FakeConfig()

            def send_request(self, **kwargs):
                captured.update(kwargs)
                return types.SimpleNamespace(headers={"ETag": "mock-etag"})

        auth_module = types.ModuleType("qcloud_cos.cos_auth")
        auth_module.CosS3Auth = FakeAuth
        with patch.dict(
            sys.modules,
            {
                "qcloud_cos": types.ModuleType("qcloud_cos"),
                "qcloud_cos.cos_auth": auth_module,
            },
        ):
            result = self.make_publisher(LowLevelClient())._put_object(
                key=f"subtitles/evening-weather/{DATE}/subtitle.vtt",
                body=b"WEBVTT\n",
                content_type="text/vtt; charset=utf-8",
                cache_control="public, max-age=31536000, immutable",
                forbid_overwrite=True,
            )
        self.assertEqual(captured["headers"]["x-cos-forbid-overwrite"], "true")
        self.assertEqual(captured["method"], "PUT")
        self.assertEqual(result["sdkResponse"]["ETag"], "mock-etag")

    def test_full_pipeline_cli_hides_sdk_exception_chain_and_marker(self):
        marker = "SYNTHETIC_SECRET_ID_SECRET_KEY_TOKEN_SIGNED_URL"
        self.write_artifact(DATE, source_content=b"cli-existing-video")
        video_file = self.test_dir / "cli-existing.mp4"
        video_file.write_bytes(b"cli-existing-video")

        class FailingClient(FakeCosClient):
            def put_object(self, **kwargs):
                self.calls.append(kwargs.copy())
                raise RuntimeError(marker)

        remote = self.make_publisher(FailingClient(self.store))
        with self.assertRaises(Exception):
            remote.publish(str(self.output_root), DATE)
        self.assertNotIn(marker, traceback.format_exc())

        stdout = io.StringIO()
        stderr = io.StringIO()
        argv = [
            "publisher.py",
            "--episode-date", DATE,
            "--source-video-url", VIDEO_URL,
            "--video-file", str(video_file),
            "--output-dir", str(self.output_root),
            "--publish-target", "cos",
        ]
        with patch.object(sys, "argv", argv), patch(
            "tools.asr.publisher.TencentCosPublisher", return_value=remote
        ), redirect_stdout(stdout), redirect_stderr(stderr):
            exit_code = main()
        combined = stdout.getvalue() + stderr.getvalue()
        self.assertEqual(exit_code, 1)
        self.assertNotIn(marker, combined)
        self.assertNotIn("Traceback", combined)
        self.assertIn("errorCode=CLI_PUBLISH_FAILED", combined)
        self.assertIn("localStatus=UNKNOWN", combined)
        self.assertIn("remoteStatus=FAILED", combined)

    def test_cli_report_file_contains_safe_public_evidence(self):
        report_path = self.test_dir / "cos-report.json"
        client = FakeCosClient(self.store)
        remote = self.make_publisher(client)
        stdout = io.StringIO()
        stderr = io.StringIO()
        argv = [
            "publisher.py",
            "--publish-existing",
            "--episode-date", DATE,
            "--output-dir", str(self.output_root),
            "--publish-target", "cos",
            "--report-file", str(report_path),
        ]
        with patch.object(sys, "argv", argv), patch(
            "tools.asr.publisher.TencentCosPublisher", return_value=remote
        ), redirect_stdout(stdout), redirect_stderr(stderr):
            exit_code = main()
        report = json.loads(report_path.read_text(encoding="utf-8"))
        self.assertEqual(exit_code, 0)
        self.assertEqual(report["remoteStatus"], "PUBLISHED")
        self.assertEqual(set(report["publicUrls"]), {"vtt", "manifest", "index"})
        self.assertIn("responseHeaders", report)
        self.assertIn("artifactHashes", report)
        self.assertNotIn("Secret", report_path.read_text(encoding="utf-8"))

    def test_publish_existing_cli_hides_sdk_exception_chain_and_marker(self):
        marker = "SYNTHETIC_SECRET_ID_SECRET_KEY_TOKEN_SIGNED_URL_DIRECT"

        class FailingClient(FakeCosClient):
            def put_object(self, **kwargs):
                self.calls.append(kwargs.copy())
                raise RuntimeError(marker)

        remote = self.make_publisher(FailingClient(self.store))
        stdout = io.StringIO()
        stderr = io.StringIO()
        argv = [
            "publisher.py",
            "--publish-existing",
            "--episode-date", DATE,
            "--output-dir", str(self.output_root),
            "--publish-target", "cos",
        ]
        with patch.object(sys, "argv", argv), patch(
            "tools.asr.publisher.TencentCosPublisher", return_value=remote
        ), redirect_stdout(stdout), redirect_stderr(stderr):
            exit_code = main()
        combined = stdout.getvalue() + stderr.getvalue()
        self.assertEqual(exit_code, 1)
        self.assertNotIn(marker, combined)
        self.assertNotIn("Traceback", combined)
        self.assertIn("errorCode=CLI_PUBLISH_FAILED", combined)

    def test_missing_credentials_error_names_only_environment_variables(self):
        with patch.dict(
            os.environ,
            {
                "TENCENT_COS_BUCKET": BUCKET,
                "TENCENT_COS_REGION": REGION,
                "TENCENT_CLOUD_SECRET_ID": "",
                "TENCENT_CLOUD_SECRET_KEY": "",
            },
            clear=True,
        ):
            with self.assertRaises(PublisherConfigurationError) as context:
                TencentCosPublisher()
        message = str(context.exception)
        self.assertIn("TENCENT_CLOUD_SECRET_ID", message)
        self.assertIn("TENCENT_CLOUD_SECRET_KEY", message)
        self.assertNotIn("secret", message.lower().replace("secret_id", "").replace("secret_key", ""))

    def test_temporary_token_is_passed_to_sdk_without_being_logged(self):
        captured = {}

        class FakeConfig:
            def __init__(self, **kwargs):
                captured.update(kwargs)

        class FakeClient:
            def __init__(self, config):
                self.config = config

        fake_sdk = types.SimpleNamespace(CosConfig=FakeConfig, CosS3Client=FakeClient)
        with patch.dict(
            os.environ,
            {
                "TENCENT_CLOUD_SECRET_ID": "test-id-value",
                "TENCENT_CLOUD_SECRET_KEY": "test-key-value",
                "TENCENT_CLOUD_SESSION_TOKEN": "test-token-value",
                "TENCENT_COS_REGION": REGION,
                "TENCENT_COS_BUCKET": BUCKET,
            },
            clear=True,
        ), patch.dict("sys.modules", {"qcloud_cos": fake_sdk}):
            publisher = TencentCosPublisher()

        self.assertIsInstance(publisher.client, FakeClient)
        self.assertEqual(captured["Token"], "test-token-value")
        self.assertNotIn("test-key-value", repr(publisher.client))


if __name__ == "__main__":
    unittest.main()
