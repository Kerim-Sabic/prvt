# 01 — Feature and Architecture Inventory

**Subject:** `C:\Users\kerim\prvt\range` — bitchat (clone of `github.com/permissionlesstech/bitchat`)
**Date of audit:** 2026-07-30
**Method:** source read of `bitchat/`, `bitchatShareExtension/`, `localPackages/`, `docs/`, `Package.swift`, `bitchat.xcodeproj`. No inference from README claims alone; every feature row below is anchored to a file.

---

## 0. Scale

| Metric | Value |
|---|---|
| Swift files (repo total) | 501 |
| App target (`bitchat/`) | 259 files, 76,244 LOC |
| Test target (`bitchatTests/`) | 198 files, 67,330 LOC |
| Local packages (`localPackages/`) | 41 files, 5,774 LOC |
| Largest single file | `bitchat/Services/BLE/BLEService.swift` — 7,825 lines |
| Second largest | `bitchat/ViewModels/ChatViewModel.swift` — 2,427 lines |
| Third largest | `bitchat/Nostr/NostrRelayManager.swift` — 1,946 lines |
| Test:code ratio | ~0.88:1 by LOC (this is a heavily-tested codebase; ~1,964 tests per `docs/BLE-ARCHITECTURE-V3.md`) |

---

## 1. Repository layout

```
/                              README.md, WHITEPAPER.md, PRIVACY_POLICY.md, SECURITY.md,
                               BRING_THE_NOISE.md, Package.swift, Package.resolved,
                               Justfile, .swiftlint.yml, .periphery.yml
/Configs                       Debug.xcconfig, Release.xcconfig, Local.xcconfig.example
/bitchat                       app target (App, Features, Identity, Models, Noise, Nostr,
                               Protocols, Services, Sync, Utils, ViewModels, Views,
                               _PreviewHelpers, Assets.xcassets, Localizable.xcstrings)
/bitchatShareExtension         iOS share extension (1 Swift file + own .xcstrings)
/bitchatTests                  198 test files (EndToEnd, Features, Fragmentation, Integration,
                               Localization, Mocks, Noise, Nostr, Performance, Prekeys,
                               Protocols, Services, Simulation, Sync, TestUtilities, Utils)
/localPackages/Arti            Tor via Arti (Rust static lib xcframework + Swift wrapper)
/localPackages/BitFoundation   wire types & primitives shared app↔extension↔tests
/localPackages/BitLogger       SecureLogger, OSLog categories, string sanitization
/relays                        online_relays_gps.csv (geo-relay directory seed)
/scripts                       perf floors, georelay validation, mac appicon gen
/docs                          12 design docs + privacy assessment + this _audit dir
/.github/workflows             swift-tests, arti-provenance, source-manifest,
                               periphery, fetch_georelays
```

### 1.1 SwiftPM manifest (`Package.swift`)

- `swift-tools-version: 5.9`, `defaultLocalization: "en"`, platforms `iOS 16` / `macOS 13`.
- Executable target `bitchat`, path `bitchat`, resource `.process("Localizable.xcstrings")`.
- Dependencies: local `Arti`, `BitFoundation`, `BitLogger`; remote `21-DOT-DEV/swift-secp256k1` pinned `exact: 0.21.1` (product `P256K`).
- Test target `bitchatTests` with resources `Localization`, `Noise/NoiseTestVectors.json`, `Nostr/Fixtures`.
- Note: SwiftPM is the *test* driver; the shipping app is built from `bitchat.xcodeproj`.

### 1.2 Xcode targets and schemes

| Target (pbxproj `productName`) | Platform | Notes |
|---|---|---|
| `bitchat_iOS` | iOS | `TARGETED_DEVICE_FAMILY = 1` (iPhone only), portrait-only, `UIRequiresFullScreen` |
| `bitchat_macOS` | macOS | `TARGETED_DEVICE_FAMILY = "1,2"` on some configs; ships as universal app |
| `bitchatShareExtension` | iOS only | `SUPPORTED_PLATFORMS = "iphoneos iphonesimulator"` |
| `bitchatTests_iOS` | iOS | |
| `bitchatTests_macOS` | macOS | |

Shared schemes: `bitchat (iOS).xcscheme`, `bitchat (macOS).xcscheme`.

### 1.3 Local packages

| Package | Contents |
|---|---|
| `BitFoundation` | `BitchatMessage`, `BitchatPacket`, `BinaryProtocol`, `MessageType`, `PeerID`, `PeerCapabilities`, `DeliveryStatus`, `CourierEnvelope`, `PrekeyBundle`, `MessagePadding`, `CompressionUtil` (LZ4), `FileTransferLimits`, `MeshPingPayload`, `KeychainManagerProtocol`, `Constants`, `Data+Hex`, `Data+SHA256`, `String+Ext` |
| `BitLogger` | `SecureLogger`, `OSLog+Categories`, `String+Sanitization` |
| `Arti` | `TorManager`, `TorURLSession`, `TorNotifications`, `arti.xcframework` (ios-arm64, ios sim, macos), Rust crate `arti-bitchat` |

---

## 2. Architecture shape

### 2.1 Layer diagram (as implemented)

```
SwiftUI Views (bitchat/Views/*)
        │  @EnvironmentObject: 10 feature models injected by BitchatApp
        ▼
Feature models (bitchat/App/*)                      ← "V2" layer
  PublicChatModel · PrivateInboxModel · PrivateConversationModel
  ConversationUIModel · PeerListModel · LocationChannelsModel
  AppChromeModel · VerificationModel · SharedContentImportModel
  BoardAlertsModel (lives in Services/Board)
        │
        ├── ConversationStore  ← SINGLE SOURCE OF TRUTH for message state
        │      Conversation (ObservableObject, one per ConversationID)
        │
        ▼
ChatViewModel (2,427 lines)  ← still the domain/UI bridge
        │  20 coordinators + 3 extensions (bitchat/ViewModels/*)
        ▼
Services (bitchat/Services/*)   MessageRouter · UnifiedPeerService · NoiseEncryptionService
                                CommandProcessor · NotificationService · KeychainManager
                                Board/ Courier/ Gateway/ Groups/ Prekeys/
        ▼
Transport protocol (bitchat/Services/Transport.swift)
        ├── BLEService (7,825 lines) + 54 BLE satellite files
        └── NostrTransport → NostrRelayManager → TorURLSession → Arti
```

### 2.2 Composition root — `AppRuntime`

`bitchat/App/AppRuntime.swift` (447 lines, `@MainActor final class AppRuntime: ObservableObject`) is the **only** composition root. It:

- constructs `ConversationStore`, `PeerIdentityStore`, `LocationPresenceStore`, `ChatViewModel`, and all 10 feature models in `init`;
- owns app lifecycle (`start()`, `handleScenePhaseChange`, `applicationWillTerminate`);
- owns notification routing (`handleNotificationResponse`, `presentationOptions`);
- owns Tor observer wiring (6 `NotificationCenter` publishers: `TorWillStart`, `TorWillRestart`, `TorDidBecomeReady`, `TorBootstrapDidStall`, `TorUserPreferenceChanged`) and the iOS screenshot observer;
- owns shared-content intake (`checkForSharedContent`, `handleOpenURL` for `bitchat://share`);
- emits typed `AppEvent`s into an `actor AppEventStream` (`bitchat/App/AppArchitecture.swift`).

### 2.3 `ConversationStore` — the single source of truth

`bitchat/App/ConversationStore.swift` (975 lines). Design in `docs/CONVERSATION-STORE-DESIGN.md` (**migration complete, steps 1–5**).

- `ConversationID` = `.mesh` | `.geohash(String)` | `.direct(PeerHandle)` (`bitchat/App/AppArchitecture.swift`).
- `Conversation` is a reference-type `@MainActor ObservableObject` with `@Published private(set) var messages`, `isUnread`, an **incrementally maintained** message-ID index, and per-conversation cap policy (`TransportConfig.meshTimelineCap` / `geoTimelineCap` / `privateChatCap`).
- Publishing granularity is **per conversation** — an append to chat A never invalidates observers of chat B.
- Intent API only: `append`, `upsertByID`, `setDeliveryStatus(_:forMessageID:in:)`, `markRead`/`markUnread`, `migrateConversation(from:to:)`, `removeMessage`, `clear`, `clearAll`.
- `changes: PassthroughSubject<ConversationChange, Never>` for non-UI consumers.
- Selection state lives here too: `activeChannel`, `selectedPrivatePeerID`, derived `selectedConversationID`.
- **In-memory only** — bitchat conversation timelines are never persisted (media files and the gossip archive are the exceptions; see §11).
- Has an `auditInvariants()` self-check and `_test*` hooks.

Deleted during the migration (do not look for them): `PublicTimelineStore`, `LegacyConversationStore`, `LegacyConversationStoreBridge`, `IdentityResolver`, `ChatDeliveryCoordinator.messageLocationIndex`.

### 2.4 Is there one giant ChatViewModel?

**Yes, but it has been hollowed out.** `ChatViewModel` is 2,427 lines and still the hub, but:

- `messages`, `privateChats`, `unreadPrivateMessages` are now **derived read-only computed views** over `ConversationStore`, not stored `@Published` properties.
- Startup wiring moved to `ChatViewModelBootstrapper` (688 lines).
- 20 coordinators carry the behavior (table in §5.2).
- Views no longer receive `ChatViewModel` in the environment at all — `BitchatApp` injects only feature models.

