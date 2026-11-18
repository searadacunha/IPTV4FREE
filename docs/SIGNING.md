# Signing the APKs installed on the TV

Android installs an update only when it is signed by the same key as the installed app. A new key means uninstalling
first, which erases the app's favourites, history and settings: HUB, WATCHED and CINEFUSE TV set
`android:allowBackup="false"`, are not debuggable and target API 34, so neither `adb backup` nor `run-as` can save
their data (see [Android 12 adb backup restrictions](https://developer.android.com/about/versions/12/behavior-changes-12#adb-backup-restrictions)).

## The key

- Builds read an optional file **outside the repository**: `~/.config/streaming4free/signing/signing.properties`
  (or the path in `S4F_SIGNING`):

  ```properties
  storeFile=/Users/<you>/.config/streaming4free/signing/<keystore>.p12
  storePassword=...
  keyAlias=...
  keyPassword=...
  ```

- `signing/release-cert.sha256` holds the SHA-256 of the release certificate (public, not a secret). It is
  `27cf445d…`, the key that signed the HUB, WATCHED and CINEFUSE TV installed on the TV.
- `hub/` (Gradle), `build.sh` (WATCHED) and `cinefuse/build.sh` (both through `tools/signing.sh`) sign with that key
  and **stop the build** when the key file is missing or its certificate does not match the expected SHA-256.
- The computer's debug key is accepted only for an explicit test build (`S4F_TEST_BUILD=1`) or in CI (`CI=true`, set
  by GitHub Actions). Such an APK cannot update the apps on the TV: Android refuses it.
- Moving to another release key is a decision, not a build setting: it means reinstalling the apps on the TV and
  losing their data. Change `signing/release-cert.sha256` only then.
- Keystores, `.p12`, `.jks` and `signing.properties` are ignored by git. Never commit them, never upload them.
- Keep at least one copy of the keystore and its password **off the computer** (an encrypted external disk or a
  password manager). A key that exists in one place only is lost with that place.

## What happened in November 2026

The apps installed on 2026-11-11 were signed by the Mac's debug key (certificate SHA-256 `27cf445d…`). On 2026-11-17
a disk cleanup archived `~/.android` to an external disk and deleted it; the next build created a new debug key
(`9bae35a6…`). HUB 1.1 could therefore not update HUB 1.0 in place and was installed next to it as
`com.local.streaming4free.test`, signed by a new stable key (`aef9b8cd…`).

## Replacing an app signed by another key

1. If the old key can be recovered, sign the new version with it: `adb install -r` then keeps every setting.
2. Otherwise nothing can copy the old app's private data. Before uninstalling it, write down what the screen shows
   (Favorites, Continue watching, Settings), install the new version, re-add them by hand, and only then remove the old
   app. Resume positions and the 24-hour dead-stream marks are not shown anywhere and are lost.
