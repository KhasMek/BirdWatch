# CLAUDE.md — Flock-You Android Port

## Project Goal

Port [flock-you](https://github.com/colonelpanichacks/flock-you) from ESP32-S3 firmware (C++/Arduino + HTML dashboard) to a native Android app (Kotlin, Jetpack Compose). The original project detects Flock Safety surveillance cameras, Raven gunshot detectors, and related monitoring hardware using BLE-only heuristics.

## Original Project Summary

The ESP32 version runs a BLE scanner and serves a web dashboard over a WiFi access point. On Android, **the WiFi AP layer is eliminated entirely** — the app IS the UI. The phone's BLE radio and GPS replace the ESP32 hardware.

### Original Tech Stack (for reference, NOT to be reused)
- **Language:** C++ (Arduino/PlatformIO)
- **BLE:** NimBLE-Arduino library
- **Web UI:** ESP Async WebServer serving HTML/JS dashboard
- **Storage:** SPIFFS flash filesystem
- **GPS:** Browser Geolocation API over HTTP (phone → ESP32)
- **Audio:** Piezo buzzer driven via GPIO tone generation

## Android Tech Stack

- **Language:** Kotlin
- **Min SDK:** 26 (Android 8.0) — needed for full BLE scan API support
- **Target SDK:** 34+
- **UI:** Jetpack Compose + Material 3
- **BLE:** Android BluetoothLeScanner API (`android.bluetooth.le`)
- **GPS:** Google Play Services FusedLocationProviderClient
- **Storage:** Room database for detection persistence
- **Maps:** Google Maps SDK for Android (wardriving visualization) — user-provided API key at runtime (see below)
- **Export:** Manual file I/O to generate JSON, CSV, KML to shared storage
- **Audio alerts:** SoundPool or MediaPlayer for detection sounds
- **Build:** Gradle (Kotlin DSL)

## Architecture

```
app/
├── src/main/java/com/flockyou/
│   ├── FlockYouApp.kt                 # Application class
│   ├── MainActivity.kt                # Single activity, Compose host
│   ├── detection/
│   │   ├── BleScanner.kt              # BLE scan lifecycle, filters
│   │   ├── DeviceClassifier.kt        # Detection heuristics (ALL 5 METHODS)
│   │   ├── DetectedDevice.kt          # Data class for a detected device
│   │   └── ScanForegroundService.kt   # Foreground service for background scanning
│   ├── location/
│   │   └── LocationProvider.kt        # FusedLocation wrapper, GPS tagging
│   ├── data/
│   │   ├── DetectionDatabase.kt       # Room database
│   │   ├── DetectionDao.kt            # DAO for device records
│   │   ├── SessionManager.kt          # Current/previous session logic
│   │   └── ExportManager.kt           # JSON, CSV, KML export
│   ├── audio/
│   │   └── AlertSounds.kt             # Detection chirps, heartbeat coo
│   ├── ui/
│   │   ├── theme/Theme.kt             # Material 3 theme
│   │   ├── screens/
│   │   │   ├── DashboardScreen.kt     # Live detection feed + stats
│   │   │   ├── MapScreen.kt           # Wardriving map view
│   │   │   ├── PreviousSessionScreen.kt
│   │   │   ├── ExportScreen.kt
│   │   │   └── SettingsScreen.kt       # API key, scan prefs, audio toggle
│   │   └── components/
│   │       ├── DeviceCard.kt          # Single detected device row
│   │       └── StatsBar.kt            # Detection count, GPS status, etc.
│   └── util/
│       └── Permissions.kt             # Runtime permission handling
├── src/main/res/raw/                  # Sound files for alerts
└── src/main/AndroidManifest.xml
```

## Critical Detection Logic — ALL 5 METHODS MUST BE IMPLEMENTED

These are the core heuristics from the original project. **Do not skip any.**

### Method 1: MAC OUI Prefix Matching
Match the first 3 bytes of the BLE MAC address against 20 known Flock Safety OUI prefixes. The full prefix list is in the original `main.cpp` and `oui.txt`. Examples include prefixes for "FS Ext Battery" and "Flock WiFi modules". On Android, use `ScanResult.getDevice().getAddress()` and check the first 8 characters (e.g., `"AA:BB:CC"`).

### Method 2: BLE Device Name Matching
Case-insensitive substring match on the advertised BLE device name. Match against:
- `"FS Ext Battery"`
- `"Penguin"`
- `"Flock"`
- `"Pigvision"`

On Android: `ScanResult.getScanRecord().getDeviceName()`.

### Method 3: Manufacturer ID `0x09C8` (XUNTONG)
Check BLE manufacturer-specific data for company ID `0x09C8`. This catches devices that broadcast no name. On Android: `ScanResult.getScanRecord().getManufacturerSpecificData(0x09C8)` — returns non-null if present.

### Method 4: Raven Service UUID Fingerprinting
Identify SoundThinking/ShotSpotter Raven gunshot detectors by checking for these BLE GATT service UUIDs:

| Service       | UUID prefix  | Description                  |
|---------------|-------------|------------------------------|
| Device Info   | `0000180a`  | Serial, model, firmware      |
| GPS           | `00003100`  | Real-time coordinates        |
| Power         | `00003200`  | Battery & solar status       |
| Network       | `00003300`  | LTE/WiFi connectivity        |
| Upload        | `00003400`  | Data transmission metrics    |
| Error         | `00003500`  | Diagnostics & error logs     |
| Health (legacy)| `00001809` | Firmware 1.1.x               |
| Location (legacy)| `00001819`| Firmware 1.1.x              |

On Android: `ScanResult.getScanRecord().getServiceUuids()`.

### Method 5: Raven Firmware Version Estimation
Determine Raven firmware version (1.1.x / 1.2.x / 1.3.x) from which combination of service UUIDs are advertised. The logic is:
- **1.1.x**: Advertises legacy UUIDs (`00001809`, `00001819`)
- **1.2.x**: Advertises `00003100`–`00003500` without legacy
- **1.3.x**: Advertises `00003100`–`00003500` WITH legacy (both present)

## Android-Specific Considerations

### Permissions (CRITICAL — get this right early)
```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- Android 12+ (API 31+): Use `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` instead of legacy `BLUETOOTH` / `BLUETOOTH_ADMIN`.
- BLE scanning REQUIRES `ACCESS_FINE_LOCATION` on all Android versions (even though we don't care about WiFi location — Android gatekeeps BLE results behind location permission).
- Request permissions at runtime before starting any scan.

### Background BLE Scanning
- Android throttles background BLE scans (~5 per 30 seconds).
- Use a **foreground service** with type `connectedDevice` and a persistent notification showing scan status.
- This is non-optional for reliable continuous scanning.
- Use `ScanSettings.SCAN_MODE_LOW_LATENCY` when in foreground, `SCAN_MODE_LOW_POWER` when backgrounded.

### BLE Scan Filters (performance optimization)
Set hardware-level scan filters where possible to reduce battery drain:
```kotlin
// Filter by manufacturer ID 0x09C8
ScanFilter.Builder()
    .setManufacturerData(0x09C8, byteArrayOf())
    .build()
```
BUT: since we also need to catch devices by name and MAC prefix, we likely need an unfiltered scan and do classification in software. Accept the battery tradeoff.

### Device Deduplication
The ESP32 version stores up to 200 unique devices. On Android with Room, there's no practical limit. Deduplicate by MAC address. Update RSSI and last-seen timestamp on re-detection.

## Data Model

```kotlin
@Entity(tableName = "detected_devices")
data class DetectedDevice(
    @PrimaryKey val macAddress: String,
    val deviceName: String?,
    val detectionMethod: String,    // "MAC_OUI", "BLE_NAME", "MFR_ID", "RAVEN_UUID", etc.
    val deviceType: String,         // "Flock", "Raven", "FS Ext Battery", etc.
    val ravenFirmware: String?,     // "1.1.x", "1.2.x", "1.3.x" or null
    val rssi: Int,
    val latitude: Double?,
    val longitude: Double?,
    val firstSeen: Long,            // epoch millis
    val lastSeen: Long,
    val sessionId: String           // UUID per scan session
)
```

## User-Provided Google Maps API Key (Runtime)

The app does NOT ship with a hardcoded Google Maps API key. Users supply their own key via a Settings screen. This avoids API key leakage, billing surprises, and lets users manage their own quota.

### Implementation Strategy

1. **Settings screen** with a text field for the API key. Store it in `EncryptedSharedPreferences` (from `androidx.security:security-crypto`). A plain `SharedPreferences` is acceptable too but encrypted is better practice for API keys.

2. **Initialize Maps SDK at runtime** using `MapsInitializer.initialize()` with the stored key. The key must be set BEFORE any `MapView` or `SupportMapFragment` is created. The recommended approach:

```kotlin
// In Application.onCreate() or before navigating to MapScreen:
val apiKey = encryptedPrefs.getString("google_maps_api_key", null)
if (apiKey != null) {
    MapsInitializer.initialize(context, MapsInitializer.Renderer.LATEST) { }
}
```

However, the standard Maps SDK reads the key from `AndroidManifest.xml` metadata at init time. For true runtime injection, use this pattern:

```xml
<!-- AndroidManifest.xml — placeholder value, overridden at runtime -->
<meta-data
    android:name="com.google.android.geo.API_KEY"
    android:value="" />
```

Then override it before the map loads:
```kotlin
val ai = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
ai.metaData.putString("com.google.android.geo.API_KEY", userProvidedKey)
```

**Alternative (simpler):** Use `MapView` programmatically with `GoogleMapOptions` and call `MapsInitializer` — test which approach works cleanly with the Compose `AndroidView` wrapper for maps.

3. **Gate the map screen.** If no API key is stored, the Map tab shows a simple prompt:
   - "To enable the wardriving map, enter your Google Maps API key in Settings."
   - Link/button to the Settings screen.
   - The rest of the app (device list, detection, export) works fully without a key.

4. **Validate the key.** After the user enters a key, attempt to load a basic map. If it fails (gray tiles, `OnMapReadyCallback` error), show a toast: "Invalid API key — check your key and try again." Don't persist an invalid key.

5. **Settings screen also includes:**
   - Google Maps API key input (with show/hide toggle)
   - Scan mode toggle (Low Latency vs Low Power)
   - Audio alerts on/off
   - Auto-export interval (optional)
   - "How to get a Maps API key" help link → `https://developers.google.com/maps/documentation/android-sdk/get-api-key`

### Architecture Addition
```
│   ├── ui/
│   │   ├── screens/
│   │   │   ├── SettingsScreen.kt       # API key input, scan prefs, audio toggle
```

### Dependency
```kotlin
implementation("com.google.maps.android:maps-compose:4.3.3")
implementation("com.google.android.gms:play-services-maps:18.2.0")
// For encrypted storage:
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

## Key Reference Files in Original Repo

Before implementing detection logic, READ these files from the cloned repo:
- `src/main.cpp` — Contains all BLE scan callbacks, MAC prefix list, name matching, manufacturer ID check, UUID fingerprinting, and firmware estimation logic. **This is the source of truth.**
- `oui.txt` — Full list of known Flock Safety OUI MAC prefixes.
- `datasets/` — May contain additional detection signatures or known device databases.
- `api/flockyou.py` — Flask companion app; useful reference for data models and export formats.

## What NOT to Port
- **WiFi AP / captive portal** — Not needed; the app IS the UI.
- **SPIFFS filesystem** — Replaced by Room database.
- **Piezo buzzer tone generation** — Replace with SoundPool audio files or ToneGenerator.
- **Serial JSON output** — Not needed unless you want USB debugging output.
- **PlatformIO build system** — Replaced by Gradle.
- **FreeRTOS mutex** — Replaced by Kotlin coroutines / Flow for thread safety.

## Build & Run
```bash
# After creating the project in Android Studio:
./gradlew assembleDebug
# Install on connected device:
./gradlew installDebug
```

## Testing Notes
- **You need a physical Android device** — BLE scanning does not work on emulators.
- Test on multiple manufacturers (Samsung, Pixel, OnePlus) — BLE behavior varies.
- If scan results are empty, check: location services enabled? Permissions granted? Bluetooth on?
