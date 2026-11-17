FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /workspace
COPY . .
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/* \
    && chmod +x mvnw \
    && ./mvnw --batch-mode -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home app
WORKDIR /app
COPY --from=build --chown=app:app /workspace/target/juris-rag-0.1.0.jar /app/juris-rag.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/juris-rag.jar"]
