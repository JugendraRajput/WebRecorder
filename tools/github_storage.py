#!/usr/bin/env python3
"""
Upload / download WebRecorder offline folders to the same private GitHub repository
the Android app syncs with.

Repository layout (shared with the app and the Vercel dashboard):
    pages/<folder>/<md5>.mht|.html
    pages/<folder>/metadata.json
    history.json

Usage:
    set GITHUB_TOKEN=<fine-grained token with Contents: Read and write>
    python tools/github_storage.py upload   path/to/offline_pages/Movie_List_2000
    python tools/github_storage.py download Movie_List_2000 --to path/to/offline_pages
    python tools/github_storage.py list

Only files whose content changed are transferred (git blob hashes are compared).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import platform
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import requests

API = os.environ.get("GITHUB_API_URL", "https://api.github.com").rstrip("/")
DEFAULT_REPO = "JugendraRajput/webrecorder-data"
MAX_BATCH_BYTES = 12 * 1024 * 1024
MAX_BATCH_FILES = 40


def git_blob_sha(data: bytes) -> str:
    return hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest()


class GitHubStorage:
    def __init__(self, repo: str, token: str, branch: str = "main"):
        if "/" not in repo:
            raise ValueError("repo must look like owner/repo")
        if not token:
            raise ValueError("Set the GITHUB_TOKEN environment variable first")
        self.repo = repo
        self.branch = branch
        self.s = requests.Session()
        self.s.headers.update({
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "WebRecorder-PC",
        })

    # ── low level ──
    def _req(self, method: str, path: str, **kw) -> requests.Response:
        for attempt in range(5):
            r = self.s.request(method, f"{API}/repos/{self.repo}{path}", timeout=180, **kw)
            if r.status_code in (429, 500, 502, 503, 504) or (r.status_code == 403 and "rate limit" in r.text.lower()):
                wait = int(r.headers.get("Retry-After", 2 ** (attempt + 1)))
                print(f"    GitHub busy ({r.status_code}), retrying in {wait}s...")
                time.sleep(min(wait, 90))
                continue
            return r
        return r

    def _ok(self, r: requests.Response, what: str) -> dict:
        if r.status_code >= 300:
            msg = ""
            try:
                msg = r.json().get("message", "")
            except Exception:
                pass
            raise RuntimeError(f"Could not {what}: HTTP {r.status_code} {msg}")
        return r.json() if r.content else {}

    def head(self) -> Tuple[str, str]:
        r = self._req("GET", f"/git/ref/heads/{self.branch}")
        if r.status_code in (404, 409):
            content = base64.b64encode(b"# WebRecorder offline pages\n").decode()
            self._ok(self._req("PUT", "/contents/README.md", json={
                "message": "Initialise WebRecorder storage", "content": content, "branch": self.branch}), "initialise repo")
            r = self._req("GET", f"/git/ref/heads/{self.branch}")
        commit = self._ok(r, "read branch")["object"]["sha"]
        tree = self._ok(self._req("GET", f"/git/commits/{commit}"), "read commit")["tree"]["sha"]
        return commit, tree

    def _child_tree(self, tree_sha: str, name: str) -> Optional[str]:
        for e in self._ok(self._req("GET", f"/git/trees/{tree_sha}"), "read tree").get("tree", []):
            if e["path"] == name and e["type"] == "tree":
                return e["sha"]
        return None

    def list_folders(self) -> Dict[str, Dict[str, Tuple[str, int]]]:
        _, tree = self.head()
        pages = self._child_tree(tree, "pages")
        result: Dict[str, Dict[str, Tuple[str, int]]] = {}
        if not pages:
            return result
        data = self._ok(self._req("GET", f"/git/trees/{pages}?recursive=1"), "list folders")
        for e in data.get("tree", []):
            parts = e["path"].split("/")
            if e["type"] == "tree" and len(parts) == 1:
                result.setdefault(parts[0], {})
            elif e["type"] == "blob" and len(parts) == 2:
                result.setdefault(parts[0], {})[parts[1]] = (e["sha"], e.get("size", 0))
        return result

    def read(self, path: str, ref: str) -> Optional[bytes]:
        r = self._req("GET", f"/contents/{path}", params={"ref": ref},
                      headers={"Accept": "application/vnd.github.raw+json"})
        if r.status_code == 404:
            return None
        if r.status_code >= 300:
            raise RuntimeError(f"Could not read {path}: HTTP {r.status_code}")
        return r.content

    def commit(self, changes: List[dict], message: str) -> None:
        """changes: {"path", "data": bytes} or {"path", "delete": True}"""
        for attempt in range(4):
            commit_sha, tree_sha = self.head()
            entries = []
            for c in changes:
                if c.get("delete"):
                    entries.append({"path": c["path"], "mode": "100644", "type": "blob", "sha": None})
                    continue
                data: bytes = c["data"]
                try:
                    entries.append({"path": c["path"], "mode": "100644", "type": "blob", "content": data.decode("utf-8")})
                except UnicodeDecodeError:
                    blob = self._ok(self._req("POST", "/git/blobs", json={
                        "content": base64.b64encode(data).decode(), "encoding": "base64"}), "upload blob")
                    entries.append({"path": c["path"], "mode": "100644", "type": "blob", "sha": blob["sha"]})
            new_tree = self._ok(self._req("POST", "/git/trees", json={"base_tree": tree_sha, "tree": entries}), "create tree")["sha"]
            new_commit = self._ok(self._req("POST", "/git/commits", json={
                "message": message, "tree": new_tree, "parents": [commit_sha]}), "create commit")["sha"]
            r = self._req("PATCH", f"/git/refs/heads/{self.branch}", json={"sha": new_commit, "force": False})
            if r.status_code in (409, 422):
                print("    Branch moved (another device synced) - retrying...")
                continue
            self._ok(r, "update branch")
            return
        raise RuntimeError("Could not commit: branch keeps moving")


def device_name() -> str:
    return f"PC {platform.node()}".strip()


def upload_folder(gh: GitHubStorage, local_dir: Path, folder: Optional[str] = None) -> int:
    folder = folder or local_dir.name
    remote = gh.list_folders().get(folder, {})
    pages = sorted(p for p in local_dir.iterdir() if p.is_file() and p.suffix.lower() in (".html", ".mht"))
    changes: List[dict] = []
    for p in pages:
        data = p.read_bytes()
        if remote.get(p.name, ("",))[0] != git_blob_sha(data):
            changes.append({"path": f"pages/{folder}/{p.name}", "data": data, "name": p.name})
    print(f"  {folder}: {len(pages)} local pages, {len(changes)} new/changed")

    batches: List[List[dict]] = []
    cur: List[dict] = []
    size = 0
    for c in changes:
        if cur and (size + len(c["data"]) > MAX_BATCH_BYTES or len(cur) >= MAX_BATCH_FILES):
            batches.append(cur)
            cur, size = [], 0
        cur.append(c)
        size += len(c["data"])
    if cur:
        batches.append(cur)
    for i, b in enumerate(batches, 1):
        gh.commit(b, f"{folder}: sync {len(b)} file(s) from {device_name()}")
        print(f"    committed batch {i}/{len(batches)} ({len(b)} files)")

    # Merge metadata: newest updated_at wins per page.
    meta_path = local_dir / "metadata.json"
    if meta_path.exists() and pages:
        local_meta = json.loads(meta_path.read_text(encoding="utf-8") or "{}")
        commit_sha, _ = gh.head()
        remote_raw = gh.read(f"pages/{folder}/metadata.json", commit_sha)
        remote_meta = json.loads(remote_raw.decode("utf-8")) if remote_raw else {}
        merged = dict(remote_meta)
        for k, v in local_meta.items():
            if k not in merged or int(v.get("updated_at", 0)) >= int(merged[k].get("updated_at", 0)):
                merged[k] = v
        text = json.dumps(merged, indent=2, ensure_ascii=False).encode("utf-8")
        if remote_raw != text:
            gh.commit([{"path": f"pages/{folder}/metadata.json", "data": text}], f"{folder}: update metadata")
            meta_path.write_bytes(text)

    if changes:
        commit_sha, _ = gh.head()
        raw = gh.read("history.json", commit_sha)
        history = json.loads(raw.decode("utf-8")) if raw else []
        now = int(time.time())
        events = [{"timestamp": now, "action": "upload", "folder": folder, "filename": c["name"],
                   "title": c["name"], "device": device_name()} for c in changes]
        history = (events[::-1] + history)[:500]
        gh.commit([{"path": "history.json", "data": json.dumps(history, indent=1).encode("utf-8")}], "Update sync history")
    return len(changes)


def download_folder(gh: GitHubStorage, folder: str, target_root: Path) -> int:
    remote = gh.list_folders().get(folder)
    if remote is None:
        raise RuntimeError(f"Folder '{folder}' not found in the cloud")
    commit_sha, _ = gh.head()
    out = target_root / folder
    out.mkdir(parents=True, exist_ok=True)
    count = 0
    for name, (sha, _size) in sorted(remote.items()):
        dest = out / name
        if dest.exists() and git_blob_sha(dest.read_bytes()) == sha:
            continue
        data = gh.read(f"pages/{folder}/{name}", commit_sha)
        if data is None or git_blob_sha(data) != sha:
            print(f"    ! {name}: download failed or checksum mismatch")
            continue
        tmp = dest.with_suffix(dest.suffix + ".part")
        tmp.write_bytes(data)
        tmp.replace(dest)
        count += 1
        print(f"    ⬇ {name}")
    return count


def main() -> int:
    ap = argparse.ArgumentParser(description="Sync WebRecorder offline folders with GitHub storage")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPO", DEFAULT_REPO))
    ap.add_argument("--branch", default=os.environ.get("GITHUB_BRANCH", "main"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    up = sub.add_parser("upload", help="Upload one offline folder (or a folder of folders)")
    up.add_argument("path")
    up.add_argument("--folder", help="Cloud folder name (default: directory name)")
    dn = sub.add_parser("download", help="Download one cloud folder")
    dn.add_argument("folder")
    dn.add_argument("--to", default="offline_pages")
    sub.add_parser("list", help="List cloud folders")
    args = ap.parse_args()

    gh = GitHubStorage(args.repo, os.environ.get("GITHUB_TOKEN", ""), args.branch)
    if args.cmd == "list":
        for name, files in sorted(gh.list_folders().items()):
            pages = [f for f in files if f.endswith((".html", ".mht"))]
            print(f"{name:40s} {len(pages):6d} pages")
        return 0
    if args.cmd == "upload":
        path = Path(args.path).expanduser().resolve()
        dirs = [path] if any(p.suffix.lower() in (".html", ".mht") for p in path.iterdir()) else \
            sorted(p for p in path.iterdir() if p.is_dir())
        total = sum(upload_folder(gh, d, args.folder if len(dirs) == 1 else None) for d in dirs)
        print(f"Done: {total} file(s) uploaded.")
        return 0
    if args.cmd == "download":
        n = download_folder(gh, args.folder, Path(args.to).expanduser().resolve())
        print(f"Done: {n} file(s) downloaded.")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
