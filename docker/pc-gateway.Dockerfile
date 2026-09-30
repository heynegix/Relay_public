# The distribution is built with :pc-gateway:installDist before Docker Compose starts this service.
FROM eclipse-temurin:17-jre@sha256:92999aea37688157a53a40bfcb187c30f317422e028045fd5fc5c548fde9e626

WORKDIR /opt/relay
COPY pc-gateway/build/install/pc-gateway/lib/ ./lib/
COPY docker/pc-gateway-entrypoint.sh /opt/relay/entrypoint.sh
RUN chmod 700 /opt/relay/entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/opt/relay/entrypoint.sh"]
