# CLAUDE.md — BirdWatch (Android)

## Project Goal

**BirdWatch** is a native Android app (Kotlin, Jetpack Compose) for spotting surveillance
hardware while wardriving. It began as a phone-native replacement for the
[flock-you](https://github.com/colonelpanichacks/flock-you) Flask dashboard (an ESP32 does the
Flock camera sniffing; the phone adds GPS, storage, map, export) and grew opt-in "signature
packs" for other hardware (Raven gunshot detectors, Axon body cams, Meta glasses, drones incl.
Remote ID). Name history: "Flock You" -> "Flock You Companion" -> **BirdWatch** (2026-09-11,
when the packs made the Flock-only name misleading). The user-facing label lives in
`res/values/strings.xml` (`app_name`). `applicationId` and Kotlin package are
`com.khasmek.birdwatch`; they were changed once (from `com.khasmek.flockyou`) while the app was
only on the dev phone and must not change again once anyone else has it installed.

The app has **two detection sources** feeding one data model:

| Source | Hardware | What it finds | Status |
|---|---|---|---|
| **ESP32 companion over USB** (primary) | Seeed XIAO ESP32-S3 running the current flock-you WiFi promiscuous firmware, attached via USB-C OTG | Flock cameras via 802.11 probe-request sniffing | Phase 4 |
| **Phone BLE scanner** (secondary) | The phone's own Bluetooth radio | Raven / SoundThinking gunshot detectors, and any BLE-visible Flock gear | Done (Phase 2) |

### Why two sources (read this before touching detection code)

The upstream project changed under us. Flock cameras stopped running their management AP around
December 2025 and stopped being BLE-detectable in spring 2026. Since April 2026 the upstream
firmware is a **WiFi promiscuous-mode sniffer**: it watches for wildcard probe requests from known
Flock OUIs and confirms them with an information-element (IE) fingerprint. That requires monitor
mode, which Android phones do not expose (the dev Pixel 5 has a Qualcomm icnss WiFi driver built
into the stock kernel; root does not help). So the phone cannot sniff probes itself.

Instead the phone plays the role the Flask dashboard plays upstream: the ESP32 does the sniffing
and streams one JSON line per detection over USB CDC serial; the phone ingests it, adds GPS, stores
it, maps it, and exports it. The phone's BLE radio is kept for Ravens, which are a different vendor
and are not known to have changed.

**Do not** implement WiFi probe sniffing or IE fingerprinting on the phone. **Do not** rely on the
phone's `WifiManager` scan for camera detection (it only sees APs, which cameras no longer run).

## Source of truth for detection logic

- **BLE heuristics (phone side):** upstream `src/main.cpp` at commit
  `6c6930b1917383f71a0413e35095c62fe9cae726` (2026-04-20), the last BLE-era revision:
  https://raw.githubusercontent.com/colonelpanichacks/flock-you/6c6930b1917383f71a0413e35095c62fe9cae726/src/main.cpp
  Every constant is already ported into `detection/DetectionSignatures.kt`. **The local clone at
  `../flock-you/` does NOT contain this code** (it is the WiFi-only dev branch).
- **ESP32 firmware wire protocol (USB side):** `../flock-you/main.cpp` and `../flock-you/README.md`
  ("Flask dashboard integration" section). `../flock-you/api/flockyou.py` shows how Flask parses it.
- **OUI list:** `../flock-you/datasets/NitekryDPaul_wifi_ouis.md` (32 prefixes, 2026-07-16 sync).

## Android Tech Stack

- **Language:** Kotlin. **Min SDK:** 26. **Target SDK:** 36. **Compile SDK:** 37.
- **UI:** Jetpack Compose + Material 3, navigation-compose, bottom navigation with 4 tabs.
- **BLE:** `android.bluetooth.le.BluetoothLeScanner`, unfiltered scan, classification in software.
- **USB serial:** usb-serial-for-android (`com.github.mik3y:usb-serial-for-android`, JitPack) —
  CDC ACM, 115200 baud. Needs `android.hardware.usb.host` and a USB permission prompt. No root.
