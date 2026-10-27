# prism-rcs Android app

Kotlin + Jetpack Compose, minSdk 29, package `dev.prism.rcs`. Licensed AGPL-3.0 like the rest of
the repository (see the [top-level README](../README.md)).

## Layout

* `rcsmobile/`: a small Go package around `pkg/libgm` that gomobile can bind (strings, bytes,
  bools, JSON, and a `Listener` callback). Its own Go module, pointing at the repository root.
* `app/`: the Android app.
  * `Repo.kt`: the Go client, the threads, messages and typing state.
  * `ChatsProvider.kt`: the chats, offered to other apps (below).
  * `SessionStore.kt`: the linked session, AES-GCM encrypted under an Android Keystore key, in
    `noBackupFilesDir` and excluded from backups. Unlinking deletes the file and the key.
  * `ui/`: a small Metro kit (`Metro.kt`, dressed by `Style.kt`) and the pages: link, Google
    sign-in, pairing, and "linked" (unlink, about).

## Building

Needs Go (1.26+, toolchain 1.27.2), gomobile, the Android SDK with an NDK, and JDK 17+.

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
```

Build the Go library into `app/libs/rcsmobile.aar` (arm64 and x86_64), then the APK:

```powershell
./build-go.ps1      # or ./build-go.sh
./gradlew assembleDebug
```

## How linking works

1. The user signs in to Google in a WebView inside the app (Google's own page). As soon as the
   messages.google.com session cookies exist, the app takes them and closes the page, then clears
   the WebView's cookies.
2. libgm starts Google-account pairing with those cookies and gets an emoji back.
3. The user taps that emoji in Google Messages on their phone. The paired session is saved
   encrypted and the app connects.

## Chats provider

Authority `dev.prism.rcs.chats`. Reading and the actions need the runtime permission
`dev.prism.rcs.permission.CHATS` (protection level "dangerous": the user grants it, as they would
SMS access). Only RCS conversations from the Google Messages inbox are offered; SMS/MMS threads
and archived ones are left out. Times are Unix milliseconds. Each URI is notified
(`ContentResolver.notifyChange`) when what it returns changes; observe `content://dev.prism.rcs.chats`
with descendants to hear about all of them.

| URI | Rows |
| --- | --- |
| `/status` | one row: `state` (`loading`, `signed_out`, `pairing`, `linked`), `connected` (0/1), `note` (text to show, or null) |
| `/threads` | newest first: `id`, `name`, `snippet`, `snippet_from`, `from_me`, `date`, `unread`, `is_group`, `read_only`, `typing` |
| `/threads/<id>/messages` | oldest first: `id`, `thread_id`, `date`, `from_me`, `sender`, `body`, `status` (`sending`, `sent`, `delivered`, `read`, `failed`, `received`), `reactions` (JSON array of `{emoji, count, from_me}`), `media` (JSON array of `{index, mime, name, width, height, has_thumb}`) |
| `/media/<message id>/<index>` | `openFile`: the attachment's bytes; add `?thumb=1` for its thumbnail |

Asking for a thread's messages starts loading them (the newest 40); a change notice follows.

Actions go through `ContentResolver.call(Uri.parse("content://dev.prism.rcs.chats"), method, arg, extras)`
and return a bundle with `ok`:

| method | arg | extras |
| --- | --- | --- |
| `send` | thread ID | `text` |
| `react` | message ID | `emoji` (the same emoji again removes it) |
| `delete` | message ID | |
| `mark_read` | thread ID | `message_id`: the newest message seen |
| `typing` | thread ID | (sent at most every 5 seconds) |
| `older` | thread ID | loads the next 40 older messages |
| `refresh` | | reloads the threads |

The link to the phone runs while the app's process does. An app that shows the chats should hold
the provider open while it's on screen (`acquireUnstableContentProviderClient`), which keeps the
process at the caller's priority.

## Opening without an animation

Action `dev.prism.rcs.OPEN` with `Intent.FLAG_ACTIVITY_NO_ANIMATION` opens the linking pages. The
activity's theme has no window animations or starting window, and it calls
`overrideActivityTransition(…, 0, 0)` (Android 14+) or `overridePendingTransition(0, 0)` on open
and close. Optional extras dress the pages in the caller's look; the last set is kept:

| extra | type | values |
| --- | --- | --- |
| `style.dark` | Boolean | dark or light theme |
| `style.accent` | Int | ARGB |
| `style.font` | String | `system`, or a Google Fonts family (`noto sans`, `open sans`, ...) |
| `style.text_scale` | Float | 1 = normal |
| `style.phone` | String | `wp7`, `wp8`, `wp81`, `w10m` |
| `style.lowercase` | Boolean | headings in lower case (otherwise sentence case) |
| `style.app_bar` | String | `RINGS_SOLID`, `RINGS_TINT`, `GLYPHS` |
| `style.app_bar_labels` | Boolean | always show the command bar's labels |
| `style.messaging` | String | `8`, `81`, `10` |
| `style.tilt`, `style.turnstile` | Boolean | press tilt, page swing |
| `style.speed` | Float | animation speed, 1 = normal |
