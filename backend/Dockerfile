# Build stage
FROM maven:3.9-eclipse-temurin-25 AS builder
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -DskipTests package

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=builder /app/target/ai-social-game-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 11031
ENTRYPOINT ["java","-jar","/app/app.jar"]