- **GPS:** Play Services `FusedLocationProviderClient`, high accuracy while a session runs.
- **Storage:** Room (KSP). Two tables: `detected_devices`, `scan_sessions`.
- **Maps:** maps-compose + play-services-maps, user-provided API key at runtime (see below).
- **Export:** JSON, CSV, KML via FileProvider + share sheet.
- **Audio:** SoundPool / ToneGenerator, built-in tones, no asset files.
- **DI:** manual, via `AppContainer` in `BirdWatchApp.kt`. No Hilt.
- **Async:** coroutines + Flow everywhere. No callbacks leak past the radio wrappers, no RxJava.
- **Build:** Gradle Kotlin DSL, AGP 9.2.1 with built-in Kotlin 2.2.

### Toolchain pins (do not "upgrade to latest" blindly)

AGP 9.2.1 bundles Kotlin 2.2 and refuses libraries compiled with newer Kotlin metadata:
- `ksp = 2.3.5` — last KSP built on Kotlin 2.2.10.
- `maps-compose = 8.3.0` / `play-services-maps = 19.2.0` — 8.4.0+ need kotlin-stdlib 2.4.
- `compileSdk 37` — required by core-ktx 1.19 and lifecycle 2.11.
If Kotlin/AGP is bumped later, revisit all three together.

## Package layout

Namespace, `applicationId` and Kotlin package are all `com.khasmek.birdwatch`.

```
app/src/main/java/com/khasmek/birdwatch/
├── BirdWatchApp.kt                 # Application + AppContainer (manual DI, appScope)
├── MainActivity.kt                # Single activity; PermissionGate -> AppNavigation
├── detection/
│   ├── DetectionSignatures.kt     # All OUIs, names, mfr IDs, Raven UUIDs (pure Kotlin)
│   ├── DeviceClassifier.kt        # BLE heuristics 1-5, DetectionMethod/DeviceType enums (pure)
│   ├── DetectedDevice.kt          # Room entity shared by both sources; DetectionSource enum
│   ├── BleScanner.kt              # BluetoothLeScanner wrapper, MAC dedupe, StateFlows
│   └── ScanForegroundService.kt   # Phase 6: owns BLE scanner + USB reader in background
├── usb/                           # Phase 4
│   ├── UsbCompanion.kt            # Device attach/permission, serial open, line reader
│   └── FirmwareLineParser.kt      # JSON line -> DetectedDevice (pure, testable)
├── location/
│   └── LocationProvider.kt        # FusedLocation wrapper; GeoFix; currentFix() for tagging
├── data/
│   ├── DetectionDatabase.kt       # Room DB (v1), schema exported to app/schemas/
│   ├── DetectionDao.kt            # upsert, by-session, all, count, delete
│   ├── SessionDao.kt              # sessions + SessionSummary (device/flock/raven counts)
│   ├── ScanSession.kt             # Session entity + SessionSummary
│   ├── SessionManager.kt          # UUID per session, starts/stops radios, persists detections
│   └── ExportManager.kt           # Phase 8
├── audio/AlertSounds.kt           # Phase 5
├── ui/
│   ├── PermissionGate.kt          # Runtime permission flow (BLE + location, notifications optional)
│   ├── navigation/AppNavigation.kt
│   ├── theme/
│   ├── screens/                   # Dashboard, Map, PreviousSession, Settings
│   └── components/                # DeviceCard, StatsBar (Phase 5)
└── util/Permissions.kt
```

## Data model

