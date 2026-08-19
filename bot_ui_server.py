"""Static file server + reverse proxy for the bot telemetry dashboard.

Serves the dashboard (bot_ui.html and other static files) from this script's
directory on 127.0.0.1:8789 and forwards every /api/* request to the game
server's telemetry API on 127.0.0.1:8456. Proxying keeps the dashboard and the
API on the same origin, so the browser never makes a cross-origin call and the
game server needs no CORS support.

Run:  python bot_ui_server.py [port]      (default 8789)
"""

import os
import socket
import sys
import threading
import urllib.error
import urllib.request
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

# Override with BOT_UI_API=http://127.0.0.1:18456 to point at a mock server.
API_ORIGIN = os.environ.get("BOT_UI_API", "http://127.0.0.1:8456").rstrip("/")
API_TIMEOUT_S = 10


class DashboardHandler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/api/"):
            self._proxy()
        else:
            super().do_GET()

    def do_POST(self):
        self._proxy()

    def do_DELETE(self):
        self._proxy()

    def _proxy(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        request = urllib.request.Request(
            API_ORIGIN + self.path,
            data=body,
            method=self.command,
        )
        content_type = self.headers.get("Content-Type")
        if content_type:
            request.add_header("Content-Type", content_type)
        try:
            with urllib.request.urlopen(request, timeout=API_TIMEOUT_S) as response:
                self._relay(response.status, response.read())
        except urllib.error.HTTPError as e:
            # The API's own error JSON (404/409/503/...) must reach the page intact.
            self._relay(e.code, e.read())
        except (urllib.error.URLError, OSError, TimeoutError):
            self._relay(
                502,
                b'{"error": "Game server not reachable on '
                + API_ORIGIN.encode()
                + b' (is the world running with telemetry enabled?)"}',
            )

    def _relay(self, status, body):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        # Quiet static-file spam; keep API call logging one line.
        if self.path.startswith("/api/"):
            sys.stderr.write("%s %s -> %s\n" % (self.command, self.path, args[1] if len(args) > 1 else "?"))


class Ipv6LoopbackServer(ThreadingHTTPServer):
    """IPv6-loopback-only server; "localhost" may resolve to ::1 in browsers."""

    address_family = socket.AF_INET6

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        super().server_bind()


def port_already_in_use(port):
    # Windows lets a second bind succeed silently via SO_REUSEADDR, which
    # splits connections between the two processes — so probe instead.
    for host in ("127.0.0.1", "::1"):
        try:
            with socket.create_connection((host, port), timeout=0.5):
                return True
        except OSError:
            continue
    return False


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8789
    root = os.path.dirname(os.path.abspath(__file__))
    handler = partial(DashboardHandler, directory=root)
    if port_already_in_use(port):
        sys.exit(f"Port {port} is already serving — leave the existing dashboard "
                 f"server running or stop it first.")
    servers = [ThreadingHTTPServer(("127.0.0.1", port), handler)]
    try:
        servers.append(Ipv6LoopbackServer(("::1", port), handler))
    except OSError:
        pass  # IPv6 loopback unavailable; IPv4 still works
    print(f"Serving dashboard on http://localhost:{port}/bot_ui.html")
    print(f"Proxying /api/* -> {API_ORIGIN}")
    print("Press Ctrl+C to stop.")
    for server in servers[1:]:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        servers[0].serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
