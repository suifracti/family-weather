import json
import os
import sys
import time
from http.server import HTTPServer, SimpleHTTPRequestHandler

DIST_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "dist"))
MANIFEST_PATH = os.path.join(DIST_DIR, "subtitles", "evening-weather", "2026-09-17", "manifest.json")
VTT_PATH = os.path.join(DIST_DIR, "subtitles", "evening-weather", "2026-09-17", "subtitle.vtt")

with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
    VALID_MANIFEST = json.load(f)

with open(VTT_PATH, "rb") as f:
    VALID_VTT_BYTES = f.read()

class SubtitleHandler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def send_body(self, code, content_type, body_bytes):
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body_bytes)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body_bytes)

    def do_GET(self):
        path = self.path
        sys.stdout.write(f"[TEST_SERVER] GET {path}\n")
        sys.stdout.flush()

        # Scenario B: 404
        if "/scenario_b/" in path:
            self.send_body(404, "text/plain", b"Not Found")
            return

        # Scenario C: VTT Hash Mismatch
        if "/scenario_c/" in path:
            if "manifest.json" in path:
                tampered = dict(VALID_MANIFEST)
                tampered["vttSha256"] = "0000000000000000000000000000000000000000000000000000000000000000"
                self.send_body(200, "application/json", json.dumps(tampered).encode("utf-8"))
                return
            elif "subtitle.vtt" in path:
                self.send_body(200, "text/vtt", VALID_VTT_BYTES)
                return

        # Scenario D: Episode Mismatch
        if "/scenario_d/" in path:
            if "manifest.json" in path:
                tampered = dict(VALID_MANIFEST)
                tampered["episodeDate"] = "2026-09-18"
                self.send_body(200, "application/json", json.dumps(tampered).encode("utf-8"))
                return

        # Scenario E: Video URL Mismatch
        if "/scenario_e/" in path:
            if "manifest.json" in path:
                tampered = dict(VALID_MANIFEST)
                tampered["sourceVideoUrl"] = "https://vod.weathertv.cn/video/different_video.mp4"
                self.send_body(200, "application/json", json.dumps(tampered).encode("utf-8"))
                return

        # Scenario F: Duration Mismatch
        if "/scenario_f/" in path:
            if "manifest.json" in path:
                tampered = dict(VALID_MANIFEST)
                tampered["durationMs"] = 265000
                self.send_body(200, "application/json", json.dumps(tampered).encode("utf-8"))
                return

        # Scenario H: Stale Async Result (simulate 3s network delay)
        if "/scenario_h/" in path:
            time.sleep(3)
            if "manifest.json" in path:
                self.send_body(200, "application/json", json.dumps(VALID_MANIFEST).encode("utf-8"))
                return

        # Scenario A (Default): Valid manifest and VTT
        if "manifest.json" in path:
            self.send_body(200, "application/json", json.dumps(VALID_MANIFEST).encode("utf-8"))
            return
        elif "subtitle.vtt" in path:
            self.send_body(200, "text/vtt", VALID_VTT_BYTES)
            return

        self.send_body(404, "text/plain", b"Not Found")

def run():
    server = HTTPServer(("0.0.0.0", 8888), SubtitleHandler)
    sys.stdout.write("Serving subtitles on port 8888...\n")
    sys.stdout.flush()
    server.serve_forever()

if __name__ == "__main__":
    run()
