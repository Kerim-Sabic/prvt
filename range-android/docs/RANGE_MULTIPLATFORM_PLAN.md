# range — Windows desktop and multiplatform plan

Status: analysis complete, measured against the real codebase. No desktop code written yet.
Baseline: `bitchat-android` @ `33538fa9`, redesign branch `redesign/range-v1`.

---

## 1. The constraint that drives every decision

range's defining feature is the **Bluetooth LE mesh**. To be a mesh *peer* — not merely a
client — a device must run both BLE roles at once:

| Role | What it does | Needed for |
|---|---|---|
| **Central** | Scans, connects, writes to others' GATT characteristics | Finding and talking to peers |
| **Peripheral** | Advertises, hosts a GATT server others connect to | Being findable; relaying |

A node that can only do *central* can talk to a full node but cannot be discovered or relay for
anyone. It is a leaf, not mesh infrastructure. So peripheral support is the hard gate on every
platform.

### Platform capability, measured against that gate

| Platform | Central | Peripheral | Verdict |
|---|---|---|---|
| Android | Yes | Yes (`BluetoothLeAdvertiser`, `BluetoothGattServer`) | **Full mesh peer** |
| iOS / macOS | Yes | Yes (`CBPeripheralManager`) | **Full mesh peer** |
| Windows 10/11 | Yes | **Yes** (`BluetoothLEAdvertisementPublisher`, `GattServiceProvider`) | **Full mesh peer** |
| Browser | Partial | **No** | Cannot mesh. Relay-only client at best. |

**Windows can be a real mesh peer.** The capability exists in WinRT and has since Windows 10.
This is why the desktop target is worth doing and the browser target is not — and it is the
reason this plan drops the web app.

### Why the browser is excluded

Web Bluetooth exposes only the central role, over a permission model that requires a user gesture
per device and forbids background operation. A browser tab cannot advertise, cannot host a GATT
server, and cannot run while backgrounded. A "range web app" could therefore only ever be a Nostr
relay client wearing range's clothes — it would show the UI while quietly lacking the one
capability the product is named for. That is precisely the "fake network" outcome the brief
forbids, so it is not built.

A read-only web surface (public geohash channel viewer) remains defensible later, but it must be
labelled as a relay client, never as range.

---

## 2. Recommended architecture: Kotlin Multiplatform + Compose Multiplatform

One Kotlin codebase producing the Android APK and the Windows desktop binary, sharing protocol,
crypto, and — critically — the range design system and UI.

```
range/
  core/                       (commonMain — pure Kotlin, no platform APIs)
    protocol/                 packet encode/decode, message types, versioning
    noise/                    Noise session handling
    nostr/                    event construction, relay selection
    model/  geohash/  sync/   domain types, geohash math, store-and-forward
    ports/                    expect declarations (the seam)
      Logger  KeyValueStore  SecureStore  BleTransport  LocationProvider  Permissions
  ui/                         (commonMain — Compose Multiplatform)
    theme/                    RangeTokens, RangeLayout, RangeMotion  <- already written
    components/  screens/     shared range UI
  androidApp/                 actual: Android BLE, DataStore, Keystore, FusedLocation
  desktopApp/                 actual: WinRT BLE, file-backed store, DPAPI, JVM entry
```

### Why this is the right call, not just the fashionable one

- The Android app is **already Kotlin** (389 files, 86,776 LOC). No language port is needed.
- Compose Multiplatform runs the *same* composables on Android and desktop, so the range design
  system already committed on this branch (`RangeTokens.kt`, `RangeLayout.kt`, `RangeMotion.kt`)
  is shared verbatim. Those files are pure Kotlin + Compose and need no changes to compile for
  desktop.
- The protocol layer stays in **one** implementation. Two hand-written implementations of a
  Noise-encrypted binary wire format will diverge, and the divergence will be a security bug.
  This is the single strongest argument against a separate Rust or C# desktop app.

### Measured feasibility of the extraction

Android coupling in the layers destined for `core/`, counted from source on this branch:

