#!/usr/bin/env python3
"""Makes a signed debug index for testing cabinwright.store on a car, without GitHub.

usage: debug-index.py <out dir> <apps.json> --sequence N [--key ~/.config/kia-store/kia-store-test.pem]
                      [--base file:///sdcard/cabinwright/store-debug/] [--expires-days 90]

<apps.json> is a list of apps in the apps/<id>.json format, except that each version names a
local "zip" instead of "url"/"sha256" (size, hash and review come from the zip), and
"icon_file" a local PNG instead of "icon". The zips and icons are
copied to <out dir> and their URLs point at --base. Writes index.json, index.json.sig and the
zips; push the directory to the car and point the store at it (root-only marker file):

  adb push <out dir>/. /sdcard/cabinwright/store-debug/
  adb shell "nemesis root -c 'echo file:///sdcard/cabinwright/store-debug/ > /data/nemesis/cabinwright/store/debug-index;
             chmod 644 /data/nemesis/cabinwright/store/debug-index'"

Only a DEBUG=1 build of cabinwright.store trusts the test key (keys/test/). The review here is a
rough stand-in for the index repo's CI review: types, apps, services and uids from NEMESIS.data.
"""
import argparse
import base64
import configparser
import datetime
import hashlib
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

KEY_ID = "kia-store-test"


def review(zpath):
    with zipfile.ZipFile(zpath) as z:
        ini = configparser.ConfigParser(interpolation=None, strict=False)
        ini.read_string(z.read("NEMESIS.data").decode("utf-8"))
    n = ini["nemesis"]
    types = [t.strip() for t in n.get("types", "").split(",") if t.strip()]
    apps, services, root = [], [], []
    for s in ini.sections():
        if s.startswith("app."):
            apps.append({"package": ini[s].get("package", ""), "version_code": 0})
        elif s.startswith("service."):
            uid = ini[s].get("uid", "root")
            services.append({"name": ini[s].get("name", s), "uid": uid, "mode": ini[s].get("mode", "")})
            if uid == "root":
                root.append(ini[s].get("name", s))
    return n.get("id"), n.get("version"), {
        "types": types, "apps": apps, "services": services, "root_services": root,
        "system_writes": False, "execute": "execute" in types,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("apps")
    ap.add_argument("--sequence", type=int, required=True)
    ap.add_argument("--key", default=str(Path("~/.config/kia-store/kia-store-test.pem").expanduser()))
    ap.add_argument("--base", default="file:///sdcard/cabinwright/store-debug/")
    ap.add_argument("--expires-days", type=float, default=90)
    a = ap.parse_args()

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    now = datetime.datetime.utcnow().replace(microsecond=0)
    apps = json.loads(Path(a.apps).read_text())
    for app in apps:
        icon = app.pop("icon_file", None)
        if icon:
            data = (Path(a.apps).parent / icon).read_bytes()
            (out / ("icon-%s.png" % app["id"])).write_bytes(data)
            app["icon"] = {"url": a.base + "icon-%s.png" % app["id"], "sha256": hashlib.sha256(data).hexdigest()}
        for v in app["versions"]:
            z = v.pop("zip", None)
            if z:
                z = (Path(a.apps).parent / z).resolve()
                name = "%s-%s.zip" % (app["id"], v["version"])
                shutil.copyfile(z, out / name)
                data = z.read_bytes()
                v["url"] = a.base + name
                v["sha256"] = hashlib.sha256(data).hexdigest()
                v["size"] = len(data)
                zid, zver, rev = review(z)
                if zid != app["id"] or zver != v["version"]:
                    sys.exit("%s is %s %s, not %s %s" % (z, zid, zver, app["id"], v["version"]))
                v.setdefault("review", rev)
            v.setdefault("firmware", ["*"])
            v.setdefault("requires", [])
            v.setdefault("notes", "")
            v.setdefault("review", {"types": [], "apps": [], "services": [], "root_services": [],
                                    "system_writes": False, "execute": False})
            v.setdefault("size", 1)
            v.setdefault("sha256", "0" * 64)
    index = {
        "format": 1,
        "sequence": a.sequence,
        "generated": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "expires": (now + datetime.timedelta(days=a.expires_days)).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "key_id": KEY_ID,
        "apps": apps,
    }
    data = json.dumps(index, indent=2, ensure_ascii=False).encode("utf-8") + b"\n"
    (out / "index.json").write_bytes(data)
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", a.key, str(out / "index.json")],
                         check=True, stdout=subprocess.PIPE).stdout
    (out / "index.json.sig").write_text(base64.b64encode(sig).decode())
    print("%s: sequence %d, %d apps" % (out / "index.json", a.sequence, len(apps)))


if __name__ == "__main__":
    main()
