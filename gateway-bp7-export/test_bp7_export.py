import unittest
from pathlib import Path

from bp7_export import RelayEnvelope, export_bundle, import_fixture


class Bp7ExportTests(unittest.TestCase):
    def test_golden_round_trip(self):
        envelope = RelayEnvelope("msg-1", 3600, "a" * 64, "HIGH")
        golden = Path(__file__).parent / "fixtures" / "golden-relay-envelope.json"
        self.assertEqual(export_bundle(envelope), golden.read_bytes())
        self.assertEqual(import_fixture(golden.read_bytes()), envelope)

    def test_invalid_envelopes_rejected(self):
        for envelope in (
            RelayEnvelope("", 3600, "a" * 64, "HIGH"),
            RelayEnvelope("msg", 0, "a" * 64, "HIGH"),
            RelayEnvelope("msg", 3600, "not-a-hash", "HIGH"),
        ):
            with self.subTest(envelope=envelope):
                with self.assertRaises(ValueError):
                    export_bundle(envelope)

    def test_invalid_fixture_rejected(self):
        with self.assertRaises(ValueError):
            import_fixture(b'{"bpVersion":6}')


if __name__ == "__main__":
    unittest.main()
