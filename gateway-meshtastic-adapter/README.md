# Relay Gateway Meshtastic adapter

This is an out-of-process, Gateway-boundary adapter. It deliberately has no
dependency on Relay core, the Android APK, or the deprecated Meshtastic Android
AIDL service. The adapter accepts a small JSONL envelope on stdin and emits
JSONL events on stdout. A real radio backend is optional and loaded only when
`--mesh-port` is supplied.

The wire contract is intentionally small: `requestId`, `shelterId`,
`urgency`, coarse location, and `payloadHash`. Payloads are capped at 220
UTF-8 bytes (the conservative Meshtastic application payload budget used by
this adapter), have a 15 minute TTL, and are deduplicated by request ID and
payload hash. Expired inbound messages are discarded.

```text
python -m gateway_meshtastic_adapter.main --mock
```

The optional production backend uses the separately installed `meshtastic`
package through `SerialInterface`/`TCPInterface`/`BLEInterface`; no Meshtastic
source is vendored or linked into Relay.
