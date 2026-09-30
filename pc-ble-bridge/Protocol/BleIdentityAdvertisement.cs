namespace Relay.PcBleBridge.Protocol;

/// <summary>
/// The only payload permitted in the BLE advertisement.  All values are
/// delivery metadata; no rescue request data belongs here.
/// </summary>
public sealed record BleIdentityAdvertisement(
    byte ProtocolVersion,
    byte[] SignedManifestFingerprint)
{
    // A legacy BLE advertisement has 31 bytes total. Windows contributes a
    // three-byte Flags field and service data contributes a two-byte AD header
    // plus the 16-byte service UUID, leaving exactly ten bytes for identity.
    public const int ProtocolVersionValue = 2;
    public const int ManifestFingerprintBytes = 9;
    public const int EncodedBytes = 1 + ManifestFingerprintBytes;

    public static BleIdentityAdvertisement Create(ReadOnlySpan<byte> signedManifestFingerprint)
    {
        if (signedManifestFingerprint.Length < ManifestFingerprintBytes)
            throw new ArgumentException("Manifest fingerprint is too short.", nameof(signedManifestFingerprint));
        return new BleIdentityAdvertisement(
            ProtocolVersion: ProtocolVersionValue,
            SignedManifestFingerprint: signedManifestFingerprint[..ManifestFingerprintBytes].ToArray());
    }

    public byte[] Encode()
    {
        if (ProtocolVersion != ProtocolVersionValue || SignedManifestFingerprint.Length != ManifestFingerprintBytes)
            throw new InvalidOperationException("Invalid advertisement identity lengths.");

        return [ProtocolVersion, .. SignedManifestFingerprint];
    }

    /** 128-bit Service Data UUIDs use full little-endian byte order on the BLE wire. */
    public byte[] EncodeServiceData(Guid serviceUuid)
    {
        var uuidBytes = serviceUuid.ToByteArray(bigEndian: true);
        Array.Reverse(uuidBytes);
        return [.. uuidBytes, .. Encode()];
    }
}