Remaining `@Published` on `ChatViewModel`: `currentColorScheme`, `currentTheme`, `isConnected`, `panicRecoveryBlocked`, `nickname`, `allPeers`, `activePublicVoiceTalker`, `autocompleteSuggestions`, `showAutocomplete`, `autocompleteRange`, `selectedAutocompleteIndex`, `showBluetoothAlert`, `bluetoothAlertMessage`, `bluetoothState`, `legacyPrivateMediaConsentRequest`, `isBatchingPublic`.

### 2.5 How views obtain state

`BitchatApp.body` injects exactly ten environment objects:

```swift
.environmentObject(runtime.publicChatModel)
.environmentObject(runtime.privateInboxModel)
.environmentObject(runtime.privateConversationModel)
.environmentObject(runtime.verificationModel)
.environmentObject(runtime.conversationUIModel)
.environmentObject(runtime.locationChannelsModel)
.environmentObject(runtime.peerListModel)
.environmentObject(runtime.appChromeModel)
.environmentObject(runtime.boardAlertsModel)
.environmentObject(runtime.sharedContentImportModel)
```

Plus `.environment(\.appTheme, …)` driven by `@AppStorage(AppTheme.storageKey)`.

**Leaks in the abstraction** — views that still reach singletons directly:

| View | Singleton reached |
|---|---|
| `ContentHeaderView` | `BridgeService.shared`, `CourierStore.shared`, `BoardStore.shared`, `NearbyNotesCounter.shared` |
| `ContentComposerView` | `BridgeService.shared` |
| `AppInfoView` | `BridgeService.shared`, `LocationChannelManager.shared`, `PTTSettings`, `LocationNotesSettings`, `NotificationPrivacySettings`, `NostrRelaySettings`, `NostrRelayManager.builtInRelayURLs`, `AppLanguageSettings` |
| `MeshEmptyStateView` | `GeohashChatActivityTracker.shared`, `MeshSightingsTracker.shared`, `NearbyNotesCounter.shared` |
| `NoticesView` | `LocationNotesPool.shared`, `LocationNotesSettings` |

---

## 3. App entry points, scene structure, iOS vs macOS

### 3.1 Entry point

`bitchat/BitchatApp.swift` — `@main struct BitchatApp: App`.

- `static let bundleID` / `static let groupID = "group.\(bundleID)"` — app group used by the share extension.
- `@StateObject private var runtime: AppRuntime` created in `init()`.
- `UNUserNotificationCenter.current().delegate = NotificationDelegate.shared` set in `init()`.
- Single `WindowGroup { ContentView() … }` — **one scene, no `Settings` scene, no multi-window, no `MenuBarExtra`, no commands**.
- `.onAppear { appDelegate.runtime = runtime; runtime.start() }`, `.onOpenURL { runtime.handleOpenURL(url) }`.

### 3.2 Platform differences

| Concern | iOS | macOS |
|---|---|---|
| App delegate | `@UIApplicationDelegateAdaptor(AppDelegate.self)` | `@NSApplicationDelegateAdaptor(MacAppDelegate.self)` |
| Delegate duties | `PrivacyScreen.shared.install()` at launch; `applicationWillTerminate` | `applicationWillTerminate`; `applicationShouldTerminateAfterLastWindowClosed → true` |
| Lifecycle signal | `@Environment(\.scenePhase)` → `runtime.handleScenePhaseChange` (background: Tor dormant, Nostr disconnect, geohash sampling ends; active: mesh restart, Tor foreground, relay reset) | `NSApplication.didBecomeActiveNotification` → `handleMacDidBecomeActiveNotification` only. **macOS has no background/inactive handling at all.** |
| Window style | default | `.windowStyle(.hiddenTitleBar)`, `.windowResizability(.contentSize)`, `ContentView.frame(minWidth: 600, minHeight: 400)` |
| App-switcher privacy | `PrivacyScreen` (opaque UIWindow cover on `willResignActive`) | none |
| Image picker | `ImagePickerView` (`UIImagePickerController`, camera + library, `.fullScreenCover`) | `MacImagePickerView` (`NSOpenPanel`-style, `.sheet`) |
| Composer attach button | camera icon; tap = library, long-press = camera | photo icon; single action |
| App Info sheet chrome | `NavigationView` + toolbar close button | custom header row + `Done`, fixed `600×700` frame |
| People sheet | `.sheet` | `.sheet` with `minWidth: 420, minHeight: 520` |
| Text field focus | default | `onAppear` forces `isTextFieldFocused = true` |
| Focus ring | n/a | `FocusEffectDisabledModifier` uses `.focusEffectDisabled()` on macOS 14+ |
| Share extension | present | **absent** |
| Background modes | `bluetooth-central`, `bluetooth-peripheral` | n/a |
| Orientation | portrait only, `UIRequiresFullScreen` | resizable window |
| Entitlements | `bitchat.entitlements` | `bitchat-macOS.entitlements` |
| Autocapitalization | `.textInputAutocapitalization(.sentences)` / `.never` | n/a |
| Screenshot detection | `UIApplication.userDidTakeScreenshotNotification` | n/a |
| External URL open | `UIApplication.shared.open` | `NSWorkspace.shared.open` |

### 3.3 URL scheme

`CFBundleURLSchemes = ["bitchat"]`. Handled in `AppRuntime.handleOpenURL`: only `bitchat://share` is acted on (triggers `checkForSharedContent`). Deep links inside notifications (`userInfo["deeplink"]`) are opened externally.

### 3.4 Info.plist permission strings (iOS)

`NSBluetoothAlwaysUsageDescription`, `NSBluetoothPeripheralUsageDescription`, `NSCameraUsageDescription` (QR verify), `NSLocationWhenInUseUsageDescription` (geohash channels / bridge cells / place labels), `NSMicrophoneUsageDescription` (voice notes + PTT), `NSPhotoLibraryUsageDescription`.

---

## 4. View inventory

| View file | Role | State source |
|---|---|---|
| `Views/ContentView.swift` (527) | Root screen: header + public timeline + composer; owns 10 sheet/alert presentations, `ContentRootModalPresentationState` | 6 env objects + local `@State` |
| `Views/ContentHeaderView.swift` (416) | Top bar: logo (tap=AppInfo, triple-tap=panic), nickname field, gateway glyph, courier-mail glyph, unread-envelope, notices pin, bookmark, channel badge, people count | `AppChromeModel`, `LocationChannelsModel`, `PeerListModel`, `BoardAlertsModel`, `VerificationModel` + 4 singletons |
| `Views/ContentComposerView.swift` (376) | Composer: autocomplete panel, command suggestions, recording HUD, text field, nearby-only toggle, attach button, send/mic button | `ConversationUIModel`, `PrivateConversationModel`, `LocationChannelsModel`, `BridgeService.shared` |
| `Views/MessageListView.swift` (745) | Timeline: windowed rendering, jump-to-latest pill, unseen separator, triple-tap clear, per-message context menus, URL open, media rows | `PublicChatModel`, `PrivateInboxModel`, `ConversationUIModel` |
| `Views/ContentSheetViews.swift` (752) | `ContentPeopleSheetView` (people list ⇄ DM view switcher) + `ContentPeopleListView` (sections) + legacy-media consent dialog | 6 env objects |
| `Views/AppInfoView.swift` (936) | Settings/Info segmented sheet — see §8 | `LocationChannelsModel`, `BridgeService.shared`, `@AppStorage` |
| `Views/NoticesView.swift` (828) | Unified notices sheet: geo tab (mesh board + Nostr notes merged) and mesh tab (offline board); composer with urgency + expiry picker | `BoardManager`, `LocationNotesManager`, `LocationChannelsModel` |
| `Views/LocationChannelsSheet.swift` (548) | Location channel picker: permission gate, level list (region…building) with participant counts, custom-geohash teleport field, bookmarks section | `LocationChannelsModel`, `PeerListModel` |
| `Views/MeshPeerList.swift` (266) | `#mesh` peer rows: reachability glyph, favorite star, verified seal, vouched seal, unread envelope, blocked glyph; context menu (DM, fingerprint, block/unblock) | `PeerListModel` |
| `Views/GeohashPeopleList.swift` (166) | Geohash participants list, teleport glyph, DM / block / unblock | `PeerListModel` |
| `Views/BridgePeopleList.swift` (76) | `PeopleSectionHeader` + "across the bridge" section | `BridgeService` |
| `Views/GroupChatList.swift` (81) | Private-groups section rows | `PeerListModel.groupRows` |
| `Views/FingerprintView.swift` (273) | Peer fingerprint / verification detail screen | `VerificationModel` |
| `Views/VerificationViews.swift` (382) | `MyQRView`, `QRCodeImage`, `ImageWrapper`, `QRScanView`, `VerificationSheetView` | `VerificationModel` |
| `Views/MeshTopologyView.swift` (217) | Mesh graph map from gossiped `directNeighbors` | `MeshTopologyDisplayModel` provider closure |
| `Views/MessageTextHelpers.swift` (89) | Shared text-parsing helpers for rendering | — |
| `Views/Components/TextMessageView.swift` (186) | One text-message row | |
| `Views/Components/DeliveryStatusView.swift` (158) | Delivery status glyphs (sending/sent/carried/delivered/read/failed/partial) | |
| `Views/Components/CommandSuggestionsView.swift` (106) | `/`-command suggestion panel | `CommandInfo.all(isGeoPublic:isGeoDM:)` |
| `Views/Components/PaymentChipView.swift` (260) | Cashu / Lightning payment chips with wallet + web-redeem fallback | `CashuTokenDecoder` |
| `Views/Components/MeshEmptyStateView.swift` (217) | Empty mesh timeline: sonar, today's sightings tally, nearby-conversation hint, "check for notes" hint | 3 singletons + 2 env objects |
| `Views/Components/MeshRadarView.swift` (68) | Decorative sonar sweep (static under Reduce Motion) | — |
| `Views/Components/IRCToggleStyle.swift` (34) | Shared on/off pill toggle style | — |
| `Views/Components/SheetCloseButton.swift` (29) | Shared sheet close button | — |
| `Views/Media/MediaMessageView.swift` (160) | Media bubble dispatcher (image vs voice) | `ConversationUIModel` |
| `Views/Media/BlockRevealImageView.swift` (314) | Blurred-until-tapped image, progressive block reveal during transfer, cancel/delete/open context menu | |
| `Views/Media/VoiceNoteView.swift` (127) | Voice-note bubble with waveform + play/pause | `VoiceNotePlaybackController` |
| `Views/Media/WaveformView.swift` (68) | Waveform rendering | `WaveformCache` |
| `Views/Media/LiveVoiceBadge.swift` (51) | `PulsingOpacityModifier` + red LIVE chip | |
| `Views/Image Viewers/ImagePickerView.swift` (79) | iOS camera / photo library | |
| `Views/Image Viewers/MacImagePickerView.swift` (71) | macOS file picker | |
| `Views/Image Viewers/ImagePreviewView.swift` (147) | Full-screen image preview + save | |

