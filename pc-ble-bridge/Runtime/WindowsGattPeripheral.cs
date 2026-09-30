using System.Runtime.InteropServices.WindowsRuntime;
using System.Threading.Channels;
using Relay.PcBleBridge.Protocol;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Foundation;
using Windows.Storage.Streams;

namespace Relay.PcBleBridge.Runtime;

/// <summary>
/// Windows-only, packaged GATT peripheral. It exposes an identity read,
/// MTU-safe uplink writes and downlink indications. Rescue bytes are held only
/// in the bounded in-process channel used by <see cref="BleBridgeWorker"/>.
/// </summary>
public sealed class WindowsGattPeripheral : IBleGattPeripheral
{
    private const int LegacyAdvertisementBytes = 31;
    private const int FlagsAdStructureBytes = 3;
    private const int ServiceDataAdHeaderBytes = 2;
    private const int ServiceUuidBytes = 16;
    // These UUIDs are a wire contract: change only with an Android client protocol version bump.
    public static readonly Guid ServiceUuid = Guid.Parse("1f7f7e90-4e0a-4b0b-8fad-1e3c5e3f4a01");
    public static readonly Guid IdentityCharacteristicUuid = Guid.Parse("1f7f7e91-4e0a-4b0b-8fad-1e3c5e3f4a01");
    public static readonly Guid UplinkCharacteristicUuid = Guid.Parse("1f7f7e92-4e0a-4b0b-8fad-1e3c5e3f4a01");
    public static readonly Guid DownlinkCharacteristicUuid = Guid.Parse("1f7f7e93-4e0a-4b0b-8fad-1e3c5e3f4a01");

    private readonly Channel<GattFrame> _incoming = Channel.CreateBounded<GattFrame>(new BoundedChannelOptions(256)
    {
        // TryWrite must return false when the worker cannot keep up. DropWrite can report success
        // while discarding the new frame, which would acknowledge data that was never reassembled.
        FullMode = BoundedChannelFullMode.Wait,
        SingleReader = true,
        SingleWriter = false,
    });
    private readonly object _gate = new();
    private GattServiceProvider? _serviceProvider;
    private GattLocalCharacteristic? _identityCharacteristic;
    private GattLocalCharacteristic? _uplinkCharacteristic;
    private GattLocalCharacteristic? _downlinkCharacteristic;
    private BluetoothLEAdvertisementPublisher? _identityPublisher;
    private byte[]? _identityBytes;
    private Exception? _advertisingFailure;
    private bool _started;
    private bool _disposed;

    /// <summary>Checks the runtime prerequisite before attempting to expose an intake endpoint.</summary>
    public static async Task EnsurePeripheralCapabilityAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        if (!OperatingSystem.IsWindowsVersionAtLeast(10, 0, 19041))
            throw new PlatformNotSupportedException("Relay BLE bridge requires Windows 10 version 2004 or later.");
        if (Windows.ApplicationModel.Package.Current is null)
            throw new InvalidOperationException("Relay BLE bridge must run as an installed MSIX packaged application.");

