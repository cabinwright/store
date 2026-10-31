"""Drives cabinwrightd against a mock head unit. See daemon-test.sh."""
import hashlib
import json
import os
import re
import signal
import socket
import struct
import subprocess
import sys
import time
import zipfile
from pathlib import Path

WORK, JAVA, CP, BT, ANDROID_JAR, TEST_KEY = sys.argv[1:7]
W = Path(WORK)
R = W / "car"
BASE = R / "data/nemesis/cabinwright"
APPS = R / "data/nemesis/apps"
SD = R / "sdcard/cabinwright/store"
STUBS = W / "stubs"
PORT = W / "port"
BOOT = None
STORE_UID, APP_UID, TWEAK_APP_UID, STRANGER = 10050, 10060, 10061, 10099
fails = 0
sequence = 1
daemon = None


def check(ok, what):
    global fails
    print(("ok   " if ok else "FAIL ") + what, flush=True)
    if not ok:
        fails += 1


def script(path, body):
    path.write_text("#!/bin/sh\n" + body)
    path.chmod(0o755)


def mock():
    for d in ("system/xbin", "data/system", "sdcard/cabinwright/store/downloads", "data/nemesis/apps"):
        (R / d).mkdir(parents=True)
    STUBS.mkdir()
    script(R / "system/xbin/nemesis", f"""
ROOT={R}
case "$1" in
  root)
    shift
    uid=$1
    [ "$1" = -c ] || shift
    shift
    if [ "$uid" = 1000 ] && [ -f $ROOT/narrow ]; then
      chmod 600 $ROOT/data/system/packages.*
      sh -c "$1"
      code=$?
      chmod 000 $ROOT/data/system/packages.*
      exit $code
    fi
    exec sh -c "$1" ;;
  install)
    [ -f $ROOT/slow ] && sleep 4
    [ "$(umask)" = 0022 ] || {{ echo "pm install failed: unreadable APK" >&2; exit 1; }}
    zip=$3
    id=$(unzip -p "$zip" NEMESIS.data | grep '^id=' | cut -d= -f2)
    rm -rf "$ROOT/data/nemesis/apps/$id"
    mkdir -p "$ROOT/data/nemesis/apps/$id"
    unzip -q -o "$zip" -d "$ROOT/data/nemesis/apps/$id"
    grep -q '^postinstall=' "$ROOT/data/nemesis/apps/$id/NEMESIS.data" && touch $ROOT/hook-ran
    [ -f $ROOT/install-fails ] && exit 3
    exit 0 ;;
  uninstall) rm -rf "$ROOT/data/nemesis/apps/$2"; exit 0 ;;
  pkg-list)
    for d in $ROOT/data/nemesis/apps/*; do
      [ -f "$d/NEMESIS.data" ] || continue
      id=${{d##*/}}
      echo "$id|$(grep '^name=' "$d/NEMESIS.data" | cut -d= -f2)|0|$(grep '^types=' "$d/NEMESIS.data" | cut -d= -f2)"
    done ;;
esac
""")
    # the car's toolbox chmod: octal modes only
    script(STUBS / "chmod", 'm=$1; [ "$m" = -R ] && m=$2\ncase "$m" in *[!0-7]*) echo "Bad mode"; exit 10 ;; esac\nexec /bin/chmod "$@"\n')
    script(STUBS / "getprop", 'echo CVEV.USA.0000.V015.012.260507\n')
    script(STUBS / "service", f'echo "$*" >> {R}/binder.log\necho "Result: Parcel(00000000 00000001   \'........\')"\n')
    script(STUBS / "am", f'echo "$*" >> {R}/am.log\n')
    script(STUBS / "logcat", 'case "$*" in *-T*) echo "Unrecognized Option" >&2; exit 1 ;; esac\necho "I/x( 1): a log line"\n')  # Android 4.4's: no -T


