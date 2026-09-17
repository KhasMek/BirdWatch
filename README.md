# BirdWatch

**An Android app for spotting surveillance hardware around you while you walk or drive.**

BirdWatch listens with your phone's Bluetooth and WiFi radios, and with a small ESP32 board
plugged into the phone, for the radio fingerprints of Flock Safety license-plate cameras,
SoundThinking / ShotSpotter "Raven" gunshot detectors, and, if you switch those packs on, police
body cameras and in-car video systems, Meta smart glasses, and drones. Every hit is GPS-tagged,
saved per session, shown on a map, and exportable as JSON, CSV, or KML.

> **For research and educational use.** Passive reception of publicly broadcast radio signals is
> legal in most places; check your local law. BirdWatch never transmits, connects to, or interferes
> with anything it detects. It is not affiliated with any camera-network operator or equipment
> vendor; all trademarks belong to their owners.

---

## What it detects

| Category | Hardware | How it's detected | Default |
|---|---|---|---|
| **Flock Safety cameras** | Flock ALPR cameras | **ESP32 companion** sniffs the cameras' WiFi probe requests over USB (primary). The phone's Bluetooth also matches the older Flock BLE signatures as a fallback. | On |
| **Gunshot detectors** | SoundThinking / ShotSpotter Raven | Phone Bluetooth: Raven's GATT service UUIDs and SoundThinking's hardware address prefix. Also estimates the Raven firmware version. | On |
| **Law-enforcement equipment** *(beta)* | Axon body cams, Fleet in-car, Tasers; WatchGuard, Digital Ally and Utility in-car / body video | Phone Bluetooth (Axon), phone WiFi access-point scan (in-car systems) | Off |
| **Wearable cameras** *(beta)* | Meta Ray-Ban and Oakley Meta glasses | Phone Bluetooth | Off |
| **Drones** *(beta)* | DJI, Parrot, Skydio; any aircraft broadcasting Remote ID | Phone WiFi access-point scan (vendor prefixes); ASTM F3411 Remote ID decoded from Bluetooth and WiFi beacons, including the drone's own reported position and the operator's position | Off |

**Beta** means the signatures are attributed to the vendor by public registries or published
research, but nobody has yet confirmed on worn or active hardware how often the devices actually
broadcast them. Expect misses rather than false alarms. If you can confirm one, please open an
issue.

Everything the app matches, with confidence level, source, and the reasons some candidates were
excluded, is in [`docs/SIGNATURES.md`](docs/SIGNATURES.md).

### How the confidence rating works

Every detection is marked **high** or **low** confidence. It answers one question: how sure are we
that this radio identifier belongs to the kind of hardware we say it does. It is decided per
detection method, not by signal strength or how often something was seen. The rules:

**Flock cameras via the ESP32.** The firmware grades each hit with a tier from 0 to 4; tiers 3 and
4 are high, 0 to 2 are low.

| Tier | What was seen | Confidence |
|---|---|---|
| 4 | Probe request from a known Flock address, wildcard SSID, *and* the information-element fingerprint | High (confirmed camera) |
| 3 | Same probe pattern, fingerprint not matched (unfingerprinted firmware, or an unrelated device sharing the address) | High |
| 2 | A Flock address as the transmitter of any frame | Low |
| 1 | A Flock address seen only as the receiver or BSSID, i.e. a nearby access point echoed it | Low (false-positive prone) |
| 0 | SSID keyword match (off by default in the firmware) | Low |

If the same camera is seen again at a higher tier the stored tier goes up; it never goes down.

**Flock and Raven via the phone's Bluetooth.**
- High: a Flock-registered address prefix, a device name such as `Penguin` or `FS Ext Battery`,
  the XUNTONG manufacturer ID, or the SoundThinking address prefix.
- Low: a *contract manufacturer* address prefix (Liteon, USI). They build Flock hardware but also
  plenty of consumer gear, so the address alone is weak evidence.
- Raven: high if any of Raven's custom services (GPS, power, network, upload, error) is
  advertised; low if the only match is a standard Bluetooth service (Device Information, Health
  Thermometer, Location and Navigation) that ordinary devices advertise too.

**Optional packs.** Each signature carries its own rating in the registry. Everything shipped so
far is high, because only identifiers registered to, or observed exclusively on, the named vendor
were included: Axon's company ID, service UUID and address prefixes; Meta glasses only when the
Luxottica company ID *and* the Meta service appear in the same advertisement (or the name
matches); and the vendor address prefixes for the in-car video systems and drones. Candidates
that would have rated low, such as generic Getac or Panasonic prefixes or a Meta company ID on
its own, were left out rather than shipped as low.

