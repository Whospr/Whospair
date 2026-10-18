# Whospair — Security

## Reporting a vulnerability
Please do not open a public issue for a security flaw.

Report it privately through GitHub: open the "Security" tab of this
repository, then "Report a vulnerability". Only the maintainer sees the
report.

Please include the Whospair version, the Android version and phone model,
and the steps to reproduce. You will get an answer as soon as possible. A
fix is released as an update, which reaches every user over Tor; every new
version becomes mandatory 5 days after it arrives.

## Supported versions
Only the latest version is supported. Older versions stop being able to
talk to up-to-date ones 5 days after those installed the new version.

## Verifying the app
Official APKs are signed with this certificate (SHA-256):

```
3212f1262680b4cb1e2fafac1900435aa603c37047a867f93582cfa2a30bc9fb
```

Check a downloaded APK with:

```
apksigner verify --print-certs whospair-<version>.apk
```

The same fingerprint is shown in the app (Settings > About > Version). If it
differs, the app is not genuine: do not install it.

Updates are also signed with a separate developer key (Ed25519). Whospair
checks that signature before downloading an update, checks the downloaded
file against it, and Android checks the APK signature at install. A
modified app is always refused.

## What Whospair protects
- Messages, voice notes, files and calls: end-to-end encrypted
  (X25519 + AES-256-GCM, Ed25519 signatures, replay protection), carried
  over Tor between onion addresses.
- Data on the phone: encrypted (SQLCipher), its key wrapped under the PIN
  (Argon2id). Private keys are in clear only in memory, while unlocked.
- Coercion: 10 wrong PINs or the duress PIN erase everything. No biometric
  unlock.

## What Whospair does not protect
- A phone already compromised by malware or a rootkit.
- A phone someone holds while it is unlocked.
- What your contact does with what you send.
- The fact that the phone uses Tor: your network operator can see it, but
  not with whom or what.
- The camera screen shield is a privacy screen, not a lock: a photo may
  fool it. The PIN is the real protection.
