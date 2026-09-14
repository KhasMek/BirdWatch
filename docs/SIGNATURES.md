# Detection signatures: registry, attribution, and expansion plan

This file is the single source of truth for **which radio signatures the app matches, how
confident each one is, and where it came from**. Every entry in
`app/src/main/java/com/khasmek/birdwatch/detection/` must trace back to a row here, and the
README credits section is generated from the "Sources" table at the bottom. Add a source here
before adding a signature in code.

Status legend: **shipped** (in the app today), **planned** (approved for a phase below),
**deferred** (known but excluded, with the reason).

---

## 1. Packs

Signatures are grouped into *packs*. A pack is a category the user can switch on or off in
Settings. Only the **Core** pack is on by default; everything else is opt-in so the app's default
behaviour stays "Flock cameras and Ravens", matching its name.

| Pack | Category | Default | Detected by | Status |
|---|---|---|---|---|
| Core: Flock Safety | ALPR / fixed surveillance | on | ESP32 WiFi (primary), phone BLE (legacy names/OUIs/mfr ID) | shipped |
| Core: SoundThinking / Raven | Gunshot detector | on | phone BLE (service UUIDs, OUI) | shipped |
| Law-enforcement equipment | Body cams, in-car video | off | phone BLE (Axon); phone WiFi AP scan (WatchGuard, Digital Ally, Utility) | **shipped (beta)** |
| Wearable cameras | Smart glasses | off | phone BLE (Meta composite) | **shipped (beta)** |
| Drones | Consumer / commercial UAS | off | phone WiFi AP scan (OUIs); ASTM F3411 Remote ID over BLE service data (0xFFFA) and WiFi beacon vendor IE (ASD-STAN OUI) | **shipped (beta)** |
| Consumer doorbells / cameras | Ring | off | phone WiFi AP scan | deferred (see §4) |

"Beta" in the app means: identifiers are attributed to the vendor by registry or published
research, but nobody has yet confirmed on real, worn/active hardware how often the device
advertises them. Expect false negatives, not false positives.

The phone WiFi AP scan is a separate switch in Settings (off by default). While it is on, Core
Flock and SoundThinking OUIs are also matched against AP BSSIDs; Flock cameras no longer run an
AP, so this is a legacy safety net, not the primary Flock path (that is the ESP32).

---

## 2. Signature registry

Confidence is what the app records on a hit. **HIGH** = the identifier is registered to, or
observed only on, the named vendor's surveillance product. **LOW** = attributable but shared
with unrelated hardware, or an echo-type match.

### 2.1 Core: Flock Safety (shipped)

| Signature | Type | Radio | Confidence | Source |
|---|---|---|---|---|
| 21 OUIs (`58:8e:81`, `cc:cc:cc`, `ec:1b:bd`, `90:35:ea`, `04:0d:84`, `f0:82:c0`, `1c:34:f1`, `38:5b:44`, `94:34:69`, `b4:e3:f9`, `70:c9:4e`, `3c:91:80`, `d8:f3:bc`, `80:30:49`, `14:5a:fc`, `74:4c:a1`, `08:3a:88`, `9c:2f:9d`, `94:08:53`, `e4:aa:ea`, `b4:1e:52`) | OUI | BLE + WiFi | HIGH | S1 |
| 14 OUIs from 2026-07-16 sync (`b8:35:32`, `c0:35:32`, `24:b2:b9`, `e0:4f:43`, `b8:1e:a4`, `70:08:94`, `3c:71:bf`, `58:00:e3`, `5c:93:a2`, `64:6e:69`, `48:27:ea`, `a4:cf:12`, `14:b5:cd`, `82:6b:f2`) | OUI | WiFi (also applied to BLE) | HIGH | S2, S3 (82:6b:f2) |
| Contract-manufacturer OUIs (`f4:6a:dd`, `f8:a2:d6`, `e0:0a:f6`, `00:f4:8d`, `d0:39:57`, `e8:d0:fc`) — Liteon / USI | OUI | BLE | LOW | S1 |
| Names `FS Ext Battery`, `Penguin`, `Flock`, `Pigvision` | name substring | BLE | HIGH | S1 |
| Company ID `0x09C8` (XUNTONG) | mfr ID | BLE | HIGH | S1, S4 |
| Wildcard probe + IE fingerprint, tiers 0-4 | 802.11 probe request | ESP32 WiFi | tier 3-4 HIGH, 0-2 LOW | S2, S3, S5 (firmware) |

