#!/bin/sh
# migrate-from-kia.sh: the one-time switch of a car from the kia.* packages (kia.tweaks,
# kia.store, kia.menu) to Cabinwright (cabinwright.tweaks, cabinwright.store,
# cabinwright.menu), run on a computer against the head unit over ADB. The steps and why
# they are safe: docs/migrating-from-kia.md.
#
#   tools/migrate-from-kia.sh [options]            dry run: checks everything, changes nothing
#   tools/migrate-from-kia.sh --apply [options]    steps 1-4, stopping at the first surprise
#   tools/migrate-from-kia.sh --verify [options]   step 5, after the head unit has restarted
#
# options:
#   -s <serial>          adb device (default $ANDROID_SERIAL, else 192.168.0.1:5555)
#   --tweaks <zip>       cabinwright.tweaks package (NEMESIS zip)
#   --store <zip>        cabinwright.store package
#   --menu <zip>         cabinwright.menu package
#   --launcher <apk>     the rebuilt launcher hook (menu repo: launcher/build.sh <stock dir>)
#   --menu-repo <dir>    a checkout of cabinwright/menu, for launcher/install-launcher.sh
#   --state <dir>        where the before/after record is kept
#                        (default ~/.local/state/cabinwright-migrate/<serial>)
#
# It changes the car only with --apply, and then only through `nemesis install`,
# `nemesis uninstall`, files in /data/local/tmp, and the menu repo's install-launcher.sh,
# which is the only thing that touches /system. A dry run reads, as shell or with
# `nemesis root`, and writes nothing on the car or here.
# a && b || die is meant (die whenever the check fails); eval '...' expands later, on purpose
# shellcheck disable=SC2015,SC2016
set -e

SERIAL=${ANDROID_SERIAL:-192.168.0.1:5555}
MODE=dry
TWEAKS_ZIP='' STORE_ZIP='' MENU_ZIP='' LAUNCHER='' MENU_REPO='' STATE=''
FIRMWARE=CVEV.USA.0000.V015.012.260507
# the stock launcher (menu repo: launcher/install-launcher.sh), and where its backup is
SUM_APK=768d9fda8efcd2d38dbe34e58ca37d1a
SUM_ODEX=12c9a96cae35fce9c11fab3918cd912a
LAUNCHER_BACKUP=/data/nemesis/backup/ev6.launcher
TMP=/data/local/tmp/cabinwright-migrate

while [ $# -gt 0 ]; do
    case "$1" in
        --apply) MODE=apply ;;
        --verify) MODE=verify ;;
        -s) SERIAL=$2; shift ;;
        --tweaks) TWEAKS_ZIP=$2; shift ;;
        --store) STORE_ZIP=$2; shift ;;
        --menu) MENU_ZIP=$2; shift ;;
        --launcher) LAUNCHER=$2; shift ;;
        --menu-repo) MENU_REPO=$2; shift ;;
        --state) STATE=$2; shift ;;
        -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
        *) echo "unknown option $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done
STATE=${STATE:-$HOME/.local/state/cabinwright-migrate/$(echo "$SERIAL" | tr -c 'A-Za-z0-9.\n' _)}

say() { echo "$*"; }
step() { echo; echo "== $*"; }
ok() { echo "   ok: $*"; }
note() { echo "   note: $*"; }
would() { echo "   would: $*"; }
die() { echo; echo "STOP: $*" >&2; echo "Nothing after this point was done. See docs/migrating-from-kia.md." >&2; exit 1; }

# ---- the car (adb shell drops exit codes on this adbd: commands print their own)

car() { adb -s "$SERIAL" shell "$1" < /dev/null | tr -d '\r'; }
# as root, through NEMESIS (no single quotes in $1)
root() { car "/system/xbin/nemesis root -c '$1'"; }
# the exit code of a command on the car
car_rc() { car "$1 >/dev/null 2>&1; echo rc=\$?" | sed -n 's/^rc=//p' | tail -n 1; }
root_rc() { root "$1 >/dev/null 2>&1; echo rc=\$?" | sed -n 's/^rc=//p' | tail -n 1; }
md5_of() { root "md5 $1 2>/dev/null" | sed -n 's/^\([0-9a-f]\{32\}\) .*/\1/p'; }
# version= of an installed package ("" when it isn't installed)
installed() { root "cat /data/nemesis/apps/$1/NEMESIS.data 2>/dev/null" | sed -n 's/^version=//p' | head -n 1; }
json() { python3 -I -c "$1" "$2"; }

