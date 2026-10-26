# The App Store as the platform

Status: design, not built yet (target: kia.store 2.0).

## Goal

Installing one NEMESIS package, `kia.store`, sets up the whole system. Everything else
(Tweaks, Edit Menu, tweak packs, apps) comes from the store, and nothing installed from the
store talks to NEMESIS directly. When NEMESIS changes, only the store changes.

## Why

- **Every app is root today.** `/system/bin/nemesis` is setuid root, executable by everyone,
  and doesn't check its caller. An ordinary app uid running `nemesis root 0 -c id` gets
  `uid=0(root)`, tested on CVEV.USA 260507. So any APK the store installs can do anything.
- **Compatibility.** Packages that call `nemesis` or rely on its file layout break when
  NEMESIS changes. A single owner of that surface keeps fixes in one place.

The model is LG webOS Homebrew Channel's: apps are ordinary unprivileged apps, and the few
privileged things live in one small, reviewed component with a narrow, stable API.

## What kia.store ships

| Part | Runs as | Job |
|---|---|---|
| Store app | its own app uid | browse, install, update, remove; the driver's approvals |
| `helper` (exists) | root, NEMESIS service | verifies and installs packages for the store app |
| `broker` (new) | root, NEMESIS service (app_process) | the privileged API apps may use |
| tweak runner (moved from kia-tweaks' `tweaksd`) | root, NEMESIS service | runs every installed package's tweaks unprivileged, performs their declared privileged steps, owns all backups |
| lockdown (new, system tier) | root, at boot | restricts who may run `nemesis` |

The Tweaks app stays a separate store app: the UI and the base tweaks. It requires
`kia.store>=2.0` and talks to the runner through the broker.

## The store writes the manifest

NEMESIS runs a package's install, update and uninstall hooks as root (`preinstall`,
`postinstall`, `preuninstall`, `postuninstall`; an update runs the old version's uninstall
hooks too), plus its root services and `kind=sh` entries. So passing a package's own
`NEMESIS.data` to NEMESIS gives that package root at install time, whatever review says.

For the `app` and `tweaks` tiers the helper therefore never hands NEMESIS the package's
manifest. After the index, size and SHA-256 checks it rebuilds the package in its private
directory:

1. **Allowed files only.** It takes APKs, `tweaks/<id>/` folders (tweaks tier) and `lib/`,
   and drops everything else.
2. **A manifest of its own.** It writes a new `NEMESIS.data` from the signed index entry: id,
   version, name, and one `[app.N]` per APK (package and component from the APK itself).
   There are no hooks, no services, no `execute`, and no `kind=sh` entries.
3. **Install.** It installs that rebuilt zip, and records its SHA-256 next to the index's.

No third-party code runs as root during install, update or removal, so review doesn't have
to catch it. Root enters only through:

- tweak scripts, run by the tweak runner under its rules and shown to the driver as
  "Changes system settings";
- first-party `system` packages, whose manifests pass through unchanged. Their ids are on
  an allowlist built into the store app and helper, not taken from the index's tier field.

A NEMESIS manifest format change then means one generator to fix, not every package.

## Permissions: escalating with the driver's knowledge

Apps run unprivileged. When an app needs more, it asks the broker, and the broker allows only
what the driver has agreed to.

- **Declared.** An index entry lists the capabilities its app may use
  (`capabilities: ["logs", "tweaks"]`), with a one-line plain-language reason for each. CI
  rejects unknown capabilities, and review checks the reasons.