### 2.2 Core: SoundThinking / ShotSpotter Raven (shipped)

| Signature | Type | Radio | Confidence | Source |
|---|---|---|---|---|
| Service UUIDs `0x3100` GPS, `0x3200` Power, `0x3300` Network, `0x3400` Upload, `0x3500` Error | service UUID | BLE | HIGH | S1 |
| Service UUIDs `0x180A` Device Info, `0x1809` Health (legacy), `0x1819` Location (legacy) | service UUID | BLE | LOW alone (standard SIG services), HIGH with any custom one | S1 |
| Firmware estimate 1.1.x / 1.2.x / 1.3.x from UUID combination | derived | BLE | n/a | S1 |
| OUI `d4:11:d6` (SoundThinking, IEEE) | OUI | BLE + WiFi | HIGH | S1 |

No other public signature exists for older ShotSpotter sensors (wired/LTE). Nothing to add.

### 2.3 Law-enforcement equipment (shipped, beta)

| Vendor | Signature | Type | Radio | Confidence | Source |
|---|---|---|---|---|---|
| Axon (Body 3/4, Fleet, Taser 7/10, Signal) | Company ID `0x034D` (TASER International) | mfr ID | BLE | HIGH | S6, S7 (SIG registry) |
| Axon | Service UUID `0xFC81` (Axon Enterprise) | service UUID | BLE | HIGH | S6, S7 |
| Axon | OUIs `00:25:DF`, `00:1F:55`, `00:0F:13` | OUI | BLE + WiFi | HIGH (`00:25:DF` is IEEE-registered to Axon; the other two per S8) | S6, S8 |
| WatchGuard Video / Motorola Solutions (in-car) | OUIs `00:19:86`, `00:1A:E9` | OUI | WiFi | HIGH | S8 |
| Digital Ally (body / in-car) | OUIs `00:11:24`, `00:1B:63` | OUI | WiFi | HIGH | S8 |
| Utility Inc. (BodyWorn) | OUIs `00:09:BC`, `00:16:ED` | OUI | WiFi | HIGH | S8 |

Excluded from this pack (see §4): Getac, Panasonic i-PRO, Axis-registered "Flock" OUIs.

**Important product note.** This pack detects police *presence* (officers, cruisers), not fixed
infrastructure. It is opt-in and its hits are counted under their own category, never as
Flock or Raven.

### 2.4 Wearable cameras (shipped, beta)

| Vendor | Signature | Type | Radio | Confidence | Source |
|---|---|---|---|---|---|
| Meta / Ray-Ban / Oakley Meta glasses | Company ID `0x0D53` (Luxottica) **AND** service UUID `0xFD5F` (Meta) in the same advertisement | composite | BLE | HIGH | S6, S9 |
| Meta glasses | Local name contains `Ray-Ban`, `Wayfarer`, or `Oakley Meta` | name substring | BLE | HIGH | S6 |

No OUI: the glasses use resolvable private (rotating) addresses, so MAC matching is noise.
Company ID alone or service UUID alone are false-positive magnets (S6 removed them); only the
composite or the name counts.

### 2.5 Drones (shipped, beta)

| Vendor | Signature | Type | Radio | Confidence | Source |
|---|---|---|---|---|---|
| DJI | OUIs `0c:9a:e6`, `8c:58:23`, `04:a8:5a`, `58:b8:58`, `e4:7a:2c`, `60:60:1f`, `48:1c:b9`, `34:d2:62` | OUI | WiFi (aircraft/controller AP) | HIGH | S6 |
| Parrot | OUIs `00:12:1c`, `00:26:7e`, `90:03:b7`, `90:3a:e6`, `a0:14:3d` | OUI | WiFi | HIGH | S6 |
| Skydio | OUI `38:1d:14` | OUI | WiFi | HIGH | S6 |
| Any Remote-ID-compliant UAS | ASTM F3411 / Open Drone ID: BLE service data UUID `0xFFFA` + app code `0x0D` (legacy single message or BT5 Message Pack); WiFi beacon vendor IE 221 with OUI `FA:0B:BC` + type `0x0D`. Decoded: Basic ID (serial / registration, UA type), Location (position, altitude, speed, track), System (operator position), Operator ID, Self ID. Map and KML show the aircraft at its reported position **and** the operator at theirs, joined by a dashed line | protocol | BLE + WiFi beacon (API 30+) | HIGH (standardised; the payload is the evidence) | S10 |