def apk(pkg, path):
    d = W / ("apk-" + pkg)
    d.mkdir()
    (d / "AndroidManifest.xml").write_text(f"""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{pkg}">
<application><activity android:name=".Main"><intent-filter>
<action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/>
</intent-filter></activity></application></manifest>""")
    un = d / "u.apk"
    subprocess.run([f"{BT}/aapt", "package", "-f", "-M", str(d / "AndroidManifest.xml"), "-I", ANDROID_JAR, "-F", str(un)], check=True)
    subprocess.run([f"{BT}/apksigner", "sign", "--ks", str(KEYSTORE), "--ks-pass", "pass:android", "--min-sdk-version", "19",
                    "--out", str(path), str(un)], check=True)
    return path


def cert_of(path, ks=None):
    with zipfile.ZipFile(path) as z:
        rsa = next(n for n in z.namelist() if n.endswith(".RSA"))
        der = subprocess.run(["openssl", "pkcs7", "-inform", "DER", "-print_certs"], input=z.read(rsa), capture_output=True, check=True).stdout
    return subprocess.run(["openssl", "x509", "-outform", "DER"], input=der, capture_output=True, check=True).stdout.hex()


def package(name, files):
    z = W / name
    with zipfile.ZipFile(z, "w") as o:
        for k, v in files.items():
            o.writestr(k, v)
    return z


def entry(id_, version, zip_path, tier, capabilities=None, tweaks=None, apps=()):
    data = zip_path.read_bytes()
    apps = apps or (("example.tools",) if tweaks else ("example.app",))
    return {"id": id_, "name": id_.title(), "versions": [{
        "version": version, "url": "x", "sha256": hashlib.sha256(data).hexdigest(), "size": len(data), "tier": tier,
        "capabilities": capabilities or {}, "firmware": ["*"], "requires": [],
        "review": {"apps": [{"package": p, "version_code": 1} for p in apps], "tweaks": [{"id": t, "needs": [{"need": n, "reason": r} for n, r in ns]} for t, ns in (tweaks or {}).items()]},
    }]}


def publish(apps, zips):
    global sequence
    sequence += 1
    doc = {"format": 1, "sequence": sequence, "generated": "2026-10-30T00:00:00Z", "expires": "2099-01-01T00:00:00Z",
           "key_id": "test", "apps": apps}
    (SD / "index.json").write_text(json.dumps(doc))
    sig = subprocess.run(["openssl", "dgst", "-sha256", "-sign", TEST_KEY, str(SD / "index.json")], capture_output=True, check=True).stdout
    import base64
    (SD / "index.json.sig").write_bytes(base64.b64encode(sig))
    for dest, path in zips.items():
        (SD / "downloads" / dest).write_bytes(path.read_bytes())


def start():
    global daemon
    PORT.unlink(missing_ok=True)
    env = dict(os.environ, PATH=f"{STUBS}:{os.environ['PATH']}")
    log = open(W / "daemon.log", "a")
    daemon = subprocess.Popen([JAVA, f"-Dcabinwright.test.root={R}", f"-Dfake.port={PORT}", "-cp", CP, "app.cabinwright.store.daemon.Cabinwrightd"],
                              env=env, stdout=log, stderr=log, preexec_fn=lambda: os.umask(0o077))  # as cabinwrightd.sh
    for _ in range(200):
        if PORT.exists() and PORT.read_text().strip():
            return
        time.sleep(0.05)
    raise SystemExit("the daemon didn't start:\n" + (W / "daemon.log").read_text())


def stop():
    daemon.send_signal(signal.SIGKILL)
    daemon.wait()


def frames(s, msg):
    body = json.dumps(msg).encode()
    s.sendall(struct.pack(">I", len(body)) + body)


def read_exact(s, n):
    b = b""
    while len(b) < n:
        c = s.recv(n - len(b))
        if not c:
            raise EOFError
        b += c
    return b