`_PreviewHelpers/` (`BitchatMessage+Preview.swift`, `PreviewKeychainManager.swift`) are development assets excluded from archive builds.

---

## 5. View models and coordinators

### 5.1 Feature models (`bitchat/App/`)

| Model | Published surface | Backing |
|---|---|---|
| `PublicChatModel` (77) | `activeChannel`; `messages` reads the observed `Conversation` directly | `ConversationStore` (observes ONE `Conversation`) |
| `PrivateInboxModel` (in `PrivateConversationModels.swift`) | `selectedPeerID`, `unreadPeerIDs`; `messages(for:)` | `ConversationStore` |
| `PrivateConversationModel` (same file, 362 total) | `selectedPeerID`, `selectedHeaderState` (`PrivateConversationHeaderState`: display name, availability, encryption, favorite, group-ness) | `ChatViewModel` + `ConversationStore` + `PeerIdentityStore` |
| `ConversationUIModel` (239) | `showAutocomplete`, `autocompleteSuggestions`, `currentNickname`, `isBatchingPublic`, `canSendMediaInCurrentContext`, `legacyPrivateMediaConsentRequest`, `activeLiveVoiceTalker`; 25 action methods (send, resend, clear, hug, slap, block/unblock, format, media send/cancel/delete, voice) | `ChatViewModel` |
| `PeerListModel` (299) | `allPeers`, `meshRows`, `geohashPeople`, `groupRows`, `reachableMeshPeerCount`, `connectedMeshPeerCount`, `visibleGeohashPeerCount`, `renderID` | `ChatViewModel`, `ConversationStore`, `LocationChannelsModel`, `PeerIdentityStore`, `LocationPresenceStore` |
| `LocationChannelsModel` (184) | `permissionState`, `availableChannels`, `selectedChannel`, `teleported`, `bookmarks`, `bookmarkNames`, `locationNames`, `userTorEnabled`, `gatewayEnabled` | `LocationChannelManager` (= `LocationStateManager.shared`) |
| `AppChromeModel` (150) | `hasUnreadPrivateMessages`, `nickname`, `showingFingerprintFor`, `isAppInfoPresented`, `isLocationChannelsSheetPresented`, `isNoticesSheetPresented`, `noticesSheetPrefersGeoTab`, `showBluetoothAlert`, `bluetoothAlertMessage`, `bluetoothState`, `showScreenshotPrivacyWarning`; lazily owns `BoardManager` | `ChatViewModel`, `PrivateInboxModel` |
| `VerificationModel` (185) | `currentNickname`, `selectedPeerID`; `myQRString()`, `verifyScannedPayload`, `verify/unverifyFingerprint`, `fingerprintPresentation` (incl. `voucherCount`) | `ChatViewModel`, `PrivateConversationModel`, `PeerIdentityStore` |
| `SharedContentImportModel` (119) | `offer: SharedContentOffer?`; `refresh/confirm/cancel/discardAll`. **Deliberately has no send dependency** — confirming can never transmit | `SharedContentStore` (app-group `UserDefaults`) |
| `BoardAlertsModel` (in `Services/Board/`, 160) | Unseen-pin counters per scope; emits system lines for urgent pins | `BoardStore` publishers |

Also in `bitchat/App/`: `PeerIdentityStore` (fingerprints, encryption status, verified set, short↔stable peer-ID map), `LocationPresenceStore` (current geohash, geo nicknames LRU, teleported set LRU), `NearbyNotesCounter` (privacy-gated nearby-notes count), `PrivacyScreen` (iOS snapshot cover), `AppArchitecture.swift` (`AppEvent`, `AppEventStream`, `PeerHandle`, `ConversationID`).

### 5.2 ChatViewModel coordinators (`bitchat/ViewModels/`)

All follow the same pattern: a narrow `…Context` protocol instead of an `unowned ChatViewModel` back-reference, so each is independently unit-tested.

| Coordinator | LOC | Responsibility |
|---|---|---|
| `ChatViewModelBootstrapper` | 688 | Service construction and Combine wiring at startup; archived-echo re-seed |
| `ChatMediaTransferCoordinator` | 1,906 | Image/voice/file send + receive, transfer progress, private-media capability policy, legacy-consent flow, retries |
| `ChatPrivateConversationCoordinator` | 943 | Inbound/outbound DM handling, ephemeral↔stable peer-ID consolidation |
| `ChatLiveVoiceCoordinator` | 711 | Push-to-talk burst assembly/playback, live bubbles, floor courtesy |
| `ChatPeerIdentityCoordinator` | 661 | Fingerprints, encryption status, identity rotation handling |
| `ChatGroupCoordinator` | 610 | Private groups: create/invite/remove/leave/list, key epochs |
| `ChatPublicConversationCoordinator` | 602 | Public timeline ingest, archived echoes, rate limiting |
| `NostrInboundPipeline` | 560 | Nostr event decrypt/dedup/dispatch (off-main decrypt with wipe generation) |
| `ChatTransportEventCoordinator` | 538 | Typed `TransportEvent` → app dispatch (incl. `NoisePayloadType` switch) |
| `ChatMessageFormatter` | 501 | Formatting facade over `MessageFormattingEngine` |
| `ChatVerificationCoordinator` | 418 | QR verify challenge/response |
| `GeohashSubscriptionManager` | 402 | Geohash REQ lifecycle, sampling subs |
| `ChatLifecycleCoordinator` | 375 | Foreground/background, startup phase |
| `ChatVouchCoordinator` | 272 | Transitive verification (vouch attestations) |
| `ChatPeerListCoordinator` | 272 | Peer-list notification + timer state |
| `ChatOutgoingCoordinator` | 238 | Outbound send paths incl. `sendMeshWave()` |
| `ChatDeliveryCoordinator` | 217 | Delivery/read status via `ConversationStore.setDeliveryStatus` |
| `GeoPresenceTracker` | 221 | Geohash presence participants |
| `GeoChannelCoordinator` | 193 | Channel selection + sampling Combine wiring |
| `ChatNostrCoordinator` | 144 | Umbrella over the Nostr component contexts |
| `ChatComposerCoordinator` | 153 | Composer/autocomplete |
| `ChatFavoriteTogglePolicy` / `ChatUnreadStateResolver` / `ChatBluetoothAlertPolicy` / `ChatMediaPreparation` | 67/43/35/57 | Pure policy structs |
| `PublicMessagePipeline` | 160 | ~80 ms batched flush of public messages into the store |
| `MessageRateLimiter` | 153 | Per-sender / per-content token buckets |
| `MinimalDistancePalette` | 203 | Evenly-spaced peer color assignment |
| `VoiceRecordingViewModel` | 247 | Hold-to-record state machine (owned by `ContentView` as `@StateObject`) |
| Extensions: `+Nostr` (97), `+PrivateChat` (199), `+Tor` (91) | | |

---

## 6. Transport architecture

### 6.1 `Transport` core + capability protocols

`bitchat/Services/Transport.swift` defines the small core every transport genuinely implements (lifecycle, identity, snapshots, basic messaging, Noise wrappers), plus `TransportPeerSnapshot`, `MeshPingResult`, `MeshTopologyEdge`, `MeshTopologySnapshot`, `TransportEvent`, `ArchivedPublicMessage`.

`bitchat/Services/MeshTransportCapabilities.swift` defines **eleven** optional capability protocols discovered with `as?` (replacing the old ~90-requirement god-protocol):

