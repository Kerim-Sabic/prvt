# 03 — Protocol & Persistence Risk Register ("Do Not Change")

**Audit target:** `C:\Users\kerim\prvt\range` (bitchat, `MARKETING_VERSION = 1.7.1`)
**Scope:** everything that must survive the bitchat → range rebrand + UI redesign without breaking wire compatibility, encryption, or existing users' data.
**Method:** direct source read of `bitchat/Protocols/`, `bitchat/Noise/`, `bitchat/Nostr/`, `bitchat/Identity/`, `bitchat/Services/`, `bitchat/Sync/`, `bitchat/Models/`, `localPackages/BitFoundation/`, `localPackages/Arti/`, `localPackages/BitLogger/`, `Configs/`, `bitchat.xcodeproj/`, `bitchatShareExtension/`.

---

## 0. Executive summary

| Metric | Value |
| --- | --- |
| Total case-insensitive `bitchat` occurrences (source + config, excluding `bitchatTests/`) | **1160** |
| Swift **string-literal** lines containing `bitchat` | **107** |
| **Load-bearing** `bitchat` literal sites (protocol / storage / compat) | **64** |
| Distinct load-bearing literal *values* | **42** |
| Noise handshake pattern (interactive) | **`Noise_XX_25519_ChaChaPoly_SHA256`** |
| Noise handshake pattern (one-way courier / prekey envelopes) | **`Noise_X_25519_ChaChaPoly_SHA256`** |
| Mesh wire protocol versions in use | **v1 (14-byte header)** and **v2 (16-byte header)** |
| BLE mainnet service UUID | `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C` |

> **The single most dangerous operation in this rebrand is a project-wide find-replace of `bitchat` → `range`.** 14 of the load-bearing literals are cryptographic domain-separation strings hashed into signatures and Noise prologues. Changing any one of them silently makes this build unable to verify or decrypt anything produced by the deployed iOS/Android fleet — with **no error message that names the cause**. See §10.

---

## 1. Wire / protocol byte formats

### 1.1 Outer packet header — `BinaryProtocol`

| Item | Exact value | File:line | Why it must not change | Risk if changed |
| --- | --- | --- | --- | --- |
| v1 header size | `v1HeaderSize = 14` | `localPackages/BitFoundation/Sources/BitFoundation/BinaryProtocol.swift:100` | Fixed offset every peer parses | Total mesh incompat; all packets rejected |
| v2 header size | `v2HeaderSize = 16` | `BinaryProtocol.swift:101` | v2 uses a 4-byte length field | Source-routed packets undecodable |
| Sender ID size | `senderIDSize = 8` | `BinaryProtocol.swift:102` | 8-byte routing ID in every header | Routing + dedup break |
| Recipient ID size | `recipientIDSize = 8` | `BinaryProtocol.swift:103` | — | Directed delivery breaks |
| Signature size | `signatureSize = 64` | `BinaryProtocol.swift:104` | Ed25519 sig length | Signature parse failure |
| Flags byte offset | `Offsets.flags = 11` | `BinaryProtocol.swift:108` | version(1)+type(1)+ttl(1)+timestamp(8) | Misparsed flags |
| Header layout | `[version:1][type:1][ttl:1][timestamp:8 BE][flags:1][payloadLen:2 (v1) or 4 (v2)]` then `senderID:8`, `recipientID:8?`, `routeCount:1 + hops*8?`, `origSize:2/4?`, `payload`, `signature:64?` | `BinaryProtocol.swift:181-246` (encode), `:301-406` (decode) | Byte-exact cross-platform contract with the Android client | Complete protocol break |
| Endianness | Network byte order (big-endian) for all multi-byte fields | `BinaryProtocol.swift:185-207` | — | Garbage timestamps/lengths |
| Accepted versions | `guard version == 1 \|\| version == 2` | `BinaryProtocol.swift:134`, `:301` | Anything else is dropped | Peers drop your packets |
| Default packet version | `version: UInt8 = 1` | `localPackages/.../BitchatPacket.swift:27` | Default emitted version | Older peers can't parse v2 |
| Signing preimage | signature computed over encoding with `signature: nil`, `ttl: 0`, `isRSR: false` | `BitchatPacket.swift:51-67` | TTL/RSR mutate during relay | Every relayed packet fails verification |

### 1.2 Flag bits

| Flag | Exact value | File:line |
| --- | --- | --- |
| `hasRecipient` | `0x01` | `BinaryProtocol.swift:124` |
| `hasSignature` | `0x02` | `BinaryProtocol.swift:125` |
| `isCompressed` | `0x04` | `BinaryProtocol.swift:126` |
| `hasRoute` (v2+ only) | `0x08` | `BinaryProtocol.swift:127` |
| `isRSR` | `0x10` | `BinaryProtocol.swift:128` |

**Risk if changed:** flags are read bitwise by every peer; a reassignment causes silent misparse (e.g. payload read as signature), not a clean rejection.

### 1.3 `MessageType` — outer wire type identifiers (1 byte)

File: `localPackages/BitFoundation/Sources/BitFoundation/MessageType.swift:12-41`

| Case | Exact value | Line |
| --- | --- | --- |
| `announce` | `0x01` | 13 |
| `message` | `0x02` | 14 |
| `leave` | `0x03` | 15 |
| `courierEnvelope` | `0x04` | 16 |
| `noiseHandshake` | `0x10` | 21 |
| `noiseEncrypted` | `0x11` | 22 |
| `fragment` | `0x20` | 25 |
| `requestSync` | `0x21` | 17 |
| `fileTransfer` | `0x22` | 26 |
| `boardPost` | `0x23` | 27 |
| `prekeyBundle` | `0x24` | 28 |
| `groupMessage` | `0x25` | 29 |
| `ping` | `0x26` | 32 |
| `pong` | `0x27` | 33 |
| `nostrCarrier` | `0x28` | 37 |
| `voiceFrame` | `0x29` | 41 |

**Why:** consolidated from 24 historical types; Android uses the same table. **Risk:** a renumber makes every message type mean something else to deployed peers.

### 1.4 `NoisePayloadType` — inner (encrypted) type byte

File: `bitchat/Protocols/BitchatProtocol.swift:76-113`

| Case | Exact value | Line | Note |
| --- | --- | --- | --- |
| `privateMessage` | `0x01` | 78 | |
| `readReceipt` | `0x02` | 79 | |
| `delivered` | `0x03` | 80 | |
| — reserved — | `0x04`, `0x05` | 81 | reserved by other features |
| `groupInvite` | `0x06` | 82 | |
| `groupKeyUpdate` | `0x07` | 83 | |
| `voiceFrame` | `0x08` | 85 | |
| `verifyChallenge` | `0x10` | 96 | |
| `verifyResponse` | `0x11` | 97 | |
| `vouch` | `0x12` | 99 | |
| `privateFile` | `0x20` | 89 | **value already deployed by the Android client** |
| `authenticatedPeerState` | `0x21` | 94 | |
| `prereleasePrivateFileRawValue` | `0x09` | 104 | **accept-only compat alias**, never emitted |

**Risk if changed:** private messages, receipts, group state, and media all ride this one byte. `0x09` must remain *decodable* (`NoisePayloadType.decoded(rawValue:)` canonicalizes it to `.privateFile`) until prerelease builds age out.

### 1.5 TLV sub-formats (all must stay byte-stable)