# ---- local files

zip_field() { unzip -p "$1" NEMESIS.data 2>/dev/null | tr -d '\r' | sed -n "s/^$2=//p" | head -n 1; }
local_md5() { if command -v md5sum >/dev/null; then md5sum "$1" | cut -d' ' -f1; else md5 -q "$1"; fi; }

# tweak states from a state.json: "<id> <want> <state>" lines; problems on stderr, exit 1
TWEAK_STATES='
import json, sys
d = json.loads(open(sys.argv[1]).read() or "{}")
bad = 0
for i, t in sorted(d.get("tweaks", {}).items()):
    flags = [f for f in ("departed", "pending", "orphan") if t.get(f)]
    if t.get("failed"): flags.append("failed")
    if t.get("state") not in ("on", "off", "unsupported"): flags.append("state " + str(t.get("state")))
    print(i, t.get("want") or "-", t.get("state") or "-")
    if flags:
        sys.stderr.write("%s: %s (%s)\n" % (i, ", ".join(flags), t.get("text", "")))
        bad = 1
sys.exit(bad)
'

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# ---- preconditions (every mode)

step "0. Preconditions ($MODE run, device $SERIAL)"
command -v adb >/dev/null || die "adb isn't on the PATH"
command -v python3 >/dev/null || die "python3 isn't on the PATH"
[ "$(adb -s "$SERIAL" get-state 2>/dev/null)" = device ] || die "no adb connection to $SERIAL (adb connect $SERIAL)"
ok "adb connected"
case "$(root id)" in *uid=0*) ok "nemesis root works" ;; *) die "nemesis root -c doesn't give root" ;; esac
fw=$(car "getprop ro.build.product")
[ "$fw" = "$FIRMWARE" ] || die "firmware is '$fw'; this switch was made for $FIRMWARE"
ok "firmware $fw"
# a process whose command line has all of the words (cmdline holds NULs: grep the file on the car)
running() { root "for f in /proc/[0-9]*/cmdline; do grep -q $1 \$f 2>/dev/null && grep -q ${2:-$1} \$f 2>/dev/null && echo \$f; done"; }
busy=$(running nemesis install)
[ -z "$busy" ] || die "a NEMESIS install is running ($busy)"

OLD_TWEAKS=$(installed kia.tweaks) OLD_STORE=$(installed kia.store) OLD_MENU=$(installed kia.menu)
NEW_TWEAKS=$(installed cabinwright.tweaks) NEW_STORE=$(installed cabinwright.store) NEW_MENU=$(installed cabinwright.menu)
say "   installed: kia.tweaks ${OLD_TWEAKS:--} kia.store ${OLD_STORE:--} kia.menu ${OLD_MENU:--} |" \
    "cabinwright.tweaks ${NEW_TWEAKS:--} cabinwright.store ${NEW_STORE:--} cabinwright.menu ${NEW_MENU:--}"
for p in tweaks.projection tweaks.adb; do
    v=$(installed $p); [ -z "$v" ] || note "tweak pack $p $v installed: it carries on as it is"
done

