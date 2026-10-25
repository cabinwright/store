# kia-store

**App Store** for Kia head units running [NEMESIS](https://github.com/xwtk/hmg_nemesis): browse,
install, update and remove packages from the [kia-apps](https://github.com/hazptr/kia-apps)
index, on the car. Written for the 2023 Kia EV6 (Gen5W, firmware
`CVEV.USA.0000.V015.012.260507`). NEMESIS package `kia.store`.

| Part | What it is |
|---|---|
| `app/` | The store app (`dev.kiastore`): Kia's look via kia-menu's `KiaPage`, day and night. |
| `helper/` | The root helper service. It does every install and removal, and checks everything again itself. |
| `common/` | Shared by both: index signature check (RSA-3072, SHA256withRSA, pinned keys), sequence and expiry checks, JSON, versions. |
| `keys/` | The pinned index public keys (`kia-apps-2026a`, backup `kia-apps-2026b`). |
| `test/` | Host tests (`test/test.sh`), including kia-apps' signature test vectors. |

## How it stays safe

- The index is only trusted if it is signed by a pinned key, newer than the last one seen, and
  not expired. The helper keeps its own record, so a rolled-back or replayed index is refused
  even after the app's data is wiped.
- Each download must match the index's size and SHA-256, its `NEMESIS.data` must match the
  entry, and `execute` packages (which run at install) are never installed.
- Before installing, the app shows in plain words what a package does (apps, root services,
  `/system` changes), taken from the review in the index.

## Build

```sh
KIA_MENU=../kia-menu sh build.sh     # -> dist/kia.store.zip
sh test/test.sh
```

Needs the Android SDK (build-tools 29.0.2, platform 23) and JDK 11. `DEBUG=1` also pins
the test key in `keys/test/` for a local debug index (`tools/debug-index.py`).