| Packet | Tag table | File:line |
| --- | --- | --- |
| `AnnouncementPacket` | `nickname 0x01`, `noisePublicKey 0x02`, `signingPublicKey 0x03`, `directNeighbors 0x04`, `capabilities 0x05`, `bridgeGeohash 0x06` | `bitchat/Protocols/Packets.swift:34-39` |
| `AuthenticatedPeerStatePacket` | `version byte = 0x01`; `capabilities 0x01`, `signingPublicKey 0x02` | `Packets.swift:173`, `:180-182` |
| `RequestSyncPacket` | `P 0x01`, `M 0x02`, `data 0x03`, `types 0x04`, `sinceTimestamp 0x05`, `fragmentIdFilter 0x06` | `bitchat/Models/RequestSyncPacket.swift:4-11` |
| `BoardPost` | `kind 0x01`, `postID 0x02`, `geohash 0x03`, `content 0x04`, `authorSigningKey 0x05`, `authorNickname 0x06` | `bitchat/Protocols/BoardPackets.swift:42-48` |
| `VouchAttestation` | `voucheeFingerprint 0x01` (32B), `voucheeSigningKey 0x02` (32B), `timestamp 0x03` (8B BE ms), `signature 0x04` (64B) | `bitchat/Protocols/VouchAttestation.swift:23-28` |
| `GroupStatePayload` | `groupID 0x01`, `name 0x02`, `key 0x03`, `epoch 0x04`, `roster 0x05`, `creatorFingerprint 0x06`, `signature 0x07` | `bitchat/Services/Groups/GroupProtocol.swift:207-215` |
| `PrekeyBundle` | `noiseStaticPublicKey 0x01`, `prekeys 0x02`, `generatedAt 0x03`, `signature 0x04` | `localPackages/.../PrekeyBundle.swift:52-57` |
| `CourierEnvelope` | TLV w/ recipientTag, expiry, ciphertext, copies, prekeyID (unknown TLVs skipped) | `localPackages/.../CourierEnvelope.swift:140-165` |

### 1.6 `BitchatMessage` payload flags (public chat body)

File: `localPackages/BitFoundation/Sources/BitFoundation/BitchatMessage.swift:141-148`, `:234-241`

`isRelay 0x01`, `isPrivate 0x02`, `hasOriginalSender 0x04`, `hasRecipientNickname 0x08`, `hasSenderPeerID 0x10`, `hasMentions 0x20`, `isBridged 0x40`.

`CodingKeys` (line 49-53): `id, sender, content, timestamp, isRelay, originalSender, isPrivate, recipientNickname, senderPeerID, mentions, deliveryStatus, isBridged` — **these property names are the JSON keys of persisted data. Renaming a property renames the on-disk key.**

### 1.7 Padding

| Item | Exact value | File:line | Why |
| --- | --- | --- | --- |
| Block sizes | `[256, 512, 1024, 2048]` | `localPackages/.../MessagePadding.swift:24` | Peers `unpad` by PKCS#7 verification; changing buckets changes observable sizes but stays decodable. Do not change the **scheme**. |
| Scheme | PKCS#7-style, every pad byte == pad length, max 255 | `MessagePadding.swift:27-52` | Decoder tries raw, then unpadded (`BinaryProtocol.swift:256-263`) |
| Overhead assumption | `dataSize + 16` | `MessagePadding.swift:57` | — |

### 1.8 Sync bitfield (`SyncTypeFlags`) — little-endian, trailing zeros trimmed, 1–8 bytes

File: `bitchat/Sync/SyncTypeFlags.swift:31-84`

`announce 0`, `message 1`, `leave 2`, `noiseHandshake 3`, `noiseEncrypted 4`, `fragment 5`, `requestSync 6`, `fileTransfer 7`, `boardPost 8`, `prekeyBundle 9`, `groupMessage 10`. Explicitly **never syncable**: `courierEnvelope`, `ping`, `pong`, `nostrCarrier`, `voiceFrame` (return `nil`).

**Risk:** bit↔type mapping is stated to "match the Android mapping". A reshuffle makes sync rounds request the wrong types.

### 1.9 `PeerCapabilities` bitfield (little-endian, trailing zeros dropped)

File: `localPackages/BitFoundation/Sources/BitFoundation/PeerCapabilities.swift:16-42`

`prekeys 1<<0`, `wifiBulk 1<<1`, `gateway 1<<2`, `groups 1<<3`, `board 1<<4`, `vouch 1<<5`, `meshDiagnostics 1<<6`, `bridge 1<<7`, `privateMedia 1<<8`, `privateMediaReceipts 1<<9`, `nonDestructiveNoiseReplacement 1<<10` (**reserved — must stay decodable, never reused**).

### 1.10 BLE transport identifiers

| Item | Exact value | File:line | Risk if changed |
| --- | --- | --- | --- |
| Service UUID (Release) | `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C` | `bitchat/Services/BLE/BLEService.swift:189` | **App becomes invisible to every existing peer.** Total mesh partition. |
| Service UUID (Debug/testnet) | `F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A` | `BLEService.swift:187` | Debug builds lose testnet |
| Characteristic UUID | `A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D` | `BLEService.swift:191` | Connections establish then never exchange data |
| Central restoration ID | `"chat.bitchat.ble.central"` | `BLEService.swift:192` | **Background BLE state restoration breaks**; wake-on-proximity relaunch stops working for existing installs |
| Peripheral restoration ID | `"chat.bitchat.ble.peripheral"` | `BLEService.swift:193` | Same |
| Default fragment size | `469` | `bitchat/Services/TransportConfig.swift:7` | Fragment reassembly mismatch |
| Max MTU | `512` | `BLEService.swift:197` | — |
| Default TTL | `messageTTLDefault: UInt8 = 7` | `TransportConfig.swift:8` | Mesh reach changes |

---

## 2. Encryption constructions

### 2.1 Noise — exact patterns

| Item | Exact value | File:line |
| --- | --- | --- |
| **Interactive pattern** | **`Noise_XX_25519_ChaChaPoly_SHA256`** | composed at `bitchat/Noise/NoiseProtocol.swift:115-124` (`NoiseProtocolName.fullName` = `"Noise_\(pattern)_\(dh)_\(cipher)_\(hash)"`), pattern name at `:905` |
| DH | `25519` (Curve25519 / X25519) | `NoiseProtocol.swift:117` |
| Cipher | `ChaChaPoly` (ChaCha20-Poly1305 AEAD) | `NoiseProtocol.swift:118` |
| Hash | `SHA256` | `NoiseProtocol.swift:119` |
| XX message patterns | `[[.e], [.e,.ee,.s,.es], [.s,.se]]` | `NoiseProtocol.swift:914-919` |
| **One-way pattern** (courier + prekey envelopes) | **`Noise_X_25519_ChaChaPoly_SHA256`** — `[[.e,.es,.s,.ss]]` | `NoiseProtocol.swift:930-934`; used at `bitchat/Services/NoiseEncryptionService.swift:448-456`, `:500-511` |
| Also implemented (currently unused paths) | `IK`, `NK` | `NoiseProtocol.swift:920-929` |