if [ "$MODE" != verify ]; then
    [ -n "$OLD_TWEAKS$OLD_STORE$OLD_MENU" ] || [ -n "$NEW_TWEAKS$NEW_STORE$NEW_MENU" ] || die "neither the kia.* nor the cabinwright.* packages are installed"
    # the old packages have no install hooks, so removing them runs nothing of theirs
    for p in kia.tweaks kia.store kia.menu; do
        [ -n "$(installed $p)" ] || continue
        h=$(root "cat /data/nemesis/apps/$p/NEMESIS.data" | grep -E '^(preinstall|postinstall|preuninstall|postuninstall)=|^types=.*execute' || true)
        [ -z "$h" ] || die "$p declares NEMESIS hooks ($h): removing it would run them"
    done
    ok "kia.* packages declare no install hooks"
    [ -n "$OLD_TWEAKS" ] && case "$OLD_TWEAKS" in 1.[3-9]*|[2-9]*) ;; *) die "kia.tweaks $OLD_TWEAKS: update it to 1.3 first (it retires aa-recover and eth0-guard)" ;; esac

    # the new packages to install
    for v in TWEAKS:cabinwright.tweaks STORE:cabinwright.store MENU:cabinwright.menu; do
        var=${v%%:*} id=${v#*:}
        eval "z=\$${var}_ZIP; have=\$NEW_$var"
        if [ -z "$z" ]; then
            [ -n "$have" ] || die "--$(echo "$var" | tr '[:upper:]' '[:lower:]') <zip> is needed: $id isn't installed yet"
            continue
        fi
        [ -f "$z" ] || die "no such file: $z"
        zid=$(zip_field "$z" id) zv=$(zip_field "$z" version) zt=$(zip_field "$z" types)
        [ "$zid" = "$id" ] || die "$z is '$zid', not $id"
        case "$zt" in *execute*) die "$z is an execute package" ;; esac
        if [ -n "$have" ] && [ "$have" != "$zv" ]; then die "$id $have is installed, the zip is $zv"; fi
        eval "${var}_VERSION=\$zv"
        ok "$z: $id $zv${have:+ (already installed)}"
    done

    # Tweaks: the state now, for comparing after the switch
    if [ -n "$OLD_TWEAKS" ]; then
        root "cat /data/nemesis/kia-tweaks/state.json" > "$WORK/before.json"
        json "$TWEAK_STATES" "$WORK/before.json" > "$WORK/before.txt" 2> "$WORK/before.err" \
            || die "kia.tweaks reports tweaks it isn't done with: $(cat "$WORK/before.err")"
        age=$(( $(car "date +%s") - $(json 'import json,sys; print(json.load(open(sys.argv[1])).get("time", 0))' "$WORK/before.json") ))
        [ "$age" -lt 60 ] || die "kia.tweaks' tweaksd last wrote its state ${age}s ago: it isn't running"
        ok "kia.tweaks' tweaksd running; tweak states (id want state):"
        sed 's/^/      /' "$WORK/before.txt"
    fi
    # the store helper's record, and Edit Menu's layout
    [ -z "$OLD_STORE" ] || note "kia.store's record: $(root "cat /data/nemesis/kia-store/state.json" | json 'import json,sys; d=json.load(open(sys.argv[1])); print("installed", d.get("installed"), "sequence", d.get("sequence"))' /dev/stdin)"
    [ -z "$OLD_MENU" ] || { [ "$(car_rc "ls /sdcard/kia-menu/layout.json")" = 0 ] && note "Edit Menu layout to import: /sdcard/kia-menu/layout.json" || note "no Edit Menu layout saved (/sdcard/kia-menu/layout.json): nothing to import"; }

    # the launcher: stock, or a hook with the stock backup kept
    sys_apk=$(md5_of /system/app/Launcher_WP3.apk)
    bk_ok=; [ "$(md5_of $LAUNCHER_BACKUP/Launcher_WP3.apk)" = $SUM_APK ] && [ "$(md5_of $LAUNCHER_BACKUP/Launcher_WP3.odex)" = $SUM_ODEX ] && bk_ok=1
    if [ "$sys_apk" = $SUM_APK ]; then ok "launcher on /system is stock (no hook yet)"
    elif [ -n "$bk_ok" ]; then ok "a launcher hook is installed ($sys_apk); stock backup in $LAUNCHER_BACKUP"
    else die "the launcher on /system isn't stock ($sys_apk) and $LAUNCHER_BACKUP doesn't hold the stock files"
    fi
    if [ -n "$LAUNCHER" ]; then
        [ -f "$LAUNCHER" ] || die "no such file: $LAUNCHER"
        [ -n "$MENU_REPO" ] && [ -f "$MENU_REPO/launcher/install-launcher.sh" ] || die "--menu-repo <cabinwright/menu checkout> is needed with --launcher"
        grep -q 'dev.cabinwright.category.ALL_MENUS' "$MENU_REPO/api/contract/src/dev/cabinwright/api/contract/MenuContract.java" 2>/dev/null \
            || die "$MENU_REPO isn't a cabinwright/menu checkout"
        unzip -p "$LAUNCHER" classes.dex 2>/dev/null | LC_ALL=C grep -q 'dev/cabinwright/launcher' \
            || die "$LAUNCHER has no Cabinwright launcher hook in its classes.dex"
        ! unzip -p "$LAUNCHER" classes.dex | LC_ALL=C grep -q 'dev/kiamenu' || die "$LAUNCHER is a kia-menu hook"
        ok "launcher hook $LAUNCHER ($(local_md5 "$LAUNCHER"))"
    else
        note "no --launcher: step 4 (rebuild the launcher hook) will be left to do by hand"
    fi
