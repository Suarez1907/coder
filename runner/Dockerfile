FROM python:3.12-slim

# Reference runner. The image has no pip packages. The container is the sandbox.
# Mount a volume on GRADLE_USER_HOME so the Gradle daemon stays warm across jobs.
ENV GRADLE_USER_HOME=/opt/gradle-home \
    SANDBOX_WORK=/opt/workspaces \
    SANDBOX_TOKEN=change-me \
    SANDBOX_PORT=8787 \
    PYTHONDONTWRITEBYTECODE=1

RUN mkdir -p /opt/gradle-home /opt/workspaces
WORKDIR /opt/sandbox
COPY hot_sandbox_server.py /opt/sandbox/hot_sandbox_server.py

EXPOSE 8787
CMD ["python", "-u", "/opt/sandbox/hot_sandbox_server.py"]
