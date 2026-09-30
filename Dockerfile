# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
# Run the same rule, HTTP, WebSocket and webhook tests as a local build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp verify

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --uid 10001 --create-home app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/bank-heist-1.0-SNAPSHOT.jar /app/bank-heist.jar
USER app
ENTRYPOINT ["java", "-cp", "/app/bank-heist.jar"]
CMD ["dk.bankheist.GameServer"]
