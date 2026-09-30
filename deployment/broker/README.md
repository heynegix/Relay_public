# Relay Broker: HTTPS deployment for a limited test

This is for a controlled test, not emergency-service deployment. The Broker stores encrypted
envelopes but is still an internet-facing service: use an approved host, named owner, backup
policy, and a domain you control.

## Host prerequisites

1. A continuously running Linux host with Docker Compose, public TCP ports 80 and 443, and a
   public DNS name.
2. An A/AAAA DNS record for that name pointing to the host.
3. A firewall that allows only TCP 80 and 443 inbound; do not expose the Broker's internal port.

## Start

```bash
./gradlew :broker:installDist
cd deployment/broker
cp .env.example .env
# Set RELAY_PUBLIC_DOMAIN to the DNS name, then start it.
docker compose up -d --build
docker compose logs -f caddy broker
```

Caddy obtains and renews the TLS certificate. Confirm `https://<domain>/v1/health` responds
before configuring a Gateway or building an APK.

## Connect the PC Gateway

Set these values only in the PC Gateway process environment, then restart it:

```text
RELAY_BROKER_URL=https://<domain>
RELAY_BROKER_CREDENTIAL=<scoped credential issued by the Broker>
```

Create the credential on the Broker host with the approved Gateway and shelter IDs. Keep the
printed token out of source control and chat logs:

```text
broker issue-gateway-credential --gateway-id <gateway-id> --shelter-id <shelter-id> --expires-at <epoch-millis>
```

## Build the Android APK

The release APK gets its immutable Broker URL at build time:

```bash
./gradlew :app:assembleDebug -Prelay.broker.endpoint=https://<domain>
```

For a real release use the existing organization signing gate and `assemblePilotRelease`; do not
publish a debug APK. An empty property keeps Broker delivery disabled by design.
