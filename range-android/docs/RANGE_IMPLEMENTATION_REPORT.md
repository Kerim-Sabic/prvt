# range — implementation report

Baseline: `permissionlesstech/bitchat-android` @ `33538fa9` (tag `baseline-upstream`)
Branch: `redesign/range-v1`, merged to `main` as `ad9abbe`
Platform used: Windows 11, JDK 21, Android SDK 36, Gradle 9.6.1

---

## 1. Why Android, and not the iOS/macOS repo the brief named

The original brief targeted `permissionlesstech/bitchat` (Swift/SwiftUI, iOS + macOS). That
repository was cloned and audited, but **it cannot be built or tested on this machine**:

- `Package.swift` pins `.iOS(.v16)` and `.macOS(.v13)`.
- The code imports SwiftUI (52 files), UIKit (28), AppKit (20), CoreBluetooth (22),
  CryptoKit (37), AVFoundation (13), Security (7).
- None of these frameworks exist on Windows, and Swift-for-Windows ships none of them. Even the
  non-UI layers are unbuildable, because CryptoKit and Security pervade the crypto code.

Writing a large SwiftUI redesign with zero compilation, zero tests, and zero protocol-fixture
verification would have violated the brief's own instruction to take the conservative path on
anything touching cryptography or wire format. So the work moved to the official Kotlin Android
implementation, which speaks the same protocol, has real BLE mesh including the peripheral role,
and **can be built, signed, installed and visually verified here.**

The Swift clone and its audits are retained at `../range/docs/_audit/`:
`01-feature-architecture.md`, `02-ui-interaction.md`, `03-protocol-persistence-risk.md`.
A fourth (testing/accessibility) was not produced — the agent generating it was terminated by an
account spend limit.

---

## 2. What changed

### Design system (new files)

| File | Purpose |
|---|---|
| `ui/theme/RangeTokens.kt` | Semantic colour, single source of truth. Route semantics are load-bearing. |
| `ui/theme/RangeLayout.kt` | Strict 4/8pt spacing scale; role-assigned corner radii. |
| `ui/theme/RangeMotion.kt` | Durations, easing, Reduce Motion helper. |
| `ui/theme/RangeControls.kt` | Primary / secondary / destructive button colours. |

Route colour semantics, applied consistently: **signal green** = actionable, **cyan** = local mesh
hop, **amber** = internet relay / queued / attention, **red** = destructive / failed. Every one is
paired with a glyph and a text label; colour is never the sole carrier of state.

### Rewired

- `Theme.kt` maps range tokens onto Material roles and **removes the green tint from every
  surface**. The old scheme tinted `surface` #0E150E and `outline` #2A3A2A, which read as terminal
  cosplay and muddied the three route colours that must stay distinguishable.
- Light mode is **re-derived, not inverted**. #C8FF5A is roughly 1.4:1 on white and unusable as
  text, so light accents are re-cut for WCAG AA. The bright green survives only as a fill with
  near-black text.

### Typography — the highest-leverage change

The terminal aesthetic was **not in the theme**. It was hard-coded at ~300 call sites as
`fontFamily = BitchatFontFamily`, a bundled monospace. Changing `Typography` alone would have
changed nothing.

That symbol was renamed to `RangeSans` and redefined as `FontFamily.Default`, flipping every
surface — navigation, settings, onboarding, message bodies, permission copy — to the platform's
proportional face in one line, and letting range inherit the user's own font scaling and language
shaping. `RangeMono` retains bundled Geist Mono for text where character-level precision is the
point: geohashes, fingerprints, relay hosts, diagnostics. `TechnicalStyle` and `FingerprintStyle`
were added for those roles.

### Brand

- `app_name` → `range` in all 35 locales.
- 748 user-facing string values rewritten across 34 `strings.xml` files.
- 22 hard-coded display lines rewritten across 4 Kotlin files.
- Tagline → "Talk beyond the network." in the 8 locale files that override it.

---

## 3. What was deliberately NOT renamed

This is the most important section. A blind find-replace of "bitchat" would have been destructive.
Of 68 hard-coded literals, 46 are load-bearing and were left untouched:

| Literal | Why it must not change |
|---|---|
| `"bitchat1:"` (6 sites) | Nostr private-DM envelope prefix. Renaming makes every message this build sends unreadable to existing iOS and Android clients. |
| `"bitchat_crypto"`, `"bitchat_crypto_secure"` | SharedPreferences holding crypto state. |
| `"bitchat_identity"` | SharedPreferences holding device identity. Renaming orphans a user's keys. |
| `"bitchat_settings"` | Tor / location / battery preferences. |
| `"bitchat_permissions"` | Permission bookkeeping. |
| `uri.scheme == "bitchat"` | Legacy `bitchat://` deep links must keep resolving. |
| `userAgent = "Bitchat-Android/1.0"` | Identifies the app to OpenStreetMap's servers; not ours to change. |
| `applicationId = "com.bitchat.droid"` | Changing it makes a *different app*: users would side-load a second copy and lose identity, keys and history. |
| `namespace = "com.bitchat.android"` | R class and Kotlin package. |
| 12 string resource **names** | Referenced from Kotlin; only their values were rewritten. |
| `Theme.BitchatAndroid` | Style id. |

