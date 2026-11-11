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
│       │   ├── ThemeManager.java        # Material Design 3 tonal palette & API 19 shape/state-layer engine
│       │   ├── CameraClient.java        # Novatek CGI protocol client & state sync engine
│       │   ├── VlcPlayerManager.java    # Embedded LibVLC player, RTSP stream playback & surface lifecycle
│       │   ├── WifiBridge.java          # Wi-Fi STA/SoftAP handover state machine
│       │   ├── UsbDownloader.java       # Atomic (.part + fsync) SD-to-USB HTTP downloader
│       │   ├── ScreenshotHelper.java    # USB-only root framebuffer capture & theme-aware BGRA/RGBA normalizer
│       │   ├── TsHeaderSniffer.java     # MPEG-TS / M2TS & MP4/MOV codec header sniffer
│       │   ├── XmlListParser.java       # Sanitized XML & fallback regex clip-list parser
│       │   ├── HmgapidClient.java       # Root daemon IPC client (LFD1 protocol over abstract UNIX socket)
│       │   ├── DebugLogger.java         # RAM-only diagnostics aggregator & USB exporter
│       │   └── ClipItem.java            # Video clip & still photo metadata entity
│       └── main/jniLibs/armeabi-v7a/
│           ├── libvlc.so                # LibVLC native engine (bundles FFmpeg / libavcodec)
│           ├── libvlcjni.so             # LibVLC JNI glue layer
│           └── libc++_shared.so         # LLVM C++ runtime
├── mod/
│   ├── mod.json                    # HMG-API mod manifest (version, apps, services, permissions)
│   ├── svc/
│   │   ├── reboot.sh               # Root execution script for head unit reboot
│   │   └── screenshot.sh           # Root execution script for atomic USB-only framebuffer PNG capture
│   └── icons/                      # 128x128 PNG icons for Gen5W launcher (active + disabled)
├── tools/
│   ├── build_package.py            # Complete build pipeline: aapt -> javac -> R8 -> zipalign -> sign
│   ├── proguard-rules.pro          # R8 shrinking & API 19 Dalvik compatibility rules
│   └── sign.py                     # HMG-API ECDSA packaging and signature generator
├── tests/
│   ├── run_tests.py                # Automated build, JVM unit test & binary invariant runner
│   └── FitcamUnitTests.java        # Unit & mock-HTTP integration test suite
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
ArrayList<String> options = VlcPlayerManager.buildEngineOptions(mirrorHorizontal);
// Includes: --rtsp-tcp, --avcodec-threads=4, --avcodec-hw=none, --avcodec-fast, --no-osd, --no-spu
// Plus --video-filter=transform and --transform-type=hflip on mirrorLibVLC ONLY.
// Note: Do NOT pass "--no-stats"; liveWatchdogRunnable reads IMedia.Stats to detect RTP/frame stalls.
```

#### Per-Media Profiles & Playback Engine:
`VlcPlayerManager` initializes **standard (`libVLC`) and horizontally-flipped (`mirrorLibVLC`) `LibVLC` instances asynchronously** off the main UI thread (`FitcamxVlcInit`) and applies per-media decoding options via `Media.addOption(...)`:
- **Live Stream (`startLiveStream`):**
  - **Horizontal Mirroring Invariant:** In VLC 3.0, `vout_thread_t` inherits `"video-filter"` and `"transform-type"` from `libvlc_media_player_t` → `LibVLC`, **NOT** from `input_thread_t` (`Media.addOption`). Live horizontal mirroring therefore binds `mirrorLivePlayer` (`mirrorLibVLC` with `--video-filter=transform` + `--transform-type=hflip`), while unmirrored Live View and SD Gallery playback bind `livePlayer` / `galleryPlayer` (`libVLC`).
  - Media options (`buildLiveMediaOptions`): `:rtsp-tcp`, `:network-caching=300`, `:ipv4-timeout=5000`, `:no-audio`, `:avcodec-threads=4`, `:avcodec-fast`, `:clock-jitter=0`, `:clock-synchro=0`.
  - **Keepalive & Anti-Freeze Self-Healing:** `MainActivity` dispatches idle Novatek `cmd=3016` heartbeats every 15s (`CameraClient.sendKeepaliveIfIdle`), while `VlcPlayerManager` polls `IMedia.Stats` (`readBytes`, `demuxReadBytes`, `decodedVideo`, `displayedPictures` via `didLiveStatsAdvance`, always calling `media.release()` in `finally`) every `250ms` with a `1200ms` stall threshold. **Never** reset `lastLiveProgressUptimeMs` on `Event.TimeChanged` or `PositionChanged` — VLC's monotonic `input_clock_t` continues firing `TimeChanged` for 10s while `live555` receives 0 RTP packets! Tier-1 recovery (`attempt == 1`) reconnects RTSP silently after `50ms` (`~170ms` connect time) without flashing `pbLiveLoading` or `"CONNECTING..."`.
  - *Note:* Live stream from Novatek is 720p/1080p H.264 (`rtsp://192.168.1.254/xxx.mov`), running smoothly with sub-second latency and automatic late-frame dropping if Wi-Fi jitters.
