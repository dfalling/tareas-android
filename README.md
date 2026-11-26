# Tareas for Android

Native Android client for [tareas](https://github.com/dfalling/tareas), a
self-hosted todo webapp. Kotlin + Jetpack Compose, Material 3, offline-first.
Distributed through GitHub Releases (works with
[Obtainium](https://github.com/ImranR98/Obtainium)) — no Google Play Services
dependency.

## Using it

- **Sign in** with your server URL, email, and password (the first account is
  created on the server's `/setup` page). The token is stored encrypted with an
  Android Keystore key and never backed up. On a rejected token you're returned
  to the login screen with your data intact; signing in again resumes sync.
- **Four tabs**, mirroring the web app: **Today** (due today or overdue),
  **Upcoming** (grouped by exact due date, "Tomorrow" header for the next day),
  **Unscheduled** (no due date), **Completed** (newest first). Alphabetical
  everywhere else. Dates render as `dd-MM-yy` in the device language.
- **Create and edit** with the ✏ button or by tapping a row: title, notes, due
  date (Material date picker). Complete/reopen with the round checkbox; delete
  asks for confirmation because it's permanent — on this device and on the
  server (tareas has no trash).
- **Offline-first**: every change lands in the local database instantly and
  syncs when a connection is available. Rows waiting to sync carry a subtle
  cloud marker, and the top bar shows when the last sync succeeded. Pending
  changes beat server state on conflicts; server deletions always win.
- **Share target**: share text from any app (browser, messenger, …) and Tareas
  opens a prefilled new-todo sheet — share subject becomes the title, the text
  becomes the notes (or the first line becomes the title when there's no
  subject).
- **App language**: follows the phone's language; English and Spanish are
  included, anything else falls back to English.
- **Account**: the top-bar menu shows the signed-in account, server, and app
  version, and logs out (which revokes the token server-side).

## How sync works

The server has no sync endpoint — no ETags, versions, or tombstones — so the
app runs a push-then-pull cycle on app start, after every local change, on
pull-to-refresh, and whenever connectivity returns (WorkManager):

1. **Push** locally queued operations in the order they were made: creates
   (`POST`), edits (`PATCH` with only the changed fields), deletes (`DELETE`;
   a 404 counts as deleted). Anything that fails stays queued.
2. **Pull** the full todo list and merge it: rows waiting to sync keep their
   local state (your most recent intent wins), everything else takes the
   server's version, and items the server no longer has are removed locally.

The whole merge is pure and unit-tested; an integration test runs a real
sync cycle against a live server when one is available.

## Server API notes

`/api/v1` with `Authorization: Bearer <token>`; errors are always
`{"error":"…"}`; unknown JSON fields in requests are rejected (400).

| Method & path | Notes |
|---|---|
| `POST /auth/login` | `{"email","password"}` → `{"token"}`; 429 after 10 attempts / 5 min |
| `POST /auth/logout` | revokes the presented token (idempotent) |
| `GET /todos?includeCompleted=true` | flat array of todos — the only read |
| `POST /todos` | `{"title","notes"?,"due_date"?}` → 201 + todo |
| `PATCH /todos/{id}` | partial: `title`, `notes` (null clears), `due_date` (null clears), `completed`; absent = untouched |
| `DELETE /todos/{id}` | 204; 404 if gone (deletes are immediate, and an hourly job can purge old completed items) |

A todo is `{"id", "title", "notes", "due_date" ("YYYY-MM-DD" or null),
"completed", "completed_at", "created_at", "updated_at"}` with RFC3339
timestamps. Limits: title ≤ 500 characters, notes ≤ 10 000. Login-issued
tokens don't expire but are revocable.

## Setup

1. Install [mise](https://mise.jdx.dev), then run `mise install` (JDK 25, sops
   and age, pinned in `mise.toml`).
2. Install the Android SDK with platform 37 and point `local.properties` at it
   (`sdk.dir=/path/to/Android/Sdk`), or open the project in Android Studio.
3. `git config core.hooksPath .githooks` runs lint and unit tests before each push.

```sh
./gradlew installDebug                                     # run on a device/emulator
./gradlew lintDebug testDebugUnitTest assembleDebug        # what CI runs
```

Debug builds allow cleartext HTTP so they can talk to a LAN/emulator server
(`http://10.0.2.2:8080` from the emulator is the host's localhost).

### Emulator (headless, scriptable)

```sh
scripts/emulator.sh                 # boot the Pixel_7 AVD headless and wait for it
scripts/emulator.sh --window        # or with a normal window, to use it by hand
./gradlew installDebug
adb exec-out screencap -p > screen.png
scripts/emulator.sh --stop
```

For a local test server: `cd ~/Code/tareas && docker compose up` (web at
`http://localhost:8080`, first account via `/setup`).

## Releasing

`.github/workflows/release.yml` builds a signed APK on every push to `main`
(versioned `0.0.<commit count>`, published as a pre-release) and on every `v*`
tag (versioned from the tag). Release runbook for a real version:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

CI then decrypts the signing secrets, builds `tareas-0.1.0.apk`, and attaches
it to a GitHub Release. `versionCode` is the total commit count (needs full
history — CI uses `fetch-depth: 0`), so it only goes up; Obtainium picks up
new `v*` releases automatically. There are no in-app updates.

## Signing

Signing material lives in `release-secrets.enc.yaml`, encrypted with
[SOPS](https://getsops.io) to the age recipient in `.sops.yaml`. The only
GitHub Actions secret is `SOPS_AGE_KEY` (the age private key). The keystore
itself is kept out of the repo at `~/.config/tareas-android/release.keystore`.

This encrypted file is the only copy of the signing key. If it's lost, existing
installs can't take updates, so keep the age key backed up.

```sh
sops release-secrets.enc.yaml    # view or edit (needs the age key in ~/.config/sops/age/keys.txt)
```

Local signed build, the same way CI does it:

```sh
RELEASE_STORE_FILE=~/.config/tareas-android/release.keystore \
RELEASE_VERSION_NAME=0.0.0-local \
RELEASE_VERSION_CODE=0 \
RELEASE_STORE_PASSWORD=$(sops -d release-secrets.enc.yaml | jq -r .android_keystore_password) \
RELEASE_KEY_PASSWORD=$(sops -d release-secrets.enc.yaml | jq -r .android_key_password) \
RELEASE_KEY_ALIAS=$(sops -d release-secrets.enc.yaml | jq -r .android_key_alias) \
./gradlew assembleRelease
```

Without those variables, `assembleRelease` signs with the debug key. That's
useful for checking that R8 minification still works.

## Repo layout

```
app/src/main/java/com/dfalling/tareas/
  MainActivity.kt, TareasApp.kt    entry point, manual dependency wiring
  data/        Room entity/DAO, DTOs + JSON parsing, API client, DataStore
               settings, Android-Keystore token cipher
  domain/      Tabs grouping/sorting (port of the webapp's internal/todos/tabs.go)
  sync/        SyncCoordinator (push queue, pull merge), SyncWorker
  ui/          Compose screens, todo sheet, share-intent draft handling
app/schemas/                       exported Room schemas
gradle/libs.versions.toml          dependency versions
release-secrets.enc.yaml           SOPS-encrypted signing passwords
```
