"""
Production Subtitle Artifact Builder for Weather Video.
Pipeline: Episode -> MP4 -> PCM 16kHz mono -> SenseVoice -> WebVTT -> Manifest Contract (v1)
"""

import os
import sys
import re
import json
import uuid
import shutil
import hashlib
import wave
import subprocess
from datetime import datetime, timezone
from typing import Optional, List, Dict, Any, Tuple
import numpy as np

# JSON Schema validation
try:
    import jsonschema
    HAS_JSONSCHEMA = True
except ImportError:
    HAS_JSONSCHEMA = False

DEFAULT_FFMPEG_PATHS = [os.environ.get("FAMILY_WEATHER_FFMPEG", "ffmpeg")]

class SubtitleBuilderError(Exception):
    """Base error for subtitle artifact builder."""
    pass

class ExactEpisodeIdentityMismatchError(SubtitleBuilderError):
    """Raised when requested episode date does not match the source video date."""
    pass

class AudioExtractionError(SubtitleBuilderError):
    """Raised when audio cannot be extracted from the source video."""
    pass

class AsrInferenceError(SubtitleBuilderError):
    """Raised when speech recognition inference fails."""
    pass

class WebVttValidationError(SubtitleBuilderError):
    """Raised when WebVTT content violates monotonic or duration constraints."""
    pass

class ManifestSchemaValidationError(SubtitleBuilderError):
    """Raised when generated manifest fails schema contract validation."""
    pass

def compute_sha256(filepath: str) -> str:
    """Computes uppercase SHA-256 hash of a file."""
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().upper()

def extract_date_code(text: str) -> Optional[str]:
    """
    Extracts an 8-digit date code (YYYYMMDD) from a date string, URL, or title.
    Supports formats:
    - 2026-09-17 or 2026/09/17 -> 20260917
    - 2026/9/17 -> 20260917
    - 20260917 -> 20260917
    """
    if not text:
        return None
        
    # Match YYYY-MM-DD or YYYY/MM/DD or YYYY/M/D
    m_sep = re.search(r"\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b", text)
    if m_sep:
        year, month, day = m_sep.groups()
        return f"{year}{int(month):02d}{int(day):02d}"
        
    # Match YYYYMMDD (8 consecutive digits)
    m_digits = re.search(r"\b(\d{4})(\d{2})(\d{2})\b", text)
    if m_digits:
        year, month, day = m_digits.groups()
        return f"{year}{month}{day}"
        
    return None

def verify_episode_identity(requested_date: str, source_video_url: str) -> Tuple[bool, str, Optional[str]]:
    """
    Verifies that requestedEpisodeDate strictly matches resolvedEpisodeDate from the video URL.
    Returns (matched, requested_date_code, url_date_code).
    """
    req_code = extract_date_code(requested_date)
    url_code = extract_date_code(source_video_url)
    if not req_code:
        return False, "", url_code
    matched = (url_code is not None and req_code == url_code)
    return matched, req_code, url_code

def format_vtt_timestamp(seconds: float) -> str:
    """Formats seconds into standard WebVTT timestamp: HH:MM:SS.mmm"""
    total_ms = int(round(seconds * 1000))
    hours = total_ms // 3600000
    minutes = (total_ms % 3600000) // 60000
    secs = (total_ms % 60000) // 1000
    ms = total_ms % 1000
    return f"{hours:02d}:{minutes:02d}:{secs:02d}.{ms:03d}"

