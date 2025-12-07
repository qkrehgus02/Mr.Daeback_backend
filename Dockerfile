## Multi-stage build for Spring Boot (Java 17)

# Build stage
FROM eclipse-temurin:17-jdk AS builder
WORKDIR /app

# Gradle wrapper and metadata
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src

RUN chmod +x gradlew
RUN ./gradlew bootJar -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","/app/app.jar"]
