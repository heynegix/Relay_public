# Relay PC BLE Bridge

`pc-ble-bridge` is the Windows-side BLE peripheral helper for the automatic
Android courier-to-shelter handoff.  It is deliberately separate from the JVM
Gateway: its only responsibility is to advertise a verified shelter identity,
receive one encrypted envelope at a time over BLE GATT, and pass the opaque
bytes to a local, authenticated loopback ingress.

## Security and privacy boundaries

- The BLE advertisement contains only protocol v2 and a nine-byte
  signed-manifest fingerprint prefix. The ten-byte identity fits the legacy
  31-byte advertisement budget and never contains rescue content, a public
  key, names, or location.
- The bridge treats an envelope as opaque bytes.  It does not decrypt, parse,
  persist, or log plaintext rescue content.
- Each transfer has one session, a 16 KiB ciphertext limit, a SHA-256 digest,
  monotonically ordered fragments, and a 120 second deadline.  A bad fragment
  or digest discards all in-memory session data.
- Before ciphertext chunks, the courier sends a bounded, fragmented `START`
  metadata value. Its exact payload is `version:u8(1) |
  courierDeliveryIdLength:u16be | carrierIdLength:u16be |
  courierDeliveryId:utf8 | carrierId:utf8`. The identifiers use the Gateway's
  ASCII identifier alphabet (`A-Z`, `a-z`, `0-9`, `-`, `_`, `.`, `:`), with
  byte limits of 256 and 128 respectively. The whole value is at most 389
  bytes (39 MTU-23-safe START frames), and is ordered with the usual
  `sequence`/`total` header fields. The bridge will not accept CHUNK frames
  until all START fragments are reassembled and validated. It forwards these
  exact values to the authenticated loopback ingress; it does not synthesize
  IDs from the BLE session.
- The loopback ingress authenticates the bridge with a locally provisioned
  secret supplied at startup, either directly through the environment or read
  from the protected per-user secret file. The bridge never creates, rewrites,
  or prints that secret.
- The final receipt is returned by the Gateway and is passed through unchanged;
  the Android app verifies the signed receipt itself.

## Windows packaging model

This helper is intended to be shipped as an **MSIX packaged, full-trust .NET 8
process**.  Windows BLE GATT peripheral APIs require package identity in the
target deployment model.  The final MSIX manifest must declare Bluetooth
capability and run the helper in the interactive shelter-operator session.
Do not run it as a Scheduled Task under `SYSTEM`: WinRT advertising availability
and user-session device access are not reliable in that model.

The packaged host now includes `WindowsGattPeripheral`, backed by
`GattServiceProvider`. It exposes these fixed UUIDs:

- service: `1f7f7e90-4e0a-4b0b-8fad-1e3c5e3f4a01`
- identity/read: `1f7f7e91-4e0a-4b0b-8fad-1e3c5e3f4a01`
- uplink/write: `1f7f7e92-4e0a-4b0b-8fad-1e3c5e3f4a01`
- downlink/indicate: `1f7f7e93-4e0a-4b0b-8fad-1e3c5e3f4a01`

It emits the encoded `BleIdentityAdvertisement` as 128-bit service data and
returns the same identity value through the read characteristic.  Incoming
values are decoded as `GattFrame` before entering a bounded in-memory channel.
Invalid frames, a full channel, WinRT capability failure, or an aborted
advertisement fail closed and never create a rescue request.

## Packaged host configuration

The host will not start unless all of the following are supplied by the signed
shelter provisioning/launcher layer:

- `RELAY_BLE_SIGNED_MANIFEST_FINGERPRINT_BASE64` (at least 9 bytes)
- either `RELAY_BLE_BRIDGE_SECRET` or `RELAY_BLE_BRIDGE_SECRET_FILE`
- optional `RELAY_BLE_BRIDGE_PORT` (default `18081`)

The bridge reads the same per-user secret file as Gateway by default:
`~/.relay/ble-bridge.key`. It POSTs only opaque envelope bytes to the fixed
loopback endpoint `http://127.0.0.1:<port>/api/internal/rescue/ble/deliver`,
using Gateway's HMAC timestamp/nonce/signature contract. No secret, envelope,
hash, receipt, or request payload is written to logs.

`Package.appxmanifest` supplies the MSIX full-trust-process and Bluetooth
capability declarations. A release packaging project must inject the
organization's publisher identity, signing certificate, and required PNG logo
assets before producing an MSIX; the placeholder identity must never be used
for a deployed package.

## Local limitation

Run the repository-local preflight before asking a packaging workstation to
build the bridge:

```powershell
./Invoke-BridgePreflight.ps1
./Invoke-BridgePreflight.ps1 -RequireReleaseIdentity
```

The first command verifies that an SDK (not just a runtime) is available and
that the manifest's referenced assets exist. The release form additionally
rejects the placeholder publisher identity. It intentionally does not read
secrets or contact a network.

The repository now builds the .NET 8 bridge with a local SDK and produces a
self-contained `win-x64` executable. Development PNG assets and a local
self-signed MSIX are available for smoke tests. The repository intentionally
does not contain an organization publisher or production signing certificate.
Use `New-BridgeMsix.ps1` on the packaging workstation after supplying those
organization-owned artifacts. The generated executable intentionally refuses
to run outside an installed MSIX package. Physical BLE
advertisement/read/write/indication tests remain required before deployment.
