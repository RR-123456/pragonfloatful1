# Pragon Mobile v13 (versionName 6.1): Rearrange, music player, phone switches by voice and panel

**Not compiled on an Android toolchain** (the build sandbox has no Android SDK). The voice phrases (`tests/V13Test.kt`, 37 cases) and the
settings page (headless browser: Rearrange, drag, move buttons, saved order, music card, tiles) were run. The Kotlin that touches Android needs
your GitHub Actions build and a test on the phone. If the build prints `e:` lines, send them over.

## Rearrange (Settings)
* **REARRANGE** button next to EXPAND ALL / COLLAPSE ALL. Groups collapse; every group gets up/down buttons and a drag handle.
  Open a group to move the sections inside it. Done / Reset order in the yellow bar. The order is saved (`pragon_layout`) and the
  chip row at the top follows it.

## Music player (`Music.kt`, group MUSIC PLAYER)
* Plays the songs on the phone (MediaStore), also with the screen off (foreground service `MusicService`, notification with previous / pause / next / stop).
* Switch "Music player" + "Allow" for the music permission. Audio focus: pauses for calls and other apps, ducks for short sounds.
* Voice: play music, pause the music, next song, previous song, stop the music, shuffle on/off, repeat one/all/off, what's playing,
  play <song> from my phone, turn on/off the music player. AI tool `music` too. "play ... on YouTube" still goes to YouTube.

## Phone switches (`QuickCatalog.kt`, `Quick.kt`, `QuickVoice.kt`)
Groups in Settings: Accessibility & convenience, Display & brightness, Sound & vibration, Notifications, Quick settings tiles.
* Direct Android settings where an app is allowed: brightness, adaptive brightness, auto-rotate, text size, screen timeout, media/ring/alarm
  volume, ring mode, Do Not Disturb, vibrate on touch, touch sounds, torch, TalkBack / Live Caption (existing switch in Settings > Security).
* Needs the one-time `adb shell pm grant ... WRITE_SECURE_SETTINGS`: colour inversion, high contrast text, remove animations, lock-screen notifications.
* Everything else (Wi-Fi, Bluetooth, mobile data/SIM, aeroplane, hotspot, location, NFC, work apps, power saving, high performance, dark mode,
  eye comfort, focus, zen space, kids mode, screen recording, screencast, QR code, song search, calculator, device controls, ... all the tiles
  from your screenshots) is done by **pressing the tile in the Quick Settings panel** through the accessibility service. Pragon opens the
  panel, finds the tile by name (swiping through the pages), reads its on/off state, presses it only if needed, and closes the panel.
  A tile must exist in your panel (pencil button).
* "clear all notifications" presses Clear all in the notification shade.
* Microphone access and camera access are privacy switches: only you can flip them (voice or the panel), never an AI tool call.
* Voice: turn on/off <name>, enable/disable <name>, toggle <name>, <name> on/off, press the <name> tile, set brightness/volume to 60,
  set ring mode to vibrate, put the phone on silent, set text size to large, set screen timeout to 30 seconds, clear notifications.
* New accessibility flag `flagRetrieveInteractiveWindows` (needed to read the Quick Settings window).

## Files
New: `Music.kt`, `Quick.kt`, `QuickCatalog.kt`, `QuickVoice.kt`, `tests/V13Test.kt`. Changed: `CommandExecutor.kt`, `Router.kt`, `Tools.kt`,
`ToolCatalog.kt`, `Brain.kt`, `PragonAccessibilityService.kt`, `SettingsActivity.kt`, `settings.html`, manifest, accessibility config.

## Limits you should know
* Tile names differ per phone maker; if a tile is not found, Pragon says so. Some tiles open their own screen instead of switching (screen recording, QR code).
* Android does not let any normal app switch Wi-Fi, mobile data, hotspot or aeroplane mode directly (since Android 10), which is why those use the tile.
* Silent mode and Do Not Disturb need "Do Not Disturb access" for Pragon (the panel shows a button).
