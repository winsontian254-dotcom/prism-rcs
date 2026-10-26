# prism-rcs

An RCS-only Google Messages client for Android with a Windows Phone–style "Metro" look, made to
open from Prism Launcher. It's a fork of [mautrix-gmessages](https://github.com/mautrix/gmessages)
and uses its Go protocol library, `pkg/libgm`, which speaks the Google Messages for web protocol.
The Android app lives in [`android/`](android/README.md).

## License: AGPL-3.0, source must be offered

This repository, the Android app included, is licensed under the **GNU Affero General Public
License v3.0** ([LICENSE](LICENSE)), the same as mautrix-gmessages. Anyone you give the app to
must also be offered its complete corresponding source code. The app's settings → about page
shows where; set `prismRcsSourceUrl` in [`android/gradle.properties`](android/gradle.properties)
to your published copy of this repository before distributing an APK.

The special exceptions in [LICENSE.exceptions](LICENSE.exceptions) are granted by the
mautrix-gmessages developers to Beeper and Element only. They don't apply to this fork.

Prism Launcher / Prism XR is closed source and **must never include, link or bundle any code from
this repository**. It only starts this app as a separate app, by intent (see below).

## Things to know before using it

* **Your messages go through Google.** Like Google Messages for web, your phone relays messages
  (SMS and MMS too, while linked) through Google's servers to this app.
* **It's unofficial.** The protocol isn't public. Google can change or break it at any time, and
  using a third-party client goes against Google's terms.
* **RCS only.** Plain SMS/MMS threads are hidden; Prism's own Messaging app handles those.

## Opening it from Prism Launcher

Start the activity by action, with the system's open animation turned off. The app also turns off
its own transitions, so it appears in place like a page of the launcher:

```kotlin
val intent = Intent("dev.prism.rcs.OPEN")
    .setPackage("dev.prism.rcs")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    // optional: open one thread, by its Google Messages conversation ID
    // .putExtra("conversation_id", id)
    // optional: use Prism's accent colour (ARGB Int) unless the user picked one in the app
    // .putExtra("accent", accent.toArgb())
runCatching { context.startActivity(intent) }  // ActivityNotFoundException: not installed
```

On Android 11+ Prism needs `<queries><package android:name="dev.prism.rcs" /></queries>` in its
manifest to check whether the app is installed.

## Upstream

The rest of this repository is mautrix-gmessages, a Matrix–Google Messages puppeting bridge.
Its documentation is on [docs.mau.fi](https://docs.mau.fi/bridges/go/gmessages/index.html), and
[ROADMAP.md](ROADMAP.md) lists what the bridge supports.
