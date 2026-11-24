# Vesper

Vesper is a private pixel-art notebook for Android and Windows. It is dark, purple and a little eerie: your notes live in a living brain, your daily habits are candles, your goals are moons.

There is no account, no server, no ads and no analytics. Everything stays on your device unless you turn on sync, and sync is end-to-end encrypted through your own Google Drive.

## What's inside

### Brain

The Brain tab is the heart of the app: one big main brain you can pan and zoom.

- **Notes** grow around the main brain. Tap one to open it as a card with a title, text and an optional link. Notes can be sorted into groups and moved between brains.
- **The pinned board** under the main brain holds the notes you want to keep in sight.
- **Tasks** are little lanterns round their own small brain, one for each thing to do.
- **Learn** is a small brain of goals, each one a moon circling it. A goal you haven't touched in a while slowly dims.
- **Daily** is a small brain of candles, one per habit. Light a candle when you've done it today; they go dark again at midnight. Its stats show how your days went.
- **Bible** is a free canvas of cards (people and things) joined by labelled lines, for mapping out stories and relationships.
- **The vault heart** makes a backup file of everything and restores from one (Android).
- **Search** finds any note and flies the map to it.

You can also share text from other apps into Vesper and pick where to keep it.

### Grimoire

A private feed of text, photos and videos, kept on burned paper. Grimoire posts stay on the device they were made on and are not synced.

### Moons

A moon-phase calendar. Open any day to see which Daily candles you lit, so you can look back at how past days went.

### Alarms (Android only)

Simple alarms, one-time or repeating, with a history of the ones that already rang.

### Calc

A small pixel calculator.

## Sync between phone and PC

Sync is optional and off until you turn it on. Vesper then keeps your phone and PC in step through a hidden app folder in **your own** Google Drive:

- Before anything is uploaded it is encrypted on your device with a passphrase you choose. Neither Google nor anyone else can read it, and the passphrase is never stored or sent anywhere.
- Vesper only gets access to its own hidden folder (the `drive.appdata` permission). It can't see any of your other Drive files.
- Edits made on two devices at once are kept: if both changed the same note, you get both versions instead of losing one.

## Privacy

Vesper collects nothing and sends nothing to its developer. The only notifications it shows are ringing alarms; there are no reminders and no sounds other than the alarm itself. See [PRIVACY.md](PRIVACY.md) for the details.

## Install (Android)

1. Open the [latest release](../../releases/latest) and download the `.apk` file.
2. Open it on your phone. If asked, allow installing apps from your browser or file manager.

Vesper needs Android 8.0 or newer. A newer release installs over an older one and keeps your data.

There is no Windows download yet; the desktop app can be built from source.

## Build from source

You need JDK 17 or newer and the Android SDK.

```sh
./gradlew :app:testDebugUnitTest      # tests
./gradlew :app:assembleDebug          # debug APK in app/build/outputs/apk/debug
./gradlew :desktopApp:run             # run the Windows desktop app
```

A release build (`:app:assembleRelease`) is signed with your own key from `keystore.properties`, which is never committed. Google Drive sync in a self-built app needs your own Google OAuth client.

The code is Kotlin with Compose Multiplatform:

```
shared/       the app itself: UI, database (Room) and the encrypted sync engine
app/          the Android shell: alarms, backups, Google sign-in
desktopApp/   the Windows shell
```

The bundled pixel fonts are used under their open font licenses, see [licenses/](licenses).
