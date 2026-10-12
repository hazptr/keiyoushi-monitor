# ext-run WebView backend: real Google Chrome (not Chromium, not headless-shell), headed under Xvfb,
# driven over raw CDP by bridge.py (mounted at /bridge, not baked in, so edits only need a restart).
# Mesa/Vulkan drivers + /dev/dri (see ../docker-compose.yml) make WebGL report the real GPU.
FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends wget gnupg ca-certificates xvfb fonts-noto fonts-noto-cjk \
        libgl1-mesa-dri libegl1 libgles2 mesa-vulkan-drivers libvulkan1 \
    && wget -qO- https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o /usr/share/keyrings/google.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google.gpg] https://dl.google.com/linux/chrome/deb/ stable main" > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update && apt-get install -y --no-install-recommends google-chrome-stable \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir websockets==15.0.1

ENV DISPLAY=:99 HOME=/tmp
ENTRYPOINT ["/bridge/start.sh"]
