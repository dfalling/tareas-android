# Tareas for Android

Native Android client for [tareas](https://github.com/dfalling/tareas), a
self-hosted todo app. Kotlin + Jetpack Compose, offline-first, distributed
through GitHub Releases (works with [Obtainium](https://github.com/ImranR98/Obtainium)).
No Google Play Services dependency.

- **Offline-first** — every change is saved locally and synced to the server when a
  connection is available. Items not yet synced carry a subtle indicator, and the
  last successful sync time is shown in the top bar.
- **Share target** — share text from any app to create a todo.
- **Same views as the web app** — Today (due today or overdue), Upcoming (grouped by
  date), Unscheduled, Completed; alphabetical everywhere.

## Install

Add `https://github.com/dfalling/tareas-android` as a GitHub-source app in
Obtainium, or grab an APK from the
[Releases](https://github.com/dfalling/tareas-android/releases) page. On first
launch, enter your tareas server URL, email, and password.

## Build

Prerequisites: [mise](https://mise.jdx.dev) (`mise install` for JDK/sops/age) and
an Android SDK with platform 37. Point `local.properties` at it:

```properties
sdk.dir=/home/you/Android/Sdk
```

Debug builds allow cleartext HTTP so you can point them at a LAN/emulator server.

```sh
./gradlew installDebug                                # run on a device/emulator
./gradlew lintDebug testDebugUnitTest assembleDebug   # what CI runs
```

## Release

- Every push to `main` ships a `0.0.<commit-count>` pre-release.
- Tagging ships a real release: `git tag v0.1.0 && git push origin v0.1.0`.

`versionCode` is the total commit count; `versionName` is the tag minus `v`.

## Signing

Signing material lives in `release-secrets.enc.yaml`, encrypted with
[SOPS](https://getsops.io) to the age recipient in `.sops.yaml`. The only GitHub
Actions secret is `SOPS_AGE_KEY` (the age private key). The keystore itself is kept
out of the repo at `~/.config/tareas-android/release.keystore`.

For a local signed build:

```sh
export SOPS_AGE_KEY=$(grep 'AGE-SECRET-KEY-' ~/.config/sops/age/keys.txt | tail -1)
RELEASE_STORE_FILE=~/.config/tareas-android/release.keystore \
RELEASE_VERSION_NAME=0.0.0-local \
RELEASE_VERSION_CODE=0 \
RELEASE_STORE_PASSWORD=$(sops -d release-secrets.enc.yaml | jq -r .android_keystore_password) \
RELEASE_KEY_PASSWORD=$(sops -d release-secrets.enc.yaml | jq -r .android_key_password) \
RELEASE_KEY_ALIAS=$(sops -d release-secrets.enc.yaml | jq -r .android_key_alias) \
./gradlew assembleRelease
```

Without those variables, `assembleRelease` falls back to the debug keystore so it
still works as an R8 smoke test.
