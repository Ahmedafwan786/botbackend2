FROM openjdk:17-jdk-slim AS builder

RUN apt-get update && apt-get install -y maven

WORKDIR /app

# Copy everything
COPY . .

# Debug: print Maven & Java versions
RUN mvn -v && java -version

# Build your app
RUN mvn clean package -DskipTests

FROM openjdk:17-jdk-slim
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
