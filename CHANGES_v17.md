# Pragon Mobile v17 (versionName 6.4): Smart Sidebar in the v3 design

**Not compiled** (no Android SDK here). If the GitHub build prints `e:` lines, send them over.
Files changed: `Sidebar.kt`, `preview/Pragon_Smart_Sidebar_v3_preview.html`, `app/build.gradle.kts` (version).

## Why the earlier restyle didn't show
The sidebar is native Kotlin (`Sidebar.kt`). The HTML preview is only a mock-up, so its CSS never reaches the phone.
v17 builds the v3 design natively and updates the preview to show the same thing.

## The sidebar now has the v3 layout: a bottom tab bar with 5 pages
| Tab | Contents |
|---|---|
| Control | SMART chips, QUICK APPS (10 slots from your favourites, most used first, Edit to remove, + opens Apps), CONTROLS, LEVELS (volume and brightness sliders), NAVIGATE, OPTIMISER (memory and storage meters, Clean / Boost / Optimiser) |
| Tiles | QUICK SETTINGS tiles, SYSTEM STATUS rings (battery, memory, storage, temperature) |
| Apps | Favourites / All apps, search, sort, press and hold to favourite |
| Files | Dock / Docs / Quick (all existing behaviour) |
| Macros | Macros and LOOK & GLOW |

Sharing or dragging a file into the panel opens the Files tab.
Look: Newsreader font, ash buttons, teal on-state, cyan section titles with a fading line, logo header, black panel (all from the earlier v17 restyle).

## Notes
- Volume and brightness sliders call the existing `volume` / `brightness` commands with a number. Brightness still needs "Allow modifying system settings".
- Not in the app: the music card with track name (the app shows Pause / Next chips instead), the connection rows under SYSTEM STATUS, the Junk meter, the silver shine on press, the thin gradient line icons (buttons still use emoji).
- The preview (`preview/…v3_preview.html`) shows the same tabs and behaviour. It drops the same items.

## My Files: albums, views, sorting, big deletes, duplicates
Files changed: `FilesActivity.kt`, `assets/files.html`.

**Deleting 1000+ at a time.** Select all, then Delete. The app now moves files to Recently deleted in the background with a progress bar.
Before, every file re-read and re-wrote the whole bin list, and files moved in the same millisecond could collide. Now the list is saved once per 200 files,
every bin name is unique, and the media scanner is told once. Tagging and starring many files also takes one read and one write.

**Photos and Videos open as albums:** WhatsApp, WhatsApp Income (received, anything downloaded through WhatsApp or WhatsApp Business lands here automatically),
Telegram, Pictures, Camera, Downloads, Instagram, Adobe, Screenshots, Drawing, Certificates, Notes, AI Gen, Official, Unofficial, ID proof, Payment screenshots, Logo, Starred.
A photo can sit in more than one album. Folder-based albums fill themselves. Certificates, Notes, AI Gen, ID proof, Payment screenshots and Logo also fill from file names.
Official, Unofficial and Starred (and any album above) are filled by you: select photos, tap Tag or the star. Each of those has a tag with the same name.

**Views:** grid (default), waterfall, list. The button at the top cycles them and the choice is remembered.
**Sort:** date, time (time of day), size, file name, type, genre. Tap the chosen one again to flip the order.
**Select:** long-press, or ⋮ → Select items. "Select all" picks everything in the current list, not just what is on screen.
**Find duplicates:** ⋮ in Photos, Videos, Audio, Documents, APKs and Archives scans that category, and every file's ⋮ menu has "Find duplicates of this".
It finds exact copies (same size, same start and end, then the whole file's SHA-1). Keep oldest selects the rest, and Delete sends them to Recently deleted.
It does not find "similar-looking" photos.
Documents also get type chips (PDF, Word, Excel, PowerPoint, Text).
