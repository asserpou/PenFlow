using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Runtime.CompilerServices;
using OpenTabletDriver;
using OpenTabletDriver.Desktop;
using OpenTabletDriver.Plugin;
using OpenTabletDriver.Plugin.Attributes;
using OpenTabletDriver.Plugin.Tablet;

namespace PenFlow.OTD.Plugin;

/// <summary>
/// Automatically registers the PenFlow Virtual Tablet configuration and DeviceHub
/// when OpenTabletDriver loads this plugin assembly, and listens for ultra-low-latency
/// 24-byte stylus packets over both UDP (127.0.0.1:9877 bridge from PenFlow-Server)
/// and TCP (127.0.0.1:9876 direct USB ADB reverse when running standalone).
/// </summary>
[AttributeUsage(AttributeTargets.Class, AllowMultiple = false, Inherited = true)]
public sealed class PenFlowAutoBootstrapAttribute : Attribute
{
    public PenFlowAutoBootstrapAttribute()
    {
        PenFlowPluginBootstrapper.EnsureStarted();
    }
}

public static class PenFlowPluginBootstrapper
{
    private static int _initialized;

    private const string TabletConfigJson = """
    {
      "Name": "PenFlow Virtual Tablet",
      "Specifications": {
        "Digitizer": {
          "Width": 293.76,
          "Height": 183.6,
          "MaxX": 32767.0,
          "MaxY": 32767.0
        },
        "Pen": {
          "MaxPressure": 4096,
          "ButtonCount": 2
        },
        "AuxiliaryButtons": null,
        "MouseButtons": null,
        "Touch": null
      },
      "DigitizerIdentifiers": [
        {
          "VendorID": 20550,
          "ProductID": 1,
          "InputReportLength": 24,
          "OutputReportLength": null,
          "ReportParser": "PenFlow.OTD.Plugin.PenFlowReportParser",
          "FeatureInitReport": null,
          "OutputInitReport": null,
          "DeviceStrings": {},
          "InitializationStrings": []
        }
      ],
      "AuxiliaryDeviceIdentifiers": [],
      "Attributes": {
        "libinputoverride": "1"
      }
    }
    """;

    [ModuleInitializer]
    public static void Initialize()
    {
        EnsureStarted();
    }

    public static void EnsureStarted()
    {
        if (Interlocked.Exchange(ref _initialized, 1) != 0)
            return;

        try
        {
            string procName = Process.GetCurrentProcess().ProcessName;
            if (procName.Contains("UX", StringComparison.OrdinalIgnoreCase) ||
                procName.Contains("Console", StringComparison.OrdinalIgnoreCase))
            {
                return;
            }
        }
        catch
        {
        }

        try
        {
            WriteConfigurationFile();
        }
        catch
        {
        }

        PenFlowNetworkReceiver.Instance.Start();
        _ = Task.Run(AttachToDriverLoopAsync);
    }

    private static void WriteConfigurationFile()
    {
        var dirs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);

        try
        {
            string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (!string.IsNullOrEmpty(localAppData))
            {
                dirs.Add(Path.Combine(localAppData, "OpenTabletDriver", "Configurations"));
            }
        }
        catch { }

        try
        {
            if (AppInfo.Current?.ConfigurationDirectory is { Length: > 0 } cfgDir)
            {
                dirs.Add(cfgDir);
            }
        }
        catch { }

