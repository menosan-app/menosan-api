# syntax=docker/dockerfile:1

# ---- Build: fat JAR with the Gradle wrapper on JDK 21 ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --quiet help
COPY src src
RUN ./gradlew --no-daemon buildFatJar -x test

# ---- Run: JRE only, non-root ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home menosan
WORKDIR /app
COPY --from=build /src/build/libs/menosan-api.jar /app/menosan-api.jar
USER menosan

# All config comes from env vars (see .env.example). APP_ENV defaults to prod: no .env, no dev tools, no clock override.
ENV APP_ENV=prod \
    PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/menosan-api.jar"]
