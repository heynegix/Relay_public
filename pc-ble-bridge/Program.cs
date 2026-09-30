using Relay.PcBleBridge.Runtime;

namespace Relay.PcBleBridge;

internal static class Program
{
    private static async Task<int> Main()
    {
        try
        {
            var configuration = BridgeHostConfiguration.Load();
            await WindowsGattPeripheral.EnsurePeripheralCapabilityAsync(CancellationToken.None).ConfigureAwait(false);
            await using var peripheral = new WindowsGattPeripheral();
            using var ingress = new HttpLoopbackGatewayIngress(configuration.LoopbackPort);
            using var lifetime = new CancellationTokenSource();
            Console.CancelKeyPress += (_, eventArgs) => { eventArgs.Cancel = true; lifetime.Cancel(); };
            var worker = new BleBridgeWorker(peripheral, ingress, configuration.SharedSecret);
            await worker.RunAsync(configuration.Identity, lifetime.Token).ConfigureAwait(false);
            return 0;
        }
        catch (OperationCanceledException)
        {
            return 0;
        }
        catch
        {
            // Do not print environment configuration, manifest fingerprints, secrets, envelope data, or receipts.
            Console.Error.WriteLine("Relay PC BLE Bridge could not start. Check MSIX installation, Bluetooth availability, signed shelter provisioning, and local Gateway availability.");
            return 1;
        }
    }
}
