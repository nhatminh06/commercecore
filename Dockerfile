FROM gradle:8.12-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle gradlew ./
COPY gradle gradle
COPY payment-proto payment-proto
COPY src src
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S commercecore && adduser -S -G commercecore commercecore
WORKDIR /app
COPY --from=build /workspace/build/libs/commercecore-0.0.1.jar app.jar
USER commercecore
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
