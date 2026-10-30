# Build from the repository root:  docker build -f tenantpulse-server/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q -pl tenantpulse-server -am package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 tenantpulse
USER tenantpulse
WORKDIR /app
COPY --from=build /src/tenantpulse-server/target/tenantpulse-server-*-SNAPSHOT.jar /app/tenantpulse-server.jar
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/tenantpulse-server.jar"]
