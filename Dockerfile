# syntax=docker/dockerfile:1

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S findworks && adduser -S findworks -G findworks
USER findworks
WORKDIR /app
COPY --chown=findworks:findworks target/findworks-*.jar app.jar
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=6 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
