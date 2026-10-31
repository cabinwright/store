# Cabinwright design

This is the design of Cabinwright 1.0, and the contract every repo builds against. It
describes the system as it is; it has no history section, because 1.0 is the first release.

## Principles

- **One privileged component.** Installing one NEMESIS package, `cabinwright.store`, sets up
  the whole system. Everything else comes from the store, and nothing installed from the store
  runs as root or talks to NEMESIS directly. When NEMESIS changes, only the store changes.
- **Escalation only with the driver's knowledge.** Apps and tweaks declare what they need, the
  driver agrees, the store enforces it, and every use is visible and revocable (the Magisk
  model).
- **Every change can be undone without the code that made it.** The store records each
  privileged change before making it, so turning a tweak off, removing its pack, or safe mode
  restores the original generically.
- **Plain words for drivers, exact contracts for developers.**

## Parts

| Part | Repo | Runs as | Job |
|---|---|---|---|
| Store app `app.cabinwright.store` | store | its own app uid | browse, install, update, remove; permission prompts and the per-app permission pages; safe mode notice |
| `cabinwrightd` | store | root, NEMESIS service, app_process | installer, broker, tweak runner, safe mode, lockdown, in one process |
| `kt` | store | the calling tweak's uid | the command tweak scripts use for privileged steps |
| Tweaks app `app.cabinwright.tweaks` + base tweaks | tweaks | app uid; tweaks via the runner | list and switch tweaks (a broker client) |
| Edit Menu `app.cabinwright.menu` + Cabinwright API | menu | app uid | menu editor; `CarPage`, `CarAudio`, `CarBroker` client |
| Tweak packs `tweaks.projection`, `tweaks.adb` | tweaks-projection, tweaks-adb | via the runner | tweaks only |
| Index | apps | GitHub Actions + maintainer | the signed list of what the store offers |

`cabinwrightd` is one daemon rather than several so there is one socket, one state file and
one place that talks to NEMESIS.

## Packages and tiers

Every index entry has a tier. CI derives it from the package zip and fails if the entry says
otherwise.

| Tier | Contents | Manifest | Root |
|---|---|---|---|
| `app` | APKs only | written by the store | none; capabilities via the broker |
| `tweaks` | `tweaks/<id>/` folders, `lib/`, and top-level APKs | written by the store | only through `kt`, within the tweak's `needs=`; its APKs as for `app` |
| `system` | anything | the package's own | yes. Only ids on the allowlist compiled into the store (`cabinwright.store`), never taken from the index |

### The store writes the manifest

For `app` and `tweaks` packages the installer never gives NEMESIS the package's own
`NEMESIS.data`. After the size and SHA-256 checks against the signed index it rebuilds the zip
in its private directory:

1. It keeps `*.apk` at the top level, plus `tweaks/<id>/**` and `lib/**` for `tweaks`. The
   package's own `NEMESIS.data` is read for nothing and replaced (CI reads the version from
   it). Anything else fails the install rather than being dropped silently, since the index check already
   promised what the zip holds.
2. It writes a new `NEMESIS.data`: `[nemesis]` with `id`, `name`, `version` and `types` from the
   index entry, and one `[app.N]` per APK with `kind=apk`, `path`, `package` and `component` read
   from the APK itself (its launcher activity). `component=` is what NEMESIS Manager's Launch
   button starts. No hooks (`preinstall` and the rest), `[service.N]`, `execute`, or
   `kind=sh`/`instance` entries, ever. NEMESIS ignores `version=`; the store tracks versions in
   its own state.
3. It installs the rebuilt zip and records the APKs' signing certificates (SHA-256 of the
   first certificate) for the broker.

A tweak's files are made readable to tweak uids (folders 755, files 644) and nothing
in a package is executable except through the runner.

## Tweaks

A tweak is a folder `tweaks/<id>/` in any `tweaks`-tier package (or in `cabinwright.tweaks`).

### tweak.conf

