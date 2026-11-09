# FITCAMX Gen5W — AI Agent & Developer Engineering Guide

This document is the authoritative engineering guide for AI agents and human developers maintaining, modifying, or extending the **FITCAMX for Gen5W** codebase.

---

## 1. Executive System Profile & Invariants

| Attribute | Specification | Critical Constraint |
| :--- | :--- | :--- |
| **Target OS** | Android 4.4.2 KitKat (API level 19) | Min & Target SDK is fixed to 19. |
| **Runtime** | Dalvik (Pre-ART) | Bytecode must be Dalvik-compatible DEX. Modern Java 8+ APIs that require desugaring must be verified against `d8 --min-api 19`. |
| **Architecture** | 32-bit ARMv7 (`armeabi-v7a`) | All JNI native libraries must be compiled for `armeabi-v7a`. 64-bit binaries will fail to load. |
| **Display Ratio** | 24:9 / 8:3 Ultra-Widescreen (1920x720 / 2560x720) | Must maintain `<meta-data android:name="android.max_aspect" android:value="4.0" />` in `AndroidManifest.xml`. Never use fixed 16:9 assumptions for layout margins. |
| **Packaging & Signing** | Android v1 JAR Signing (apksigner) | Android 4.4.2 does **NOT** support APK Signature Scheme v2. You must always sign with `--v1-signing-enabled true --v2-signing-enabled false`. |
| **Root Platform** | HMG-API (v1.0.0+) / NEMESIS | Mod package is signed with ECDSA SHA256 over `META-HMGAPI/manifest.json`. Root IPC uses `HmgapidClient` (`LFD1` protocol). |

### ⚠️ Strict Operational Invariants

1. **User Communication Privacy Invariant:**
   **NEVER mention or display "Android Auto" in user toasts, dialogs, or UI text.** Always refer to phone projection neutrally (e.g., "Wi-Fi state", "hotspot", or "wireless projection").
2. **Flash Memory Preservation Invariant:**
   Do **NOT** write automated debug dumps to flash storage on app startup, error callbacks, or exit. Write diagnostics **only** when an external USB flash drive is attached and the user explicitly clicks the **"Export Log"** button.
3. **No External Codec Dependency Invariant:**
   Do **NOT** reintroduce `"depends": "media.codecs"` in `mod/mod.json`. Video decoding is performed self-contained inside LibVLC.
4. **Legal & Non-Affiliation Invariant:**
   Maintain clear non-affiliation notices and disclaimers. See [`DISCLAIMER.md`](DISCLAIMER.md) for full legal text. Never claim official affiliation with Hyundai Motor Group or FITCAMX.

---

## 2. Codebase Architecture & File Map

```
fitcamx/
├── app/
│   ├── AndroidManifest.xml         # Declares API 19 target, permissions, landscape, max_aspect
│   ├── libs/
│   │   └── libvlc.jar              # LibVLC 3.3.4 Java API wrappers
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml   # Vertical control sidebar on left, live/gallery surfaces on right
│   │   │   └── item_clip.xml       # Custom ListView item for SD card movie entries
│   │   ├── values/                 # Colors, strings, dimensions
│   │   └── drawable/               # Launcher and button iconography
│   └── src/
│       ├── fitcamx/app/
│       │   ├── MainActivity.java        # Core lifecycle, UI views, tab switching, and button handlers
│       │   ├── CameraClient.java        # Novatek CGI protocol client & state sync engine
│       │   ├── VlcPlayerManager.java    # Embedded LibVLC player, RTSP stream playback & surface lifecycle
│       │   ├── WifiBridge.java          # Wi-Fi STA/SoftAP handover state machine
│       │   ├── UsbDownloader.java       # SD-to-USB chunked HTTP downloader
│       │   ├── HmgapidClient.java       # Root daemon IPC client (LFD1 protocol over abstract UNIX socket)
│       │   ├── DebugLogger.java         # Diagnostics aggregator & USB exporter
│       │   └── ClipItem.java            # Video clip metadata entity (filename, size, time, URL builder)
│       └── main/jniLibs/armeabi-v7a/
│           ├── libvlc.so                # LibVLC native engine (bundles FFmpeg / libavcodec)
│           ├── libvlcjni.so             # LibVLC JNI glue layer
│           └── libc++_shared.so         # LLVM C++ runtime
├── mod/
│   ├── mod.json                    # HMG-API mod manifest (version, apps, services, permissions)
│   ├── svc/
│   │   └── reboot.sh               # Root execution script for head unit reboot
│   └── icons/                      # 128x128 PNG icons for Gen5W launcher
├── tools/
│   ├── build_package.py            # Complete build pipeline: aapt -> javac -> d8 -> zipalign -> sign
│   └── sign.py                     # HMG-API ECDSA packaging and signature generator
└── dist/                           # Release ZIP artifacts for HMG-API and NEMESIS USB sideloading
```

