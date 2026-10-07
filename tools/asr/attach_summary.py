"""Attach a human-reviewed summary to the exact local subtitle artifact."""

import argparse
import hashlib
import json
import os
import re
import tempfile
from typing import Any, Dict, List, Tuple


SUMMARY_METHOD = "HUMAN_REVIEWED_FROM_AI_ASR"
TIMING = re.compile(
    r"^(?P<start>\d{2}:\d{2}:\d{2}\.\d{3})\s+-->\s+"
    r"(?P<end>\d{2}:\d{2}:\d{2}\.\d{3})(?:\s+.*)?$"
)


def _time_ms(value: str) -> int:
    hours, minutes, seconds = value.split(":")
    whole, millis = seconds.split(".")
    return (((int(hours) * 60) + int(minutes)) * 60 + int(whole)) * 1000 + int(millis)


def _sha256(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as source:
        for chunk in iter(lambda: source.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def _date_code(value: str) -> str:
    match = re.search(r"\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b", value)
    if match:
        year, month, day = match.groups()
        return f"{year}{int(month):02d}{int(day):02d}"
    match = re.search(r"\b(\d{4})(\d{2})(\d{2})\b", value)
    return "".join(match.groups()) if match else ""


def _read_cues(vtt_path: str) -> List[Tuple[int, int]]:
    cues: List[Tuple[int, int]] = []
    with open(vtt_path, "r", encoding="utf-8") as source:
        for line in source:
            match = TIMING.match(line.strip())
            if match:
                cues.append((_time_ms(match.group("start")), _time_ms(match.group("end"))))
    return cues


def attach_summary(episode_dir: str, episode_date: str, items: List[Dict[str, Any]]) -> Dict[str, Any]:
    manifest_path = os.path.join(episode_dir, "manifest.json")
    vtt_path = os.path.join(episode_dir, "subtitle.vtt")
    with open(manifest_path, "r", encoding="utf-8") as source:
        manifest = json.load(source)

    if manifest.get("program") != "EVENING_WEATHER":
        raise ValueError("Unexpected program identity")
    if manifest.get("episodeDate") != episode_date:
        raise ValueError("Episode date does not match the requested date")
    if _date_code(manifest.get("sourceVideoUrl", "")) != episode_date.replace("-", ""):
        raise ValueError("Source video URL does not identify the requested date")
    if not os.path.isfile(vtt_path) or _sha256(vtt_path).lower() != str(manifest.get("vttSha256", "")).lower():
        raise ValueError("Subtitle VTT is missing or its hash does not match the manifest")

    cues = _read_cues(vtt_path)
    if len(cues) != manifest.get("cueCount"):
        raise ValueError("Parsed VTT cue count does not match the manifest")
    if len(items) not in range(3, 6):
        raise ValueError("Summary must contain 3 to 5 items")

    duration_ms = int(manifest.get("durationMs", 0))
    for item in items:
        start_ms = item.get("startMs")
        end_ms = item.get("endMs")
        text = item.get("text")
        if not isinstance(start_ms, int) or not isinstance(end_ms, int) or start_ms < 0 or end_ms <= start_ms:
            raise ValueError("Summary time range is invalid")
        if end_ms > duration_ms or not isinstance(text, str) or not text.strip():
            raise ValueError("Summary item is empty or outside the episode")
        if not any(cue_start < end_ms and cue_end > start_ms for cue_start, cue_end in cues):
            raise ValueError("Summary item time range does not overlap a subtitle cue")

    if manifest.get("episodeSummary") is not None:
        raise ValueError("Manifest already contains a summary; refusing to overwrite it")

    manifest["episodeSummary"] = {
        "program": manifest["program"],
        "episodeDate": manifest["episodeDate"],
        "sourceVideoUrl": manifest["sourceVideoUrl"],
        "sourceVideoSha256": manifest["sourceVideoSha256"],
        "subtitleVttSha256": manifest["vttSha256"],
        "summaryMethod": SUMMARY_METHOD,
        "items": items,
    }

    schema_path = os.path.join(os.path.dirname(__file__), "schema", "subtitle_manifest_schema_v1.json")
    try:
        import jsonschema
        with open(schema_path, "r", encoding="utf-8") as source:
            jsonschema.validate(manifest, json.load(source))
    except ImportError:
        pass

    fd, temp_path = tempfile.mkstemp(prefix=".manifest_", suffix=".tmp", dir=episode_dir)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(manifest, output, ensure_ascii=False, indent=2)
            output.write("\n")
        os.replace(temp_path, manifest_path)
    finally:
        if os.path.exists(temp_path):
            os.remove(temp_path)
    return manifest["episodeSummary"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--episode-date", required=True, help="Exact episode date YYYY-MM-DD")
    parser.add_argument("--episode-dir", required=True, help="Local directory containing manifest.json and subtitle.vtt")
    parser.add_argument("--items-file", required=True, help="JSON file containing an items array")
    args = parser.parse_args()
    with open(args.items_file, "r", encoding="utf-8") as source:
        payload = json.load(source)
    result = attach_summary(args.episode_dir, args.episode_date, payload.get("items", []))
    print(json.dumps({"episodeDate": result["episodeDate"], "itemCount": len(result["items"]), "method": result["summaryMethod"]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
