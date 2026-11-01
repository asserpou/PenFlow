using System.Buffers.Binary;
using System.Numerics;
using System.Text.Json;
using OpenTabletDriver.Plugin.Attributes;
using OpenTabletDriver.Plugin.Tablet;

namespace PenFlow.OTD.Plugin;

/// <summary>
/// Full OpenTabletDriver report supporting absolute position, 4096-level pressure,
/// tilt (X/Y), eraser tool, barrel buttons, and hover proximity.
/// Binary layout matches PenFlow 24-byte PenPacket:
///   [0..1]   ushort Magic (0x5046 "PF")
///   [2]      byte PacketType (0x01 = Pointer, 0x02 = Ping)
///   [3]      byte Action (0 = Hover, 1 = Down, 2 = Move, 3 = Up, 4 = Cancel)
///   [4..7]   float NormX (0.0f .. 1.0f)
///   [8..11]  float NormY (0.0f .. 1.0f)
///   [12..15] float Pressure (0.0f .. 1.0f)
///   [16..17] short TiltX (-90 .. +90)
///   [18..19] short TiltY (-90 .. +90)
///   [20]     byte ToolType (1 = Stylus, 2 = Eraser, 3 = Finger)
///   [21]     byte ButtonState (0x01 = BarrelPrimary, 0x02 = Secondary)
///   [22..23] ushort Sequence
/// </summary>
public struct PenFlowTabletReport : ITabletReport, ITiltReport, IEraserReport, IProximityReport
{
    public PenFlowTabletReport(byte[] raw)
    {
        Raw = raw;

        byte action = raw[3];
        float normX = Math.Clamp(BitConverter.ToSingle(raw, 4), 0f, 1f);
        float normY = Math.Clamp(BitConverter.ToSingle(raw, 8), 0f, 1f);
        float normPressure = Math.Clamp(BitConverter.ToSingle(raw, 12), 0f, 1f);
        short tiltX = BinaryPrimitives.ReadInt16LittleEndian(raw.AsSpan(16, 2));
        short tiltY = BinaryPrimitives.ReadInt16LittleEndian(raw.AsSpan(18, 2));
        byte toolType = raw[20];
        byte buttons = raw[21];

        Position = new Vector2(normX * 32767f, normY * 32767f);

        bool isTouching = action == 1 || action == 2; // 1 = ActionDown, 2 = ActionMove
        if (isTouching)
        {
            // Ensure even light stylus contact registers at least 2% pressure so Tip binding activates immediately
            float effective = Math.Max(normPressure, 0.02f);
            Pressure = (uint)Math.Clamp((int)MathF.Round(effective * 4096f), 1, 4096);
        }
        else
        {
            Pressure = 0;
        }

        Tilt = new Vector2(tiltX, tiltY);
        Eraser = toolType == 2; // ToolEraser
        PenButtons =
        [
            (buttons & 0x01) != 0,
            (buttons & 0x02) != 0
        ];
        NearProximity = true;
        HoverDistance = isTouching ? 0u : 10u;
    }

    public byte[] Raw { set; get; }
    public Vector2 Position { set; get; }
    public uint Pressure { set; get; }
    public bool[] PenButtons { set; get; }
    public Vector2 Tilt { set; get; }
    public bool Eraser { set; get; }
    public bool NearProximity { set; get; }
    public uint HoverDistance { set; get; }
}

[PenFlowAutoBootstrap]
[PluginName("PenFlow.OTD.Plugin.PenFlowReportParser")]
public class PenFlowReportParser : IReportParser<IDeviceReport>
{
    private const float OtdVirtualWidthMm = 293.76f;
    private const float OtdVirtualHeightMm = 183.60f;

    private Vector2 _lastValidPosition = new(16383.5f, 16383.5f);

    // Physical dimensions of the active area on the connected Android phone/tablet (in mm)
    private float _deviceWidthMm = 293.76f;  // Defaults to Lenovo Tab P12 12.7" until packet updates it
    private float _deviceHeightMm = 183.60f;

    // Cached OpenTabletDriver Tablet Area from settings.json
    private float _otdAreaWidthMm = 55.0f;
    private float _otdAreaHeightMm = 30.9375f;
    private float _otdAreaCenterXMm = 266.26f;
    private float _otdAreaCenterYMm = 168.13126f;
    private long _lastSettingsCheckTick;
    private DateTime _lastSettingsWriteUtc;

