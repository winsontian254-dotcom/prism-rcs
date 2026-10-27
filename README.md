# prism-rcs

> **This is a fork** of [mautrix/gmessages](https://github.com/mautrix/gmessages), published at
> [github.com/winsontian254-dotcom/prism-rcs](https://github.com/winsontian254-dotcom/prism-rcs) under the
> same AGPL-3.0 license. All credit for the Google Messages protocol library (`pkg/libgm`) goes to the
> mautrix-gmessages developers; this fork adds the Android app in `android/`.

"RCS chats" is a small Android app that links to Google Messages on your phone and makes your RCS
conversations available to other apps through a content provider, the way Android makes texts
available through its SMS provider. It isn't a chat app of its own: Prism Launcher's Messaging
reads the provider and shows the RCS chats alongside your texts. The app itself only has pages
for linking, unlinking and its about page. It uses mautrix-gmessages' Go protocol library,
`pkg/libgm`, which speaks the Google Messages for web protocol. See [`android/`](android/README.md).

## License: AGPL-3.0, source must be offered

This repository, the Android app included, is licensed under the **GNU Affero General Public
License v3.0** ([LICENSE](LICENSE)), the same as mautrix-gmessages. Anyone you give the app to
must also be offered its complete corresponding source code. The app's about page shows where;
set `prismRcsSourceUrl` in [`android/gradle.properties`](android/gradle.properties) to your
published copy of this repository before distributing an APK.

The special exceptions in [LICENSE.exceptions](LICENSE.exceptions) are granted by the
mautrix-gmessages developers to Beeper and Element only. They don't apply to this fork.

Prism Launcher / Prism XR is closed source and **must never include, link or bundle any code from
this repository**. It talks to this app only as a separate program: it reads the documented
provider (with the user's permission, like any other app could) and starts the app by intent.

## Things to know before using it

* **Your messages go through Google.** Like Google Messages for web, your phone relays messages
  (SMS and MMS too, while linked) through Google's servers to this app.
* **It's unofficial.** The protocol isn't public. Google can change or break it at any time, and
  using a third-party client goes against Google's terms.
* **RCS only.** Plain SMS/MMS threads are left out; Prism reads those from Android itself.
* **Only while something's reading.** The link runs while an app holds the provider open (Prism's
  Messaging does while it's showing). There are no notifications for new RCS messages yet.

## Using it from another app

The provider, its permission and its actions are described in
[android/README.md → Chats provider](android/README.md#chats-provider). To link or unlink, start
the app by action, with the system's open animation off. The app also turns off its own
transitions, so it looks like a page of the caller:

```kotlin
val intent = Intent("dev.prism.rcs.OPEN")
    .setPackage("dev.prism.rcs")
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    // optional "style.*" extras dress its pages in the caller's look (android/README.md)
runCatching { context.startActivity(intent) }  // ActivityNotFoundException: not installed
```

The app has no app-list icon, so on Android 11+ a caller needs
`<queries><package android:name="dev.prism.rcs" /></queries>` to see it.

## Upstream

The rest of this repository is mautrix-gmessages, a Matrix–Google Messages puppeting bridge.
Its documentation is on [docs.mau.fi](https://docs.mau.fi/bridges/go/gmessages/index.html), and
[ROADMAP.md](ROADMAP.md) lists what the bridge supports.
