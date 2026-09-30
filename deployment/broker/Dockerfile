# Build the distribution with the repository Gradle wrapper before Docker Compose.
FROM eclipse-temurin:17-jre@sha256:92999aea37688157a53a40bfcb187c30f317422e028045fd5fc5c548fde9e626
WORKDIR /relay
COPY broker/build/install/broker/ ./broker/
RUN mkdir -p /var/lib/relay-broker
ENV RELAY_BROKER_PROFILE=production \
    RELAY_BROKER_HOST=127.0.0.1 \
    RELAY_BROKER_PORT=8443 \
    RELAY_BROKER_DB_PATH=/var/lib/relay-broker/broker.db
EXPOSE 8443
ENTRYPOINT ["/relay/broker/bin/broker"]
