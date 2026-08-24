# syntax=docker/dockerfile:1

# Production builds must supply a digest-pinned Alpine JRE image.
ARG JAVA_RUNTIME_IMAGE=eclipse-temurin:25-jre-alpine
FROM ${JAVA_RUNTIME_IMAGE}
RUN apk add --no-cache podman-remote wget \
    && addgroup -S -g 10001 findworks \
    && adduser -S -D -H -u 10001 -G findworks findworks
USER findworks
WORKDIR /app
COPY --chown=findworks:findworks target/findworks-*.jar app.jar
COPY --chown=findworks:findworks scripts/container-entrypoint.sh /app/entrypoint
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=6 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["/app/entrypoint"]
CMD ["local"]