fi

# ---- the steps

wait_for() {   # wait_for <seconds> <what> <command...>: until the command succeeds
    t=$1 what=$2; shift 2
    i=0
    while ! "$@"; do
        i=$((i + 3)); [ $i -lt "$t" ] || return 1
        sleep 3
    done
    ok "$what"
}
tweaks_state() { root "cat /data/nemesis/cabinwright/tweaks/state.json" > "$WORK/now.json"; }
tweaks_imported() { root "cat /data/nemesis/cabinwright/tweaks/imported 2>/dev/null"; }
standing_by() { tweaks_state; grep -q '"standby"' "$WORK/now.json"; }
taken_over() { tweaks_state; ! grep -q '"standby"' "$WORK/now.json" && [ "$(tweaks_imported)" = final ]; }
store_import() { root "cat /data/nemesis/cabinwright/store/state.json 2>/dev/null" | json 'import json,sys
try: d = json.load(open(sys.argv[1]))
except ValueError: d = {}
i = d.get("imported", {})
print("final" if i.get("final") else ("yes" if i else "no"))' /dev/stdin; }
store_imported() { [ "$(store_import)" != no ]; }
store_final() { [ "$(store_import)" = final ]; }
legacy_helper() { [ -n "$(running dev.kiastore.helper.Helper)" ]; }
# the tweaks as they are now against before.txt: same switch and state for every tweak
same_tweaks() {
    tweaks_state
    json "$TWEAK_STATES" "$WORK/now.json" > "$WORK/now.txt" 2> "$WORK/now.err" || return 1
    while read -r id w s; do
        case "$s" in on|off) ;; *) continue ;; esac
        grep -qx "$id $w $s" "$WORK/now.txt" || { echo "   $id was '$w $s', now '$(grep "^$id " "$WORK/now.txt" | cut -d' ' -f2-)'" >> "$WORK/diff"; return 1; }
    done < "$1"
}
nemesis_do() {   # nemesis_do <args>: runs `nemesis <args>` on the car, stops if it fails
    out=$(root "/system/xbin/nemesis $1 2>&1; echo rc=\$?")
    echo "$out" | sed 's/^/      /' | tail -n 8
    [ "$(echo "$out" | sed -n 's/^rc=//p' | tail -n 1)" = 0 ] || die "nemesis $1 failed"
}
install_new() {   # install_new <id> <zip> <version>
    if [ "$(installed "$1")" = "$3" ]; then ok "$1 $3 already installed"; return; fi
    if [ $MODE = dry ]; then would "push $2 and run: nemesis install $TMP/$1.zip"; return; fi
    car "mkdir -p $TMP" >/dev/null
    adb -s "$SERIAL" push "$2" "$TMP/$1.zip" >/dev/null || die "adb push $2"
    nemesis_do "install $TMP/$1.zip"
    [ "$(installed "$1")" = "$3" ] || die "$1 isn't installed at $3 after nemesis install"
    ok "installed $1 $3"
}
remove_old() {   # remove_old <id>
    if [ -z "$(installed "$1")" ]; then ok "$1 already removed"; return; fi
    if [ $MODE = dry ]; then would "run: nemesis uninstall $1"; return; fi
    nemesis_do "uninstall $1"
    [ -z "$(installed "$1")" ] || die "$1 is still installed after nemesis uninstall"
    ok "removed $1"
}

