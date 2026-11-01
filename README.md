# 🖊️ PenFlow

**PenFlow** turns your Android tablet or smartphone into an ultra-low-latency, high-performance drawing tablet designed specifically for **osu!** and digital art. 

It integrates natively with **[OpenTabletDriver](https://opentabletdriver.net/) (OTD)**, allowing you to use all of OTD's powerful features (filters, custom tablet areas, anti-smoothing, reconstructor, latency reduction, and bindings) directly with your Android device over a USB cable.

---

## ✨ Features

- ⚡ **Ultra-Low Latency (Sub-millisecond):** Direct USB ADB reverse socket streaming (no WiFi jitter or lag).
- 🔄 **High Polling Rate:** Up to **1000Hz** reporting rate with smooth sub-frame interpolation.
- 🎯 **Native OpenTabletDriver Plugin:**
  - Registers as a native virtual tablet inside OpenTabletDriver (`PenFlow Virtual Tablet`).
  - No background server app required! Just run OpenTabletDriver.
  - Automatic detection and connection as soon as your device is plugged in.
- 📏 **1:1 Millimeter Physical Mapping:**
  - Maintains exact millimeter-to-millimeter sensitivity between your screen and OTD.
  - Custom in-app area selection that seamlessly syncs with OTD without double-cropping.
- ✍️ **Universal Pen & Touch Support:**
  - **Active Stylus:** Full support for Lenovo Tab Pen Plus, Samsung S-Pen, and other active styluses with **Hover detection**, **Pressure sensitivity**, **Tilt**, and **Pen Buttons**.
  - **Capacitive Touch:** Play with your fingers or rubber-tip styluses on phones and tablets.
  - **Configurable Palm Rejection:** Reject palm touches while using an active stylus.
- 📱 **Modern Android Client:**
  - Built with Kotlin and Jetpack Compose.
  - Dynamic local ink trail indicator option.
  - Clean UI with customizable settings.

---

## 📦 Ready-to-Use Downloads

You can download the pre-compiled binaries directly from the [`release/`](./release) folder or the GitHub Releases page:
- 📱 **Android Client:** [`release/PenFlow.apk`](./release/PenFlow.apk)
- 🔌 **OpenTabletDriver Plugin:** [`release/PenFlow.OTD.Plugin.dll`](./release/PenFlow.OTD.Plugin.dll)

---

## 🚀 Quick Setup Guide

### 1. Requirements
- PC running Windows with **[OpenTabletDriver (0.6.x)](https://opentabletdriver.net/)** installed.
- Android Tablet or Phone (Android 8.0 or newer).
- USB Cable connecting your Android device to PC.
- Android SDK Platform-tools (`adb.exe`) or USB Debugging enabled on your Android device.

---

### 2. Install the OpenTabletDriver Plugin
1. Download **`PenFlow.OTD.Plugin.dll`**.
2. Open Windows Run (`Win + R`) and paste:
   ```cmd
   %LOCALAPPDATA%\OpenTabletDriver\Plugins
   ```
3. Create a folder named `PenFlow` and place `PenFlow.OTD.Plugin.dll` inside:
   ```text
   %LOCALAPPDATA%\OpenTabletDriver\Plugins\PenFlow\PenFlow.OTD.Plugin.dll
   ```
4. Restart OpenTabletDriver.

---

### 3. Install the Android App
1. Enable **Developer Options** and **USB Debugging** on your Android device:
   - Go to **Settings > About Phone / Tablet**.
   - Tap **Build Number** 7 times until Developer Mode is unlocked.
   - Go to **Settings > System > Developer Options** and enable **USB Debugging**.
2. Install **`PenFlow.apk`** on your device.
3. Open the **PenFlow** app on your Android device.

---

### 4. Connect and Play
1. Connect your Android device to your PC using the USB cable.
2. In a command prompt / PowerShell, set up ADB reverse (or let the plugin auto-detect):
   ```cmd
   adb reverse tcp:9876 tcp:9876
   ```
3. Open **OpenTabletDriver**. You will see **`PenFlow Virtual Tablet`** detected automatically!
4. Configure your preferred tablet area, smoothing/reconstructor filters, and screen mapping in OpenTabletDriver just like a physical Wacom tablet.
5. Launch osu! and enjoy! 🎮

---

## 🛠️ Project Structure

```text
PenFlow/
├── PenFlow-Android/                # Android client application (Kotlin / Compose)
│   ├── app/src/main/java/com/penflow/tablet/
│   │   ├── model/                  # Binary packet protocol & settings
│   │   ├── network/                # High-speed USB socket client
│   │   └── ui/                     # Stylus capture surface & Compose UI
├── PenFlow-OpenTabletDriver-Plugin/ # C# OpenTabletDriver device hub & report parser
│   ├── PenFlowDeviceHub.cs         # Virtual tablet device endpoint
│   ├── PenFlowPluginBootstrapper.cs# Network receiver & auto-bootstrap
│   ├── PenFlowReportParser.cs      # Packet deserializer & 1:1 area mapping
│   └── lib/                        # OTD reference assemblies for building
└── release/                        # Pre-compiled ready-to-run APK & DLL
```

---

## ⚙️ Building from Source

### Building the OpenTabletDriver Plugin (.NET 8)
```powershell
cd PenFlow-OpenTabletDriver-Plugin
dotnet build -c Release
```
The output DLL will be in `bin/Release/net8.0/PenFlow.OTD.Plugin.dll`.

### Building the Android App (Gradle)
```powershell
cd PenFlow-Android
./gradlew assembleDebug
```
The output APK will be in `app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License
This project is open-source under the MIT License. See [LICENSE](LICENSE) for details.
