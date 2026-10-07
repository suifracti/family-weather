"""Static artifact publishing targets for subtitle release artifacts.

The ASR pipeline creates a standard local artifact first.  This module only
publishes an already-created artifact; it never downloads a video and never
invokes ASR.  The Tencent COS SDK is imported lazily so local generation and
local static publishing do not require COS dependencies or credentials.
"""

from __future__ import annotations

import json
import hashlib
import logging
import os
import re
import time
import urllib.error
import urllib.request
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Dict, Mapping, Optional, Protocol

from tools.asr.builder import HAS_JSONSCHEMA, compute_sha256


PROGRAM_SLUG = "evening-weather"
OBJECT_PREFIX = f"subtitles/{PROGRAM_SLUG}"
INDEX_KEY = f"{OBJECT_PREFIX}/index.json"
DATE_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}$")
BUCKET_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]*-\d+$")
REGION_PATTERN = re.compile(r"^[a-z0-9-]+$")

JSON_CONTENT_TYPE = "application/json; charset=utf-8"
VTT_CONTENT_TYPE = "text/vtt; charset=utf-8"
DATE_CACHE_CONTROL = "public, max-age=31536000, immutable"
INDEX_CACHE_CONTROL = "no-cache, max-age=60"
CONTENT_DISPOSITION = "inline"


class ArtifactPublishingError(Exception):
    """Base class for safe, user-facing publishing errors."""


class ArtifactValidationError(ArtifactPublishingError):
    """The local or remote artifact does not satisfy the v1 contract."""


class PublisherConfigurationError(ArtifactPublishingError):
    """The selected publishing target is not configured."""


class CosSdkUnavailableError(PublisherConfigurationError):
    """The optional Tencent COS Python SDK is not installed."""


class RevisionConflictError(ArtifactPublishingError):
    """A complete remote date already contains a different artifact."""


class PublicReadbackError(ArtifactPublishingError):
    """Unauthenticated public HTTPS verification failed."""


class ObjectAlreadyExistsError(ArtifactPublishingError):
    """The COS service rejected a no-overwrite upload because the object exists."""


class LocalPublisherLockBusyError(ArtifactPublishingError):
    """Another instance of this local publisher already owns the COS lock."""


class IndexUpdateRequiresSingleWriterError(ArtifactPublishingError):
    """Updating an existing index requires an explicit single-writer assertion."""


class IndexWriteOutcomeUnknownError(ArtifactPublishingError):
    """The index write may have succeeded; inspect saved bytes before retrying."""


@dataclass(frozen=True)
class PublicHttpResponse:
    """Small response value used by the real and mocked public GET clients."""

    status_code: int
    headers: Mapping[str, str]
    body: bytes


@dataclass(frozen=True)
class StandardSubtitleArtifact:
    """A validated, existing v1 subtitle artifact for one exact date."""

    root_dir: Path
    episode_date: str
    episode_dir: Path
    manifest_path: Path
    vtt_path: Path
    manifest: Dict[str, Any]
    manifest_bytes: bytes
    vtt_bytes: bytes
    vtt_sha256: str

    @property
    def manifest_key(self) -> str:
        return f"{OBJECT_PREFIX}/{self.episode_date}/manifest.json"

    @property
    def vtt_key(self) -> str:
        return f"{OBJECT_PREFIX}/{self.episode_date}/subtitle.vtt"


@dataclass(frozen=True)
class RemoteObject:
    key: str
    url: str
    response: PublicHttpResponse
    content_type: str
    content_disposition: str
    cache_control: str


class ArtifactPublisher(Protocol):
    """Common target interface used by the pipeline and direct CLI path."""

    def publish(self, artifact_root_dir: str, episode_date: str) -> Dict[str, Any]:
        ...


def _header(headers: Mapping[str, str], name: str) -> str:
    """Gets a response header case-insensitively without logging its value."""

    wanted = name.lower()
    for key, value in headers.items():
        if str(key).lower() == wanted:
            return str(value)
    return ""


def _base_content_type(value: str) -> str:
    return value.split(";", 1)[0].strip().lower()


def _safe_exception_type(error: BaseException) -> str:
    """Return only an exception type, never a signed URL or secret-bearing text."""

    return type(error).__name__


@contextmanager
def _quiet_cos_sdk_logs():
    """Prevent SDK debug/error handlers from emitting signed request details."""

    sdk_logger = logging.getLogger("qcloud_cos")
    previous_level = sdk_logger.level
    previous_disabled = sdk_logger.disabled
    sdk_logger.setLevel(logging.CRITICAL + 1)
    sdk_logger.disabled = False
    try:
        yield
    finally:
        sdk_logger.setLevel(previous_level)
        sdk_logger.disabled = previous_disabled


def _validate_episode_date(episode_date: str) -> str:
    if not isinstance(episode_date, str) or not DATE_PATTERN.fullmatch(episode_date):
        raise ArtifactValidationError(
            f"INVALID_EPISODE_DATE: expected YYYY-MM-DD, got {episode_date!r}"
        )
    try:
        datetime.strptime(episode_date, "%Y-%m-%d")
    except ValueError as error:
        raise ArtifactValidationError(f"INVALID_EPISODE_DATE: {episode_date}") from None
    return episode_date


def _schema_path() -> Path:
    return Path(__file__).resolve().parent / "schema" / "subtitle_manifest_schema_v1.json"


