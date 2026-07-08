FROM eclipse-temurin:17-jre

WORKDIR /app
COPY target/mini-reco-access-layer-0.1.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080
ENV PORT=8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
