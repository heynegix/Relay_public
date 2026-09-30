using System.Security.Cryptography;
using Relay.PcBleBridge.Protocol;

namespace Relay.PcBleBridge.Runtime;

/// <summary>
/// Opaque, fail-closed handoff worker.  Session buffers exist only in process
/// memory and are discarded on every error or timeout.
/// </summary>
public sealed class BleBridgeWorker(IBleGattPeripheral peripheral, ILoopbackGatewayIngress ingress, string bridgeBearerToken)
{
    public async Task RunAsync(BleIdentityAdvertisement identity, CancellationToken cancellationToken)
    {
        await peripheral.StartAsync(identity, cancellationToken);
        try
        {
            SingleEnvelopeSession? session = null;
            StartMetadataReassembly? startMetadata = null;
            DeliveryStartMetadata? deliveryMetadata = null;
            await foreach (var frame in peripheral.ReceiveFramesAsync(cancellationToken))
            {
                try
                {
                    if (frame.Kind == GattFrameKind.Start)
                    {
                        // A new sequence-zero START explicitly supersedes an incomplete
                        // session. Any other START must continue the same bounded,
                        // ordered metadata sequence.
                        if (startMetadata is null || frame.Sequence == 0)
                        {
                            session?.Abort();
                            startMetadata?.Abort();
                            session = null;
                            deliveryMetadata = null;
                            startMetadata = new StartMetadataReassembly(frame.SessionId, DateTimeOffset.UtcNow);
                        }
                        if (!startMetadata.Append(frame, DateTimeOffset.UtcNow)) continue;
                        deliveryMetadata = startMetadata.Complete(DateTimeOffset.UtcNow);
                        startMetadata = null;
                        session = new SingleEnvelopeSession(frame.SessionId, DateTimeOffset.UtcNow);
                        continue;
                    }
                    if (frame.Kind == GattFrameKind.Abort)
                    {
                        session?.Abort();
                        startMetadata?.Abort();
                        session = null;
                        startMetadata = null;
                        deliveryMetadata = null;
                        continue;
                    }
                    if (session is null || deliveryMetadata is null) throw new InvalidDataException("No active BLE session.");
                    if (frame.Kind == GattFrameKind.Chunk) { session.Append(frame, DateTimeOffset.UtcNow); continue; }
                    if (frame.Kind != GattFrameKind.Commit) throw new InvalidDataException("Unsupported BLE frame.");
                    if (!session.AppendCommitDigest(frame, DateTimeOffset.UtcNow)) continue;

                    var completed = session.Complete(DateTimeOffset.UtcNow);
                    var result = await ingress.SubmitAsync(new LoopbackIngressRequest(
                        completed.EncryptedEnvelope,
                        completed.Sha256,
                        bridgeBearerToken,
                        CarrierId: deliveryMetadata.CarrierId,
                        CourierDeliveryId: deliveryMetadata.CourierDeliveryId), cancellationToken);
                    var response = result.Accepted ? result.SignedReceipt ?? [] : System.Text.Encoding.UTF8.GetBytes(result.FailureCode ?? "rejected");
                    foreach (var resultFrame in GattFrame.Fragment(GattFrameKind.Result, completed.SessionId, response))
                        await peripheral.SendResultAsync(resultFrame, cancellationToken);
                    session = null;
                    deliveryMetadata = null;
                }
                catch (Exception ex) when (ex is InvalidDataException or InvalidOperationException or CryptographicException)
                {
                    session?.Abort();
                    startMetadata?.Abort();
                    session = null;
                    startMetadata = null;
                    deliveryMetadata = null;
                    // Do not log request bytes, frame payloads, hashes, or receipts.
                }
            }
        }
        finally
        {
            await peripheral.StopAsync(CancellationToken.None);
        }
    }
}