def call(uid, cap, op, args=None, v=1, timeout=70):
    s = socket.create_connection(("127.0.0.1", int(PORT.read_text())), timeout=timeout)
    s.sendall(struct.pack(">i", uid))
    frames(s, {"v": v, "cap": cap, "op": op, "args": args or {}})
    n = struct.unpack(">I", read_exact(s, 4))[0]
    out = json.loads(read_exact(s, n))
    s.close()
    return out


def store(op, args=None):
    return call(STORE_UID, "store", op, args)


def request(rid, timeout=20):
    end = time.time() + timeout
    while time.time() < end:
        for q in store("status")["data"]["requests"]:
            if q["request_id"] == rid and q["state"] != "working":
                return q
        time.sleep(0.1)
    return {"state": "timeout", "message": ""}


def install(id_, version):
    r = store("install", {"id": id_, "version": version})
    return request(r["data"]["request_id"])


def wait(cond, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.1)
    return False


def tweak(id_):
    return next((t for t in call(TWEAK_APP_UID, "tweaks", "list")["data"]["tweaks"] if t["id"] == id_), {})


mock()
KEYSTORE = W / "k.jks"
subprocess.run(["keytool", "-genkeypair", "-keystore", str(KEYSTORE), "-storepass", "android", "-keypass", "android", "-alias", "k",
                "-dname", "CN=t", "-keyalg", "RSA", "-keysize", "2048", "-validity", "100"], check=True, capture_output=True)
store_apk = apk("app.cabinwright.store", W / "store.apk")
app_apk = apk("example.app", W / "app.apk")
tools_apk = apk("example.tools", W / "tools.apk")
self_dir = APPS / "cabinwright.store"
(self_dir / "app").mkdir(parents=True)
(self_dir / "app/store.apk").write_bytes(store_apk.read_bytes())
(self_dir / "NEMESIS.data").write_text("[nemesis]\nid=cabinwright.store\nname=Cabinwright\nversion=1.0\ntypes=application,service\n")
cert = cert_of(store_apk)
(R / "data/system/packages.list").write_text(
    f"app.cabinwright.store {STORE_UID} 0 /d default 3003\nexample.app {APP_UID} 0 /d default 3003\n"
    f"example.tools {TWEAK_APP_UID} 0 /d default 3003\nexample.other {STRANGER} 0 /d default 3003\n")
(R / "data/system/packages.xml").write_text(
    f'<packages><package name="app.cabinwright.store"><cert index="0" key="{cert}"/></package>'
    '<package name="example.app"><cert index="0"/></package><package name="example.tools"><cert index="0"/></package>'
    '<package name="example.other"><cert index="0"/></package></packages>')
(BASE).mkdir(parents=True)
(BASE / "kt/bin").mkdir(parents=True)
PORT_ARG = f"-Dfake.port={PORT}"
script(BASE / "kt/bin/kt", f'exec {JAVA} {PORT_ARG} -Dfake.uid="$TWEAK_UID" -cp {CP} app.cabinwright.store.daemon.Kt "$@"\n')
(BASE / "vehicle.json").write_text('{"assume_parked": true}')
(BASE / "debug-index").write_text("test")
start()

print("what tweak uids need")
time.sleep(1)
check((BASE / "kt/bin").stat().st_mode & 0o777 == 0o755, "kt's folder is open to tweak uids despite the daemon's umask")
check((BASE / "kt/bin/kt").stat().st_mode & 0o777 == 0o755, "and kt can be run")

