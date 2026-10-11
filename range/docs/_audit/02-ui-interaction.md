# 02 — UI & Interaction Audit

**Subject:** `bitchat` (SwiftUI, iOS + macOS) — BLE mesh + Nostr messenger
**Scope:** `bitchat/Views/`, `bitchat/Features/`, `bitchat/ViewModels/`, `bitchat/App/`, plus `bitchat/Models/CommandInfo.swift`, `bitchat/Services/CommandProcessor.swift`, `bitchat/Utils/Theme.swift`
**Method:** direct read of every SwiftUI view file (34 files under `Views/`), the app entry point, the chrome/feature models, and the command layer. Every path and symbol below was read, not inferred.

---

## 0. Headline numbers

| Metric | Count |
| --- | --- |
| SwiftUI view files under `bitchat/Views/` | 34 |
| Distinct presented surfaces (screens, sheets, covers, alerts, dialogs, context menus) | **34** (11 sheets/covers, 1 pushed destination, 4 alerts, 5 confirmation dialogs, 6 context menus, plus embedded panels) |
| Top-level "screens" a user perceives | **2** (public timeline; people-sheet stack) |
| Tab bars | 0 |
| `NavigationStack` instances | 1 (`ContentSheetViews.swift:127`) |
| `NavigationView` (deprecated) instances | 3 (`AppInfoView.swift:283`, `LocationChannelsSheet.swift:93`, `MeshTopologyView.swift:62`) |
| `navigationDestination` push destinations | 1 (`ContentSheetViews.swift:173`) |
| Slash commands accepted by the processor | **15 aliases / 13 distinct commands** |
| Gesture handlers (tap/long-press/drag/swipe/context) | 24 call sites |
| Keyboard shortcuts / `CommandMenu` (macOS) | **0** |
| Localized languages in `bitchat/Localizable.xcstrings` | 30 |
| Themes | 2 (`matrix`, `liquidGlass`) |

---

## 1. Screen, sheet, popover, alert, and modal inventory

### 1.1 Root scene

| Surface | File : line | How presented | Trigger |
| --- | --- | --- | --- |
| `BitchatApp` → `WindowGroup { ContentView() }` | `bitchat/BitchatApp.swift:32` | Scene root | App launch. macOS: `.windowStyle(.hiddenTitleBar)`, `.windowResizability(.contentSize)` |
| `ContentView` (public timeline) | `bitchat/Views/ContentView.swift:88` | Root view | Always visible; min 600×400 on macOS (`ContentView.swift:251`) |
| `ContentHeaderView` (top bar) | `bitchat/Views/ContentHeaderView.swift:3` | Inline; `safeAreaInset(edge:.top)` under liquid glass, `VStack` under matrix (`ContentView.swift:434–466`) | Always |
| `MessageListView` (public) | `bitchat/Views/MessageListView.swift:16` | Inline | Always |
| `ContentComposerView` | `bitchat/Views/ContentComposerView.swift:6` | Inline; hidden when a DM is selected (`ContentView.swift:441`, `:462`) | Always in public context |
| `PrivacyScreen` (UIKit cover) | `bitchat/App/PrivacyScreen.swift:24` | `window.addSubview` on `willResignActive` | App backgrounding; hides conversation from app-switcher snapshot |

### 1.2 Sheets & covers

| Surface | Defined at | Presented at | Presentation | Trigger |
| --- | --- | --- | --- | --- |
| **People / DM sheet** — `ContentPeopleSheetView` | `ContentSheetViews.swift:25` | `ContentView.swift:262` | `.sheet` (bound to `showSidebar \|\| selectedPrivatePeerID != nil`) | Header person-count button (`ContentHeaderView.swift:249`); unread-envelope button (`:154`); any action that opens a DM |
| ↳ **People list** — `ContentPeopleListView` | `ContentSheetViews.swift:297` | `ContentSheetViews.swift:167` | Branch inside the sheet's `NavigationStack` | Shown when `selectedPeerID == nil` |
| ↳ **Private/group chat** — `ContentPrivateChatSheetView` | `ContentSheetViews.swift:428` | `ContentSheetViews.swift:131/149` | Branch inside the same sheet | Shown when `selectedPeerID != nil` |
| ↳ **`FingerprintView`** (pushed) | `FingerprintView.swift:12` | `ContentSheetViews.swift:173` | `.navigationDestination` | Peer-row context menu "fingerprint"; double-tap on a mesh peer row; DM header name tap |
| **`AppInfoView`** (Info + Settings) | `AppInfoView.swift:7` | `ContentView.swift:319` | `.sheet` | **Single tap on the `bitchat/` logo** (`ContentHeaderView.swift:52`); gateway globe icon (`:118`) |
| ↳ **`MeshTopologyView`** | `MeshTopologyView.swift:30` | `AppInfoView.swift:277` (macOS) / `:300` (iOS) | `.sheet` | "Network → topology" row in the Info pane (`AppInfoView.swift:774`) |
| **`FingerprintView`** (root copy) | `FingerprintView.swift:12` | `ContentView.swift:326` | `.sheet`, gated on people sheet being closed | `appChromeModel.showFingerprint(for:)` while no sheet is up |
| **`ImagePickerView`** (camera / library) | `ImagePickerView.swift:14` | `ContentView.swift:336` **and** `ContentSheetViews.swift:250` | `.fullScreenCover` ×2 (iOS) | Composer camera button: tap → `.photoLibrary`, long-press → `.camera` (`ContentComposerView.swift:237–244`) |
| **`MacImagePickerView`** | `MacImagePickerView.swift:13` | `ContentView.swift:352` **and** `ContentSheetViews.swift:266` | `.sheet` ×2 (macOS) | Composer photo button |
| **`ImagePreviewView`** (full-screen viewer) | `ImagePreviewView.swift:14` | `ContentView.swift:366` | `.sheet` | Tap a revealed image, or its "open" context-menu item (`MediaMessageView.swift:109`) |
| ↳ `FileExportWrapper` (`UIDocumentPickerViewController`) | `ImagePreviewView.swift:115` | `ImagePreviewView.swift:72` | `.sheet` | "save" button in the image viewer |
| **`LocationChannelsSheet`** | `LocationChannelsSheet.swift:8` | `ContentHeaderView.swift:309` | `.sheet` | Header channel badge (`#mesh` / `#geohash`) button (`ContentHeaderView.swift:218`) |
| **`NoticesView`** | `NoticesView.swift:18` | `ContentHeaderView.swift:314` | `.sheet` | Header pin icon (`ContentHeaderView.swift:166`); "notes here" strip (`MessageListView.swift:317`) |
| **`VerificationSheetView`** (QR show/scan) | `VerificationViews.swift:285` | `ContentSheetViews.swift:414` | `.sheet` | QR button in the people sheet — **only rendered when the mesh channel is active** (`ContentSheetViews.swift:318`) |
| **`VerificationSheetView`** (second copy) | `VerificationViews.swift:285` | `ContentHeaderView.swift:291` | `.sheet` | ⚠️ **Unreachable.** `ContentView.showVerifySheet` (`ContentView.swift:109`) is never assigned `true` anywhere in the codebase (verified by grep) |

