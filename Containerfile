# McG - Headless MCP Server for Ghidra
# Usage: podman build --build-arg GHIDRA_VERSION=12.0.3_PUBLIC -t mcg .
#        podman run -p 8888:8888 -v ./bins:/data:Z mcg /data/firmware.bin

ARG GHIDRA_VERSION=12.0.3_PUBLIC

# --- Stage 1: Build the extension ---
FROM docker.io/eclipse-temurin:21-jdk AS builder

ARG GHIDRA_VERSION
ENV GHIDRA_VERSION=${GHIDRA_VERSION}

WORKDIR /build

# Download and extract Ghidra
RUN apt-get update && apt-get install -y --no-install-recommends unzip curl && \
    curl -sSfL -o /tmp/ghidra.zip \
      "https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_${GHIDRA_VERSION}_build/ghidra_${GHIDRA_VERSION}_$(date +%Y%m%d).zip" || \
    # Fallback: find the actual asset URL from the release
    curl -sSf "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/tags/Ghidra_${GHIDRA_VERSION}_build" \
      | grep -o '"browser_download_url": *"[^"]*"' | head -1 | cut -d'"' -f4 \
      | xargs curl -sSfL -o /tmp/ghidra.zip && \
    unzip -q /tmp/ghidra.zip -d /opt && \
    mv /opt/ghidra_* /opt/ghidra && \
    rm /tmp/ghidra.zip

ENV GHIDRA_INSTALL_DIR=/opt/ghidra

# Copy source and build
COPY . /build/
RUN ./gradlew clean distributeExtension --no-daemon -q

# --- Stage 2: Runtime ---
FROM docker.io/eclipse-temurin:21-jre

ARG GHIDRA_VERSION
ENV GHIDRA_VERSION=${GHIDRA_VERSION}

RUN apt-get update && apt-get install -y --no-install-recommends unzip && \
    rm -rf /var/lib/apt/lists/*

# Copy Ghidra (exclude docs and server to save space)
COPY --from=builder /opt/ghidra /opt/ghidra
RUN rm -rf /opt/ghidra/docs /opt/ghidra/server

ENV GHIDRA_INSTALL_DIR=/opt/ghidra

# Install extension
COPY --from=builder /build/dist/*.zip /tmp/extension.zip
RUN unzip -q /tmp/extension.zip -d "$GHIDRA_INSTALL_DIR/Ghidra/Extensions/" && \
    rm /tmp/extension.zip

# Copy McgServer script into extension's script dir
COPY ghidra_scripts/McgServer.java \
     "$GHIDRA_INSTALL_DIR/Ghidra/Extensions/ghidra-mcp/ghidra_scripts/McgServer.java"

# Default to network-accessible binding for containers
ENV GHIDRA_MCP_HOST=0.0.0.0
EXPOSE 8888

VOLUME /data
WORKDIR /data

ENTRYPOINT ["sh", "-c", "exec \"$GHIDRA_INSTALL_DIR/support/analyzeHeadless\" /tmp/mcg-project McG -import \"$1\" -postScript McgServer.java", "--"]