---

## 3. Video Pipeline & Surface Management (LibVLC)

### Why LibVLC was chosen over Stagefright
Gen5W infotainment uses a heavily locked-down Stagefright multimedia stack on Freescale/Telechips hardware. Native Android `MediaPlayer` and `VideoView` fail on Novatek RTSP streams due to proprietary OMX codec restrictions and non-standard RTP packet framing. LibVLC 3.3.4 bypasses Stagefright by embedding its own software and OpenMAX decoders directly within `libvlc.so`.

### Surface View & Geometry Scaling Invariants on KitKat
In Android 4.4.2 KitKat, `SurfaceView` instances create dedicated hardware overlay windows below the normal view hierarchy.
- **Tab Switching Visibility:**
  - When on the **Live View** tab:
    `surfaceLive.setVisibility(View.VISIBLE)`
    `surfaceGallery.setVisibility(View.GONE)`
  - When switching to the **SD Card Gallery** tab:
    `vlcPlayerManager.stopLiveStream()`
    `surfaceLive.setVisibility(View.GONE)`
    `surfaceGallery.setVisibility(View.VISIBLE)`
  - In `VlcPlayerManager.java`, always call `vout.detachViews()` before attaching a new surface to clear the hardware surface buffer.
- **Surface Sizing Critical Rule:**
  - **NEVER call `surfaceView.getHolder().setFixedSize()` on an active LibVLC surface.** In Android 4.4 SurfaceFlinger, `setFixedSize()` destroys and re-allocates the underlying `GraphicBuffer`. Doing this while LibVLC's native C thread is decoding/rendering corrupts the chroma plane and turns HEVC/H.264 video into gray screen noise with vague shapes.
  - **NEVER attach an `OnLayoutChangeListener` to `SurfaceView`** that triggers layout updates, as modifying `LayoutParams` causes an infinite layout thrashing loop.
  - Scale and center video strictly by modifying `FrameLayout.LayoutParams` (`lp.width`, `lp.height`, `lp.gravity = Gravity.CENTER`) and notifying `vout.setWindowSize(w, h)`. Android's Hardware Composer automatically scales the native surface buffer to the view bounds in hardware at 0% CPU.

### LibVLC Codec & Performance Options
Telechips TCC803x has 4x ARM Cortex-A7 cores @ 1.0 GHz with NO hardware HEVC decoder in MediaCodec (only OMX.TCC.avcdec exists for H.264, which is prone to Stagefright deadlocks). All HEVC decoding must run in software via bundled FFmpeg (`libavcodec.so`).

```java
ArrayList<String> options = new ArrayList<String>();
options.add("--rtsp-tcp");              // Force TCP interleaved RTSP transport (cellular firewall bypass)
options.add("--avcodec-threads=4");     // 4 dedicated worker threads for the 4 physical Cortex-A7 cores
options.add("--avcodec-hw=none");       // Pure software NEON SIMD decoding (bypasses OMX.TCC.avcdec)
options.add("--avcodec-fast");          // Fast IDCT and simplified motion compensation
options.add("--no-stats");
libVlc = new LibVLC(context, options);
```

#### Per-Media Profiles & Playback Engines:
- **Live Stream (`startLiveStream`):**
  - Uses standard LibVLC engine.
  - Media options: `:rtsp-tcp`, `:network-caching=500`, `:no-audio`, `:avcodec-threads=4`, `:avcodec-fast`.
  - *Note:* Live stream from Novatek is 720p/1080p H.264 (`rtsp://192.168.1.254/xxx.mov`), running smoothly with sub-second latency.
- **Rear 1080p Clips (`playGalleryClip` with `isFront = false`):**
  - Uses standard LibVLC engine at **1.0x full framerate**.
  - Media options: `:no-audio`, `:network-caching=2500`, `:avcodec-threads=4`, `:avcodec-fast=1`.
  - Decodes smoothly at 30 fps with 0 dropped frames.
