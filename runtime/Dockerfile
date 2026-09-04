FROM node:24-alpine
RUN addgroup -g 10001 findworks && adduser -D -u 10001 -G findworks findworks \
    && npm install -g @earendil-works/pi-coding-agent@0.84.4 typebox@1.3.7
WORKDIR /runtime
ENV NODE_PATH=/usr/local/lib/node_modules
COPY runner.mjs auth.mjs extension.ts interview/SKILL.md ./
RUN chown -R 10001:10001 /runtime
USER 10001:10001
ENTRYPOINT ["node", "/runtime/runner.mjs"]
