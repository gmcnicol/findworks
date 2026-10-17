FROM node:24-alpine
ARG PI_VERSION=0.84.4
ARG VCS_REF=unknown
LABEL io.findworks.pi.version="${PI_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}"
RUN addgroup -g 10001 findworks && adduser -D -u 10001 -G findworks findworks \
    && npm install -g "@earendil-works/pi-coding-agent@${PI_VERSION}" typebox@1.3.7
WORKDIR /runtime
ENV NODE_PATH=/usr/local/lib/node_modules
COPY entrypoint.mjs runner.mjs reviewer-runner.mjs rpc-state.mjs auth.mjs turn-client.mjs extension.ts reviewer-extension.ts interview/SKILL.md ./
COPY reviewer/SKILL.md ./reviewer/SKILL.md
RUN chown -R 10001:10001 /runtime
USER 10001:10001
ENTRYPOINT ["node", "/runtime/entrypoint.mjs"]