def load_standard_artifact(artifact_root_dir: str, episode_date: str) -> StandardSubtitleArtifact:
    """Load and fail-closed validate one existing standard artifact.

    Validation is deliberately performed before any remote write.  The
    current v1 schema is reused unchanged; this module does not introduce a
    second manifest schema or a different object layout.
    """

    episode_date = _validate_episode_date(episode_date)
    root_dir = Path(artifact_root_dir).resolve()
    episode_dir = root_dir / episode_date
    manifest_path = episode_dir / "manifest.json"
    vtt_path = episode_dir / "subtitle.vtt"

    if not manifest_path.is_file() or not vtt_path.is_file():
        raise ArtifactValidationError(
            f"ARTIFACT_MISSING: expected {episode_date}/manifest.json and subtitle.vtt"
        )

    try:
        manifest_bytes = manifest_path.read_bytes()
        vtt_bytes = vtt_path.read_bytes()
        manifest = json.loads(manifest_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ArtifactValidationError(
            f"ARTIFACT_READ_FAILED: {episode_date} ({_safe_exception_type(error)})"
        ) from None

    if not isinstance(manifest, dict):
        raise ArtifactValidationError("MANIFEST_SCHEMA_INVALID: root must be an object")

    if not HAS_JSONSCHEMA:
        raise ArtifactValidationError(
            "MANIFEST_SCHEMA_VALIDATION_UNAVAILABLE: install the existing jsonschema dependency"
        )

    try:
        import jsonschema

        with _schema_path().open("r", encoding="utf-8") as schema_file:
            schema = json.load(schema_file)
        jsonschema.validate(instance=manifest, schema=schema)
    except Exception as error:
        if isinstance(error, ArtifactValidationError):
            raise
        raise ArtifactValidationError(
            f"MANIFEST_SCHEMA_INVALID: errorType={_safe_exception_type(error)}"
        ) from None

    if manifest.get("episodeDate") != episode_date:
        raise ArtifactValidationError(
            "MANIFEST_DATE_MISMATCH: "
            f"path={episode_date}, manifest={manifest.get('episodeDate')!r}"
        )
    if manifest.get("vttFile") != "subtitle.vtt":
        raise ArtifactValidationError(
            f"MANIFEST_VTT_PATH_INVALID: {manifest.get('vttFile')!r}"
        )
    if not vtt_bytes.startswith(b"WEBVTT"):
        raise ArtifactValidationError("VTT_INVALID: missing WEBVTT header")

    actual_vtt_sha256 = compute_sha256(str(vtt_path))
    expected_vtt_sha256 = str(manifest.get("vttSha256", "")).upper()
    if actual_vtt_sha256 != expected_vtt_sha256:
        raise ArtifactValidationError(
            "VTT_HASH_MISMATCH: "
            f"expected={expected_vtt_sha256}, actual={actual_vtt_sha256}"
        )

    return StandardSubtitleArtifact(
        root_dir=root_dir,
        episode_date=episode_date,
        episode_dir=episode_dir,
        manifest_path=manifest_path,
        vtt_path=vtt_path,
        manifest=manifest,
        manifest_bytes=manifest_bytes,
        vtt_bytes=vtt_bytes,
        vtt_sha256=actual_vtt_sha256,
    )


def build_index(episode_date: str, updated_at: Optional[str] = None) -> Dict[str, Any]:
    """Build the unchanged lightweight index, pinned to the validated date."""

    episode_date = _validate_episode_date(episode_date)
    return {
        "schemaVersion": 1,
        "latestEpisodeDate": episode_date,
        "latestManifestUrl": f"/{OBJECT_PREFIX}/{episode_date}/manifest.json",
        "updatedAt": updated_at or datetime.now(timezone.utc).isoformat(),
    }


def update_index_preserving_fields(
    episode_date: str,
    existing_index: Optional[Mapping[str, Any]] = None,
    updated_at: Optional[str] = None,
) -> Dict[str, Any]:
    """Advance the pointer while retaining any unrelated existing index fields."""

    updated = dict(existing_index or {})
    updated.update(build_index(episode_date, updated_at))
    return updated


def _json_bytes(value: Mapping[str, Any]) -> bytes:
    return json.dumps(value, ensure_ascii=False, indent=2).encode("utf-8")


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temp_path = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    try:
        temp_path.write_bytes(data)
        os.replace(str(temp_path), str(path))
    finally:
        if temp_path.exists():
            temp_path.unlink()


@contextmanager
def _local_publisher_mutex(lock_path: Path):
    """Serialize this publisher on one machine; it does not lock other hosts."""

    lock_path.parent.mkdir(parents=True, exist_ok=True)
    handle = lock_path.open("a+b")
    acquired = False
    try:
        if os.name == "nt":
            import msvcrt

            handle.seek(0, os.SEEK_END)
            if handle.tell() == 0:
                handle.write(b"\0")
                handle.flush()
            handle.seek(0)
            try:
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
            except OSError:
                raise LocalPublisherLockBusyError("LOCAL_PUBLISH_LOCK_BUSY") from None
            acquired = True
        else:
            import fcntl

            try:
                fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                raise LocalPublisherLockBusyError("LOCAL_PUBLISH_LOCK_BUSY") from None
            acquired = True
        yield
    finally:
        if acquired:
            if os.name == "nt":
                import msvcrt

                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl

                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)
        handle.close()


def _write_immutable_snapshot(path: Path, body: bytes) -> None:
    """Persist exact pre-write/candidate bytes without replacing prior evidence."""

    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("xb") as output:
            output.write(body)
            output.flush()
            os.fsync(output.fileno())
    except FileExistsError:
        if path.read_bytes() != body:
            raise ArtifactValidationError("PUBLISH_SNAPSHOT_CONFLICT") from None


def _parse_manifest_contract(body: bytes, expected_date: str) -> Dict[str, Any]:
    """Parse a remote manifest without preserving parser/validator details."""

    try:
        manifest = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ValueError("manifest JSON is invalid") from None
    if not isinstance(manifest, dict):
        raise ValueError("manifest root is not an object")
    if not HAS_JSONSCHEMA:
        raise ValueError("manifest schema validator is unavailable")
    try:
        import jsonschema

        with _schema_path().open("r", encoding="utf-8") as schema_file:
            schema = json.load(schema_file)
        jsonschema.validate(instance=manifest, schema=schema)
    except Exception:
        raise ValueError("manifest schema is invalid") from None
    if manifest.get("episodeDate") != expected_date:
        raise ValueError("manifest date does not match object path")
    if manifest.get("vttFile") != "subtitle.vtt":
        raise ValueError("manifest VTT path is invalid")
    return manifest


