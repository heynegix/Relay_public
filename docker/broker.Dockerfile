# The application distribution is built by Gradle before `docker compose up`.
FROM eclipse-temurin:17-jre@sha256:92999aea37688157a53a40bfcb187c30f317422e028045fd5fc5c548fde9e626

WORKDIR /opt/relay
COPY broker/build/install/broker/lib/ ./lib/

EXPOSE 8443
ENTRYPOINT ["java", "-cp", "/opt/relay/lib/*", "com.example.relay.broker.MainKt"]
