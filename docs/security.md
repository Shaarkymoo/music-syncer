# Music-Syncer — Security & Scope

Last updated: 2026-09-30 · App version 0.1.0

This document answers, plainly: **what is exposed, what is always on, where the
app can be used, what the app can see, and what the honest caveats are.**

## What is exposed

| Thing | Exposure | Notes |
|---|---|---|
| Laptop sync server | `0.0.0.0:8756` on your LAN, **no authentication** | Any device on the same network can reach it: read your library's manifest, fetch files, push files, delete files per the sync rules |
| mDNS advertisement (`_music-syncer._tcp`) | Broadcast on your LAN | Any device on the network can *discover* that the server exists and its address |
| Phone app's folder | Only the folder you picked (SAF scoped grant) | The app holds read/write **only** on that one folder |
| Phone app's network | Can reach any address it is told to | It only ever talks to the laptop server (discovered via mDNS or your sync action) |

**The server is unauthenticated by design** (your spec: trusted home LAN). Anyone
on your Wi-Fi can, in principle, connect to port 8756 and interact with your
library. There is no internet exposure — the server binds to your LAN and never
phones home.

## What is always on

**Nothing.**

- The **laptop server runs only while you have `msserve` running** in a
  terminal. Ctrl-C stops it. It does not auto-start at boot.
- The **phone app has no background service, no watcher, no auto-sync.** It
  only does work while you have it open and tap a button (Scan / Sync /
  Verify / Find-laptop). Android may keep the process cached, but nothing runs
  in the background and nothing phones home.
- The phone app **uses the network only when you tap Sync or Check-update.**

## Where it works

- **Home Wi-Fi** (both devices on the same router).
- **Phone hotspot** (works offline — the laptop joins the phone's hotspot; no
  internet needed).
- **Laptop hotspot** (the phone joins it).
- It does **not** work over the internet. The protocol is LAN-local HTTP.

## What the app can see / do

- **Permissions requested** (install-time, from the manifest):
  - `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`,
    `CHANGE_WIFI_MULTICAST_STATE` — network for sync + mDNS discovery.
  - `POST_NOTIFICATIONS` — optional notifications (you may deny it).
  - `REQUEST_INSTALL_PACKAGES` — only used when **you** tap "Check for update"
    and confirm an install.
  - **No storage permission, no contacts, no location, no SMS, nothing else.**
- **Folder access** is via the system SAF picker — Android grants the app
  read/write **only** to the folder you choose. There is no "all files access".
- The app can **rename/move/delete/edit tags** on files inside that folder —
  that is its job. It never touches anything outside it.

## The self-update flow (0.1.0+)

- The app asks your laptop server `GET /version` and, if newer, downloads the
  APK from `GET /apk` and hands it to the Android system installer.
- The APK travels over **cleartext HTTP on your LAN**. Android's own
  **signature check** still protects the install: the update must be signed
  with the same key as the installed app, or the OS refuses it. (We have not
  added an explicit app-side hash check of the APK — noted as a future
  hardening step.)
- The first in-app install requires you to grant the app the "install unknown
  apps" permission once (system dialog).
- The laptop must be running with `MS_APK_PATH` (or `apk_path` in the config)
  pointing at the APK for `/apk` to serve it.

## Honest caveats / recommendations

1. **Use only networks you trust.** On an open/public Wi-Fi, anyone could talk
   to the server (no auth). Home Wi-Fi or a personal hotspot is the intended
   environment.
2. **Same-size modification protection is built in** (the journal-aware sha
   adoption), but the engine trusts that the two libraries started from the
   same source — it's a two-device mirror, not a security boundary.
3. **Future hardening options** (not yet implemented, by your choice to defer
   clashes/auth): a shared-token auth on the server, an APK checksum check
   before install, and TLS if you ever want to sync over the internet.
4. Your library's **file names and sizes are visible on the LAN** via the
   manifest — treat the network as trusted.