- **1080p AVC Clips (`playGalleryClip` with `useKeyframeMode = false`):**
  - Always defaults to **1.0x full framerate** on each new clip, and allows cycling `1.0x` → `0.5x` → `0.33x` → `1.0x`.
  - Media options: `:no-audio`, `:ts-seek-percent`, `:network-caching=1500` (`:file-caching=500` on USB), `:avcodec-threads=4`, `:avcodec-fast=1`.
- **4K / 1440p HEVC Clips (`playGalleryClip` with `useKeyframeMode = true`):**
  - Media options: `:no-audio`, `:ts-seek-percent`, `:no-ts-trust-pcr`, `:avcodec-skip-frame=3`, `:avcodec-skiploopfilter=4`, `:avcodec-hurry-up=1`, `:avcodec-threads=4`, `:avcodec-fast=1`.
  - **Rate Dilation (`0.33x` default, user-toggleable `0.33x` ↔ `0.5x`; `1.0x` disallowed):** `player.setRate(0.33f)` dilates presentation clock time by 3x so the 1.0 GHz Cortex-A7 CPU has ample wall-clock time to decode and render each HEVC keyframe without tripping VLC's lateness watchdog.
  - **Floating Smoked-Glass Overlays (`layout_gallery_top_overlay` & `layout_gallery_seek_overlay`):** Both the top action bar and bottom scrub/status card composite inside `frame_gallery_container` above the `SurfaceView` punch-through layer without shrinking video bounds (`1026×578` with clip list open; `1120×630` across the full `630px` app height with clip list hidden), auto-hiding after 3.5s of playback or photo display (tap viewport to toggle).
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
| **Live Stream Control** | `?custom=1&cmd=2015&par=0` / `par=1` | Stop / Start Live View | Enables or stops RTSP encoder output |
| **Live Stream URL Query** | `?custom=1&cmd=2019` | Query Active RTSP URL | Returns `<MovieLiveViewLink>rtsp://192.168.1.254/xxx.mov</MovieLiveViewLink>` |
| **Live Channel Switch** | `?custom=1&cmd=3028&par=0\|1\|2` | Switch Live Camera Feed | `0`=Front, `1`=Rear, `2`=Dual PiP (non-disruptive while recording) |
| **Camera Settings Sync** | `?custom=1&cmd=3014` | Query All Camera Settings | Returns XML list of current parameters |
| **Set System Clock** | `?custom=1&cmd=3005&str=YYYY-MM-DD` + `cmd=3006&str=HH:MM:SS` | Sync RTC Date & Time | Sets camera internal clock to car clock |
| **Directory Query** | `?custom=1&cmd=3015` | List Recorded Media on SD Card | Returns XML `<ALLFile>` index (`Movie`, `EMR`, `PHOTO`) |
| **Heartbeat / Ping** | `?custom=1&cmd=3016` | Keepalive Ping | Keeps camera Wi-Fi & RTSP session active (sent every 15s when idle) |
| **Firmware Info** | `?custom=1&cmd=3504` | Query Hardware / Firmware Info | Returns chipset and firmware string |

### RTSP Stream Endpoints
- **Primary Dynamic Endpoint (via `cmd=2019`):** `rtsp://192.168.1.254/xxx.mov` (live camera channel selected via `cmd=3028&par=0|1|2`)
- **Fallback Candidate Endpoints:**
  - Front: `rtsp://192.168.1.254:554/264_pcm_rt/live.fhd`, `.../front.fhd`, `.../liveRTSP/v1`, `.../liveRTSP/av4`
  - Rear: `rtsp://192.168.1.254:554/264_pcm_rt/rear.fhd`, `.../rear.mov`, `.../liveRTSP/v2`, `.../liveRTSP/av2`

---

## 5. Wi-Fi Handover & Invariant Management (`WifiBridge.java`)

The Gen5W head unit has a single physical Wi-Fi chipset. It cannot run in concurrent SoftAP (Hotspot for wireless projection) and Station (Client) mode.
- **Fast Reconnect & Zero-Wear Profile Retention:** Once a dashcam is verified over HTTP, its identity (`ssid`, `ip`, `soc`, `port`) is persisted strictly once on change, and its `wpa_supplicant` `WifiConfiguration` (`priority=100`, pure WPA2-CCMP when `[WPA2-PSK-CCMP]` is advertised) is retained across exits (disabled in RAM on teardown rather than removed/re-saved on every launch). On subsequent launches, `WIFI_STATE_ENABLED` associates directly with the saved `networkId` without waiting for a full scan or colliding with background networks, begins `150ms` DHCP polling immediately upon `SupplicantState.COMPLETED`, and falls back to active scanning if the camera is absent or replaced.
- **Custom Password & Manual SSID Fallback:** `WifiBridge` detects explicit `ERROR_AUTHENTICATING`, Broadcom `bcmdhd` KitKat `FOUR_WAY_HANDSHAKE -> DISCONNECTED` PSK rejections, exhausted association watchdogs, and missing standard SSIDs (`NO_MATCH_PROMPT_SCAN_THRESHOLD`), pausing auto-retries on rejected SSIDs and invoking `Listener.onManualSetupNeeded(...)`. User-supplied passwords are persisted per SSID (`pwd_<ssid>`) strictly on change and preserved even if cached camera identity is cleared.
- **SoftAP Mode 2 Restoration Order:** On Gen5W Broadcom `bcmdhd`, `setWifiApEnabledForConnectivity(conf, true)` (Mode 2) fails if client `wpa_supplicant` is still active. `restoreWifiStateInternal()` always calls `wm.setWifiEnabled(false)` **before** re-enabling SoftAP, and retains `initialStateRecorded` across temporary background suspends so final `Exit` still knows the true pre-launch AP state.
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

