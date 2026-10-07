"""
Daily Subtitle Publisher for Weather Video.
Pipeline:
  discover latest/exact episode
  -> exact episode identity
  -> download China Weather MP4
  -> verify source video SHA-256
  -> idempotency check (NO_OP / ALREADY_PUBLISHED vs SOURCE_REVISION_CHANGED)
  -> extract audio
  -> SenseVoice ASR
  -> WebVTT
  -> manifest v1
  -> schema & hash validation
  -> atomic publish (directory swap + index.json)
  -> operational logging
"""

import os
import sys
import re
import json
import time
import uuid
import shutil
import hashlib
import argparse
import urllib.request
import ssl
from datetime import datetime, timezone
from typing import Optional, Dict, Any, Tuple, List

# Ensure repository root is in sys.path
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.asr.builder import (
    SubtitleArtifactBuilder,
    compute_sha256,
    extract_date_code,
    verify_episode_identity,
    find_ffmpeg,
    SubtitleBuilderError,
    ExactEpisodeIdentityMismatchError,
    WebVttValidationError,
    ManifestSchemaValidationError
)
from tools.asr.publishing import (
    ArtifactPublisher,
    LocalStaticPublisher,
    TencentCosPublisher,
    write_safe_report,
)

CHINA_WEATHER_JSONP_URL = "https://www.weather.com.cn/pubm/lianbo_3m.htm"
PROGRAM_NAME = "EVENING_WEATHER"
PROGRAM_SLUG = "evening-weather"

class IdempotencyStatus:
    ALREADY_PUBLISHED = "NO_OP / ALREADY_PUBLISHED"
    SOURCE_REVISION_CHANGED = "SOURCE_REVISION_CHANGED"
    NEEDS_PUBLISH = "NEEDS_PUBLISH"

