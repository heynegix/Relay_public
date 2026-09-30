"""Export-only Relay-to-BPv7 boundary with deterministic golden fixtures."""

from dataclasses import dataclass, asdict
import json
import re
import sys


@dataclass(frozen=True)
class RelayEnvelope:
    message_id: str
    ttl_seconds: int
    payload_hash: str
    priority: str


def export_bundle(envelope: RelayEnvelope) -> bytes:
    if (not envelope.message_id or envelope.ttl_seconds <= 0 or
            not re.fullmatch(r"[0-9a-fA-F]{64}", envelope.payload_hash) or
            envelope.priority not in {"LOW", "NORMAL", "HIGH", "CRITICAL"}):
        raise ValueError("invalid Relay envelope")
    bundle = {
        "bpVersion": 7,
        "primaryBlock": {"source": "relay:gateway", "destination": "dtn:external"},
        "extensionBlocks": [{"type": "relay-envelope", "data": {
            "messageId": envelope.message_id,
            "ttl": envelope.ttl_seconds,
            "payloadHash": envelope.payload_hash.lower(),
            "priority": envelope.priority,
        }}],
    }
    return (json.dumps(bundle, sort_keys=True, separators=(",", ":")) + "\n").encode()


def import_fixture(data: bytes) -> RelayEnvelope:
    raw = json.loads(data)
    if raw.get("bpVersion") != 7:
        raise ValueError("unsupported bundle version")
    extension = next(
        (block for block in raw["extensionBlocks"] if block["type"] == "relay-envelope"),
        None,
    )
    if extension is None:
        raise ValueError("missing relay-envelope extension block")
    data = extension["data"]
    return RelayEnvelope(data["messageId"], int(data["ttl"]), data["payloadHash"], data["priority"])


def main() -> None:
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            raw = json.loads(line)
            envelope = RelayEnvelope(
                str(raw["messageId"]), int(raw["ttl"]), str(raw["payloadHash"]), str(raw["priority"])
            )
            sys.stdout.buffer.write(export_bundle(envelope) + b"\n")
            sys.stdout.flush()
        except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            sys.stdout.write(json.dumps({"status": "rejected", "reason": str(exc)}) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