if [ "$MODE" != verify ]; then
    mkdir -p "$WORK"
    [ $MODE = dry ] || { mkdir -p "$STATE"; [ -f "$WORK/before.txt" ] && [ ! -f "$STATE/before.txt" ] && cp "$WORK/before.txt" "$STATE/before.txt"; }
    [ -f "$STATE/before.txt" ] && cp "$STATE/before.txt" "$WORK/before.txt" 2>/dev/null || true

    step "1. Install the new packages beside the old ones"
    [ -z "$TWEAKS_ZIP" ] || install_new cabinwright.tweaks "$TWEAKS_ZIP" "$TWEAKS_VERSION"
    [ -z "$STORE_ZIP" ] || install_new cabinwright.store "$STORE_ZIP" "$STORE_VERSION"
    [ -z "$MENU_ZIP" ] || install_new cabinwright.menu "$MENU_ZIP" "$MENU_VERSION"

    step "2. Let them import the old data"
    if [ $MODE = dry ]; then
        would "wait for cabinwright.tweaks' tweaksd to import /data/nemesis/kia-tweaks and stand by (\$DATA/imported, \"standby\" in its state.json)"
        would "wait for cabinwright.store's helper to import /data/nemesis/kia-store/state.json and wait for kia.store's helper"
        note "Edit Menu imports /sdcard/kia-menu/layout.json the first time the launcher hook asks it (step 5)"
    else
        if [ -n "$OLD_TWEAKS" ]; then
            wait_for 90 "cabinwright.tweaks imported kia.tweaks' data and stands by" standing_by || die "cabinwright.tweaks' tweaksd isn't standing by (see its log: nemesis log)"
            [ -n "$(tweaks_imported)" ] && [ "$(tweaks_imported)" != started ] || die "cabinwright.tweaks hasn't imported kia.tweaks' data"
            for id in $(root "ls /data/nemesis/kia-tweaks/want"); do
                [ "$(root "cat /data/nemesis/kia-tweaks/want/$id")" = "$(root "cat /data/nemesis/cabinwright/tweaks/want/$id")" ] || die "switch $id wasn't imported the same"
            done
            ok "switches imported the same"
        fi
        [ -z "$OLD_STORE" ] || wait_for 60 "cabinwright.store imported kia.store's record" store_imported || die "cabinwright.store's helper hasn't imported kia.store's record"
    fi

    step "3. Remove the old packages (no install hooks: nothing of theirs runs)"
    if [ -n "$OLD_TWEAKS" ]; then
        remove_old kia.tweaks
        if [ $MODE = apply ]; then
            : > "$WORK/diff"
            wait_for 90 "cabinwright.tweaks' tweaksd took over" taken_over || die "cabinwright.tweaks didn't take over from kia.tweaks"
            wait_for 60 "every tweak as it was before" same_tweaks "$WORK/before.txt" \
                || die "the tweaks aren't as they were before: $(cat "$WORK/diff" "$WORK/now.err" 2>/dev/null)"
        else
            would "wait for cabinwright.tweaks to take over, then compare every tweak with the list above"
        fi
    fi
    if [ -n "$OLD_STORE" ]; then
        remove_old kia.store
        if [ $MODE = apply ]; then
            wait_for 60 "kia.store's helper stopped" eval '! legacy_helper' || die "kia.store's helper still runs"
            wait_for 60 "cabinwright.store's helper carries on with the imported record" store_final || die "cabinwright.store's import isn't final"
        fi
    fi
    [ -z "$OLD_MENU" ] || remove_old kia.menu

    step "4. Rebuild the launcher hook"
    if [ -z "$LAUNCHER" ]; then
        note "build it from the stock files (cabinwright/menu: launcher/build.sh <stock dir>), then run this again with --launcher <apk> --menu-repo <dir>, or install it by hand (README)"
    elif [ "$(md5_of /system/app/Launcher_WP3.apk)" = "$(local_md5 "$LAUNCHER")" ]; then
        ok "this hook is already installed"
    elif [ $MODE = dry ]; then
        would "push $LAUNCHER and $MENU_REPO/launcher/install-launcher.sh to $TMP, run install-launcher.sh as root (it keeps $LAUNCHER_BACKUP and replaces the hook)"
    else
        car "mkdir -p $TMP" >/dev/null
        adb -s "$SERIAL" push "$LAUNCHER" "$TMP/Launcher_WP3.apk" >/dev/null || die "adb push $LAUNCHER"
        adb -s "$SERIAL" push "$MENU_REPO/launcher/install-launcher.sh" "$TMP/install-launcher.sh" >/dev/null || die "adb push install-launcher.sh"
        out=$(root "sh $TMP/install-launcher.sh $TMP/Launcher_WP3.apk")
        echo "$out" | sed 's/^/      /'
        case "$out" in *"done - reboot to load it"*) ;; *) die "install-launcher.sh didn't finish (see above)" ;; esac
        [ "$(md5_of /system/app/Launcher_WP3.apk)" = "$(local_md5 "$LAUNCHER")" ] || die "the launcher on /system isn't the hook just installed"
        local_md5 "$LAUNCHER" > "$STATE/launcher.md5"
        ok "launcher hook replaced; the stock backup stays in $LAUNCHER_BACKUP"
    fi

    echo
    if [ $MODE = dry ]; then
        say "Dry run: nothing was changed. Run again with --apply to do steps 1-4."
    else
        car "rm -r $TMP" >/dev/null 2>&1 || true
        say "Steps 1-4 done. Restart the head unit (the launcher hook loads at start), then run:"
        say "  $0 --verify -s $SERIAL"
    fi
    exit 0
