"""JSONL CLI for the out-of-process Meshtastic adapter."""

import argparse
import importlib
import json
import sys
import time

from .protocol import DedupeCache, decode_message, encode_message


class MockBackend:
    def send(self, encoded: bytes) -> None:
        return None


class MeshtasticBackend:
    def __init__(self, port: str) -> None:
        try:
            module = importlib.import_module("meshtastic")
        except ImportError as exc:
            raise RuntimeError("real backend requires the separately installed meshtastic package") from exc
        if hasattr(module, "serial_interface"):
            self.interface = module.serial_interface.SerialInterface(devPath=port)
        elif hasattr(module, "tcp_interface"):
            self.interface = module.tcp_interface.TCPInterface(hostname=port)
        else:
            raise RuntimeError("installed meshtastic package exposes no supported interface")

    def send(self, encoded: bytes) -> None:
        self.interface.sendText(encoded.decode("utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mock", action="store_true", help="validate JSONL without a radio")
    parser.add_argument("--mesh-port", help="serial/TCP endpoint for the optional real backend")
    args = parser.parse_args()
    if args.mock == bool(args.mesh_port):
        parser.error("choose exactly one of --mock or --mesh-port")
    try:
        backend = MockBackend() if args.mock else MeshtasticBackend(args.mesh_port)
    except RuntimeError as exc:
        parser.error(str(exc))
    cache = DedupeCache()
    for line in sys.stdin:
        if not line.strip():
            continue
        now = int(time.time())
        try:
            message = decode_message(line.encode(), now)
            encoded = encode_message(message, now)
            if cache.seen(message, now):
                event = {"status": "duplicate", "requestId": message.request_id, "payloadHash": message.payload_hash}
            else:
                backend.send(encoded)
                event = {"status": "accepted", "requestId": message.request_id, "payloadHash": message.payload_hash}
            sys.stdout.write(json.dumps(event, sort_keys=True, separators=(",", ":")) + "\n")
            sys.stdout.flush()
        except ValueError as exc:
            sys.stdout.write(json.dumps({"status": "rejected", "reason": str(exc)}, sort_keys=True, separators=(",", ":")) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
