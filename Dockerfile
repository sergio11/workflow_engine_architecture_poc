# ---- Builder Stage ----
FROM docker.io/library/clojure:temurin-21-tools-deps AS builder

WORKDIR /app

# Copy source and build uberjar
COPY deps.edn build.clj /app/
COPY src /app/src
COPY resources /app/resources
RUN clojure -X:uberjar

# ---- Runtime Stage ----
FROM docker.io/library/eclipse-temurin:21-jre

RUN groupadd -r workflowengine && useradd -r -g workflowengine workflowengine

WORKDIR /app
COPY --from=builder /app/target/*.jar /app/workflow-engine.jar

ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0"

USER workflowengine
EXPOSE 3000

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/workflow-engine.jar"]