| Package | Files | LOC | Files importing `android.*` |
|---|---|---|---|
| `mesh` | 32 | 10,665 | 21 (66%) |
| `nostr` | 29 | 7,557 | 22 (76%) |
| `geohash` | 13 | 2,059 | 9 (69%) |
| `noise` | 5 | 2,067 | 4 (80%) |
| `model` | 10 | 1,422 | 5 (50%) |
| `protocol` | 4 | 939 | 2 (50%) |
| `sync` | 4 | 566 | 1 (25%) |
| `crypto` | 1 | 526 | 1 (100%) |
| `identity` | 1 | 539 | 1 (100%) |
| **Total** | **~99** | **~26,340** | — |

That looks alarming until you count *what* is imported. All 179 Android imports across those
packages:

| Dependency | Count | Difficulty |
|---|---|---|
| `android.util.Log` | 53 | **Trivial** — one `expect` logger |
| `android.content.Context` | 28 | **Mechanical** — carrier for prefs/resources |
| `android.app.Application` | 7 | **Mechanical** |
| `android.content.SharedPreferences` | 6 | **Trivial** — `KeyValueStore` port |
| `android.util.Base64` | 3 | **Trivial** — pure Kotlin equivalent |
| `SuppressLint`, `core.content.edit` | 5 | **Trivial** — annotations/sugar |
| `android.os.Parcelable`, `ParcelUuid` | 7 | Low — Android-only serialisation |
| `android.bluetooth.*` | ~12 | **Genuinely platform-specific** (expected) |
| `android.location.*` | ~14 | **Genuinely platform-specific** (expected) |
| `Manifest`, `PackageManager`, `ActivityCompat` | ~12 | **Genuinely platform-specific** (expected) |
| `androidx.security.crypto.MasterKey` | 2 | **Genuinely platform-specific** (expected) |

**~102 of 179 (57%) is logging, preferences, and `Context` plumbing.** The remainder is BLE,
location, permissions, and keystore — the four things that *should* be `expect/actual` in any
multiplatform design. The protocol, Noise, and Nostr logic itself is essentially pure Kotlin once
a logger and a key-value store are injected.

Conclusion: this is a **decoupling exercise, not a rewrite.** That is the finding that makes the
desktop target economically sensible.

---

## 3. The hard part: BLE peripheral role on Windows from the JVM

Everything above is routine. This is the one genuinely difficult piece, and it should not be
soft-pedalled.

Compose Multiplatform desktop runs on the JVM. The JVM has no Bluetooth API, so the WinRT calls
must be reached from Kotlin/JVM. Options, honestly assessed:

| Option | How | Assessment |
|---|---|---|
| **A. Rust sidecar** | Small `windows`-crate binary owning BLE; talks to the JVM over a local socket with a length-prefixed frame protocol | **Recommended.** The `windows` crate has complete, generated WinRT bindings including `GattServiceProvider` and `BluetoothLEAdvertisementPublisher`. Rust toolchain is already present on this machine. Sidecar can crash and restart without taking the UI with it. Cost: an IPC boundary and a second binary to ship. |
| **B. Panama / JNI direct** | Bind WinRT from the JVM via Project Panama (JDK 22+) or hand-written JNI | Removes the extra process, but WinRT is COM-based with reference counting and async `IAsyncOperation` types. Binding it by hand is error-prone and hostile to debug. Not worth it. |
| **C. `btleplug` (Rust)** | Popular cross-platform Rust BLE crate | **Insufficient alone — central role only.** Fine for scanning; cannot advertise or host a GATT server. Would produce a leaf node. Usable *inside* option A for the central half. |
| **D. .NET/C# desktop app** | WinUI 3 + native WinRT | Easiest BLE story by far, but forces a **second implementation of the protocol and crypto** in C#. Rejected on those grounds — see the divergence argument above. |
| **E. Electron + Node** | `noble` / `bleno` | **Rejected.** `bleno` is unmaintained and has never supported the Windows peripheral role. |

**Recommendation: option A.** A ~1,500-line Rust sidecar exposing a narrow, versioned command
channel (`advertise`, `scan`, `connect`, `write`, `subscribe`, `notify`, plus inbound events),
with the Kotlin side implementing the `BleTransport` port against it.

### Risks to confirm before committing to a delivery date

