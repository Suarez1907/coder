FROM python:3.12-slim

# The image is the sandbox. No model weights. Gradle home is a volume so the daemon stays warm.
ENV GRADLE_USER_HOME=/opt/gradle-home \
    SANDBOX_WORK=/opt/workspaces \
    SANDBOX_TOKEN=change-me \
    SANDBOX_PORT=8787 \
    PYTHONDONTWRITEBYTECODE=1

RUN pip install --no-cache-dir fastapi uvicorn \
    && mkdir -p /opt/gradle-home /opt/workspaces
WORKDIR /opt/sandbox
COPY hot_sandbox_server.py /opt/sandbox/hot_sandbox_server.py

EXPOSE 8787
CMD ["uvicorn", "hot_sandbox_server:app", "--host", "0.0.0.0", "--port", "8787"]
