#!/usr/bin/env python3
"""Read-only snapshot endpoint, bound only to Tailscale."""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path
import json,time,os
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path!='/status': self.send_error(404); return
        try:
            data=json.loads(Path('/var/lib/pi-device-monitor/status.json').read_text())
            data['stale']=time.time()-data['collected_at']>180
            body=json.dumps(data,ensure_ascii=False).encode()
            self.send_response(200); self.send_header('Content-Type','application/json; charset=utf-8'); self.send_header('Cache-Control','no-store'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
        except (OSError,ValueError): self.send_error(503)
    def log_message(self,*args): pass
ThreadingHTTPServer((os.environ['PI_MONITOR_BIND'],8786),Handler).serve_forever()
