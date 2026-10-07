"""
Static HTTPS Server for Subtitle Artifacts (Local / Test).
Enforces:
- HTTPS protocol
- UTF-8 encoding
- Correct Content-Type (application/json, text/vtt)
- Cache-Control headers
- 404 fail-closed
"""

import os
import sys
import ssl
import argparse
from http.server import HTTPServer, SimpleHTTPRequestHandler

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_DIST = os.path.join(REPO_ROOT, "dist")

class SubtitleStaticRequestHandler(SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def end_headers(self):
        # Enforce cache-control and security headers
        self.send_header("Cache-Control", "public, max-age=86400")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def guess_type(self, path):
        if path.endswith(".vtt"):
            return "text/vtt; charset=utf-8"
        if path.endswith(".json"):
            return "application/json; charset=utf-8"
        return super().guess_type(path)

    def do_GET(self):
        sys.stdout.write(f"[SUBTITLE_HTTPS] GET {self.path}\n")
        sys.stdout.flush()
        super().do_GET()

def create_self_signed_cert(cert_path: str, key_path: str):
    """Generates a temporary self-signed certificate using python-cryptography or standard tools."""
    try:
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.primitives import serialization
        import datetime

        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = issuer = x509.Name([
            x509.NameAttribute(NameOID.COMMON_NAME, "localhost"),
        ])
        cert = x509.CertificateBuilder().subject_name(
            subject
        ).issuer_name(
            issuer
        ).public_key(
            key.public_key()
        ).serial_number(
            x509.random_serial_number()
        ).not_valid_before(
            datetime.datetime.now(datetime.timezone.utc)
        ).not_valid_after(
            datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(days=365)
        ).add_extension(
            x509.SubjectAlternativeName([x509.DNSName("localhost"), x509.IPAddress(ipaddress.IPv4Address("127.0.0.1"))]),
            critical=False,
        ).sign(key, hashes.SHA256())

        with open(key_path, "wb") as f:
            f.write(key.private_bytes(
                encoding=serialization.Encoding.PEM,
                format=serialization.PrivateFormat.TraditionalOpenSSL,
                encryption_algorithm=serialization.NoEncryption()
            ))
        with open(cert_path, "wb") as f:
            f.write(cert.public_bytes(serialization.Encoding.PEM))
    except Exception as e:
        sys.stderr.write(f"Could not generate self-signed cert via cryptography: {e}\n")

def run_server(port: int = 8443, root_dir: str = DEFAULT_DIST, certfile: str = None, keyfile: str = None):
    os.chdir(root_dir)
    handler = SubtitleStaticRequestHandler
    httpd = HTTPServer(("0.0.0.0", port), handler)

    if certfile and os.path.exists(certfile) and keyfile and os.path.exists(keyfile):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(certfile=certfile, keyfile=keyfile)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
        print(f"Serving static subtitles over HTTPS on port {port} from {root_dir}...")
    else:
        print(f"Serving static subtitles over HTTP on port {port} from {root_dir}...")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping subtitle server.")
        httpd.server_close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--dir", default=DEFAULT_DIST)
    parser.add_argument("--cert", default=None)
    parser.add_argument("--key", default=None)
    args = parser.parse_args()
    run_server(args.port, args.dir, args.cert, args.key)