print("who's calling")
h = call(STRANGER, "broker", "hello")
check(h["ok"] and h["data"]["package"] == "", "hello answers anyone, and names no package for a stranger")
check(call(99, "store", "status")["error"] == "unknown_caller", "store ops refuse an unknown uid")
check(call(APP_UID, "store", "status")["error"] == "unknown_caller", "store ops refuse an installed app that isn't the store")
check(call(STRANGER, "root", "exec", {"command": "id"})["error"] == "unknown_caller", "root refuses a stranger")
check(call(STRANGER, "kt", "run", {"argv": ["ps"]})["error"] == "unknown_caller", "kt refuses anything but a tweak uid")
# started from ADB the daemon can't read system's files itself
stop()
(R / "narrow").touch()
for f in ("packages.list", "packages.xml"):
    (R / "data/system" / f).chmod(0)
start()
check(call(STORE_UID, "store", "status")["ok"], "without CAP_DAC_OVERRIDE the store is still recognised")
check(call(STRANGER, "store", "status")["error"] == "unknown_caller", "and a stranger still isn't")
stop()
(R / "narrow").unlink()
for f in ("packages.list", "packages.xml"):
    (R / "data/system" / f).chmod(0o644)
start()
check(call(STORE_UID, "broker", "hello", v=99)["error"] == "unsupported", "an unknown protocol version is refused")
check(store("status")["ok"], "the store app is recognised by its certificate")
check(store("nonsense")["error"] == "unsupported", "unknown op")

print("installing")
evil = package("evil.zip", {"NEMESIS.data": "[nemesis]\nid=example.app\nversion=1\ntypes=application\npostinstall=svc/pwn.sh\n\n[service.0]\nname=x\npath=svc/pwn.sh\nuid=root\n",
                            "app.apk": app_apk.read_bytes(), "svc/pwn.sh": "id\n"})
good = package("good.zip", {"NEMESIS.data": "[nemesis]\nid=example.app\nversion=1\npostinstall=hook.sh\n\n[service.0]\nname=x\npath=x\nuid=root\n",
                            "app.apk": app_apk.read_bytes()})
publish([entry("example.app", "1", evil, "app", {"root": "Runs things"})], {"example.app-1.zip": evil})
q = install("example.app", "1")
check(q["state"] == "failed" and "may not" in q["message"], "a file an app package may not hold fails the install: " + q["message"])
check(not (APPS / "example.app").exists(), "nothing was installed")
named = package("named.zip", {"NEMESIS.data": "x", "$(touch ../pwned).apk": app_apk.read_bytes()})
publish([entry("example.app", "1", named, "app")], {"example.app-1.zip": named})
q = install("example.app", "1")
check(q["state"] == "failed", "an APK name that would reach NEMESIS's shell is refused: " + q["message"])
fake = package("fake.zip", {"NEMESIS.data": "x", "a.apk": store_apk.read_bytes()})
publish([entry("example.app", "1", fake, "app", apps=("app.cabinwright.store",))], {"example.app-1.zip": fake})
q = install("example.app", "1")
check(q["state"] == "failed" and "store app" in q["message"], "a package can't replace the store app: " + q["message"])
publish([entry("example.app", "1", good, "app", apps=("example.other",))], {"example.app-1.zip": good})
q = install("example.app", "1")
check(q["state"] == "failed" and "reviewed" in q["message"], "an APK the review doesn't list is refused: " + q["message"])
broken = package("broken.zip", {"NEMESIS.data": "x", "a.apk": b"not a zip"})
publish([entry("example.app", "1", broken, "app")], {"example.app-1.zip": broken})
q = install("example.app", "1")
check(q["state"] == "failed", "a broken APK fails the install, not the daemon: " + q["message"])
publish([entry("example.app", "1", good, "app", {"root": "Runs things"})], {"example.app-1.zip": evil})
q = install("example.app", "1")
check(q["state"] == "failed" and (any(w in q["message"] for w in ("larger", "bytes", "SHA-256"))), "a download that isn't the indexed one is refused: " + q["message"])
publish([entry("example.app", "1", good, "app", {"root": "Runs things"})], {"example.app-1.zip": good})
(SD / "index.json").write_text((SD / "index.json").read_text().replace('"sequence"', '"sequence" ', 1))
q = install("example.app", "1")
check(q["state"] == "failed" and "signature" in q["message"], "a changed index is refused")
publish([entry("example.app", "1", good, "app", {"root": "Runs things"})], {"example.app-1.zip": good})
old = (SD / "index.json").read_bytes(), (SD / "index.json.sig").read_bytes()
q = install("example.app", "1")
check(q["state"] == "ok", "a good package installs: " + q["message"])
data = (APPS / "example.app/NEMESIS.data").read_text()
check("postinstall" not in data and "[service" not in data and "kind=apk" in data, "its own manifest was replaced: no hooks, no services")
check("component=example.app/" in data, "component= comes from the APK")
check(not (R / "hook-ran").exists(), "its install hook never ran")
publish([entry("example.app", "1", good, "app", {"root": "Runs things"})], {})
check(install("example.app", "1")["state"] == "ok", "a newer index is accepted")
(SD / "index.json").write_bytes(old[0])
(SD / "index.json.sig").write_bytes(old[1])
q = install("example.app", "1")
check(q["state"] == "failed" and "Index refused" in q["message"], "an older index is refused, even correctly signed: " + q["message"])
publish([entry("example.app", "1", good, "app", {"root": "Runs things"})], {})
st = store("status")["data"]
pk = next(p for p in st["packages"] if p["id"] == "example.app")
check(pk["store_installed"] and pk["tier"] == "app" and pk["version"] == "1", "status lists it as the store's")
check(store("uninstall", {"id": "cabinwright.store"})["ok"] and "itself" in request(store("uninstall", {"id": "cabinwright.store"})["data"]["request_id"])["message"], "the store won't remove itself")

