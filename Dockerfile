# Build stage
FROM gradle:8.5-jdk17 AS builder
WORKDIR /app

# Copy gradle wrapper and dependencies files
COPY build.gradle settings.gradle gradlew ./
COPY gradle ./gradle

# Download dependencies
RUN ./gradlew dependencies --no-daemon

# Copy source code
COPY src ./src

# Build application
RUN ./gradlew bootJar --no-daemon

# Runtime stage
FROM openjdk:17-jdk-slim
WORKDIR /app

# Copy built jar from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Expose application port
EXPOSE 8081

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]