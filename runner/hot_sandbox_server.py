#!/usr/bin/env python3
"""Minimal hot sandbox. Bearer token, workspace cache, no extra Python packages.

POST /v1/jobs            {"command": "...", "repo": "...", "branch": "..."}
GET  /v1/jobs/{id}/stream
GET  /v1/jobs/{id}/artifacts/{name}
GET  /health

The container is the isolation boundary. Gradle stays warm because the process
and GRADLE_USER_HOME outlive a single job.
"""
import json
import os
import subprocess
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN = os.environ.get("SANDBOX_TOKEN", "change-me")
WORK = os.environ.get("SANDBOX_WORK", "/opt/workspaces")
HOST = os.environ.get("SANDBOX_HOST", "0.0.0.0")
PORT = int(os.environ.get("SANDBOX_PORT", "8787"))
os.makedirs(WORK, exist_ok=True)

JOBS = {}
LOCK = threading.Lock()


def run_job(job_id, command):
    workspace = os.path.join(WORK, job_id)
    os.makedirs(workspace, exist_ok=True)
    log_path = os.path.join(workspace, "job.log")
    with LOCK:
        JOBS[job_id]["status"] = "running"
    try:
        with open(log_path, "w", encoding="utf-8") as log:
            proc = subprocess.run(
                ["sh", "-c", command],
                cwd=workspace,
                stdout=log,
                stderr=subprocess.STDOUT,
                timeout=60 * 30,
                check=False,
            )
        status = "ok" if proc.returncode == 0 else "failed"
        code = proc.returncode
    except subprocess.TimeoutExpired:
        status, code = "failed", 124
        with open(log_path, "a", encoding="utf-8") as log:
            log.write("\ntimeout\n")
    with LOCK:
        JOBS[job_id]["status"] = status
        JOBS[job_id]["code"] = code


class Handler(BaseHTTPRequestHandler):
    def _auth(self):
        header = self.headers.get("Authorization", "")
        if header != "Bearer " + TOKEN:
            self._json(401, {"error": "unauthorized"})
            return False
        return True

    def _json(self, code, payload):
        raw = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/health":
            self._json(200, {"ok": True, "gradle_home": os.environ.get("GRADLE_USER_HOME", "")})
            return
        if not self._auth():
            return
        parts = [p for p in path.split("/") if p]
        if len(parts) == 4 and parts[:2] == ["v1", "jobs"] and parts[3] == "stream":
            job = JOBS.get(parts[2])
            if not job:
                self._json(404, {"error": "missing"})
                return
            log_path = os.path.join(WORK, parts[2], "job.log")
            body = ""
            if os.path.exists(log_path):
                with open(log_path, encoding="utf-8", errors="replace") as f:
                    body = f.read()[-8000:]
            raw = ("data: " + json.dumps({"status": job["status"], "log": body}) + "\n\n").encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return
        if len(parts) == 5 and parts[:2] == ["v1", "jobs"] and parts[3] == "artifacts":
            name = os.path.basename(parts[4])
            target = os.path.join(WORK, parts[2], name)
            if not os.path.isfile(target):
                self._json(404, {"error": "missing"})
                return
            with open(target, "rb") as f:
                blob = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(blob)))
            self.end_headers()
            self.wfile.write(blob)
            return
        self._json(404, {"error": "not found"})

    def do_POST(self):
        if not self._auth():
            return
        if self.path.split("?", 1)[0] != "/v1/jobs":
            self._json(404, {"error": "not found"})
            return
        length = int(self.headers.get("Content-Length", "0") or 0)
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode() or "{}")
        except json.JSONDecodeError:
            self._json(400, {"error": "json"})
            return
        command = str(payload.get("command") or "echo sandbox")
        job_id = uuid.uuid4().hex[:12]
        with LOCK:
            JOBS[job_id] = {"status": "queued", "code": None, "at": time.time()}
        threading.Thread(target=run_job, args=(job_id, command), daemon=True).start()
        self._json(200, {"id": job_id, "status": "queued"})

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
