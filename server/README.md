# WebRecorder cloud dashboard (Vercel)

The Android app stores every offline page in a **private GitHub repository**
(default `JugendraRajput/webrecorder-data`). This folder is a small PHP site for
Vercel that reads that repository and shows folders, pages, sizes and the sync history.
Nothing is stored on Vercel, so nothing can be lost when Vercel restarts.

```
server/
├── api/index.php    dashboard + "Open" link for a page + CSV export
├── api/github.php   read-only GitHub client (curl)
└── vercel.json      vercel-php runtime, all routes → api/index.php
```

## Deploy (one time)

1. **vercel.com → Add New → Project → Import** the `WebRecorder` GitHub repository.
2. Set **Root Directory** to `server` (Framework preset: *Other*). Deploy.
3. **Settings → Environment Variables** (Production + Preview):

   | Name | Value |
   |---|---|
   | `GITHUB_TOKEN` | fine-grained token with **Contents: Read** on `webrecorder-data` (a read-only token is enough here) |
   | `GITHUB_REPO` | `JugendraRajput/webrecorder-data` |
   | `GITHUB_BRANCH` | `main` (optional) |
   | `DASHBOARD_PASSWORD` | optional – if set, the browser asks for it (any user name) |

4. **Deployments → ⋯ → Redeploy** so the variables are picked up.
5. **Settings → Domains → Add** `webrecorder.jdworks.in`. In Hostinger DNS keep the
   `CNAME webrecorder → cname.vercel-dns.com` record (it already exists).

Every push to `master` redeploys automatically.

## Notes

* "Open" streams a page from GitHub. Vercel limits responses to ~4.5 MB, so very
  large pages should be opened in the app instead.
* `robots.txt` blocks search engines.
* Local test: `GITHUB_TOKEN=… GITHUB_REPO=owner/repo php -S localhost:8000 api/index.php`
