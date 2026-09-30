using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text;

namespace Relay.PcBleBridge.Protocol;

public enum GattFrameKind : byte
{
    Start = 1,
    Chunk = 2,
    Commit = 3,
    Result = 4,
    Abort = 5,
}

/// <summary>
/// A 20-byte ATT value is safe when the negotiated BLE MTU is only 23 bytes.
/// This codec reserves ten bytes for routing/ordering and leaves ten bytes for
/// data. Larger encrypted envelopes are split before writing the GATT value.
/// </summary>
public sealed record GattFrame(
    GattFrameKind Kind,
    ushort Sequence,
    ushort Total,
    byte[] SessionId,
    byte[] Payload)
{
    public const int AttPayloadBytesAtMtu23 = 20;
    public const int HeaderBytes = 10;
    public const int MaxPayloadBytes = AttPayloadBytesAtMtu23 - HeaderBytes;
    public const int SessionIdBytes = 4;

    public byte[] Encode()
    {
        if (SessionId.Length != SessionIdBytes) throw new InvalidOperationException("Session ID must be four bytes.");
        if (Payload.Length > MaxPayloadBytes) throw new InvalidOperationException("Frame payload exceeds MTU-safe limit.");
        var encoded = new byte[HeaderBytes + Payload.Length];
        encoded[0] = (byte)Kind;
        encoded[1] = (byte)Payload.Length;
        BinaryPrimitives.WriteUInt16BigEndian(encoded.AsSpan(2), Sequence);
        BinaryPrimitives.WriteUInt16BigEndian(encoded.AsSpan(4), Total);
        SessionId.CopyTo(encoded, 6);
        Payload.CopyTo(encoded, HeaderBytes);
        return encoded;
    }

    public static GattFrame Decode(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length < HeaderBytes || bytes.Length > AttPayloadBytesAtMtu23)
            throw new InvalidDataException("GATT frame length is invalid.");
        var payloadLength = bytes[1];
        if (payloadLength > MaxPayloadBytes || bytes.Length != HeaderBytes + payloadLength)
            throw new InvalidDataException("GATT frame payload length is invalid.");
        if (!Enum.IsDefined((GattFrameKind)bytes[0]))
            throw new InvalidDataException("GATT frame kind is invalid.");

        return new GattFrame(
            (GattFrameKind)bytes[0],
            BinaryPrimitives.ReadUInt16BigEndian(bytes[2..4]),
            BinaryPrimitives.ReadUInt16BigEndian(bytes[4..6]),
            bytes[6..10].ToArray(),
            bytes[HeaderBytes..].ToArray());
    }

    public static IReadOnlyList<GattFrame> Fragment(GattFrameKind kind, byte[] sessionId, ReadOnlySpan<byte> payload)
    {
        if (payload.Length > BridgeLimits.MaxEnvelopeBytes) throw new InvalidOperationException("Envelope exceeds 16 KiB.");
        var total = Math.Max(1, (int)Math.Ceiling(payload.Length / (double)MaxPayloadBytes));
        if (total > ushort.MaxValue) throw new InvalidOperationException("Too many BLE fragments.");
        var frames = new List<GattFrame>(total);
        for (var sequence = 0; sequence < total; sequence++)
        {
            var offset = sequence * MaxPayloadBytes;
            var count = Math.Min(MaxPayloadBytes, payload.Length - offset);
            frames.Add(new GattFrame(kind, (ushort)sequence, (ushort)total, sessionId.ToArray(), payload.Slice(offset, count).ToArray()));
        }
        return frames;
    }
}

public static class BridgeLimits
{
    public const int MaxEnvelopeBytes = 16 * 1024;
    // The values must remain compatible with RescueIntakeService.  Keeping the
    // byte bound here makes START fragmentation bounded even before a Gateway
    // request is created.
    public const int MaxCourierDeliveryIdBytes = 256;
    public const int MaxCarrierIdBytes = 128;
    public const int StartMetadataHeaderBytes = 5;
    public const int MaxStartMetadataBytes = StartMetadataHeaderBytes + MaxCourierDeliveryIdBytes + MaxCarrierIdBytes;
    public const int MaxStartFrames = (MaxStartMetadataBytes + GattFrame.MaxPayloadBytes - 1) / GattFrame.MaxPayloadBytes;
    public static readonly TimeSpan SessionTimeout = TimeSpan.FromSeconds(120);
}

