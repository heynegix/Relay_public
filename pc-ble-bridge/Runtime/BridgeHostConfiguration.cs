using Relay.PcBleBridge.Protocol;

namespace Relay.PcBleBridge.Runtime;

/// <summary>Reads only the minimum local configuration required by the packaged bridge host.</summary>
internal sealed record BridgeHostConfiguration(BleIdentityAdvertisement Identity, string SharedSecret, int LoopbackPort)
{
    public static BridgeHostConfiguration Load()
    {
        var encodedFingerprint = Required("RELAY_BLE_SIGNED_MANIFEST_FINGERPRINT_BASE64");
        var fingerprint = Convert.FromBase64String(encodedFingerprint);
        if (fingerprint.Length < BleIdentityAdvertisement.ManifestFingerprintBytes)
            throw new InvalidOperationException("Shelter manifest fingerprint is invalid.");
        var secret = ResolveSharedSecret();
        var port = int.TryParse(Environment.GetEnvironmentVariable("RELAY_BLE_BRIDGE_PORT"), out var configuredPort)
            ? configuredPort : 18081;
        if (port is < 1 or > 65535) throw new InvalidOperationException("BLE bridge port is invalid.");
        return new BridgeHostConfiguration(
            BleIdentityAdvertisement.Create(fingerprint),
            secret,
            port);
    }

    private static string ResolveSharedSecret()
    {
        var fromEnvironment = Environment.GetEnvironmentVariable("RELAY_BLE_BRIDGE_SECRET")?.Trim();
        if (!string.IsNullOrEmpty(fromEnvironment)) return ValidateSecret(fromEnvironment);
        var path = Environment.GetEnvironmentVariable("RELAY_BLE_BRIDGE_SECRET_FILE");
        if (string.IsNullOrWhiteSpace(path))
            path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".relay", "ble-bridge.key");
        if (!File.Exists(path)) throw new InvalidOperationException("BLE bridge shared secret is not provisioned.");
        return ValidateSecret(File.ReadAllText(path).Trim());
    }

    private static string ValidateSecret(string secret) => secret.Length >= 32
        ? secret
        : throw new InvalidOperationException("BLE bridge shared secret is invalid.");

    private static string Required(string name) => Environment.GetEnvironmentVariable(name)?.Trim() switch
    {
        { Length: > 0 } value => value,
        _ => throw new InvalidOperationException($"Required Relay bridge configuration is unavailable: {name}"),
    };
}
