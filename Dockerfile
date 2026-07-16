ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${RUNTIME_IMAGE}

WORKDIR /app
COPY target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"
USER 65532:65532

EXPOSE 8080 19001 19002 19003 4317 16686
ENTRYPOINT ["java"]
CMD ["-jar", "/app/app.jar", "8080"]