/// <summary>
/// Delivery metadata carried before an opaque envelope.  This class deliberately
/// has no relationship to the encrypted rescue body and is never persisted by
/// the bridge.
///
/// START payload wire, fragmented using ordinary GATT START frames:
/// <code>
/// byte  version = 1
/// u16be courierDeliveryIdUtf8Length (1..256)
/// u16be carrierIdUtf8Length        (1..128)
/// byte[courierDeliveryIdUtf8Length] courierDeliveryId UTF-8
/// byte[carrierIdUtf8Length]        carrierId UTF-8
/// </code>
/// The complete metadata is at most 389 bytes / 39 MTU-23-safe frames.  The
/// first START frame has sequence 0 and all fragments use the same nonzero
/// total and session ID.  CHUNK frames are invalid until this metadata has
/// been completely reassembled and validated.
/// </summary>
public sealed record DeliveryStartMetadata(string CourierDeliveryId, string CarrierId)
{
    public const byte WireVersion = 1;
    private static readonly UTF8Encoding StrictUtf8 = new(false, true);

    public byte[] Encode()
    {
        var courier = StrictUtf8.GetBytes(CourierDeliveryId);
        var carrier = StrictUtf8.GetBytes(CarrierId);
        ValidateIdentifier(CourierDeliveryId, courier.Length, BridgeLimits.MaxCourierDeliveryIdBytes, nameof(CourierDeliveryId));
        ValidateIdentifier(CarrierId, carrier.Length, BridgeLimits.MaxCarrierIdBytes, nameof(CarrierId));

        var encoded = new byte[BridgeLimits.StartMetadataHeaderBytes + courier.Length + carrier.Length];
        encoded[0] = WireVersion;
        BinaryPrimitives.WriteUInt16BigEndian(encoded.AsSpan(1, 2), checked((ushort)courier.Length));
        BinaryPrimitives.WriteUInt16BigEndian(encoded.AsSpan(3, 2), checked((ushort)carrier.Length));
        courier.CopyTo(encoded, BridgeLimits.StartMetadataHeaderBytes);
        carrier.CopyTo(encoded, BridgeLimits.StartMetadataHeaderBytes + courier.Length);
        return encoded;
    }

    public static DeliveryStartMetadata Decode(ReadOnlySpan<byte> encoded)
    {
        if (encoded.Length < BridgeLimits.StartMetadataHeaderBytes || encoded.Length > BridgeLimits.MaxStartMetadataBytes)
            throw new InvalidDataException("START metadata length is invalid.");
        if (encoded[0] != WireVersion) throw new InvalidDataException("START metadata version is unsupported.");
        var courierLength = BinaryPrimitives.ReadUInt16BigEndian(encoded.Slice(1, 2));
        var carrierLength = BinaryPrimitives.ReadUInt16BigEndian(encoded.Slice(3, 2));
        if (courierLength is 0 or > BridgeLimits.MaxCourierDeliveryIdBytes || carrierLength is 0 or > BridgeLimits.MaxCarrierIdBytes ||
            encoded.Length != BridgeLimits.StartMetadataHeaderBytes + courierLength + carrierLength)
            throw new InvalidDataException("START metadata identifier lengths are invalid.");

        string courier;
        string carrier;
        try
        {
            courier = StrictUtf8.GetString(encoded.Slice(BridgeLimits.StartMetadataHeaderBytes, courierLength));
            carrier = StrictUtf8.GetString(encoded.Slice(BridgeLimits.StartMetadataHeaderBytes + courierLength, carrierLength));
        }
        catch (DecoderFallbackException ex)
        {
            throw new InvalidDataException("START metadata is not valid UTF-8.", ex);
        }
        ValidateIdentifier(courier, courierLength, BridgeLimits.MaxCourierDeliveryIdBytes, "courierDeliveryId");
        ValidateIdentifier(carrier, carrierLength, BridgeLimits.MaxCarrierIdBytes, "carrierId");
        return new DeliveryStartMetadata(courier, carrier);
    }

    private static void ValidateIdentifier(string value, int byteLength, int maximumBytes, string name)
    {
        if (byteLength < 1 || byteLength > maximumBytes ||
            value.Any(character => !char.IsAsciiLetterOrDigit(character) && character is not '-' and not '_' and not '.' and not ':'))
            throw new InvalidDataException($"{name} is invalid.");
    }
}

/// <summary>Bounded, ordered reassembly of fragmented START metadata.</summary>
public sealed class StartMetadataReassembly
{
    private readonly DateTimeOffset _startedAt;
    private readonly byte[] _sessionId;
    private readonly List<byte> _buffer = [];
    private ushort _nextSequence;
    private ushort? _total;
    private bool _closed;

    public StartMetadataReassembly(byte[] sessionId, DateTimeOffset startedAt)
    {
        if (sessionId.Length != GattFrame.SessionIdBytes) throw new ArgumentException("Session ID must be four bytes.", nameof(sessionId));
        _sessionId = sessionId.ToArray();
        _startedAt = startedAt;
    }