| Protocol | Surface |
|---|---|
| `BluetoothStateReporting` | `getCurrentBluetoothState()` |
| `PanicResettingTransport` | `suspendForPanicReset`, `completePanicReset`, `resetIdentityForPanic` |
| `MeshFileTransferring` | broadcast/private file send, receipt retry, cancel, private-media send policy |
| `MeshVoiceStreaming` | `sendVoiceFrame(to:)`, `sendVoiceFrameBroadcast` |
| `MeshCourierTransporting` | `sendCourierMessage(…via couriers:)` |
| `MeshGroupMessaging` | `sendGroupInvite`, `sendGroupKeyUpdate`, `broadcastGroupMessage` |
| `MeshBoardBroadcasting` | `sendBoardPayload` |
| `MeshDiagnosing` | `sendMeshPing`, `computeMeshPath`, `currentMeshTopology` |
| `MeshVerifying` | `sendVerifyChallenge`, `sendVerifyResponse`, `sendVouchAttestations` |
| `MeshPublicArchiving` | `collectArchivedPublicMessages`, `purgeArchivedPublicMessages(from:)`, `purgeAllArchivedPublicMessages` |
| `MeshBridgingTransport` | gateway/bridge/courier wiring: capability bits, carrier packets, sealed bridge envelopes, verified-announce hook |

### 6.2 BLE stack (`bitchat/Services/BLE/`, 55 files)

`BLEService.swift` (7,825 lines) is the mesh engine. It is surrounded by ~54 extracted units, categorized:

- **Radio/link:** `BLERadioController`, `BLEConnectionScheduler`, `BLEScanDutyPolicy`, `BLEEngineScheduler`, `BLEMaintenancePolicy`, `BLERecentPeripheralCache`, `BLERedundantLinkPolicy`, `BLELinkStateStore`, `BLELinkAuthState`, `BLELinkBindings`, `BLEIngressLinkRegistry`, `BLEOutboundLinkPlanner`
- **Buffers/backpressure:** `BLEInboundWriteBuffer`, `BLEOutboundWriteBuffer`, `BLEOutboundNotificationBuffer`
- **Fragmentation:** `BLEFragmentHandler`, `BLEFragmentAssemblyBuffer`, `BLEOutboundFragmentPlanner`, `BLEOutboundFragmentTransferScheduler`
- **Relay/flood:** `BLEFanoutSelector`, `BLERouteForwardingPolicy`, `BLESourceRouteOriginationPolicy`, `BLESourceRouteFailureCache`, `BLEScheduledRelayStore`, `BLEDirectedRelaySpool`, `BLEPacketFreshnessPolicy`, `BLEIngressPacketGuard`, `BLESelfBroadcastTracker`
- **Announce/presence:** `BLEAnnounceHandler`, `BLEAnnounceHandlingPolicy`, `BLEAnnounceThrottle`, `BLESubscriptionAnnounceLimiter`, `BLEPeerEventDebouncer`, `BLEPeerPublishCoalescer`, `BLEPeerRegistry`, `BLEPeerRegistryStore`, `BLEPeerSenderDisplayName`, `BLELocalIdentityStateStore`
- **Noise:** `BLENoisePacketHandler`, `BLENoisePayloadFactory`, `BLENoiseReconnectPolicy`, `BLENoiseSessionQueues`
- **Features:** `BLEFileTransferHandler`, `BLEFileTransferPolicy`, `BLEIncomingFileStore`, `BLEPrivateMediaSessionStore`, `BLEPrivateMediaReceiptStore`, `BLEPublicMessageHandler`, `BLEPublicMessagePolicy`, `BLEMeshPingTracker`, `BLEOutboundPacketPolicy`, `BLEReceivePipeline`, `MimeType`, `BLELogRateLimiter`

Concurrency contract (`docs/BLE-ARCHITECTURE-V3.md`): engine-confined serial queue, bleQueue-confined link state, lock-backed cross-domain stores. Sync-edge order `main → engine → bleQueue` is debug-enforced by `onEngine` and grep-pinned by `BLEQueueContractTests`.

`bitchatTests/Simulation/SimulatedMesh` wires CB-free `BLEService` engines edge-to-edge with manual-scheduler time — 5 deterministic multi-node tests in ~40 ms.

### 6.3 Nostr stack (`bitchat/Nostr/`)

`NostrRelayManager` (1,946) websockets over `TorURLSession`; `NostrProtocol` (917) — BitChat's **proprietary** private-envelope construction (kinds 1059/13/14 reused, `v2:` XChaCha20-Poly1305, **not NIP-17/44/59 compatible**); `NostrIdentity`, `NostrIdentityBridge` (Noise↔Nostr key association, per-geohash ephemeral identities), `NostrPoW` (NIP-13 mining + inbound scoring), `GeoRelayDirectory` (627 — CSV of geolocated relays, refreshed over Tor), `NostrRelaySettings` (custom relays, max 8, `.onion` accepted), `Bech32`, `Base64URLCoding`, `XChaCha20Poly1305Compat`, `NostrEmbeddedBitChat`, `NostrRelayURL`.
`bitchat/Services/NostrTransport.swift` (410) implements `Transport` over it.

### 6.4 Noise stack (`bitchat/Noise/`)

`NoiseProtocol`, `NoiseSession`, `SecureNoiseSession`, `NoiseSessionManager`, `NoiseSessionState`, `NoiseSessionError`, `NoiseSecurityValidator`, `NoiseSecurityConstants`, `NoiseSecurityError`, `NoiseRateLimiter`; orchestrated by `Services/NoiseEncryptionService.swift` (1,186).

### 6.5 Store-and-forward stack

| Mechanism | Files |
|---|---|
| Sender outbox (sealed, disk, ChaChaPoly key in Keychain) | `Services/Courier/MessageOutboxStore.swift` (898), `Services/MessageRouter.swift` (808) |
| Couriers (spray-and-wait, rotating recipient tags, trust tiers) | `Services/Courier/CourierStore.swift` (629), `BitFoundation/CourierEnvelope.swift` |
| Prekeys (forward secrecy for sealed mail) | `Services/Prekeys/LocalPrekeyStore.swift` (228), `PrekeyBundleStore.swift` (216), `BitFoundation/PrekeyBundle.swift` |
| Gossip sync of public history (GCS filters) | `Sync/GossipSyncManager.swift` (797), `GCSFilter.swift`, `GossipMessageArchive.swift`, `RequestSyncManager.swift`, `SyncResponseRateLimiter.swift`, `SyncTypeFlags.swift`, `PacketIdUtil.swift`, `Models/RequestSyncPacket.swift` |
| Nostr mailboxes (24 h lookback) | `NostrRelayManager`, `Services/NostrProcessedEventStore.swift` |
| Bridge courier drops (kind-1401 over relays) | `Services/Gateway/BridgeCourierService.swift` (611), `BridgeDropDedupStore.swift` |
| Metrics | `Services/Courier/StoreAndForwardMetrics.swift` |

---

## 7. FEATURE INVENTORY

Legend for **Scope**: `M` = BLE mesh, `N` = Nostr/internet, `L` = local-only.

### 7.1 Messaging core

| # | Feature | Entry point (UI) | Implementation | Model / service |
|---|---|---|---|---|
| 1 | Public mesh chat (`#mesh`) | `ContentView` → `MessageListView` (privatePeer nil) | `ChatPublicConversationCoordinator`, `PublicMessagePipeline`, `BLEPublicMessageHandler` | `PublicChatModel`, `ConversationStore(.mesh)` | M |
| 2 | Location (geohash) public channels | `LocationChannelsSheet` → channel select; timeline same as #1 | `GeohashSubscriptionManager`, `NostrInboundPipeline`, `GeoChannelCoordinator` | `LocationChannelsModel`, `ConversationStore(.geohash)` | N |
| 3 | Private 1:1 chat (mesh) | People sheet → tap peer, or `/msg` | `ChatPrivateConversationCoordinator`, `NoiseEncryptionService` | `PrivateInboxModel`, `PrivateConversationModel` | M |
| 4 | Private 1:1 chat over Nostr (mutual favorites) | same DM screen; transport chosen automatically | `NostrTransport`, `NostrProtocol` private envelopes | `MessageRouter` | N |
| 5 | Geohash DMs (`nostr_` peer IDs) | Geohash people list → tap person | `PeerID.geoDM` prefix, geo-DM subscription | `PeerListModel.openGeohashDirectMessage` | N |
| 6 | Private groups (encrypted, creator-managed) | `GroupChatList` section; `/group create/invite/remove/leave/list` | `Services/Groups/GroupProtocol.swift` (568), `GroupStore.swift` (194), `ChatGroupCoordinator` | `GroupStore`, `PeerListModel.groupRows` | M |
| 7 | Intelligent transport selection (mesh → Nostr → queue) | transparent | `MessageRouter.canDeliverPromptly`, outbox | `MessageRouter` | M/N |
| 8 | Delivery + read receipts | `DeliveryStatusView` glyph under own messages | `ChatDeliveryCoordinator`, `Models/ReadReceipt.swift`, `NoisePayloadType.delivered/.readReceipt` | `ConversationStore.setDeliveryStatus` | M/N |
| 9 | Failed-message resend | Message context menu → "resend" | `MessageListView.isResendableFailedMessage`, `ConversationUIModel.resendFailedPrivateMessage` | | |
| 10 | Message deduplication (network + UI) | transparent | `Utils/MessageDeduplicator.swift`, `Services/MessageDeduplicationService.swift` (LRU + `ContentNormalizer`) | | |
| 11 | Public-message rate limiting | transparent | `ViewModels/MessageRateLimiter.swift` (per-sender + per-content token buckets) | | |
| 12 | Clear conversation | Triple-tap timeline → confirm; or `/clear` | `MessageListView` confirmationDialog → `ConversationUIModel.clearCurrentConversation` | `ConversationStore.clear` | L |
| 13 | Windowed timeline rendering + "jump to latest" pill | Timeline scroll | `MessageListView` (`windowCountPublic`/`windowCountPrivate`, `expandWindow`) | | L |
| 14 | Unseen-messages separator / count | Timeline | `MessageListView.unseenEligibleCount`, `rebaselinedAppendedCount` | | L |
| 15 | Copy message | Message context menu → "copy" | `MessageListView` | | L |
| 16 | Mention insertion from message | Context menu → "mention" | `MessageListView.insertMention` | | L |

