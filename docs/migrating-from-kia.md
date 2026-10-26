# Moving from the kia.* packages to Cabinwright

This project used to carry the Kia name. It isn't affiliated with Kia, so every package, app and
path was renamed. The new packages are new NEMESIS ids, so they install **beside** the old
ones, take over their data, and the old ones are then removed:

| Before | After | App |
|---|---|---|
| `kia.store` (`dev.kiastore`) | `cabinwright.store` (`dev.cabinwright.store`) | Cabinwright |
| `kia.menu` (`dev.kiamenu.editor`) | `cabinwright.menu` (`dev.cabinwright.menu`) | Edit Menu |
| `kia.tweaks` (`dev.kiatweaks`) | `cabinwright.tweaks` (`dev.cabinwright.tweaks`) | Tweaks |
| `tweaks.projection`, `tweaks.adb` | unchanged | (tweak packs) |

| Data before | After |
|---|---|
| `/data/nemesis/kia-tweaks/` | `/data/nemesis/cabinwright/tweaks/` |
| `/data/nemesis/kia-store/` | `/data/nemesis/cabinwright/store/` |
| `/sdcard/kia-{menu,store,tweaks}/` | `/sdcard/cabinwright/{menu,store,tweaks}/` |
| `/data/nemesis/backup/kia-tweaks.systemui` | `/data/nemesis/backup/cabinwright.tweaks.systemui` |
| `/data/nemesis/backup/ev6.launcher` | unchanged (the stock launcher) |

The old index, `hazptr.github.io/kia-apps`, is frozen: it marks kia.store, kia.menu and
kia.tweaks deprecated with a link here, and gets no new versions. The new index is
`https://cabinwright.github.io/apps/`, signed with the same keys.

## What each new package does on its own

Every import reads the old files and never writes them, so the old packages keep working until
they are removed. Each happens only while the new package has no data of its own, is logged,
and can run again without harm.

- **cabinwright.tweaks.** While kia.tweaks' `tweaksd` runs (its command line,
  `/data/nemesis/apps/kia.tweaks/svc/tweaksd.sh`, in `/proc`), the new `tweaksd` stands by: it
  runs no tweak script, so two runners never drive the same tweaks. Meanwhile it imports
  kia.tweaks' switches (`want/`), `state.json`, pack copies (`shadow/`), logs and the
  skip-consent stock backup, and keeps the switches current. The Tweaks app shows "The old Tweaks
  package (kia.tweaks) still runs these tweaks". Once kia.tweaks is removed, `tweaksd` takes
  over within a few seconds with the same switches. Every kia.tweaks tweak is a cabinwright.tweaks
  tweak too, so none counts as departed: a removed package's tweak that another installed
  package provides continues from that package and is never turned off. Logged in
  `/data/nemesis/cabinwright/tweaks/logs` and the service log
  (`imported kia.tweaks' data`, `standing by`, `taking over`, `<id>: kia.tweaks was removed;
  <id> continues from cabinwright.tweaks`).