```kotlin
@Entity(tableName = "detected_devices", primaryKeys = ["sessionId", "macAddress"])
data class DetectedDevice(
    val sessionId: String,
    val macAddress: String,
    val source: DetectionSource,        // BLE | ESP32_WIFI
    val deviceName: String?,
    val detectionMethod: DetectionMethod, // wireName == firmware detection_method string
    val deviceType: DeviceType,         // FLOCK | SOUNDTHINKING | RAVEN
    val confidence: Confidence,         // HIGH | LOW
    val matchedOn: String,              // the OUI / name pattern / mfr id / UUID that fired
    val ravenFirmware: String?,         // "1.1.x" | "1.2.x" | "1.3.x" | "?"   (BLE Raven only)
    val tier: Int?,                     // 0..4 firmware confidence tier      (ESP32 only)
    val channel: Int?,                  // WiFi channel                       (ESP32 only)
    val rssi: Int,
    val latitude: Double?, val longitude: Double?, val accuracyMeters: Float?,
    val firstSeen: Long, val lastSeen: Long,   // epoch millis
    val sightings: Int,
)

@Entity(tableName = "scan_sessions")
data class ScanSession(@PrimaryKey val id: String, val startedAt: Long, val endedAt: Long?)
```

Dedupe is per (session, MAC). A re-sighting updates rssi, lastSeen, sightings, GPS, and keeps a
name once learned. `SessionManager` writes new MACs immediately and batches re-sightings every 2 s.

## Detection logic

### A. ESP32 WiFi firmware (primary, Phase 4) — ingest, don't re-implement

The firmware emits one JSON object per line over USB CDC at 115200 baud:

```json
{"event":"detection","detection_method":"wifi_wildcard_probe_ie_sig","detection_tier":4,
 "protocol":"wifi_2_4ghz","mac_address":"82:6b:f2:14:07:3a","oui":"82:6b:f2",
 "device_name":"","rssi":-52,"channel":6,"frequency":2437,"ssid":""}
```

| Tier | `detection_method` | Meaning |
|---|---|---|
| 4 | `wifi_wildcard_probe_ie_sig` | OUI + wildcard probe + IE fingerprint (confirmed camera) |
| 3 | `wifi_wildcard_probe` | OUI + wildcard probe, IE not matched |
| 2 | `wifi_oui_addr2` | Flock OUI as transmitter, any frame |
| 1 | `wifi_oui_addr1` / `wifi_oui_addr3` | Flock OUI as receiver / BSSID (echo, false-positive prone) |
| 0 | `wifi_ssid` | SSID keyword (off by default in firmware) |

Rules: tier 3-4 -> `Confidence.HIGH`, tier 0-2 -> `Confidence.LOW`; `deviceType = FLOCK`;
`source = ESP32_WIFI`. A higher tier for an already-seen MAC upgrades the stored method/tier;
a lower tier never downgrades it. Other line types from the device (`{"event":"config",...}`,
`session_begin` / `session_det` / `session_end` dump replies, free-form banners) must be parsed or
ignored without crashing. The firmware also accepts commands as JSON lines (beep mask, dump_session);
these are optional extras, not required for detection.

### B. Phone BLE (secondary, done) — all 5 methods from the BLE-era firmware

Implemented in `DeviceClassifier`, in this priority order (first match wins):

1. **MAC OUI prefix** — `FLOCK_MAC_PREFIXES` (21, high), `FLOCK_MAC_PREFIXES_2026` (14, high,
   from the WiFi-era list), `FLOCK_CONTRACT_MFR_MAC_PREFIXES` (6, Liteon/USI, **low**),
   `SOUNDTHINKING_MAC_PREFIXES` (`d4:11:d6`).
2. **Device name** — case-insensitive substring: `FS Ext Battery`, `Penguin`, `Flock`, `Pigvision`.
3. **Manufacturer ID** `0x09C8` (XUNTONG) in manufacturer-specific data.
4. **Raven service UUIDs** — `0000180a`, `00003100`..`00003500`, `00001809`, `00001819`. A hit on
   a generic SIG UUID alone (180a/1809/1819) is low confidence.