    public bool Append(GattFrame frame, DateTimeOffset now)
    {
        EnsureOpen(now);
        if (frame.Kind != GattFrameKind.Start || !frame.SessionId.SequenceEqual(_sessionId) ||
            frame.Sequence != _nextSequence || frame.Total is 0 or > BridgeLimits.MaxStartFrames ||
            (_total is not null && frame.Total != _total))
            throw new InvalidDataException("Invalid START metadata fragment.");
        _total ??= frame.Total;
        if (_buffer.Count + frame.Payload.Length > BridgeLimits.MaxStartMetadataBytes)
            throw new InvalidDataException("START metadata exceeds the limit.");
        _buffer.AddRange(frame.Payload);
        _nextSequence++;
        return _nextSequence == _total;
    }

    public DeliveryStartMetadata Complete(DateTimeOffset now)
    {
        EnsureOpen(now);
        if (_total is null || _nextSequence != _total) throw new InvalidDataException("START metadata is incomplete.");
        _closed = true;
        return DeliveryStartMetadata.Decode(_buffer.ToArray());
    }

    public void Abort() { _buffer.Clear(); _closed = true; }

    private void EnsureOpen(DateTimeOffset now)
    {
        if (_closed || now - _startedAt > BridgeLimits.SessionTimeout)
            throw new InvalidOperationException("BLE START metadata is closed or timed out.");
    }
}

public sealed record CompletedEnvelope(byte[] SessionId, byte[] EncryptedEnvelope, byte[] Sha256);

/// <summary>In-memory only reassembly for exactly one active courier session.</summary>
public sealed class SingleEnvelopeSession
{
    private readonly DateTimeOffset _startedAt;
    private readonly byte[] _sessionId;
    private readonly List<byte> _buffer = [];
    private ushort _nextSequence;
    private ushort? _total;
    private readonly List<byte> _commitDigest = [];
    private ushort _nextCommitSequence;
    private ushort? _commitTotal;
    private bool _completed;

    public SingleEnvelopeSession(byte[] sessionId, DateTimeOffset startedAt)
    {
        if (sessionId.Length != GattFrame.SessionIdBytes) throw new ArgumentException("Session ID must be four bytes.", nameof(sessionId));
        _sessionId = sessionId.ToArray();
        _startedAt = startedAt;
    }

    public void Append(GattFrame frame, DateTimeOffset now)
    {
        EnsureOpen(now);
        if (frame.Kind != GattFrameKind.Chunk || !frame.SessionId.SequenceEqual(_sessionId)) throw new InvalidDataException("Unexpected GATT fragment.");
        if (frame.Sequence != _nextSequence || frame.Total == 0 || (_total is not null && frame.Total != _total)) throw new InvalidDataException("Out-of-order GATT fragment.");
        _total ??= frame.Total;
        if (_buffer.Count + frame.Payload.Length > BridgeLimits.MaxEnvelopeBytes) throw new InvalidDataException("Envelope exceeds 16 KiB.");
        _buffer.AddRange(frame.Payload);
        _nextSequence++;
    }

    /// <summary>
    /// Receives one fragment of the 32-byte SHA-256 commit digest.  Commit
    /// frames are fragmented too because an MTU-23 GATT write cannot contain a
    /// SHA-256 digest in one ATT value.
    /// </summary>
    public bool AppendCommitDigest(GattFrame commit, DateTimeOffset now)
    {
        EnsureOpen(now);
        if (commit.Kind != GattFrameKind.Commit || !commit.SessionId.SequenceEqual(_sessionId) || _total is null || _nextSequence != _total)
            throw new InvalidDataException("Incomplete GATT session.");
        if (commit.Sequence != _nextCommitSequence || commit.Total == 0 || (_commitTotal is not null && commit.Total != _commitTotal))
            throw new InvalidDataException("Out-of-order commit digest fragment.");
        _commitTotal ??= commit.Total;
        if (_commitDigest.Count + commit.Payload.Length > 32) throw new InvalidDataException("Commit digest is too long.");
        _commitDigest.AddRange(commit.Payload);
        _nextCommitSequence++;
        return _nextCommitSequence == _commitTotal;
    }

    public CompletedEnvelope Complete(DateTimeOffset now)
    {
        EnsureOpen(now);
        if (_commitTotal is null || _nextCommitSequence != _commitTotal || _commitDigest.Count != 32)
            throw new InvalidDataException("Commit digest is incomplete.");
        var encryptedEnvelope = _buffer.ToArray();
        var actual = SHA256.HashData(encryptedEnvelope);
        if (!CryptographicOperations.FixedTimeEquals(actual, _commitDigest.ToArray())) throw new InvalidDataException("Envelope hash mismatch.");
        _completed = true;
        return new CompletedEnvelope(_sessionId.ToArray(), encryptedEnvelope, actual);
    }

    public void Abort() { _buffer.Clear(); _completed = true; }

    private void EnsureOpen(DateTimeOffset now)
    {
        if (_completed || now - _startedAt > BridgeLimits.SessionTimeout) throw new InvalidOperationException("BLE session is closed or timed out.");
    }
}