class DailySubtitlePublisher:
    def __init__(
        self,
        output_base_dir: str = "dist/subtitles/evening-weather",
        scratch_dir: Optional[str] = None,
        jsonp_url: str = CHINA_WEATHER_JSONP_URL,
        ffmpeg_bin: Optional[str] = None
    ):
        self.output_base_dir = os.path.abspath(output_base_dir)
        self.scratch_dir = os.path.abspath(scratch_dir or os.path.join(REPO_ROOT, ".asr_scratch"))
        self.jsonp_url = jsonp_url
        self.ffmpeg_bin = ffmpeg_bin
        os.makedirs(self.output_base_dir, exist_ok=True)
        os.makedirs(self.scratch_dir, exist_ok=True)

    def fetch_episode_info(self, requested_date: Optional[str] = None) -> Dict[str, str]:
        """
        Discovers episode information from China Weather JSONP.
        If requested_date is given (YYYY-MM-DD or YYYYMMDD), enforces exact match fail-closed.
        If requested_date is None, returns the newest broadcast item.
        """
        req = urllib.request.Request(
            self.jsonp_url,
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer": "http://www.weather.com.cn/video/ylist.shtml"
            }
        )
        ctx = ssl.create_default_context()
        try:
            with urllib.request.urlopen(req, context=ctx, timeout=15) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
        except Exception as e:
            raise SubtitleBuilderError(f"Failed to fetch China Weather JSONP: {e}") from e

        m = re.search(r"getLbDatas\((.*)\)", raw, re.DOTALL)
        if not m:
            raise SubtitleBuilderError("Invalid JSONP response: getLbDatas wrapper missing")

        try:
            data_wrapper = json.loads(m.group(1).strip())
            items = data_wrapper.get("data", [])
        except Exception as e:
            raise SubtitleBuilderError(f"Failed to parse JSONP body: {e}") from e

        if not items:
            raise SubtitleBuilderError("No episodes found in China Weather data")

        if requested_date:
            target_code = extract_date_code(requested_date)
            for item in items:
                title = item.get("title", "")
                pub_date = item.get("pubDate", "")
                item_code = extract_date_code(title) or extract_date_code(pub_date)
                if item_code == target_code:
                    url = item.get("url", "").strip()
                    matched, _, _ = verify_episode_identity(requested_date, url)
                    if not matched:
                        raise ExactEpisodeIdentityMismatchError(
                            f"EXACT_EPISODE_IDENTITY_MISMATCH: requested='{requested_date}', resolved URL='{url}'"
                        )
                    # Canonical ISO date: YYYY-MM-DD
                    iso_date = f"{target_code[:4]}-{target_code[4:6]}-{target_code[6:8]}"
                    return {
                        "episodeDate": iso_date,
                        "dateCode": target_code,
                        "sourceVideoUrl": url,
                        "title": title,
                        "pubDate": pub_date
                    }
            raise ExactEpisodeIdentityMismatchError(
                f"EXACT_EPISODE_NOT_FOUND: Requested date '{requested_date}' (code {target_code}) not present in China Weather listing"
            )
        else:
            latest = items[0]
            title = latest.get("title", "")
            pub_date = latest.get("pubDate", "")
            url = latest.get("url", "").strip()
            date_code = extract_date_code(title) or extract_date_code(pub_date)
            if not date_code:
                raise SubtitleBuilderError(f"Cannot extract date from latest episode: title='{title}', pubDate='{pub_date}'")
            matched, _, _ = verify_episode_identity(date_code, url)
            if not matched:
                raise ExactEpisodeIdentityMismatchError(
                    f"EXACT_EPISODE_IDENTITY_MISMATCH in latest: dateCode='{date_code}', url='{url}'"
                )
            iso_date = f"{date_code[:4]}-{date_code[4:6]}-{date_code[6:8]}"
            return {
                "episodeDate": iso_date,
                "dateCode": date_code,
                "sourceVideoUrl": url,
                "title": title,
                "pubDate": pub_date
            }

    def download_video(self, source_video_url: str, episode_date: str) -> Tuple[str, str]:
        """
        Downloads source MP4 to scratch directory (resuming/reusing if already complete).
        Returns (local_filepath, uppercase_sha256).
        """
        date_code = episode_date.replace("-", "")
        dest_file = os.path.join(self.scratch_dir, f"{date_code}.mp4")

        # Download if file does not exist or is empty
        if not os.path.exists(dest_file) or os.path.getsize(dest_file) == 0:
            temp_dest = dest_file + f".part_{uuid.uuid4().hex[:6]}"
            req = urllib.request.Request(
                source_video_url,
                headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"}
            )
            ctx = ssl.create_default_context()
            try:
                with urllib.request.urlopen(req, context=ctx, timeout=60) as resp, open(temp_dest, "wb") as out:
                    shutil.copyfileobj(resp, out)
                os.replace(temp_dest, dest_file)
            except Exception as e:
                if os.path.exists(temp_dest):
                    os.remove(temp_dest)
                raise SubtitleBuilderError(f"Failed to download source MP4 from {source_video_url}: {e}") from e

        sha256 = compute_sha256(dest_file)
        return dest_file, sha256

    def check_idempotency(
        self,
        episode_date: str,
        current_source_sha256: str
    ) -> Tuple[str, Optional[Dict[str, Any]]]:
        """
        Checks existing published artifact in output_base_dir / episode_date.
        Returns:
          (ALREADY_PUBLISHED, manifest) if sourceVideoSha256 and VTT SHA-256 match perfectly.
          (SOURCE_REVISION_CHANGED, manifest) if existing sourceVideoSha256 differs.
          (NEEDS_PUBLISH, None) if not published yet or corrupt.
        """
        episode_dir = os.path.join(self.output_base_dir, episode_date)
        manifest_path = os.path.join(episode_dir, "manifest.json")
        vtt_path = os.path.join(episode_dir, "subtitle.vtt")

        if not os.path.exists(manifest_path) or not os.path.exists(vtt_path):
            return IdempotencyStatus.NEEDS_PUBLISH, None

        try:
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)

            # Check existing VTT hash integrity
            actual_vtt_sha = compute_sha256(vtt_path)
            if actual_vtt_sha != manifest.get("vttSha256"):
                return IdempotencyStatus.NEEDS_PUBLISH, None

            existing_source_sha = manifest.get("sourceVideoSha256", "").upper()
            if existing_source_sha == current_source_sha256.upper():
                return IdempotencyStatus.ALREADY_PUBLISHED, manifest
            else:
                return IdempotencyStatus.SOURCE_REVISION_CHANGED, manifest

        except Exception:
            return IdempotencyStatus.NEEDS_PUBLISH, None

    def update_index_manifest(self, explicit_latest_date: Optional[str] = None) -> Dict[str, Any]:
        """
        Generates and atomically writes lightweight index:
        /subtitles/evening-weather/index.json
        Only contains:
        - latestEpisodeDate
        - latestManifestUrl
        - updatedAt
        - schemaVersion
        """
        # Discover all valid YYYY-MM-DD directories in output_base_dir
        date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
        dates = []
        if os.path.exists(self.output_base_dir):
            for entry in os.listdir(self.output_base_dir):
                full_path = os.path.join(self.output_base_dir, entry)
                if os.path.isdir(full_path) and date_pattern.match(entry):
                    # verify manifest exists
                    if os.path.exists(os.path.join(full_path, "manifest.json")):
                        dates.append(entry)

        if explicit_latest_date and explicit_latest_date not in dates:
            dates.append(explicit_latest_date)

        dates.sort(reverse=True)
        latest_date = dates[0] if dates else (explicit_latest_date or "2026-09-17")

        index_data = {
            "schemaVersion": 1,
            "latestEpisodeDate": latest_date,
            "latestManifestUrl": f"/subtitles/{PROGRAM_SLUG}/{latest_date}/manifest.json",
            "updatedAt": datetime.now(timezone.utc).isoformat()
        }

        index_path = os.path.join(self.output_base_dir, "index.json")
        temp_index = os.path.join(self.output_base_dir, f".index_{uuid.uuid4().hex[:6]}.tmp")
        with open(temp_index, "w", encoding="utf-8") as f:
            json.dump(index_data, f, ensure_ascii=False, indent=2)

        os.replace(temp_index, index_path)
        return index_data

    def run(
        self,
        episode_date: Optional[str] = None,
        source_video_url: Optional[str] = None,
        video_file_override: Optional[str] = None,
        audio_file_override: Optional[str] = None,
        precomputed_cues_override: Optional[List[Dict[str, Any]]] = None,
        fixed_generated_at: Optional[str] = None,
        force: bool = False,
        remote_publisher: Optional[ArtifactPublisher] = None
    ) -> Dict[str, Any]:
        """
        Executes the full automated daily pipeline.
        Returns a dict with execution results and operational metrics.
        """
        t0 = time.perf_counter()
        publish_status = "UNKNOWN"

        try:
            # 1. Discover episode info if not explicitly fully provided
            if source_video_url and episode_date:
                resolved_date = episode_date.strip()
                resolved_url = source_video_url.strip()
                matched, _, _ = verify_episode_identity(resolved_date, resolved_url)
                if not matched:
                    raise ExactEpisodeIdentityMismatchError(
                        f"EXACT_EPISODE_IDENTITY_MISMATCH: '{resolved_date}' vs '{resolved_url}'"
                    )
            else:
                info = self.fetch_episode_info(requested_date=episode_date)
                resolved_date = info["episodeDate"]
                resolved_url = info["sourceVideoUrl"]

            # 2. Obtain video file & compute sourceVideoSha256
            if video_file_override and os.path.exists(video_file_override):
                video_file = video_file_override
                source_video_sha256 = compute_sha256(video_file)
            elif resolved_url:
                video_file, source_video_sha256 = self.download_video(resolved_url, resolved_date)
            else:
                raise SubtitleBuilderError("No source video URL or file available")

            # 3. Idempotency Gate
            idempotency_status, existing_manifest = self.check_idempotency(resolved_date, source_video_sha256)

            if idempotency_status == IdempotencyStatus.ALREADY_PUBLISHED and not force:
                publish_status = IdempotencyStatus.ALREADY_PUBLISHED
                elapsed_sec = round(time.perf_counter() - t0, 3)
                index_manifest = self.update_index_manifest(resolved_date)

                self._log_operational(
                    episode_date=resolved_date,
                    source_video_sha256=source_video_sha256,
                    asr_model=existing_manifest.get("asrModel", "SenseVoiceSmall"),
                    cue_count=existing_manifest.get("cueCount", 0),
                    vtt_sha256=existing_manifest.get("vttSha256", ""),
                    publish_status=publish_status,
                    elapsed_seconds=elapsed_sec
                )

                # A local NO_OP is not a remote NO_OP.  An existing local
                # artifact may be missing from COS or may need a failed
                # upload retried, so the remote target is always called.
                remote_result = None
                if remote_publisher is not None:
                    remote_result = remote_publisher.publish(
                        self.output_base_dir,
                        resolved_date,
                    )

                return {
                    "publishStatus": publish_status,
                    "localPublishStatus": publish_status,
                    "remotePublishStatus": (
                        remote_result["remoteStatus"]
                        if remote_result is not None
                        else "NOT_REQUESTED"
                    ),
                    "remotePublish": remote_result,
                    "episodeDate": resolved_date,
                    "manifest": existing_manifest,
                    "index": index_manifest,
                    "elapsedSeconds": elapsed_sec
                }

            if idempotency_status == IdempotencyStatus.SOURCE_REVISION_CHANGED:
                old_sha = existing_manifest.get("sourceVideoSha256") if existing_manifest else "NONE"
                print(f"[DailySubtitlePublisher] SOURCE_REVISION_CHANGED: old={old_sha} new={source_video_sha256}")
                publish_status = "REVISION_UPDATED"
            else:
                publish_status = "PUBLISHED"

            # 4. Build Artifact & Atomic Publish via SubtitleArtifactBuilder
            builder = SubtitleArtifactBuilder(
                episode_date=resolved_date,
                source_video_url=resolved_url,
                output_base_dir=self.output_base_dir,
                ffmpeg_bin=self.ffmpeg_bin,
                scratch_dir=self.scratch_dir
            )

            build_result = builder.build_artifact(
                video_file_override=video_file,
                audio_file_override=audio_file_override,
                fixed_generated_at=fixed_generated_at,
                precomputed_cues_override=precomputed_cues_override
            )

            manifest = build_result["manifest"]

            # 5. Update index manifest atomically
            index_manifest = self.update_index_manifest(resolved_date)

            elapsed_sec = round(time.perf_counter() - t0, 3)
            self._log_operational(
                episode_date=resolved_date,
                source_video_sha256=source_video_sha256,
                asr_model=manifest["asrModel"],
                cue_count=manifest["cueCount"],
                vtt_sha256=manifest["vttSha256"],
                publish_status=publish_status,
                elapsed_seconds=elapsed_sec
            )

            remote_result = None
            if remote_publisher is not None:
                remote_result = remote_publisher.publish(
                    self.output_base_dir,
                    resolved_date,
                )

            return {
                "publishStatus": publish_status,
                "localPublishStatus": publish_status,
                "remotePublishStatus": (
                    remote_result["remoteStatus"]
                    if remote_result is not None
                    else "NOT_REQUESTED"
                ),
                "remotePublish": remote_result,
                "episodeDate": resolved_date,
                "manifest": manifest,
                "index": index_manifest,
                "vttFile": build_result["vttFile"],
                "manifestFile": build_result["manifestFile"],
                "elapsedSeconds": elapsed_sec
            }

        except Exception as e:
            elapsed_sec = round(time.perf_counter() - t0, 3)
            # The CLI adds the stable error code.  Keep this operational line
            # type-only so an injected SDK exception can never print its
            # message or traceback through the pipeline path.
            print(
                "[DailySubtitlePublisher] "
                f"PUBLISH FAILED errorType={type(e).__name__}",
                file=sys.stderr,
            )
            raise

    def _log_operational(
        self,
        episode_date: str,
        source_video_sha256: str,
        asr_model: str,
        cue_count: int,
        vtt_sha256: str,
        publish_status: str,
        elapsed_seconds: float
    ):
        print("\n==================== OPERATIONAL LOG ====================")
        print(f"episodeDate: {episode_date}")
        print(f"sourceVideoSha256: {source_video_sha256}")
        print(f"ASR model: {asr_model}")
        print(f"cueCount: {cue_count}")
        print(f"vttSha256: {vtt_sha256}")
        print(f"publishStatus: {publish_status}")
        print(f"elapsedSeconds: {elapsed_seconds}")
        print("=========================================================\n")