### 7.2 Identity, trust, moderation

| # | Feature | Entry point | Implementation | Scope |
|---|---|---|---|---|
| 17 | Nickname (editable, validated, persisted) | Header text field | `AppChromeModel.setNickname/validateAndSaveNickname`, `Utils/String+Nickname.swift` | L |
| 18 | Three-layer identity model (peer ID / Noise static / social) | — | `Identity/IdentityModels.swift`, `SecureIdentityStateManager.swift` | L |
| 19 | Favorites (mutual-trust relationship) | Peer row star; `/fav`, `/unfav` | `Services/FavoritesPersistenceService.swift` (318), `ChatFavoriteTogglePolicy` | M/N |
| 20 | Blocking (mesh fingerprint) | Peer context menu; `/block`, `/unblock` | `SecureIdentityStateManager` + `MeshPublicArchiving.purgeArchivedPublicMessages` | L |
| 21 | Blocking (geohash Nostr pubkey) | Geohash people context menu; `/block` | `identityManager.setNostrBlocked` | L |
| 22 | QR verification (out-of-band fingerprint binding) | People sheet QR button → `VerificationSheetView` (`MyQRView` / `QRScanView`) | `Services/VerificationService.swift`, `ChatVerificationCoordinator`, `NoisePayloadType.verifyChallenge/.verifyResponse` | M |
| 23 | Transitive verification / vouching (web of trust) | "vouched" unfilled-seal badge on peer rows and fingerprint screen | `Protocols/VouchAttestation.swift` (225), `ChatVouchCoordinator`, `NoisePayloadType.vouch` | M |
| 24 | Fingerprint detail screen | Peer context menu → "fingerprint" | `Views/FingerprintView.swift`, `VerificationModel.fingerprintPresentation` | L |
| 25 | Encryption status per conversation (lock / lock.slash) | DM header + legend | `PeerIdentityStore.encryptionStatuses` | L |
| 26 | Peer color assignment (evenly spaced, stable) | Peer rows + message senders | `ViewModels/MinimalDistancePalette.swift`, `Utils/Color+Peer.swift` | L |
| 27 | Display-name collision suffixing | Everywhere a nickname renders | `Utils/PeerDisplayNameResolver.swift` | L |

### 7.3 Media

| # | Feature | Entry point | Implementation | Scope |
|---|---|---|---|---|
| 28 | Image send (photo library) | Composer attach button (tap) | `ImagePickerView` / `MacImagePickerView` → `ConversationUIModel.processSelectedImage` → `ChatMediaTransferCoordinator` | M |
| 29 | Image send (camera) | Composer attach long-press (iOS) / VoiceOver action | same | M |
| 30 | Image compression / resizing | transparent | `Features/media/ImageUtils.swift` (217) | L |
| 31 | Blurred-until-tapped incoming images | Image bubble | `Views/Media/BlockRevealImageView.swift` | L |
| 32 | Progressive block-reveal during transfer | Image bubble | same (`revealProgress`) | L |
| 33 | Cancel in-flight media send | Image bubble context menu | `ConversationUIModel.cancelMediaSend`, `MeshFileTransferring.cancelTransfer` | M |
| 34 | Delete received media (with confirm) | Image bubble context menu | `ConversationUIModel.deleteMediaMessage` | L |
| 35 | Full-screen image preview + save | Tap revealed image | `Views/Image Viewers/ImagePreviewView.swift` | L |
| 36 | Voice notes (hold-to-record, release-to-send) | Composer mic button `DragGesture` | `VoiceRecordingViewModel`, `Features/voice/VoiceRecorder.swift` (AAC-LC 16 kHz mono 16 kbps) | M |
| 37 | Slide-to-cancel recording | Recording HUD cancel button | `VoiceRecordingViewModel.cancel` | L |
| 38 | Voice-note playback, one-at-a-time | Voice bubble play button | `VoiceNotePlaybackController` + `VoiceNotePlaybackCoordinator` (`ExclusivePlayback`) | L |
| 39 | Waveform rendering + cache | Voice bubble | `Features/voice/Waveform.swift`, `Views/Media/WaveformView.swift` | L |
| 40 | Live push-to-talk (walkie-talkie) — DM | Same mic hold when peer has a Noise session | `Features/voice/PTT*` (`PTTCaptureEngine`, `PTTAudioCodec`, `PTTBurstPlayer`, `PTTAudioFormat`), `Protocols/VoiceBurstPacket.swift`, `NoisePayloadType.voiceFrame` (0x08), `ChatLiveVoiceCoordinator` | M |
| 41 | Live PTT — public mesh broadcast | Same mic hold in `#mesh` | `MessageType.voiceFrame` (0x29), signed, unpadded | M |
| 42 | LIVE recording HUD + live bubble badge | Composer HUD, voice bubble | `ContentComposerView.recordingIndicator`, `Views/Media/LiveVoiceBadge.swift` | L |
| 43 | Floor courtesy (mic tints busy while someone talks) | Composer mic color | `ConversationUIModel.activeLiveVoiceTalker` | L |
| 44 | Audio session coordination (duck/route/interruption) | transparent | `Features/voice/AudioSessionCoordinator.swift` (472) | L |
| 45 | Media accept-before-disk + 1 MiB cap + storage quota | transparent | `BLEIncomingFileStore`, `BitFoundation/FileTransferLimits.swift` | M |
| 46 | Aged-media expiry sweep at launch | transparent | `AppRuntime.expireAgedMedia` → `BLEIncomingFileStore.expireAgedMedia` | L |
| 47 | Encrypted private media (Noise `0x20`) with capability pinning | transparent | `docs/PRIVATE-MEDIA-MIGRATION.md`, `BLEPrivateMediaSessionStore`, `Protocols/Packets.swift` `authenticatedPeerState` (0x21) | M |
| 48 | Legacy clear-media consent dialog (per send, never remembered) | Confirmation dialog in `ContentPeopleSheetView` | `LegacyPrivateMediaConsentRequest`, `ConversationUIModel.resolveLegacyPrivateMediaConsent` | M |
| 49 | Transfer progress bus | Progress ring on bubbles | `Services/TransferProgressManager.swift` | L |

### 7.4 Location, geohash, bridging

| # | Feature | Entry point | Implementation | Scope |
|---|---|---|---|---|
| 50 | Geohash channel levels (region 2 / province 4 / city 5 / neighborhood 6 / block 7 / building 8) | `LocationChannelsSheet` list | `Protocols/LocationChannel.swift`, `Protocols/Geohash.swift` | N |
| 51 | Channel bookmarks (+ friendly name resolution) | Header bookmark icon; sheet bookmarks section | `LocationStateManager` bookmark store, `LocationChannelsModel.toggleBookmark` | L |
| 52 | Teleport to an arbitrary geohash | `LocationChannelsSheet` custom-geohash field | `LocationChannelsModel.teleport(to:)`, `markTeleported` | N |
| 53 | Teleported-user indicator (`face.dashed`) | Geohash people list | `LocationPresenceStore.teleportedGeo` | N |
| 54 | Geohash presence heartbeats (kind 20001, 40–80 s, precision ≤ 5 only) | transparent | `Services/GeohashPresenceService.swift` (312), `docs/GeohashPresenceSpec.md` | N |
| 55 | Participant counts (`[N people]` / `[? people]`) | Sheet + header | `Services/GeohashParticipantTracker.swift`, `PeerListModel.participantCount` | N |
| 56 | Nearby-conversation hint on empty mesh timeline | `MeshEmptyStateView` | `Services/GeohashChatActivityTracker.swift` (background geohash sampling) | N |
| 57 | Daily mesh sightings tally | `MeshEmptyStateView` | `Services/MeshSightingsTracker.swift` (salted per-day hashes only) | L |
| 58 | Mesh bridge (stitch disjoint BLE islands over Nostr) | Settings toggle "mesh bridge" | `Services/Gateway/BridgeService.swift` (906) | M+N |
| 59 | Bridged-message glyph (cyan `network`) | Message rows, legend | `BitchatMessage.isBridged` | L |
| 60 | "Across the bridge" people section | People sheet | `Views/BridgePeopleList.swift`, `BridgeService.bridgedPeerCount` | N |
| 61 | Nearby-only compose scope toggle | Composer (mesh + bridge on) | `BridgeService.nearbyOnly` | L |
| 62 | Internet gateway (share connection with mesh-only peers) | Driven by bridge toggle; header globe glyph deep-links to settings | `Services/Gateway/GatewayService.swift` (468), `Protocols/NostrCarrierPacket.swift`, `MessageType.nostrCarrier` (0x28) | M+N |
| 63 | Bridge courier drops (kind-1401 sealed mail on relays) | transparent | `Services/Gateway/BridgeCourierService.swift` | N |
| 64 | Location permission flow (3 states: request / denied+settings / granted+revoke) | `AppInfoView` settings + `LocationChannelsSheet` | `LocationChannelsModel.permissionState`, `SystemSettings.location` | L |