### 1.3 Alerts

| Alert | File : line | Trigger | Notes |
| --- | --- | --- | --- |
| "Recording Error" | `ContentView.swift:378` **and** `ContentSheetViews.swift:273` | `voiceRecordingVM.showAlert` | **Duplicated** with hand-rolled mutual deferral via `rootVoiceAlertBinding` / `voiceAlertBinding`. Title string is a raw English literal, not localized |
| `content.alert.bluetooth_required` | `ContentView.swift:388` **and** `ContentSheetViews.swift:283` | `appChromeModel.showBluetoothAlert` | **Duplicated**, same deferral dance (`rootBluetoothAlertBinding` / `bluetoothAlertBinding`) |
| `share_import.review` | `ContentView.swift:396` | `sharedContentImportModel.offer != nil` (share extension staged content) | "use in composer" **replaces** the current draft |
| `content.alert.screenshot` | `ContentHeaderView.swift:334` | `UIApplication.userDidTakeScreenshotNotification` → `AppRuntime.handleScreenshotCaptured()` (`AppRuntime.swift:421`) | Suppressed while the channels or app-info sheet is up |

### 1.4 Confirmation dialogs

| Dialog | File : line | Trigger |
| --- | --- | --- |
| Clear this conversation | `MessageListView.swift:194` | **Triple-tap anywhere on the message list** (`MessageListView.swift:191`) |
| Message actions (mention / DM / hug / slap / block) | `MessageListView.swift:219` | Tapping a sender's name — the name is an `AttributedString` link to `bitchat://user/<peerID>` (`ChatMessageFormatter.swift:57`), handled at `MessageListView.swift:552` |
| Legacy private-media consent ("send without end-to-end encryption?") | `ContentSheetViews.swift:189` | Sending media to a peer whose client lacks encrypted-media support |
| Panic wipe confirm | `AppInfoView.swift:561` | "panic wipe" button in Settings → DANGER ZONE |
| Delete received image | `BlockRevealImageView.swift:110` | Image context menu → delete |

### 1.5 Context menus (long-press / right-click)

| Menu | File : line | Items |
| --- | --- | --- |
| Message row | `MessageListView.swift:119` | mention, direct message, hug, slap, copy, resend (failed only), block |
| Mesh peer row | `MeshPeerList.swift:187` | direct message, favorite/unfavorite, fingerprint, block/unblock |
| Geohash person row | `GeohashPeopleList.swift:101` | block / unblock |
| Image bubble | `BlockRevealImageView.swift:103` | cancel send / open, reveal, hide, delete |
| Payment chip | `PaymentChipView.swift:143` | copy token, redeem in wallet, redeem on web |
| Fingerprint blocks (×2) | `FingerprintView.swift:106`, `:140` | copy |

### 1.6 Embedded / non-modal panels

| Panel | File : line | Presentation |
| --- | --- | --- |
| Nickname autocomplete list | `ContentComposerView.swift:30–52` | Inline `VStack` above the input, `.themedOverlayPanel()`, max 4 rows |
| Slash-command suggestions | `CommandSuggestionsView.swift:40` | Inline overlay panel above the input |
| Recording HUD | `ContentComposerView.swift:185` | Inline; red card with `TimelineView` timer |
| Jump-to-latest pill | `MessageListView.swift:455` | `.overlay(alignment: .bottomTrailing)` on the scroll view |
| "notes left here" strip | `MessageListView.swift:308` | Inline banner above the timeline (mesh only) |
| `MeshEmptyStateView` + `MeshRadarView` | `MeshEmptyStateView.swift:14`, `MeshRadarView.swift:14` | Inline empty-state / ambient footer |
| `QRScanView` / `CameraScannerView` | `VerificationViews.swift:113`, `:193` | Embedded `AVCaptureVideoPreviewLayer` inside `VerificationSheetView` |
| Share extension UI | `bitchatShareExtension/ShareViewController.swift:14` | Separate process; single `UILabel` status, auto-dismisses |

---

## 2. Navigation structure

**Shape: single-window, sheet-first, zero tabs, one push.**

