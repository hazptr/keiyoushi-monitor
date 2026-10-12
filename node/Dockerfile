# The recheck node's sandbox image: runs third-party extension code and visits third-party
# sites from this container. Hardened on purpose — see the top-level README's security model.
#
# - Base: plain debian:stable-slim, pinned by digest. NOT the GitHub Actions runner image: no
#   actions-runner binary, no runner user with passwordless sudo, nothing that can register
#   itself with GitHub. This image never talks to the GitHub API at all.
# - No sudo installed, anywhere, at any layer.
# - Runs as a dedicated non-root uid (never root, even transiently after the image is built).
# - JDK 21, pinned to an exact Adoptium release by URL AND a SHA256 hardcoded in THIS file (not
#   trusted from Adoptium's API response at build time — a compromised/MITM'd API call could lie
#   about its own checksum just as easily as about the binary). Bump both together, deliberately,
#   when a newer JDK 21 point release is wanted.
# - Chrome comes from Google's own apt repo (stable channel). That is deliberately NOT pinned to
#   a specific version/digest: Google's apt repo only ever serves the current `stable` release —
#   older versions are removed outright, not just hidden — so pinning a version string here would
#   make every future rebuild fail once Google rotates it out, with no way to recover the old
#   `.deb` from that repo at all. Accepted, documented tradeoff (same one the GitHub-hosted sweep
#   already makes): a rebuild always gets whatever Chrome stable is current that day.
# - `pip install --require-hashes`: the one Python dependency this image installs (websockets)
#   is pinned by an exact sha256 of its one universal wheel, so a compromised/typosquatted PyPI
#   response can't silently swap in different bytes under the same version number.
# - The harness RUNTIME (harness/runtime's build output) is baked in at /app/runtime-dist:
#   the per-run recheck bundle no longer carries it at all, so every recheck runs this image's own
#   known-good runtime build, not one shipped inside the same archive as untrusted-adjacent build
#   output. /app/RUNTIME_COMMIT records which commit of this repo built it, so node/entrypoint.sh
#   can refuse a recheck.json whose harnessRuntimeCommit doesn't match.
FROM debian@sha256:eb593cf2c358cacef45ca0a424bbc7d30cfa3466265fc2662b9466a0ca6ba1c5

RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
      ca-certificates curl gnupg xvfb fonts-noto fonts-noto-cjk zstd python3 python3-pip \
    && curl -fsSL https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] http://dl.google.com/linux/chrome/deb/ stable main" \
         > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update -qq && apt-get install -y -qq --no-install-recommends google-chrome-stable \
    && apt-get clean && rm -rf /var/lib/apt/lists/*
    # (gnupg is left installed: google-chrome-stable's own apt repo entry needs it for future
    # `apt-get update`s if this layer is ever rebuilt interactively; chrome itself pulls in wget
    # as a real dependency, so it's not purged here either — purging it previously removed
    # google-chrome-stable along with it.)

# websockets==15.0.1, require-hashes: pip refuses to install anything (including this one
# package) unless every distribution it resolves to is listed here by exact sha256. One line
# because this version ships a single universal (py3-none-any) wheel with no dependencies of its
# own; if a future bump needs a per-platform wheel, add that wheel's hash alongside this one.
RUN echo "websockets==15.0.1 --hash=sha256:f7a866fbc1e97b5c617ee4116daaa09b722101d4a3c170c787450ba409f9736f" \
      > /tmp/requirements-pinned.txt \
    && pip install --no-cache-dir --break-system-packages --require-hashes -r /tmp/requirements-pinned.txt \
    && rm -f /tmp/requirements-pinned.txt

# JDK 21.0.12+1 (Eclipse Temurin / Adoptium), URL and SHA256 both hardcoded here — see the header
# comment above for why the checksum is not read from Adoptium's API response at build time.
RUN set -eux; \
    url="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz"; \
    sha="ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94"; \
    curl -fsSL -o /tmp/jdk21.tar.gz "$url"; \
    echo "$sha  /tmp/jdk21.tar.gz" | sha256sum -c -; \
    mkdir -p /opt/jdk21; \
    tar -xzf /tmp/jdk21.tar.gz -C /opt/jdk21 --strip-components=1; \
    rm -f /tmp/jdk21.tar.gz

ENV JAVA_HOME=/opt/jdk21 \
    PATH="/opt/jdk21/bin:${PATH}" \
    DISPLAY=:99 \
    EXT_RUN_WV_NO_GPU=1 \
    TZ=UTC \
    HOME=/tmp

# Dedicated non-root user. No entry in /etc/sudoers, no setuid binaries added by this image
# beyond what debian:stable-slim itself ships for ordinary system use (ping, etc — none of which
# this container runs).
RUN useradd --uid 10001 --create-home --shell /usr/sbin/nologin node
RUN mkdir -p /work && chown node:node /work

# Built with the repo root as context (see .github/workflows/node-image.yml and
# node/README.md's "build it yourself" section): `docker build -f node/Dockerfile .`
# node/runtime-dist/ and node/RUNTIME_COMMIT are produced by node-image.yml just before this
# build (harness/ci/build-runtime.sh + `git rev-parse` of the building commit) — not checked into
# the repo themselves.
COPY --chown=root:root node/entrypoint.sh node/extract_bundle.py node/categorize.py /app/
COPY --chown=root:root node/RUNTIME_COMMIT /app/RUNTIME_COMMIT
COPY --chown=root:root node/runtime-dist /app/runtime-dist
COPY --chown=root:root publisher /app/publisher
COPY --chown=root:root harness /app/harness
RUN chmod 0555 /app/entrypoint.sh /app/extract_bundle.py /app/categorize.py \
    && chmod 0444 /app/RUNTIME_COMMIT \
    && chmod -R a+rX /app/harness /app/publisher /app/runtime-dist \
    && chmod 0555 /app/harness/ci/*.sh /app/harness/webview/nodriver/*.sh /app/harness/webview/nodriver/*.py

USER node
WORKDIR /work
ENTRYPOINT ["/app/entrypoint.sh"]