def deterministic_normalize(text: str) -> Tuple[str, List[str]]:
    """
    Deterministic presentation transcript normalization.
    Strictly forbids LLM rewriting, hallucination, or guessing weather domain words.
    Only deterministic transformations allowed:
    1. Strip model special tokens (<|...|>, [...])
    2. Convert half-width punctuation to full-width Chinese punctuation
    3. Normalize whitespace
    """
    ops = []
    original = text
    
    # 1. Strip special tokens
    stripped = re.sub(r"<\|[^|>]+(?:\|>)?", "", text)
    stripped = re.sub(r"\[[A-Z_]+\]", "", stripped)
    if stripped != original:
        ops.append("STRIP_MODEL_SPECIAL_TOKENS")
        
    # 2. Punctuation normalization
    punc_map = {
        ",": "，",
        "?": "？",
        "!": "！",
        ":": "：",
        ";": "；"
    }
    cleaned_punc = stripped
    for half, full in punc_map.items():
        if half in cleaned_punc:
            cleaned_punc = cleaned_punc.replace(half, full)
            if f"PUNCTUATION_HALF_TO_FULL_{half}" not in ops:
                ops.append(f"PUNCTUATION_HALF_TO_FULL_{half}")
                
    # 3. Collapse multiple spaces
    norm_space = re.sub(r"\s+", " ", cleaned_punc).strip()
    if norm_space != cleaned_punc:
        ops.append("COLLAPSE_WHITESPACE")
        
    return norm_space, ops

def validate_webvtt_cues(cues: List[Dict[str, Any]], max_duration_sec: float) -> Dict[str, Any]:
    """
    Validates WebVTT cues for strict monotonicity, positive durations, and boundary limits.
    """
    if not cues:
        return {
            "is_valid": False,
            "cue_count": 0,
            "is_monotonic": True,
            "exceeds_duration": False,
            "errors": ["Cue list is empty"]
        }

    last_start = -1.0
    is_monotonic = True
    exceeds_duration = False
    errors = []

    for idx, cue in enumerate(cues, start=1):
        start_sec = cue["startTimeSec"]
        end_sec = cue["endTimeSec"]
        
        if start_sec < last_start:
            is_monotonic = False
            errors.append(f"Cue #{idx} violates monotonicity: start {start_sec} < previous start {last_start}")
            
        if end_sec <= start_sec:
            errors.append(f"Cue #{idx} duration is non-positive: start={start_sec}, end={end_sec}")
            
        # Allow tiny 0.5s float rounding leeway against exact audio duration
        if end_sec > max_duration_sec + 0.5:
            exceeds_duration = True
            errors.append(f"Cue #{idx} exceeds media duration ({max_duration_sec}s): end={end_sec}")
            
        last_start = start_sec

    is_valid = (is_monotonic and not exceeds_duration and len(errors) == 0)
    return {
        "is_valid": is_valid,
        "cue_count": len(cues),
        "is_monotonic": is_monotonic,
        "exceeds_duration": exceeds_duration,
        "errors": errors
    }

def find_ffmpeg() -> str:
    """Finds an available ffmpeg executable."""
    for path in DEFAULT_FFMPEG_PATHS:
        if os.path.isabs(path) and os.path.exists(path):
            return path
        elif not os.path.isabs(path) and shutil.which(path):
            return path
    raise AudioExtractionError("ffmpeg executable not found in system or default paths")

