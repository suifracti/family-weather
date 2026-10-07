"""
Command-line interface for Subtitle Artifact Builder.
Usage:
    python tools/asr/cli.py --episode-date 2026-09-17 --source-video-url https://vod.weathertv.cn/...
"""

import sys
import os
import argparse
import json

# Ensure repository root is in sys.path
REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.asr.builder import (
    SubtitleArtifactBuilder,
    SubtitleBuilderError,
    ExactEpisodeIdentityMismatchError,
    WebVttValidationError,
    ManifestSchemaValidationError
)

def parse_args():
    parser = argparse.ArgumentParser(description="Build standard WebVTT and Manifest artifacts for weather video.")
    parser.add_argument(
        "--episode-date",
        required=True,
        help="Calendar date of the broadcast episode in YYYY-MM-DD format (e.g. 2026-09-17)"
    )
    parser.add_argument(
        "--source-video-url",
        required=True,
        help="Official video stream/page URL"
    )
    parser.add_argument(
        "--output-dir",
        default="dist/subtitles/evening-weather",
        help="Base output directory (default: dist/subtitles/evening-weather)"
    )
    parser.add_argument(
        "--video-file",
        default=None,
        help="Optional local video MP4 path to skip downloading"
    )
    parser.add_argument(
        "--audio-file",
        default=None,
        help="Optional local PCM 16kHz mono WAV path to skip audio extraction"
    )
    parser.add_argument(
        "--fixed-timestamp",
        default=None,
        help="Fixed ISO-8601 UTC timestamp for deterministic idempotent reproduction"
    )
    parser.add_argument(
        "--scratch-dir",
        default=None,
        help="Temporary scratch directory for caching intermediate files"
    )
    return parser.parse_args()

def main():
    args = parse_args()
    print(f"[SubtitleArtifactBuilder] Starting build for episode: {args.episode_date}")
    print(f"  Source URL: {args.source_video_url}")
    print(f"  Output Base: {args.output_dir}")

    try:
        builder = SubtitleArtifactBuilder(
            episode_date=args.episode_date,
            source_video_url=args.source_video_url,
            output_base_dir=args.output_dir,
            scratch_dir=args.scratch_dir
        )

        result = builder.build_artifact(
            video_file_override=args.video_file,
            audio_file_override=args.audio_file,
            fixed_generated_at=args.fixed_timestamp
        )

        manifest = result["manifest"]
        print(f"[SubtitleArtifactBuilder] BUILD SUCCESSFUL!")
        print(f"  Target Directory: {result['targetDir']}")
        print(f"  VTT File: {result['vttFile']} (SHA-256: {manifest['vttSha256']})")
        print(f"  Manifest: {result['manifestFile']}")
        print(f"  Cue Count: {manifest['cueCount']}, Duration: {manifest['durationMs']}ms")
        return 0

    except ExactEpisodeIdentityMismatchError as e:
        print(f"[SubtitleArtifactBuilder] ERROR: {e}", file=sys.stderr)
        return 1
    except WebVttValidationError as e:
        print(f"[SubtitleArtifactBuilder] ERROR: {e}", file=sys.stderr)
        return 1
    except ManifestSchemaValidationError as e:
        print(f"[SubtitleArtifactBuilder] ERROR: {e}", file=sys.stderr)
        return 1
    except SubtitleBuilderError as e:
        print(f"[SubtitleArtifactBuilder] ERROR: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"[SubtitleArtifactBuilder] UNEXPECTED ERROR: {e}", file=sys.stderr)
        return 1

if __name__ == "__main__":
    sys.exit(main())
