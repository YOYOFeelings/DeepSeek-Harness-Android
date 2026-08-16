# DeepSeek-Harness-Android

![DeepSeek Harness](https://img.shields.io/badge/DeepSeek_Harness-blue?style=flat&logo=DeepSeek&logoSize=auto&color=%232D5F9E)
![Android](https://img.shields.io/badge/Android-blue?style=flat&logo=Android&logoSize=auto&color=%2397CA00)
![License MIT](https://img.shields.io/badge/License-MIT-yellowgreen?style=flat)
![Fork](https://img.shields.io/badge/Fork-2nd%20Edition-orange?style=flat)

> **Fork / secondary modification notice:** This repository is a **fork** maintained by
> [YOYOFeelings](https://github.com/YOYOFeelings) (中文昵称「孤独的」), based on
> [kelai141/dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk).
> It is a secondary-development ("二改") edition that adds robustness fixes and
> quality-of-life features on top of the original project.

Android shell for [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness): a WebView
mobile UI over an **embedded Termux runtime snapshot** (extract-and-run, no Termux app needed),
SAF directory bridge, keep-alive foreground service, **hardened engine watchdog**, and online
runtime updates. One APK to install: it boots a full dsh web agent that can really execute bash.

## Fork enhancements over the original

Compared with the upstream `dsh-mobile-apk`, this fork adds / fixes:

- **Hardened engine watchdog** — consecutive-failure tolerance with restart backoff; automatic
  restarts are **exempted while a Web page is open** so browsing is never interrupted by the engine
  recovering.
- **Fix: Web-page config save no longer freezes the engine** — previously saving the config from
  the Web UI could hang the engine; this is fixed and verified.
- **Storage usage statistics & one-click cleanup** — see how much space the runtime occupies
  (data / cache) and free it with a single tap.
- **Home status cards** — engine latency, uptime, data usage and cache size shown at a glance on
  the main screen.
- **First-launch guide page** — walks you through the app on first start, including permission
  caveats.
- **Permissions management page** — shows notification / file-access / network permission status
  with a direct "go to system settings to revoke" entry.
- **View engine logs entry** — open the engine's log output straight from the UI.
- **About page grid-button layout** — a clean 2×2 grid for project / license / group / donation
  entries.

## Features

- **Embedded runtime** — ships a ~70MB xz snapshot (node + bash + coreutils + dsh + plugins);
  first launch extracts in ~10s and starts the engine from the app's own files; fully offline.
- **Mobile UI** — system WebView over `http://127.0.0.1:3080` with the responsive plugin
  (drawer/sheet on phones).
- **Keep-alive** — foreground service ("dsh 引擎运行中") + a 5s watchdog that restarts a dead engine.
- **Online runtime updates** — manifest-driven snapshot swap (download → sha256 → atomic switch →
  auto-restart); the running runtime can update itself without an APK update.
- **SAF bridge** — `pickDirectory` maps the picked tree to a real path (`/storage/emulated/0/…`).

## Build

Requirements: JDK 17+, Android SDK (compileSdk 36); Gradle 8.11.1 via wrapper.

```sh
# 1. Prepare the runtime snapshot (required, ~70MB, distributed as a Release asset)
#    Option A: download snapshot-x86_64.tar.xz from GitHub Releases
#    Option B: build on a Termux device (scripts/make-snapshot.sh) and pull it
mkdir -p app/src/main/assets
cp snapshot/snapshot.tar.xz app/src/main/assets/snapshot.tar.xz

# 2. Build (fails loudly when the snapshot is missing)
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

## Bridge protocol v1 (`window.androidBridge`)

| method | signature | description |
|---|---|---|
| `version` | getter → string | bridge protocol version (`"1.0"`) for feature detection |
| `checkEngine` | () → string | probes 127.0.0.1:3080; JSON `{running, latencyMs}` |
| `keepScreenOn` | (enable: boolean) | screen-on wake lock |
| `showNotification` | (title, text) | test notification channel (POST_NOTIFICATIONS) |
| `pickDirectory` | (callbackId: string) | SAF tree picker; result async via `window.__dshBridge.onDirectoryPicked(callbackId, path)` |

The bridge decouples the APK from the dsh version: pages feature-detect on `androidBridge.version`.

## Online update protocol (multi-mirror)

1. The app fetches `MANIFEST.txt` from GitHub Releases — one `sha256 path size` triple per line
   (the publishing manifest for the runtime snapshots).
2. It matches the correct `snapshot-{arm64|x86_64}.tar.xz` asset by the device ABI.
3. The archive is downloaded through the selected mirror. Multiple mirrors are built in; the
   default is **akaere** (`https://cdn.akaere.online/`), with `gh-proxy` / official direct etc.
   kept as options (auto speed-tested, unusable sources are skipped).
4. The download is verified with **SHA-256**, extracted into a staging dir (never touching the
   live tree), atomically swapped as `usr` → `usr-old` → new `usr`, then the old engine is killed —
   the watchdog restarts it from the new runtime.

Test trigger: `adb shell am start -n com.dshmobile.shell/.MainActivity -a com.dshmobile.shell.action.UPDATE`;
status is written to `files/update-status.txt`. Test server: `node scripts/snapshot-server.mjs`.

## Permissions

`INTERNET` (WebView + engine probe), `POST_NOTIFICATIONS` (notification channel),
`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` (keep-alive). SAF picking needs no permission.

## ABI & pagesize

The x86_64 snapshot is verified end-to-end. arm64 snapshots are assembled from the official
Termux aarch64 repo (see docs/design.md §ABI); a 16KB-page build must be produced on a 16KB device.
APKs are per-ABI (the snapshot inside is arch-specific).

## License

MIT. Copyright is jointly credited to **kelai141** (original author) and **YOYOFeelings**
(fork / secondary-modification author). Contains third-party components under their own licenses
(see dependency declarations). Design rationale: `docs/design.md`.

## Fork / 二改说明

- **Original author:** [kelai141](https://github.com/kelai141) — original repository:
  [kelai141/dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk)
- **Fork author:** [YOYOFeelings](https://github.com/YOYOFeelings)（中文昵称「孤独的」)— this
  repository: [YOYOFeelings/DeepSeek-Harness-Android](https://github.com/YOYOFeelings/DeepSeek-Harness-Android)

This is a secondary-development (fork) edition built on top of the original project, released
under the same MIT license with the original author fully credited. It keeps all upstream features
and adds the robustness fixes and enhancements listed above.
