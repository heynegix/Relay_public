# Gateway BPv7 export boundary

This boundary exports a Relay envelope to a BPv7-shaped JSON fixture for a
DTN sidecar. It is intentionally export-only: Relay keeps ownership of TTL,
receipt, deduplication, and delivery semantics. A real RFC 9171 encoder can
replace the serializer without changing the mapping contract.

Required mapping: `messageId`, `ttl`, `payloadHash`, and `priority`.
