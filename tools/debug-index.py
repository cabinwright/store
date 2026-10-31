#!/usr/bin/env python3
"""Makes a signed debug index for testing cabinwright.store on a car, without GitHub.

usage: debug-index.py <out dir> <package.zip>... [--apps-dir ../apps/apps] [--sequence N]
                      [--key ~/.config/cabinwright/store-test.pem]
                      [--base file:///sdcard/cabinwright/store-debug/] [--expires-days 90]
                      [--allow-errors]

Each zip is reviewed by the index repo's own tools/validate.py (imported from the apps
checkout next to --apps-dir), so every version carries what the published index carries:
`tier` and `capabilities` from the app's apps/<id>.json entry (the version matching the zip's
NEMESIS.data, else the entry's newest), and the CI `review` with `tweaks[]` and their needs.
The entry's other fields (name, firmware, requires, notes...) and its icons/<id>.png come along
too. A package without an entry, or whose review has errors (a tier that doesn't match, a bad
tweak.conf), stops the index unless --allow-errors.

The zips and icons are copied to <out dir> and their URLs point at --base. Writes index.json,
index.json.sig and the zips; push the directory to the car and point the store at it
(root-only marker file):

  adb push <out dir>/. /sdcard/cabinwright/store-debug/
  adb shell "nemesis root -c 'echo file:///sdcard/cabinwright/store-debug/ > /data/nemesis/cabinwright/debug-index;
             chmod 644 /data/nemesis/cabinwright/debug-index'"

Only a DEBUG=1 build of cabinwright.store trusts the test key (keys/test/). --sequence defaults
to the current Unix time, so each new debug index is newer than the last one the store saw.
"""
import argparse
import base64
import datetime
import hashlib
import importlib.util
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

KEY_ID = "store-test"
ROOT = Path(__file__).resolve().parent.parent
FIELDS = ("id", "name", "summary", "description", "author", "homepage", "category")


def load(name, path):
    """A module from the apps checkout's tools/, by path."""
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None:
        sys.exit("can't load %s" % path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def iso(t):
    return t.strftime("%Y-%m-%dT%H:%M:%SZ")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("zips", nargs="+")
    ap.add_argument("--apps-dir", default=str(ROOT.parent / "apps" / "apps"),
                    help="the index repo's apps/ (its tools/validate.py is used for the review)")
    ap.add_argument("--sequence", type=int, default=int(time.time()))
    ap.add_argument("--key", default=str(Path("~/.config/cabinwright/store-test.pem").expanduser()))
    ap.add_argument("--base", default="file:///sdcard/cabinwright/store-debug/")
    ap.add_argument("--expires-days", type=float, default=90)
    ap.add_argument("--allow-errors", action="store_true", help="report review errors but write the index")
    a = ap.parse_args()
    base = a.base if a.base.endswith("/") else a.base + "/"

    apps_dir = Path(a.apps_dir).resolve()
    tools = apps_dir.parent / "tools"
    validate = load("validate", tools / "validate.py")
    sign_index = load("sign_index", tools / "sign_index.py") if (tools / "sign_index.py").exists() else None
    icons_dir = apps_dir.parent / "icons"

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    problems = []
    apps = {}
    for zp in a.zips:
        zp = Path(zp).resolve()
        data = zp.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        try:
            r = validate.review_zip(data, sha)
        except validate.Fail as e:
            sys.exit("%s: %s" % (zp, e))
        zid, zver = r["nemesis"]["id"], r["nemesis"]["version"]
        where = "%s (%s %s)" % (zp.name, zid, zver)
        entry_path = apps_dir / ("%s.json" % zid)
        if not entry_path.exists():
            sys.exit("%s: no %s, so no tier or capabilities to give it" % (where, entry_path))
        doc = json.loads(entry_path.read_text(encoding="utf-8"))
        v = next((x for x in doc["versions"] if x["version"] == zver), None)
        if v is None:
            v = doc["versions"][0]
            problems.append("%s: %s lists no version %s; using %s's tier and capabilities" % (
                where, entry_path.name, zver, v["version"]))
        errs = []
        verr = errs.append
        validate.check_tier(doc, v, r, verr)
        errs += r.get("errors", [])
        if r["review"]["execute"]:
            errs.append("type=execute (USB autorun) packages are refused by the store")
        if r["review"]["system_writes"] and v["firmware"] == ["*"]:
            errs.append("writes to the system but firmware is [\"*\"]")
        problems += ["%s: %s" % (where, e) for e in errs]
        for w in r.get("warnings", []):
            print("%s: warning: %s" % (where, w), file=sys.stderr)

        app = apps.get(zid)
        if app is None:
            app = {k: doc.get(k, "") for k in FIELDS}
            icon = icons_dir / ("%s.png" % zid)
            if icon.exists():
                idata = icon.read_bytes()
                (out / icon.name).write_bytes(idata)
                app["icon"] = {"url": base + icon.name, "sha256": hashlib.sha256(idata).hexdigest()}
            for k in ("deprecated", "withdrawn", "replaced_by"):
                if k in doc:
                    app[k] = doc[k]
            app["versions"] = []
            apps[zid] = app
        name = "%s-%s.zip" % (zid, zver)
        if (out / name).resolve() != zp:
            shutil.copyfile(zp, out / name)
        app["versions"].append({
            "version": zver, "url": base + name, "sha256": sha, "size": len(data),
            "firmware": v["firmware"], "requires": v.get("requires", []), "tier": v["tier"],
            "capabilities": v.get("capabilities", {}), "notes": v.get("notes", ""),
            "review": r["review"],
        })

    for app in apps.values():
        app["versions"].sort(key=lambda x: validate.vkey(x["version"]), reverse=True)
    for p in problems:
        print("error: " + p, file=sys.stderr)
    if problems and not a.allow_errors:
        sys.exit("debug-index.py: %d problem(s); fix them or pass --allow-errors" % len(problems))

    now = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0, tzinfo=None)
    index = {
        "format": 1,
        "sequence": a.sequence,
        "generated": iso(now),
        "expires": iso(now + datetime.timedelta(days=a.expires_days)),
        "key_id": KEY_ID,
        "apps": [apps[k] for k in sorted(apps)],
    }
    # ASCII-only, as the published index
    data = (json.dumps(index, indent=2, ensure_ascii=True) + "\n").encode("ascii")
    (out / "index.json").write_bytes(data)
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", a.key, str(out / "index.json")],
                         check=True, stdout=subprocess.PIPE).stdout
    sig_b64 = base64.b64encode(sig).decode()
    if sign_index is not None:
        pub = ROOT / "keys" / "test" / ("%s.pub.pem" % KEY_ID)
        if not sign_index.verify(data, sig_b64, sign_index.public_key(pub.read_text())):
            sys.exit("the signature doesn't verify against %s: wrong --key?" % pub)
    (out / "index.json.sig").write_text(sig_b64)
    print("%s: sequence %d, %d apps%s" % (out / "index.json", a.sequence, len(apps),
                                          ", with %d problem(s)" % len(problems) if problems else ""))


if __name__ == "__main__":
    main()
