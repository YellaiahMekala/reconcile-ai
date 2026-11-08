# Multi-stage build: compile with Maven on JDK 21, run on a slim JRE as a non-root user.
# Build:  docker build -t reconcile-ai:local .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests -DskipITs package && mv target/reconcile-ai-*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home reconcile
WORKDIR /opt/reconcile
COPY --from=build /app.jar app.jar
USER 10001
EXPOSE 8080
# Container-aware heap sizing so the JVM respects the pod memory limit.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "app.jar"]