```
WindowGroup (BitchatApp.swift:32)
└── ContentView ──────────────────────── public timeline (mesh OR one geohash channel)
    │   header: logo | @nickname | [globe] [walk] [envelope] [pin] [bookmark] [#channel] [people N]
    │   body:   MessageListView
    │   footer: ContentComposerView
    │
    ├─ .sheet  ContentPeopleSheetView  ← the ONLY multi-level surface
    │   └── NavigationStack
    │       ├── ContentPeopleListView       (mesh peers / bridge / groups  — OR geohash people)
    │       ├── ContentPrivateChatSheetView (DM or group chat: own header + list + composer)
    │       └── .navigationDestination → FingerprintView
    │       └── .sheet → VerificationSheetView (mesh channel only)
    │
    ├─ .sheet  AppInfoView   → segmented [info | settings]  → .sheet MeshTopologyView
    ├─ .sheet  LocationChannelsSheet   (channel switcher + teleport + bookmarks)
    ├─ .sheet  NoticesView             → segmented [geo | mesh]
    ├─ .sheet  FingerprintView         (root copy, when no people sheet)
    ├─ .sheet  ImagePreviewView        → .sheet FileExportWrapper
    └─ .fullScreenCover ImagePickerView / .sheet MacImagePickerView
```

Key characteristics and consequences:

- **There is no conversation list.** The DM inbox does not exist as a screen. Unread DMs are reachable only via (a) the header envelope icon, which calls `openMostRelevantPrivateChat()` (`ContentHeaderView.swift:154` → `AppChromeModel.swift:62`) and picks *one* chat for you, or (b) finding the person in the people sheet.
- **The DM is a sheet child of the people sheet**, not a sibling of the public timeline. Switching between "public" and "a DM" means closing and reopening a modal. `ContentPeopleSheetView` branches on `privateConversationModel.selectedPeerID` (`ContentSheetViews.swift:129`) rather than pushing, so there is no back-swipe affordance from the system.
- **Two hierarchies for the same view.** `FingerprintView` and `VerificationSheetView` each have two presentation sites with mutually exclusive guards (`ContentView.swift:327` vs `ContentSheetViews.swift:174`) — a workaround for SwiftUI's inability to present a sheet over a sheet.
- **Channel switching is modal, not navigational.** `LocationChannelsSheet` swaps `locationChannelsModel.selectedChannel`, which replaces the public timeline in place (`MessageListView.onSelectedChannelChange`, `MessageListView.swift:695`).
- **`NavigationView` (deprecated since iOS 16) is still used in 3 places** while the newest surface uses `NavigationStack` — the navigation container itself is inconsistent.
- **macOS has no menu bar commands and no keyboard shortcuts.** Zero `keyboardShortcut`/`CommandMenu` in the codebase. Every action on macOS is mouse-only.
- **Deep links exist but are internal-only:** `bitchat://user/<peerID>` and `bitchat://geohash/<gh>` handled in `MessageListView.handleOpenURL` (`:552`); `bitchat://share` handled in `AppRuntime.handleOpenURL` (`:170`). Notification taps route through `AppRuntime.handleNotificationResponse` (`:248`).

---

## 3. Gesture inventory — including hidden ones

Legend: **H** = hidden (no visual affordance, no label, not discoverable without documentation).

| # | Gesture | File : line | Effect | Hidden? | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | **Triple-tap on `bitchat/` logo** | `ContentHeaderView.swift:49` | **Panic wipe — erases all messages, keys, identity, instantly, no confirmation** | **H** | Deliberately hidden per the code comment (`:57`). Also intentionally absent from accessibility. Documented only in Settings caption (`AppInfoView.swift:123`) |
| 2 | Single tap on `bitchat/` logo | `ContentHeaderView.swift:52` | Opens `AppInfoView` (the only entry to Settings) | **H** | Looks like static branding. Code comment admits it: *"This is the only entry point to App Info, but it reads as static text"*. Mitigated for VoiceOver only (`.accessibilityAddTraits(.isButton)`, `:58`) |
| 3 | **Triple-tap on the message list** | `MessageListView.swift:191` | Opens "clear this conversation" confirmation | **H** | Whole-timeline hit area; collides conceptually with #1 |
| 4 | **Double-tap on a mesh peer row** | `MeshPeerList.swift:185` | Opens `FingerprintView` | **H** | Also in the context menu, so at least redundant |
| 5 | Single tap on a mesh peer row | `MeshPeerList.swift:186` | Opens DM | No | |
| 6 | **Long-press composer camera button** | `ContentComposerView.swift:241` | Opens **camera** (tap opens the photo library) | **H** | Mirrored for VoiceOver as a named action (`:254`) but invisible to sighted users |
| 7 | **Press-and-hold the mic button** | `ContentComposerView.swift:318` | Records/streams a voice note; release sends | **H** | Implemented as `DragGesture(minimumDistance: 0)`, not a documented long-press. Quick taps silently no-op (`VoiceRecordingViewModel.swift:184`). VoiceOver gets a tap-toggle instead (`:348`) |
| 8 | **Swipe right on the DM message list** | `ContentSheetViews.swift:540`, `:573` | Leaves the DM, returns to the people list | **H** | `highPriorityGesture`, >80pt horizontal, <60pt vertical. Deliberately scoped to the list only to avoid starving the mic gesture (comment at `:536`) |
| 9 | **Swipe horizontally on a revealed image** | `BlockRevealImageView.swift:94`, `:264` | Re-blurs ("hide") the image | **H** | `simultaneousGesture`, >40pt |
| 10 | Tap a blurred image | `BlockRevealImageView.swift:73`, `:253` | Reveals it | No | Has an explicit "tap to reveal" overlay (`:165`) |
| 11 | Tap a revealed image | same | Opens `ImagePreviewView` | Partly | No affordance that a second tap opens full-screen |
| 12 | Tap on a sender name in a message | `ChatMessageFormatter.swift:57` + `MessageListView.swift:552` | Opens the message-actions dialog | Partly | Name is a link but has no underline (unlike URLs, `:294`) |
| 13 | Tap a `#geohash` token in a message | `ChatMessageFormatter.swift:270` | Switches to that location channel | No | Underlined (`:273`) |
| 14 | Long-press / right-click a message | `MessageListView.swift:119` | Context menu | Convention | Standard iOS idiom |
| 15 | Long-press a peer / person row | `MeshPeerList.swift:187`, `GeohashPeopleList.swift:101` | Context menu | Convention | |
| 16 | Swipe left on a notice row | `NoticesView.swift:775` | Delete (own notices only) | Convention | `allowsFullSwipe: false` |
| 17 | Drag across a voice waveform | `WaveformView.swift:56` | Seek | Partly | Only interactive **while playing** (`isInteractive: playback.isPlaying`, `VoiceNoteView.swift:71`) — a paused note cannot be scrubbed |
| 18 | Tap a channel row | `LocationChannelsSheet.swift:428` | Select channel + close sheet | No | Plain `HStack` + `onTapGesture`; needed a manual `accessibilityAddTraits(.isButton)` fix (`:434`) |
| 19 | Tap a group row | `GroupChatList.swift:62` | Opens the group chat | No | |
| 20 | Tap a geohash person row | `GeohashPeopleList.swift:95` | Opens a geohash DM | No | |
| 21 | Tap the delivery-status glyph | `TextMessageView.swift:72`, `MediaMessageView.swift:61` | Reveals a plain-language delivery caption | **H** | 10pt glyph as the sole affordance |
| 22 | Tap the "check for notes left here" line | `MeshEmptyStateView.swift:150` | Unlocks the nearby-notes counter (privacy-gated reveal) | Partly | Styled as an asterisked terminal line, not a button |
| 23 | Tap the nearby-conversation hint | `MeshEmptyStateView.swift:174` | Jumps to the liveliest nearby geohash | Partly | Same asterisked-line styling |
| 24 | Tap "show more"/"show less" | `TextMessageView.swift:109` | Expand messages >2000 chars or with 512-char tokens | No | |

