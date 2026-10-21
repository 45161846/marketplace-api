FROM docker.io/library/eclipse-temurin:17-jdk-jammy AS build

WORKDIR /app
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew && ./gradlew bootJar openApiValidate --no-daemon

FROM docker.io/library/eclipse-temurin:17-jre-jammy

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