fi

# ---- verify (after the restart)

step "5. Verify"
[ -z "$OLD_TWEAKS$OLD_STORE$OLD_MENU" ] || die "still installed: kia.tweaks ${OLD_TWEAKS:--} kia.store ${OLD_STORE:--} kia.menu ${OLD_MENU:--}"
ok "the kia.* packages are gone"
[ -n "$NEW_TWEAKS" ] && [ -n "$NEW_STORE" ] && [ -n "$NEW_MENU" ] || die "not all of cabinwright.tweaks, .store and .menu are installed"
ok "cabinwright.tweaks $NEW_TWEAKS, cabinwright.store $NEW_STORE, cabinwright.menu $NEW_MENU"
wait_for 60 "tweaksd running, not standing by" taken_over || die "cabinwright.tweaks' tweaksd isn't running the tweaks"
if [ -f "$STATE/before.txt" ]; then
    : > "$WORK/diff"
    wait_for 120 "every tweak as it was before the switch" same_tweaks "$STATE/before.txt" \
        || die "the tweaks aren't as they were before: $(cat "$WORK/diff" "$WORK/now.err" 2>/dev/null)"
else
    note "no record of the tweaks before the switch in $STATE"
fi
json "$TWEAK_STATES" "$WORK/now.json" | sed 's/^/      /' || true
legacy_helper && die "kia.store's helper is running"
car "cat /sdcard/cabinwright/store/status.json" > "$WORK/status.json"
json 'import json,sys,time
d = json.load(open(sys.argv[1]))
sys.exit(1 if d.get("waiting") else 0)' "$WORK/status.json" || die "cabinwright.store's helper says it is waiting"
ok "cabinwright.store's helper running ($(json 'import json,sys; d=json.load(open(sys.argv[1])); print("helper", d.get("helper"), "installed by it:", d.get("store_installed"))' "$WORK/status.json"))"
store_final || die "cabinwright.store's record isn't final"
if [ "$(car_rc "ls /sdcard/kia-menu/layout.json")" = 0 ]; then
    wait_for 60 "Edit Menu imported the layout" eval '[ "$(car_rc "ls /sdcard/cabinwright/menu/layout.json")" = 0 ]' \
        || die "Edit Menu hasn't imported /sdcard/kia-menu/layout.json: open All Menus once (the hook asks Edit Menu for the layout), then verify again"
fi
sys_apk=$(md5_of /system/app/Launcher_WP3.apk)
hook=$(root "for f in /system/app/Launcher_WP3.odex /system/app/Launcher_WP3.apk; do grep -q dev/cabinwright/launcher \$f 2>/dev/null && echo \$f; done")
[ -n "$hook" ] || die "the launcher on /system has no Cabinwright hook: step 4 (it still asks kia.menu, which is gone)"
if [ -f "$STATE/launcher.md5" ]; then
    [ "$sys_apk" = "$(cat "$STATE/launcher.md5")" ] || die "the launcher on /system ($sys_apk) isn't the hook step 4 installed"
    ok "launcher hook in place ($sys_apk)"
else
    note "launcher on /system: $sys_apk (step 4 was done by hand)"
fi
[ "$(md5_of $LAUNCHER_BACKUP/Launcher_WP3.apk)" = $SUM_APK ] && [ "$(md5_of $LAUNCHER_BACKUP/Launcher_WP3.odex)" = $SUM_ODEX ] \
    && ok "stock launcher backup intact" || die "$LAUNCHER_BACKUP doesn't hold the stock launcher"
echo
say "The switch to Cabinwright is complete."
