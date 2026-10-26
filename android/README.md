# prism-rcs Android app

Kotlin + Jetpack Compose, minSdk 29, package `dev.prism.rcs`. Licensed AGPL-3.0 like the rest of
the repository (see the [top-level README](../README.md)).

## Layout

* `rcsmobile/`: a small Go package around `pkg/libgm` that gomobile can bind (strings, bytes,
  bools, JSON, and a `Listener` callback). Its own Go module, pointing at the repository root.
* `app/`: the Android app.
  * `Repo.kt`: the Go client, the threads, messages and typing state.
  * `SessionStore.kt`: the linked session, AES-GCM encrypted under an Android Keystore key, in
    `noBackupFilesDir` and excluded from backups. Unlinking deletes the file and the key.
  * `ui/`: the Metro kit (`Metro.kt`) and the pages: link, Google sign-in, pairing, threads, a
    conversation, forward, settings.

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

## What's in it

* "threads": RCS conversations only (conversation type RCS; SMS/MMS threads are left out),
  newest first, the time at the right, unread names in the accent.
* A conversation: square bubbles (theirs in the accent with the tail at the top left, yours darker
  with the tail at the bottom right), the message box pinned at the bottom, images shown inline,
  "typing…", read receipts sent for what you've seen, a typing indicator sent while you type.
* Hold a message: react (Google Messages' six reactions), copy, forward, delete. Reactions show as
  a small bubble above the message.
* Settings: accent colour, unlink, and the about page with the source code location.

Not done yet: notifications and staying connected in the background (the app only receives
messages while it's running), starting new conversations, sending replies to a specific message.

## Opening without an animation

See the top-level README: action `dev.prism.rcs.OPEN` with `Intent.FLAG_ACTIVITY_NO_ANIMATION`.
The activity's theme has no window animations or starting window, and it calls
`overrideActivityTransition(…, 0, 0)` (Android 14+) or `overridePendingTransition(0, 0)` on open
and close.
