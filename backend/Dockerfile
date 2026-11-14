# syntax=docker/dockerfile:1

# --- Build stage ---
FROM eclipse-temurin:25-jdk-alpine AS builder
WORKDIR /workspace

# Resolve dependencies first so code changes don't invalidate this layer.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN addgroup -S ticketflow && adduser -S ticketflow -G ticketflow
USER ticketflow:ticketflow

COPY --from=builder /workspace/target/*.jar app.jar

EXPOSE 8080

# Container-aware heap sizing; the JVM reads cgroup limits by default since Java 10.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
