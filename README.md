# Vesper

A private, offline pixel-art notebook for Android and Windows, with optional end-to-end encrypted sync through your own Google Drive.

No account, no server, no ads, no analytics. Nothing leaves your device until you choose to sync.

## Tabs

| Tab | What it does |
| --- | --- |
| **Brain** | One brain you think in. Portals open worlds (sub brains), groups are constellations, notes are stars. Search finds a star and flies to it. The vault heart makes and restores backups. |
| **Learn brain** | A brain of goals. Each goal is a moon, each goal has steps with days, and a strip shows what is due today. |
| **Moons** | A moon-phase calendar with plans, your learn steps, and alarms (one-time with history, or repeating) on Android. |
| **Grimoire** | A private feed of text, photos and video on burned paper. |
| **Calc** | A small calculator. |

## Install

Download the APK from the [Releases](../../releases) page and open it on your phone (allow installs from your browser if asked). The Windows build is not published.

## Sync and privacy

Sync is off by default. When you turn it on, everything is encrypted on your device with a passphrase only you know, then stored in the hidden app folder of your own Google Drive. Vesper never sees your other Drive files. See [PRIVACY.md](PRIVACY.md).

## Build from source

Requires JDK 17+ and the Android SDK. On Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest     # unit tests
.\gradlew.bat :app:assembleDebug         # app\build\outputs\apk\debug
.\gradlew.bat :app:assembleRelease       # signed, minified (needs keystore.properties)
.\gradlew.bat :desktopApp:run            # Windows desktop app
```

Release signing reads `keystore.properties` and `keystore/`, both git-ignored. Back them up: without the keystore an installed release can never be updated in place.

## Project layout

```
shared/       Kotlin Multiplatform + Compose UI, database (Room), sync engine
  src/commonMain/kotlin/app/vesper/ui/   screens: Brain, Learn, Moons, Alarms, Shrine, Calculator
  src/commonMain/kotlin/app/vesper/sync/ encrypted Drive sync
  src/commonMain/composeResources/       pixel art and fonts
app/          Android shell: activity, alarms and ringing service, backups, Drive sign-in
desktopApp/   Windows shell (Compose Desktop)
```

Fonts are bundled under their open licenses in [licenses/](licenses).