        var adapter = await BluetoothAdapter.GetDefaultAsync().AsTask(cancellationToken).ConfigureAwait(false);
        if (adapter is null)
            throw new InvalidOperationException("No Bluetooth adapter is available for Relay BLE bridge.");
        if (!adapter.IsLowEnergySupported)
            throw new InvalidOperationException("The Bluetooth adapter does not support Bluetooth Low Energy.");
    }

    public async Task StartAsync(BleIdentityAdvertisement identity, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(identity);
        ThrowIfDisposed();
        await EnsurePeripheralCapabilityAsync(cancellationToken).ConfigureAwait(false);
        var identityBytes = identity.Encode(); // Validates fixed-length metadata before any advertisement is emitted.

        lock (_gate)
        {
            if (_started) throw new InvalidOperationException("Relay BLE GATT peripheral is already running.");
            _identityBytes = identityBytes;
        }

        try
        {
            var serviceResult = await GattServiceProvider.CreateAsync(ServiceUuid).AsTask(cancellationToken).ConfigureAwait(false);
            if (serviceResult.Error != BluetoothError.Success || serviceResult.ServiceProvider is null)
                throw new InvalidOperationException("Windows could not create the Relay GATT service.");
            _serviceProvider = serviceResult.ServiceProvider;

            _identityCharacteristic = await CreateCharacteristicAsync(
                IdentityCharacteristicUuid,
                GattCharacteristicProperties.Read,
                cancellationToken).ConfigureAwait(false);
            _uplinkCharacteristic = await CreateCharacteristicAsync(
                UplinkCharacteristicUuid,
                GattCharacteristicProperties.Write | GattCharacteristicProperties.WriteWithoutResponse,
                cancellationToken).ConfigureAwait(false);
            _downlinkCharacteristic = await CreateCharacteristicAsync(
                DownlinkCharacteristicUuid,
                GattCharacteristicProperties.Indicate,
                cancellationToken).ConfigureAwait(false);

            _identityCharacteristic.ReadRequested += OnIdentityReadRequested;
            _uplinkCharacteristic.WriteRequested += OnUplinkWriteRequested;

            StartIdentityAdvertisement(identity, identityBytes);
            _serviceProvider.StartAdvertising(new GattServiceProviderAdvertisingParameters
            {
                IsConnectable = true,
                IsDiscoverable = true,
            });
            ThrowIfAdvertisementFailed();
            lock (_gate) _started = true;
        }
        catch
        {
            await StopAsync(CancellationToken.None).ConfigureAwait(false);
            throw;
        }
    }

    public async IAsyncEnumerable<GattFrame> ReceiveFramesAsync([System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken cancellationToken)
    {
        await foreach (var frame in _incoming.Reader.ReadAllAsync(cancellationToken).ConfigureAwait(false))
            yield return frame;
    }

    public async Task SendResultAsync(GattFrame result, CancellationToken cancellationToken)
    {
        ThrowIfDisposed();
        ThrowIfAdvertisementFailed();
        var characteristic = _downlinkCharacteristic ?? throw new InvalidOperationException("Relay GATT peripheral is not started.");
        var encoded = result.Encode(); // Validates every outbound value before it reaches the radio.
        var results = await characteristic.NotifyValueAsync(encoded.AsBuffer()).AsTask(cancellationToken).ConfigureAwait(false);
        if (results.Count == 0 || results.Any(result => result.Status != GattCommunicationStatus.Success))
            throw new InvalidOperationException("Relay GATT result indication was not accepted by a connected client.");
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        StopCore();
        return Task.CompletedTask;
    }

    public ValueTask DisposeAsync()
    {
        if (_disposed) return ValueTask.CompletedTask;
        _disposed = true;
        StopCore();
        _incoming.Writer.TryComplete();
        return ValueTask.CompletedTask;
    }

    private async Task<GattLocalCharacteristic> CreateCharacteristicAsync(
        Guid uuid,
        GattCharacteristicProperties properties,
        CancellationToken cancellationToken)
    {
        var service = _serviceProvider?.Service ?? throw new InvalidOperationException("GATT service was not created.");
        var result = await service.CreateCharacteristicAsync(uuid, new GattLocalCharacteristicParameters
        {
            CharacteristicProperties = properties,
            ReadProtectionLevel = GattProtectionLevel.Plain,
            WriteProtectionLevel = GattProtectionLevel.Plain,
        }).AsTask(cancellationToken).ConfigureAwait(false);
        if (result.Error != BluetoothError.Success || result.Characteristic is null)
            throw new InvalidOperationException("Windows could not create a Relay GATT characteristic.");
        return result.Characteristic;
    }

    private void OnIdentityReadRequested(GattLocalCharacteristic sender, GattReadRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        _ = RespondIdentityReadAsync(args, deferral);
    }

    private async Task RespondIdentityReadAsync(GattReadRequestedEventArgs args, Deferral deferral)
    {
        try
        {
            var request = await args.GetRequestAsync();
            if (request is null || _identityBytes is null) return;
            var writer = new DataWriter();
            writer.WriteBytes(_identityBytes);
            request.RespondWithValue(writer.DetachBuffer());
        }
        catch
        {
            // A client can disconnect during a read. Do not expose or log its request data.
        }
        finally
        {
            deferral.Complete();
        }
    }

    private void OnUplinkWriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        _ = AcceptUplinkWriteAsync(args, deferral);
    }

    private async Task AcceptUplinkWriteAsync(GattWriteRequestedEventArgs args, Deferral deferral)
    {
        try
        {
            var request = await args.GetRequestAsync();
            if (request is null || _advertisingFailure is not null) return;
            var raw = request.Value.ToArray();
            var frame = GattFrame.Decode(raw);
            // Back pressure is fail-closed. The Android sender gets no success indication for a dropped frame.
            if (!_incoming.Writer.TryWrite(frame)) return;
            request.Respond();
        }
        catch
        {
            // Corrupt, oversize, or disconnected writes are intentionally discarded without logging.
        }
        finally
        {
            deferral.Complete();
        }
    }

    private void StartIdentityAdvertisement(BleIdentityAdvertisement identity, byte[] identityBytes)
    {
        if (FlagsAdStructureBytes + ServiceDataAdHeaderBytes + ServiceUuidBytes + identityBytes.Length > LegacyAdvertisementBytes)
            throw new InvalidOperationException("Relay BLE identity does not fit a legacy advertisement.");
        var advertisement = new BluetoothLEAdvertisement();
        // AD type 0x21: Service Data - 128-bit UUID. The UUID is little-endian on the wire.
        var serviceData = identity.EncodeServiceData(ServiceUuid);
        advertisement.DataSections.Add(new BluetoothLEAdvertisementDataSection(0x21, serviceData.AsBuffer()));

        _identityPublisher = new BluetoothLEAdvertisementPublisher(advertisement);
        _identityPublisher.StatusChanged += OnAdvertisementStatusChanged;
        _identityPublisher.Start();
        if (_identityPublisher.Status == BluetoothLEAdvertisementPublisherStatus.Aborted)
            throw new InvalidOperationException("Windows rejected Relay BLE identity advertising.");
    }

    private void OnAdvertisementStatusChanged(BluetoothLEAdvertisementPublisher sender, BluetoothLEAdvertisementPublisherStatusChangedEventArgs args)
    {
        if (args.Status == BluetoothLEAdvertisementPublisherStatus.Aborted)
            _advertisingFailure = new InvalidOperationException("Relay BLE identity advertising stopped unexpectedly.");
    }

    private void StopCore()
    {
        lock (_gate)
        {
            if (_identityCharacteristic is not null) _identityCharacteristic.ReadRequested -= OnIdentityReadRequested;
            if (_uplinkCharacteristic is not null) _uplinkCharacteristic.WriteRequested -= OnUplinkWriteRequested;
            if (_identityPublisher is not null)
            {
                _identityPublisher.StatusChanged -= OnAdvertisementStatusChanged;
                _identityPublisher.Stop();
            }
            _serviceProvider?.StopAdvertising();
            _identityPublisher = null;
            _identityCharacteristic = null;
            _uplinkCharacteristic = null;
            _downlinkCharacteristic = null;
            _serviceProvider = null;
            _identityBytes = null;
            _started = false;
        }
    }

    private void ThrowIfAdvertisementFailed()
    {
        if (_advertisingFailure is not null) throw new InvalidOperationException("Relay BLE advertisement is unavailable.", _advertisingFailure);
    }

    private void ThrowIfDisposed()
    {
        if (_disposed) throw new ObjectDisposedException(nameof(WindowsGattPeripheral));
    }
}