5. **Raven firmware** — legacy Location without new GPS -> `1.1.x`; GPS without Power -> `1.2.x`;
   GPS with Power -> `1.3.x`; else `?`. (This is the firmware's actual logic; an earlier draft of
   this file described it differently.)

Unit tests: `app/src/test/.../DeviceClassifierTest.kt`. Keep the classifier free of Android imports.

## Android specifics

### Permissions
Declared in the manifest and requested on launch by `PermissionGate`:
`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (API 31+), `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS` (optional).
Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` are declared with `maxSdkVersion=30`. Android also needs
**location services switched on** to deliver BLE scan results; `BleScanner` surfaces a warning.
USB access uses the USB host permission dialog, not a manifest permission.

### Background scanning
Phase 6 adds a foreground service of type `connectedDevice` that owns both the BLE scanner and the
USB reader. `SCAN_MODE_LOW_LATENCY` in foreground, `SCAN_MODE_LOW_POWER` when backgrounded.

### User-provided Google Maps API key (Phase 7)
No key ships in source, build config, or manifest. The manifest carries an empty
`com.google.android.geo.API_KEY` placeholder; the user enters a key in Settings, stored in
`EncryptedSharedPreferences`, injected into `ApplicationInfo.metaData` before the map loads. The
Map tab shows a prompt with a button to Settings when no key is stored. Everything except the map
works without a key. Help link: https://developers.google.com/maps/documentation/android-sdk/get-api-key

## Phases

1. Skeleton + permissions + bottom nav — **done, device-verified**
2. BLE detection engine + unit tests — **done, device-verified**
3. Location provider, Room, SessionManager — **done, device-verified**
4. USB serial ESP32 companion (attach/permission, CDC ACM reader, line parser, status in UI) —
   **code done, parser unit-tested; live serial test pending flashed XIAO + OTG cable**
5. Dashboard UI: device cards with source badge + tier, stats bar, start/stop, audio alerts —
   **done, device-verified** (MVVM: `DashboardViewModel` + `appViewModel {}` factory helper;
   `AppSettings` holds audio/scan-mode toggles; `AlertSounds` synthesises WAVs into cache)
6. Foreground service (`connectedDevice|location`); live notification count; LOW_POWER when the
   app UI is backgrounded — **done, device-verified**. `SessionManager.start()` routes through
   `ScanForegroundService.start()`; the service observes `currentSession` and stops itself when
   it becomes null, so every stop path (FAB, notification action, programmatic) converges.
7. Settings (API key, toggles) + Map screen with typed markers — **done, device-verified**.
   Runtime key injection confirmed: the Maps SDK's authorization-failure log echoed the key read
   from EncryptedSharedPreferences. There is no SDK callback for a bad key (gray tiles only), so
   validation is a format check (39 chars, `AIza` prefix) plus visual confirmation. A changed key
   needs an app restart because the SDK caches the first key it reads.
8. Export (JSON/CSV/KML via share sheet) + Sessions screen (list, detail, export, delete) —
   **done, device-verified**. `ExportWriter` is pure (JVM-tested); `ExportManager` writes to
   `cache/exports/` behind a FileProvider and returns an ACTION_SEND chooser. Sessions list ->
   `sessions/{sessionId}` detail route; active session cannot be deleted.

All eight phases are complete. Remaining hardware validation: live ESP32 USB serial ingestion
(Phase 4) once a flashed XIAO ESP32-S3 and OTG cable are available.