- **cabinwright.store.** The helper imports `/data/nemesis/kia-store/state.json`: what the
  store installed, with `kia.menu` and `kia.tweaks` recorded as `cabinwright.menu` and
  `cabinwright.tweaks`, `tweaks.*` as they are, `kia.store` left out, and the index sequence (so
  an older index is still refused). While kia.store's helper runs (`dev.kiastore.helper.Helper`
  in `/proc`), the new helper does nothing else and the app says so ("The old App Store
  (kia.store) is still installed"). The two can't take each other's requests: each has its own
  request folder (`/sdcard/kia-store` and `/sdcard/cabinwright/store`), app, provider and data
  folder. `debug-index` moves to `/data/nemesis/cabinwright/store/debug-index`.
- **cabinwright.menu.** Edit Menu imports `/sdcard/kia-menu/layout.json` the first time it is
  asked for the layout, with the renamed apps' entries moved to their new names. The launcher
  hook on `/system` is built against one package, so it has to be rebuilt: an installed hook
  only asks `dev.kiamenu.editor`. `install-launcher.sh` replaces an installed hook and keeps the
  stock backup in `/data/nemesis/backup/ev6.launcher`.

The tweak packs carry on as they are: `tweaksd` finds them by scanning `/data/nemesis/apps`.

## The switch, in order

`tools/migrate-from-kia.sh` does steps 1 to 4 and checks step 5, from a computer over ADB. By
default it is a dry run: it checks every precondition and prints what it would do. With
`--apply` it stops at the first thing it doesn't expect. It changes the car only through
`nemesis install`, `nemesis uninstall`, `/data/local/tmp`, and the menu repo's
`launcher/install-launcher.sh`, the only step that writes `/system`.

```sh
tools/migrate-from-kia.sh -s 192.168.0.1:5555 \
    --tweaks cabinwright.tweaks-v1.4.zip --store cabinwright.store-v1.2.zip \
    --menu cabinwright.menu-v1.3.zip \
    --launcher Launcher_WP3.apk --menu-repo ../menu      # dry run
tools/migrate-from-kia.sh --apply ...                    # steps 1-4
# restart the head unit, then
tools/migrate-from-kia.sh --verify -s 192.168.0.1:5555   # step 5
```

Before anything, it checks: ADB and `nemesis root` work, the firmware is
`CVEV.USA.0000.V015.012.260507`, no NEMESIS install is running, kia.tweaks is 1.3 or newer and
its `tweaksd` is running, every tweak is settled (on, off or unsupported, nothing pending,
departed or failed), each zip is the package it should be, the old packages declare no NEMESIS
install hooks, and the launcher on `/system` is stock or a hook with the stock backup intact. It
records the tweak states (switch and state) to compare later.

**1. Install the new packages** beside the old ones: `cabinwright.tweaks`, `cabinwright.store`,
`cabinwright.menu`. Nothing on `/system` changes, and the old packages keep running.

**2. Let them import.** Wait until cabinwright.tweaks' `state.json` says `"standby"` and its
switches equal kia.tweaks' (`want/`), and cabinwright.store's `state.json` has `"imported"`.

**3. Stop and remove the old packages:** `nemesis uninstall kia.tweaks`, then `kia.store`,
then `kia.menu`. None of them has install hooks (no `preinstall`, `postinstall`,
`preuninstall` or `postuninstall` in its `NEMESIS.data`), so NEMESIS stops their service and
deletes their app and folder, and nothing of theirs runs. After kia.tweaks goes, wait until
cabinwright.tweaks has taken over and every tweak has the switch and state it had before.
Until step 4 and the restart, the All Menus launcher still asks the old Edit Menu, so its
custom entries are missing until then.

**4. Rebuild the launcher hook.** In a cabinwright/menu checkout, build it from the car's stock
files (`launcher/build.sh <stock dir>`) and run `launcher/install-launcher.sh` again (as root
on the car). It sees the installed hook, says `replacing the installed hook`, leaves the stock
backup as it is, and swaps in the new hook. Restart the head unit.

**5. Verify:** the kia.* packages are gone, the cabinwright.* ones are installed, `tweaksd` runs
the tweaks (not standing by) and every tweak is as it was before, the store's helper runs and
isn't waiting, Edit Menu has imported the layout (open All Menus once), the launcher on
`/system` is the new hook, and `/data/nemesis/backup/ev6.launcher` still holds the stock
launcher.

## If something goes wrong

- Stopped at step 1 or 2: uninstall the cabinwright.* packages. Nothing old was changed.
- Stopped at step 3: the old data is untouched, so reinstalling the old package from the old
  release puts it back; the new package then stands by again.
- Launcher: the menu repo's `launcher/restore-launcher.sh` puts the stock launcher back from
  `/data/nemesis/backup/ev6.launcher`.
- Skip-consent: the stock SystemUI backup was copied, not moved; the old copy stays in
  `/data/nemesis/backup/kia-tweaks.systemui`, and the USB rescue
  (`systemui/restore-systemui.sh`) finds either.

The old folders (`/data/nemesis/kia-*`, `/sdcard/kia-*`) are left for you to delete once the
switch is verified.
