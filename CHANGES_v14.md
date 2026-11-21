# Pragon Mobile v14: My Files (file manager)

**Not compiled on an Android toolchain** (no Android SDK in the build sandbox). The page (`assets/files.html`) was run in a headless browser with
mock data: home, folders, sheet menus, tags, multi-select, back handling. `FilesActivity.kt` needs your GitHub Actions build and a test on the phone;
if the build prints `e:` lines, send them over.

## What it is
Settings > MY FILES > **Open My Files**. Layout follows the screenshot you sent:
* **All files** card: used / total storage with a bar (tap to browse internal storage).
* **Photos, Videos, Audio, Documents, APKs, Archives** tiles with counts (MediaStore; photos and videos show as a thumbnail grid).
* **Sources**: Internal storage, Downloads, WhatsApp, Bluetooth (shown only if the folder exists) + **Add source** (pick any folder).
* **Quick access**: your own folders + **Add folders** (also in a folder's ⋮ menu).
* **Tag**: project, important, work, study, life + **New tag**. Tag any file or folder from its ⋮ menu; tap a tag to list what is in it.
* **Recently deleted**: Delete moves to a bin (`/sdcard/.PragonTrash`), kept 30 days, with Restore / Delete forever / Empty.
* **Recent** tab (bottom bar), **search** (top right), sort by name / date / size / type, new folder, rename, share, open, copy path, details.
* Long-press selects several items (select all, tag, delete).

## Permission
Needs **All files access** (`MANAGE_EXTERNAL_STORAGE`). The page shows a button that opens Android's own screen for it. Nothing uses the network.

## Not included
* **Private Safe** (the locked area in your screenshot): it needs a PIN / biometric and encryption, so it is left out rather than faked.
* A voice command ("open my files") is not added yet.
* Thumbnails and the build are untested on a real phone.

---

# v14 (part 2): File Dock, Doc box and Quick files in the Smart Sidebar

New **FILES** strip in the sidebar panel (under the smart chips): three tabs, **📥 Dock**, **📄 Docs**, **⭐ Quick**. Tap a tab to open it, tap it again to fold it.
Code: `Dock.kt` (data, sorting, downloads query), `Sidebar.kt` (the panel), `DockShareActivity.kt` (Share target), small hooks in `FilesActivity.kt` / `files.html`.

## File Dock (the backpack)
* Holds text, links, photos and files for a while (max 40 items, files up to 150 MB; copies live in the app's private folder).
* Put things in: **Share > Pragon File Dock** from any app (also several at once), **📋 Paste** (reads the clipboard), **＋** on a file in Docs, **Add to File Dock** in My Files, or
  drag something to the screen edge: the sidebar opens on the dock and you drop it on the dashed box.
* Take things out: **press and hold** an item and drag it into another app (Notes, Gmail, WhatsApp ...). The panel closes so the app underneath is free. Files go across as
  read-only `content://` links, text as text. **Tap** an item for Share / Copy (or Open) / Remove. **Clear all** empties it.

## Doc box (Docs tab)
* Your downloads: every document, archive and APK anywhere (WhatsApp, Telegram, Downloads ...) plus anything at all in Downloads; latest 500, by the time each was added.
* Type filter: All, PDF, DOC (doc, docx, odt, rtf), XLS (xls, xlsx, csv, ods), PPT, TXT (txt, md, json, xml, html, epub), ZIP (zip, rar, 7z ...), APK.
* Default order: newest first, with TODAY / YESTERDAY / date headings and the date and time on every row. Tap a file to open it; ＋ copies it to the dock; ☆ pins it to Quick.

## Quick files (Quick tab)
* Files you pinned (☆ in Docs, **Pin to Quick files** in My Files) plus files you opened 3 or more times through Pragon (sidebar or My Files), which appear on their own. ✕ removes.

## Sorting (every list has its own, remembered)
* Chips: **Date · Name · Size · Type**. The chip that is on shows ↓ or ↑; tap it again to flip (ascending / descending). Default everywhere: Date, newest first.

## Limits
* **Not compiled** (no Android SDK here); the My Files menu was run in a browser, the sidebar is native and needs your build and a test on the phone.
* Dragging out only works into apps that accept drops, and mostly while both apps are visible (split screen or a pop-up window). Otherwise: tap the item > Share.
* Android hides the clipboard from background apps; **Paste** works while Pragon's accessibility is on. Dropping files from another app onto the dock may be refused by that app; use Share > Pragon File Dock then.
* The Docs tab needs "All files access" (My Files asks for it).
