"""Small, dependency-free wire contract for the Meshtastic Gateway adapter."""

from dataclasses import dataclass
import hashlib
import json
import time

MAX_MESSAGE_BYTES = 220
DEFAULT_TTL_SECONDS = 15 * 60


class MessageRejected(ValueError):
    pass


@dataclass(frozen=True)
class MeshMessage:
    request_id: str
    shelter_id: str
    urgency: str
    coarse_location: str
    payload_hash: str
    created_at: int
    ttl_seconds: int = DEFAULT_TTL_SECONDS

    def validate(self, now: int | None = None) -> None:
        if not self.request_id or not self.shelter_id or not self.urgency:
            raise MessageRejected("missing required metadata")
        if len(self.payload_hash) != 64 or any(c not in "0123456789abcdef" for c in self.payload_hash.lower()):
            raise MessageRejected("payload_hash must be sha256 hex")
        if self.ttl_seconds <= 0 or (now if now is not None else int(time.time())) >= self.created_at + self.ttl_seconds:
            raise MessageRejected("message expired")

    def to_dict(self) -> dict[str, object]:
        return {
            "requestId": self.request_id,
            "shelterId": self.shelter_id,
            "urgency": self.urgency,
            "coarseLocation": self.coarse_location,
            "payloadHash": self.payload_hash,
            "createdAt": self.created_at,
            "ttlSeconds": self.ttl_seconds,
        }


def encode_message(message: MeshMessage, now: int | None = None) -> bytes:
    message.validate(now)
    encoded = json.dumps(message.to_dict(), separators=(",", ":"), sort_keys=True).encode("utf-8")
    if len(encoded) > MAX_MESSAGE_BYTES:
        raise MessageRejected("message exceeds radio payload limit")
    return encoded


def decode_message(data: bytes, now: int | None = None) -> MeshMessage:
    if len(data) > MAX_MESSAGE_BYTES:
        raise MessageRejected("message exceeds radio payload limit")
    try:
        raw = json.loads(data.decode("utf-8"))
        message = MeshMessage(
            request_id=str(raw["requestId"]),
            shelter_id=str(raw["shelterId"]),
            urgency=str(raw["urgency"]),
            coarse_location=str(raw.get("coarseLocation", "")),
            payload_hash=str(raw["payloadHash"]).lower(),
            created_at=int(raw["createdAt"]),
            ttl_seconds=int(raw.get("ttlSeconds", DEFAULT_TTL_SECONDS)),
        )
        message.validate(now)
        return message
    except (KeyError, TypeError, ValueError, UnicodeDecodeError) as exc:
        if isinstance(exc, MessageRejected):
            raise
        raise MessageRejected("malformed message") from exc


def dedupe_key(message: MeshMessage) -> str:
    return hashlib.sha256(f"{message.request_id}:{message.payload_hash}".encode()).hexdigest()


class DedupeCache:
    """Small in-process TTL cache for a single adapter process.

    The PC Gateway remains the durable authority. This cache only prevents a
    radio retry from forwarding the same request while the sidecar is alive.
    """

    def __init__(self) -> None:
        self._expires: dict[str, int] = {}

    def seen(self, message: MeshMessage, now: int) -> bool:
        key = dedupe_key(message)
        self._purge(now)
        if key in self._expires:
            return True
        self._expires[key] = min(message.created_at + message.ttl_seconds, now + message.ttl_seconds)
        return False

    def _purge(self, now: int) -> None:
        for key, expiry in list(self._expires.items()):
            if expiry <= now:
                del self._expires[key]
