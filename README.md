# Cabinwright store

**Cabinwright**, an app store for Kia/Hyundai head units running [NEMESIS](https://github.com/xwtk/hmg_nemesis): browse,
install, update and remove packages from the [cabinwright/apps](https://github.com/cabinwright/apps)
index, on the car. Written for the 2023 Kia EV6 (Gen5W, firmware
`CVEV.USA.0000.V015.012.260507`). NEMESIS package `cabinwright.store`.

| Part | What it is |
|---|---|
| `app/` | The store app (`app.cabinwright.store`): the head unit's look via the Cabinwright API's `CarPage`, day and night. |
| `daemon/` | `cabinwrightd`, the one root process: installer, broker and tweak runner, and `kt`, the command tweaks use. See [docs/design.md](docs/design.md). |
| `common/` | Shared by both: index signature check (RSA-3072, SHA256withRSA, pinned keys), sequence and expiry checks, JSON, versions. |
| `keys/` | The pinned index public keys (`cabinwright-2026a`, backup `cabinwright-2026b`). |
| `test/` | Host tests: `test/test.sh` (`common/`, with the index repo's signature test vectors) and `test/daemon-test.sh` (the daemon against a mock head unit). |

## How it stays safe

- Nothing privileged happens without the driver's knowledge. Only the store app, known by its
  uid and certificate, can ask `cabinwrightd` to install or remove, and it asks only for what the
  driver just confirmed.
- Nothing on the sdcard is trusted for anything privileged: the index and downloads are copied to
  private storage and checked there.
- The index is only trusted if it is signed by a pinned key, newer than the last one seen, and
  not expired. The daemon keeps its own record, so a rolled-back or replayed index is refused
  even after the app's data is wiped.
- Each download must match the index's size and SHA-256. `app` and `tweaks` packages never reach
  NEMESIS with their own manifest, so their hooks and services never run; `execute` packages are
  never installed.
- A tweak's needs must be exactly what the store reviewed, and an app can use only the
  capabilities its entry declares and the driver left on.
- Before installing, the app shows in plain words what a package does, from the review in the index.
- Retired apps (`deprecated` or `withdrawn` in the index) are never installed or updated, only
  removed. Removal doesn't need the index at all: the daemon removes what its own record
  (`/data/nemesis/cabinwright/state.json`) says it installed, and nothing else, never itself.
- With an expired index, nothing is installed or updated; removing still works.

**Lockdown isn't possible yet.** Any app can still reach root through NEMESIS without the broker:
see "Lockdown" in the design.

## Build

```sh
CABINWRIGHT_MENU=../menu sh build.sh     # -> dist/cabinwright.store.zip, and R8's name maps
CABINWRIGHT_MENU=../menu sh test/test.sh
sh ../menu/api/tools/lint.sh .           # ktlint, detekt, ShellCheck; --fix reformats the Kotlin
```

Needs the Android SDK's build-tools 29.0.2, JDK 11, and a checkout of
[cabinwright/menu](https://github.com/cabinwright/menu) (CI pins a release tag): its
`api/tools/fetch-tools.sh` brings the pinned Kotlin, R8, Android 4.4's `android.jar` and the
lint tools, and its `build-app.sh` builds the app. `DEBUG=1` also pins the test key in
`keys/test/` for a local debug index (`tools/debug-index.py`).

Everything is Kotlin, compiled against Android 4.4's `android.jar` (API 19, the head unit's),
so a newer call is a build error:

- `app/` is built by the menu's `build-app.sh` with `common/` and the generated `Keys.kt`,
  shrunk by R8 (`dist/cabinwright.store.map`).
- `daemon/` is a dex for `app_process`, with `common/` and Kotlin's stdlib shrunk into it by R8;
  `daemon/proguard-rules.pro` keeps the two mains, which `svc/cabinwrightd.sh` and `kt` start by
  name (`dist/cabinwright.store.daemon.map`).
- `test/test.sh` runs `common/` on the host JVM against the index repo's signing test vectors.
- `test/daemon-test.sh` runs the real daemon on the host JVM, with stand-ins for `android.net`'s
  sockets and for `nemesis`, and real packages, APK signatures and index signatures.