9. **Signature packs**: opt-in detection categories beyond Flock/Raven. The full registry,
   confidence rules, exclusions, and attribution live in `docs/SIGNATURES.md`; read it before
   touching anything in `detection/`. Every signature in code cites a source id from that file
   (`Sources` in `SignaturePacks.kt` mirrors its table and feeds the in-app credits).
   - 9a — **done**: `SignaturePacks.kt` (typed signatures: OUI / company ID / 16-bit service
     UUID / name substring / composite), `DeviceClassifier.classify(adv, enabledPacks)` runs Core
     first then enabled packs; `DeviceType` gained a `category`; Settings has per-pack switches
     with a BETA tag and an About & credits list; stats/sessions show an "Other" count; exports
     carry `category`. Ships Axon (LE pack) and Meta glasses (wearables pack) on phone BLE.
   - 9b — **done**: `wifi/WifiApScanner.kt` is a third `DetectionSource` (`PHONE_WIFI`). It
     listens for the system's own scan results and requests one every 35 s (Android throttle:
     4 per 2 min foreground). `DeviceClassifier.classifyWifiAp(bssid, packs)` matches Core OUIs
     then WiFi-scoped pack OUIs (`Signature.Oui.radios`). Adds the Drones pack (DJI, Parrot,
     Skydio) and the WiFi-only LE vendors (WatchGuard, Digital Ally, Utility). Separate
     "Phone WiFi access-point scan" switch in Settings, off by default; needs
     ACCESS_WIFI_STATE + CHANGE_WIFI_STATE. Rooted tip: `settings put global
     wifi_scan_throttle_enabled 0`.
   - 9c — **done**: `detection/RemoteId.kt` is a pure ASTM F3411 / Open Drone ID decoder (Basic
     ID, Location, System, Operator ID, Self ID, Message Pack). Consumed from BLE service data
     under UUID 0xFFFA (`BleAdvertisement.serviceData`) and from WiFi beacon vendor IEs with the
     ASD-STAN OUI (`ScanResult.informationElements`, API 30+). Gated by the Drones pack;
     `DeviceType.REMOTE_ID_UAS`, methods `REMOTE_ID_BLE` / `REMOTE_ID_WIFI`. Room schema **v2**
     (auto-migration) adds nullable `uasId`, `operatorId`, `target*` (drone-reported position /
     altitude) and `operator*` columns; map and KML place a drone at its own reported position.

Work one phase at a time; stop after each for review. The user runs all git commands; suggest
commit points and messages but never run git.

## Build, test, release
```bash
./gradlew assembleDebug          # compile ("dev-debug" version)
./gradlew testDebugUnitTest      # JVM tests (classifier, parsers, exporters, Remote ID)
./gradlew installDebug           # dev device: Pixel 5 (redfin), Android 14, rooted
./gradlew assembleRelease -PbirdwatchVersion=2026.09.1   # minified; debug-signed unless keystore.properties exists
```

**Versioning** is rolling date-based: `YYYY.MM.N` (N = release number within the month, 1-99).
`versionName` = that string; `versionCode` = `YYYYMM * 100 + N` (2026.09.1 -> 20260901), computed
in `app/build.gradle.kts` from `BIRDWATCH_VERSION` (env) or `-PbirdwatchVersion`. Without either
the build is `dev` with versionCode `YYYYMM * 100 + 99` for the current month, so a dev build
installs over any release of that month and next month's release installs over dev. (Android
will not downgrade a non-debuggable install even with `adb install -d`; this avoids the
uninstall.)

**Release**: run `.github/workflows/release.yml` by hand from the Actions tab with the version
as input (it creates the tag on the current commit), or push a tag `YYYY.MM.N`. Either way it
validates the format, runs unit tests, builds a signed minified APK, and publishes a GitHub Release with
`BirdWatch-<tag>.apk`, a sha256, and the R8 `mapping.txt`. It refuses to run without the
`KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` secrets. CI
(`ci.yml`) runs build + tests + lint on pushes to `main` and PRs. Release is minified with R8
(`isMinifyEnabled` + `isShrinkResources`); app-specific rules live in `app/proguard-rules.pro`,
everything else comes from library consumer rules. Debug and release share the `applicationId`
on purpose; debug only adds `-debug` to the version name.
BLE and USB need a physical device. If BLE results are empty: location services on? permissions
granted? Bluetooth on? For USB: OTG cable, XIAO ESP32-S3 flashed with `../flock-you` firmware.