print("capabilities")
h = call(APP_UID, "broker", "hello")
check(h["data"]["package"] == "example.app" and h["data"]["grants"] == {"root": "ask"}, "hello reports the app and its grants")
check(call(APP_UID, "logs", "read")["error"] == "not_granted", "a capability it didn't declare is refused")


def answer(choice):
    def run():
        if wait(lambda: (R / "am.log").exists() and "PromptActivity" in (R / "am.log").read_text()):
            rid = re.findall(r"--es request_id (\S+)", (R / "am.log").read_text())[-1]
            store("prompt_answer", {"request_id": rid, "choice": choice})
            (R / "am.log").unlink()
    import threading
    threading.Thread(target=run).start()


answer("once")
r = call(APP_UID, "root", "exec", {"command": "echo hello"})
check(r["ok"] and r["data"]["stdout"].strip() == "hello" and r["data"]["code"] == 0, "root runs after the driver says once")
check("used root" in (R / "am.log").read_text(), "and the driver sees a toast")
check(any("root: echo hello" in l for l in store("log", {"id": "example.app"})["data"]["lines"]), "and the command is in the app's log")
(R / "am.log").unlink()
answer("deny")
check(call(APP_UID, "root", "exec", {"command": "id"})["error"] == "denied", "deny is refused")
check(call(APP_UID, "root", "exec", {"command": "id"})["error"] == "denied", "and stays refused without asking")
check(store("grant_set", {"id": "example.app", "cap": "root", "on": True})["ok"], "the store can put it back")
(BASE / "vehicle.json").write_text("{}")
check(call(APP_UID, "root", "exec", {"command": "id"})["error"] == "not_parked", "without a working gear probe the car counts as not parked")
(BASE / "vehicle.json").write_text('{"assume_parked": true}')
store("grant_set", {"id": "example.app", "cap": "root", "on": False})
check(call(APP_UID, "root", "exec", {"command": "id"})["error"] == "not_granted", "switched off in the store: refused")
store("grant_set", {"id": "example.app", "cap": "root", "on": True})
check(call(APP_UID, "root", "exec", {"command": "x" * 501})["error"] == "bad_request", "a command longer than the prompt shows is refused")
check(call(APP_UID, "root", "exec", {"command": ""})["error"] == "bad_request", "an empty command is refused")
store("grant_set", {"id": "example.app", "cap": "root", "on": False})
g = store("grants", {"id": "example.app"})["data"]["packages"]["example.app"]["root"]
check(g["state"] == "off" and g["reason"] == "Runs things", "grants lists state and reason")
check(store("prompt_answer", {"request_id": "nope", "choice": "always"})["error"] == "bad_request", "answering no question")

