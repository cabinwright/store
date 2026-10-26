# Cabinwright store

**Cabinwright**, an app store for Kia/Hyundai head units running [NEMESIS](https://github.com/xwtk/hmg_nemesis): browse,
install, update and remove packages from the [cabinwright/apps](https://github.com/cabinwright/apps)
index, on the car. Written for the 2023 Kia EV6 (Gen5W, firmware
`CVEV.USA.0000.V015.012.260507`). NEMESIS package `cabinwright.store`.

| Part | What it is |
|---|---|
| `app/` | The store app (`dev.cabinwright.store`): the head unit's look via the Cabinwright API's `CarPage`, day and night. |
| `helper/` | The root helper service. It does every install and removal, and checks everything again itself. |
| `common/` | Shared by both: index signature check (RSA-3072, SHA256withRSA, pinned keys), sequence and expiry checks, JSON, versions. |
| `keys/` | The pinned index public keys (`kia-apps-2026a`, backup `kia-apps-2026b`). |
| `test/` | Host tests (`test/test.sh`), including the index repo's signature test vectors. |

## How it stays safe

- Nothing privileged happens without the driver's knowledge. The helper takes requests only
  from the store app's read-only provider (`content://dev.cabinwright.store.requests/pending`), which
  answers only root, system and adb, and the app lists only what the driver just confirmed.
  Other apps can't see or add requests; request files on the sdcard (App Store 1.0's way) are
  ignored and deleted. Each request runs once (by its uuid), and the app drops it once it sees
  the result, gives up, or restarts.
- Nothing else on the sdcard is trusted for anything privileged: downloads and the index copy
  are checked again by the helper, and results and status only go to the screen.
- The index is only trusted if it is signed by a pinned key, newer than the last one seen, and
  not expired. The helper keeps its own record, so a rolled-back or replayed index is refused
  even after the app's data is wiped.
- Each download must match the index's size and SHA-256, its `NEMESIS.data` must match the
  entry, and `execute` packages (which run at install) are never installed.
- Before installing, the app shows in plain words what a package does (apps, root services,
  `/system` changes), taken from the review in the index.
- Retired apps (`deprecated` or `withdrawn` in the index) are never installed or updated, only
  removed. Removal doesn't need the index at all: the helper removes what its own record
  (`/data/nemesis/cabinwright/store/state.json`) says it installed, and nothing else, never itself.
  Helpers before 1.1 kept no record; the first 1.1 helper seeds it once from the verified,
  unexpired index (installed packages it lists) and logs what it added.
- With an expired index, nothing is installed or updated; removing still works.

## Moving from kia.store

This project used to be called kia-store (NEMESIS package `kia.store`, app `dev.kiastore`).
`cabinwright.store` installs beside it. Its helper imports kia.store's record
(`/data/nemesis/kia-store/state.json`, read only), with `kia.menu` and `kia.tweaks` recorded
under their new ids, and waits while kia.store's helper still runs, so only one helper acts at a
time. Then remove kia.store (it has no install hooks). [docs/migrating-from-kia.md](docs/migrating-from-kia.md)
has the whole switch for a car, and `tools/migrate-from-kia.sh` does it over ADB (a dry run
unless `--apply`).

## Build

```sh
CABINWRIGHT_MENU=../menu sh build.sh     # -> dist/cabinwright.store.zip
sh test/test.sh
```

Needs the Android SDK (build-tools 29.0.2, platform 23) and JDK 11. `DEBUG=1` also pins
the test key in `keys/test/` for a local debug index (`tools/debug-index.py`).