    private void RefreshOtdAreaIfNeeded()
    {
        long nowTick = Environment.TickCount64;
        if (nowTick - _lastSettingsCheckTick < 1000) return;
        _lastSettingsCheckTick = nowTick;

        try
        {
            string localAppData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            string settingsPath = Path.Combine(localAppData, "OpenTabletDriver", "settings.json");
            if (!File.Exists(settingsPath)) return;

            DateTime writeUtc = File.GetLastWriteTimeUtc(settingsPath);
            if (writeUtc == _lastSettingsWriteUtc) return;
            _lastSettingsWriteUtc = writeUtc;

            using var doc = JsonDocument.Parse(File.ReadAllText(settingsPath));
            if (doc.RootElement.TryGetProperty("Profiles", out var profiles) && profiles.ValueKind == JsonValueKind.Array)
            {
                foreach (var profile in profiles.EnumerateArray())
                {
                    if (profile.TryGetProperty("AbsoluteModeSettings", out var abs) &&
                        abs.TryGetProperty("Tablet", out var tab))
                    {
                        if (tab.TryGetProperty("Width", out var wProp) && wProp.TryGetSingle(out float w) && w > 1f)
                            _otdAreaWidthMm = w;
                        if (tab.TryGetProperty("Height", out var hProp) && hProp.TryGetSingle(out float h) && h > 1f)
                            _otdAreaHeightMm = h;
                        if (tab.TryGetProperty("X", out var xProp) && xProp.TryGetSingle(out float x))
                            _otdAreaCenterXMm = x;
                        if (tab.TryGetProperty("Y", out var yProp) && yProp.TryGetSingle(out float y))
                            _otdAreaCenterYMm = y;
                        break;
                    }
                }
            }
        }
        catch
        {
        }
    }

    public IDeviceReport Parse(byte[] report)
    {
        if (report == null || report.Length < 24)
            return new DeviceReport(report ?? []);

        // Verify magic header 0x5046 ('F', 'P' in little-endian) and PacketType == 0x01 (TypePointer)
        if (report[0] != 0x46 || report[1] != 0x50 || report[2] != 0x01)
            return new DeviceReport(report);

        RefreshOtdAreaIfNeeded();

        // Decode physical device active area dimensions (in 0.1mm units) from bytes 22..23
        ushort dimEncoded = BinaryPrimitives.ReadUInt16LittleEndian(report.AsSpan(22, 2));
        if (dimEncoded > 0)
        {
            float mm = (dimEncoded & 0x7FFF) * 0.1f;
            if (mm >= 10f && mm <= 500f)
            {
                if ((dimEncoded & 0x8000) == 0)
                    _deviceWidthMm = mm;
                else
                    _deviceHeightMm = mm;
            }
        }

        var parsed = new PenFlowTabletReport(report);
        byte action = report[3];

        if (action == 4) // ActionCancel: keep last valid position with Pressure = 0 so OTD pipeline never resets
        {
            parsed.Position = _lastValidPosition;
            parsed.Pressure = 0;
            return parsed;
        }

        float normX = Math.Clamp(BitConverter.ToSingle(report, 4), 0f, 1f);
        float normY = Math.Clamp(BitConverter.ToSingle(report, 8), 0f, 1f);
        bool isFullScreenOnDevice = (report[21] & 0x04) != 0;

        float otdLeftMm = _otdAreaCenterXMm - (_otdAreaWidthMm * 0.5f);
        float otdTopMm = _otdAreaCenterYMm - (_otdAreaHeightMm * 0.5f);

        if (!isFullScreenOnDevice)
        {
            // User selected a Custom Area box inside the Android app:
            // Map 0..1 of the Android cyan box directly to 0..1 of OpenTabletDriver's active area so it never double-crops!
            float mappedXMm = otdLeftMm + (normX * _otdAreaWidthMm);
            float mappedYMm = otdTopMm + (normY * _otdAreaHeightMm);
            parsed.Position = new Vector2(
                Math.Clamp(mappedXMm / OtdVirtualWidthMm, 0f, 1f) * 32767f,
                Math.Clamp(mappedYMm / OtdVirtualHeightMm, 0f, 1f) * 32767f
            );
        }
        else if (MathF.Abs(_deviceWidthMm - OtdVirtualWidthMm) > 8f || MathF.Abs(_deviceHeightMm - OtdVirtualHeightMm) > 8f)
        {
            // User selected "Full Tablet" on a phone or different-sized screen:
            // Map 1.0 physical millimeter on the phone screen to 1.0 physical millimeter in OpenTabletDriver,
            // preserving the exact millimeter sensitivity and relative placement (e.g. bottom-right corner) set in OTD!
            float penXMm = normX * _deviceWidthMm;
            float penYMm = normY * _deviceHeightMm;

            float offsetXRatio = Math.Clamp(otdLeftMm / Math.Max(1f, OtdVirtualWidthMm - _otdAreaWidthMm), 0f, 1f);
            float offsetYRatio = Math.Clamp(otdTopMm / Math.Max(1f, OtdVirtualHeightMm - _otdAreaHeightMm), 0f, 1f);

            float deviceAreaLeftMm = Math.Max(0f, _deviceWidthMm - _otdAreaWidthMm) * offsetXRatio;
            float deviceAreaTopMm = Math.Max(0f, _deviceHeightMm - _otdAreaHeightMm) * offsetYRatio;

            float mappedXMm = otdLeftMm + (penXMm - deviceAreaLeftMm);
            float mappedYMm = otdTopMm + (penYMm - deviceAreaTopMm);

            parsed.Position = new Vector2(
                Math.Clamp(mappedXMm / OtdVirtualWidthMm, 0f, 1f) * 32767f,
                Math.Clamp(mappedYMm / OtdVirtualHeightMm, 0f, 1f) * 32767f
            );
        }

        _lastValidPosition = parsed.Position;
        return parsed;
    }
}