def parse_args():
    parser = argparse.ArgumentParser(description="Daily Weather Subtitle Publisher")
    parser.add_argument("--episode-date", help="Optional exact date YYYY-MM-DD (defaults to latest broadcast)")
    parser.add_argument("--source-video-url", help="Optional direct MP4 URL")
    parser.add_argument("--video-file", help="Optional local MP4 file to skip download")
    parser.add_argument("--output-dir", default="dist/subtitles/evening-weather", help="Output directory")
    parser.add_argument("--scratch-dir", help="Scratch directory for video and temp files")
    parser.add_argument("--force", action="store_true", help="Force republish even if identical")
    parser.add_argument(
        "--publish-existing",
        action="store_true",
        help="Publish an existing standard artifact without downloading video or running ASR",
    )
    parser.add_argument(
        "--publish-target",
        choices=("local", "cos"),
        help="Optional artifact target for --publish-existing or the full pipeline",
    )
    parser.add_argument(
        "--report-file",
        help="Optional path for a safe JSON evidence report (no credentials)",
    )
    parser.add_argument(
        "--single-writer-confirmed",
        action="store_true",
        help=(
            "Assert this is the only production index writer; the local process lock "
            "does not protect other machines"
        ),
    )
    return parser.parse_args()


def _print_cli_failure(*, local_status: str, remote_status: str, error: BaseException) -> None:
    print(
        "[DailySubtitlePublisher] "
        f"errorCode=CLI_PUBLISH_FAILED "
        f"localStatus={local_status} "
        f"remoteStatus={remote_status} "
        f"errorType={type(error).__name__}",
        file=sys.stderr,
    )