```
name=Automatic daylight saving time
summary=Changes the clock for daylight saving time
description=Moves the clock forward in March and back in November.
firmware=CVEV.USA.0000.V015.012.260507     exact strings, comma-separated, or *
kind=service | oneshot
default=off | on
conflicts=<ids>                             optional
need=setting:system/summer_time | Turn the head unit's own daylight-saving switch
need=binder:com.hkmc.micom.time 3,4 | Set the clock through the head unit's time service
```

`risk` is not declared: a tweak is "Changes system files" when it has any `file:` need and
"Changes system settings" otherwise. Unknown keys fail CI and are ignored on the car.

### Needs

Each `need=` line is `<need> | <reason>`, the reason in plain words for the driver.

| Need | Allows | kt commands |
|---|---|---|
| `file:<path>@<version>[,<version>…]` | replace that file when its original is exactly one of those versions: an md5, `absent` (the file may be created) or `link:<target>` (a symlink) | `kt file replace [--under] <path> <new> [<mode>]`, `kt file adopt`, `kt file original` |
| `prop:<name>` | set that property | `kt prop set <name> <value>` |
| `setting:<table>/<name>` | change that Android setting | `kt setting put <table> <name> <value>` |
| `service:<name>` | start or stop that init service | `kt service start\|stop <name>` |
| `kill:<process>` | restart that process | `kt kill <process>` |
| `logs:<tag>` | read that log tag | `kt logcat <tag>:<priority>` |
| `binder:<service> <codes>` | those binder transaction codes | `kt binder <service> <code> <args…>` (`service call` syntax) |
| `root` | anything (red warning; first-party review only) | `kt root <command>` |

Reads need no declaration: `kt prop get`, `kt setting get`, `kt file md5 <path>`, `kt ps`.
`kt file md5` answers for `/system`, `/vendor`, `/data/nemesis/backup` and the tweak's declared
files; anywhere else it fails without counting against the tweak.

Details of the `kt` commands:

- `kt file replace <path> <new> [<mode>]`: the new file gets `<mode>`, else the original's mode
  and owner, else 0644 root:root for a created file. Undo restores the original exactly: deletes
  a created file, puts a symlink back as a symlink.
- **Two tweaks may change one file.** The runner keeps a stack of layers per file. A replace
  checks the file's *original* (the bottom of the stack) against the need's versions, not its
  current content. Turning a tweak off removes its layer: if it is on top, the layer below
  comes back; if it is lower down, the layer above it takes over its original. `--under` puts
  the new layer below the other tweaks' layers. "Changed by something else" compares only the
  top layer.
- `kt file original <path> <dest>` copies the stock original of a declared file into
  `$TWEAK_DATA`.
- `kt file adopt <path> <original>|absent` and `kt prop adopt <name> <previous>` take over a
  change something else already made: they record the original without changing anything, so
  turning the tweak off restores it. The `<original>` file must be one of the need's declared
  versions; cabinwrightd reads it as root. `kt prop adopt` does nothing if this tweak already
  recorded that property.
- `kt logcat <tag>:<priority>` streams that tag's lines, starting with those already in the log, until the log ends or the caller exits (one connection,
  frames until it closes).
- `kt kill`: NEMESIS gives services root without CAP_KILL, so cabinwrightd sends the signal as
  the target process's own uid.
- Restoring a property that was unset deletes `/data/property/<name>` rather than setting it
  to an empty value.

`kt keep <need>` (refused anywhere but `disable.sh`) drops the journal entries for one of the tweak's own
needs, so turning the tweak off leaves that value as it is instead of restoring it. auto-dst
uses it: the clock's daylight-saving setting should match the clock, not go back to what it
was when the tweak was turned on.

### What the runner records

Every mutating `kt` call is journaled per tweak before it happens: the original file (copied
to `/data/nemesis/cabinwright/backup/<tweak>/files/<absolute path>`, e.g.
`backup/skip-consent/files/system/priv-app/SystemUI.apk`), the previous property or setting value, or
nothing for actions that leave no state (`kill`, `binder`, `service`, `root`). Undo replays
the journal backwards: files are put back byte for byte and verified, values restored.
`binder`, `kill`, `service` and `root` steps cannot be undone generically, so a tweak that
uses them for lasting changes must ship `disable.sh`. CI requires it for oneshot tweaks with
those needs; a service tweak may ship one when its steps outlast `run.sh`.