**Remote ID.** Always high: the decoded broadcast is the evidence itself, not an inference from an
address.

Confidence says nothing about whether the device is active right now or how close it is; signal
strength and the sighting count carry that. It is also separate from the **beta** tag on packs,
which is about whether anyone has confirmed on real hardware how often the device broadcasts,
not about attribution.

## What you need

- **An Android phone**, Android 8.0 or newer, with Bluetooth LE. WiFi-beacon Remote ID needs
  Android 11+. Location services must be on (Android withholds Bluetooth scan results otherwise).
- **For Flock camera detection:** a **Seeed XIAO ESP32-S3** running the
  [flock-you](https://github.com/colonelpanichacks/flock-you) WiFi firmware, and a **USB-C OTG
  cable** to plug it into the phone. This is the only way to catch current Flock cameras: they
  stopped advertising over Bluetooth and stopped running a WiFi access point in 2025-2026, so the
  only remaining signal is their probe requests, which a phone's WiFi chip cannot see. The ESP32
  can. Without it, BirdWatch still does everything else.
- **Optional:** your own Google Maps API key for the map tab (free tier is plenty). Detection,
  sessions, and export work without one.

Note that a phone in USB-host mode does not charge from the cable; bring a full battery or a
powered OTG hub for long drives.

## Setup

### 1. Flash the ESP32

Follow the build-and-flash instructions in the
[flock-you repository](https://github.com/colonelpanichacks/flock-you#build-and-flash)
(`pio run -e xiao_esp32s3 -t upload`). BirdWatch speaks the firmware's USB serial protocol
directly; no changes to the firmware are needed.

### 2. Install BirdWatch

Download the latest `BirdWatch-<version>.apk` from the
[Releases](../../releases) page and sideload it (you'll need to allow installs from your browser
or file manager). Versions are dated, `YYYY.MM.N`, so newer is always higher. There is no store
listing yet.

Or build from source with a current Android Studio (one that supports Android Gradle Plugin
9.2) or from the command line:

```bash
./gradlew installDebug        # phone connected over adb
```

Requires Android SDK Platform 37; Gradle provisions its own JDK 21 automatically. The first
build downloads dependencies.

### 3. First run

1. Grant the permissions the app asks for: **Location** (required by Android for Bluetooth
   scanning, and used to tag detections), **Nearby devices**, and optionally **Notifications**
   for the scanning status while the app is in the background. Choose *While using the app* for
   location, not *Only this time*, so background scanning keeps working.
2. Plug in the ESP32. Android will offer to open BirdWatch; accept, and USB access is granted.
   The dashboard's status row shows **ESP32 on** when the link is up.
3. Press **Start scan**. That's it.

### 4. Optional: a Google Maps API key for the Map tab

The map uses Google's Maps SDK, which needs an API key tied to your own Google account. BirdWatch
does not ship one, so nobody else's quota or billing is involved and the key never leaves your
phone (it is stored encrypted and injected into the map at runtime). Everything except the Map tab
works without it.

**Create the key**

1. Open the [Google Cloud Console](https://console.cloud.google.com/) and create a project (any
   name, e.g. "BirdWatch"). Google requires a billing account on the project even for free use;
   the Maps SDK for Android includes a monthly free allowance that a personal wardriving app will
   not come close to using, and you can set a budget alert to be sure.
2. Go to **APIs & Services → Library**, search for **Maps SDK for Android**, and click **Enable**.
   Only this one API is needed.
3. Go to **APIs & Services → Credentials → Create credentials → API key**. Copy the key; it starts
   with `AIza` and is 39 characters long.

**Lock the key to BirdWatch** (do this; an unrestricted key can be used by anyone who extracts it)

4. On the key's edit page, under **Application restrictions**, choose **Android apps** and add an
   item with:
   - **Package name:** `com.khasmek.birdwatch`
   - **SHA-1 certificate fingerprint:** the fingerprint of the certificate that signed *your*
     installed copy. See below for how to find it.
5. Under **API restrictions**, choose **Restrict key** and tick only **Maps SDK for Android**.
6. Save. Restrictions can take a few minutes to apply.

**Finding the SHA-1 fingerprint**

- *Installed from the Releases page:* every release is signed with the project's release
  certificate, and its SHA-1 is printed at the top of each release's notes. To read it from the
  APK yourself you need `apksigner` from the Android SDK build-tools (`keytool` cannot read APK
  signatures):
  ```bash
  apksigner verify --print-certs BirdWatch-2026.09.1.apk | grep "SHA-1"
  ```
- *Built from source yourself:* debug builds are signed with your machine's debug key:
  ```bash
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
    -storepass android -keypass android | grep SHA1
  ```
  If you also make signed release builds, add their certificate's SHA-1 as a second entry on
  the same key. One key can list several package/fingerprint pairs.

**Enter it in the app**

7. Settings → *Google Maps* → paste the key → **Save key**, then open the Map tab. Streets and
   labels mean it works. A blank grey map with only the Google logo means the key is wrong, the
   Maps SDK for Android isn't enabled, or the package/fingerprint restriction doesn't match the
   copy you installed. Google's own guide is at
   [Get an API key](https://developers.google.com/maps/documentation/android-sdk/get-api-key).
8. If you ever replace the key, restart the app; the Maps SDK caches the first key it reads.

## Using it

**Dashboard.** Live list of everything detected this session: type badge, name or hardware
address, how it was matched, signal strength, and how long ago it was last seen. Tap a card for
GPS, timestamps, sighting count and the raw matched value. The stats bar shows totals per
category and the state of each radio. Scanning continues with the screen off; a persistent
notification shows the running count and has a Stop button.

**Detection packs.** Settings → *Detection packs*. Flock and Raven are always on. Switch on the
law-enforcement, wearable, or drone packs as you see fit. The law-enforcement pack detects
police *presence* (officers and cruisers), not fixed infrastructure, and its hits are counted
under "Other" so they never inflate the Flock or Raven numbers.

**Phone WiFi scan.** Settings → *Phone WiFi access-point scan*. Needed for in-car police video and
for drone vendor prefixes, which show up as WiFi access points. Android limits how often apps may
scan, so results refresh every 15 to 30 seconds. On a rooted phone,
`adb shell settings put global wifi_scan_throttle_enabled 0` removes the limit.

**Map.** Needs your own Google Maps API key; see [step 4](#4-optional-a-google-maps-api-key-for-the-map-tab)
above for creating one and locking it to this app. Markers are coloured by category. Remote ID drones appear at their self-reported
position with a second marker for the operator and a dashed line between them. Switch between
the current session and all sessions with the chips above the map, and switch whole categories
on or off with the second row of chips. In *All sessions* a device is one pin no matter how many
times you drove past it.

**Seen before.** A device that was already recorded in an earlier session gets a "seen N× before"
tag on its card, and by default plays a short low tick instead of the chirp, so on a route you
drive often only new hardware makes you look at the phone. The tick can be switched back to the
full chirp in Settings.

**Editing a device.** Tap a pin for its details and the edit buttons. *Move pin* puts a crosshair
over the map: pan until it sits on the device (or tap *My location* if you're standing next to
it) and save; every session that saw the device now draws it there, and the detected position
is kept too. *Set alias* gives it a name of your own, shown above the detected name and MAC on
every screen. *Hide* drops the pin but keeps the data; the "hidden by you" button above the map
brings it back. *Delete* removes the detection itself. Move and alias are only offered for
devices with a fixed address; smart glasses and Remote ID drones rotate theirs.

**Sessions and export.** Every scan is a session. The Sessions tab lists them with counts; tap
one for its detections, share it as JSON, CSV or KML through the Android share sheet, or delete
it. The export icon on the dashboard shares the session that's running right now. Exports use
the corrected position and alias when you've set them (the detected position rides along as
`detected_latitude`/`detected_longitude`), and hidden devices are left out of KML.

**Restoring an export.** *Import session…* in the Sessions menu (the ⋮ button) imports a JSON or
CSV file that BirdWatch exported earlier, on this phone or another one, as a session with its
original timestamps and GPS. KML can't be imported (it only carries located devices). A session that's
already present is refused, so the same file can't be imported twice.

**Backup and restore.** The Sessions menu has *Back up…* and *Restore…*. Back up writes every
session to one file you save wherever you like (Drive, Files, an SD card), and lets you choose
which categories to include, for example Flock cameras and body cams but not gunshot detectors.
JSON is the full format; CSV is also restorable but drops session names and exact start/end
times; KML is for Google Earth only. Restore reads a JSON or CSV backup, shows what's in it, lets
you leave categories out, and *merges*: sessions you already have keep everything and gain any
missing detections, sessions you don't have are added. A detection present in both keeps its most
recent record and higher sighting count, so restoring an old backup never rolls anything back.
Your pin corrections, aliases and hidden flags travel with the backup and are restored unless
you've made a newer edit on the phone since.
*Delete all data…* wipes every session and detection after confirmation; settings and your Maps
key stay.

**Importing from the ESP32.** The board keeps its own detection table when it runs on its own
(powered from a battery pack, say). Plug it in, open the Sessions menu and choose *Import from
ESP32…* to pull either the current run (from memory) or the previous run (saved to flash at
power-off) into a new session. Imported detections have no GPS and are timestamped at import time, because the
board has no clock; their tier, channel, signal and sighting counts are preserved.

**Audio.** A two-note chirp for a high-confidence detection, a single blip for a low-confidence
one, mirroring the ESP32 firmware's buzzer. Mute from the dashboard or Settings.

## Privacy

Everything stays on your phone. There is no account, no server, no analytics, and no network
traffic apart from Google Maps tiles when you use the map with your own key. The app opts out
of Android's cloud backup and device-to-device transfer, so the detection database and your
Maps key are never copied off the phone by the system; use Sessions > Back up… when you want to
move data yourself. Exports and backups contain GPS coordinates and timestamps; treat them
accordingly before sharing.

## Relationship to flock-you

BirdWatch started life as a port of the flock-you ESP32 project and grew into the phone-side
dashboard for its current firmware, then into a broader scanner. The ESP32 does the hard part for
Flock cameras; the phone contributes GPS, storage, mapping, export, and its own Bluetooth and
WiFi radios for everything else. The detection logic ported from the firmware is kept faithful to
the upstream code, and the newer signatures each cite where they came from (below).

## Credits and sources

This app is built on other people's research. Every detection signature traces to one of these;
the same list is in the app under *Settings → About & credits* and, with the exact values, in
[`docs/SIGNATURES.md`](docs/SIGNATURES.md).

- **[colonelpanichacks / flock-you](https://github.com/colonelpanichacks/flock-you)** — the
  project this app grew from. The ESP32 WiFi firmware, its USB serial protocol and confidence
  tiers, and the earlier BLE-era firmware whose Flock and Raven signatures, firmware-version
  estimation and de-duplication logic are ported here.
- **OrdoOuroboros / [@NitekryDPaul](https://github.com/nitekry)** —
  [nite-oui-collection](https://github.com/nitekry/nite-oui-collection): the Flock Cam WiFi
  hardware-address list, the receiver-side detection technique, and the law-enforcement
  equipment address list (Axon, WatchGuard, Digital Ally, Utility Inc.).
- **[DeFlockJoplin](https://github.com/DeflockJoplin/flock-you)** — the wildcard-probe plus
  information-element fingerprint that confirms a Flock camera, and one additional hardware
  prefix, drive-tested in Joplin.
- **[Will Greenberg](https://github.com/wgreenberg/flock-you)** — the XUNTONG Bluetooth company
  ID used by Flock devices.
- **[colonelpanichacks / oui-spy-unified-blue](https://github.com/colonelpanichacks/oui-spy-unified-blue)**
  — the Axon Bluetooth signatures, the Meta glasses composite matcher and name patterns, and the
  DJI / Parrot / Skydio address database.
- **[lnxgod / friendorfoe](https://github.com/lnxgod/friendorfoe)** — cross-reference for the
  Meta glasses discrimination logic.
- **[Bluetooth SIG Assigned Numbers](https://www.bluetooth.com/specifications/assigned-numbers/)**
  and the **[IEEE OUI registry](https://standards-oui.ieee.org/)** — vendor attribution for
  company IDs, service UUIDs and hardware address prefixes.
- **[ASTM F3411 / Open Drone ID](https://github.com/opendroneid/opendroneid-core-c)** — the
  Remote ID broadcast format decoded for drones.
- **[DeFlock](https://github.com/FoggedLens/deflock)** ([deflock.me](https://deflock.me)) —
  crowdsourced ALPR mapping and identification guides; also the reason fixed ALPR competitors are
  *not* fingerprinted here (no public radio signatures exist).
- **Lucia Pintor & Luigi Atzori (2022)**, *Analysis of Wi-Fi Probe Requests Towards Information
  Element Fingerprinting*, IEEE GLOBECOM,
  [doi:10.1109/GLOBECOM48099.2022.10001618](https://doi.org/10.1109/GLOBECOM48099.2022.10001618)
  — the fingerprinting method used by the ESP32 firmware.

The app itself uses [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android),
Jetpack Compose, Room, and the Google Maps Compose library.

## License

[MIT](LICENSE). The upstream flock-you project is MIT licensed as well; please keep the research
credits above intact if you fork or redistribute, as its authors ask.

## Disclaimer

BirdWatch passively receives radio signals that the detected devices broadcast to everyone
nearby. It does not transmit, connect, authenticate, jam, or otherwise interact with them.
Detecting the presence of surveillance hardware in public spaces is legal in most jurisdictions;
you are responsible for complying with the laws where you use it. The authors are not
responsible for misuse.