def _parse_index_contract(body: bytes) -> Dict[str, Any]:
    """Parse the small index contract and reject untrusted pointers."""

    try:
        index = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise ValueError("index JSON is invalid") from None
    if not isinstance(index, dict):
        raise ValueError("index root is not an object")
    latest_date = index.get("latestEpisodeDate")
    try:
        _validate_episode_date(latest_date)
    except ArtifactValidationError:
        raise ValueError("index date is invalid") from None
    if index.get("schemaVersion") != 1:
        raise ValueError("index schema version is invalid")
    if index.get("latestManifestUrl") != f"/{OBJECT_PREFIX}/{latest_date}/manifest.json":
        raise ValueError("index manifest reference is invalid")
    if not isinstance(index.get("updatedAt"), str) or not index["updatedAt"].strip():
        raise ValueError("index updatedAt is invalid")
    return index


def write_safe_report(report_path: str, report: Mapping[str, Any]) -> None:
    """Persist a publisher result containing only public evidence and hashes."""

    _atomic_write(
        Path(report_path).resolve(),
        (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    )


class LocalStaticPublisher:
    """Validate/publish standard artifacts into a local static directory.

    No COS package, network, or credential is touched by this target.  If a
    source root is different from ``destination_root_dir``, only the standard
    VTT and manifest for the selected date are copied; no unrelated local
    dates are scanned into the index.
    """

    def __init__(self, destination_root_dir: Optional[str] = None):
        self.destination_root_dir = (
            Path(destination_root_dir).resolve() if destination_root_dir else None
        )

    def publish(self, artifact_root_dir: str, episode_date: str) -> Dict[str, Any]:
        artifact = load_standard_artifact(artifact_root_dir, episode_date)
        destination_root = self.destination_root_dir or artifact.root_dir
        destination_episode_dir = destination_root / artifact.episode_date

        if destination_root != artifact.root_dir:
            existing_manifest = destination_episode_dir / "manifest.json"
            existing_vtt = destination_episode_dir / "subtitle.vtt"
            if existing_manifest.exists() and existing_manifest.read_bytes() != artifact.manifest_bytes:
                raise RevisionConflictError(
                    f"REVISION_CONFLICT: local {artifact.episode_date}/manifest.json differs"
                )
            if existing_vtt.exists() and existing_vtt.read_bytes() != artifact.vtt_bytes:
                raise RevisionConflictError(
                    f"REVISION_CONFLICT: local {artifact.episode_date}/subtitle.vtt differs"
                )
            if not existing_vtt.exists():
                _atomic_write(existing_vtt, artifact.vtt_bytes)
            if not existing_manifest.exists():
                _atomic_write(existing_manifest, artifact.manifest_bytes)

        index_path = destination_root / "index.json"
        existing_index = None
        index_action = "UPDATED"
        if index_path.is_file():
            try:
                existing_index = _parse_index_contract(index_path.read_bytes())
            except (OSError, ValueError):
                raise ArtifactValidationError("LOCAL_INDEX_INVALID") from None

            if existing_index["latestEpisodeDate"] > artifact.episode_date:
                try:
                    load_standard_artifact(
                        str(destination_root), existing_index["latestEpisodeDate"]
                    )
                except ArtifactPublishingError:
                    raise ArtifactValidationError(
                        "LOCAL_INDEX_TARGET_UNVERIFIED"
                    ) from None
                index = existing_index
                index_action = "PRESERVED_NEWER"
            else:
                index = update_index_preserving_fields(
                    artifact.episode_date,
                    existing_index,
                )
        else:
            index = update_index_preserving_fields(artifact.episode_date)

        if index_action != "PRESERVED_NEWER":
            _atomic_write(index_path, _json_bytes(index))
        return {
            "localStatus": "LOCAL_ARTIFACT_PUBLISHED",
            "remoteStatus": "NOT_REQUESTED",
            "episodeDate": artifact.episode_date,
            "index": index,
            "indexAction": index_action,
            "rootDir": str(destination_root),
            "artifactHashes": {
                "manifestSha256": hashlib.sha256(artifact.manifest_bytes).hexdigest().upper(),
                "vttSha256": artifact.vtt_sha256,
            },
            "objectKeys": [
                artifact.vtt_key,
                artifact.manifest_key,
                INDEX_KEY,
            ],
        }

    # Explicit alias for callers that want to document that ASR is skipped.
    publish_existing_artifact = publish


def _default_public_get(url: str, timeout: float) -> PublicHttpResponse:
    """Perform a public GET with no COS signature or authorization header."""

    request = urllib.request.Request(
        url,
        headers={
            "Accept": "application/json, text/vtt, */*",
            "User-Agent": "BreezyWeatherSubtitlePublisher/1.0",
        },
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return PublicHttpResponse(
                status_code=int(response.getcode() or 200),
                headers=dict(response.headers.items()),
                body=response.read(),
            )
    except urllib.error.HTTPError as error:
        # HTTPError is also a response for expected 403/404 checks.  Reading
        # only the body does not expose any signed request because this GET is
        # intentionally unsigned.
        try:
            body = error.read()
        except Exception:
            body = b""
        return PublicHttpResponse(
            status_code=int(error.code),
            headers=dict(error.headers.items()) if error.headers else {},
            body=body,
        )


class TencentCosPublisher:
    """Publish validated artifacts to Tencent COS using the optional SDK.

    Uploads use the SDK's signed PutObject calls.  Every verification GET is
    deliberately made by ``public_get`` without credentials, so a signed GET
    can never mask a public-read or endpoint-policy failure.
    """

    RETRYABLE_GET_STATUS_CODES = frozenset({408, 425, 429, 500, 502, 503, 504})
    SDK_VERSION = "1.9.44"

    def __init__(
        self,
        *,
        client: Optional[Any] = None,
        bucket: Optional[str] = None,
        region: Optional[str] = None,
        public_get: Optional[Callable[[str, float], PublicHttpResponse]] = None,
        get_timeout_seconds: float = 15.0,
        max_get_attempts: int = 3,
        retry_backoff_seconds: float = 0.2,
        sleep_fn: Callable[[float], None] = time.sleep,
        single_writer_confirmed: bool = False,
        state_dir: Optional[str] = None,
    ):
        self.bucket = (bucket or os.environ.get("TENCENT_COS_BUCKET", "")).strip()
        self.region = (region or os.environ.get("TENCENT_COS_REGION", "")).strip()
        self._validate_bucket_and_region()

        self.public_get = public_get or _default_public_get
        if get_timeout_seconds <= 0:
            raise PublisherConfigurationError("GET_TIMEOUT_INVALID")
        if max_get_attempts < 1:
            raise PublisherConfigurationError("GET_RETRY_COUNT_INVALID")
        self.get_timeout_seconds = get_timeout_seconds
        self.max_get_attempts = max_get_attempts
        self.retry_backoff_seconds = max(0.0, retry_backoff_seconds)
        self.sleep_fn = sleep_fn
        self.single_writer_confirmed = single_writer_confirmed
        self.state_dir = (
            Path(state_dir).resolve()
            if state_dir
            else Path(__file__).resolve().parents[2]
            / "build"
            / "cctv-pilot"
            / "publish-state"
        )
        self.client = client or self._create_cos_client_from_environment()

    @property
    def endpoint(self) -> str:
        # COS virtual-hosted style: <Bucket-Appid>.cos.<Region>.myqcloud.com
        return f"https://{self.bucket}.cos.{self.region}.myqcloud.com"

    def object_url(self, key: str) -> str:
        normalized_key = key.lstrip("/")
        if normalized_key.startswith(f"{OBJECT_PREFIX}/") or normalized_key == INDEX_KEY:
            return f"{self.endpoint}/{normalized_key}"
        raise ArtifactPublishingError(
            f"OBJECT_PATH_INVALID: expected prefix {OBJECT_PREFIX}/"
        )

    def _validate_bucket_and_region(self) -> None:
        if not BUCKET_PATTERN.fullmatch(self.bucket):
            raise PublisherConfigurationError(
                "COS_BUCKET_INVALID: expected BucketName-AppId from TENCENT_COS_BUCKET"
            )
        if not REGION_PATTERN.fullmatch(self.region):
            raise PublisherConfigurationError(
                "COS_REGION_INVALID: expected region such as ap-guangzhou"
            )

    @staticmethod
    def _read_secret_environment() -> Dict[str, str]:
        required = {
            "TENCENT_CLOUD_SECRET_ID": os.environ.get("TENCENT_CLOUD_SECRET_ID", ""),
            "TENCENT_CLOUD_SECRET_KEY": os.environ.get("TENCENT_CLOUD_SECRET_KEY", ""),
        }
        missing = [name for name, value in required.items() if not value.strip()]
        if missing:
            raise PublisherConfigurationError(
                "COS_CREDENTIALS_MISSING: " + ", ".join(missing)
            )

        token = ""
        for token_name in (
            "TENCENT_CLOUD_SESSION_TOKEN",
            "TENCENT_CLOUD_TOKEN",
            "TENCENTCLOUD_SESSION_TOKEN",
        ):
            candidate = os.environ.get(token_name, "").strip()
            if candidate:
                token = candidate
                break
        return {
            "secret_id": required["TENCENT_CLOUD_SECRET_ID"],
            "secret_key": required["TENCENT_CLOUD_SECRET_KEY"],
            "token": token,
        }

    def _create_cos_client_from_environment(self) -> Any:
        credentials = self._read_secret_environment()
        try:
            from qcloud_cos import CosConfig, CosS3Client
        except ImportError as error:
            raise CosSdkUnavailableError(
                f"COS_SDK_MISSING: install cos-python-sdk-v5=={self.SDK_VERSION}"
            ) from None

        config_kwargs = {
            "Region": self.region,
            "SecretId": credentials["secret_id"],
            "SecretKey": credentials["secret_key"],
            "Scheme": "https",
        }
        if credentials["token"]:
            config_kwargs["Token"] = credentials["token"]
        return CosS3Client(CosConfig(**config_kwargs))

    def _public_get_with_retry(self, url: str, key: str) -> PublicHttpResponse:
        last_error_type = "unknown"
        for attempt in range(1, self.max_get_attempts + 1):
            try:
                response = self.public_get(url, self.get_timeout_seconds)
            except Exception as error:
                last_error_type = _safe_exception_type(error)
                if attempt >= self.max_get_attempts:
                    raise PublicReadbackError(
                        f"PUBLIC_GET_NETWORK_FAILED: key={key}, errorType={last_error_type}"
                    ) from None
                self.sleep_fn(self.retry_backoff_seconds * attempt)
                continue

            if response.status_code in self.RETRYABLE_GET_STATUS_CODES:
                if attempt >= self.max_get_attempts:
                    raise PublicReadbackError(
                        f"PUBLIC_GET_RETRIES_EXHAUSTED: key={key}, status={response.status_code}"
                    )
                self.sleep_fn(self.retry_backoff_seconds * attempt)
                continue
            return response

        raise PublicReadbackError(f"PUBLIC_GET_FAILED: key={key}")

    def _fetch_object(
        self,
        key: str,
        expected_content_type: str,
    ) -> Optional[RemoteObject]:
        url = self.object_url(key)
        response = self._public_get_with_retry(url, key)
        if response.status_code in (404, 410):
            return None
        if response.status_code == 403:
            raise PublicReadbackError(f"PUBLIC_GET_FORBIDDEN: key={key}")
        if response.status_code != 200:
            raise PublicReadbackError(
                f"PUBLIC_GET_UNEXPECTED_STATUS: key={key}, status={response.status_code}"
            )

        actual_content_type = _header(response.headers, "Content-Type")
        if _base_content_type(actual_content_type) != _base_content_type(expected_content_type):
            raise PublicReadbackError(
                "PUBLIC_RESPONSE_CONTENT_TYPE_MISMATCH: "
                f"key={key}, expected={_base_content_type(expected_content_type)}, "
                f"actual={_base_content_type(actual_content_type) or '<missing>'}"
            )

        return RemoteObject(
            key=key,
            url=url,
            response=response,
            content_type=actual_content_type,
            content_disposition=_header(response.headers, "Content-Disposition"),
            cache_control=_header(response.headers, "Cache-Control"),
        )

    @staticmethod
    def _safe_sdk_response(response: Any) -> Dict[str, str]:
        if isinstance(response, Mapping):
            source = response
        else:
            source = getattr(response, "headers", None)
        if not isinstance(source, Mapping):
            return {}
        details: Dict[str, str] = {}
        for name in ("ETag", "x-cos-request-id", "x-cos-version-id"):
            if name in source:
                details[name] = str(source[name])
        return details

    @staticmethod
    def _is_object_already_exists(error: BaseException) -> bool:
        status = getattr(error, "status_code", None)
        if status is None:
            status = getattr(error, "status", None)
        for method_name in ("get_status_code", "get_error_code"):
            method = getattr(error, method_name, None)
            if callable(method):
                try:
                    value = method()
                except Exception:
                    value = None
                if method_name == "get_status_code" and status is None:
                    status = value
                if method_name == "get_error_code" and value:
                    error_code = str(value)
                    if error_code in {
                        "FileAlreadyExists",
                        "ObjectAlreadyExists",
                        "AlreadyExists",
                    }:
                        return True
        if status is not None:
            try:
                if int(status) == 409:
                    return True
            except (TypeError, ValueError):
                pass
        for name in ("error_code", "code"):
            value = getattr(error, name, None)
            if value in {"FileAlreadyExists", "ObjectAlreadyExists", "AlreadyExists"}:
                return True
        return False

    def _put_object_with_forbid_header(
        self,
        *,
        key: str,
        body: bytes,
        request_metadata: Mapping[str, str],
    ) -> Any:
        """Use the pinned SDK request/auth path with COS's real header.

        cos-python-sdk-v5==1.9.44 exposes arbitrary PUT headers through the
        internal request layer, while its public ``put_object`` kwarg map does
        not expose ``x-cos-forbid-overwrite``.  This follows that SDK's own
        ``put_object`` implementation (CosS3Auth + send_request) so the real
        service header is signed; it does not invent a fake SDK parameter.
        """

        try:
            from qcloud_cos.cos_auth import CosS3Auth
        except ImportError:
            raise RuntimeError("COS SDK auth adapter unavailable") from None

        config = getattr(self.client, "_conf", None)
        send_request = getattr(self.client, "send_request", None)
        uri = getattr(config, "uri", None)
        if config is None or not callable(send_request) or not callable(uri):
            raise RuntimeError("COS SDK forbid-overwrite adapter unsupported")

        headers = dict(request_metadata)
        headers["x-cos-forbid-overwrite"] = "true"
        return send_request(
            method="PUT",
            url=uri(bucket=self.bucket, path=key),
            bucket=self.bucket,
            auth=CosS3Auth(config, key),
            data=body,
            headers=headers,
        )

    def _put_object(
        self,
        *,
        key: str,
        body: bytes,
        content_type: str,
        cache_control: str,
        forbid_overwrite: bool = False,
    ) -> Dict[str, Any]:
        request_metadata = {
            "Content-Type": content_type,
            "Cache-Control": cache_control,
            "Content-Disposition": CONTENT_DISPOSITION,
        }
        if forbid_overwrite:
            request_metadata["x-cos-forbid-overwrite"] = "true"
        try:
            with _quiet_cos_sdk_logs():
                if forbid_overwrite and hasattr(self.client, "_conf") and callable(
                    getattr(self.client, "send_request", None)
                ):
                    response = self._put_object_with_forbid_header(
                        key=key,
                        body=body,
                        request_metadata=request_metadata,
                    )
                else:
                    kwargs = {
                        "Bucket": self.bucket,
                        "Key": key,
                        "Body": body,
                        "ContentType": content_type,
                        "CacheControl": cache_control,
                        "ContentDisposition": CONTENT_DISPOSITION,
                    }
                    # This branch is for the injected test double.  A real
                    # qcloud_cos client takes the signed raw-header branch above.
                    if forbid_overwrite:
                        kwargs["ForbidOverwrite"] = "true"
                    response = self.client.put_object(**kwargs)
        except Exception as error:
            if forbid_overwrite and self._is_object_already_exists(error):
                raise ObjectAlreadyExistsError(
                    f"COS_OBJECT_ALREADY_EXISTS: key={key}"
                ) from None
            raise ArtifactPublishingError(
                f"COS_UPLOAD_FAILED: key={key}, errorType={_safe_exception_type(error)}"
            ) from None
        return {
            "key": key,
            "requestMetadata": request_metadata,
            "sdkResponse": self._safe_sdk_response(response),
        }

    def _validate_remote_manifest(
        self,
        artifact: StandardSubtitleArtifact,
        remote_manifest: RemoteObject,
    ) -> Dict[str, Any]:
        try:
            manifest = _parse_manifest_contract(
                remote_manifest.response.body,
                artifact.episode_date,
            )
        except ValueError as error:
            raise RevisionConflictError(
                f"REVISION_CONFLICT: remote {error}"
            ) from None
        # Existing manifests are compared by complete schema-field semantics.
        # This intentionally catches cueCount, sourceVideoUrl, durationMs,
        # generatedAt, model, and every other client-visible v1 field.
        if manifest != artifact.manifest:
            raise RevisionConflictError(
                f"REVISION_CONFLICT: remote manifest fields differ for {artifact.episode_date}"
            )
        return manifest

    def _verify_remote_artifact(
        self,
        artifact: StandardSubtitleArtifact,
        remote_manifest: RemoteObject,
        remote_vtt: RemoteObject,
    ) -> None:
        self._validate_remote_manifest(artifact, remote_manifest)
        if remote_vtt.response.body != artifact.vtt_bytes:
            actual = hashlib.sha256(remote_vtt.response.body).hexdigest().upper()
            raise RevisionConflictError(
                "REVISION_CONFLICT: "
                f"remote VTT bytes differ for {artifact.episode_date} (actual={actual})"
            )
        actual_vtt_sha256 = hashlib.sha256(remote_vtt.response.body).hexdigest().upper()
        if actual_vtt_sha256 != artifact.vtt_sha256:
            raise PublicReadbackError(f"PUBLIC_VTT_HASH_MISMATCH: key={artifact.vtt_key}")

    def _verify_index_target(self, index_data: Mapping[str, Any]) -> list[str]:
        """Verify the date currently named by a remote index before preserving it."""

        target_date = str(index_data["latestEpisodeDate"])
        manifest_key = f"{OBJECT_PREFIX}/{target_date}/manifest.json"
        vtt_key = f"{OBJECT_PREFIX}/{target_date}/subtitle.vtt"
        remote_manifest = self._fetch_object(manifest_key, JSON_CONTENT_TYPE)
        remote_vtt = self._fetch_object(vtt_key, VTT_CONTENT_TYPE)
        if remote_manifest is None or remote_vtt is None:
            raise PublicReadbackError("PUBLIC_INDEX_TARGET_UNVERIFIED")
        try:
            manifest = _parse_manifest_contract(
                remote_manifest.response.body,
                target_date,
            )
        except ValueError:
            raise PublicReadbackError("PUBLIC_INDEX_TARGET_MANIFEST_INVALID") from None
        if not remote_vtt.response.body.startswith(b"WEBVTT"):
            raise PublicReadbackError("PUBLIC_INDEX_TARGET_VTT_INVALID")
        actual_vtt_sha256 = hashlib.sha256(remote_vtt.response.body).hexdigest().upper()
        if actual_vtt_sha256 != str(manifest.get("vttSha256", "")).upper():
            raise PublicReadbackError("PUBLIC_INDEX_TARGET_VTT_HASH_MISMATCH")
        return [vtt_key, manifest_key]

    def _ensure_vtt(
        self,
        artifact: StandardSubtitleArtifact,
    ) -> tuple[Optional[Dict[str, Any]], RemoteObject]:
        try:
            upload = self._put_object(
                key=artifact.vtt_key,
                body=artifact.vtt_bytes,
                content_type=VTT_CONTENT_TYPE,
                cache_control=DATE_CACHE_CONTROL,
                forbid_overwrite=True,
            )
        except ObjectAlreadyExistsError:
            # A preflight 404 is only a hint.  Re-read after a server-side
            # conflict and accept only identical bytes as an idempotent race.
            raced = self._fetch_object(artifact.vtt_key, VTT_CONTENT_TYPE)
            if raced is None:
                raise PublicReadbackError(
                    f"PUBLIC_VTT_READBACK_MISSING_AFTER_CONFLICT: key={artifact.vtt_key}"
                ) from None
            if raced.response.body != artifact.vtt_bytes:
                raise RevisionConflictError(
                    f"REVISION_CONFLICT: remote VTT bytes differ for {artifact.episode_date}"
                ) from None
            return None, raced

        remote = self._fetch_object(artifact.vtt_key, VTT_CONTENT_TYPE)
        if remote is None:
            raise PublicReadbackError(
                f"PUBLIC_VTT_READBACK_MISSING: key={artifact.vtt_key}"
            )
        if remote.response.body != artifact.vtt_bytes:
            raise PublicReadbackError(
                f"PUBLIC_VTT_READBACK_BYTES_MISMATCH: key={artifact.vtt_key}"
            )
        actual_vtt_sha256 = hashlib.sha256(remote.response.body).hexdigest().upper()
        if actual_vtt_sha256 != artifact.vtt_sha256:
            raise PublicReadbackError(f"PUBLIC_VTT_HASH_MISMATCH: key={artifact.vtt_key}")
        return upload, remote

    def _ensure_manifest(
        self,
        artifact: StandardSubtitleArtifact,
    ) -> tuple[Optional[Dict[str, Any]], RemoteObject]:
        try:
            upload = self._put_object(
                key=artifact.manifest_key,
                body=artifact.manifest_bytes,
                content_type=JSON_CONTENT_TYPE,
                cache_control=DATE_CACHE_CONTROL,
                forbid_overwrite=True,
            )
        except ObjectAlreadyExistsError:
            raced = self._fetch_object(artifact.manifest_key, JSON_CONTENT_TYPE)
            if raced is None:
                raise PublicReadbackError(
                    f"PUBLIC_MANIFEST_READBACK_MISSING_AFTER_CONFLICT: key={artifact.manifest_key}"
                ) from None
            self._validate_remote_manifest(artifact, raced)
            return None, raced

        remote = self._fetch_object(artifact.manifest_key, JSON_CONTENT_TYPE)
        if remote is None:
            raise PublicReadbackError(
                f"PUBLIC_MANIFEST_READBACK_MISSING: key={artifact.manifest_key}"
            )
        # A newly uploaded manifest must be byte-identical.  Existing
        # manifests use complete parsed-field equality, so formatting-only
        # differences are explicitly tolerated only on the preflight/race
        # path above.
        if remote.response.body != artifact.manifest_bytes:
            raise PublicReadbackError(
                f"PUBLIC_MANIFEST_READBACK_BYTES_MISMATCH: key={artifact.manifest_key}"
            )
        actual_manifest_sha256 = hashlib.sha256(remote.response.body).hexdigest().upper()
        expected_manifest_sha256 = hashlib.sha256(artifact.manifest_bytes).hexdigest().upper()
        if actual_manifest_sha256 != expected_manifest_sha256:
            raise PublicReadbackError(
                f"PUBLIC_MANIFEST_HASH_MISMATCH: key={artifact.manifest_key}"
            )
        return upload, remote

    def publish(self, artifact_root_dir: str, episode_date: str) -> Dict[str, Any]:
        lock_id = hashlib.sha256(
            f"{self.bucket}\0{self.region}\0{INDEX_KEY}".encode("utf-8")
        ).hexdigest()
        lock_path = self.state_dir / "locks" / f"{lock_id}.lock"
        with _local_publisher_mutex(lock_path):
            return self._publish_locked(artifact_root_dir, episode_date)

    def _save_index_candidates(
        self,
        episode_date: str,
        original_index_bytes: Optional[bytes],
        candidate_index_bytes: bytes,
    ) -> tuple[str, str]:
        original_hash = (
            hashlib.sha256(original_index_bytes).hexdigest().upper()
            if original_index_bytes is not None
            else "ABSENT"
        )
        candidate_hash = hashlib.sha256(candidate_index_bytes).hexdigest().upper()
        transaction_id = hashlib.sha256(
            f"{episode_date}\0{original_hash}\0{candidate_hash}".encode("utf-8")
        ).hexdigest()[:20]
        snapshot_dir = self.state_dir / "index-snapshots" / episode_date
        if original_index_bytes is None:
            original_path = snapshot_dir / f"index-before-{transaction_id}.absent.json"
            original_bytes = b'{"existed":false}\n'
        else:
            original_path = snapshot_dir / f"index-before-{transaction_id}.json"
            original_bytes = original_index_bytes
        candidate_path = snapshot_dir / f"index-candidate-{transaction_id}.json"
        _write_immutable_snapshot(original_path, original_bytes)
        _write_immutable_snapshot(candidate_path, candidate_index_bytes)
        return str(original_path), str(candidate_path)

    def _publish_locked(self, artifact_root_dir: str, episode_date: str) -> Dict[str, Any]:
        """Publish one existing artifact and verify it through public GETs.

        The VTT is uploaded and publicly verified before the manifest.  The
        index is uploaded only after both date objects are publicly verified.
        This sequence is ordered, not an atomic multi-object transaction.
        """

        artifact = load_standard_artifact(artifact_root_dir, episode_date)
        preflight_index = self._fetch_object(INDEX_KEY, JSON_CONTENT_TYPE)
        if preflight_index is not None:
            try:
                preflight_data = _parse_index_contract(preflight_index.response.body)
            except ValueError as error:
                raise PublicReadbackError(
                    f"PUBLIC_INDEX_INVALID: {error}"
                ) from None
            if (
                preflight_data["latestEpisodeDate"] < artifact.episode_date
                and not self.single_writer_confirmed
            ):
                raise IndexUpdateRequiresSingleWriterError(
                    "INDEX_UPDATE_REQUIRES_SINGLE_WRITER_CONFIRMATION"
                )

        remote_manifest = self._fetch_object(artifact.manifest_key, JSON_CONTENT_TYPE)
        remote_vtt = self._fetch_object(artifact.vtt_key, VTT_CONTENT_TYPE)
        uploaded: list[Dict[str, Any]] = []
        warnings: list[str] = []

        if remote_manifest is not None:
            self._validate_remote_manifest(artifact, remote_manifest)
        if remote_vtt is not None:
            if remote_vtt.response.body != artifact.vtt_bytes:
                actual = hashlib.sha256(remote_vtt.response.body).hexdigest().upper()
                raise RevisionConflictError(
                    "REVISION_CONFLICT: "
                    f"remote VTT bytes differ for {artifact.episode_date} (actual={actual})"
                )
            if remote_vtt.content_disposition.lower().find("attachment") >= 0:
                warnings.append(f"PUBLIC_GET_FORCED_DOWNLOAD: key={artifact.vtt_key}")
        if remote_manifest is not None and remote_manifest.content_disposition.lower().find("attachment") >= 0:
            warnings.append(f"PUBLIC_GET_FORCED_DOWNLOAD: key={artifact.manifest_key}")

        # If a manifest exists it describes the complete date artifact.  A
        # missing VTT can be repaired, but a different existing VTT is a hard
        # conflict and is never overwritten in this first release.  Date
        # objects use the service-side forbid-overwrite request; the initial
        # GETs above are only preflight hints and cannot protect this write.
        if remote_vtt is None:
            upload, remote_vtt = self._ensure_vtt(artifact)
            if upload is not None:
                uploaded.append(upload)

        if remote_manifest is None:
            upload, remote_manifest = self._ensure_manifest(artifact)
            if upload is not None:
                uploaded.append(upload)

        if remote_manifest is None or remote_vtt is None:
            raise PublicReadbackError(
                f"PUBLIC_DATE_ARTIFACT_INCOMPLETE: date={artifact.episode_date}"
            )
        self._verify_remote_artifact(artifact, remote_manifest, remote_vtt)
        warnings.extend(
            warning
            for warning in (
                f"PUBLIC_GET_FORCED_DOWNLOAD: key={artifact.vtt_key}"
                if "attachment" in remote_vtt.content_disposition.lower()
                else "",
                f"PUBLIC_GET_FORCED_DOWNLOAD: key={artifact.manifest_key}"
                if "attachment" in remote_manifest.content_disposition.lower()
                else "",
            )
            if warning and warning not in warnings
        )

        # The exact date just passed public verification.  Re-read the
        # updateable index immediately before deciding whether to advance it.
        # A verified newer pointer is preserved; an untrusted/invalid pointer
        # fails closed.  This is intentionally a single-publisher boundary:
        # another writer can still race after this final GET, so the index is
        # not described as an atomic compare-and-swap transaction.
        remote_index = self._fetch_object(INDEX_KEY, JSON_CONTENT_TYPE)
        index_target_verified_keys: list[str] = []
        index_action = "UPDATED"
        remote_index_data: Dict[str, Any]
        if remote_index is not None:
            try:
                existing_index = _parse_index_contract(remote_index.response.body)
            except ValueError as error:
                raise PublicReadbackError(
                    f"PUBLIC_INDEX_INVALID: {error}"
                ) from None
            if existing_index["latestEpisodeDate"] > artifact.episode_date:
                index_target_verified_keys = self._verify_index_target(existing_index)
                remote_index_data = existing_index
                index_action = "PRESERVED_NEWER"
            elif existing_index["latestEpisodeDate"] == artifact.episode_date:
                remote_index_data = existing_index
                index_action = "ALREADY_CURRENT"
            else:
                if not self.single_writer_confirmed:
                    raise IndexUpdateRequiresSingleWriterError(
                        "INDEX_UPDATE_REQUIRES_SINGLE_WRITER_CONFIRMATION"
                    )
                remote_index_data = existing_index
        else:
            remote_index_data = {}

        index_backup_path = None
        index_candidate_path = None
        if index_action != "PRESERVED_NEWER":
            if index_action == "ALREADY_CURRENT":
                index = remote_index_data
            else:
                index = update_index_preserving_fields(
                    artifact.episode_date,
                    remote_index_data if remote_index is not None else None,
                )
                index_bytes = _json_bytes(index)
                index_backup_path, index_candidate_path = self._save_index_candidates(
                    artifact.episode_date,
                    remote_index.response.body if remote_index is not None else None,
                    index_bytes,
                )
                try:
                    uploaded.append(
                        self._put_object(
                            key=INDEX_KEY,
                            body=index_bytes,
                            content_type=JSON_CONTENT_TYPE,
                            cache_control=INDEX_CACHE_CONTROL,
                            forbid_overwrite=remote_index is None,
                        )
                    )
                except Exception:
                    raise IndexWriteOutcomeUnknownError(
                        "INDEX_WRITE_OUTCOME_UNKNOWN: inspect saved snapshot and public index before retrying"
                    ) from None

                try:
                    remote_index = self._fetch_object(INDEX_KEY, JSON_CONTENT_TYPE)
                    if remote_index is None:
                        raise ValueError("index is missing after write")
                    if remote_index.response.body != index_bytes:
                        raise ValueError("index bytes differ after write")
                    actual_index_sha256 = hashlib.sha256(remote_index.response.body).hexdigest().upper()
                    expected_index_sha256 = hashlib.sha256(index_bytes).hexdigest().upper()
                    if actual_index_sha256 != expected_index_sha256:
                        raise ValueError("index hash differs after write")
                    remote_index_data = _parse_index_contract(remote_index.response.body)
                    if remote_index_data["latestEpisodeDate"] != artifact.episode_date:
                        raise ValueError("index date differs after write")
                except Exception:
                    raise IndexWriteOutcomeUnknownError(
                        "INDEX_WRITE_OUTCOME_UNKNOWN: inspect saved snapshot and public index before retrying"
                    ) from None
        if "attachment" in remote_index.content_disposition.lower():
            warnings.append(f"PUBLIC_GET_FORCED_DOWNLOAD: key={INDEX_KEY}")

        uploaded_keys = [item["key"] for item in uploaded]
        status = (
            "PUBLISHED"
            if any(key != INDEX_KEY for key in uploaded_keys)
            else "ALREADY_PUBLISHED"
        )
        verified_keys = [artifact.vtt_key, artifact.manifest_key]
        for key in index_target_verified_keys + [INDEX_KEY]:
            if key not in verified_keys:
                verified_keys.append(key)
        index_sha256 = hashlib.sha256(remote_index.response.body).hexdigest().upper()
        return {
            "localStatus": "LOCAL_ARTIFACT_VALIDATED",
            "remoteStatus": status,
            "episodeDate": artifact.episode_date,
            "uploadedKeys": uploaded_keys,
            "verifiedKeys": verified_keys,
            "indexAction": index_action,
            "indexBackupPath": index_backup_path,
            "indexCandidatePath": index_candidate_path,
            "index": remote_index_data,
            "artifactHashes": {
                "manifestSha256": hashlib.sha256(artifact.manifest_bytes).hexdigest().upper(),
                "vttSha256": artifact.vtt_sha256,
                "indexSha256": index_sha256,
            },
            "publicUrls": {
                "vtt": self.object_url(artifact.vtt_key),
                "manifest": self.object_url(artifact.manifest_key),
                "index": self.object_url(INDEX_KEY),
            },
            "responseHeaders": {
                "vtt": {
                    "Content-Type": remote_vtt.content_type,
                    "Content-Disposition": remote_vtt.content_disposition,
                    "Cache-Control": remote_vtt.cache_control,
                },
                "manifest": {
                    "Content-Type": remote_manifest.content_type,
                    "Content-Disposition": remote_manifest.content_disposition,
                    "Cache-Control": remote_manifest.cache_control,
                },
                "index": {
                    "Content-Type": remote_index.content_type,
                    "Content-Disposition": remote_index.content_disposition,
                    "Cache-Control": remote_index.cache_control,
                },
            },
            "warnings": sorted(set(warnings)),
            "sdkVersion": self.SDK_VERSION,
        }

    publish_existing_artifact = publish