`kt file replace` remounts `/system` as needed, writes to a temporary file next to the target,
gives it the original's mode and owner, verifies it, and renames it into place. `kt binder`
prints `service call`'s own output (`Result: Parcel(…)`).

### Running

- **oneshot:** on → `enable.sh`; off → `disable.sh` if present, then the journal is replayed
  for whatever is left. `status.sh` (optional) prints `on|off|error <text>`; without it the
  runner reports from the journal. A `run.sh` beside `enable.sh` runs while the tweak is on, as
  a service tweak's does, for a job that goes with the change.
- **`default=on`:** the tweak turns on by itself once the driver has agreed to its needs at
  install. After that the switch is theirs.
- **service:** on → `run.sh` runs while the switch is on; off → it is stopped, `disable.sh`
  runs if present, then its journal is replayed.
- Scripts run as the tweak's own uid, allocated by the runner from 90000–98999 (unused on
  Android 4.4, unlike 99000 and up, Android's isolated processes, which any app can start; no
  package, no permissions), in the tweak's folder, with
  `$TWEAK_ID`, `$TWEAK_DIR`, `$TWEAK_DATA` (a private directory owned by that uid) and `kt` on
  `$PATH`. That uid can run `sh`, toolbox, `getprop`, `app_process` and `dexopt`, read its
  folder and its package's `lib/`, and write `$TWEAK_DATA`,
  and nothing else: settings, `am`, `service call`, logcat, the sdcard and the network all go
  through `kt`. The uid is also how the runner knows which tweak a `kt` call comes from.
- A tweak uid has no supplementary groups when the daemon was started at boot. NEMESIS never
  clears them, so a daemon started from ADB (`nemesis service start`) passes the shell's groups on
  to tweaks until the next restart.
- `kt` works only for a tweak that is on, or has a script running, and whose needs the driver
  agreed to. When a tweak goes off its uid's processes are killed, after `disable.sh` too.
- `kt` steps run as root in `cabinwrightd`.
- A `kt` call outside the tweak's needs fails with exit 70 and is logged; the tweak goes to
  `error` with "Tried something it didn't declare".
- `firmware=` that doesn't match: the tweak is listed but can't be turned on.
- `conflicts=`: a tweak waits until the tweaks it conflicts with are off. Each round turns
  tweaks off before turning any on.
- **A pack is removed or a tweak disappears in an update:** after the update window (no
  `nemesis install`/`uninstall` running for 60 s), the runner replays the journal of each of its
  tweaks that was on, then forgets it. Nothing is left behind and no copy of the pack is kept.
- **A pack is updated:** service tweaks restart; a oneshot tweak that is on and whose
  `enable.sh` changed is re-applied (`disable` path, then `enable`).
- **Status** combines the script's report with the journal: a recorded file whose content no
  longer matches what the runner wrote is reported as "Changed by something else".

### Safe mode

After a change to a tweak with a `file:` need, the runner counts head unit starts that end within
three minutes (`short_boots` in `state.json`; the car's clock can't be trusted across a boot). After
three in a row, the runner replays the journals of the tweaks with file changes, pauses them, and the Tweaks app and store show "Safe mode:
system tweaks were turned off after repeated restarts", with a button to resume.

## Capabilities and the broker

### Capabilities

| Capability | Lets an app | Prompt at first use |
|---|---|---|
| `tweaks` | list tweaks, read their status and log, switch them | no |
| `logs` | read logcat | yes |
| `settings` | change the settings and properties on the store's allowlist | no |
| `root` | run commands as root; every command logged | yes, always red |