These are real and must be settled with a spike, not assumed:

1. **Adapter variance.** Windows BLE behaviour differs sharply across Intel/Realtek/Broadcom
   chipsets and driver versions. Peripheral advertising in particular is inconsistent in the
   field. Must be tested on at least three different radios.
2. **MTU and throughput.** Windows negotiates ATT MTU differently from Android; the mesh's
   fragmentation assumptions need re-verifying rather than trusting.
3. **Simultaneous roles.** Some Windows radios degrade or refuse advertising while scanning.
   Needs measurement.
4. **No background execution guarantee.** Desktop range only participates while running.
5. **Concurrent advertisers.** Windows historically limits the number of active advertisement
   publishers per adapter.

Until item 1 is measured, treat "Windows is a full mesh peer" as *capable in principle, unproven
in the field.*

---

## 4. Staged delivery

Each stage ships something verifiable and leaves the protocol untouched.

**Stage 0 — range identity on Android (in progress; foundation committed)**
Design system, then screen-level redesign, on the existing APK. Ships a real product.

**Stage 1 — the seam**
Introduce `core/ports/` with `expect` declarations for `Logger`, `KeyValueStore`, `SecureStore`,
`BleTransport`, `LocationProvider`, `Permissions`. Implement Android `actual`s by delegating to
the current code. **No behaviour change, protocol untouched, existing tests must still pass.**
This is the highest-value/lowest-risk stage and the one that unlocks everything after it.

**Stage 2 — extract `core/`**
Move protocol, noise, nostr, model, geohash, sync into `commonMain`, replacing the 102 trivial
Android imports with ports. Gate on the existing protocol/golden-fixture tests: they are the
proof that the wire format survived the move.

**Stage 3 — BLE spike on Windows (de-risk before building UI)**
Standalone Rust binary: advertise the range GATT service, accept a connection from an Android
handset, exchange one framed packet. **Go / no-go gate.** If Windows advertising proves unusable
on target radios, the desktop app degrades to a documented relay-only client rather than silently
pretending to mesh.

**Stage 4 — desktop shell**
Compose Multiplatform desktop app: `NavigationSplitView`-equivalent three-column layout, sharing
the range design system and screens. Nostr transport first (no BLE dependency), so there is a
working desktop app before the hard part lands.

**Stage 5 — wire the sidecar**
Implement desktop `BleTransport` against the Stage 3 binary. Desktop becomes a true mesh peer.

**Stage 6 — packaging**
`msi`/`exe` via Compose's `packageReleaseDistributionForCurrentOS` (jpackage), sidecar bundled
alongside. Code signing required or SmartScreen will block it.

Stages 1–2 are worth doing **even if the desktop app is never shipped**, because they also make
the protocol layer independently testable on the JVM without an emulator.

---

## 5. What NOT to do

- **Do not** reimplement the protocol or crypto in Rust, C#, or TypeScript. Two implementations
  of a Noise-encrypted wire format will diverge, and that divergence is a security bug.
- **Do not** ship a browser build that presents itself as range while being relay-only.
- **Do not** rename protocol constants, Keychain/Keystore aliases, storage keys, App Group ids, or
  the `bitchat://` scheme as part of this work. The wire and storage identity stay; only the
  user-facing brand changes. Legacy deep links keep working; `range://` is added alongside.
- **Do not** let the desktop UI construct packets, choose relays, or read secure storage directly.
  It consumes presentation state, same as the mobile UI.

---

## 6. Answering the original question directly

> can we bring this to web, and to Windows desktop?

- **Web: no**, not as range. The browser cannot advertise or host a GATT server, so it cannot be
  a mesh peer. Anything shipped there would be a relay-only client, and calling it range would
  misrepresent what it does.
- **Windows desktop: yes**, and it can be a *full* mesh peer, because WinRT exposes both BLE
  roles. The work is a Kotlin Multiplatform decoupling (measured above as ~57% trivial) plus a
  Rust sidecar for BLE. The one genuine unknown is Windows radio/driver variance, which Stage 3
  is designed to settle before any UI is built on top of it.
- **Android: already viable today.** Full mesh, and the build is verified on this machine.
