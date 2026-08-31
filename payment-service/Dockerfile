FROM gradle:8.12-jdk21 AS build
WORKDIR /workspace
COPY settings.gradle build.gradle ./
COPY gradle gradle
COPY gradlew ./
COPY payment-proto payment-proto
COPY payment-service payment-service
RUN gradle :payment-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/payment-service/build/libs/payment-service-0.0.1.jar app.jar
EXPOSE 8091 9090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
