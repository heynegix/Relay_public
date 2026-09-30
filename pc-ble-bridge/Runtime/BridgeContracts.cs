using Relay.PcBleBridge.Protocol;

namespace Relay.PcBleBridge.Runtime;

public interface IBleGattPeripheral : IAsyncDisposable
{
    /// <summary>Starts advertising only <see cref="BleIdentityAdvertisement"/> metadata.</summary>
    Task StartAsync(BleIdentityAdvertisement identity, CancellationToken cancellationToken);
    IAsyncEnumerable<GattFrame> ReceiveFramesAsync(CancellationToken cancellationToken);
    Task SendResultAsync(GattFrame result, CancellationToken cancellationToken);
    Task StopAsync(CancellationToken cancellationToken);
}

public sealed record LoopbackIngressRequest(
    byte[] EncryptedEnvelope,
    byte[] Sha256,
    string BridgeBearerToken,
    string CarrierId,
    string CourierDeliveryId);

public sealed record LoopbackIngressResult(bool Accepted, byte[]? SignedReceipt, string? FailureCode);

/// <summary>Boundary to localhost Gateway intake; implementations must not persist rescue content.</summary>
public interface ILoopbackGatewayIngress
{
    Task<LoopbackIngressResult> SubmitAsync(LoopbackIngressRequest request, CancellationToken cancellationToken);
}

public sealed class FakeBleGattPeripheral : IBleGattPeripheral
{
    private readonly System.Threading.Channels.Channel<GattFrame> _incoming = System.Threading.Channels.Channel.CreateUnbounded<GattFrame>();
    public BleIdentityAdvertisement? LastAdvertisement { get; private set; }
    public List<GattFrame> Results { get; } = [];
    public Task StartAsync(BleIdentityAdvertisement identity, CancellationToken cancellationToken) { LastAdvertisement = identity; return Task.CompletedTask; }
    public async IAsyncEnumerable<GattFrame> ReceiveFramesAsync([System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
    {
        await foreach (var frame in _incoming.Reader.ReadAllAsync(cancellationToken)) yield return frame;
    }
    public Task SendResultAsync(GattFrame result, CancellationToken cancellationToken) { Results.Add(result); return Task.CompletedTask; }
    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
    public ValueTask InjectAsync(GattFrame frame) => _incoming.Writer.WriteAsync(frame);
}

public sealed class FakeLoopbackGatewayIngress : ILoopbackGatewayIngress
{
    public List<LoopbackIngressRequest> Requests { get; } = [];
    public LoopbackIngressResult NextResult { get; set; } = new(true, null, null);
    public Task<LoopbackIngressResult> SubmitAsync(LoopbackIngressRequest request, CancellationToken cancellationToken)
    {
        Requests.Add(request);
        return Task.FromResult(NextResult);
    }
}