- **Agreed at install.** The install confirmation lists them in plain words ("Read the head
  unit's logs: to send bug reports"). An update that adds a capability asks again before
  installing; one that only drops capabilities doesn't.
- **Enforced at use.** The broker identifies the caller from the socket (uid → package →
  signing certificate recorded at install) and checks the request against what was agreed.
  Anything else is refused and logged.
- **Visible and revocable.** The app's page in the store shows its permissions and when each
  was last used, with a switch per permission. Turning one off takes effect immediately; the
  app sees a refusal and should explain it.
- **Full root.** A `root` capability exists for tools that really need it (a terminal, a
  file manager). The store warns in red that the app can change anything on the head unit,
  including breaking it. It is never granted silently, and its use is logged with the
  command. Review asks why a narrower capability won't do.
- **Asked again at first use** (Magisk's model). `root` and capabilities marked sensitive
  also prompt the first time the app actually uses them: once, until the car is turned off,
  always, or deny. Prompts appear only while the car is parked; a request made while
  driving waits for a decision or fails, and never auto-grants.
- **Seen when used.** A small toast when an app uses root, and a per-app log in the store.
- **Granted to the package, not the process.** A grant is tied to the package id and
  certificate. It ends when the app is removed, and it is not carried over if a new version
  is signed differently.

Capabilities, each with a written threat note before it ships:

| Capability | Lets an app | First user |
|---|---|---|
| `tweaks` | list tweaks and flip their switches | Tweaks app |
| `logs` | read logcat (its own lines, or all with the driver's OK) | bug-report tools |
| `settings` | change an allowlisted set of system settings and props | — |
| `root` | run commands as root (logged) | terminal-type tools |

## Tweaks under the same permissions

Tweaks are most of the root code on the car, so they use the same system as apps instead of
running as root scripts.

- **Scripts run unprivileged.** The runner starts a tweak's scripts as a dedicated non-root
  uid. Privileged steps go through `kt`, a small command the runner provides, which checks
  each step against what the tweak declared.
- **Declared in tweak.conf** (`needs=`), each with a reason, shown to the driver when they
  turn the tweak on, and again if an update adds one:

  | Need | Lets the tweak | Example |
  |---|---|---|
  | `file:<path>@<stock md5>` | replace that one system file, if it is exactly stock | `file:/system/app/HKMC_GPM.apk@c155b0fb…` |
  | `prop:<name>` | set that property | `prop:persist.sys.iap2.wifi` |
  | `setting:<table>/<name>` | change that Android setting | `setting:system/summer_time` |
  | `service:<name>` | start or stop that init service | `service:set_adb_ip` |
  | `kill:<process>` | restart that process (as system) | `kill:com.google.android.projection.sink` |
  | `binder:<service> <codes>` | make those binder calls | `binder:com.hkmc.micom.time 3,4` |
  | `root` | anything (red warning, first-party review, every command logged) | — |

- **The runner owns every change.** `kt replace <path> <new file>` checks the stock md5,
  backs the original up in the runner's own store, writes through a temp file and verifies
  the result. Props, settings and services record their previous values the same way. So
  turning a tweak off, retiring it, removing its pack, and safe mode can all restore the
  originals generically, without the tweak's own scripts. Nothing can be stranded, and no
  tweak carries its own backup code.
- **Status stays the tweak's job** (`status.sh`, unprivileged, read-only), but the runner
  also knows exactly what it changed and can report a mismatch, such as a file changed
  behind its back.
- **The Tweaks app is a client.** It uses the `tweaks` capability through the broker, like
  any app, instead of its own provider handshake.
- **Review gets easier.** A tweak's `needs=` lines are its whole privileged surface. CI shows
  them in the review summary, and a tweak whose scripts try anything undeclared fails at run
  time, not just in review.

Moving the existing tweaks over:

- auto-dst: `binder:com.hkmc.micom.time 3,4`, `setting:system/summer_time`
- skip-consent: two `file:` needs (SystemUI apk and odex)
- the projection pack: `file:` per app and library, `prop:` for the iAP2 props, `service:set_adb_ip`
- aa-reconnect: `kill:com.google.android.projection.sink`

The rebuilding of patched files (deltas, recipes) stays in the tweak, as unprivileged work
in its own directory. Only the final `kt replace` is privileged.

## Tiers

Every index entry has one tier. CI decides it from the package zip itself, not from what the
entry claims, and fails if they differ.

| Tier | May contain | Review | Store shows |
|---|---|---|---|
| `app` | APKs (the store writes the manifest) | normal, plus any capabilities it declares | its permissions, if any |
| `tweaks` | `tweaks/<id>/` folders in the tweak format and `lib/` (the store writes the manifest) | scripts read line by line | "Changes system settings" |
| `system` | anything, including patches to Kia's apps; its own manifest | first-party only (allowlisted in the store); exact firmware match; tested restore and USB rescue | "System change" with the restore notes |

Only `kia.store` itself (and the first-party system packages) may declare NEMESIS services.

## The broker

- A root daemon (Kotlin under app_process, like the helper) on a Unix socket in the abstract
  namespace (`dev.kiastore.broker`).
- **Who's asking:** the peer's uid comes from the socket (`SO_PEERCRED`), never from the
  request. uid → package comes from `/data/system/packages.list`, and the package's signing
  certificate must match the one recorded at install.
- **What they may do:** what the driver agreed to (see Permissions), recorded by the helper
  in `state.json`. A request for anything else is refused and logged.
- **API:** small, versioned requests (`{"v":1,"cap":"tweaks","op":"set","id":...}`), with a
  client in kia-menu's API (`KiaBroker`). An app checks `KiaBroker.version()` and degrades.
- KiaAudio needs no capability: app audio works unprivileged.

## Lockdown

Once the broker covers our own needs, the store restricts `nemesis` to callers that need it:
root, system, and NEMESIS Manager's uid. That means a group/mode change on `/system/bin/nemesis`
at boot, done the system-tier way: check the stock file, keep the original mode, restore on
disable or uninstall, and keep a USB rescue. Before that ships, check:

- what NEMESIS Manager and NEMESIS's own services exec, and as which uid;
- that NEMESIS updates and reinstalls survive it;
- that third-party NEMESIS packages installed outside the store keep working (their
  services run as root, so they should).

The better fix is upstream: an allowlist or a prompt in NEMESIS itself. Until either exists,
store review treats every APK as root-capable, and the store says so.

## Bootstrapping and migration

1. A user installs `kia.store` with NEMESIS Manager (USB or ADB). Its services start:
   helper, broker, runner.
2. The store offers Tweaks, Edit Menu and the rest.
3. Moving from today: kia.store 2.0 takes over tweak running. Tweaks 2.0 drops `tweaksd`
   and requires store 2.0. The runner reads the existing `/data/nemesis/kia-tweaks/state.json`,
   so switches keep their state, and updating the store before Tweaks briefly runs both
   runners. To prevent that, the runner stays idle while an old `tweaksd` is running.

## Borrowed from Magisk

The store plays the part of Magisk: the broker is `magiskd`, the store app is the superuser
manager, tweak packs are modules, and lockdown turns today's unmanaged setuid `nemesis`
into a gated `su`. Two more ideas from it:

- **Safe mode for tweaks.** If the head unit restarts several times within a few minutes
  after a system tweak changed, the runner pauses every system tweak and says so in Tweaks,
  instead of relying on the USB rescue.
- **Systemless tweaks (research).** Bind-mount patched files over the stock ones at each boot
  instead of rewriting `/system`. Turning off would just mean not mounting, and nothing could
  be stranded. NEMESIS services lack CAP_SYS_ADMIN, but init services (`setprop ctl.start`)
  may not; to check.

## Index signing

The index signing key is the root of trust, and today any push to kia-apps `main` is
signed by Actions. At minimum: branch protection with required review on `main`. Better:
sign on the maintainer's machine, or require both keys (2026a and 2026b) so one leaked
secret isn't enough.

## Open questions

- Capabilities the first third-party apps actually need (collect them before building more).
- Whether the runner should sandbox pack scripts further, for example by running
  `status.sh` as a non-root uid.
