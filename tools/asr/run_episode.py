"""Run the local, exact-date CCTV subtitle and human-summary workflow.

First run with --episode-date to discover and build/reuse that episode's local
subtitle artifact. Review subtitle.vtt and manually prepare a JSON file with
an ``items`` array, then rerun with --summary-file to bind it. This command
never publishes artifacts to a remote service.
"""

import argparse
import json
import os
import sys
from typing import Any, Dict, Optional


REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
if REPO_ROOT not in sys.path:
    sys.path.insert(0, REPO_ROOT)

from tools.asr.attach_summary import attach_summary
from tools.asr.publisher import DailySubtitlePublisher


def _read_summary_file(path: str) -> Dict[str, Any]:
    with open(path, "r", encoding="utf-8") as source:
        payload = json.load(source)
    items = payload.get("items") if isinstance(payload, dict) else None
    if not isinstance(items, list):
        raise ValueError("Summary JSON must contain an items array")
    return payload


def _summary_matches_manifest(summary: Dict[str, Any], manifest: Dict[str, Any]) -> bool:
    return (
        summary.get("program") == manifest.get("program") == "EVENING_WEATHER"
        and summary.get("episodeDate") == manifest.get("episodeDate")
        and summary.get("sourceVideoUrl") == manifest.get("sourceVideoUrl")
        and summary.get("sourceVideoSha256") == manifest.get("sourceVideoSha256")
        and summary.get("subtitleVttSha256") == manifest.get("vttSha256")
    )


def run_episode(
    episode_date: str,
    output_dir: str,
    scratch_dir: Optional[str] = None,
    summary_file: Optional[str] = None,
) -> Dict[str, Any]:
    if summary_file is not None and not os.path.isfile(summary_file):
        raise FileNotFoundError(f"Summary file does not exist: {summary_file}")
    supplied_summary = _read_summary_file(summary_file) if summary_file is not None else None

    publisher = DailySubtitlePublisher(output_base_dir=output_dir, scratch_dir=scratch_dir)
    result = publisher.run(episode_date=episode_date)
    manifest = result.get("manifest")
    resolved_date = result.get("episodeDate")
    if not isinstance(manifest, dict) or resolved_date != episode_date:
        raise RuntimeError("Exact episode subtitle preparation did not return the requested date")

    episode_dir = os.path.join(os.path.abspath(output_dir), episode_date)
    existing_summary = manifest.get("episodeSummary")
    if existing_summary is not None:
        if not _summary_matches_manifest(existing_summary, manifest):
            raise ValueError("Existing episode summary identity does not match its subtitle manifest")
        if supplied_summary is not None:
            if supplied_summary["items"] != existing_summary.get("items"):
                raise ValueError("A summary is already bound; refusing to overwrite reviewed content")
        return {
            "episodeDate": episode_date,
            "subtitleStatus": result.get("publishStatus", "REUSED"),
            "summaryStatus": "REUSED_BOUND_SUMMARY",
            "summaryItemCount": len(existing_summary.get("items", [])),
        }

    if summary_file is None:
        return {
            "episodeDate": episode_date,
            "subtitleStatus": result.get("publishStatus", "PREPARED"),
            "summaryStatus": "NEEDS_MANUAL_REVIEW",
            "nextStep": "Review subtitle.vtt and prepare a human-written items JSON, then rerun with --summary-file.",
            "episodeDir": episode_dir,
        }

    attached = attach_summary(episode_dir, episode_date, supplied_summary["items"])
    return {
        "episodeDate": episode_date,
        "subtitleStatus": result.get("publishStatus", "PREPARED"),
        "summaryStatus": "HUMAN_REVIEWED_SUMMARY_BOUND",
        "summaryMethod": attached["summaryMethod"],
        "summaryItemCount": len(attached["items"]),
    }


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prepare one exact CCTV episode locally, then attach its manually reviewed summary.",
        epilog=(
            "First run: --episode-date YYYY-MM-DD. Review the resulting subtitle.vtt and manually write "
            "an items JSON, then rerun with --summary-file path\\to\\items.json. "
            "Summaries are human-authored; this local workflow does not upload or schedule anything. "
            "A matching source-video hash lets publisher.py reuse the VTT without rerunning ASR."
        ),
    )
    parser.add_argument("--episode-date", required=True, help="Exact episode date YYYY-MM-DD")
    parser.add_argument(
        "--output-dir",
        default=os.path.join(REPO_ROOT, "dist", "subtitles", "evening-weather"),
        help="Local artifact directory (default: dist/subtitles/evening-weather)",
    )
    parser.add_argument("--scratch-dir", help="Local working files directory")
    parser.add_argument(
        "--summary-file",
        help="Human-reviewed JSON with an items array of startMs, endMs, and text objects",
    )
    args = parser.parse_args()

    try:
        result = run_episode(
            episode_date=args.episode_date,
            output_dir=args.output_dir,
            scratch_dir=args.scratch_dir,
            summary_file=args.summary_file,
        )
    except Exception as error:
        print(f"[CCTV episode workflow] failed: errorType={type(error).__name__}", file=sys.stderr)
        return 1

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
