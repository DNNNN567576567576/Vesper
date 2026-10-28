# Vesper

A pocket grimoire for notes and quests. Native Android, Kotlin + Jetpack Compose + Room.
Fully offline: no accounts, no network, no analytics, no ads.

## Rules this app must never break

- Exactly two tabs: **Notes** (default) and **Tasks**.
- A task has no completed state. Tapping its ring **deletes it permanently** — no undo, no archive, no history.
- Notes delete permanently after a confirmation. Long-press a note card in the list to delete it without opening it.
- Never add timestamps, tags, folders, categories, search, or rich text to the UI.
- Note bodies autosave on a 500 ms debounce; empty notes are never written to the database.

## Layout

```
app/src/main/java/app/vesper/
├── MainActivity.kt        app shell, tab + editor navigation, launcher shortcuts
├── ViewModels.kt          Notes / Tasks / NoteEditor (autosave, permanent delete)
├── NotebookApp.kt         application, database singleton, error log
├── data/Database.kt       Room entities, DAOs, AppDatabase
└── ui/
    ├── Pixel.kt           palette, pixel shape/panel, hand-drawn pixel icon grids
    ├── Screens.kt         backdrop, masthead, list screens, nav bar, dialogs
    ├── Editor.kt          distraction-free editor, add-task dialog
    └── MascotSprite.kt    generated mascot grid (see tools/gen-mascot.ps1)
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