Index fields the store reads, per version: `tier`, `capabilities` (`{cap: reason}`), and
`review.tweaks[]` with `id` and `needs` (a list of `{need, reason}`, compared exactly with the
zip's tweak.conf files). A version without `tier` isn't installed. An index entry declares capabilities with reasons:
`"capabilities": {"tweaks": "Shows and switches your tweaks"}`. CI rejects unknown ones.

- **At install** the confirmation lists each capability and its reason, and each tweak's needs.
  An update that adds a capability or need asks again first.
- **At first use**, `logs` and `root` ask: Allow once / Until the car is turned off / Always /
  Deny. The prompt appears only while the car is parked; a request made while driving fails
  with `not_parked` and the app may retry later. Nothing is ever granted without an answer.
- **At any time** the app's page in the store lists its capabilities, when each was last
  used, and a switch for each. Turning one off takes effect on the next request.
- **Seen when used:** `root` use shows a toast ("<App> used root") and every command is in the
  app's log in the store.
- **Bound to the package and its certificate.** Grants end when the app is removed, and are
  dropped if an update is signed with a different certificate.

### Protocol

- Abstract Unix socket `app.cabinwright.broker`. Frames are a 4-byte big-endian length plus
  UTF-8 JSON, at most 1 MiB. One request per frame, one response per request.
- The caller is identified by the socket's peer uid only (`SO_PEERCRED` via
  `LocalSocket.getPeerCredentials()`), never by anything in the request. Abstract sockets have
  no permissions, so every app can connect: identity is the only gate.
- uid → package through `/data/system/packages.list`; the package's current certificate
  from `/data/system/packages.xml`, compared with the certificate recorded at install (read
  from the APK's own signature in the package zip). The store app itself is checked against
  `store.apk` in the installed cabinwright.store package. A uid shared by several packages is refused. Callers that aren't
  store-installed packages, the store app, or a running tweak's uid get `unknown_caller`.
- Request: `{"v":1,"cap":"tweaks","op":"set","args":{"id":"auto-dst","on":true}}`.
  Response: `{"ok":true,"data":…}` or `{"ok":false,"error":"<code>","message":"<plain words>"}`.
  Error codes: `unknown_caller`, `not_granted`, `denied`, `not_parked`, `bad_request`,
  `unsupported`, `failed`. The client adds `not_running`, `timeout` and `io_error`.
- `{"v":1,"cap":"broker","op":"hello"}` answers every caller: the broker version, the caller's
  package (empty if the store didn't install it) and its grants. Clients degrade on an older
  broker.

Operations:

| cap | op | args → data |
|---|---|---|
| `broker` | `hello` | → `{version, package, grants}` |
| `tweaks` | `list` | → `{tweaks: [...], safe_mode}`; each tweak: id, package (NEMESIS id), name, summary, description, kind, needs (`[{need, reason}]`), firmware (list), firmware_ok, desired, state, text, paused |
| `tweaks` | `set` | `{id, on}` → `{}`; turning on a tweak with needs the driver hasn't agreed to fails `not_granted` (the Tweaks app sends the driver to the store's confirmation) |
| `tweaks` | `log` | `{id}` → `{lines}` |
| `logs` | `read` | `{filter?, lines?}` → `{lines}` |
| `settings` | `get` | `{table, name}` → `{value}`. `put` isn't built, and the allowlist is empty until an app needs an entry |
| `root` | `exec` | `{command, timeout_ms?}` → `{code, stdout, stderr}` |
| `store` | `install` | `{id, version}` → `{request_id}`. The app calls it only after the driver confirmed the version's capabilities and needs (from the verified index); that confirmation is the agreement. The daemon reads `index.json`, `index.json.sig` and `downloads/<id>-<version>.zip` from `/sdcard/cabinwright/store/` |
| `store` | `uninstall` | `{id}` → `{request_id}`; only packages the store installed |
| `store` | `status` | → `{busy, firmware, sequence, debug, lockdown, safe_mode, packages: [{id, name, version, types, tier, store_installed}], requests: [{request_id, op, id, version, state: working\|ok\|failed, step, message, log}]}` (the last 20 requests) |
| `store` | `grants` | `{id?}` → `{packages: {<id>: {<cap>: {state, reason, last_used}}}}` |
| `store` | `grant_set` | `{id, cap, on}` → `{}` |
| `store` | `prompt_answer` | `{request_id, choice: once\|session\|always\|deny}` → `{}` |
| `store` | `resume_tweaks` | → `{}`: leaves safe mode |
| `store` | `lockdown` | `{on?}` → `{on}` |
| `store` | `log` | `{id}` → `{lines}`: that package's root commands and prompts |

Formats: times (`last_used`) are seconds since the epoch; `lines` and `log` are lists of
strings; `requests` are oldest first. Grant states are `agreed`, `always`, `session`, `deny` and
`off`; `grant_set` with `on: true` puts any of them back to `agreed` (prompting capabilities ask
again). A request that is cut off by the daemon restarting during a cabinwright.store update
is finished by the new daemon from `pending.json` and keeps its `request_id`. The `system`
allowlist is checked by the daemon; the app's copy only hides what would be refused.

`kt` talks to the same socket with `cap: "kt"`, identified by its tweak uid.
`kt` is a small Kotlin program run with app_process (like `cabinwrightd`); `cabinwrightd`
prepares its dalvik-cache at start so a tweak uid can run it without writing anything.

### Prompts

The broker asks the store app to show a prompt by starting its non-exported `PromptActivity` (as root,
with the request id); the store app answers with `store/prompt_answer`. Unanswered prompts
expire after 60 s as `denied` for that request only.

**Parked** means the gear is P: `cabinwrightd` calls `getGearPStatus` on `com.hkmc.micom.system`,
finding the code by name in the car's own framework, and 1 means Park (seen on an Ioniq 5; to be
confirmed on the EV6). `vehicle.json` can override the probe. If the gear can't be read, the car
counts as not parked.

