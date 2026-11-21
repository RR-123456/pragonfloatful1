# Pragon Mobile v16 (versionName 6.3): LOOK & GLOW inside the Smart Sidebar panel

**Not compiled on an Android toolchain** (no Android SDK in the build sandbox). The preview page (`preview/Pragon_Smart_Sidebar_v3_preview.html`) was run in a headless
browser: every control clicked, no script errors. The Kotlin (`Glow.kt`, `Sidebar.kt`) needs your GitHub Actions build and a test on the phone;
if the build prints `e:` lines, send them over.

## What changed
The row of buttons that sat above the phone in the preview (Open / close, Switch side, Music on/off, Border glow, Haze glow, Ambient glow, the four colour dots,
Play/Pause video, Match video colour, Sound reactive) is now a **LOOK & GLOW** section inside the panel, below MACROS (preview: bottom of the Macros tab).
The top bar is gone from the preview.

| Button | What it does on the phone |
|---|---|
| ✕ Close panel | closes the panel (same as the ✕ at the top) |
| ⇄ Switch side | moves handle and panel to the other edge and reopens the panel (saved) |
| ♪ Music on/off | shows or hides the Pause / Next chips in SMART while music plays |
| Border glow | crisp glowing edge around the whole screen |
| Haze glow | soft band along the screen edges that slowly breathes |
| Ambient glow | wide, faint wash from the edges (a phone cannot light up the room around it, so this lights the screen instead) |
| ● ● ● ● | Blue / Cyan / Violet / Pink glow colour (saved) |
| ⏸ Pause video / ▶ Play video | pauses or plays whatever is playing (media key); the label follows what the phone is doing |
| Match video colour | while something plays, the glow takes the average colour of the middle of the screen |
| Sound reactive | the glow swells with the sound the phone is playing |

## How it works (new file `Glow.kt`, changes in `Sidebar.kt`)
* One full-screen, touch-through overlay window owned by the sidebar service (same overlay type as the sidebar). It exists only while at least one glow is on.
* All glow switches start **off** on the phone (a glow over every app should be your choice). The preview still starts with them on.
* Match video colour: `takeScreenshot` through the accessibility service, about every 0.7 s, only while audio is playing; it reads only the middle of the screen so the glow
  does not feed on itself; grey or white screens keep your chosen colour. Needs Android 11+ and Pragon accessibility on (the button says so if not).
* Sound reactive: Android's `Visualizer` on the audio output. Needs the microphone permission Pragon already asks for. Some phones refuse this tap; the button says so and switches itself off.
* Colour changes ease in; the animation stops by itself when nothing moves (a static border costs nothing).

## Limits
* Android has no way to know that the playing thing is a *video*: "video" means any playing media.
* The preview's glow around the phone body and the notch lip are a drawing of a phone; on a real phone the glow follows the screen edge.
* Not tested on a real phone.

## Files
New: `Glow.kt`, `preview/Pragon_Smart_Sidebar_v3_preview.html`. Changed: `Sidebar.kt`, `app/build.gradle.kts`.
