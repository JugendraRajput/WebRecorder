# Web Recorder

Android app for recording YouTube movie-review videos from Wikipedia pages – online or
completely offline.

* Load a movie list from Excel (column A = title, column B = Wikipedia URL).
* Browse the list page by page; every page is saved for offline use automatically.
* Record the screen (+ microphone) to `Movies/WebRecordings/<title>.mp4`.
* Download whole lists in the background with the **Batch Downloader**.
* Check, repair and clean saved pages in the **Offline Manager**.
* Keep several phones / the PC in sync through a free private GitHub repository
  (**Cloud Sync**) and see everything on the Vercel dashboard.

## Architecture

```
 Phone(s) ── Cloud Sync (GitHub REST API, incremental, background service) ──┐
                                                                            ▼
 PC tools ── tools/github_storage.py ───────────────▶  private repo  webrecorder-data
                                                       pages/<list>/<md5>.mht|.html
                                                       pages/<list>/metadata.json
                                                       history.json
                                                                            ▲
 webrecorder.jdworks.in ── Vercel (server/, PHP) ── reads (read-only) ──────┘
```

| Part | Where |
|---|---|
| Android app | `app/` (Java, minSdk 26, targetSdk 36) |
| Cloud storage | private GitHub repo `JugendraRajput/webrecorder-data` (free) |
| Dashboard | `server/` deployed on Vercel, domain `webrecorder.jdworks.in` (DNS at Hostinger) |
| Excel tools | `tools/Excel_Fixer` (Node), `tools/generate_offline_assets.py`, `tools/github_storage.py` |

### App components

| Class | Responsibility |
|---|---|
| `MainActivity` | List navigation, live/offline page loading, auto offline save, edit/delete entries, recording controls |
| `RecordingService` | Foreground screen recorder (MediaProjection + MediaRecorder), notification with Pause/Stop |
| `BatchAutoProcessActivity` | Bulk download: native WebView MHT engine or multi-threaded HTML inliner |
| `OfflineManagerActivity` / `OfflineViewerActivity` / `OfflinePageRepairer` | Validate, view, repair and clean offline pages |
| `OfflineStore` | Local storage, metadata (thread-safe, atomic writes), settings |
| `SyncActivity` / `SyncService` / `SyncEngine` / `GitHubStorage` | Cloud sync UI, background service, 3-way diff, GitHub client |
| `ContentFilter` | Shared whole-word filter for unwanted titles |

### How Cloud Sync decides what to do

Every page is compared by its git blob hash (local file vs cloud copy vs the copy at the
last sync):

| Situation | Two-way sync |
|---|---|
| same on both sides | nothing |
| changed only on this phone | upload |
| changed only in the cloud | download |
| changed on both | the larger (more complete) copy wins |
| deleted on this phone | deleted in the cloud |
| deleted on another device | deleted on this phone |
| new on either side | copied to the other side |

*Upload* only pushes this phone's changes; *Download* only pulls. Uploads are grouped into
commits of ≤ 40 files / 12 MB, downloads are verified against their hash, and the sync
keeps running in the background with a progress notification.

## Setup

1. **Storage repo** – create a private GitHub repo `webrecorder-data` (with a README) and a
   fine-grained token: *Repository access → only webrecorder-data*, *Permissions → Contents:
   Read and write*.
2. **App** – open ⋮ → *Cloud Sync*, enter `JugendraRajput/webrecorder-data`, branch `main`
   and the token → *Save* → *Sync All Folders*.
3. **Dashboard** – see [`server/README.md`](server/README.md).
4. **PC** – `set GITHUB_TOKEN=…` then
   `python tools/github_storage.py upload tools/out/offline_pages/Movie_List_2000`
   or `python tools/generate_offline_assets.py --excel-folder tools/Excel_Fixer/fixed --upload`.

## Build

Open the project in Android Studio (AGP 9.4, JDK 17+) → *Run*.
`./gradlew assembleDebug` builds `app/build/outputs/apk/debug/app-debug.apk`.