---

## 3. Implementation plan

### Phase 9a: signature packs on the phone BLE path (no new radios)

1. **Data model.** Replace the fixed `DeviceType` triple with a `Vendor`/`DeviceType` enum that
   carries a `category` (`FLOCK_ALPR`, `GUNSHOT_DETECTOR`, `LAW_ENFORCEMENT`, `WEARABLE_CAMERA`,
   `DRONE`, `CONSUMER_CAMERA`) and a display label + accent colour. New enum values are
   backward-compatible with Room (enums are stored by name; no migration). Add
   `BLE_SERVICE_UUID` and `BLE_COMPOSITE` to `DetectionMethod`.
2. **Pack definitions.** `detection/SignaturePacks.kt`: a `SignaturePack(id, name, category,
   defaultEnabled, entries, sources)` per row in §1, with typed entries (`Oui`, `CompanyId`,
   `ServiceUuid16`, `NameSubstring`, `Composite`) each carrying a confidence and a source id.
   Flock and Raven move into the Core pack; `DetectionSignatures.kt` keeps the raw constants.
3. **Classifier.** `DeviceClassifier.classify(adv, enabledPacks)` stays pure. Core keeps its
   current priority order; other packs are evaluated afterwards in pack order. A device matches
   the first pack that hits.
4. **Settings.** "Detection packs" section with one switch per non-core pack, persisted in
   `AppSettings`; a short description and the product note for the LE pack. Packs can be
   toggled mid-session (next advertisement uses the new set).
5. **UI.** `DeviceCard` badge and Map marker hue per category. `StatsBar` gains an "Other"
   count that only appears when any opt-in pack is enabled, so Flock/Raven counts never mix
   with LE/wearable hits. Session summaries add an `otherCount`.
6. **Export.** JSON/CSV/KML gain a `category` field; KML gets a style per category.
7. **Credits.** Settings gets an "About & credits" screen listing §5 with links; README
   credits are copied from §5. Each pack entry in code references its source id.
8. **Tests.** Per-pack classifier tests (Axon by each of three identifiers; Meta composite
   fires, CID-alone and UUID-alone do not; pack disabled means no match; Core unaffected).

### Phase 9b: phone WiFi access-point scan as a third source

Needed for the WiFi-only OUIs (WatchGuard, Digital Ally, Utility, DJI, Parrot, Skydio), which
belong to devices that run their own access point.

1. `wifi/WifiApScanner.kt`: subscribe to `SCAN_RESULTS_AVAILABLE_ACTION` and read
   `WifiManager.scanResults` (the system already scans every 15-30 s with the screen on);
   call `startScan()` only on session start and at most every 30 s to respect Android's
   throttle (4 per 2 min foreground). Match BSSID OUIs against enabled packs; SSID patterns
   only where a vendor uses a fixed prefix (to be confirmed per vendor before shipping).
2. Feeds the same `DetectionTable` with `source = PHONE_WIFI`; persisted and exported like
   the others. Stats bar shows a third radio indicator.
3. Off by default; switch in Settings under the same "Detection packs" section.
4. Note the rooted-device option `settings put global wifi_scan_throttle_enabled 0` in docs
   for faster scanning during wardriving.

### Phase 9c (shipped): Remote ID decoding for drones

`detection/RemoteId.kt` parses ASTM F3411 Open Drone ID messages (Basic ID, Location, System,
Operator ID, Self ID, Message Pack) from BLE service data under UUID 0xFFFA and from WiFi
beacon vendor elements carrying the ASD-STAN OUI. Standardised, highly reliable, and yields the
drone's own position (and its operator's) rather than just presence. Gated by the Drones pack.

---

## 4. Deferred / excluded, with reasons

