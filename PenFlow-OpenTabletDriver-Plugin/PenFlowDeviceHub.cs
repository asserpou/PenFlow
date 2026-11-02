using System.Collections.Concurrent;
using OpenTabletDriver.Plugin;
using OpenTabletDriver.Plugin.Attributes;
using OpenTabletDriver.Plugin.Devices;

namespace PenFlow.OTD.Plugin;

[PenFlowAutoBootstrap]
[DeviceHub]
[PluginName("PenFlow USB Tablet Hub")]
public class PenFlowDeviceHub : IDeviceHub
{
    public static PenFlowDeviceHub Instance { get; } = new();

    private readonly PenFlowDeviceEndpoint _endpoint = new();

    public event EventHandler<DevicesChangedEventArgs>? DevicesChanged;

    public IEnumerable<IDeviceEndpoint> GetDevices()
    {
        return [_endpoint];
    }

    public void NotifyDeviceReady()
    {
        try
        {
            DevicesChanged?.Invoke(this, new DevicesChangedEventArgs([], [_endpoint]));
        }
        catch (Exception ex)
        {
            Log.Exception(ex);
        }
    }

    internal void PushPacket(byte[] packet24)
    {
        _endpoint.PushPacket(packet24);
    }
}

public class PenFlowDeviceEndpoint : IDeviceEndpoint
{
    public const int PenFlowVendorId = 0x5046;  // "PF" (20550)
    public const int PenFlowProductId = 0x0001; // 1

    private readonly object _streamLock = new();
    private PenFlowEndpointStream? _activeStream;

    public int ProductID => PenFlowProductId;
    public int VendorID => PenFlowVendorId;
    public int InputReportLength => 24;
    public int OutputReportLength => 0;
    public int FeatureReportLength => 0;
    public string Manufacturer => "PenFlow";
    public string ProductName => "PenFlow USB Stylus Tablet";
    public string FriendlyName => "PenFlow Virtual Tablet";
    public string SerialNumber => "PENFLOW-USB-01";
    public string DevicePath => @"\\?\hid#vid_5046&pid_0001#penflow";
    public bool CanOpen => true;
    public IDictionary<string, string>? DeviceAttributes { get; } = new Dictionary<string, string>
    {
        ["Interface"] = "0",
        ["USB_INTERFACE_NUMBER"] = "0"
    };

    public string GetDeviceString(byte index) => index switch
    {
        1 => Manufacturer,
        2 => ProductName,
        3 => SerialNumber,
        _ => string.Empty
    };

    public virtual IDeviceEndpointStream Open()
    {
        lock (_streamLock)
        {
            _activeStream?.Dispose();
            _activeStream = new PenFlowEndpointStream();
            return _activeStream;
        }
    }

    // Required by OpenTabletDriver v0.6.8+ IDeviceEndpoint interface (while remaining 100% compatible with v0.6.6/v0.6.7)
    public virtual void OpenAndSendFeatureInit(byte[] initData) { }

    public virtual void OpenAndSendOutputInit(byte[] initData) { }

    internal void PushPacket(byte[] packet24)
    {
        var stream = Volatile.Read(ref _activeStream);
        stream?.Enqueue(packet24);
    }
}

public sealed class PenFlowEndpointStream : IDeviceEndpointStream
{
    private readonly object _sync = new();
    private readonly List<byte[]> _queue = new(8);
    private readonly ManualResetEventSlim _signal = new(false, spinCount: 200);
    private volatile bool _disposed;

    internal void Enqueue(byte[] packet24)
    {
        if (_disposed) return;

        lock (_sync)
        {
            if (_disposed) return;

            // Bounded low-latency ring buffer (max 16 packets = ~1 frame at 1000Hz)
            // Preserves full 240Hz/1000Hz sub-frame smoothness while preventing any lag backlog!
            while (_queue.Count >= 16)
            {
                _queue.RemoveAt(0);
            }

            _queue.Add(packet24);
            _signal.Set();
        }
    }

    public byte[] Read()
    {
        while (!_disposed)
        {
            lock (_sync)
            {
                if (_disposed) break;

                if (_queue.Count > 0)
                {
                    byte[] packet = _queue[0];
                    _queue.RemoveAt(0);
                    if (_queue.Count == 0)
                    {
                        _signal.Reset();
                    }
                    return packet;
                }

                _signal.Reset();
            }

            try
            {
                _signal.Wait(100);
            }
            catch (ObjectDisposedException)
            {
                break;
            }
        }

        throw new ObjectDisposedException(nameof(PenFlowEndpointStream));
    }

    public void Write(byte[] buffer) { }
    public void GetFeature(byte[] buffer) { }
    public void SetFeature(byte[] buffer) { }

    public void Dispose()
    {
        if (_disposed) return;
        lock (_sync)
        {
            if (_disposed) return;
            _disposed = true;
            _queue.Clear();
            try
            {
                _signal.Set();
                _signal.Dispose();
            }
            catch
            {
            }
        }
    }
}