### 7.5 Notices, board, dead drops

| # | Feature | Entry point | Implementation | Scope |
|---|---|---|---|---|
| 65 | Mesh bulletin board (signed, persistent, ≤ 7 day expiry) | Header pin icon → `NoticesView` mesh tab | `Protocols/BoardPackets.swift` (348), `Services/Board/BoardStore.swift` (368), `BoardManager.swift` (198), `MessageType.boardPost` (0x23) | M |
| 66 | Board post composer (urgency flag + expiry days picker) | `NoticesView` composer | `NoticesView`, `BoardManager` | M |
| 67 | Board tombstones (author-only delete that outruns the post) | Own post delete | `BoardTombstonePacket` | M |
| 68 | Location notes / dead drops (Nostr kind 1 + NIP-40) | `/drop <message>`; `NoticesView` geo tab composer | `Services/LocationNotesManager.swift` (460), `LocationNotesPool.swift`, `LocationNotesSettings.swift` | N |
| 69 | Unified notices (mesh board ∪ Nostr notes, deduped) | `NoticesView` geo tab | `Services/Board/UnifiedNotices.swift` | M+N |
| 70 | Unseen-pin badge + urgent-pin system lines in chat | Header pin fill / orange tint; chat system lines | `Services/Board/BoardAlertsModel.swift` | L |
| 71 | Nearby-notes counter (privacy-gated, tap-to-reveal) | Empty mesh timeline "check for notes" | `App/NearbyNotesCounter.swift` — never subscribes until an explicit notes act | N |

### 7.6 Store-and-forward, resilience

| # | Feature | Entry point | Implementation | Scope |
|---|---|---|---|---|
| 72 | Sender outbox (100 msgs/peer, 24 h, 8 attempts, sealed on disk) | Transparent; "sending"/"failed" status | `MessageOutboxStore`, `MessageRouter` | M/N |
| 73 | Courier envelopes (spray-and-wait, 3 couriers, budget 4→8) | Header `figure.walk` glyph shows carried count | `CourierStore`, `MessageType.courierEnvelope` (0x04) | M |
| 74 | Courier trust tiers + quotas (favorites 5, verified 2, pool 20/40) | transparent | `CourierDepositTier` in `CourierStore` | M |
| 75 | "Carried by a friend" delivery status | Message status glyph | `DeliveryStatus.carried` | L |
| 76 | One-time prekeys (forward secrecy for sealed mail) | transparent | `LocalPrekeyStore`, `PrekeyBundleStore`, `MessageType.prekeyBundle` (0x24) | M |
| 77 | Gossip sync of public history (GCS filters, 6 h window, 1000 packets) | transparent | `GossipSyncManager`, `GCSFilter`, `GossipMessageArchive` | M |
| 78 | Fragment resync (targeted stream-ID filter after 5 s stall) | transparent | `RequestSyncPacket` TLV 0x06, `docs/REQUEST_SYNC_MANAGER.md` | M |
| 79 | Solicited-response attribution (`IS_RSR` flag) | transparent | `RequestSyncManager`, `SyncResponseRateLimiter` | M |
| 80 | Archived echoes — "heard here earlier" (dimmed replay at launch) | Timeline top on launch | `ChatViewModelBootstrapper` (`BitchatMessage.archivedEchoIDPrefix`), `MeshPublicArchiving`, `MeshEchoSettings` watermark | M |
| 81 | Nostr mailbox re-subscribe with 24 h lookback | transparent | `NostrRelayManager`, `NostrProcessedEventStore` | N |
| 82 | Store-and-forward metrics (identity-free local counters) | Logs only | `StoreAndForwardMetrics` | L |

### 7.7 Mesh routing and diagnostics

| # | Feature | Entry point | Implementation | Scope |
|---|---|---|---|---|
| 83 | Multi-hop controlled flood (TTL 7, degree-based clamps, jitter, fanout subsetting) | transparent | `Services/RelayController.swift`, `BLEFanoutSelector` | M |
| 84 | Source-based routing v2 (≤ 4 intermediate hops, policy-gated) | transparent | `BLESourceRouteOriginationPolicy`, `BLERouteForwardingPolicy`, `docs/SOURCE_ROUTING.md` | M |
| 85 | Route-failure health cache (10 s window → 60 s flood fallback) | transparent | `BLESourceRouteFailureCache` | M |
| 86 | Topology discovery from announce `directNeighbors` TLV (two-way confirmed edges) | transparent | `Services/MeshTopologyTracker.swift` | M |
| 87 | Mesh topology map | App Info → Info pane → "network topology" | `Views/MeshTopologyView.swift`, `AppChromeModel.meshTopologyDisplayModel` | L |
| 88 | `/ping` round-trip measurement | Composer command | `BLEMeshPingTracker`, `MessageType.ping/pong` (0x26/0x27), `BitFoundation/MeshPingPayload.swift` | M |
| 89 | `/trace` estimated path | Composer command | `MeshDiagnosing.computeMeshPath` | M |
| 90 | Fragmentation / reassembly (~469 B, 128 concurrent, 30 s, 1 MiB) | transparent | `BLEFragmentHandler`, `BLEFragmentAssemblyBuffer` | M |
| 91 | LZ4 message compression | transparent | `BitFoundation/CompressionUtil.swift` | M |
| 92 | Adaptive duty cycling / battery modes | transparent | `BLEScanDutyPolicy`, `BLEConnectionScheduler`, `BLEMaintenancePolicy` | M |
| 93 | Mesh radar / sonar on empty timeline | Empty `#mesh` timeline | `Views/Components/MeshRadarView.swift` | L |
| 94 | Bluetooth-off alert with Settings deep link | Root alert + people-sheet alert | `ChatBluetoothAlertPolicy`, `SystemSettings.bluetooth` | L |

### 7.8 Commands (IRC-style)

Suggestion panel: `Views/Components/CommandSuggestionsView.swift`, catalog: `Models/CommandInfo.swift`, execution: `Services/CommandProcessor.swift` (583).

| # | Command | Aliases | Availability | Handler |
|---|---|---|---|---|
| 95 | `/msg @name [text]` | `/m` | everywhere | `handleMessage` |
| 96 | `/who` | `/w` | everywhere (mesh peers or geo participants) | `handleWho` |
| 97 | `/clear` | — | everywhere | `handleClear` |
| 98 | `/hug @name` | — | everywhere | `handleEmote` (🫂) |
| 99 | `/slap @name` | — | everywhere | `handleEmote` (🐟 "with a large trout") |
| 100 | `/block [@name]` | — | everywhere; bare form lists blocks | `handleBlock` (mesh fingerprint + Nostr pubkey) |
| 101 | `/unblock @name` | — | everywhere | `handleUnblock` |
| 102 | `/fav @name` | — | **mesh only** | `handleFavorite(add: true)` |
| 103 | `/unfav @name` | — | **mesh only** | `handleFavorite(add: false)` |
| 104 | `/group create\|invite\|remove\|leave\|list` | — | **mesh only** | `handleGroup` → `ChatGroupCoordinator` |
| 105 | `/ping @name` | — | **mesh only** | `handlePing` (async, destination captured) |
| 106 | `/trace @name` | — | **mesh only** | `handleTrace` |
| 107 | `/pay <cashu-token> [public]` | — | everywhere; hidden from suggestions in geo-public | `handlePay` (`CashuTokenDecoder`, strict decode, explicit `public` confirm) |
| 108 | `/drop <message>` | — | needs location + notes enabled | `handleDrop` (24 h NIP-40 note) |
| 109 | `/help` | — | everywhere | static `helpText` |

Unknown commands return `unknown command: … — type /help for commands`.

### 7.9 Text and rich content

| # | Feature | Entry point | Implementation |
|---|---|---|---|
| 110 | @mention parsing + highlight + mention notification | Message text | `Services/MessageFormattingEngine.swift` (468) `Patterns.mention` |
| 111 | #hashtag highlight | Message text | `Patterns.hashtag` |
| 112 | URL detection + tap-to-open | Message text | `MessageFormattingEngine`, `MessageListView.handleOpenURL` |
| 113 | Cashu token chip (redeem via wallet or `redeem.cashu.me` fallback) | Message text | `Views/Components/PaymentChipView.swift`, `Services/CashuTokenDecoder.swift` (339, incl. a bounded CBOR/TokenV4 parser) |
| 114 | Lightning invoice / `lightning:` chip | Message text | `PaymentChipView`, `Patterns.lightningScheme` |
| 115 | Nickname autocomplete (`@`-triggered, 150 ms debounce, 4 suggestions) | Composer | `Services/AutocompleteService.swift`, `ChatComposerCoordinator` |
| 116 | Command suggestions panel | Composer, on `/` | `CommandSuggestionsView` |
| 117 | Formatted-text caching keyed by theme + dark + self | transparent | `BitchatMessage.getCachedFormattedText`, `AppTheme.formatCacheVariant` |
| 118 | Input validation (injection/overflow guards) | transparent | `Utils/InputValidator.swift` |

