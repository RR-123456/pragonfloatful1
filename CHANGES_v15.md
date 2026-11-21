# Pragon Mobile v15: Phone optimiser and All apps in the Smart Sidebar

**Not compiled on an Android toolchain** (no Android SDK in the build sandbox). `assets/optimiser.html` was run in a headless browser with mock data
(home, junk scan, per-file ticks, clean, app cache, boost, protected apps, back handling: no script errors). The Kotlin needs your GitHub Actions build and a
test on the phone; if the build prints `e:` lines, send them over.

## Optimiser (Settings > OPTIMISER > Open Optimiser, or sidebar QUICK > Optimiser)
Code: `Optimiser.kt` (engine), `OptimiserActivity.kt` (bridge), `assets/optimiser.html` (page), `clearCacheCurrentAppInfo()` in `PragonAccessibilityService.kt`.

### Junk cleaner
One walk through the phone's storage, grouped into:
* **Pragon's own cache**, **Temp and log files** (.tmp .log .dmp .chk .old, thumbdata), **Thumbnail caches**, **Trashed media** (.trashed-…), **Empty files**, **Empty folders**: ticked for you and deleted for good.
* **Unfinished downloads** (.crdownload, .part), **Old installers (APK)**, **Duplicate files**, **Large files (100 MB+)**: not ticked; whatever you tick goes to the **Recently deleted** bin of My Files (30 days, restorable).
* **Recently deleted bin**: shown so you can empty it from here; not ticked.
* Duplicates: same size, then a quick fingerprint, then a full MD5. The oldest copy of each is kept, only the extras are listed. Files under 100 KB are ignored.
* Tap a group to see its files; each file has its own tick. Android/, hidden folders and standard folders (DCIM, Download ...) are never reported as empty.
* Needs **All files access** (the page has a button).

### App cache
Android does not allow one app to clear another app's cache. So Pragon opens each chosen app's **App info > Storage & cache** and presses **Clear cache** through the accessibility service (same idea as "force stop" already used by voice). Pragon's own cache is cleared directly.
* Sizes per app show only after you allow **Usage access** (Android's rule). Without it you still can clear, but pick apps without seeing sizes. Apps with 5 MB+ cache are ticked by default.
* Takes about 4 seconds per app; a Stop button is on screen. Phones whose Settings screen uses unusual wording may not match (the result screen counts "not possible"; the button words are in English, Hindi, Tamil and a few other languages).
* Only the cache is cleared; logins and data are not.

### Boost
Asks Android to end background processes of apps with a launcher icon, except Pragon, your launcher, keyboard, phone app and **Protected apps** (a list you pick on the Boost screen). Shows memory free before and after. Android restarts some apps by itself, so the gain can be small.

### Sidebar
QUICK now has **🧹 Clean** (opens the junk scan), **⚡ Boost** (runs it, shows the memory freed) and **🧰 Optimiser**.

## All apps in the Smart Sidebar
The APPS strip has two tabs: **★ Favourites** (as before) and **▦ All apps**.
* Every app with a launcher icon (apps Pragon is not allowed to open stay hidden when your allow-list is on), 24 at a time with **Show more**.
* **Search box**: tapping it lets the panel take the keyboard (it is normally not focusable so it never steals focus); press Done on the keyboard to hand focus back.
* Sort chips **Name / Used / Newest**; tap the active chip to flip direction. Default is Name A to Z. Your choice is remembered.
* **Press and hold** an app to add or remove it from Favourites (★ shows on favourites in the All apps list).
* Apps now open straight by package (so two apps with the same name no longer clash), falling back to the old voice path if Android refuses.

## New permission
`PACKAGE_USAGE_STATS` (Usage access): only to read each app's cache size. Granted by you on Android's own screen; optional.

## Not included
* No voice commands yet ("clean junk", "boost").
* Cache sizes of other apps' private data cannot be read, or cleared, without Usage access / the accessibility steps above. That is Android's rule, not a choice here.
* Not tested on a real phone.
