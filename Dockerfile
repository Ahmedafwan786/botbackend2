# Use an official OpenJDK image
FROM openjdk:17-jdk-slim AS builder

# Set working directory
WORKDIR /app

# Copy Maven files first to leverage Docker cache
COPY pom.xml .
COPY mvnw .
COPY .mvn .mvn

# Download dependencies (cached for faster builds)
RUN ./mvnw dependency:go-offline

# Copy the entire source code
COPY src src

# Build the JAR file
RUN ./mvnw clean package -DskipTests

# ------------------------------
# Second stage: run the app
# ------------------------------
FROM openjdk:17-jdk-slim

WORKDIR /app

# Copy JAR file from builder image
COPY --from=builder /app/target/*.jar app.jar

# Expose port 8080 (or your custom port)
EXPOSE 8080

# Start the app
ENTRYPOINT ["java", "-jar", "app.jar"]