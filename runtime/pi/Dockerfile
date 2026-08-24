# syntax=docker/dockerfile:1

# Supply a digest-pinned Node image, for example through
# --build-arg PI_BASE_IMAGE=node:24-alpine@sha256:<verified-digest>.
ARG PI_BASE_IMAGE
FROM ${PI_BASE_IMAGE}

RUN npm install --global --ignore-scripts @earendil-works/pi-coding-agent@0.84.2 \
    && addgroup -S -g 10001 findworks \
    && adduser -S -D -H -u 10001 -G findworks findworks \
    && mkdir -p /opt/findworks/interview-skill /run/findworks \
    && chown -R 10001:10001 /run/findworks

COPY runtime/pi/entrypoint.mjs /opt/findworks/entrypoint.mjs
COPY runtime/pi/settings.json /opt/findworks/pi-config/settings.json
COPY src/main/resources/pi/interview-extension.ts /opt/findworks/interview-extension.ts
COPY src/main/resources/pi/interview/SKILL.md /opt/findworks/interview-skill/SKILL.md

USER 10001:10001
WORKDIR /run/findworks
ENTRYPOINT ["node", "/opt/findworks/entrypoint.mjs"]