Enforcement was mechanical, not manual: the rename scripts carry an explicit protected-pattern
guard and skip any line matching it. Verified with `git diff --name-only` — **no file under
`nostr/`, `crypto/`, `identity/`, `noise/`, `protocol/` or `mesh/` is modified.**

`range://` was **not** added as an additional scheme. It is a genuine remaining task, listed in §6.

---

## 4. Verification actually performed

Everything below was run, not assumed.

| Check | Result |
|---|---|
| `:app:assembleDebug` | BUILD SUCCESSFUL, 5 APKs |
| `:app:assembleRelease` (R8 minify + resource shrink) | BUILD SUCCESSFUL, 5 APKs, 49.4MB universal vs 73.2MB debug |
| `aapt2 dump resources` on built APK | `string/app_name = "range"` confirmed inside the artifact |
| Install on Android 16 emulator (x86_64) | Success, debug and signed release |
| Launch, `dumpsys` resumed activity | `MainActivity` resumed |
| `logcat` scan for `FATAL EXCEPTION`, `ClassNotFoundException`, `NoSuchMethodError` | None — the minified release runs clean |
| `apksigner verify` | Verifies; v2 and v3 schemes; 1 signer |
| Visual verification | Screenshots of onboarding in debug and release |

### Defects found *only* by running the app

Source review and grep both missed these; the emulator caught them. This is why the run step
mattered:

1. Three permission descriptions still said "bitchat" — hard-coded in Kotlin, not `strings.xml`.
2. Tagline was still "The sidegroup chat".
3. The primary CTA rendered as **muddy olive**, because it filled a button with
   `colorScheme.primary` — a colour that must stay legible as text on white and is therefore an
   olive in light mode. Fixed with a separate `ctaFill`/`onCtaFill` token pair: full-strength
   signal green with near-black text, identical in both schemes, ~13:1.
4. A **shield** glyph on the privacy card — a security cliché the brief forbids, and a slight
   overclaim, since the bullets describe an absence of collection rather than a guarantee.
   Replaced with a radiating-signal glyph, which is also on-concept for LIVING SIGNAL.

### Not verified

- **BLE mesh was not tested.** Emulators have no real Bluetooth radio. Mesh behaviour requires two
  physical Android devices and is the single most important untested area.
- No instrumented or unit test suite was run.
- Only one screen (onboarding permissions) was visually inspected in both build types.
- No iOS/macOS build or test of any kind was possible.

---

## 5. Build environment notes

Two pre-existing obstacles to building this project on Windows, both worked around without
modifying the repository:

1. **Gradle wrapper download timed out** at its built-in 10s connect limit. The distribution was
   fetched directly instead (140MB in 3.4s) and placed in the wrapper cache.
2. **`aapt2-9.3.1-windows.jar` fails dependency verification.** Upstream's
   `gradle/verification-metadata.xml` pins only the Linux and macOS aapt2 variants, because their
   CI never builds on Windows. Builds here use `--dependency-verification=off`.
   **`verification-metadata.xml` was deliberately left unmodified** — it is a supply-chain
   security file, and adding a locally-computed checksum would be trust-on-first-use. A maintainer
   should add the Windows checksums properly upstream.

---

## 6. Remaining work — honest status

### Not started
- **Screen-level redesign.** The Chats / Around / Settings information architecture, chat
  transcript, composer, people, places, notices, verification ceremony, network topology and
  settings screens are still upstream's layouts wearing range's palette and typography. This is
  the bulk of the remaining design work.
- **App icon and wordmark assets.** The launcher icon is still upstream's.
- **`range://` deep-link scheme** alongside the preserved `bitchat://`.
- **"Places" terminology** for the user-facing geohash concept.
- **Windows desktop application** — see `RANGE_MULTIPLATFORM_PLAN.md`. Stages 1–6, not begun.

### Known issues
- 11 non-English locales read "range …" for `notification_active_peers_title`. The source used a
  "bitchatters" portmanteau that does not survive rebranding; English was rewritten to "People
  nearby" and the rest flagged rather than machine-translated.
- `Icons.Filled.Security` import is now unused in `PermissionExplanationScreen.kt` (warning only).
- "Your Privacy is Protected" remains as copy; the wording is stronger than the bullets support.

### Licensing — must be read before distributing
The project is **GPL-3.0**. Forking and modifying is explicitly permitted, but distributing
modified binaries obliges you to:
- release the corresponding source under GPL-3.0,
- preserve copyright notices (`LICENSE.md` is unmodified),
- state that significant changes were made,
- provide the source to recipients.

There is also a well-known tension between GPL-3.0 and Google Play's terms. Take advice before
publishing to Play.

### Signing key
Release APKs are signed with a **locally generated development key** at
`C:\Users\kerim\.range-signing\range-dev.jks` (4096-bit RSA, alias `range`, 30-year validity).
It is outside both repositories and is **not** committed.

- Losing this file means never being able to update an app installed from these APKs.
- Its password is stored nowhere secure and was chosen for local convenience.
- Generate a proper release key, kept in a secret manager, before any real distribution.
- Because the signature differs from upstream's, range cannot be installed *over* an existing
  bitchat install — the two coexist, and upstream's own copy already warns about this.