## Index

`apps/<id>.json` per app as today, plus per version: `tier`, `capabilities`, and the CI review
(files, needs per tweak, hooks if `system`). The store shows the review's needs at install,
and the installer refuses a zip whose tweaks' needs differ from the review.

**Signing.** GitHub Actions signs the index with the `INDEX_SIGNING_KEY` secret when `main`
changes, and re-signs weekly. The key is protected by what can reach `main`: required checks,
signed commits, linear history, rules applied to admins too. The publish workflow runs only
on `main` and from a `publish` environment that only `main` may deploy to. Signatures expire
after 90 days, so a stale copy can't be replayed for long.

## Lockdown

A head unit isn't built for installing whatever its owner likes. A store that adds third-party
apps should be careful about who can reach NEMESIS's root access, so Cabinwright means to narrow
that path: apps reach root only through `cabinwrightd`, with the driver's permission. Turning it
off or removing the store would restore NEMESIS's own defaults, and a restart is the USB rescue.

**It can't be enforced from outside NEMESIS, so it is off and can't be turned on.** A caller that
isn't root doesn't use the setuid bit at all: `nemesis` hands the command to NEMESIS's own daemon
over a socket, so a mode change on the binary doesn't stop it, and any app can speak that protocol
itself. Every `nemesis` command run as root also sets the binary back to 04755, as do its boot
hooks. It needs NEMESIS to check its callers, which is being settled with NEMESIS's maintainer.
Until then `store/status` reports `lockdown: false`, `store/lockdown {on: true}` answers
`unsupported`, and any app can still reach root without the broker. The switch stays honest rather
than showing protection the car doesn't have.

## Files on the car

| Path | What |
|---|---|
| `/data/nemesis/cabinwright/state.json` | grants, recorded certificates, desired tweak states, safe mode |
| `/data/nemesis/cabinwright/journal/<tweak>.json` | the runner's journal |
| `/data/nemesis/cabinwright/backup/<tweak>/` | original files |
| `/data/nemesis/cabinwright/tweakdata/<tweak>/` | `$TWEAK_DATA` |
| `/data/nemesis/cabinwright/log/` | broker and runner logs, rotated |
| `/data/nemesis/cabinwright/debug-index` | test builds only: the URL of a debug index (root-only) |
| `/data/nemesis/cabinwright/vehicle.json` | root-only, optional: overrides the gear probe for "parked" |

The daemon has no network access, so the store app downloads the index, its signature and
package zips to `/sdcard/cabinwright/store/` and the daemon verifies them itself (signature,
size, SHA-256). Nothing else lives on the sdcard.

## Store app intents

- `app.cabinwright.store.action.SHOW_APP` with extra `package` (a NEMESIS package id): opens
  that package's page, with its permissions. Without the extra it opens the store's main page,
  which shows the safe-mode notice and its Resume button when safe mode is on.
