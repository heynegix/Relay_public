using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Relay.PcBleBridge.Protocol;

namespace Relay.PcBleBridge.Runtime;

/// <summary>
/// Authenticated, loopback-only client for the JVM Gateway BLE ingress.
/// It serializes opaque envelope bytes only and never writes request or receipt
/// bytes to disk or to diagnostics.
/// </summary>
public sealed class HttpLoopbackGatewayIngress : ILoopbackGatewayIngress, IDisposable
{
    private const string DeliveryPath = "/api/internal/rescue/ble/deliver";
    private const string HmacDomain = "relay.ble-ingress.v1";
    private readonly HttpClient _client;
    private readonly bool _ownsClient;

    public HttpLoopbackGatewayIngress(int port, HttpClient? client = null)
    {
        if (port is < 1 or > 65535) throw new ArgumentOutOfRangeException(nameof(port));
        _client = client ?? new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        _ownsClient = client is null;
        Endpoint = new Uri($"http://127.0.0.1:{port}{DeliveryPath}");
    }

    public Uri Endpoint { get; }

    public async Task<LoopbackIngressResult> SubmitAsync(LoopbackIngressRequest request, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(request);
        if (request.EncryptedEnvelope.Length is < 1 or > BridgeLimits.MaxEnvelopeBytes || request.Sha256.Length != 32)
            return new LoopbackIngressResult(false, null, "invalid_envelope");
        if (!CryptographicOperations.FixedTimeEquals(SHA256.HashData(request.EncryptedEnvelope), request.Sha256))
            return new LoopbackIngressResult(false, null, "hash_mismatch");
        if (string.IsNullOrWhiteSpace(request.BridgeBearerToken) || request.BridgeBearerToken.Length < 32)
            return new LoopbackIngressResult(false, null, "bridge_not_configured");

        var body = SerializeRequest(request);
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var nonce = Convert.ToBase64String(RandomNumberGenerator.GetBytes(24)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        var signature = Sign(request.BridgeBearerToken, timestamp, nonce, body);
        using var message = new HttpRequestMessage(HttpMethod.Post, Endpoint)
        {
            Content = new ByteArrayContent(body),
        };
        message.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
        message.Headers.TryAddWithoutValidation("X-Relay-Ble-Timestamp", timestamp.ToString(System.Globalization.CultureInfo.InvariantCulture));
        message.Headers.TryAddWithoutValidation("X-Relay-Ble-Nonce", nonce);
        message.Headers.TryAddWithoutValidation("X-Relay-Ble-Signature", signature);

        try
        {
            using var response = await _client.SendAsync(message, HttpCompletionOption.ResponseContentRead, cancellationToken).ConfigureAwait(false);
            var raw = await response.Content.ReadAsByteArrayAsync(cancellationToken).ConfigureAwait(false);
            return ParseResponse(response.StatusCode, raw);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            throw;
        }
        catch
        {
            // The worker retains no completed receipt after this point; Android retries with its delivery id.
            return new LoopbackIngressResult(false, null, "gateway_unavailable");
        }
    }

    public void Dispose()
    {
        if (_ownsClient) _client.Dispose();
    }

    private static byte[] SerializeRequest(LoopbackIngressRequest request)
    {
        using var stream = new MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WriteString("carrierId", request.CarrierId);
            writer.WriteString("courierDeliveryId", request.CourierDeliveryId);
            writer.WriteString("envelopeBase64", Convert.ToBase64String(request.EncryptedEnvelope));
            writer.WriteEndObject();
        }
        return stream.ToArray();
    }

    private static string Sign(string secret, long timestamp, string nonce, byte[] body)
    {
        var bodyHash = Convert.ToHexString(SHA256.HashData(body)).ToLowerInvariant();
        var input = Encoding.UTF8.GetBytes($"{HmacDomain}\nPOST\n{DeliveryPath}\n{timestamp}\n{nonce}\n{bodyHash}");
        return Convert.ToBase64String(HMACSHA256.HashData(Encoding.UTF8.GetBytes(secret), input));
    }

    private static LoopbackIngressResult ParseResponse(HttpStatusCode statusCode, byte[] raw)
    {
        try
        {
            using var json = JsonDocument.Parse(raw);
            var root = json.RootElement;
            var status = root.TryGetProperty("status", out var statusElement) ? statusElement.GetString() : null;
            if (status is "accepted" or "duplicate" && root.TryGetProperty("receipt", out var receipt))
                return new LoopbackIngressResult(true, Encoding.UTF8.GetBytes(receipt.GetRawText()), null);
            var failure = root.TryGetProperty("rejectionCode", out var code) ? code.GetString() : null;
            return new LoopbackIngressResult(false, null, failure ?? $"gateway_{(int)statusCode}");
        }
        catch
        {
            return new LoopbackIngressResult(false, null, $"gateway_{(int)statusCode}");
        }
    }
}
