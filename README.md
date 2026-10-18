# Whospair

**Private by design.** A serverless messenger for Android. Messages, voice notes, files and calls go straight from one phone to the other, over Tor. No account, no phone number, no server that keeps anything.

> What is said in Whospair exists only on the two phones, for the time you chose, and nowhere else.

## [Download Whospair 1.3.9 (APK, 39 MB)](https://github.com/Whospr/Whospair/releases/latest/download/whospair-1.3.9.apk)

Android 10 or later (arm64) · Not on the Play Store · [All releases](https://github.com/Whospr/Whospair/releases) · [Check the app before installing](#install) · [Donate](https://liberapay.com/Whospair/donate)

---

## Why Whospair

Most messengers encrypt your messages but still route them through a company's servers, tied to your phone number. Whospair removes the server altogether.

- **No server, no account.** Your identity is a key pair created on your phone. There is no phone number, no e-mail and no username.
- **Everything goes over Tor.** Each phone gets its own onion address. Nobody, including us, sees who talks to whom.
- **Nothing is collected.** No analytics, no ads, no crash reports sent anywhere, no Google code (no Play Services, no Firebase).
- **Nothing to hand over.** Since nothing is stored anywhere else, there is nothing anyone could be forced to give away.

## Features

### Encryption
- End-to-end encryption: X25519 + AES-256-GCM, every message signed with Ed25519, with replay protection.
- Signed handshake on every connection: you know you reached the right contact.
- Voice calls encrypted with a key that changes about every ten seconds.
- Local storage encrypted with SQLCipher, its key wrapped under your PIN (Argon2id).
- Private keys sealed on disk, in clear only in memory while the app is unlocked. The Tor key is derived from a seed sealed with them; Tor itself never writes it to disk.
- Received files are opened in memory only; they are written in clear only if you save them (when the sender allows it).

### Lock and coercion
- Mandatory PIN with the app's own keypad. No fingerprint or face unlock: you can be forced to touch a sensor, not to reveal a code.
- 10 wrong PINs erase everything.
- **Duress PIN:** a second PIN that erases everything when entered.
- The app locks as soon as it goes to the background. A quick-settings tile locks it in one gesture.
- Screenshots and app-switcher previews are blocked.

### Contacts and trust
- Add someone by scanning their QR code face to face, or with a `whospair:` link.
- Three trust levels: *to confirm*, *confirmed*, *verified in person* (security codes scanned face to face).
- Safety number to compare out loud. Block, rename (locally), delete.
- Optional profile photo, resized and stripped of metadata, with a visibility setting.

### Messages
- Lifetime chosen when sending: from 5 minutes to 7 days, or never. Expired messages disappear on both sides.
- Read receipts, replies, copy, emoji, delete for everyone.
- **Two-party erase:** if both agree, either one can wipe the whole conversation on both phones at any time.
- Pin conversations to the top.

### Voice, files, calls
- Voice notes (Opus).
- Files up to 25 MB, with *view once* and *allow saving* options, a built-in viewer, and a notice to the sender when a file is forwarded, shared or saved.
- Voice calls over Tor between confirmed contacts.

### Presence
- Strictly live: a message is delivered when your contact has the app open, or has *Stay reachable* on. Never "last seen".
- *Nudge*: make a contact's Whospair vibrate, with no content.
- Private mode per conversation.
- **Stay reachable (optional):** with the app closed, your address stays online. Incoming messages are sealed so that the background service can store them but never read them; only your PIN opens them.

### Privacy on screen
- Phone face down on the table: the screen goes black.
- Optional front-camera shield, processed entirely on the phone: hides the screen when you look away, when someone else looks, or when it is not your face. A PIN always gets you out of it (pause, until morning, or off), and the look-away mode can switch itself off at night.
- Per-conversation reading code: messages stay hidden until the code is entered.

### Updates without a store
- Updates travel from contact to contact, or from the update distributor, over Tor. No Play Store.
- Each version is signed by the developer. Whospair checks the signature before downloading, then the file, and Android checks the APK signature at install.
- Downloads resume where they stopped, and continue with the app closed when *Stay reachable* is on.
- Every new version becomes mandatory 5 days after your phone learns of it, and an up-to-date app stops talking to older versions 5 days after its own install: an older version may contain a flaw.

### Interface
- Sober design, day and night modes, 6 conversation backgrounds.
- 21 languages, including right-to-left (Arabic, Persian, Hebrew).
- Atkinson Hyperlegible font, bundled. No font, image or icon is loaded from the internet.

## Install

Whospair is not on the Play Store. **[Download the latest APK](https://github.com/Whospr/Whospair/releases/latest)** from the Releases page, then check that it really is the official app before installing.

**Signing certificate (SHA-256):**

```
3212f1262680b4cb1e2fafac1900435aa603c37047a867f93582cfa2a30bc9fb
```

The same fingerprint is shown in the app, under *Settings › About › Version*. If it differs, it is not the real app: do not install it.

You can check a downloaded APK with:

```
apksigner verify --print-certs whospair-1.3.9.apk
```

Once installed, later updates arrive by themselves over Tor.

## Build from source

Requirements: JDK 17, Gradle 8.9, Android SDK 34, Python 3. The Android project is in `WhospairApp/`.

```
cd WhospairApp
python3 tools/tor-aar.py       # fetches and checks the tor-android library
python3 tools/face-models.py   # fetches the face models (pinned SHA-256)
gradle :app:testPublicDebugUnitTest
gradle :app:assemblePublicDebug
```

The native face module (`native/face`) is not built by Gradle: a source build has no "Only my face" mode.

Official release APKs (R8, arm64) are built and signed by the developer. The signing key is not part of this repository.

## Limits

Whospair protects what you say in transit and on the phone. It does not protect a phone that is already compromised by malware, or that someone holds unlocked in their hands. Your network operator can see that the phone uses Tor, but not with whom or what. Read [SECURITY.md](SECURITY.md) and [PRIVACY.md](PRIVACY.md).

## Third-party software

Tor (through tor-android), SQLCipher, Bouncy Castle, ZXing, AndroidX, Dagger/Hilt, Kotlin libraries, OpenCV, and the YuNet and SFace models. Each one keeps its own license; full texts are in `app/src/main/assets/licenses/` and in the app.

## Support Whospair

Whospair is built by one developer, with no investor, no ads and no data to sell. Donations pay for the update distributor, the build servers and the time spent fixing flaws quickly.

<a href="https://liberapay.com/Whospair/donate"><img alt="Donate using Liberapay" src="https://liberapay.com/assets/widgets/donate.svg"></a>

Donations go through [Liberapay](https://liberapay.com/Whospair/donate), a non-profit platform. Whospair itself never knows who donated: no account in the app, nothing linked to your identity in Whospair.

## License

Whospair is **source-available, not open source**: the code is public so that anyone can read and audit it, under the [PolyForm Strict License 1.0.0](LICENSE.md). It may not be redistributed or modified. Anyone may use the official app, for any purpose. The name "Whospair" and its logo are not licensed. Third-party components keep their own licenses: see [THIRD-PARTY-NOTICES.txt](THIRD-PARTY-NOTICES.txt).
