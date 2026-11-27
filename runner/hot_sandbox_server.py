#!/usr/bin/env python3
"""Hot sandbox. FastAPI + uvicorn, Bearer token, workspace cache.

GET  /health
POST /v1/jobs
GET  /v1/jobs/{id}/stream
GET  /v1/jobs/{id}/artifacts/{name}

The container is the isolation boundary. GRADLE_USER_HOME stays on a volume so the
Gradle daemon outlives a single job.
"""
import json
import os
import subprocess
import threading
import time
import uuid

from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import FileResponse, StreamingResponse

TOKEN = os.environ.get("SANDBOX_TOKEN", "change-me")
WORK = os.environ.get("SANDBOX_WORK", "/opt/workspaces")
HOST = os.environ.get("SANDBOX_HOST", "0.0.0.0")
PORT = int(os.environ.get("SANDBOX_PORT", "8787"))
os.makedirs(WORK, exist_ok=True)
os.makedirs(os.environ.get("GRADLE_USER_HOME", "/opt/gradle-home"), exist_ok=True)

JOBS = {}
LOCK = threading.Lock()
app = FastAPI()


def require_token(authorization: str | None) -> None:
    if authorization != "Bearer " + TOKEN:
        raise HTTPException(status_code=401, detail="unauthorized")


def run_job(job_id: str, command: str) -> None:
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
                env={**os.environ, "GRADLE_USER_HOME": os.environ.get("GRADLE_USER_HOME", "/opt/gradle-home")},
            )
        status, code = ("ok", proc.returncode) if proc.returncode == 0 else ("failed", proc.returncode)
    except subprocess.TimeoutExpired:
        status, code = "failed", 124
        with open(log_path, "a", encoding="utf-8") as log:
            log.write("\ntimeout\n")
    with LOCK:
        JOBS[job_id]["status"] = status
        JOBS[job_id]["code"] = code


@app.get("/health")
def health():
    return {"ok": True, "gradle_home": os.environ.get("GRADLE_USER_HOME", "")}


@app.post("/v1/jobs")
def create_job(payload: dict, authorization: str | None = Header(default=None)):
    require_token(authorization)
    command = str(payload.get("command") or "echo sandbox")
    job_id = uuid.uuid4().hex[:12]
    with LOCK:
        JOBS[job_id] = {"status": "queued", "code": None, "at": time.time()}
    threading.Thread(target=run_job, args=(job_id, command), daemon=True).start()
    return {"id": job_id, "status": "queued"}


@app.get("/v1/jobs/{job_id}/stream")
def stream(job_id: str, authorization: str | None = Header(default=None)):
    require_token(authorization)
    with LOCK:
        job = JOBS.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="missing")
    log_path = os.path.join(WORK, job_id, "job.log")
    body = ""
    if os.path.isfile(log_path):
        with open(log_path, encoding="utf-8", errors="replace") as handle:
            body = handle.read()[-8000:]

    def chunks():
        yield "data: " + json.dumps({"status": job["status"], "log": body}) + "\n\n"

    return StreamingResponse(chunks(), media_type="text/event-stream")


@app.get("/v1/jobs/{job_id}/artifacts/{name}")
def artifact(job_id: str, name: str, authorization: str | None = Header(default=None)):
    require_token(authorization)
    safe = os.path.basename(name)
    target = os.path.join(WORK, job_id, safe)
    if not os.path.isfile(target):
        raise HTTPException(status_code=404, detail="missing")
    return FileResponse(target)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=HOST, port=PORT, log_level="warning")