        foreach (string dir in dirs)
        {
            try
            {
                Directory.CreateDirectory(dir);
                string filePath = Path.Combine(dir, "PenFlow Virtual Tablet.json");
                File.WriteAllText(filePath, TabletConfigJson);
            }
            catch { }
        }
    }

    private static Driver? _attachedDriver;
    private static int _detectInProgress;

    private static async Task AttachToDriverLoopAsync()
    {
        for (int attempt = 0; attempt < 2000; attempt++)
        {
            try
            {
                var pm = AppInfo.PluginManager;
                if (pm != null)
                {
                    var driverObj = pm.GetService(typeof(IDriver));
                    if (driverObj is Driver driver && !ReferenceEquals(driver, _attachedDriver))
                    {
                        _attachedDriver = driver;
                        WriteConfigurationFile();

                        bool alreadyConnected = driver.CompositeDeviceHub.DeviceHubs
                            .Any(h => h is PenFlowDeviceHub || h.GetType().FullName == typeof(PenFlowDeviceHub).FullName);

                        if (!alreadyConnected)
                        {
                            driver.CompositeDeviceHub.ConnectDeviceHub(PenFlowDeviceHub.Instance);
                            Log.Write("PenFlow", "Connected PenFlow USB Tablet Hub to OpenTabletDriver.", LogLevel.Info);
                        }

                        // Wait briefly for initial daemon startup then ensure tablet is detected
                        await Task.Delay(400);
                        EnsureTabletDetected();
                        await Task.Delay(1200);
                        EnsureTabletDetected();
                        break;
                    }
                }
            }
            catch
            {
            }

            // Poll every 2ms during initial startup so we attach before the first Detect() pass
            await Task.Delay(attempt < 500 ? 2 : 25);
        }
    }

    internal static void EnsureTabletDetected()
    {
        try
        {
            var driver = _attachedDriver ?? (AppInfo.PluginManager?.GetService(typeof(IDriver)) as Driver);
            if (driver != null && driver.Tablets.Any(t => t.Properties.Name.Contains("PenFlow", StringComparison.OrdinalIgnoreCase)))
            {
                return;
            }

            PenFlowDeviceHub.Instance.NotifyDeviceReady();

            if (Interlocked.Exchange(ref _detectInProgress, 1) != 0)
                return;

            _ = Task.Run(async () =>
            {
                try
                {
                    string? daemonExe = Environment.ProcessPath;
                    string? dir = !string.IsNullOrEmpty(daemonExe) ? Path.GetDirectoryName(daemonExe) : AppContext.BaseDirectory;
                    if (!string.IsNullOrEmpty(dir))
                    {
                        string consoleExe = Path.Combine(dir, "OpenTabletDriver.Console.exe");
                        if (File.Exists(consoleExe))
                        {
                            using var proc = new Process
                            {
                                StartInfo = new ProcessStartInfo
                                {
                                    FileName = consoleExe,
                                    Arguments = "detect",
                                    WorkingDirectory = dir,
                                    CreateNoWindow = true,
                                    UseShellExecute = false,
                                    RedirectStandardOutput = true,
                                    RedirectStandardError = true
                                }
                            };
                            proc.Start();
                            await proc.WaitForExitAsync();
                        }
                    }
                }
                catch
                {
                }
                finally
                {
                    Interlocked.Exchange(ref _detectInProgress, 0);
                }
            });
        }
        catch
        {
        }
    }
}

[PenFlowAutoBootstrap]
[PluginName("PenFlow USB Tablet Bridge")]
public class PenFlowBridgeTool : ITool
{
    public bool Initialize()
    {
        PenFlowPluginBootstrapper.EnsureStarted();
        return true;
    }

    public void Dispose()
    {
    }
}

internal sealed class PenFlowNetworkReceiver
{
    public static PenFlowNetworkReceiver Instance { get; } = new();

    private const int TcpPort = 9876;
    private const int UdpBridgePort = 9877;
    private const int UdpMirrorToServerPort = 9878;
    private const int PacketSize = 24;

    private readonly CancellationTokenSource _cts = new();
    private int _started;
    private volatile bool _tcpClientConnected;

    public void Start()
    {
        if (Interlocked.Exchange(ref _started, 1) != 0)
            return;

        // 1. Always listen on UDP 127.0.0.1:9877 for packets bridged from PenFlow-Server
        Thread udpThread = new(() => RunUdpBridgeLoop(_cts.Token))
        {
            Name = "PenFlow-OTD-UDP-Receiver",
            IsBackground = true,
            Priority = ThreadPriority.Highest
        };
        udpThread.Start();

        // 2. Also listen on TCP 127.0.0.1:9876 when PenFlow-Server is not holding port 9876
        Thread tcpThread = new(() => RunStandaloneTcpLoop(_cts.Token))
        {
            Name = "PenFlow-OTD-TCP-Receiver",
            IsBackground = true,
            Priority = ThreadPriority.Highest
        };
        tcpThread.Start();

        // 3. Auto-setup `adb reverse tcp:9876 tcp:9876` periodically for standalone USB plug-and-play
        _ = Task.Run(() => RunAdbReverseLoopAsync(_cts.Token));
    }

    private static void RunUdpBridgeLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var udp = new UdpClient();
                udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                udp.Client.Bind(new IPEndPoint(IPAddress.Loopback, UdpBridgePort));
                udp.Client.ReceiveBufferSize = 65536;