print("tweaks")
TARGET = R / "data/demo-file"
NEEDS = {"demo": [("binder:com.hkmc.demo 3", "Reads the demo"), (f"file:{TARGET}@absent", "Writes the demo")]}
conf = ("name=Demo\nsummary=s\ndescription=d\nfirmware=*\nkind=oneshot\ndefault=off\n"
        f"need=binder:com.hkmc.demo 3 | Reads the demo\nneed=file:{TARGET}@absent | Writes the demo\n")
tw = package("tw.zip", {"NEMESIS.data": "[nemesis]\nid=example.tools\nversion=1\n", "tools.apk": tools_apk.read_bytes(), "tweaks/demo/tweak.conf": conf,
                        "tweaks/demo/enable.sh": "kt binder com.hkmc.demo 3 i32 1\n",
                        "tweaks/demo/disable.sh": "echo off\n",
                        "tweaks/demo/status.sh": "echo on running\n",
                        "lib/x": "x\n"})
caps = {"tweaks": "Shows your tweaks"}
publish([entry("example.tools", "1", tw, "tweaks", caps, {"demo": [("binder:com.hkmc.demo 9", "Something else")]})], {"example.tools-1.zip": tw})
q = install("example.tools", "1")
check(q["state"] == "failed" and "didn't review" in q["message"], "needs that differ from the review fail the install: " + q["message"])
publish([entry("example.tools", "1", tw, "tweaks", caps, NEEDS)], {"example.tools-1.zip": tw})
q = install("example.tools", "1")
check(q["state"] == "ok", "a tweaks package installs: " + q["message"])
check(wait(lambda: tweak("demo").get("agreed")), "its tweak is listed and agreed")
auto = package("auto.zip", {"NEMESIS.data": "[nemesis]\nid=example.auto\nversion=1\n",
                            "tweaks/auto/tweak.conf": "name=Auto\nsummary=s\ndescription=d\nfirmware=*\nkind=oneshot\ndefault=on\nneed=logs:demo | Reads a log\n",
                            "tweaks/auto/enable.sh": "echo on\n",
                            "tweaks/auto/run.sh": f"touch {R}/auto-ran\nkt logcat demo:E > {R}/auto-log 2>&1\necho $? > {R}/auto-log-done\nexec sleep 60\n"})
publish([entry("example.tools", "1", tw, "tweaks", caps, NEEDS), entry("example.auto", "1", auto, "tweaks", {}, {"auto": [("logs:demo", "Reads a log")]})],
        {"example.tools-1.zip": tw, "example.auto-1.zip": auto})