- **Front 4K / 1440p HEVC Clips (`playGalleryClip` with `isFront = true`):**
  - Uses dedicated `libVlcFront` keyframe engine initialized with dynamic option probing (`--avcodec-skip-frame=3`, `--avcodec-skiploopfilter=4`, `--avcodec-skip-idct=3`, `--avcodec-hurry-up`, `--no-ts-trust-pcr`).
  - Media options: `:no-audio`, `:no-ts-trust-pcr`, `:avcodec-skip-frame=3`, `:avcodec-skiploopfilter=4`, `:avcodec-skip-idct=3`, `:avcodec-hurry-up=1`, `:avcodec-threads=4`, `:avcodec-fast=1`.
  - **Rate Dilation (`0.33x`):** `player.setRate(0.33f)` dilates presentation clock time by 3x. This guarantees the 1.0 GHz Cortex-A7 CPU has ample wall-clock time (~3s per keyframe) to decode and render without tripping VLC's native 5-second lateness watchdog (`modules/codec/avcodec/video.c`). Keyframes advance smoothly as a slideshow with zero dropped frames and zero gray noise.
  - *Note:* Always call `media.setHWDecoderEnabled(false, false)` to avoid Stagefright OMX crashes on KitKat.

---

## 4. Novatek Dashcam Protocol & CGI Reference

The dashcam communicates via HTTP GET queries to its internal web server (`http://192.168.1.254/`).

| Command | Query String | Description | Expected Return / Behavior |
| :--- | :--- | :--- | :--- |
| **Movie Record Control** | `?custom=1&cmd=2001&par=0` | Stop Loop Recording | `<Function><Cmd>2001</Cmd><Status>0</Status></Function>` |
| | `?custom=1&cmd=2001&par=1` | Start Loop Recording | `<Function><Cmd>2001</Cmd><Status>0</Status></Function>` |
| | `?custom=1&cmd=2001&par=2` | Trigger Emergency Video Lock | Locks current clip segment to emergency folder |
| **Resolution Selection** | `?custom=1&cmd=2002&par=0` | Set Resolution to 4K UHD | Front 3840x2160 / Rear 1080p |
| | `?custom=1&cmd=2002&par=1` | Set Resolution to 2K QHD | Front 2560x1440 / Rear 1080p |
| | `?custom=1&cmd=2002&par=2` | Set Resolution to 1080p FHD | Front 1920x1080 / Rear 1080p |
| **Microphone Control** | `?custom=1&cmd=2007&par=0` | Mute Microphone | Disables audio track in recordings |
| | `?custom=1&cmd=2007&par=1` | Unmute Microphone | Enables audio recording |
| **Snapshot Capture** | `?custom=1&cmd=2017` | Video Snapshot (while recording) | Captures photo without stopping movie recording |
| | `?custom=1&cmd=1001` | Photo Capture (fallback) | Used if recording is stopped |
| **Emergency Lock** | `?custom=1&cmd=2011&par=3` | Protect current recording | Flags recording as locked |
| **Camera Settings Sync** | `?custom=1&cmd=3014` | Query All Camera Settings | Returns XML/HTML list of current parameters |
| **Set System Clock** | `?custom=1&cmd=3015&par=YYYY-MM-DD HH:MM:SS` | Sync RTC Clock | Sets camera internal clock to car clock |
| **Heartbeat / Ping** | `?custom=1&cmd=3016` | Keepalive Ping | Keeps camera Wi-Fi module active |
| **Directory Query** | `?custom=1&cmd=3023` | List Recorded Clips on SD Card | Returns XML document with filenames and attributes |
| **Firmware Info** | `?custom=1&cmd=3504` | Query Hardware / Firmware Info | Returns chipset and firmware string |

### RTSP Stream Endpoints
- **Front Feed:**
  1. `rtsp://192.168.1.254:554/264_pcm_rt/live.fhd` (Primary)
  2. `rtsp://192.168.1.254:554/264_pcm_rt/front.fhd`
  3. `rtsp://192.168.1.254:554/liveRTSP/v1`
  4. `rtsp://192.168.1.254:554/liveRTSP/av4`
- **Rear Feed:**
  1. `rtsp://192.168.1.254:554/264_pcm_rt/rear.fhd` (Primary)
  2. `rtsp://192.168.1.254:554/264_pcm_rt/rear.mov`
  3. `rtsp://192.168.1.254:554/liveRTSP/v2`
  4. `rtsp://192.168.1.254:554/liveRTSP/av2`

---

## 5. Wi-Fi Handover & Invariant Management (`WifiBridge.java`)