                IPEndPoint remote = new(IPAddress.Any, 0);
                while (!ct.IsCancellationRequested)
                {
                    byte[] datagram = udp.Receive(ref remote);
                    if (datagram.Length >= PacketSize)
                    {
                        int offset = 0;
                        while (offset + PacketSize <= datagram.Length)
                        {
                            // Magic 0x5046 (little-endian: 0x46, 0x50) and PacketType == 0x01 (Pointer)
                            if (datagram[offset] == 0x46 && datagram[offset + 1] == 0x50 && datagram[offset + 2] == 0x01)
                            {
                                byte[] packet = new byte[PacketSize];
                                Buffer.BlockCopy(datagram, offset, packet, 0, PacketSize);
                                PenFlowDeviceHub.Instance.PushPacket(packet);
                            }
                            offset += PacketSize;
                        }
                    }
                }
            }
            catch
            {
                Thread.Sleep(500);
            }
        }
    }

    private void RunStandaloneTcpLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            TcpListener? listener = null;
            try
            {
                listener = new TcpListener(IPAddress.Loopback, TcpPort);
                listener.Start();
                Log.Write("PenFlow", $"Standalone USB listener active on 127.0.0.1:{TcpPort}", LogLevel.Info);

                while (!ct.IsCancellationRequested)
                {
                    TcpClient client = listener.AcceptTcpClient();
                    Thread clientThread = new(() => HandleTcpClient(client, ct))
                    {
                        Name = "PenFlow-OTD-USB-Client",
                        IsBackground = true,
                        Priority = ThreadPriority.Highest
                    };
                    clientThread.Start();
                }
            }
            catch (SocketException)
            {
                // Port 9876 is currently owned by PenFlow-Server; we receive via UDP 9877 bridge instead
                Thread.Sleep(2000);
            }
            catch
            {
                Thread.Sleep(1000);
            }
            finally
            {
                try { listener?.Stop(); } catch { }
            }
        }
    }

    private void HandleTcpClient(TcpClient client, CancellationToken ct)
    {
        using (client)
        {
            try
            {
                client.NoDelay = true;
                client.ReceiveBufferSize = 4096;
                client.SendBufferSize = 4096;
                client.ReceiveTimeout = 6000;
                client.SendTimeout = 2000;
                _tcpClientConnected = true;

                using NetworkStream stream = client.GetStream();
                byte[] readBuf = new byte[8192];
                int filled = 0;
                bool loggedConnect = false;

                while (!ct.IsCancellationRequested)
                {
                    int read = stream.Read(readBuf, filled, readBuf.Length - filled);
                    if (read <= 0) break;
                    filled += read;

                    if (!loggedConnect)
                    {
                        loggedConnect = true;
                        Log.Write("PenFlow", "Android tablet connected over USB!", LogLevel.Info);
                        PenFlowPluginBootstrapper.EnsureTabletDetected();
                    }

                    int offset = 0;
                    while (filled - offset >= PacketSize)
                    {
                        if (readBuf[offset] == 0x46 && readBuf[offset + 1] == 0x50)
                        {
                            byte packetType = readBuf[offset + 2];
                            if (packetType == 0x02) // TypePing
                            {
                                try
                                {
                                    stream.Write(readBuf, offset, PacketSize);
                                    stream.Flush();
                                }
                                catch { }
                            }
                            else if (packetType == 0x01) // TypePointer
                            {
                                byte[] packet = new byte[PacketSize];
                                Buffer.BlockCopy(readBuf, offset, packet, 0, PacketSize);
                                PenFlowDeviceHub.SharedEndpoint.PushPacket(packet);
                            }
                            offset += PacketSize;
                        }
                        else
                        {
                            // Resynchronize to next magic byte if stream ever misaligned
                            offset += 1;
                        }
                    }

                    int remaining = filled - offset;
                    if (remaining > 0 && offset > 0)
                    {
                        Buffer.BlockCopy(readBuf, offset, readBuf, 0, remaining);
                    }
                    filled = remaining;
                }
            }
            catch
            {
            }
            finally
            {
                _tcpClientConnected = false;
            }
        }
    }

    private async Task RunAdbReverseLoopAsync(CancellationToken ct)
    {
        string? adbPath = FindAdbExecutable();
        if (adbPath == null) return;

        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (!_tcpClientConnected)
                {
                    using var proc = new Process
                    {
                        StartInfo = new ProcessStartInfo
                        {
                            FileName = adbPath,
                            Arguments = $"reverse tcp:{TcpPort} tcp:{TcpPort}",
                            CreateNoWindow = true,
                            UseShellExecute = false,
                            RedirectStandardOutput = true,
                            RedirectStandardError = true
                        }
                    };
                    proc.Start();
                    await proc.WaitForExitAsync(ct);
                }
            }
            catch
            {
            }

            await Task.Delay(3000, ct);
        }
    }

    private static string? FindAdbExecutable()
    {
        string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        string[] candidates =
        [
            @"C:\Android\platform-tools\adb.exe",
            Path.Combine(localAppData, @"Android\Sdk\platform-tools\adb.exe"),
            @"C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe"
        ];

        foreach (string path in candidates)
        {
            if (File.Exists(path)) return path;
        }

        string? pathEnv = Environment.GetEnvironmentVariable("PATH");
        if (!string.IsNullOrEmpty(pathEnv))
        {
            foreach (string dir in pathEnv.Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries))
            {
                string full = Path.Combine(dir.Trim(), "adb.exe");
                if (File.Exists(full)) return full;
            }
        }

        return null;
    }
}