**The protocol name string is hashed directly into the symmetric state** (`NoiseSymmetricState.init(protocolName:)`, `NoiseProtocol.swift:419-430`: if ≤32 bytes it is zero-padded to 32 and used as the initial `h`/`ck`, else SHA-256'd). **Any change to the pattern/DH/cipher/hash names makes every handshake with a deployed peer fail with an opaque decrypt error.**

### 2.2 Key derivation & cipher details

| Item | Exact value | File:line |
| --- | --- | --- |
| HKDF | `HMAC<SHA256>`, chaining-key as PRK, counter bytes `0x01..0x0N` | `NoiseProtocol.swift:516-532` |
| `mixHash` | `h = SHA256(h ‖ data)` | `NoiseProtocol.swift:439-441` |
| Transport nonce (on-wire) | 4 bytes, **big-endian**, prepended: `<nonce4><ciphertext><tag>` | `NoiseProtocol.swift:225-258`, `:283-290` |
| ChaChaPoly nonce (internal) | 12 bytes; counter written **little-endian** into bytes `4..<12` | `NoiseProtocol.swift:274-277`, `:345-348` |
| Nonce ceiling | `nonce <= UInt32.max - 1` | `NoiseProtocol.swift:269` |
| Replay window | 1024 messages / 128 bytes bitmap | `NoiseProtocol.swift:134-136` |
| Cipher split | initiator sends `c1`/receives `c2`; responder inverted | `NoiseProtocol.swift:869-874` |
| Rekey check interval | `60.0` s | `NoiseEncryptionService.swift:181` |
| Low-order point blocklist | 8 hardcoded Curve25519 points, constant-time compared | `NoiseProtocol.swift:997-1028` |

### 2.3 Nostr private-envelope cipher (BitChat-proprietary, **not** NIP-44)

| Item | Exact value | File:line | Why it must not change |
| --- | --- | --- | --- |
| Content prefix | `"v2:"` | `bitchat/Nostr/NostrProtocol.swift:626`, `:637` | Deployed decoders require this exact prefix |
| Layout | `base64url(nonce24 ‖ ciphertext ‖ tag16)` | `NostrProtocol.swift:621-626` | — |
| Cipher | XChaCha20-Poly1305 (24-byte nonce) | `NostrProtocol.swift:619`, `bitchat/Nostr/XChaCha20Poly1305Compat.swift` | — |
| KDF | `HKDF<SHA256>.deriveKey(ikm: ECDH_secret, salt: Data(), info: Data("nip44-v2".utf8), outputByteCount: 32)` | `NostrProtocol.swift:908-916` | **The `"nip44-v2"` info string is a historical label kept for wire compat.** It is *not* NIP-44 semantics. Changing it breaks every existing DM peer. |
| ECDH | secp256k1, compressed pubkey, x-only keys tried with `0x02` then `0x03` parity | `NostrProtocol.swift:686-746`, `:664-676` | — |
| Envelope ciphertext cap | `64 * 1024` | `NostrProtocol.swift:43` | — |
| Group message cipher | ChaCha20-Poly1305, 32-byte symmetric group key per epoch | `bitchat/Services/Groups/GroupProtocol.swift` | — |
| Identity cache at rest | AES-GCM, 256-bit key | `bitchat/Identity/SecureIdentityStateManager.swift:32-36`, `:217` | — |

---

## 3. Identity derivation & storage

| Item | Exact value / mechanism | File:line | Why it must not change |
| --- | --- | --- | --- |
| Device identity (mesh) | Curve25519 **KeyAgreement** private key, `Curve25519.KeyAgreement.PrivateKey()` | `bitchat/Services/NoiseEncryptionService.swift:366` | The long-lived Noise static key **is** the device identity |
| Keychain account | `"noiseStaticKey"` (stored as `identity_noiseStaticKey`) | `NoiseEncryptionService.swift:249`, `:370`; prefix applied at `bitchat/Services/KeychainManager.swift:367`, `:375`, `:384` | Rename ⇒ every user gets a brand-new identity, loses all favorites/verifications |
| Signing key | Curve25519 **Signing** (Ed25519) private key | `NoiseEncryptionService.swift:390` | — |
| Keychain account | `"ed25519SigningKey"` (→ `identity_ed25519SigningKey`) | `NoiseEncryptionService.swift:291`, `:394` | Rename ⇒ all announce/board/vouch signatures re-key |
| Fingerprint | `SHA256(noiseStaticPublicKey).hexEncodedString()` (64 hex) | `localPackages/.../Data+SHA256.swift:14-17`; used `NoiseEncryptionService.swift:426`, `:1029` | Displayed to users for OOB verification; changing the hash invalidates every verified contact |
| 8-byte routing peer ID | `PeerID(publicKey:)` = **first 16 hex chars of the fingerprint** | `localPackages/.../PeerID.swift:130-134` | The 8-byte sender ID in every packet header. Changing derivation partitions the mesh. |
| Peer ID prefixes | `"" `, `"mesh:"`, `"name:"`, `"noise:"`, `"nostr_"`, `"nostr:"`, `"group_"`, `"bridge:"` | `PeerID.swift:24-44` | Persisted in conversation IDs and JSON; renaming orphans every stored chat |
| Prefix lengths | geoDM = first **16** hex of pubkey; geoChat = first **8** | `PeerID.swift:15-17`, `:64-72` | Cross-client peer matching |
| Nostr identity | secp256k1 Schnorr; `npub` = Bech32 hrp `"npub"` | `bitchat/Nostr/NostrIdentity.swift:18-29` | — |
| Nostr device seed | 32 random bytes, keychain account `"nostr-device-seed"` | `bitchat/Nostr/NostrIdentityBridge.swift:9`, `:79-86` | Root of all per-geohash identities |
| Per-geohash identity | `HMAC-SHA256(deviceSeed, geohash ‖ UInt32BE(iteration))`, iterations `0..<10`, fallback `SHA256(seed ‖ geohash)` | `NostrIdentityBridge.swift:99-144` | Deterministic & unlinkable; change ⇒ every geohash pubkey rotates, breaking presence + geo-DM threads |
| Bridge rendezvous identity | `deriveIdentity(forGeohash: "bridge\|" + cell)` — literal `"bridge\|"` separator | `NostrIdentityBridge.swift:92-94` | Keeps bridge identity unlinkable from geohash-chat identity for the same cell |
| Current Nostr identity | keychain account `"nostr-current-identity"`, JSON-encoded `NostrIdentity` (`privateKey`, `publicKey`, `npub`) | `NostrIdentityBridge.swift:8`, `:25-36` | Property names are the JSON keys |
| Noise↔Nostr map key | `"nostr-noise-\(noisePublicKey.base64EncodedString())"` | `NostrIdentityBridge.swift:42` | — |
| Keychain accessibility | `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` | `KeychainManager.swift:116` | Mesh must run while locked; `ThisDeviceOnly` prevents identity migrating via backup |

---

## 4. Keychain service / account identifiers & access groups

### 4.1 Primary service

| Item | Exact value | File:line |
| --- | --- | --- |
| Primary service | `BitchatApp.bundleID` → resolves to **`chat.bitchat`** | `bitchat/Services/KeychainManager.swift:90`; `bitchat/BitchatApp.swift:14` |
| Access group used in queries | `"group.\(BitchatApp.bundleID)"` → **`group.chat.bitchat`** | `KeychainManager.swift:91`; `BitchatApp.swift:15` |
| Item label attribute | `"bitchat-\(key)"` | `KeychainManager.swift:418`, `:578` |
| iOS fallback | `-34018` ⇒ retry the same query **without** the access group | `KeychainManager.swift:430-437`, `:489-494`, `:629-634`, `:657-663` |

### 4.2 Every declared application-owned service (`additionalApplicationOwnedServices`)

`bitchat/Services/KeychainManager.swift:98-109` — **this list is the authority for panic wipe and for the accessibility migration.** Any service dropped here is silently missed by both.

| Exact value | Line | Current use |
| --- | --- | --- |
| `"chat.bitchat.nostr"` | 99 | **Live** — `NostrIdentityBridge.swift:7` |
| `"chat.bitchat.favorites"` | 100 | **Live** — `FavoritesPersistenceService.swift:29` |
| `"chat.bitchat.outbox"` | 101 | **Live** — `MessageOutboxStore.swift:63` |
| `"com.bitchat.passwords"` | 102 | Legacy |
| `"com.bitchat.deviceidentity"` | 103 | Legacy |
| `"com.bitchat.noise.identity"` | 104 | Legacy |
| `"chat.bitchat.passwords"` | 105 | Legacy |
| `"bitchat.keychain"` | 106 | Legacy |
| `"bitchat"` | 107 | Legacy |
| `"com.bitchat"` | 108 | Legacy |

### 4.3 Keychain accounts (within the primary service)

| Account | Exact value | File:line |
| --- | --- | --- |
| Noise static key | `identity_noiseStaticKey` | `KeychainManager.swift:367` + `NoiseEncryptionService.swift:249` |
| Ed25519 signing key | `identity_ed25519SigningKey` | `NoiseEncryptionService.swift:291` |
| Identity cache blob | `"bitchat.identityCache.v2"` | `bitchat/Identity/SecureIdentityStateManager.swift:158` |
| Identity cache AES key | `identity_identityCacheEncryptionKey` | `SecureIdentityStateManager.swift:159` |
| One-time prekeys | `identity_prekeysV1` | `bitchat/Services/Prekeys/LocalPrekeyStore.swift:57` |
| Group keys | `identity_groupKey-<groupIDhex>` | `bitchat/Services/Groups/GroupStore.swift:138-140` |
| Install lifecycle marker | `"install_lifecycle_marker"` | `KeychainManager.swift:208` |
| Outbox encryption key | account `"outbox-encryption-key"` in service `chat.bitchat.outbox` | `MessageOutboxStore.swift:63-64` |
| Favorites blob | account `"chat.bitchat.favorites"` in service `chat.bitchat.favorites` | `FavoritesPersistenceService.swift:28-29` |
| Nostr identity | accounts `"nostr-current-identity"`, `"nostr-device-seed"` in `chat.bitchat.nostr` | `NostrIdentityBridge.swift:8-9` |

**Risk if any changes:** existing users lose identity, favorites, verified contacts, group keys, and queued outbound mail. Panic wipe stops erasing the old items — a security regression, not just a UX one.

---

## 5. UserDefaults / persistent storage keys

### 5.1 Keys containing `bitchat` (highest rename risk)

| Key | Exact value | File:line | Purpose | Risk if changed |
| --- | --- | --- | --- | --- |
| Nickname | `"bitchat.nickname"` | `bitchat/ViewModels/ChatViewModel.swift:335` | User's display name | Everyone reset to `anonNNNN` |
| Noise identity (legacy) | `"bitchat.noiseIdentityKey"` | `ChatViewModel.swift:1601` | Wiped on panic | **Panic stops erasing it** |
| Retention (legacy) | `"bitchat.messageRetentionKey"` | `ChatViewModel.swift:1602` | Wiped on panic | **Panic stops erasing it** |
| Panic latch | `"bitchat.panicResetPending"` | `bitchat/Services/BLE/BLEIncomingFileStore.swift:44` | Durable crash-recovery latch for an interrupted panic wipe | An interrupted wipe is never retried; pre-panic media survives |
| S&F metrics | `"chat.bitchat.storeAndForwardMetrics"` | `bitchat/Services/Courier/StoreAndForwardMetrics.swift:45` | Counters | Metrics reset |

### 5.2 Other persistent keys (no `bitchat` substring, still load-bearing)

| Key | File:line |
| --- | --- |
| `"appTheme"` | `bitchat/Utils/Theme.swift:19` |
| `"appInfo.selectedPane"` | `bitchat/Views/AppInfoView.swift:31` |
| `"app.languageOverride"` | `bitchat/Utils/AppLanguageSettings.swift:9` |
| `"AppleLanguages"` (system) | `AppLanguageSettings.swift:10` |
| `"nostr.customRelays"` | `bitchat/Nostr/NostrRelaySettings.swift:27` |
| `"georelay.lastFetchAt"` | `bitchat/Nostr/GeoRelayDirectory.swift:195` |
| `"locationChannel.selected"` | `bitchat/Services/LocationStateManager.swift:108` |
| `"locationChannel.teleportedSet"` | `LocationStateManager.swift:109` |
| `"locationChannel.bookmarks"` | `LocationStateManager.swift:110` |
| `"locationChannel.bookmarkNames"` | `LocationStateManager.swift:111` |
| `"locationChannel.bookmarkNamesSchemaVersion"` (current = `1`) | `LocationStateManager.swift:112-113` |
| `"locationNotes.enabled"` | `bitchat/Services/LocationNotesSettings.swift:16` |
| `"notifications.hideMessagePreviews"` | `bitchat/Services/NotificationPrivacySettings.swift:22` |
| `"bridge.userEnabled"` | `bitchat/Services/Gateway/BridgeService.swift:249` |
| `"gateway.userEnabled"` | `bitchat/Services/Gateway/GatewayService.swift:136` |
| `"networkActivationService.userTorEnabled"` | `bitchat/Services/NetworkActivationService.swift:48` |
| `"ptt.liveVoiceEnabled"` | `bitchat/Features/voice/PTTSettings.swift:20` |
| `"meshEchoes.clearedThrough"` | `bitchat/Services/MeshEchoSettings.swift:20` |
| `"meshSightings.dayKey"` | `bitchat/Services/MeshSightingsTracker.swift:21` |
| `"keychain.installLifecycleMarker.present"` | `bitchat/Services/KeychainManager.swift:209` |
| `"keychain.installLifecycleCleanup.pending"` | `KeychainManager.swift:210-211` |
| `"keychain.accessibility.afterFirstUnlockThisDeviceOnly.migrated"` | `KeychainManager.swift:311` |
| `"sharedContentEnvelopeV1"` (**app-group suite**) | `bitchat/Services/SharedContentHandoff.swift:129` |
| legacy app-group keys `"sharedContent"`, `"sharedContentType"`, `"sharedContentDate"` (cleanup only) | `SharedContentHandoff.swift:131-135` |

### 5.3 On-disk stores (Application Support)

| Path | File:line |
| --- | --- |
| `files/`, `files/incoming`, `images/incoming`, `images/outgoing`, `voicenotes/incoming`, `voicenotes/outgoing` | `bitchat/Features/media/ImageUtils.swift:207-215`, `bitchat/Services/BLE/BLEIncomingFileStore.swift:215`, `:516-518` |
| `board/posts.json` | `bitchat/Services/Board/BoardStore.swift:365-366` |
| `courier/envelopes.json` | `bitchat/Services/Courier/CourierStore.swift:626-627` |
| `courier/outbox.sealed` | `bitchat/Services/Courier/MessageOutboxStore.swift:895-896` |
| `courier/bridge-drop-dedup.json` | `bitchat/Services/Gateway/BridgeDropDedupStore.swift:134-135` |
| `groups/groups.json` | `bitchat/Services/Groups/GroupStore.swift:191-192` |
| `nostr/processed-events.json` | `bitchat/Services/NostrProcessedEventStore.swift:104-105` |
| `prekeys/` | `bitchat/Services/Prekeys/PrekeyBundleStore.swift:213` |
| **`bitchat/georelays_cache_v2.csv`** (legacy `georelays_cache.csv` deleted on load) | `bitchat/Nostr/GeoRelayDirectory.swift:130`, `:134-136` |
| **`bitchat/arti`** — Tor (Arti) state directory | `localPackages/Arti/Sources/TorManager.swift:154` |
| `.panic-recovery-pending`, `.panic-media-wipe-pending` marker files | `BLEIncomingFileStore.swift:122-127` |

---

## 6. App Group identifiers

| Item | Exact value | File:line | Risk if changed |
| --- | --- | --- | --- |
| Build setting | `APP_GROUP_ID = group.chat.bitchat` | `Configs/Release.xcconfig:12` | Share-extension handoff silently stops working |
| Runtime derivation | `BitchatApp.groupID = "group.\(bundleID)"` | `bitchat/BitchatApp.swift:15` | — |
| Extension fallback | `Bundle.main.object(forInfoDictionaryKey: "AppGroupID") as? String ?? "group.chat.bitchat"` | `bitchatShareExtension/ShareViewController.swift:16` | Hardcoded fallback diverges if the plist key is missed |
| Info.plist key | `AppGroupID = $(APP_GROUP_ID)` | `bitchat/Info.plist` and `bitchatShareExtension/Info.plist` | — |
| Entitlements | `com.apple.security.application-groups` = `$(APP_GROUP_ID)` | `bitchat/bitchat.entitlements`, `bitchat/bitchat-macOS.entitlements`, `bitchatShareExtension/bitchatShareExtension.entitlements` | — |
| Local dev template | `group.chat.bitchat.$(DEVELOPMENT_TEAM)` | `Configs/Local.xcconfig.example` | — |
| Keychain access group | `KeychainManager.appGroup = "group.\(bundleID)"` | `bitchat/Services/KeychainManager.swift:91` | Also swept by panic wipe (`:777-784`) |

**Note:** the app group is *also* used as a keychain access-group probe. `KeychainManager.completedApplicationGroupDelete(status:)` (`:200-204`) treats `-34018` as success because the app has an *application*-group, not a *keychain-access*-group, entitlement.

---

## 7. Deep link URL schemes & notification routing payload keys

### 7.1 URL scheme `bitchat`

| Site | Exact value | File:line | Purpose |
| --- | --- | --- | --- |
| Registration | `CFBundleURLSchemes = ["bitchat"]` | `bitchat/Info.plist` | System-registered scheme |
| Share handoff | `url.scheme == "bitchat", url.host == "share"` | `bitchat/App/AppRuntime.swift:173` | Share extension → app |
| In-app router | `guard url.scheme == "bitchat"`; hosts `"user"`, `"geohash"` | `bitchat/Views/MessageListView.swift:553-580` | Tappable mentions / geohash chips |
| Verification QR (emit) | `comps.scheme = "bitchat"; comps.host = "verify"` | `bitchat/Services/VerificationService.swift:47-48` | **QR codes printed/shared by existing users** |
| Verification QR (parse) | `url.scheme == "bitchat", url.host == "verify"` | `VerificationService.swift:62` | **Must keep parsing old QRs** |
| Geohash notification deeplink | `"bitchat://geohash/\(geohash)"` | `bitchat/Services/NotificationService.swift:257` | — |
| Mention link | `"bitchat://user/\(spid.toPercentEncoded())"` | `bitchat/ViewModels/ChatMessageFormatter.swift:58`, `:373`, `:417` | — |
| Geohash link | `"bitchat://geohash/\(token)"` | `ChatMessageFormatter.swift:271` | — |

**Verification QR query items (order-sensitive canonical signature):** `v`, `noise`, `sign`, `nick`, `ts`, `nonce`, `sig`, optional `npub` — `VerificationService.swift:49-58`, canonical bytes at `:27-43`.

**Risk:** dropping `bitchat` from `CFBundleURLSchemes` breaks (a) every previously generated verification QR, (b) the share extension handoff, (c) all geohash notification taps. **Keep `bitchat` registered alongside any new `range` scheme.**

Also validated in the geohash route: geohash length `2...12`, alphabet `"0123456789bcdefghjkmnpqrstuvwxyz"` (`MessageListView.swift:574-575`).

### 7.2 Notification payload keys & identifiers

| Item | Exact value | File:line | Consumed at |
| --- | --- | --- | --- |
| Category ID | `"chat.bitchat.category.nearby"` | `bitchat/Services/NotificationService.swift:95` | registered `:192`, attached `:273` |
| Action ID | `"chat.bitchat.action.wave"` | `NotificationService.swift:96` | matched `bitchat/App/AppRuntime.swift:254` |
| DM userInfo | `["peerID": peerID.id, "senderName": sender]` | `NotificationService.swift:245` | read `AppRuntime.swift:259`, `:274` |
| Geohash userInfo | `["deeplink": "bitchat://geohash/<gh>"]` | `NotificationService.swift:258` | read `AppRuntime.swift:264`, `:282` |
| DM identifier prefix | `"private-"` | `NotificationService.swift:242` | matched `AppRuntime.swift:259`, `:274` |
| Geo identifier prefix | `"geo-activity-"` | `NotificationService.swift:256` | matched `AppRuntime.swift:281` |
| Network identifier | `"network-available"` (fixed, so iOS coalesces) | `NotificationService.swift:266` | — |
| Mention identifier prefix | `"mention-"` | `NotificationService.swift:234` | — |

**Risk:** notification categories/actions are registered with the system **and persist in already-delivered notifications**. Renaming the wave action ID means taps on notifications delivered before the update do nothing. The prefix strings (`"private-"`, `"geo-activity-"`) are matched with `hasPrefix` — a rename in one file and not the other silently breaks routing with no compile error.

---

## 8. Nostr event kinds & tag conventions

File: `bitchat/Nostr/NostrProtocol.swift:21-37`

| Kind | Exact value | Purpose | Note |
| --- | --- | --- | --- |
| `metadata` | `0` | — | |
| `textNote` | `1` | Persistent geohash location note | |
| `deletion` | `5` | NIP-09 deletion request | |
| `seal` | `13` | Sender-signed seal (inside ciphertext) | **Reuses NIP-59 number; payload is NOT NIP-compatible** |
| `dm` | `14` | Unsigned inner rumor | Same caveat |
| `giftWrap` | `1059` | Public outer envelope, one-time key | Same caveat |
| `courierDrop` | `1401` | Sealed courier envelope parked on relays | Stored kind |
| `ephemeralEvent` | `20000` | Geohash public message / bridge mesh event | |
| `geohashPresence` | `20001` | Presence heartbeat (empty content, no nickname tag) | |

### Tag conventions

| Tag | Shape | File:line | Rule |
| --- | --- | --- | --- |
| `g` | `["g", geohash]` | `NostrProtocol.swift:309`, `:326`, `:437` | Geohash channel filter key (`#g`) |
| `n` | `["n", nickname]` | `:311`, `:358`, `:439` | Optional; **must be absent** on kind-20001 presence |
| `t` | `["t", "teleport"]`, `["t", "urgent"]` | `:314`, `:444` | |
| `p` | `["p", recipientPubkey]` | `:524` | Gift wrap: **exactly one**; validated `giftWrap.tags == [["p", recipientIdentity.publicKeyHex]]` (`:112`) |
| `r` | `["r", cell]` | `:356`, `:390` | Bridge rendezvous; deliberately distinct from `g` so bridge traffic stays out of `#g` subscriptions |
| `m` | `["m", stableID, meshSenderID, String(meshTimestampMs)]` | `:366` | v1.7.0 parsers key dedup on `m[1]` unconditionally |
| `x` | `["x", recipientTagHex]` | `:411` | Courier drop rotating recipient tag |
| `expiration` | `["expiration", String(epochSeconds)]` | `:412`, `:441` | NIP-40 |
| `nonce` | NIP-13 PoW nonce tag | `:289`, `bitchat/Nostr/NostrPoW.swift` | Mined over the full serialized event; `created_at` fixed up front |

### Envelope validation invariants (must not be loosened)

- Seal: `kind == 13`, `tags.isEmpty`, valid signature (`NostrProtocol.swift:135-140`).
- Rumor: `kind == 14`, `sig == nil`, `seal.pubkey == rumor.pubkey`, tags either empty (iOS) or exactly `[["p", recipient]]` (Android) (`:160-181`).
- Event ID: `SHA256(JSON([0, pubkey, created_at, kind, tags, content]))` with `.withoutEscapingSlashes` (`:872-884`).
- Outer timestamp randomized ±900 s (`:748-767`).
- Inbound tag caps: `nostrMaxEventTags = 64`, `nostrMaxEventTagValues = 16`, `nostrMaxEventTagValueBytes = 1024` (`bitchat/Services/TransportConfig.swift:100-102`).

### Embedded-packet prefix

| Item | Exact value | File:line |
| --- | --- | --- |
| BitChat-over-Nostr payload prefix | **`"bitchat1:"`** + base64url(BitchatPacket) | emit: `bitchat/Nostr/NostrEmbeddedBitChat.swift:31`, `:54`, `:75`, `:97`; parse: `bitchat/ViewModels/NostrInboundPipeline.swift` |

**This is a wire-format magic string.** A rename means no deployed peer can read your Nostr DMs and you cannot read theirs.

### Default relays

`"wss://relay.damus.io"`, `"wss://nos.lol"`, `"wss://relay.primal.net"`, `"wss://offchain.pub"` — `bitchat/Nostr/NostrRelayManager.swift:157-160`.

Geo relay directory remote: `https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv` — `bitchat/Nostr/GeoRelayDirectory.swift:73`. **Contains `bitchat` in a real URL** — a find-replace produces a 404 and freezes the geo relay directory at its bundled snapshot.

---

## 9. Bundle identifiers & build configuration

| Item | Exact value | File:line | Risk if changed |
| --- | --- | --- | --- |
| Bundle ID | `PRODUCT_BUNDLE_IDENTIFIER = chat.bitchat` | `Configs/Release.xcconfig:11` | **Users receive a new app, not an update. All keychain items, UserDefaults, and Application Support data are orphaned. This is a full data loss event for the installed base.** |
| App group | `APP_GROUP_ID = group.chat.bitchat` | `Configs/Release.xcconfig:12` | Share extension handoff breaks |
| Runtime fallback | `Bundle.main.bundleIdentifier ?? "chat.bitchat"` | `bitchat/BitchatApp.swift:14` | — |
| Marketing version | `MARKETING_VERSION = 1.7.1` | `Release.xcconfig:1` | — |
| Build number | `CURRENT_PROJECT_VERSION = 1` | `Release.xcconfig:2` | — |
| Team | `DEVELOPMENT_TEAM = L3N5LHJD5Y` | `Release.xcconfig:7` | — |
| Deployment targets | iOS `16.0`, macOS `13.0` | `Release.xcconfig:4-5` | — |
| Extension bundle ID | `$(PRODUCT_BUNDLE_IDENTIFIER).ShareExtension` | `bitchat.xcodeproj/project.pbxproj:542`, `:853` | Derived — safe |
| Test bundle ID | `$(PRODUCT_BUNDLE_IDENTIFIER).tests` | `project.pbxproj:467`, `:491`, `:517`, `:604` | Derived — safe |
| Product name | `PRODUCT_NAME = bitchat` | `project.pbxproj:575`, `:633`, `:667`, `:759` | Feeds `$(PRODUCT_MODULE_NAME)` |
| Extension principal class | `$(PRODUCT_MODULE_NAME).ShareViewController` | `bitchatShareExtension/Info.plist` | Changing `PRODUCT_NAME` changes the module name — this stays correct **only** because it is a variable. Do not hardcode. |
| Entitlements paths | `bitchat/bitchat.entitlements`, `bitchat/bitchat-macOS.entitlements`, `bitchatShareExtension/bitchatShareExtension.entitlements` | `project.pbxproj:532`, `:562`, `:653`, `:745`, `:843` | File renames must be mirrored in the pbxproj |
| Display name | `CFBundleDisplayName = bitchat` | `bitchat/Info.plist`, `bitchatShareExtension/Info.plist` | **Display only — safe to change** |
| Usage descriptions | `NSBluetoothAlwaysUsageDescription` etc. mention "bitchat" | `bitchat/Info.plist` | **Display only — safe to change** |

**Verdict:** keeping `chat.bitchat` / `group.chat.bitchat` is the only way to ship the rebrand as an *update*. If the team insists on a new bundle ID, an explicit migration is required and must be designed — see §12.

---

## 10. ⚠️ Load-bearing `bitchat` string literals (the find-replace kill list)

**64 sites / 42 distinct values.** Everything below is protocol, storage, or platform-registration — **not display copy**.

### 10.1 Cryptographic domain separators (14) — HIGHEST RISK

These bytes are hashed into signatures and Noise prologues. A rename produces **valid-looking but unverifiable** output. Failures surface as "signature invalid" / "decrypt failed", never as "you renamed a constant".

| # | Exact literal | File:line | Used for |
| --- | --- | --- | --- |
| 1 | `"bitchat-announce-v1"` | `bitchat/Services/NoiseEncryptionService.swift:633` | Length-prefixed context in canonical announce signing bytes |
| 2 | `"bitchat-courier-v1"` | `NoiseEncryptionService.swift:438` | **Noise X prologue** for courier envelopes |
| 3 | `"bitchat-prekey-v1"` | `NoiseEncryptionService.swift:483` | **Noise X prologue prefix**, then `UInt32BE(prekeyID)` |
| 4 | `"bitchat-courier-tag-v1"` | `localPackages/BitFoundation/Sources/BitFoundation/CourierEnvelope.swift:169` | Rotating daily recipient tag derivation |
| 5 | `"bitchat-prekey-bundle-v1"` | `localPackages/BitFoundation/Sources/BitFoundation/PrekeyBundle.swift:50` | Prekey bundle signature domain |
| 6 | `"bitchat-vouch-v1"` | `bitchat/Protocols/VouchAttestation.swift:35` | Ed25519 vouch attestation signing context |
| 7 | `"bitchat-board-v1"` | `bitchat/Protocols/BoardPackets.swift:37` | Board post signing context |
| 8 | `"bitchat-board-del-v1"` | `bitchat/Protocols/BoardPackets.swift:38` | Board tombstone signing context |
| 9 | `"bitchat-group-v1"` | `bitchat/Services/Groups/GroupProtocol.swift:217` | Creator-signed group state domain |
| 10 | `"bitchat-group-msg-v1"` | `bitchat/Services/Groups/GroupProtocol.swift:414` | Group message signing domain |
| 11 | `"bitchat-verify-v1"` | `bitchat/Services/VerificationService.swift:24` | QR verification signature context |
| 12 | `"bitchat-verify-resp-v1"` | `bitchat/Services/VerificationService.swift:134`, `:179` | Verification response signature context |
| 13 | `"bitchat-private-media-message-v1"` | `bitchat/Protocols/BitchatFilePacket.swift:169` | Stable private-media message ID derivation (cross-device receipt correlation) |
| 14 | `"bitchat-bridge-drop-dedup-v2"` | `bitchat/Services/Gateway/BridgeCourierService.swift:225` | Bridge drop dedup key material |

### 10.2 Wire magic string (1 value / 5 sites)

| Exact literal | File:line |
| --- | --- |
| `"bitchat1:"` | `bitchat/Nostr/NostrEmbeddedBitChat.swift:31`, `:54`, `:75`, `:97`; parsed in `bitchat/ViewModels/NostrInboundPipeline.swift` |

### 10.3 Keychain service / account strings (12 values / 17 sites)

`"chat.bitchat.nostr"` (`NostrIdentityBridge.swift:7`, `KeychainManager.swift:99`) · `"chat.bitchat.favorites"` (`FavoritesPersistenceService.swift:28`, `:29`, `KeychainManager.swift:100`) · `"chat.bitchat.outbox"` (`MessageOutboxStore.swift:63`, `KeychainManager.swift:101`) · `"bitchat.identityCache.v2"` (`SecureIdentityStateManager.swift:158`) · legacy list `"com.bitchat.passwords"`, `"com.bitchat.deviceidentity"`, `"com.bitchat.noise.identity"`, `"chat.bitchat.passwords"`, `"bitchat.keychain"`, `"bitchat"`, `"com.bitchat"` (`KeychainManager.swift:102-108`) · label `"bitchat-\(key)"` (`KeychainManager.swift:418`, `:578`).

### 10.4 UserDefaults keys (5)

`"bitchat.nickname"` (`ChatViewModel.swift:335`) · `"bitchat.noiseIdentityKey"` (`ChatViewModel.swift:1601`) · `"bitchat.messageRetentionKey"` (`ChatViewModel.swift:1602`) · `"bitchat.panicResetPending"` (`BLEIncomingFileStore.swift:44`) · `"chat.bitchat.storeAndForwardMetrics"` (`StoreAndForwardMetrics.swift:45`).

### 10.5 Bundle / app-group (2 values / 6 sites)

`"chat.bitchat"` (`BitchatApp.swift:14`, `Configs/Release.xcconfig:11`, `Configs/Local.xcconfig.example`) · `"group.chat.bitchat"` (`ShareViewController.swift:16`, `Configs/Release.xcconfig:12`, `Configs/Local.xcconfig.example`).

### 10.6 URL scheme & deep links (10 sites)

`CFBundleURLSchemes = ["bitchat"]` (`bitchat/Info.plist`) · `AppRuntime.swift:173` · `MessageListView.swift:553` · `VerificationService.swift:47`, `:62` · `NotificationService.swift:257` · `ChatMessageFormatter.swift:58`, `:271`, `:373`, `:417`.

### 10.7 Platform-registered identifiers (4)

`"chat.bitchat.ble.central"` (`BLEService.swift:192`) · `"chat.bitchat.ble.peripheral"` (`BLEService.swift:193`) · `"chat.bitchat.category.nearby"` (`NotificationService.swift:95`) · `"chat.bitchat.action.wave"` (`NotificationService.swift:96`).

### 10.8 Filesystem / remote resource (3)

`"bitchat"` app-support subdirectory (`GeoRelayDirectory.swift:130`) · `"bitchat/arti"` Tor state dir (`TorManager.swift:154`) · `https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv` (`GeoRelayDirectory.swift:73`).

---

### 10.9 SAFE to rename (for contrast)

| Category | Examples | Why safe |
| --- | --- | --- |
| Swift type names | `BitchatPacket`, `BitchatMessage`, `BitchatFilePacket`, `BitchatDelegate`, `BitchatGroup`, `BitchatPeer`, `BitchatApp` | Compile-time identifiers; not serialized |
| Font/view helpers | `.bitchatFont`, `.bitchatSystem`, `bitchat/Utils/Font+Bitchat.swift` | Pure UI |
| Display copy | `"bitchat"` label (`PrivacyScreen.swift:70`), `"bitchat/"` logo (`ContentHeaderView.swift`, `AppInfoView.swift`), `"👥 bitchatters nearby!"` (`NotificationService.swift:263`), `"open bitchat to read"` (`:112`), `CFBundleDisplayName`, all `NS*UsageDescription` strings | User-visible text only |
| GCD queue labels | `"chat.bitchat.noise.session"`, `"chat.bitchat.ble.log-rate-limiter"`, `"com.bitchat.waveform-cache"`, `"chat.bitchat.board.store"`, `"chat.bitchat.courier.store"`, `"chat.bitchat.reachability"`, `"chat.bitchat.prekeys.local"`, `"chat.bitchat.prekeys.bundles"`, `"chat.bitchat.nostr-ack-pacer"`, `"chat.bitchat.nostr-processed-events"`, `"chat.bitchat.audio-session"`, `"chat.bitchat.ptt.capture"`, `"com.bitchat.transfer-progress"`, `"bitchat.identity.state"` | Debug labels; no cross-process meaning |
| Process-local `Notification.Name` | `"bitchat.geoRelayDirectoryDidRefresh"`, `"bitchat.nostrRelaySettingsDidChange"`, `"bitchat.locationNotesSettingsDidChange"` | In-process only |
| Logging | OSLog subsystem `"chat.bitchat"` (`localPackages/BitLogger/Sources/OSLog+Categories.swift`), env var `"BITCHAT_LOG_LEVEL"` (`SecureLogger.swift:106`), compile flag `BITCHAT_DEV_ALLOW_CLEARNET` (`TorManager.swift:67`) | Diagnostics only (log-filter presets will need updating) |

> ⚠️ **However:** `"chat.bitchat.outbox"` and `"chat.bitchat.favorites"` appear as *both* keychain services and near-identical strings elsewhere. Never rename these by pattern — rename by call-site inspection.

---

## 11. Panic wipe — exact inventory of what it erases

Entry point: `ChatViewModel.panicClearAllData(restartServices:)` — `bitchat/ViewModels/ChatViewModel.swift:1551-1770`.
Triggers: triple-tap the logo (`AppInfoView.swift:123`), and crash-recovery replay at startup (`ChatViewModel.swift:1241`).

**Ordering is a security property.** Network is stopped first, durable intents are established *before* deletion, and services restart only after the transaction commits.

| # | Store touched | Call | Line |
| --- | --- | --- | --- |
| 1 | Network + presence services stopped | `panicNetworkLifecycle.stop()` → `GeohashPresenceService.stopForPanic()`, `NetworkActivationService.stopForPanic()` | 1559; 101-102 |
| 2 | Durable recovery intent (UserDefaults `bitchat.panicResetPending` + `.panic-recovery-pending` file) | `panicRecoveryOperations.begin()` | 1563 |
| 3 | Mesh transport quiesced | `suspendForPanicReset()` / `emergencyDisconnectAll()` | 1567-1571 |
| 4 | In-flight media prep + live capture handles | `mediaTransferCoordinator.resetForPanic()`, `liveVoiceCoordinator.resetForPanic()` | 1575-1576 |
| 5 | Pending private-media consents | `cancelAllLegacyPrivateMediaConsents()` | 1583 |
| 6 | **All messages** (public + private, ConversationStore) | `conversations.clearAll()` | 1588 |
| 7 | **All keychain data** — every service in §4.2 + access-group sweep + install marker recreated | `keychain.deleteAllKeychainData()` | 1592; impl `KeychainManager.swift:669-830` |
| 8 | UserDefaults `"bitchat.noiseIdentityKey"`, `"bitchat.messageRetentionKey"` | `userDefaults.removeObject` | 1601-1602 |
| 9 | Location state (selected channel, teleport set, bookmarks) | `LocationStateManager.shared.panicWipe()` | 1607 |
| 10 | Nickname reset to `anon<1000-9999>`, written to `bitchat.nickname` | — | 1610-1611 |
| 11 | Identity cache (fingerprints, social identities, verifications, vouches, blocks) | `identityManager.clearAllIdentityData()` | 1615 |
| 12 | Peer identity store / location presence / rate limiter | `peerIdentityStore.clearAll()`, `locationPresenceStore.reset()`, `publicRateLimiter.reset()` | 1616-1618 |
| 13 | Favorites (keychain) | `FavoritesPersistenceService.shared.clearAllFavorites()` | 1621 |
| 14 | Courier envelopes carried for third parties (memory + `courier/envelopes.json`) | `CourierStore.shared.wipe()` | 1626 |
| 15 | Bridge courier state + `courier/bridge-drop-dedup.json` | `BridgeCourierService.shared.wipe()` | 1627 |
| 16 | Own outbox (`courier/outbox.sealed` + its keychain key) | `messageRouter.wipeOutbox()` | 1628 |
| 17 | Gossip archive (carried public history) | `GossipMessageArchive.wipeDefault()` | 1629 |
| 18 | S&F metrics (`chat.bitchat.storeAndForwardMetrics`) | `StoreAndForwardMetrics.shared.reset()` | 1630 |
| 19 | Geohash chat activity samples | `GeohashChatActivityTracker.shared.clear()` | 1634 |
| 20 | Daily mesh sightings tally | `MeshSightingsTracker.shared.clear()` | 1635 |
| 21 | Mesh echo watermark | `MeshEchoSettings.reset()` | 1636 |
| 22 | Notification privacy preference | `NotificationPrivacySettings.reset()` | 1637 |
| 23 | Custom Nostr relays (`nostr.customRelays`) | `NostrRelaySettings.reset()` | 1640 |
| 24 | Group keys + rosters (keychain `groupKey-*` + `groups/groups.json`) | `groupStore.wipe()` | 1643 |
| 25 | Cached peer prekey bundles | `PrekeyBundleStore.shared.wipe()` | 1647 |
| 26 | Board posts + tombstones (memory + `board/posts.json`) | `BoardStore.shared.wipe()` | 1650 |
| 27 | **App-group** share handoff envelope (`sharedContentEnvelopeV1`) | `SharedContentStore(defaults: UserDefaults(suiteName: BitchatApp.groupID)).discardAll()` | 1657-1659 |
| 28 | Autocomplete state, selected chat | — | 1664-1670 |
| 29 | Geohash session state, subscriptions, cached geohash identity, Nostr key map | — | 1675-1680 |
| 30 | Read receipts + dedup service | `sentReadReceipts.removeAll()`, `deduplicationService.clearAll()` | 1683-1684 |
| 31 | Nostr relay manager (subscriptions, handlers, pending sends, replay state) | `NostrRelayManager.shared.resetForPanicWipe()` | 1690 |
| 32 | In-flight gift-wrap decrypts invalidated (pre-wipe keys must not land) | `nostrCoordinator.inbound.invalidateInFlightDecrypts()` | 1695 |
| 33 | Nostr identity associations + **in-memory derived per-geohash secp256k1 keys** | `idBridge.clearAllAssociations()` | 1699; impl `NostrIdentityBridge.swift:51-65` |
| 34 | BLE identity replaced (radio still stopped) | `resetIdentityForPanic(...)` | 1703-1710 |
| 35 | **All media files on disk** | `panicRecoveryOperations.wipeMedia(recoveryIntent)` | 1717 |
| 36 | iOS app-switcher snapshots | `Self.clearAppSwitcherSnapshots()` | 1743 |
| 37 | Also cleared by `NoiseEncryptionService`: sessions, fingerprint maps, rate limiter, **one-time prekey privates** | `clearEphemeralStateForPanic()`, `clearPersistentIdentity()` → `localPrekeys.wipe()` | `NoiseEncryptionService.swift:1001-1008`, `:574-584` |

**Panic-wipe invariants that must survive the rebrand:**
- The keychain service list (§4.2) must include **every** service the app has ever written, or panic misses items. If services are renamed for `range`, the old `chat.bitchat.*` names **must stay in the legacy list**.
- `"bitchat.panicResetPending"` must keep its exact name, or an interrupted wipe is never retried on the next launch.
- The install marker is deliberately **recreated** after a panic (`KeychainManager.swift:794-811`) so a later uninstall/reinstall is still distinguishable from an in-place upgrade.
- The function returns `false` and leaves services stopped if the transaction did not commit (`ChatViewModel.swift:1747`).

---

## 12. Existing data-migration code

| Migration | What it does | File:line | Rebrand impact |
| --- | --- | --- | --- |
| **Install-lifecycle reconciliation** | Keychain items survive app deletion but the container/UserDefaults do not. A surviving keychain marker + missing defaults marker ⇒ container was replaced ⇒ delete stale secrets. Fail-closed gate blocks concurrent readers during cleanup. | `bitchat/Services/KeychainManager.swift:128-149`, `:206-304`, `:26-60` | **A bundle-ID change makes every existing install look like a reinstall to the *new* bundle only in the sense that it sees nothing at all — the old keychain is in a different access scope. Nothing is migrated, nothing is cleaned.** |
| **Keychain accessibility migration** | One-time `SecItemUpdate` of every application-owned service to `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`; flag `keychain.accessibility.afterFirstUnlockThisDeviceOnly.migrated` only set when *all* services succeeded, else retried next launch. | `KeychainManager.swift:161-175`, `:310-345` | Driven off the service list in §4.2 — renaming services without adding the old names skips the migration |
| **Nostr device-seed re-save** | On load, rewrites the seed with `AfterFirstUnlockThisDeviceOnly` accessibility. | `bitchat/Nostr/NostrIdentityBridge.swift:73-78` | — |
| **Bookmark-name schema v1** | `locationChannel.bookmarkNamesSchemaVersion` gate. | `bitchat/Services/LocationStateManager.swift:112-113`, `:228`, `:234-246` | — |
| **Shared-content legacy keys** | Clears `sharedContent`, `sharedContentType`, `sharedContentDate` whenever the v1 envelope is staged/consumed. | `bitchat/Services/SharedContentHandoff.swift:131-135`, `:158`, `:164`, `:204-210` | App-group scoped — dies with an app-group rename |
| **Geo relay cache v1→v2** | Deletes `georelays_cache.csv`, writes `georelays_cache_v2.csv`. | `bitchat/Nostr/GeoRelayDirectory.swift:134-136` | Lives under the `bitchat/` app-support subdir |
| **Courier/outbox file protection** | Re-applies file protection on load. | `CourierStore.swift:605-613`, `MessageOutboxStore.swift:874-882` | — |
| **Peer-ID rebind migration** | Migrates conversations + fingerprint mappings when a peer's short ID changes (`migrateConversation`, `migrateFingerprintMapping`, `migrateNoiseKeyUpdate`), gated by `uiMigrationCutoffSeconds = 24h`. | `bitchat/App/ConversationStore.swift:583-619`, `bitchat/App/PeerIdentityStore.swift:45`, `bitchat/ViewModels/ChatPeerIdentityCoordinator.swift:561-590`, `bitchat/Services/TransportConfig.swift:361` | Runtime identity churn, unrelated to rebrand |
| **`NoisePayloadType` 0x09 compat alias** | Accepts the prerelease private-file value, never emits it. | `bitchat/Protocols/BitchatProtocol.swift:101-113` | Keep |
| **Nostr inner-tag shape tolerance** | Accepts both iOS (no inner tags) and Android (`[["p", recipient]]`) historical shapes. | `bitchat/Nostr/NostrProtocol.swift:172-181` | Keep |

**There is no bitchat→range migration path in this codebase.** If the bundle ID or app group changes, one must be written from scratch — and note that a bundle-ID change makes the *old* keychain unreachable, so an in-app migration is impossible after the fact. The migration would have to ship in a final `chat.bitchat` build.

---

## 13. Recommended rebrand policy

1. **Keep `PRODUCT_BUNDLE_IDENTIFIER = chat.bitchat` and `APP_GROUP_ID = group.chat.bitchat`.** Everything else follows from this. The App Store name, `CFBundleDisplayName`, icon, and all UI copy can change freely.
2. **Never run a global find-replace.** Change only: display strings, type names, file names, font helpers, and (optionally) GCD queue labels.
3. **Add** `range` as an *additional* URL scheme; do not remove `bitchat` (verification QRs in the wild encode `bitchat://verify?...`).
4. **Freeze** the 14 domain separators in §10.1. If protocol evolution ever requires new ones, add `range-*-v1` alongside and accept both, never replace.
5. **Freeze** `"bitchat1:"`, the BLE UUIDs, the BLE restoration IDs, and the notification category/action IDs.
6. If keychain services are ever renamed, **append** the old `chat.bitchat.*` / `com.bitchat.*` names to `additionalApplicationOwnedServices` permanently — panic wipe correctness depends on it.
7. Add a CI guard: fail the build if any literal in §10.1–§10.7 disappears from the tree. These have no compile-time protection and no runtime error that names the cause.
