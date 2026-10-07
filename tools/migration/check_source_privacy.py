#!/usr/bin/env python3
"""Read-only, limited current-source privacy gate. Never prints matched values."""
from pathlib import Path
import json
import re
import subprocess

RULES = {
    "personal_mac_path": re.compile(r"/Users/(?!YOUR_USER\b|USERNAME\b|your-user\b)[A-Za-z0-9_.-]+/"),
    "personal_windows_path": re.compile(r"[A-Za-z]:[\\/]+(?:Users|ai|Tools|backup|work)[\\/]", re.I),
    "private_drive_identifier": re.compile(r"https?://(?:drive\.google\.com/file/d/|docs\.google\.com/(?:document|spreadsheets|presentation)/d/)[A-Za-z0-9_-]+"),
    "private_key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "credential_token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|AKID[A-Za-z0-9]{28,}|sk-[A-Za-z0-9_-]{24,}|AIza[A-Za-z0-9_-]{35})\b"),
}


def inspect_text(text, path, private_values=()):
    findings = []
    for number, line in enumerate(text.splitlines(), 1):
        for kind, rule in RULES.items():
            if rule.search(line):
                findings.append({"path": path, "line": number, "kind": kind})
        for value in private_values:
            pattern = r"(?<![\d.])" + re.escape(value) + r"(?![\d.])" if re.fullmatch(r"[0-9.]+", value) else re.escape(value)
            if re.search(pattern, line):
                findings.append({"path": path, "line": number, "kind": "private_location_value"})
        if path.endswith("LiveScenarioVerificationTest.kt") and "println(" in line:
            if re.search(r"\$\{[^}]*\.(?:lat|lon|latitude|longitude|canonicalLocationId|message)\}", line):
                findings.append({"path": path, "line": number, "kind": "sensitive_live_log"})
    return findings


def check(root):
    paths = subprocess.check_output(["git", "ls-files", "-z"], cwd=root).decode().split("\0")
    private_path = "domain/src/main/kotlin/org/breezyweather/domain/multisource/location/LocationAuthority.kt"
    findings = []
    if private_path in paths:
        findings.append({"path": private_path, "line": 1, "kind": "tracked_private_configuration"})
    private_values = []
    private_config = root / private_path
    if private_config.exists():
        config = private_config.read_text()
        private_values.extend(re.findall(r"(?:latitude|longitude) = ([0-9.]+)", config))
        template = (root / (private_path + ".example")).read_text()
        names = set(re.findall(r'displayName = "([^"]+)"', config))
        public_names = set(re.findall(r'displayName = "([^"]+)"', template))
        private_values.extend(names - public_names)
    inspected = 0
    for path in paths:
        file = root / path
        if not path or not file.is_file():
            continue
        try:
            text = file.read_text(encoding="utf-8")
        except (UnicodeError, OSError):
            continue
        inspected += 1
        # Required public geographic resources may contain unrelated numeric coincidences.
        values = () if path.endswith("ne_50m_admin_0_countries.json") or "/breezytz_" in path else private_values
        findings.extend(inspect_text(text, path, values))
    return {"scope": "tracked working-tree UTF-8 source only; not history, APK or unknown credential formats", "inspectedTextFiles": inspected, "findings": findings}


if __name__ == "__main__":
    result = check(Path(__file__).resolve().parents[2])
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(bool(result["findings"]))
