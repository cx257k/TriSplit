# TriSplit

Three-column multitasking for the Galaxy Z Fold 7 (One UI 9 / Android 17).

One UI on the Fold 7 splits the screen into two. TriSplit opens three apps side by side as pop-up (freeform) windows, tiled into columns, and keeps them behaving like one split screen: draggable dividers, swapping, rotation, the pop-up tray after going home, and hand-over to native split screen.

> Unofficial and experimental. It relies on hidden Android and One UI behaviour through [Shizuku](https://shizuku.rikka.app/), so a system update can break it.

## Features

- **Three columns:** pick apps for the left, centre and right columns, or open a saved preset.
- **Completing a native split:**
  - In a native split, pick TriSplit as the other side, then choose the remaining apps. The split becomes three columns.
  - The apps already on screen stay as they are, including a third app placed in the top or bottom of one side.
- **Divider handles:**
  - Drag to resize two neighbouring windows together.
  - Tap for a swap button; double-tap for equal widths.
  - Apps' own minimum widths are respected.
- **Rotation and the taskbar:** the columns are re-tiled when the screen rotates and when the taskbar appears or hides. Widths can be remembered per combination of apps.
- **One UI integration:**
  - **Going home:** tapping one app in the pop-up tray restores all three windows.
  - **One window closed:** the other two move to native split screen.
  - **Full screen or split from a window menu:** making one window full screen or split screen closes the other two.
  - **Pop-ups opened over the columns:** they can be brought back with a chip when they end up behind the columns.
  - **Front (cover) screen:** three columns are disabled there.
- **Quick access:**
  - Presets, with home-screen shortcuts.
  - Recently opened combinations.
  - A Quick Settings tile that reopens the last combination.
  - Notification actions: equal widths, save as preset, close all.
- **Languages:** English, Japanese, Simplified Chinese and Traditional Chinese, chosen in the app or in the system's per-app language settings. Other apps' names always follow the system language.

## How it works

- `SplitService` runs as a Shizuku user service (shell uid). It calls hidden `IActivityTaskManager` APIs and `WindowContainerTransaction` through reflection to:
  - start apps as freeform windows at given bounds;
  - resize them;
  - move tasks out of native split screen;
  - read task state.

  It also fixes pop-ups that would otherwise reopen at a column's narrow size later.
- `DividerOverlayService` draws the handles as overlay windows. It polls the windows' state and reacts to:
  - rotation;
  - the pop-up tray;
  - closed or promoted windows;
  - edge resizes made with One UI's own controls.
- `TripleLauncher` places the windows: column sizes, minimum widths, remembered widths, and completing a native split.
- `TripleLayout` holds the column and divider math. It is unit-tested.

## Requirements

- A Galaxy Z Fold 7 or a similar large-screen Samsung device on One UI 9.
- [Shizuku](https://shizuku.rikka.app/), started over wireless debugging. Shizuku has to be started again after each reboot.
- The developer options "Enable freeform windows" and "Force activities to be resizable". TriSplit can switch these on.

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open TriSplit and allow:

- Shizuku access;
- notifications;
- "Display over other apps".

Choose three apps and tap **Launch in three columns**.

## Tests

```bash
./gradlew testDebugUnitTest
```

## Known limitations

- Each window keeps One UI's pop-up caption handle.
- A column is at least One UI's minimum pop-up width (220 dp), or wider if the app asks for it.
- Choosing the same app for two columns only works for apps that can open a second window of themselves.
- One UI ignores requests to keep another pop-up above the columns. TriSplit offers a chip to bring it back instead.

## License

[MIT](LICENSE)