def _safe_result_report(result: Dict[str, Any]) -> Dict[str, Any]:
    remote_result = result.get("remotePublish")
    if remote_result is None:
        return result
    return {
        "localStatus": result.get("localPublishStatus", "UNKNOWN"),
        "remoteStatus": result.get("remotePublishStatus", "UNKNOWN"),
        "episodeDate": result.get("episodeDate"),
        "remotePublish": remote_result,
    }


def _emit_report(result: Dict[str, Any], report_file: Optional[str]) -> bool:
    report = _safe_result_report(result)
    if report_file:
        try:
            write_safe_report(report_file, report)
        except Exception as error:
            _print_cli_failure(
                local_status=str(result.get("localPublishStatus", result.get("localStatus", "UNKNOWN"))),
                remote_status=str(result.get("remotePublishStatus", result.get("remoteStatus", "UNKNOWN"))),
                error=error,
            )
            return False
        print(f"[DailySubtitlePublisher] reportFile={os.path.abspath(report_file)}")
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return True

def main():
    args = parse_args()

    if args.publish_existing:
        if not args.episode_date:
            print(
                "[DailySubtitlePublisher] --publish-existing requires --episode-date YYYY-MM-DD",
                file=sys.stderr,
            )
            return 2
        if not args.publish_target:
            print(
                "[DailySubtitlePublisher] --publish-existing requires --publish-target local|cos",
                file=sys.stderr,
            )
            return 2

        try:
            target = (
                LocalStaticPublisher()
                if args.publish_target == "local"
                else TencentCosPublisher(
                    single_writer_confirmed=args.single_writer_confirmed
                )
            )
            result = target.publish(args.output_dir, args.episode_date)
        except Exception as error:
            _print_cli_failure(
                local_status="LOCAL_VALIDATION_OR_PUBLISH_FAILED",
                remote_status="FAILED",
                error=error,
            )
            return 1
        print(
            "[DailySubtitlePublisher] "
            f"localStatus={result['localStatus']} "
            f"remoteStatus={result['remoteStatus']}"
        )
        if not _emit_report(result, args.report_file):
            return 1
        return 0

    remote_publisher = None
    try:
        if args.publish_target == "local":
            remote_publisher = LocalStaticPublisher()
        elif args.publish_target == "cos":
            remote_publisher = TencentCosPublisher(
                single_writer_confirmed=args.single_writer_confirmed
            )
    except Exception as error:
        _print_cli_failure(
            local_status="NOT_STARTED",
            remote_status="FAILED",
            error=error,
        )
        return 1

    publisher = DailySubtitlePublisher(
        output_base_dir=args.output_dir,
        scratch_dir=args.scratch_dir
    )
    try:
        result = publisher.run(
            episode_date=args.episode_date,
            source_video_url=args.source_video_url,
            video_file_override=args.video_file,
            force=args.force,
            remote_publisher=remote_publisher
        )
    except Exception as error:
        _print_cli_failure(
            local_status="UNKNOWN",
            remote_status="FAILED" if remote_publisher is not None else "NOT_REQUESTED",
            error=error,
        )
        return 1

    print(f"[DailySubtitlePublisher] Execution finished with status: {result['publishStatus']}")
    print(
        "[DailySubtitlePublisher] "
        f"localStatus={result['localPublishStatus']} "
        f"remoteStatus={result['remotePublishStatus']}"
    )
    if not _emit_report(result, args.report_file):
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