| Candidate | Reason |
|---|---|
| Ring (11 OUIs, S6) | Vendor-attributable but ubiquitous in residential areas; several prefixes are Amazon-registered and shared with Echo devices. Would dominate the list and dilute the app's purpose. Revisit only as an explicitly separate "consumer cameras" pack. |
| Getac (`00:1c:23`, `50:ec:50`), Panasonic i-PRO (`3c:bb:fd`, `e0:13:33`) | General-purpose hardware vendors (rugged laptops, IP cameras). Attribution to law enforcement is circumstantial. |
| "Flock Safety" `00:40:8c`, `ac:cc:8e` (S8) | These are Axis Communications OUIs; would match any Axis camera. |
| Fixed ALPR competitors (Motorola Vigilant, Rekor, Genetec AutoVu, Leonardo ELSAG) | No public RF signatures exist. DeFlock (S11) catalogues them by appearance only. Do not invent fingerprints. |
| Older ShotSpotter sensors | Wired / LTE, no known BLE or WiFi emission. |
| Company ID `0x0D53` alone, service UUID `0xFD5F` alone | False-positive magnets per S6; only the composite counts. |

---

## 5. Sources (credit these in the README)

| Id | Source | What we took | Link |
|---|---|---|---|
| S1 | colonelpanichacks / flock-you, BLE-era `src/main.cpp` at commit `6c6930b` | All BLE Flock and Raven signatures, firmware-estimation logic, dedupe semantics | https://github.com/colonelpanichacks/flock-you/blob/6c6930b1917383f71a0413e35095c62fe9cae726/src/main.cpp |
| S2 | OrdoOuroboros / **@NitekryDPaul**, nite-oui-collection `groups/flockers/my_tested_flock.md` (2026-07-16) | Flock Cam WiFi OUI list, addr1 receiver-side technique | https://github.com/nitekry/nite-oui-collection |
| S3 | **DeFlockJoplin** / flock-you fork | OUI `82:6b:f2`, wildcard-probe + IE-fingerprint signature | https://github.com/DeflockJoplin/flock-you |
| S4 | **Will Greenberg** (@wgreenberg) / flock-you fork | XUNTONG company ID `0x09C8` | https://github.com/wgreenberg/flock-you |
| S5 | colonelpanichacks / flock-you (current `main.cpp`, dev branch) | ESP32 firmware serial JSON protocol, tiers | https://github.com/colonelpanichacks/flock-you |
| S6 | colonelpanichacks / oui-spy-unified-blue, `src/raw/detector.cpp` | Axon preset (OUI, CID, UUID), Meta composite matcher and name patterns, DJI / Parrot / Skydio / Ring OUI database | https://github.com/colonelpanichacks/oui-spy-unified-blue |
| S7 | Bluetooth SIG Assigned Numbers | Company IDs `0x034D`, `0x0D53`, `0x09C8`; 16-bit service UUIDs `0xFC81`, `0xFD5F` | https://www.bluetooth.com/specifications/assigned-numbers/ |
| S8 | @NitekryDPaul, nite-oui-collection `groups/le/privacy_invaders_ouis_law_enforcement.csv` | Axon, WatchGuard, Digital Ally, Utility Inc. OUIs (and the excluded Getac / Panasonic / Axis rows) | https://github.com/nitekry/nite-oui-collection/blob/main/groups/le/privacy_invaders_ouis_law_enforcement.csv |
| S9 | lnxgod / friendorfoe, `esp32/scanner/main/detection/ble_fingerprint.c` | Cross-reference for the Meta composite discrimination logic (cited by S6) | https://github.com/lnxgod/friendorfoe |
| S10 | ASTM F3411 / Open Drone ID | Remote ID message formats for BLE advertisements and WiFi beacon vendor elements | https://github.com/opendroneid/opendroneid-core-c |
| S11 | DeFlock (FoggedLens) | ALPR make/model catalogue; confirms no RF signatures for fixed ALPR competitors | https://github.com/FoggedLens/deflock |
| S12 | IEEE OUI registry | Vendor attribution for every MAC prefix above | https://standards-oui.ieee.org/ |
| S13 | Lucia Pintor & Luigi Atzori (2022), "Analysis of Wi-Fi Probe Requests Towards Information Element Fingerprinting", IEEE GLOBECOM | The IE-fingerprint method used by the ESP32 firmware (research credit as written upstream) | https://doi.org/10.1109/GLOBECOM48099.2022.10001618 |

`Sources.ALL` in `SignaturePacks.kt` mirrors this table row for row and feeds the in-app
"About & credits" screen; keep the two in step.
