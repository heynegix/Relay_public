from gateway_meshtastic_adapter.protocol import MessageRejected, MeshMessage, decode_message, dedupe_key, encode_message
import unittest


def message(**overrides):
    values = dict(request_id="req-1", shelter_id="shelter-1", urgency="HIGH", coarse_location="north", payload_hash="a" * 64, created_at=100)
    values.update(overrides)
    return MeshMessage(**values)


class ProtocolTest(unittest.TestCase):
    def test_round_trip_and_dedupe(self):
        encoded = encode_message(message(), now=101)
        self.assertEqual(decode_message(encoded, now=101), message())
        self.assertEqual(dedupe_key(message()), dedupe_key(message()))

    def test_expiry_and_size_rejected(self):
        with self.assertRaises(MessageRejected):
            encode_message(message(created_at=0), now=1000)
        with self.assertRaises(MessageRejected):
            encode_message(message(coarse_location="x" * 400), now=101)
