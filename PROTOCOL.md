# Whospair — Protocol

This document describes what Whospair actually does on the wire and on disk, as of version 1.3.9 (versionCode 16). It is written from the source code; when the two disagree, the code is right and this file is a bug.

Source pointers are given as `package/File.kt`, under `WhospairApp/app/src/main/java/com/whisper/`.

Some labels still say `whisper` (the project's former name). They are domain-separation strings and are kept as-is so that existing installs keep working.

---

## 1. Identity

Each install generates, on the phone:

| Key | Algorithm | Use |
|---|---|---|
| Encryption key | X25519 | key agreement, and the user's ID |
| Signing key | Ed25519 | signatures |
| Onion seed | 32 bytes | the phone's Tor v3 onion service |

There is no account, number, e-mail or username. The X25519 public key is the identifier.

**Contact link** (`util/ContactKeyParser.kt`): 184 characters, the X25519 key in hex (64), then the Ed25519 key in hex (64), then the onion address (56, base32, checksum verified). Three forms are accepted: `whospair:<184>` (QR), `whospair://add_contact?key=<184>` (clickable link), and the raw 184 characters.

**Onion service** (`tor/TorRuntime.kt`): created with `ADD_ONION ED25519-V3:<key>` and the `DiscardPK` flag, over Tor's control port on a Unix socket in a private folder (0700). No `HiddenServiceDir` is used, so Tor never writes the onion key to a file. SOCKS is a Unix socket with `OnionTrafficOnly`: the app cannot reach the clearnet.

---

## 2. Connection handshake (v3)

`transport/TorLink.kt`, `transport/MessageTransport.kt` (`dial`, `accept`).

A (the caller) dials B's onion address. Tor guarantees A reached the holder of that onion key, which A got from B's QR. B learns nothing about A from Tor, so A must prove who it is:

```
B → A   CHALLENGE  [0x03][32 random bytes]
A → B   HELLO      [0x03][A.x 32][A.ed 32][A.onion 56][proof 32][sig 64]   (217 bytes)

binding = "whisper-v3|tor-hello|" ‖ challenge ‖ A.x ‖ A.ed ‖ A.onion ‖ B.x
proof   = HMAC-SHA256( HKDF-SHA256(X25519(A.x_priv, B.x), info="whisper-v3|hello-x25519"), binding )
sig     = Ed25519(A.ed_priv, binding)
```

B accepts only if all of these hold:

- size and version byte are right, and A.x is not B's own key;
- if A is a known contact: not blocked, its stored Ed25519 key equals A.ed, and private mode allows it;
- `sig` verifies under A.ed;
- `proof` verifies (constant-time). This proves A holds the X25519 private key it announces, so nobody can present someone else's public key (which is in their QR) and take their place.

If A is unknown, it is added as an **unconfirmed** contact (one scan is enough). The user confirms or blocks it. The number of unconfirmed contacts is capped.

The fresh challenge prevents replaying a HELLO; B.x inside the binding prevents B from reusing it with a third party. Handshake timeout is 10 s, with at most 8 handshakes pending at once.

If both sides dial each other at the same time, the stream opened by the lower X25519 key wins and the other is closed.

**No extra session encryption.** The Tor rendezvous circuit is already end-to-end encrypted with ephemeral keys (forward secrecy included), and each message is separately encrypted and signed (section 4).

---

## 3. Framing

Every frame on a stream is `[length, 4 bytes big-endian][payload]`, with `1 ≤ length ≤ 65,536`. Anything else closes the stream (a peer announcing 4 GB cannot exhaust memory).

The payload's first bytes tell its kind:

| Start | Kind |
|---|---|
| `{` | JSON: a message, or a control frame (`"t": …`) |
| `WBM1` | binary message (too large for JSON hex, e.g. file chunks) |
| `0xC1`–`0xC4` | call frame (section 5) |
| `WUD1` | update data (section 8) |

The first frame each side sends after the handshake is its version: `{"v":1,"t":"ver","code":<versionCode>}`. A peer below the minimum version only gets update frames and `bye` (section 8).

---

## 4. Messages

`crypto/CryptoManager.kt`, `services/MessageService.kt`.

Each message uses a fresh ephemeral X25519 key on the sender side:

```
ss       = X25519(eph_priv, B.x)
key      = HKDF-SHA256(ss, salt = none, info = eph_pub ‖ B.x, 32 bytes)
binding  = "whisper-v1" | "msg" | sender.x | recipient.x | eph_pub | counter | timestamp   (joined with "|", hex lowercase)
ct, tag  = AES-256-GCM(key, nonce = 12 random bytes, aad = binding, plaintext)
sig      = Ed25519(sender.ed_priv, binding)
```

`ss` and `key` are zeroed after use.

**On the wire** (JSON):

```
{"v":1, "id", "from", "to", "ts", "exp", "ctr", "content", "nonce", "tag", "epk", "sig"}
```

Binary values are hex. When the JSON would exceed one frame, the message goes as `WBM1 ‖ header length (2 bytes) ‖ JSON header without "content" ‖ raw ciphertext`.

**On arrival**, in this order (under a lock, so two streams from the same contact cannot race):

1. `from` must equal the contact authenticated by the handshake, and `to` must be us.
2. The contact must be known and not blocked.
3. Same `id` already stored: acknowledge again, store nothing.
4. `ctr` must be strictly greater than the last counter accepted from this contact. Otherwise the message is a replay, or was already received and since erased, and is answered with `stale`.
5. The signature over `binding` must verify. GCM then fails on any change to the ciphertext or to `binding`.
6. The counter is saved **before** the message is written: a crash loses one message, it never lets one through twice.

**Lifetime.** The sender picks it (5 minutes to 7 days, or "never"); it travels as `exp − ts`. The receiver recounts that duration on its own clock from the moment it stores the message, so a sender's clock running fast or slow changes nothing. Each phone deletes the message at its own expiry. Messages received while the app was closed (section 7) get their lifetime counted from the unlock, so they cannot expire before they could be read.

**Control frames** (`{"v":1,"t":…}`): `ack`, `stale`, `del` (delete for everyone, up to 200 ids), `wipe-req` / `wipe-ok` / `wipe-off` / `wipe` (two-party erase), `nudge`, `bye`, the profile photo, and the update frames. They are not signed one by one: they are accepted only on the stream of the contact they concern, which the handshake already authenticated. A contact can only affect its own conversation with you.

---

## 5. Voice calls

`call/CallProtocol.kt`, `security/CallKeys.kt`. Calls are allowed only between confirmed contacts and travel on the same Tor stream.

```
OFFER   [0xC1][call id 16][eph X25519 32][sig 64]
ACCEPT  [0xC2][call id 16][eph X25519 32][sig 64]
END     [0xC3][call id 16][reason 1]
AUDIO   [0xC4][call id 16][seq u32][AES-GCM(Opus packets) + tag 16]

sig = Ed25519 over "whisper-v1|call|offer|" (or "accept|") ‖ call id ‖ eph ‖ sender.x ‖ recipient.x
```

Each side makes a fresh ephemeral X25519 key per call. The signature ties it to the identity in the QR and cannot be reused for another call or another contact. `END` is not signed: only the contact at the other end of the stream can send it, and only for its own call.

**Keys:**

```
okm = HKDF-SHA256(X25519(eph_mine, eph_peer), salt = call id (hex), info = "whisper-v1|media|" ‖ eph_initiator ‖ eph_responder, 64 bytes)
```

The initiator sends with the first 32 bytes, the responder with the last 32. The ephemeral private keys are destroyed right after: stealing the identity later decrypts no past call.

**Key that moves forward:** every 167 frames (about 10 s of 60 ms frames), each direction replaces its key with `HKDF-SHA256(key, info = "whisper-v1|media-ratchet")` and erases the old one. Stealing the current key does not decrypt what came before. This is a hash ratchet: it gives forward secrecy within a call, not recovery after a compromise.

The nonce is `0x00000000 ‖ seq` (8 bytes, big-endian), unique per key and direction; the AAD is the frame header. `seq` must always increase (Tor delivers in order), so a frame that does not is a replay. A receiver will not jump more than 6 key steps ahead, and tries a new key on a copy, keeping it only if the tag verifies: a forged frame with a far sequence number cannot make it lose the real key. Up to 8 Opus packets can share one frame.

---

## 6. Data on the phone

`security/PinCrypto.kt`, `security/Vault.kt`, `services/IdentityService.kt`.

```
PIN (≥ 6 digits) ──Argon2id (19 MiB, 2 passes, parallelism 1, salt 16)──▶ wrapping key
wrapping key ──AES-256-GCM (aad "whisper-vault-v1")──▶ wrapped base key (32 bytes)
the wrapped blob lives in EncryptedSharedPreferences (an AES key in the Android Keystore)

base key ──▶ opens SQLCipher (whole database file)
HKDF(base key, "whisper-v1|identity") ──▶ seals X25519 ‖ Ed25519 ‖ onion seed
```

HKDF(identity sub-key, "whisper-v1|local-copy") and HKDF(identity sub-key, "whisper-v1|inbox") derive the keys for local copies and the background inbox (section 7).

Our copy of the base key is erased once handed to SQLCipher, which keeps its own while the database is open. Private keys are in clear only in memory while unlocked. Locking means the app process is killed (`security/SessionLifecycle.kt`), which takes both with it.

PIN checks are serialized, so typing fast cannot keep the failure counter from moving forward, and attempts are slowed down after 3 failures. **10 failed PINs or the duress PIN** give exactly the same result: the background process is killed, then the sealed inbox, the database, Tor's files, the update files, the stored settings and the Keystore key are deleted. Once the wrapped key and the Keystore key are gone, any database fragment left on flash storage can no longer be decrypted. The optional "Only my face" screen shield stores the owner's face as a numeric template (128 numbers from SFace, not a photo), encrypted on the phone.

The duress PIN must have the same length as the real one, so no second stored length gives it away. It is stored as its own Argon2id hash and compared in constant time; when none is set, an Argon2id computation is still run, so unlock time does not reveal whether a duress PIN exists.

---

## 7. "Stay reachable" (app closed)

`reach/*`.

When the user enables it, a separate process (`:reach`) keeps the onion address online with the app closed. When the app leaves, it hands that process:

- the onion seed (in memory, through Binder), erased from `:reach` once Tor has published the address;
- its own X25519 public key and the inbox **public** key;
- the list of contacts that are neither blocked nor in private mode (their X25519 and Ed25519 public keys, and whether they are confirmed);
- the signed update manifest it relays, if any.

It does **not** get the identity private keys, so it cannot contact anyone; the only connection it can open is to the update distributor, to finish a download. So:

- only known contacts get through. Their HELLO signature is checked against their stored Ed25519 key; the X25519 proof cannot be checked without our private key, and is not needed for a contact whose keys are already stored;
- messages are neither verified nor decrypted. Each one is sealed for the inbox and acknowledged:

```
inbox_priv = HKDF(vault sub-key, "whisper-v1|inbox")          (only the PIN can rebuild it)
sealed     = eph_pub ‖ AES-256-GCM( HKDF(X25519(eph, inbox_pub) ‖ eph_pub ‖ inbox_pub, "whisper-v1|inbox-seal"),
                                    contact.x ‖ raw message frame )
```

Each sealed message is one file named by a sequence number (the replay counter needs arrival order), with its modification time set to 0. The file system's inode change time and the folder's date still reveal when messages arrived. At unlock, the app kills `:reach` and runs every file through the normal arrival path (section 4).

- an incoming call or nudge from a confirmed contact rings or vibrates without showing who;
- the signed update is offered and served to confirmed contacts.

The inbox is capped at 1 GB, and never more than 20% of the inbox plus free space.

---

## 8. Updates

`update/*`.

**Trust.** Two keys, both kept on the build machine, never on a phone that relays:

- the APK signing certificate (SHA-256 `3212f126…a30bc9fb`): Android refuses any APK signed by another key over Whospair;
- the developer's Ed25519 key, public part `63b2cbe96112213307648d9a8b3882b90ca7a631f7cbc4fab5265401ee8da0cd`, hard-coded in `update/UpdateManifest.kt`.

**Manifest**, signed byte for byte as it travels (at most 1,024 bytes):

```
{"app":"com.whospair","code":N,"name":"…","size":…,"sha256":"…","date":…}
signature = Ed25519(developer key, "whisper-update-v1|" ‖ manifest)
```

A phone accepts an update only if the signature verifies, `app` matches, `code` is strictly higher than the installed version, `size` ≤ 150 MB, and the downloaded APK matches `size` and `sha256`. Android then checks the APK signature at install. A phone that relays an update holds no secret: stolen or compromised, it cannot get anything accepted.

**Frames**:

| Frame | Meaning |
|---|---|
| `{"t":"upd","m":…,"s":…}` | offer: signed manifest (and optional signed release notes) |
| `{"t":"upd-get","code":N,"off":N}` | send version N from byte `off` (resume) |
| `{"t":"upd-stop"}` | cancel |
| `{"t":"upd-ask","code":N}` | to the distributor: "I have N, anything newer?" |
| `WUD1 ‖ code (8) ‖ offset (8) ‖ bytes` | data, 60 KiB chunks |

Contacts serve the APK only to **confirmed** contacts. An unconfirmed contact is only told that a version exists, without being served the file.

**Distributor.** A Whospair build that serves updates at three fixed onion addresses: the first two in random order, the third (emergency) last. While the app is open it is asked at most every 30 minutes. The request contains only the installed version code: no key, no onion address, no contact.

The distributor keeps a daily log per version: requests received, downloads finished, and the version updated from and to. It records no address (it only sees Tor circuits), no identity and no time of day. It also logs, with their time, its own onion addresses being published to the Tor network; that concerns the distributor, not users. The distributor's source code is not public. You do not need to trust it: it holds no signing key, and anything it serves goes through the checks above.

**5-day rule.** Time is counted by `update/GraceClock.kt`, which uses the time since boot and does not go backward if the phone's date is changed.

- 120 h after a phone first learns of a newer signed version, updating becomes mandatory.
- 120 h after a version is installed, it refuses to exchange anything with peers on an older version, except update frames: an outdated contact can still update through you.

---

## 9. Safety numbers

```
digest = SHA-256( sort(A.x ‖ A.ed, B.x ‖ B.ed) concatenated )
```

Both sides get the same value. It is shown as 8 groups of 5 digits (each group is 4 bytes of the digest modulo 100,000), and as a QR `whospair-verify:<digest hex>` that each side scans face to face. A match sets the contact to *verified in person*.

---

## 10. Known limits and design choices

These are deliberate or not yet addressed. Feedback on them is welcome.

- **No message ratchet.** Each message uses a fresh sender key, but the recipient's X25519 key is long-term. Someone who obtains that private key **and** a copy of the inner message frames could decrypt them. Those frames only exist inside Tor's circuit (which has forward secrecy) and, once received, inside SQLCipher. Calls do have forward secrecy (section 5).
- **Fields outside the signature.** `id` and `exp` are not part of the signed `binding`, and control frames are not signed one by one. Their integrity rests on the authenticated Tor stream: only the contact at the other end can send them, and only for its own conversation.
- **`ts` is signed but not checked against a time window.** Freshness comes from the counter, and lifetimes are recounted on the receiver's own clock.
- **"Stay reachable" keeps some things in memory with the app closed:** the onion key (inside Tor) and the list of contacts' public keys, in the `:reach` process. Someone able to dump that process's memory could learn who your contacts are, but not read any message. The arrival times of sealed messages also remain in file-system metadata until the next unlock.
- **In "Stay reachable", the acknowledgement is sent before verification.** A message that later fails the checks at unlock is dropped, although the sender saw it delivered.
- **One developer, one key.** The developer key signs every update, and every update becomes mandatory after 5 days. Whoever controls that key controls what users run. This is the price of fixing flaws fast without a store; it is the main thing to weigh before trusting Whospair.
- **Rooted phones.** A rooted phone can use the Keystore without going through the app, then attack the Argon2id layer offline; a 6-digit PIN would then fall in days. A longer PIN is the only real defence there.
- **The PIN's length is stored** (so the keypad can confirm by itself). It reveals how many digits to try, not the digits.
- **No independent audit yet.**

Report security issues privately, as described in [SECURITY.md](SECURITY.md).
