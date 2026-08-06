ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${RUNTIME_IMAGE}

WORKDIR /app
COPY target/mini-reco-access-layer-0.1.0-SNAPSHOT-all.jar /app/app.jar
COPY db/migration /app/db/migration

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"
USER 65532:65532

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=3 \
  CMD ["java", "-cp", "/app/app.jar", "io.github.hzyang0.minireco.ops.HttpHealthProbe", "http://localhost:8080/health"]
ENTRYPOINT ["java"]
CMD ["-jar", "/app/app.jar", "8080"]