**Hidden-gesture count: 9 of 24** — one of which (#1) is irreversibly destructive.

---

## 4. Design / theme layer

### 4.1 Where it lives

| Concern | File | Symbols |
| --- | --- | --- |
| Theme enum + palette + modifiers | `bitchat/Utils/Theme.swift` | `AppTheme`, `ThemePalette`, `@ThemedPalette`, `ThemedRootBackground`, `GlassPanel`, `ThemedChromePanelModifier`, `ThemedInputBackgroundModifier`, `ThemedSurfaceModifier`, `ThemedOverlayPanelModifier`, `.bitchatFont(size:weight:)`, `.themedChromePanel(edge:)`, `.themedInputBackground()`, `.themedOverlayPanel()`, `.themedSheetBackground()`, `.themedSurface(opacity:)` |
| Typography scale | `bitchat/Utils/Font+Bitchat.swift` | `Font.bitchatSystem(size:weight:design:)` — maps 10 fixed pt sizes onto Dynamic Type text styles |
| Per-peer identity colors | `bitchat/ViewModels/MinimalDistancePalette.swift`, `bitchat/Utils/Color+Peer.swift` | `MinimalDistancePalette` (mesh + nostr configs), `Color(peerSeed:isDark:)` |
| Rich message text | `bitchat/ViewModels/ChatMessageFormatter.swift` | `formatMessageAsText`, `formatMessageHeader`, per-message `AttributedString` cache keyed by `theme.formatCacheVariant` |
| Shared controls | `Views/Components/SheetCloseButton.swift`, `Views/Components/IRCToggleStyle.swift`, `Views/BridgePeopleList.swift` (`PeopleSectionHeader`), `Views/AppInfoView.swift` (`SectionHeader`, `FeatureRow`) | |

### 4.2 The two themes

`AppTheme` (`Theme.swift:12`), persisted under `UserDefaults` key `"appTheme"`, selected in Settings (`AppInfoView.swift:337–358`), injected at the scene root (`BitchatApp.swift:34`).

| Token | `matrix` (default) | `liquidGlass` |
| --- | --- | --- |
| `bodyFontDesign` | `.monospaced` | `.default` |
| `background` | black / white | `systemBackground` |
| `primary` | `Color.green` (dark) / `rgb(0, 0.5, 0)` (light) | `.primary` |
| `secondary` | green @ 0.8 | `.secondary` |
| `accent` | green | `.blue` |
| `locationAccent` | green | `.green` |
| `accentBlue` | `rgb(0, 0.478, 1)` | `.blue` |
| `alertRed` | `rgb(0.75, 0.1, 0.1)` | `.red` |
| `divider` | white/black @ 0.12/0.08 | `separator` |
| Chrome | flat opaque bars + `Divider()` | floating `glassEffect(.regular)` panels (iOS/macOS 26+), `.ultraThinMaterial` fallback; content scrolls under via `safeAreaInset` |
| Root backdrop | flat `palette.background` | blue/purple radial gradients over a linear gradient (`Theme.swift:176–197`) |

### 4.3 Current visual language

**A green-on-black IRC/terminal emulator, with a full modern-glass alternate skin bolted on.**

- **Message format is literal IRC:** `<@nickname> message text [HH:MM]` — see `ChatMessageFormatter.swift:62–69` and `:319`. System lines render as `* text *` in italic gray (`:327`).
- **Empty states and hints are asterisked terminal narration:** `* text *` (`MessageListView.swift:396`, `MeshEmptyStateView.swift:212`).
- **Toggles are IRC pills**, not system switches: `IRCToggleStyle` renders an uppercase `ON`/`OFF` chip (`IRCToggleStyle.swift:16`).
- **Channels are hash-prefixed:** `#mesh`, `#<geohash>`, `#<groupname>`.
- **All-lowercase copy** throughout (`content.header.people` is `.lowercased()` at `ContentSheetViews.swift:423`; section headers are literal uppercase strings like `"CONNECTIVITY"`, `"DANGER ZONE"`).
- **No message bubbles, no avatars, no grouping.** Every message is a single flush-left run of attributed text with 12pt horizontal / 1pt vertical padding (`MessageListView.swift:165`).
- **Identity is color-coded**, not avatar-coded: `MinimalDistancePalette` assigns maximally-separated hues per peer; self is always `.orange`.
- **Sizes are hard-coded pt values passed to `bitchatSystem(size:)`**, which buckets them into Dynamic Type styles. Observed size vocabulary: 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 24, 32 — **13 distinct sizes with no named scale**.
- Semantic accent colors leak outside the palette: `Color.orange` (self / private), `Color.cyan` (bridged), `Color.purple` (Nostr), `Color.yellow` (favorite/creator), `Color.red`, `Color.teal` (vouched), and a bespoke mesh blue `Color(hue: 0.60, saturation: 0.85, brightness: 0.82)` hard-coded twice in `ContentHeaderView.swift` (`:228`, `:411`).

---

## 5. Composer (`bitchat/Views/ContentComposerView.swift`)

One composer component is used in **both** the public timeline (`ContentView.swift:495`) and the DM sheet (`ContentSheetViews.swift:549`).

| Capability | Implementation | Notes |
| --- | --- | --- |
| Text input | `TextField` with `.submitLabel(.send)`, `.autocorrectionDisabled(true)`, `.textInputAutocapitalization(.sentences)` | **Single-line only** — no `axis: .vertical`. (The *notices* composer at `NoticesView.swift:473` *is* multiline.) |
| Context-aware placeholder | `placeholderText` (`:161`) | `message #mesh — public, nearby` / `message #<gh> — public` / `message @name — private` |
| Send | Return key (`:76`, refocuses field) or `arrow.up.circle.fill` button (`:357`) | Return-key path deliberately re-focuses; button path deliberately does not (`:79`) |
| `@mention` autocomplete | `AutocompleteService.getMentionSuggestions` (`AutocompleteService.swift:48`) via `ConversationUIModel.updateAutocomplete` | 150 ms debounce timer (`:91`); top 5 computed, **top 4 rendered** (`:32`); cursor position is faked as `newValue.count` (`:92`) so mid-string completion is broken |
| Slash-command suggestions | `CommandSuggestionsView` (`:54`) | Filters `CommandInfo.all(isGeoPublic:isGeoDM:)`; keeps the usage row visible after the first space (`CommandSuggestionsView.swift:32`) |
| Photo attachment | `attachmentButton` (`:230`) | iOS: tap → library, **long-press → camera**. macOS: `NSOpenPanel`. Hidden entirely when `canSendMediaInCurrentContext == false` (geohash channels, geo DMs, groups — `ConversationUIModel.swift:224`) |
| Voice note / push-to-talk | `micButtonView` (`:308`) | Press-and-hold `DragGesture`. Live PTT vs classic note is chosen by `ConversationUIModel.makeVoiceCaptureSession()`; the idle button color does **not** distinguish them (comment at `:299`) |
| Mic/send morph | `sendOrMicButton` (`:272`) | `ZStack` cross-fade on `hasText`; falls back to send-only where media is unavailable |
| Floor courtesy | `busyTalker` (`:294`) | Mic pulses red @ 0.6 when someone else is speaking live in the public channel — advisory only |
| Bridge scope toggle | `nearbyOnlyToggle` (`:137`) | Only when `bridgeService.isEnabled` **and** mesh public **and** not in a DM. `antenna` = nearby-only, `network` (cyan) = bridged |
| Recording HUD | `recordingIndicator` (`:185`) | `TimelineView(.periodic(by: 0.05))`, `mm:ss.cc`, "live" vs "recording" label, cancel `xmark.circle` |
| DM privacy caption | `ContentSheetViews.swift:590` | Persistent orange `lock.fill` + caption strip above the DM composer; three variants (pre-encryption / encrypted / group) |
| Not supported | — | Multi-line input, drafts per conversation, edit, reply/quote, reactions, typing indicators, file (non-image) attachments, emoji picker, paste-image |

---

## 6. Message list (`bitchat/Views/MessageListView.swift`)

**Structure:** `ScrollViewReader` → `ScrollView` → `LazyVStack(alignment: .leading, spacing: 0)`. Row IDs are `"<contextKey>|<message.id>"` where `contextKey` ∈ `mesh` / `geo:<gh>` / `dm:<peerID>` (`:67`, `:734`).

**Row types** (`messageRow(for:)`, `:496`):

| Sender/content | View | Rendering |
| --- | --- | --- |
| `sender == "system"` | `systemMessageRow` (`:512`) | Italic gray `* content *` + `[timestamp]` |
| Has media attachment | `MediaMessageView` (`Media/MediaMessageView.swift:11`) | Header line + `VoiceNoteView` or `BlockRevealImageView` (capped at 280pt wide) |
| Everything else | `TextMessageView` (`Components/TextMessageView.swift:12`) | `<@name> content [HH:MM]` |

**States shown:**

| State | Where |
| --- | --- |
| Archived echo ("heard here earlier") | `opacity 0.55` + `palette.secondary @ 0.08` block tint (`:169`, `:509`) |
| Private message | Orange `lock.fill` @ 8pt prefix (`TextMessageView.swift:47`) |
| Bridged message | Cyan `network` @ 8pt prefix (`TextMessageView.swift:53`) |
| Delivery status | `DeliveryStatusView` glyphs: `circle` (sending), `checkmark` (sent), `figure.walk` (carried), `checkmark`×2 (delivered), filled `checkmark.circle.fill`×2 in blue (read), `exclamationmark.triangle` (failed), `checkmark n/m` (partial) — `DeliveryStatusView.swift:73` |
| Failure reason | Always-visible red caption; other statuses tap-to-reveal (`TextMessageView.swift:88–103`) |
| Mention of me | Bold body + orange `@name` (`ChatMessageFormatter.swift:199`, `:232`) |
| Long message | `lineLimit(30)` + "show more" for >2000 chars or >512-char tokens (`TextMessageView.swift:64`, `MessageTextHelpers.swift:27`) |
| Send progress (media) | `BlockRevealMask` 24×16 block reveal (`BlockRevealImageView.swift:279`) / waveform fill (`WaveformView.swift:42`) + cancel `xmark` |
| Incoming image | Blurred (radius 20) + `eye.slash.fill` + "tap to reveal" (`BlockRevealImageView.swift:156`) |
| Live voice | Red pulsing `LiveVoiceBadge` (`Media/LiveVoiceBadge.swift:31`) |
| Payment | `PaymentChipView` chips for Cashu (🥜) / Lightning (⚡) with decoded amount + mint host |
| Empty (mesh) | `MeshEmptyStateView` — narration + `MeshRadarView` sonar + today's sightings tally + nearby-conversation hint + notes hint |
| Empty (geohash) | Two asterisked narration lines (`:379–385`) |
| Ambient footer | `MeshEmptyStateView(compact: true)` when only archived echoes + system lines exist (`:178`, `:361`) |
| Unseen while scrolled up | Jump-to-latest pill with count (`:455`) |

**Grouping / windowing:**
- **No grouping at all** — no date separators, no sender collapsing, no "unread" divider line.
- Windowed at 300 messages initially, +200 per expansion on reaching the top (`TransportConfig.swift:355–357`), with scroll-anchor preservation (`:519`).
- Auto-scroll is throttled to 0.5 s (`TransportConfig.uiScrollThrottleSeconds`) and suppressed when the user is scrolled up (`:621`).
- Whitespace-only messages are filtered out of rendering entirely (`:76`).

---

## 7. IRC-style slash commands

Parsed in `CommandProcessor.process(_:)` (`bitchat/Services/CommandProcessor.swift:106`); dispatched from `ChatOutgoingCoordinator.sendMessage` when `content.hasPrefix("/")` (`ChatOutgoingCoordinator.swift:100`). Suggestion metadata lives in `bitchat/Models/CommandInfo.swift`.

| Command | Aliases | Arguments | Description (catalog key) | Context restrictions |
| --- | --- | --- | --- | --- |
| `/msg` | `/m` | `@nickname [message]` | send private message (`content.commands.message`) | Any |
| `/who` | `/w` | — | see who's online (`content.commands.who`) | Any (mesh peers vs geohash participants) |
| `/clear` | — | — | clear chat messages (`content.commands.clear`) | Any (clears the current conversation) |
| `/hug` | — | `@nickname` | send someone a warm hug (`content.commands.hug`) | Any |
| `/slap` | — | `@nickname` | slap someone with a trout (`content.commands.slap`) | Any |
| `/block` | — | `[@nickname]` (empty = list) | block or list blocked peers (`content.commands.block`) | Any (mesh fingerprint or Nostr pubkey) |
| `/unblock` | — | `@nickname` | unblock a peer (`content.commands.unblock`) | Any |
| `/group` | — | `create <name>` \| `invite @name` \| `remove @name` \| `leave` \| `list` | create or manage private groups (`content.commands.group`) | **Mesh only** — rejected in geo public / geo DM (`:136`) |
| `/fav` | — | `@nickname` | add to favorites (`content.commands.favorite`) | **Mesh only** (`:139`) |
| `/unfav` | — | `@nickname` | remove from favorites (`content.commands.unfavorite`) | **Mesh only** (`:142`) |
| `/ping` | — | `@nickname` | measure round-trip time to a mesh peer (`content.commands.ping`) | **Mesh only** (`:145`); async result routed to the originating conversation via `CommandOutputDestination` |
| `/trace` | — | `@nickname` | estimate the mesh path to a peer (`content.commands.trace`) | **Mesh only** (`:148`) |
| `/pay` | — | `<cashu-token> [public]` | send a cashu ecash token in this chat (`content.commands.pay`) | Any; **public channels require the literal `public` confirm word** (`:543`). Not *suggested* in geohash public (`CommandInfo.swift:78`) |
| `/drop` | — | `<message>` | pin a note to this place for 24h (`content.commands.drop`) | Requires location notes enabled + location authorized + a building-level geohash (`:187–201`) |
| `/help` | — | — | show available commands (`content.commands.help`) | Any; prints `CommandProcessor.helpText` (`:164`) as a system message |

Unknown commands produce `"unknown command: <cmd> — type /help for commands"` (`:157`).

Notes:
- `CommandInfo` deliberately mirrors the processor's accepted spellings (comment at `CommandInfo.swift:14`) — but **`/m` and `/w` are accepted by the processor and never suggested**.
- `AutocompleteService.getCommandSuggestions` is **commented out** (`AutocompleteService.swift:25`); command completion lives entirely in the view.
- Three context menus and the `ConversationUIModel` synthesize commands as strings rather than calling APIs: `clearCurrentConversation()` → `/clear`, `sendHug(to:)` → `/hug @x`, `sendSlap(to:)` → `/slap @x`, and one `block` fallback → `/block <name>` (`ConversationUIModel.swift:64–88`).

---

## 8. Problems a redesign must fix

### 8.1 Discoverability

| # | Problem | Evidence |
| --- | --- | --- |
| D1 | **The only entry point to Settings is a single tap on text that reads as a logo.** The code itself flags this as a known problem. | `ContentHeaderView.swift:52`, comment at `:56` |
| D2 | **A triple-tap on the same logo irreversibly wipes all data with no confirmation.** The safe action and the catastrophic action share one hit area and are distinguished only by tap count. `AppInfoView.swift:551` offers a *confirmed* panic button — so the app has both a guarded and an unguarded path to the same destruction. | `ContentHeaderView.swift:49` vs `AppInfoView.swift:561` |
| D3 | **9 of 24 gestures have no visual affordance** (§3): triple-tap-to-clear, double-tap-for-fingerprint, long-press-for-camera, hold-for-mic, swipe-to-leave-DM, swipe-to-rehide-image, tap-delivery-glyph. Six of them are documented only inside the Info pane's `HOW TO USE` paragraph, which is itself behind D1. | `AppInfoView.swift:236–247` |
| D4 | **Quick taps on the mic silently do nothing.** The failure is logged, never surfaced. | `VoiceRecordingViewModel.swift:184` |
| D5 | **19 `.help()` tooltips carry information that only exists on macOS** (on iOS `.help` maps to an accessibility hint). Peer-status glyphs, courier mail, gateway state, bookmark state are unexplained on iOS unless VoiceOver is on. | grep: 19 `.help(` call sites |
| D6 | **`/m` and `/w` work but are never suggested**, and `/help` is the only route out of a typo. | `CommandProcessor.swift:121–123`, `:157` |

### 8.2 Navigation

| # | Problem | Evidence |
| --- | --- | --- |
| N1 | **No conversation list / inbox.** There is no screen that shows "your DMs". The header envelope opens *one* algorithmically chosen chat; everything else requires locating a human in the people sheet. Group chats hide in a third section of that same sheet. | `AppChromeModel.swift:62`, `ContentSheetViews.swift:396` |
| N2 | **DMs live inside a modal sheet nested under the people sheet**, so leaving a conversation means dismissing a modal, and the only "back" affordances are a custom chevron (`ContentSheetViews.swift:464`), a custom X (`:506`), and a hidden right-swipe (`:573`) — three controls doing overlapping jobs in one 44pt bar. | `ContentSheetViews.swift:456–520` |
| N3 | **Sheet-over-sheet is worked around by duplicating every conflicting surface.** `FingerprintView`, `VerificationSheetView`, the image pickers, the Bluetooth alert, and the recording alert each exist twice with mutually exclusive `isPresented` guards and hand-written deferral bindings. `ContentRootModalPresentationState` (`ContentView.swift:17`) and `ContentPeopleSheetModalPresentationState` (`ContentSheetViews.swift:9`) exist solely to arbitrate this. | `ContentView.swift:143–221`, `ContentSheetViews.swift:56–123` |
| N4 | **One `VerificationSheetView` presentation site is dead code.** `ContentView.showVerifySheet` is never set `true`. | `ContentView.swift:109` vs `ContentHeaderView.swift:291` |
| N5 | **Channel identity is only in a 14pt header badge.** After switching via `LocationChannelsSheet` the whole timeline is replaced with no transition, no title, and no confirmation of where you now are. | `ContentHeaderView.swift:218–246`, `MessageListView.swift:695` |
| N6 | **macOS is a phone app in a window:** no menu commands, no keyboard shortcuts, no split view, minimum 600×400, and DMs still open as sheets. | `BitchatApp.swift:65`, zero `keyboardShortcut` |

### 8.3 Consistency

| # | Problem | Evidence |
| --- | --- | --- |
| C1 | **Three navigation containers coexist:** `NavigationStack` (people sheet), deprecated `NavigationView` (app info, channels, topology), and raw `VStack` + hand-built header bars (notices, fingerprint, verification, DM). Each header therefore has a different close control, title size, and padding. | `ContentSheetViews.swift:127`, `AppInfoView.swift:283`, `NoticesView.swift:351`, `VerificationViews.swift:296` |
| C2 | **Two toggle styles.** `IRCToggleStyle` in App Info settings vs `.toggleStyle(.switch)` in `NoticesView.swift:441` and `:497` — the same on/off semantics rendered two ways in two sheets. |
| C3 | **Two composers with different capabilities.** The chat composer is single-line; the notices composer is `axis: .vertical` with `lineLimit(3, reservesSpace: true)`. | `ContentComposerView.swift:61` vs `NoticesView.swift:473` |
| C4 | **Settings are split across three surfaces.** App Info → Settings holds theme/language/voice/bridge/tor/relays/location/privacy/panic; `LocationChannelsSheet` holds channel selection + teleport + bookmarks; `NoticesView` holds a *second* location-notes toggle (`NoticesView.swift:423`) duplicating the App Info one (`AppInfoView.swift:468`). The bookmark toggle exists both in the header (`ContentHeaderView.swift:203`) and per-row in the channels sheet (`:198`). |
| C5 | **Un-tokenized color and type.** 13 hard-coded font sizes, a bespoke mesh blue duplicated at `ContentHeaderView.swift:228` and `:411`, and 7 semantic colors (`orange`, `cyan`, `purple`, `yellow`, `red`, `teal`, `white`) used directly outside `ThemePalette`. The palette has 8 tokens; the app needs roughly twice that. |
| C6 | **Two unlocalized user-facing strings.** The alert title `"Recording Error"` (`ContentView.swift:378`, `ContentSheetViews.swift:273`) and every `VoiceRecordingViewModel.State.alertMessage` case (`VoiceRecordingViewModel.swift:29–35`) are raw English in a 30-language app. `MacImagePickerView.swift:19–39` is entirely unlocalized. |
| C7 | **Peer rows differ per section.** Mesh rows carry a status glyph, encryption badge, favorite star, and 4-item context menu; bridge rows are name-only with no actions (`BridgePeopleList.swift:61`); geohash rows have a location glyph and a block-only menu; group rows have a crown and no menu. Four row grammars in one scroll view. |
| C8 | **Mention autocomplete assumes the cursor is at the end of the string** (`cursorPosition = newValue.count`, `ContentComposerView.swift:92`), so editing mid-message produces wrong suggestions. |
| C9 | **Waveform scrubbing only works while audio is playing** (`VoiceNoteView.swift:71`), which is the opposite of every media player convention. |

### 8.4 Top 5 for a redesign

1. **No inbox.** Add a real conversation list; stop routing DMs through a modal nested in a people list, and stop letting an algorithm pick which unread chat opens.
2. **Destructive hidden gesture on the primary chrome element.** Triple-tap-to-wipe shares a hit target with the only Settings entry point. Both need to change.
3. **Nine undiscoverable gestures**, several of them the *only* way to reach a feature (camera, voice notes, clear-chat, fingerprint). Every one needs a visible affordance.
4. **Sheet-stacking architecture** that forces duplicated views, duplicated alerts, and hand-written presentation arbitration — replace with a real navigation model (`NavigationSplitView`/`NavigationStack` with typed paths).
5. **No design tokens.** 13 ad-hoc font sizes, 8 palette entries doing the work of ~16, and raw system colors scattered across 20 view files — the theme layer cannot express what the UI already does.

---

## 9. Strengths worth preserving

| # | Strength | Evidence |
| --- | --- | --- |
| S1 | **A genuine, coherent visual identity.** The IRC/terminal language (`<@name> msg [HH:MM]`, `* system *`, `#channel`, `ON`/`OFF` pills, monospace green) is consistent, distinctive, and matches the product's politics. This is an asset, not debt. | `ChatMessageFormatter.swift:62`, `IRCToggleStyle.swift` |
| S2 | **A real theme abstraction already exists and is used everywhere.** `@ThemedPalette` + `.bitchatFont()` + the `themed*` modifiers are applied consistently across all 34 view files; two complete themes ship, including a genuine Liquid Glass layout variant with `safeAreaInset` full-bleed scrolling. Extending it is cheaper than building it. | `Utils/Theme.swift` |
| S3 | **Accessibility is unusually thorough for an indie app.** Icon-only rows get `accessibilityElement(children: .ignore)` + a composed sentence (`MeshPeerList.swift:247`, `GeohashPeopleList.swift:159`); hidden gestures are mirrored as named actions (long-press camera → `ContentComposerView.swift:254`, hold-mic → `:348`, swipe-delete → `NoticesView.swift:770`); non-button rows get explicit `.isButton` traits; `MeshRadarView` respects `accessibilityReduceMotion`. |
| S4 | **Dynamic Type is real, not decorative.** `Font.bitchatSystem` maps every pt size onto a preferred text style; `@ScaledMetric` drives header height (`ContentView.swift:117`); header line limits adapt at accessibility sizes (`ContentHeaderView.swift:353`). |
| S5 | **Excellent empty and ambient states.** `MeshEmptyStateView` turns a dead timeline into a live surface: sonar sweep, daily sightings tally, one-tap jump to the liveliest nearby geohash, and a privacy-gated tap-to-reveal notes counter. It also survives as a compact footer once archived echoes exist. | `MeshEmptyStateView.swift:76–130`, `MessageListView.swift:178` |
| S6 | **Privacy is surfaced in the UI, not just the protocol.** Persistent DM privacy caption that only claims E2E once the session actually secures (`ContentSheetViews.swift:610`); incoming images blurred by default; screenshot warning; app-switcher `PrivacyScreen`; share-extension content requires a destination-labelled confirmation before touching the composer (`ContentView.swift:396`); public `/pay` requires an explicit `public` keyword. |
| S7 | **Delivery status is legible without color** (filled vs outline glyphs), explained in plain language on tap on iOS and via tooltip on macOS, with failure reasons always visible and one-tap resend. | `DeliveryStatusView.swift:99`, `TextMessageView.swift:88`, `MessageListView.swift:429` |
| S8 | **Message-list engineering is solid.** Windowed rendering with anchor-preserving expansion, throttled autoscroll, per-context unseen baselines that survive channel switches, `AttributedString` caching keyed by theme + color scheme + self-ness. | `MessageListView.swift:413`, `:519`, `ChatMessageFormatter.swift:44` |
| S9 | **Context-aware affordances.** Media buttons vanish where media cannot be sent; the bridge scope toggle only appears where it means something; commands are filtered per channel so the suggestion list never teaches a dead end; the DM composer placeholder always names the recipient. | `ConversationUIModel.swift:224`, `ContentComposerView.swift:125`, `CommandInfo.swift:72` |
| S10 | **30 languages, with a genuine in-app language override** including endonym labels and an honest restart note. | `AppLanguageSettings.swift`, `AppInfoView.swift:362–404` |
| S11 | **Design decisions are documented in the code.** Nearly every non-obvious choice carries a comment explaining the failure it prevents (gesture starvation, optical glyph centering, cache-key variants, presentation deferral). A redesign can read the constraints instead of rediscovering them. |

---

*Audit produced by reading source only; no build or runtime observation was performed.*
