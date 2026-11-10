# FITCAMX for Gen5W (Hyundai / Kia / Genesis)

[![Platform](https://img.shields.io/badge/Platform-Gen5W%20(Android%204.4.2%20KitKat%20API%2019)-blue.svg)]()
[![Architecture](https://img.shields.io/badge/Architecture-armeabi--v7a-orange.svg)]()
[![Engine](https://img.shields.io/badge/Video%20Engine-LibVLC%203.3.4-red.svg)]()
[![License](https://img.shields.io/badge/License-AGPL--3.0-green.svg)](LICENSE)
[![Latest Release](https://img.shields.io/badge/Release-v0.6.0-brightgreen.svg)](https://github.com/lemoyneiv/fitcamx-gen5w/releases)

A native, high-performance dashcam viewer and controller application specifically engineered for Hyundai, Kia, and Genesis **Gen5W** navigation head units (10.25" and 12.3" widescreen displays running Android 4.4.2 KitKat API 19).

Designed specifically for **FITCAMX** integrated dashcams (Novatek NT96670 / NT96672 / NT96675 series chipsets).

---

## Screenshots

The application is specifically engineered for Gen5W 24:9 ultra-widescreen infotainment displays (1920×720 and 2560×720), featuring ergonomic driver-side controls, centered 16:9 aspect-ratio video surfaces, and non-intrusive translucent overlays.

### Live View (Front 4K Stream)
*Sub-second RTSP live monitoring with real-time status badges, instant snapshot, emergency clip lock, and camera settings.*
![Live View - Front Camera](docs/screenshots/01_live_view.png)

### SD Card Footage Gallery & Playback
*Indexed SD card clip browser (`CARDV/MOVIE` & `CARDV/EMR`), filter chips, 4K HEVC playback with rate controls, translucent scrub bar overlay, and direct USB export.*
![SD Card Footage Playback](docs/screenshots/02_gallery_playback.png)

### Dual Camera Monitoring (Picture-in-Picture)
*Simultaneous live front and rear camera monitoring with hardware-composited picture-in-picture.*
![Dual Camera View](docs/screenshots/03_dual_pip_view.png)

---

## Installation

### Prerequisites
- Head unit jailbroken/rooted with **[HMG-API](https://github.com/VysseCodes/HMG-API)** (v1.0.0+) or **NEMESIS Manager**.

### Method 1: HMG-API USB Installation (Recommended)
1. Download the latest release package: [**`fitcamx-gen5w-v0.6.0-hmgapi-usb.zip`**](https://github.com/lemoyneiv/fitcamx-gen5w/releases/download/v0.6.0/fitcamx-gen5w-v0.6.0-hmgapi-usb.zip) (also available in [`dist/`](dist/fitcamx-gen5w-v0.6.0-hmgapi-usb.zip) or on the [GitHub Releases page](https://github.com/lemoyneiv/fitcamx-gen5w/releases/tag/v0.6.0)).
2. Extract the archive onto the root of a FAT32-formatted USB flash drive.
   - The flash drive will contain `HMGAPI/updates/fitcamx-gen5w-v0.6.0.zip`.
3. Insert the flash drive into the vehicle's multimedia USB port.
4. Open the **HMG-API** app on the infotainment screen.
5. Go to **Updates**, select **FITCAMX Dashcam**, tap **Install**, then tap **Apply changes**.
6. Tap **Restart unit** (or reboot). The FITCAMX icon will appear in your application launcher.

### Method 2: NEMESIS Package Store
1. Download [**`fitcamx-gen5w-v0.6.0-nemesis-usb.zip`**](https://github.com/lemoyneiv/fitcamx-gen5w/releases/download/v0.6.0/fitcamx-gen5w-v0.6.0-nemesis-usb.zip) (or package [`dist/fitcamx-gen5w-v0.6.0-nemesis-package.zip`](dist/fitcamx-gen5w-v0.6.0-nemesis-package.zip)) and copy to your USB drive.
2. Launch NEMESIS Manager on the head unit and install the package.

> [!NOTE]
> **No Codec Patch Required:** Unlike generic Android media players, this application bundles its own compiled LibVLC native engine and does **not** require KramerTech's `media.codecs` patch or system modifications.

---

## Highlights & Features

- **Embedded LibVLC 3.3.4 Media Engine:**
  - Self-contained software & hardware decoding pipeline (`libvlc.so`, `libvlcjni.so`, `libc++_shared.so`).
  - **Zero external codec dependencies** — operates standalone without requiring KramerTech's `media.codecs` patch or system modifications.
  - Smooth RTSP streaming with sub-second latency for Front, Rear, and Dual camera feeds (`rtsp://192.168.1.254/xxx.mov`).
  - Uninterrupted recording: Live stream monitoring does not pause or interrupt ongoing dashcam recording.

- **Dual-Profile SD Card Playback Engine:**
  - **Front 4K / 1440p HEVC Clips:** Because the vehicle's Telechips TCC803x processor lacks hardware HEVC decoding silicon, Front clips are played via a specialized keyframe review engine. The player decodes full-resolution intra/keyframes in high quality while dilating presentation timing (`0.33x` slideshow pacing) and decoupling stream clocks (`:no-ts-trust-pcr`). This allows crystal-clear incident review without overloading the 1.0 GHz Cortex-A7 CPU cores or dropping frames.
  - **Rear 1080p H.264 Clips:** Plays at full 30 fps 1.0x speed with complete hardware/SIMD acceleration.
  - Audio is automatically muted during gallery playback to prevent interfering with vehicle radio/audio focus.

- **Optimized 24:9 / 8:3 Widescreen Interface:**
  - Ergonomic vertical control sidebar along the left display edge for direct driver reach.
  - Maximized video viewing area for both live streams and SD card clip playback.
  - Automatic aspect ratio protection (`android.max_aspect="4.0"`) and dynamic surface layout scaling ensure video feeds are always centered with true 16:9 geometry and zero letterbox distortion.

- **Complete Dashcam Remote Control:**
  - **Camera Switching:** Instantly switch between Front, Rear, and Dual live feeds.
  - **Resolution Setting:** Toggle between 4K UHD (`par=0`) and 2K QHD (`par=1`) recording modes with active badge confirmation.
  - **Microphone Control:** Toggle in-cabin audio recording with real-time status query verification (`cmd=2007`).
  - **Instant Snapshot:** Capture still photos while video recording is active (`cmd=2017` / `cmd=1001`).
  - **Emergency Lock:** Write-protect current video segments (`cmd=2001&par=2` / `cmd=2011&par=3`).
  - **Automatic Clock Sync:** Synchronizes dashcam internal RTC clock with the car's system clock (`cmd=3014`) upon connection.

- **SD Card Footage Review & Direct USB Export:**
  - Fast directory indexing: loads and filters thousands of clips from `CARDV/Movie` and `CARDV/EMR`.
  - Filter clips by front/rear camera, chronological timestamp, and file size.
  - Direct SD-to-USB transfer: export selected clips directly from the camera to an attached car USB drive (`/storage/usb0/DashcamFootage/`) with live progress and write speed indicators.

- **Intelligent Wi-Fi Handover & Clean State Restoration:**
  - Automatically transitions between the head unit's SoftAP and client (STA) mode to connect to the dashcam's Wi-Fi network.
  - Accurately restores prior Wi-Fi mode and SoftAP configuration upon exit so wireless phone projection reconnects cleanly.
  - Integrated **"Exit & Reboot"** option invokes the HMG-API root mod service (`svc/reboot.sh`) for a clean head unit restart when desired.

- **Safe & Non-Intrusive Logging:**
  - Automated continuous background disk dumps are disabled to protect flash memory wear.
  - Diagnostics are only written when a USB drive is attached and the user explicitly taps **"Export Log"**.

---

## Supported Vehicles & Hardware

### Head Unit Platforms & Compatibility

> [!IMPORTANT]
> **Tested & Verified Vehicle:** This software has been actively developed, benchmarked, and verified on a **2023 Hyundai Ioniq 5 AWD**.
>
> **Potentially Supported Vehicles (Untested):** Any Hyundai, Kia, or Genesis vehicle equipped with a **Gen5W** widescreen navigation system (10.25" or 12.3" display running Android 4.4.2 KitKat API 19). Because they share the identical underlying Telechips/Mobis hardware architecture and firmware stack, the following vehicles are theoretically compatible, but **have not yet been individually tested or verified**:
> - **Hyundai:** Ioniq 5 (Tested & Verified), Ioniq 6, Kona Electric (Gen5W), Tucson, Santa Fe, Sonata, Palisade
> - **Kia:** EV6, EV9, Niro EV, Sportage, K5, Carnival, Telluride
> - **Genesis:** GV60, G80, GV70, GV80 (Gen5W models)
> - **OS Platform:** Android 4.4.2 KitKat (API 19), Dalvik runtime, ARMv7 (`armeabi-v7a`)

### Dashcam Hardware
- **FITCAMX OEM-Look Dashcams** (Novatek NT96672 / NT96675 / NT96670 architectures).
- Default IP: `192.168.1.254`
- Default RTSP Port: `554`
- Default HTTP Control Port: `80` (or `3333` on select firmware revisions)

---

> [!CAUTION]
> ### Important Legal Disclaimer & Assumption of Risk
> **USE AT YOUR OWN RISK:** This application is provided "AS IS", without warranty of any kind, express or implied. Installing third-party software, modifying system settings, or jailbreaking automotive infotainment hardware carries inherent risks including system instability, potential loss of functionality, or voiding vehicle warranties. The authors and contributors assume no liability for any damage, warranty loss, vehicle malfunction, or legal consequences resulting from the use or installation of this software.
>
> **NON-AFFILIATION NOTICE:**
> * This project is an independent community open-source project and is **NOT affiliated with, authorized by, sponsored by, or endorsed by Hyundai Motor Group** (Hyundai Motor Company, Kia Corporation, Genesis Motor LLC) or any of their subsidiaries. All vehicle names, trademarks, and emblems belong to their respective owners.
> * This project is **NOT affiliated with, authorized by, sponsored by, or endorsed by FITCAMX** or its manufacturer. The mark "FITCAMX" is used strictly for descriptive and nominative fair-use identification of hardware compatibility.
>
> **DRIVER SAFETY NOTICE:**
> Operating or viewing interactive video applications while driving is hazardous and strictly prohibited by traffic safety laws in most jurisdictions. This software is designed and intended solely for use when the vehicle is **safely parked**. Never operate or view video playback while operating the vehicle. Always keep your full attention on driving and the road.
>
> *For the complete legal notice, please read [`DISCLAIMER.md`](DISCLAIMER.md).*

---

## Building from Source

### Prerequisites
- **Operating System:** Linux, macOS, or Windows 10/11
- **JDK:** Java Development Kit 8 or 17 (`javac`, `keytool`)
- **Android SDK:**
  - Build Tools: `30.0.3` (providing `aapt`, `d8`, `zipalign`, `apksigner`)
  - Platforms: `android-19` (`android.jar`)
- **Python:** Python 3.8+
- **OpenSSL:** Standard CLI utility for signing HMG-API packages
- **Repository Checkout:** [HMG-API](https://github.com/VysseCodes/HMG-API) checked out in a sibling directory or under `third_party/HMG-API`

### Build Steps

1. Clone this repository:
   ```bash
   git clone https://github.com/lemoyneiv/fitcamx-gen5w.git
   cd fitcamx-gen5w
   ```

2. Run the build script:
   ```bash
   python tools/build_package.py
   ```

The script will:
1. Generate `R.java` with `aapt`.
2. Compile Java source files targeting Java 8 (`javac --release 8`).
3. Desugar and dex bytecode to Dalvik API 19 format using `d8`.
4. Bundle prebuilt `libvlc.so`, `libvlcjni.so`, and `libc++_shared.so` into the APK.
5. Zipalign and sign `Fitcamx.apk` with v1 JAR signatures (compatible with Android 4.4.2).
6. Package and sign HMG-API and NEMESIS USB packages in `dist/`.

---

## Project Structure

```
fitcamx/
├── app/
│   ├── AndroidManifest.xml         # Package definition, permissions, min/targetSdk 19
│   ├── libs/
│   │   └── libvlc.jar              # LibVLC 3.3.4 Android Java interface
│   ├── res/                        # Layouts, drawables, strings, colors
│   └── src/
│       ├── fitcamx/app/
│       │   ├── MainActivity.java        # Core UI controller & lifecycle coordinator
│       │   ├── CameraClient.java        # Novatek CGI protocol client & state sync
│       │   ├── VlcPlayerManager.java    # LibVLC lifecycle, surface views, RTSP streams
│       │   ├── WifiBridge.java          # Wi-Fi STA/SoftAP handover state machine
│       │   ├── UsbDownloader.java       # Direct SD-to-USB chunked download worker
│       │   ├── HmgapidClient.java       # Root daemon IPC client (LFD1 protocol)
│       │   ├── DebugLogger.java         # Diagnostics collector & on-demand USB exporter
│       │   └── ClipItem.java            # SD card movie clip metadata model
│       └── main/jniLibs/armeabi-v7a/
│           ├── libvlc.so                # Precompiled LibVLC native ARMv7 binary
│           ├── libvlcjni.so             # LibVLC JNI binding
│           └── libc++_shared.so         # LLVM C++ runtime
├── mod/
│   ├── mod.json                    # HMG-API manifest & launcher descriptor
│   ├── svc/
│   │   └── reboot.sh               # Root service script for head unit reboot
│   └── icons/                      # Head unit launcher icons
├── tools/
│   ├── build_package.py            # Automated build, dexing, alignment & signing pipeline
│   ├── setup_android_sdk.py        # Automated SDK setup helper
│   └── sign.py                     # Standalone package signing utility
├── dist/                           # Final signed release packages
├── AGENT_GUIDE.md                  # Comprehensive technical handbook for AI assistants & developers
├── DISCLAIMER.md                   # Detailed legal disclaimers, trademark & driver safety notices
├── LICENSE                         # AGPL-3.0 and third-party notices
└── README.md
```

---

## Technical Documentation for Developers & AI Agents

For an in-depth breakdown of system constraints, Novatek CGI query commands, LibVLC KitKat considerations, and HMG-API root IPC protocol, please refer to [`AGENT_GUIDE.md`](AGENT_GUIDE.md).

---

## License, Disclaimers & Credits

- **Disclaimers & Trademark Notices:** Use of this application is at the user's sole risk. See [`DISCLAIMER.md`](DISCLAIMER.md) for full liability, non-affiliation, and safety notices.
- **Application Code & Mod Packaging:** Licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE).
- **LibVLC for Android:** Licensed under the [GNU Lesser General Public License v2.1 or later (LGPL-2.1+)](https://www.videolan.org/legal.html). Copyright © VideoLAN and VLC authors.
- **HMG-API:** Platform integration specifications developed by [VysseCodes](https://github.com/VysseCodes/HMG-API) under AGPL-3.0.
- **AOSP Platform Framework:** Licensed under the Apache License, Version 2.0. Copyright © The Android Open Source Project.