The Gen5W head unit has a single physical Wi-Fi chipset. It cannot run in concurrent SoftAP (Hotspot for wireless projection) and Station (Client) mode.
```
                      [ User Launches FITCAMX ]
                                  │
                                  ▼
                     Cache current SoftAP config:
              - wm.isWifiApEnabled()
              - wm.getWifiApMode() (1=Hotspot, 2=Projection)
              - wm.getWifiApConfiguration()
                                  │
                                  ▼
                   Disable SoftAP via Reflection:
                 setWifiApEnabled(wm, null, false)
                                  │
                                  ▼
                       Enable Wi-Fi Client:
                        wm.setWifiEnabled(true)
                                  │
                                  ▼
                      Scan & Connect to Dashcam:
                    SSID matching "CAR_CAM_*"
                                  │
                                  ▼
                         [ App is Running ]
                                  │
                                  ▼
                      [ User Exits Application ]
                                  │
                                  ▼
                      Disconnect from Dashcam
                                  │
                                  ▼
                   Disable Wi-Fi Client Mode (STA)
                                  │
                                  ▼
                Restore Previous SoftAP State & Mode:
             - Call setWifiApEnabled(wm, cachedConfig, true)
             - Restore setWifiApMode(cachedMode)
             - Phone projection reconnects cleanly!
```

---

## 6. Root Daemon IPC & Head Unit Reboot (`HmgapidClient.java`)

The HMG-API platform runs a background daemon `hmgapid` as `root`, listening on abstract UNIX domain socket `\0hmgapid` (or `/dev/socket/hmgapid`).
- All packets adhere to the **`LFD1`** (Line Framing Descriptor 1) protocol:
  - Header: `LFD1` (4 bytes ASCII)
  - Message Length: Big-endian 32-bit integer
  - Payload: UTF-8 command string
- The daemon's `reboot` command is restricted to system callers (returning error code 13 to third-party mods).
- To bypass this limitation, we define a privileged mod service in `mod/mod.json`:
  ```json
  "services": [
    {
      "name": "reboot",
      "script": "svc/reboot.sh",
      "user": "root",
      "start": "manual",
      "restart": false
    }
  ]
  ```
  Where `svc/reboot.sh` contains:
  ```bash
  #!/system/bin/sh
  sync
  sleep 1
  /system/bin/reboot
  ```
- `HmgapidClient.reboot()` sends `service-start reboot` to the daemon, executing the script with root permissions and cleanly rebooting the unit.

---

## 7. Direct SD-to-USB Downloader (`UsbDownloader.java`)

- Files are streamed via HTTP chunked transfer (`http://192.168.1.254/CARDV/MOVIE/<filename>`) directly into the vehicle's USB flash drive.
- Target directory: `/storage/usb0/DashcamFootage/` (falling back to `/mnt/media_rw/usb0/` or `/storage/usb1/`).
- Streamed in 64 KB buffer chunks with live progress percentage and write speed calculations posted to the main UI thread.

---

## 8. Build, Packaging, and Signing Pipeline

To build and sign the complete suite of release packages:
```powershell
python tools/build_package.py
```
The script performs the following tasks:
1. Compiles Java source files with `javac --release 8` against `android-19/android.jar` and `libvlc.jar`.
2. Converts bytecode with `d8 --min-api 19` into `classes.dex`.
3. Injects `classes.dex` and `armeabi-v7a` native libraries (`libvlc.so`, `libvlcjni.so`, `libc++_shared.so`) into `unsigned.apk`.
4. Executes `zipalign -f 4`.
5. Signs `Fitcamx.apk` with `apksigner` using a dedicated 2048-bit RSA keystore (v1 JAR signature only).
6. Stages the mod directory, invokes `sign.py` with ECDSA SHA256 author keys, and outputs:
   - `dist/fitcamx-gen5w-v0.5.3.hmgapi.zip`
   - `dist/fitcamx-gen5w-v0.5.3-hmgapi-usb.zip`
   - `dist/fitcamx-gen5w-v0.5.3-nemesis-usb.zip`
   - `dist/fitcamx-gen5w-v0.5.3-nemesis-package.zip`

---

## 9. Development Dos and Don'ts

| Do | Don't |
| :--- | :--- |
| **DO** verify all UI changes look balanced on a 24:9 widescreen canvas. | **DON'T** hardcode 16:9 vertical paddings or center dialogs without bounds checking. |
| **DO** release LibVLC views (`vlcVout.detachViews()`) before toggling visibility or switching activities. | **DON'T** let two `SurfaceView` instances remain visible simultaneously on KitKat. |
| **DO** keep background logging off by default. | **DON'T** write periodic debug files to internal flash storage (`/data` or `/storage/emulated`). |
| **DO** use `HmgapidClient` and `svc/reboot.sh` for root operations. | **DON'T** call `Runtime.getRuntime().exec("reboot")` expecting unprivileged shell permissions to succeed. |
| **DO** handle camera RTSP disconnections gracefully by falling back through candidate URL lists. | **DON'T** freeze the UI thread while attempting socket connections to `192.168.1.254`. |