### 7.10 Notifications

`Services/NotificationService.swift` (276), routed by `NotificationDelegate` → `AppRuntime`.

| # | Notification | Identifier prefix | Behavior |
|---|---|---|---|
| 119 | Mention in public chat | `mention-` | banner + sound |
| 120 | Private message | `private-` | tap opens the DM (`chatViewModel.startPrivateChat`); suppressed when that DM is on screen |
| 121 | Geohash activity | `geo-activity-` | deep link into the geohash; suppressed when that channel is selected |
| 122 | Network available (peer count) | `network-available` (fixed, updates in place) | |
| 123 | 👋 Wave quick action | category `chat.bitchat.category.nearby`, action `chat.bitchat.action.wave` | Background action → `ChatViewModel.sendMeshWave()`; completion handler deliberately deferred so the send isn't dropped |
| 124 | Hide message previews (privacy) | — | `Services/NotificationPrivacySettings.swift` — **on by default**; withholds body, sender, geohash |
| 125 | Streamed notification assembly | — | `Services/NotificationStreamAssembler.swift` |

### 7.11 Privacy & safety

| # | Feature | Entry point | Implementation |
|---|---|---|---|
| 126 | Panic wipe — triple-tap the `bitchat/` logo | Header logo | `AppChromeModel.panicClearAllData` (undiscoverable on purpose) |
| 127 | Panic wipe — settings button + confirmation dialog | App Info → Settings → DANGER ZONE | same |
| 128 | Panic wipe scope | — | `ChatViewModel.panicClearAllData` clears: conversations, keychain (Noise + Nostr + group keys + outbox key), identity defaults, location state & bookmarks, nickname reset to `anonNNNN`, favorites, courier store, bridge courier store, outbox, gossip archive, S&F metrics, geohash activity tracker, sightings tracker, echo watermark, notification-privacy setting, custom relays, group store, prekey bundle store, board store, share-extension staged payload, autocomplete, selected DM, geohash subscriptions, read receipts, dedup caches, Nostr relay manager + in-flight decrypts, identity associations, media files |
| 129 | Panic recovery marker + crash-replay (`panicRecoveryBlocked` gate) | — | `panicRecoveryOperations`, `networkActivationAllowed` |
| 130 | App-switcher snapshot cover (iOS) | automatic on resign-active | `App/PrivacyScreen.swift` |
| 131 | Screenshot-taken warning | System screenshot | `UIApplication.userDidTakeScreenshotNotification` → `AppChromeModel.triggerScreenshotPrivacyWarning`; suppressed while App Info is open, special-cased for the location sheet |
| 132 | Tor routing (fail-closed, default on) | Settings toggle | `localPackages/Arti/TorManager`, `TorURLSession`, `docs/TOR-INTEGRATION.md` |
| 133 | Tor bootstrap-stall detection + system message | Chat system lines | `TorBootstrapDidStall` → `ChatViewModel+Tor` |
| 134 | Network activation policy (no Tor/relays without location auth or a mutual favorite, and with no network path) | transparent | `Services/NetworkActivationService.swift`, `NetworkReachabilityMonitor.swift` |
| 135 | Message padding for Noise frames (256/512/1024/2048 buckets) | transparent | `BitFoundation/MessagePadding.swift` |
| 136 | Secure logging with sanitization | transparent | `BitLogger/SecureLogger.swift`, `String+Sanitization.swift` |
| 137 | Keychain install-lifecycle gate (fail-closed on unresolved reinstall) | transparent | `Services/KeychainManager.swift` (962), `KeychainInstallAccessGate` |
| 138 | `PrivacyInfo.xcprivacy` manifests (app + extension) | — | required-reason API declarations |

### 7.12 Ambient / discovery UI

| # | Feature | Entry point | Implementation |
|---|---|---|---|
| 139 | Empty mesh state narration + hints | Empty `#mesh` timeline | `MeshEmptyStateView` |
| 140 | Ambient footer under archived echoes | Timeline bottom | `MeshEmptyStateView(compact: true)` |
| 141 | Header people count with reachability color coding | Header | `ContentHeaderView.channelPeopleCountAndColor` |
| 142 | Carried-mail indicator (`figure.walk`) | Header | `CourierStore.$carriedCount` |
| 143 | Gateway-active indicator (`globe`, deep-links to settings) | Header | `LocationChannelsModel.gatewayEnabled` |
| 144 | Unread-DM envelope shortcut (opens most relevant DM) | Header | `AppChromeModel.openMostRelevantPrivateChat` |
| 145 | Symbols legend (13 glyphs) | App Info → Info pane | `AppInfoView.Strings.Legend.items` |

---

## 8. Settings inventory (`Views/AppInfoView.swift`)

Sheet has two panes via a segmented picker, sticky across opens (`@AppStorage("appInfo.selectedPane")`, first-ever open lands on **Info**).

### Settings pane

| Group | Control | Storage / service |
|---|---|---|
| APPEARANCE | Theme chips: **matrix** (monospace, flat) / **liquid glass** (system font, translucent chrome) | `@AppStorage(AppTheme.storageKey)`, `Utils/Theme.swift` |
| LANGUAGE | App-language menu (system default + every bundled localization, listed by endonym) + "restart to apply" note | `Utils/AppLanguageSettings.swift` (writes `AppleLanguages`) |
| VOICE | "live voice messages" toggle (gates both live send and inbound autoplay) | `Features/voice/PTTSettings.swift` |
| CONNECTIVITY | **mesh bridge** toggle + rendezvous-cell caption | `BridgeService.setEnabled` |
| CONNECTIVITY | **tor routing** toggle + red off-warning | `LocationChannelsModel.setUserTorEnabled` → `NetworkActivationService` |
| CONNECTIVITY | **private message relays** editor: 4 built-in (read-only) + up to 8 custom, add/remove, `.onion` accepted, 3 error cases | `Nostr/NostrRelaySettings.swift` |
| CONNECTIVITY | **location notes** toggle (dead drops kill switch) | `Services/LocationNotesSettings.swift` |
| CONNECTIVITY | Location permission control (request / open settings / remove access) | `LocationChannelsModel`, `SystemSettings.location` |
| PRIVACY | **hide message previews** toggle (default on) | `Services/NotificationPrivacySettings.swift` |
| DANGER ZONE | **panic wipe** button + confirmation dialog + explanation of the triple-tap shortcut | `AppChromeModel.panicClearAllData` |

### Info pane

App name + tagline; HOW TO USE paragraph (6 flowed strings); NETWORK → topology map link; FEATURES (7 rows: offline comms, encryption, extended range, mesh bridging, favorites, geohash, mentions); PRIVACY (3 rows: no tracking, ephemeral, panic); SYMBOLS legend (13 glyph rows).

All toggles share `IRCToggleStyle` (whole-row button + spelled-out on/off pill) and the `settingsCard` container.

---

## 9. Share extension

`bitchatShareExtension/` — **iOS only**, one file: `ShareViewController.swift` (191 lines), `NSExtensionPointIdentifier = com.apple.share-services`.

- Activation rule: `NSExtensionActivationSupportsText` + `NSExtensionActivationSupportsWebURLWithMaxCount = 1`.
- Extraction order: URL detected in `attributedContentText` → `NSItemProvider` URL (`public.url`, `public.file-url`) → `NSItemProvider` plain text (promoted to URL when http/https) → `attributedTitle` fallback.
- **Role: staging only.** It writes exactly one `SharedContentPayload` into the app-group `UserDefaults` via `SharedContentStore.stage(_:)` and shows `share.status.saved_for_review`. It never sends anything and holds no networking, keys, or transports.
- App group is read from `Info.plist` key `AppGroupID` = `$(APP_GROUP_ID)`, with fallback `group.chat.bitchat`.
- Store semantics (`Services/SharedContentHandoff.swift`, 212): at most one envelope; a newer share replaces an older one; bounded to prevent shared-container growth; `stage` / `pending` / `consume(id:)` / `discard(id:)` / `discardAll`.
- App side: `AppRuntime.checkForSharedContent` on launch / become-active / `bitchat://share` → `SharedContentImportModel.refresh` → an alert in `ContentView` naming the **current destination** (`#mesh`, `#geohash`, or the DM partner) with buttons `cancel` / `use in composer`. Confirming only **fills the composer** (replacing any draft) and focuses the field — it never sends. Changing destination invalidates the confirmation and requires another tap.
- The staged payload is cleared by the panic wipe (both via `AppChromeModel.onPanicWipe` and directly inside `ChatViewModel.panicClearAllData`).

---

## 10. Localization