check(install("example.auto", "1")["state"] == "ok", "a package of one default=on tweak installs")
copy = subprocess.Popen([JAVA, f"-Dcabinwright.test.root={R}", f"-Dfake.port={PORT}", "-cp", CP, "app.cabinwright.store.daemon.Cabinwrightd"],
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
time.sleep(3)
copy.kill()
said = copy.communicate()[0]
check("waiting for the socket" in said and "running" not in said, "a second copy waits, and starts nothing")
check(wait(lambda: tweak("auto").get("desired")), "default=on turns on by itself once agreed")
check(wait(lambda: (R / "auto-ran").exists()), "a oneshot's run.sh runs while it's on")
check(wait(lambda: (R / "auto-log-done").exists()), "kt logcat ends when the log does")
check("a log line" in (R / "auto-log").read_text(), "and its lines reached the tweak")
check(call(TWEAK_APP_UID, "tweaks", "set", {"id": "auto", "on": False})["ok"] and wait(lambda: tweak("auto").get("state") == "off"), "and the driver can turn it off")
time.sleep(2)
check(not tweak("auto").get("desired"), "a default doesn't turn it back on")
store("uninstall", {"id": "example.auto"})
check(call(TWEAK_APP_UID, "tweaks", "set", {"id": "nope", "on": True})["error"] == "bad_request", "no such tweak")
check(call(TWEAK_APP_UID, "tweaks", "set", {"id": "demo", "on": True})["ok"], "switched on")
ok = wait(lambda: (R / "binder.log").exists() and "com.hkmc.demo 3 i32 1" in (R / "binder.log").read_text())
check(ok, "enable.sh's declared binder call reached the car through kt")
if not ok:
    print(call(TWEAK_APP_UID, "tweaks", "log", {"id": "demo"})["data"]["lines"])
check(wait(lambda: tweak("demo").get("state") == "on"), "the tweak reports on")
conf_file = APPS / "example.tools/tweaks/demo/tweak.conf"
conf_file.chmod(0)
time.sleep(3)
conf_file.chmod(0o644)
check(wait(lambda: tweak("demo").get("state") == "on"), "a tweak.conf that can't be read doesn't count as removed")
state = json.loads((BASE / "state.json").read_text())
uid = state["tweaks"]["demo"]["uid"]
check(90000 <= uid <= 98999, f"its uid {uid} isn't one an app's isolated process can get")
(W / "payload").write_text("x")


def kt(*argv):
    return call(uid, "kt", "run", {"argv": list(argv)})


check(kt("file", "md5", "/data/nemesis/elsewhere")["data"]["code"] == 1, "a read it can't make fails without counting against the tweak")
check(kt("binder", "com.hkmc.demo", "9")["data"]["code"] == 70, "kt refuses a code the tweak didn't declare")
check(kt("file", "replace", str(TARGET), str(W / "payload"), "6755")["data"]["code"] == 64, "kt won't make a setuid file")
check(not TARGET.exists(), "and wrote nothing")
check(kt("keep", "binder:com.hkmc.demo 3")["data"]["code"] == 64, "kt keep is refused outside disable.sh")
big = call(TWEAK_APP_UID, "tweaks", "set", {"id": "x" * 1048000, "on": True})
check(not big["ok"], "a reply over the frame limit is an error, not a crash")
held = []
for _ in range(4):
    h_ = socket.create_connection(("127.0.0.1", int(PORT.read_text())))
    h_.sendall(struct.pack(">i", STRANGER))
    held.append(h_)
time.sleep(0.3)
try:
    call(STRANGER, "broker", "hello", timeout=3)
    check(False, "one uid can't hold more than four connections")
except (EOFError, OSError):
    check(True, "one uid can't hold more than four connections")
check(call(STORE_UID, "broker", "hello")["ok"], "and the store still gets through")
for h_ in held:
    h_.close()
time.sleep(0.3)
check(call(TWEAK_APP_UID, "tweaks", "set", {"id": "demo", "on": False})["ok"], "switched off")
check(wait(lambda: not json.loads((BASE / "state.json").read_text())["tweaks"]["demo"].get("applied")), "it goes off")
check(kt("ps").get("error") == "unknown_caller", "a tweak that's off can't use kt")
check(call(TWEAK_APP_UID, "tweaks", "set", {"id": "demo", "on": True})["ok"], "switched back on")
check(wait(lambda: tweak("demo").get("state") == "on"), "and it's on again")

bad = package("tw2.zip", {"NEMESIS.data": "[nemesis]\nid=example.tools\nversion=2\n", "tools.apk": tools_apk.read_bytes(), "tweaks/demo/tweak.conf": conf,
                          "tweaks/demo/enable.sh": "kt binder com.hkmc.demo 9 i32 1\n"})
publish([entry("example.tools", "2", bad, "tweaks", caps, NEEDS)], {"example.tools-2.zip": bad})
check(install("example.tools", "2")["state"] == "ok", "an update that changes enable.sh installs")
check(wait(lambda: tweak("demo").get("state") == "error"), "an undeclared call is refused and shown: " + str(tweak("demo").get("text")))
check("demo 9" not in (R / "binder.log").read_text(), "and never reached the car")
check(call(TWEAK_APP_UID, "root", "exec", {"command": "id"})["error"] == "not_granted", "an app can't borrow what it didn't declare")

hog = package("tw3.zip", {"NEMESIS.data": "x", "tools.apk": tools_apk.read_bytes(), "tweaks/demo/tweak.conf": conf,
                          "tweaks/demo/enable.sh": "true\n",
                          "tweaks/demo/status.sh": "exec >/dev/null 2>&1\nexec sleep 300\n"})
publish([entry("example.tools", "3", hog, "tweaks", caps, NEEDS)], {"example.tools-3.zip": hog})
check(install("example.tools", "3")["state"] == "ok", "a tweak whose status.sh closes its output and hangs installs")
call(TWEAK_APP_UID, "tweaks", "set", {"id": "demo", "on": True})
print("removing")
q = request(store("uninstall", {"id": "example.tools"})["data"]["request_id"], 60)
check(q["state"] == "ok" and not (APPS / "example.tools").exists(), "a tweaks package is removed: " + q["message"])
q = request(store("uninstall", {"id": "example.app"})["data"]["request_id"])
check(q["state"] == "ok", "an app is removed")
check(call(APP_UID, "broker", "hello")["data"]["package"] == "", "and the broker no longer knows it")
(APPS / "manual").mkdir()
(APPS / "manual/NEMESIS.data").write_text("[nemesis]\nid=manual\nname=Manual\nversion=1\ntypes=application\n")
q = request(store("uninstall", {"id": "manual"})["data"]["request_id"])
check(q["state"] == "failed" and (APPS / "manual").exists(), "a package the store didn't install stays: " + q["message"])

print("lockdown")
r = store("lockdown", {"on": True})
check(r["error"] == "unsupported" and "NEMESIS" in r["message"], "lockdown says it can't be on, rather than pretending")
check(store("lockdown", {"on": False})["data"]["on"] is False and store("status")["data"]["lockdown"] is False, "and reports off")

print("across a restart")
(R / "slow").touch()
publish([entry("example.app", "1", good, "app", {"root": "Runs things"})], {"example.app-1.zip": good})
rid = store("install", {"id": "example.app", "version": "1"})["data"]["request_id"]
check(wait(lambda: (BASE / "pending.json").is_file()), "the install is under way")
stop()
start()
q = request(rid)
check(q["state"] == "ok", "a new daemon finishes it under the same request_id: " + q["message"])
check(call(APP_UID, "broker", "hello")["data"]["package"] == "example.app", "and records it")
(R / "slow").unlink()
stop()
start()
check(call(APP_UID, "broker", "hello")["data"]["grants"] == {"root": "ask"}, "grants survive a restart")

print("bad frames")
s = socket.create_connection(("127.0.0.1", int(PORT.read_text())))
s.sendall(struct.pack(">i", STRANGER) + struct.pack(">I", 0x7fffffff))
time.sleep(0.2)
s.close()
s = socket.create_connection(("127.0.0.1", int(PORT.read_text())))
s.sendall(struct.pack(">i", STRANGER) + struct.pack(">I", 3) + b"{{{")
time.sleep(0.2)
s.close()
check(call(STRANGER, "broker", "hello")["ok"], "the broker survives bad frames")
stop()
print("all passed" if not fails else f"{fails} failed")
if fails:
    print((W / "daemon.log").read_text()[-3000:])
sys.exit(1 if fails else 0)
