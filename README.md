# Vesper

A pocket grimoire for notes and quests. Native Android, Kotlin + Jetpack Compose + Room.
Fully offline: no accounts, no network, no analytics, no ads.

## Rules this app must never break

- Exactly five tabs: **Notes** (default), **Tasks**, **Brain** (a map of thoughts: a main brain, user-made lobes, ideas that can link to notes; lobes connect only through the main brain), **Shrine** (a private Telegram-style feed of text and photos on burned paper; photos live in `filesDir/shrine`) and **Moons** (a month calendar where each day shows its real moon phase, the Brain lobes in focus that day, and a list of plans; plans can be checked off, edited or deleted).
- A task has no completed state. Tapping its candle **deletes it permanently** — no undo, no archive, no history.
- Notes delete permanently after a confirmation. Long-press a note card in the list to delete it without opening it.
- Never add timestamps, tags, categories, search, or rich text to the UI. (Brain lobes are the one exception to "no folders"; Shrine posts show a date and time, and Moons is a calendar of dates, so dates there are allowed too.)
- Note bodies autosave on a 500 ms debounce; empty notes are never written to the database.

## Layout

```
app/src/main/java/app/vesper/
├── MainActivity.kt        app shell, tab + editor navigation, launcher shortcuts
├── ViewModels.kt          Notes / Tasks / NoteEditor (autosave, permanent delete) / Brain / Shrine / Moons
├── NotebookApp.kt         application, database singleton, error log
├── data/Database.kt       Room entities, DAOs, AppDatabase (v2 adds lobes + ideas, v3 adds Shrine posts, v4 adds Moons plans + day focus; MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
└── ui/
    ├── Pixel.kt           palette, pixel shape/panel, hand-drawn pixel icon grids
    ├── Screens.kt         backdrop, masthead, list screens, nav bar, dialogs
    ├── Brain.kt           Brain tab: pixel-brain canvas, resume sheet, idea dialogs
    ├── Shrine.kt          Shrine tab: feed, burned-paper posts, composer, photo viewer
    ├── Moons.kt           Moons tab: moon-phase month calendar, day focus, plans
    └── Editor.kt          distraction-free editor, add-task dialog
```

## Build

```powershell
cd C:\Users\davit\Desktop\Vesper
.\gradlew.bat assembleDebug      # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease    # app\build\outputs\apk\release\app-release.apk (signed, minified)
```

Release signing is configured from `keystore.properties` + `keystore/vesper-release.jks`.
**Both are git-ignored. Back them up** — losing the keystore means you can never update an
installed release build in place.

## Install on the phone

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\release\app-release.apk
& $adb shell am start -n app.vesper/.MainActivity
```

Debug and release use the same application id (`app.vesper`) but different signing keys, so
installing one over the other requires an uninstall first. Use `assembleRelease` for anything
you care about keeping.

## Launcher shortcuts

Long-press the Vesper icon: **New note** / **New task**. They fire
`app.vesper.NEW_NOTE` and `app.vesper.NEW_TASK` intents into `MainActivity`.

## Assets

Pixel art was generated externally, then keyed, palette-quantized and snapped to whole pixels
(`shots/snap_assets.py` during development; outputs live in `app/src/main/res/`). The launcher
icon is composed by `shots/build_icon.py` from the grimoire sprite.