| Item | Value |
|---|---|
| Main catalog | `bitchat/Localizable.xcstrings` (2.28 MB, format v1.1, source language `en`) |
| Keys | **409** |
| Languages | **30**: ar, bn, de, en, es, fa, fil, fr, he, hi, id, it, ja, ko, ms, ne, nl, pl, pt, pt-BR, ru, sv, ta, th, tr, uk, ur, vi, zh-Hans, zh-Hant (`fa` has 409, all others 408) |
| Share-extension catalog | `bitchatShareExtension/Localization/Localizable.xcstrings` — 5 keys × 30 languages |
| SwiftPM resource | `.process("Localizable.xcstrings")` in `Package.swift` |
| In-app override | `Utils/AppLanguageSettings.swift` — writes `AppleLanguages`; takes effect next launch; menu built from `Bundle.main.localizations`, labeled by **endonym** |
| Key-prefix distribution | `content` 126, `app_info` 89, `system` 45, `location_channels` 19, `media` 18, `fingerprint` 12, `notices` 12, `encryption` 10, `verification` 10, `location_notes` 9, `mesh_peers` 9, `geohash_people` 8, `common` 7, `location_levels` 6, `notification` 5, `topology` 5, `groups` 4, `share_import` 3, `bridge_people` 2 |
| Non-catalog strings | ~11 literal keys still uncatalogued (e.g. `"Choose an image"`, `"Images are only available in mesh chats"`, `"live %@"`, `"recording %@"`, `"save"`, `"close"`) |
| Newer keys | Carry English inline via `String(localized:defaultValue:comment:)` until the i18n pass lands them (widespread in `AppInfoView`) |
| Convention (README) | Intent-describing keys (`app_info.features.offline.title`), reuse existing keys |
| Test coverage | `bitchatTests/Localization/` |
| RTL | ar, fa, he, ur present; no explicit RTL-specific layout code found |
| Accessibility | Extensive `accessibilityLabel/Value/Hint/Action` throughout; `@ScaledMetric` for header metrics; `Utils/Font+Bitchat.swift` maps fixed sizes onto Dynamic Type styles; `dynamicTypeSize.isAccessibilitySize` branches |

---

## 11. Persistence inventory

Conversation timelines are **in-memory only**. What actually touches disk:

| Store | Location | Protection | Wiped on panic |
|---|---|---|---|
| Noise static + Ed25519 signing keys, Nostr key, group keys, prekey privates, outbox seal key | Keychain (`KeychainManager`) | after-first-unlock, this device only | yes |
| Message outbox | Application Support | ChaChaPoly-sealed + file protection | yes |
| Courier envelopes | Application Support | opaque ciphertext + file protection | yes |
| Bridge courier drop dedup | disk | file protection | yes |
| Gossip public-message archive | disk (raw signed packets) | file protection | yes |
| Board posts + tombstones | disk (raw signed packets) | file protection | yes |
| Peer prekey bundles | disk (public material) | file protection | yes |
| Nostr processed-event IDs | disk | until-first-unlock | yes |
| Group metadata (roster, name, epoch) | Application Support JSON | protected | yes |
| Media files (images, voice notes) | Application Support `files/{images,audio}/{incoming,outgoing}` | **unsealed**, platform data protection only | yes |
| Favorites | Keychain-backed (`FavoritesPersistenceService`) | | yes |
| Social identities / blocks / verified set | `SecureIdentityStateManager` | | yes |
| Location state, bookmarks, teleport set | `LocationStateManager` (defaults) | | yes |
| Sightings tally, echo watermark, relay list, notification-privacy, theme, language, PTT, notes toggle, sent read receipts | `UserDefaults` | | mostly yes (theme/language survive) |
| Shared-content handoff | app-group `UserDefaults` | | yes |
| Geo-relay directory cache | disk (`GeoRelayDirectory`) | | — |

---

## 12. Wire protocol surface (feature-parity relevant)

`MessageType` (`localPackages/BitFoundation/.../MessageType.swift`) — 16 cases:
`announce 0x01`, `message 0x02`, `leave 0x03`, `courierEnvelope 0x04`, `noiseHandshake 0x10`, `noiseEncrypted 0x11`, `fragment 0x20`, `requestSync 0x21`, `fileTransfer 0x22`, `boardPost 0x23`, `prekeyBundle 0x24`, `groupMessage 0x25`, `ping 0x26`, `pong 0x27`, `nostrCarrier 0x28`, `voiceFrame 0x29`.

`NoisePayloadType` (`bitchat/Protocols/BitchatProtocol.swift`) — 11 cases:
`privateMessage 0x01`, `readReceipt 0x02`, `delivered 0x03`, `groupInvite 0x06`, `groupKeyUpdate 0x07`, `voiceFrame 0x08`, `verifyChallenge 0x10`, `verifyResponse 0x11`, `vouch 0x12`, `privateFile 0x20` (+ prerelease alias `0x09`, decode-only), `authenticatedPeerState 0x21`.

`DeliveryStatus` — `sending`, `sent`, `carried`, `delivered(to:at:)`, `read(by:at:)`, `failed(reason:)`, `partiallyDelivered(reached:total:)`.

`PeerID` forms (`BitFoundation/PeerID.swift`) — 16-hex mesh short ID, 64-hex Noise-key ID (offline favorites), `nostr_` + 16 hex (geo DM), `group_` + 32 hex (virtual group conversation).

Packet versions: v1 (14-byte header, 2-byte length) and v2 (16-byte header, 4-byte length, optional `HAS_ROUTE` source route). Flags include `IS_RSR 0x10`, `HAS_ROUTE 0x08`.

Nostr kinds used: 1 (location notes), 1059/13/14 (BitChat private envelopes — reused numbers, **incompatible** construction), 1401 (bridge courier drops), 20000 (geohash chat), 20001 (geohash presence).

---

## 13. Design-doc index (`docs/`)

| Doc | Status per its own text |
|---|---|
| `ARCHITECTURE_V2.md` | App-layer rebuild; **landed**, with next steps listed |
| `BLE-ARCHITECTURE-V3.md` | Transport restructuring; roadmap step 1(a)(b) **done**, simulator **done**; sans-I/O engine + LinkEvent/LinkCommand extraction remain |
| `CONVERSATION-STORE-DESIGN.md` | **Migration complete (steps 1–5)** |
| `SOURCE_ROUTING.md` | **Implemented** iOS + Android, iOS origination policy-gated |
| `REQUEST_SYNC_MANAGER.md` | Implemented |
| `GeohashPresenceSpec.md` | Implemented (kind 20001) |
| `PRIVATE-MEDIA-MIGRATION.md` | Migration in progress; three inbound shapes accepted; removal gates listed |
| `PUSH-TO-TALK-DESIGN.md` | Header says "Draft for review — no code yet" but **the code exists** (`Features/voice/PTT*`, `Protocols/VoiceBurstPacket.swift`, both wire types). Treat the doc as the spec, not the status. |
| `TOR-INTEGRATION.md` | Implemented via Arti in-process; **known gap: no bridges/pluggable transports** |
| `ARTI-BINARY-PROVENANCE.md` | Binary provenance, CI-enforced |
| `VERIFYING-A-BUILD.md` | Release hash-manifest verification |
| `privacy-assessment.md` | Privacy analysis |

Repo root also carries `WHITEPAPER.md` (v2.0, 2026-07-06 — the protocol contract), `PRIVACY_POLICY.md`, `SECURITY.md`, `BRING_THE_NOISE.md`.

---

## 14. Notes for a redesign team

1. **`ConversationStore` is finished work — do not re-litigate it.** It is the single writer and holder of message state, per-conversation `ObservableObject` granularity, intent-only mutation, with performance baselines recorded in `bitchatTests/Performance/PerformanceBaselineTests.swift`. Any new UI should observe `Conversation` objects, never mirror them.

2. **The god objects are `BLEService` (7,825 lines) and `ChatViewModel` (2,427 lines), and they are different problems.** `ChatViewModel` is mid-decomposition and safe to keep shrinking coordinator-by-coordinator. `BLEService` is under an explicit plan of record (`BLE-ARCHITECTURE-V3.md`) with a documented concurrency contract that is *debug-enforced and grep-pinned in tests* — changing thread ownership there will break `BLEQueueContractTests` by design.

3. **Wire compatibility is a hard constraint with an Android peer.** `MessageType`, `NoisePayloadType`, `BitchatFilePacket` TLV, packet v1/v2 headers, signature construction (TTL zeroed, padding is signed), the peer-ID↔Noise-key binding, and courier tag construction are all cross-client contracts. `docs/PRIVATE-MEDIA-MIGRATION.md` documents three simultaneously-accepted inbound media shapes with independent removal gates.

4. **Feature count is large and long-tailed.** 145 distinct user-facing features are catalogued above across 12 groups; roughly a third of them (courier mail, prekeys, gossip sync, bridge, gateway, source routing, PTT, vouching, board, dead drops, archived echoes, sightings) are invisible in the README's feature list but each has a UI surface, persistence, and tests. The peer-list glyph vocabulary alone (13 legend entries) encodes reachability, trust, and transport semantics that a visual redesign must preserve or explicitly replace.

5. **Privacy behaviors are load-bearing UX, not polish.** Panic wipe has two entry points and clears ~25 stores; the nearby-notes counter refuses to open a building-precision relay subscription until an explicit user act; presence heartbeats are restricted to geohash precision ≤ 5; notification previews are hidden by default; Tor is fail-closed with a user-visible toggle that carries an in-UI warning; the iOS app-switcher snapshot is covered synchronously in `willResignActive`. Several of these have comments explaining *why the obvious implementation is wrong* — read them before simplifying.

Secondary but worth flagging: macOS has no background/inactive lifecycle handling and no share extension; the app is iPhone-portrait-only on iOS; ten views still reach singletons directly despite the injected-model architecture; and `docs/PUSH-TO-TALK-DESIGN.md` is stale on status (says "no code yet" — the feature ships).