## 8. Build, Cloud Workflow, Signing, and Verification Pipeline

To build, sign, and run the automated verification suite (self-bootstrapping in cloud VMs, GitHub Actions, and local machines):
```bash
./build.sh
python3 tests/run_tests.py
```
`tools/cloud_env.py` and `tools/build_package.py` perform the following tasks automatically:
1. **Self-Bootstrapping Cloud Toolchain & Persistent Cache:**
   - Discovers JDK 8/17+ (`JAVA_HOME` or `javac` on `PATH`), automatically downloads & extracts Android `android-19/android.jar` + Build-Tools `30.0.3` (`~/tools/android-sdk`), and shallow-clones `VysseCodes/HMG-API` (`~/tools/HMG-API`).
   - In Antigravity Cloud VMs, archives are cached in persistent `~/.gemini/fitcamx-gen5w/cache/` so new cloud sessions restore the entire SDK + HMG-API toolchain in ~1 second without re-downloading.
2. **Bytecode Compilation & Dalvik R8 Shrinking:**
   - Compiles Java source files with `javac --release 8` against `android-19/android.jar` and `libvlc.jar`.
   - Shrinks and converts bytecode with `R8 --min-api 19` (`tools/proguard-rules.pro`) into Dalvik `classes.dex` (`dex 035`), stripping post-KitKat classes (`AudioDeviceCallback`, `androidx.lifecycle.Observer`, unused UI widgets).
3. **Deflated Native Packaging & KitKat v1 Signing:**
   - Injects `classes.dex` and `armeabi-v7a` native libraries (`libvlc.so`, `libvlcjni.so`, `libc++_shared.so`) into `unsigned.apk` with `ZIP_DEFLATED`, then runs `zipalign -f 4`.
   - Resolves signing keys in priority order: CI Secrets (`FITCAMX_KEYSTORE_BASE64` + `FITCAMX_AUTHOR_KEY`) → persistent cloud keys (`~/.gemini/fitcamx-gen5w/keys/`) → local `../build/keys/` → automatic ephemeral dev keys (`~/.cache/fitcamx-dev-keys`, unless `--require-release-keys` is set).
   - Signs `Fitcamx.apk` with `apksigner` strictly using Android v1 JAR signature (`--v1-signing-enabled true --v2-signing-enabled false --v3-signing-enabled false`).
4. **HMG-API & NEMESIS Packaging (`dist/`, untracked in git):**
   - Stages `mod/`, invokes `sign.py` with ECDSA SHA256 author keys, and outputs all 4 packages in `dist/` (`*.hmgapi.zip`, `*-hmgapi-usb.zip`, `*-nemesis-usb.zip`, `*-nemesis-package.zip`).
5. **Cloud Key Management, Google Drive Export & GitHub Actions:**
   - Check or import production keys (`uuid: 7abe7eda-b784-851a-bb6e-91cb0294bdf1`):
     ```bash
     python3 tools/manage_keys.py status
     python3 tools/manage_keys.py import --sync-github-secrets
     ```
   - Sync built packages to the `FITCAMX Gen5W Builds` Google Drive folder:
     ```bash
     ./build.sh --sync-gdrive
     ```
   - CI & Releases: `.github/workflows/ci.yml` verifies every push/PR and uploads build artifacts; `.github/workflows/release.yml` enforces `--require-release-keys` on `v*.*.*` tags and publishes all 4 signed ZIPs + `SHA256SUMS.txt` to GitHub Releases.

---

## 9. Development Dos and Don'ts

| Do | Don't |
| :--- | :--- |
| **DO** verify all UI changes look balanced on a 24:9 widescreen canvas. | **DON'T** hardcode 16:9 vertical paddings or center dialogs without bounds checking. |
| **DO** release LibVLC views (`vlcVout.detachViews()`) before toggling visibility or switching activities. | **DON'T** let two `SurfaceView` instances remain visible simultaneously on KitKat. |
| **DO** keep background logging off by default. | **DON'T** write periodic debug files to internal flash storage (`/data` or `/storage/emulated`). |
| **DO** use `HmgapidClient` and `svc/reboot.sh` for root operations. | **DON'T** call `Runtime.getRuntime().exec("reboot")` expecting unprivileged shell permissions to succeed. |
| **DO** handle camera RTSP disconnections gracefully by falling back through candidate URL lists. | **DON'T** freeze the UI thread while attempting socket connections to `192.168.1.254`. |