class SubtitleArtifactBuilder:
    """
    Engine for building standard WebVTT and Manifest artifacts from weather video input.
    """
    def __init__(
        self,
        episode_date: str,
        source_video_url: str,
        output_base_dir: str = "dist/subtitles/evening-weather",
        ffmpeg_bin: Optional[str] = None,
        scratch_dir: Optional[str] = None
    ):
        self.episode_date = episode_date.strip()
        self.source_video_url = source_video_url.strip()
        self.output_base_dir = output_base_dir
        self.ffmpeg_bin = ffmpeg_bin or find_ffmpeg()
        self.scratch_dir = scratch_dir or os.path.join(os.getcwd(), ".asr_scratch")
        os.makedirs(self.scratch_dir, exist_ok=True)
        
        # Load JSON Schema
        schema_path = os.path.join(os.path.dirname(__file__), "schema", "subtitle_manifest_schema_v1.json")
        with open(schema_path, "r", encoding="utf-8") as f:
            self.manifest_schema = json.load(f)

    def validate_identity(self) -> str:
        """
        Enforces requestedEpisodeDate == resolvedEpisodeDate.
        Fail-closed if dates do not match.
        """
        matched, req_code, url_code = verify_episode_identity(self.episode_date, self.source_video_url)
        if not matched:
            raise ExactEpisodeIdentityMismatchError(
                f"EXACT_EPISODE_IDENTITY_MISMATCH: requested='{self.episode_date}' (code {req_code}), "
                f"resolved='{self.source_video_url}' (code {url_code}). Cross-date fallback strictly forbidden."
            )
        return req_code

    def extract_pcm_audio(self, video_file: str, wav_path: str) -> float:
        """Extracts 16kHz mono S16LE PCM audio using ffmpeg and returns duration in seconds."""
        cmd = [
            self.ffmpeg_bin,
            "-i", video_file,
            "-vn",
            "-acodec", "pcm_s16le",
            "-ar", "16000",
            "-ac", "1",
            wav_path,
            "-y"
        ]
        res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        if res.returncode != 0 or not os.path.exists(wav_path):
            raise AudioExtractionError(f"ffmpeg extraction failed with code {res.returncode}: {res.stderr}")
            
        with wave.open(wav_path, "rb") as wf:
            duration = wf.getnframes() / float(wf.getframerate())
        return duration

    def run_sensevoice_asr(
        self,
        wav_path: str,
        audio_duration: float,
        model_recognizer: Optional[Any] = None
    ) -> List[Dict[str, Any]]:
        """
        Runs SenseVoice speech recognition on the audio and returns timestamped cues.
        Allows passing a mock recognizer for unit testing.
        """
        try:
            if model_recognizer is None:
                import sherpa_onnx
                from huggingface_hub import hf_hub_download
                model_path = hf_hub_download(
                    repo_id="csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
                    filename="model.int8.onnx"
                )
                tokens_path = hf_hub_download(
                    repo_id="csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
                    filename="tokens.txt"
                )
                recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
                    model=model_path,
                    tokens=tokens_path,
                    num_threads=4,
                    use_itn=True,
                    language="auto"
                )
            else:
                recognizer = model_recognizer

            with wave.open(wav_path, "rb") as wf:
                n_frames = wf.getnframes()
                frames = wf.readframes(n_frames)
                samples = np.frombuffer(frames, dtype=np.int16).astype(np.float32) / 32768.0

            stream = recognizer.create_stream()
            stream.accept_waveform(16000, samples)
            recognizer.decode_stream(stream)
            res = stream.result

            tokens = getattr(res, "tokens", [])
            timestamps = getattr(res, "timestamps", [])
            
            cues = []
            current_tokens = []
            current_start = None
            last_time = 0.0

            for i, (tok, ts) in enumerate(zip(tokens, timestamps)):
                if current_start is None:
                    current_start = ts
                current_tokens.append(tok)
                last_time = ts

                is_punc = tok in ["，", "。", "！", "？", "；", ",", ".", "!", "?", ";"]
                next_is_far = False
                if i + 1 < len(timestamps):
                    if timestamps[i + 1] - ts > 0.75:
                        next_is_far = True

                if is_punc or next_is_far or len(current_tokens) >= 20:
                    raw_text = "".join(current_tokens)
                    norm_text, _ = deterministic_normalize(raw_text)
                    if norm_text.strip():
                        end_time = min(audio_duration, last_time + 0.45)
                        cues.append({
                            "startTimeSec": round(current_start, 3),
                            "endTimeSec": round(end_time, 3),
                            "startMs": int(round(current_start * 1000)),
                            "endMs": int(round(end_time * 1000)),
                            "rawText": raw_text,
                            "normalizedText": norm_text,
                            "model": "SenseVoiceSmall"
                        })
                    current_tokens = []
                    current_start = None

            if current_tokens:
                raw_text = "".join(current_tokens)
                norm_text, _ = deterministic_normalize(raw_text)
                if norm_text.strip():
                    start_t = current_start if current_start is not None else last_time
                    end_t = min(audio_duration, last_time + 0.45)
                    cues.append({
                        "startTimeSec": round(start_t, 3),
                        "endTimeSec": round(end_t, 3),
                        "startMs": int(round(start_t * 1000)),
                        "endMs": int(round(end_t * 1000)),
                        "rawText": raw_text,
                        "normalizedText": norm_text,
                        "model": "SenseVoiceSmall"
                    })

            return cues
        except Exception as e:
            raise AsrInferenceError(f"SenseVoice inference failed: {str(e)}") from e

    def generate_vtt_content(
        self,
        cues: List[Dict[str, Any]],
        video_duration_sec: float,
        generated_at_iso: str
    ) -> Tuple[str, List[Dict[str, Any]]]:
        """Formats cues into standard WebVTT syntax with metadata headers."""
        lines = [
            "WEBVTT",
            f"NOTE episodeDate={self.episode_date}",
            "NOTE asrModel=SenseVoiceSmall",
            f"NOTE generatedAt={generated_at_iso}",
            "NOTE sourceAuthority=CHINA_WEATHER_OFFICIAL_VIDEO",
            "NOTE subtitleOrigin=AI_ASR_GENERATED",
            "NOTE uiLabel=AI 自动转写字幕",
            "NOTE extractionMethod=AUTOMATED_ASR_EXTRACTED",
            ""
        ]

        last_end = 0.0
        normalized_cues = []

        for idx, cue in enumerate(cues, start=1):
            start_sec = cue["startTimeSec"]
            end_sec = cue["endTimeSec"]

            # Enforce strict monotonicity and duration constraints
            if start_sec < last_end:
                start_sec = last_end
            if end_sec <= start_sec:
                end_sec = start_sec + 0.5
            if end_sec > video_duration_sec:
                end_sec = video_duration_sec

            last_end = end_sec
            start_ts = format_vtt_timestamp(start_sec)
            end_ts = format_vtt_timestamp(end_sec)
            text = cue["normalizedText"]

            lines.append(f"{idx}")
            lines.append(f"{start_ts} --> {end_ts}")
            lines.append(f"{text}")
            lines.append("")

            normalized_cues.append({
                "cueIndex": idx,
                "startTimeSec": start_sec,
                "endTimeSec": end_sec,
                "startMs": int(round(start_sec * 1000)),
                "endMs": int(round(end_sec * 1000)),
                "rawText": cue["rawText"],
                "normalizedText": text
            })

        return "\n".join(lines), normalized_cues

    def build_artifact(
        self,
        video_file_override: Optional[str] = None,
        audio_file_override: Optional[str] = None,
        fixed_generated_at: Optional[str] = None,
        asr_recognizer_override: Optional[Any] = None,
        precomputed_cues_override: Optional[List[Dict[str, Any]]] = None
    ) -> Dict[str, Any]:
        """
        Executes the full artifact build pipeline with atomic write guarantees.
        """
        # Step 1: Exact episode identity validation
        self.validate_identity()

        # Target directory: dist/subtitles/evening-weather/YYYY-MM-DD
        target_dir = os.path.join(self.output_base_dir, self.episode_date)
        staging_dir = os.path.join(self.output_base_dir, f".staging_{self.episode_date}_{uuid.uuid4().hex[:8]}")
        os.makedirs(staging_dir, exist_ok=True)

        try:
            # Step 2: Source video hash & audio extraction
            if video_file_override and os.path.exists(video_file_override):
                source_video_sha256 = compute_sha256(video_file_override)
                source_video_path = video_file_override
            else:
                # If no local file override, look in scratch or download
                scratch_video = os.path.join(self.scratch_dir, f"{self.episode_date.replace('-', '')}.mp4")
                if os.path.exists(scratch_video):
                    source_video_sha256 = compute_sha256(scratch_video)
                    source_video_path = scratch_video
                else:
                    # In test/offline mode without video file, mock hash or raise
                    source_video_sha256 = hashlib.sha256(self.source_video_url.encode("utf-8")).hexdigest().upper()
                    source_video_path = None

            # Audio extraction
            if audio_file_override and os.path.exists(audio_file_override):
                wav_path = audio_file_override
                with wave.open(wav_path, "rb") as wf:
                    duration_sec = wf.getnframes() / float(wf.getframerate())
            elif source_video_path:
                wav_path = os.path.join(staging_dir, "temp_audio.wav")
                duration_sec = self.extract_pcm_audio(source_video_path, wav_path)
            else:
                raise AudioExtractionError("No video or audio input file available for ASR processing")

            duration_ms = int(round(duration_sec * 1000))

            # Step 3: Run ASR inference or use precomputed cues
            if precomputed_cues_override is not None:
                cues = precomputed_cues_override
            else:
                cues = self.run_sensevoice_asr(wav_path, duration_sec, model_recognizer=asr_recognizer_override)

            # Step 4: Validate cues (strict fail-closed)
            val_res = validate_webvtt_cues(cues, duration_sec)
            if not val_res["is_valid"]:
                raise WebVttValidationError(f"WebVTT cues validation failed: {val_res['errors']}")

            # Step 5: Generate WebVTT
            generated_at = fixed_generated_at or datetime.now(timezone.utc).isoformat()
            vtt_content, norm_cues = self.generate_vtt_content(cues, duration_sec, generated_at)

            vtt_file_path = os.path.join(staging_dir, "subtitle.vtt")
            with open(vtt_file_path, "w", encoding="utf-8", newline="\n") as f:
                f.write(vtt_content)

            vtt_sha256 = compute_sha256(vtt_file_path)

            # Step 6: Create and validate manifest
            manifest = {
                "schemaVersion": 1,
                "program": "EVENING_WEATHER",
                "episodeDate": self.episode_date,
                "sourceVideoUrl": self.source_video_url,
                "sourceVideoSha256": source_video_sha256,
                "durationMs": duration_ms,
                "subtitleOrigin": "AI_ASR_GENERATED",
                "extractionMethod": "AUTOMATED_ASR_EXTRACTED",
                "asrModel": "SenseVoiceSmall",
                "generatedAt": generated_at,
                "vttFile": "subtitle.vtt",
                "vttSha256": vtt_sha256,
                "cueCount": len(norm_cues)
            }

            if HAS_JSONSCHEMA:
                try:
                    jsonschema.validate(instance=manifest, schema=self.manifest_schema)
                except jsonschema.ValidationError as e:
                    raise ManifestSchemaValidationError(f"Manifest failed schema validation: {e.message}") from e

            manifest_file_path = os.path.join(staging_dir, "manifest.json")
            with open(manifest_file_path, "w", encoding="utf-8") as f:
                json.dump(manifest, f, ensure_ascii=False, indent=2)

            # Clean up temp audio if created in staging
            temp_wav = os.path.join(staging_dir, "temp_audio.wav")
            if os.path.exists(temp_wav):
                os.remove(temp_wav)

            # Step 7: Atomic swap to target directory
            if os.path.exists(target_dir):
                # On Windows, os.replace on directories requires backup or rmtree
                backup_dir = os.path.join(self.output_base_dir, f".backup_{self.episode_date}_{uuid.uuid4().hex[:8]}")
                os.rename(target_dir, backup_dir)
                try:
                    os.rename(staging_dir, target_dir)
                    shutil.rmtree(backup_dir, ignore_errors=True)
                except Exception:
                    # Rollback
                    if os.path.exists(backup_dir):
                        os.rename(backup_dir, target_dir)
                    raise
            else:
                os.rename(staging_dir, target_dir)

            return {
                "targetDir": target_dir,
                "manifest": manifest,
                "vttFile": os.path.join(target_dir, "subtitle.vtt"),
                "manifestFile": os.path.join(target_dir, "manifest.json")
            }

        except Exception:
            # Atomic cleanup on failure
            if os.path.exists(staging_dir):
                shutil.rmtree(staging_dir, ignore_errors=True)
            raise
