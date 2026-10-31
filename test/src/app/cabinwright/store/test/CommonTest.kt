package app.cabinwright.store.test

import app.cabinwright.store.common.Axml
import app.cabinwright.store.common.Callers
import app.cabinwright.store.common.Grants
import app.cabinwright.store.common.Index
import app.cabinwright.store.common.IndexCheck
import app.cabinwright.store.common.Json
import app.cabinwright.store.common.Need
import app.cabinwright.store.common.NemesisData
import app.cabinwright.store.common.Repack
import app.cabinwright.store.common.TweakConf
import app.cabinwright.store.common.Versions
import app.cabinwright.store.common.Wire
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.system.exitProcess

/** Plain-main tests (no JUnit on the build machine): exits 1 on the first failure. Runs on the host JVM. */
object CommonTest {
    private var passed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        versions()
        json()
        nemesisData()
        retirement()
        tweakConf()
        repack()
        wire()
        callers()
        grants()
        vectors(File(args[0]))
        axml(File(args[0], "../fixtures/edit-menu.AndroidManifest.xml"))
        println("ok: $passed checks")
    }

    private fun check(ok: Boolean, what: String) {
        if (!ok) {
            println("FAIL: $what")
            exitProcess(1)
        }
        passed++
    }

    private fun tweakConf() {
        val conf = TweakConf(
            "name=Daylight saving\nsummary=s\nfirmware=CVEV.A, CVEV.B\nkind=service\ndefault=on\n" +
                "need=binder:com.hkmc.micom.time 3,4 | Set the clock\n" +
                "need=setting:system/summer_time | Keep the checkbox in step\n" +
                "need=file:/system/priv-app/SystemUI.apk@512fa1835649e710d91deeb37c90433d | Replace it\n",
        )
        check(conf.kind == "service" && conf.defaultOn && conf.firmware == listOf("CVEV.A", "CVEV.B"), "conf fields")
        check(conf.fits("CVEV.B") && !conf.fits("CVEV.C") && !conf.fits(null), "conf firmware")
        check(conf.changesFiles(), "file need = changes files")
        val binder = conf.needs[0]
        check(
            binder.covers("binder", "com.hkmc.micom.time", "4") && !binder.covers("binder", "com.hkmc.micom.time", "5"),
            "binder codes",
        )
        check(!binder.covers("binder", "com.hkmc.micom.mode", "3"), "binder service")
        check(
            conf.needs[1].covers("setting", "system/summer_time") && !conf.needs[1].covers("setting", "system/other"),
            "setting",
        )
        check(conf.needs[2].target == "/system/priv-app/SystemUI.apk" && conf.needs[2].allowed.size == 1, "file md5")
        check(Need.parse("root | Anything").covers("kill", "x"), "root covers all")
        val created = Need.parse("file:/system/lib/libev6aac.so@absent | Add it")
        check(created.allowed == listOf("absent"), "file may be created")
        check(
            Need.parse("file:/system/bin/ifconfig@link:toolbox | Wrap it").allowed == listOf("link:toolbox"),
            "symlink version",
        )
        check(Need.parse("logs:AAProjection | Watch it").covers("logs", "AAProjection"), "logs need")
        check(refused { Need.parse("file:/system/bin/x@link:../../data/x | up") }, "link target escaping")
        check(refused { Need.parse("logs:A B | t") }, "bad log tag")
        for (bad in listOf(
            "name=x\nkind=service\nfirmware=*\nrisk=system\n",
            "name=x\nkind=thing\nfirmware=*\n",
            "name=x\nkind=oneshot\nfirmware=*\nneed=file:/system/x | no md5\n",
            "name=x\nkind=oneshot\nfirmware=*\nneed=file:/system/../x@512fa1835649e710d91deeb37c90433d | up\n",
            "name=x\nkind=oneshot\nfirmware=*\nneed=prop:x\n",
            "name=x\nkind=oneshot\nfirmware=*\nneed=setting:other/x | t\n",
            "name=x\nkind=oneshot\nfirmware=*\nneed=binder:svc | no codes\n",
            "name=x\nkind=oneshot\nfirmware=*\nneed=mount:/x | t\n",
        )) {
            check(refused { TweakConf(bad) }, "refused: $bad")
        }
    }

    private fun repack() {
        Repack.checkTier("tweaks.adb", "tweaks")
        Repack.checkTier("cabinwright.store", "system")
        check(refused { Repack.checkTier("tweaks.adb", "system") }, "system only for the allowlist")
        check(refused { Repack.checkTier("x", "") }, "no tier")
        val zip =
            listOf(
                "NEMESIS.data",
                "tweaks.apk",
                "tweaks/auto-dst/",
                "tweaks/auto-dst/tweak.conf",
                "tweaks/auto-dst/run.sh",
                "lib/x.jar",
            )
        check(
            Repack.keep("tweaks", zip) ==
                listOf("tweaks.apk", "tweaks/auto-dst/tweak.conf", "tweaks/auto-dst/run.sh", "lib/x.jar"),
            "tweaks keep",
        )
        check(refused { Repack.keep("app", zip) }, "app tier refuses tweaks/")
        check(refused { Repack.keep("tweaks", listOf("scripts/post.sh")) }, "hooks refused")
        check(refused { Repack.keep("app", listOf("app/x.apk")) }, "nested apk refused")
        check(refused { Repack.keep("tweaks", listOf("tweaks/../x")) }, "unsafe path")
        check(refused { Repack.keep("tweaks", listOf("tweaks/Bad_Id/tweak.conf")) }, "bad tweak id")
        check(Repack.tweakIds(zip) == listOf("auto-dst"), "tweak ids")
        val conf = "name=x\nkind=service\nfirmware=*\nneed=kill:com.x | Restart it\n"
        Repack.checkNeeds(mapOf("t" to conf), mapOf("t" to listOf("kill:com.x" to "Restart it")))
        check(
            refused {
                Repack.checkNeeds(mapOf("t" to conf), mapOf("t" to listOf("kill:com.y" to "Restart it")))
            },
            "needs differ",
        )
        check(refused { Repack.checkNeeds(mapOf("t" to conf), mapOf("u" to listOf())) }, "tweaks differ")
        val m =
            NemesisData(
                Repack.manifest(
                    "cabinwright.tweaks",
                    "Tweaks",
                    "1.0",
                    listOf(
                        Repack.Apk("tweaks.apk", "app.cabinwright.tweaks", "app.cabinwright.tweaks/.TweaksActivity"),
                    ),
                ),
            )
        check(m.id() == "cabinwright.tweaks" && m.version() == "1.0" && m.get("app.0", "kind") == "apk", "manifest")
        check(m.get("app.0", "component") == "app.cabinwright.tweaks/.TweaksActivity", "manifest component")
        check(
            m.sections.keys.none {
                it.startsWith("service")
            } &&
                m.get("nemesis", "postinstall").isEmpty(),
            "no services or hooks",
        )
        check(refused { Repack.manifest("x", "a]\n[service.0", "1.0", listOf()) }, "manifest injection")
    }

    private fun wire() {
        val out = ByteArrayOutputStream()
        Wire.write(out, Json.of("v" to 1L, "cap" to "tweaks", "op" to "list"))
        val bytes = out.toByteArray()
        check(
            bytes[0].toInt() == 0 &&
                bytes.size == 4 + ((bytes[2].toInt() and 0xff) shl 8 or (bytes[3].toInt() and 0xff)),
            "frame length",
        )
        val back = Wire.read(ByteArrayInputStream(bytes))
        check(Json.str(back, "cap") == "tweaks" && Json.num(back, "v", 0) == 1L, "frame round trip")
        check(refusedIo(bytes.copyOf(bytes.size - 1)), "short frame")
        check(refusedIo(byteArrayOf(0x7f, 0, 0, 0)), "too big")
        check(refusedIo(byteArrayOf(0, 0, 0, 2, '['.code.toByte(), ']'.code.toByte())), "not an object")
    }

    private fun callers() {
        val list = "app.cabinwright.tweaks 10071 0 /data/data/app.cabinwright.tweaks default 3003\n" +
            "com.a 10080 0 /data/data/com.a default none\ncom.b 10080 0 /data/data/com.b default none\n"
        val by = Callers.packagesByUid(list)
        check(Callers.packageFor(10071, by) == "app.cabinwright.tweaks", "uid -> package")
        check(Callers.packageFor(10080, by) == null, "shared uid refused")
        check(Callers.packageFor(1, by) == null, "unknown uid")
        val xml =
            "<packages><package name=\"com.first\" codePath=\"/x\">" +
                "<sigs count=\"1\"><cert index=\"0\" key=\"3082aa\" /></sigs></package>" +
                "<shared-user name=\"android.uid.system\">" +
                "<sigs count=\"1\"><cert index=\"1\" key=\"3082bb\" /></sigs></shared-user>" +
                "<package name=\"com.second\"><sigs count=\"1\"><cert index=\"0\" /></sigs></package>" +
                "<package name=\"com.third\"><sigs count=\"1\"><cert index=\"1\" /></sigs></package></packages>"
        check(Callers.certHex(xml, "com.first") == "3082aa", "cert with key")
        check(Callers.certHex(xml, "com.second") == "3082aa", "cert by index")
        check(Callers.certHex(xml, "com.third") == "3082bb", "cert defined under a shared user")
        check(Callers.certHex(xml, "com.none") == null, "no package")
    }

    private fun grants() {
        val g = Json.of()
        Grants.install(g, setOf("tweaks", "root", "bogus"), false)
        check(g.keys == setOf("tweaks", "root"), "install agrees to declared, known caps")
        check(Grants.decide(g, "tweaks", "b1") == Grants.Decision.ALLOW, "tweaks allowed")
        check(Grants.decide(g, "root", "b1") == Grants.Decision.PROMPT, "root asks first")
        check(Grants.decide(g, "logs", "b1") == Grants.Decision.NOT_GRANTED, "undeclared")
        check(
            Grants.answer(g, "root", "once", "b1") && Grants.decide(g, "root", "b1") == Grants.Decision.PROMPT,
            "once",
        )
        check(
            Grants.answer(g, "root", "session", "b1") && Grants.decide(g, "root", "b1") == Grants.Decision.ALLOW,
            "session",
        )
        check(Grants.decide(g, "root", "b2") == Grants.Decision.PROMPT, "session ends at boot")
        check(
            !Grants.answer(g, "root", "deny", "b2") && Grants.decide(g, "root", "b3") == Grants.Decision.DENIED,
            "deny sticks",
        )
        Grants.switch(g, "root", true)
        check(Grants.decide(g, "root", "b3") == Grants.Decision.PROMPT, "switch on asks again")
        Grants.switch(g, "tweaks", false)
        check(Grants.decide(g, "tweaks", "b3") == Grants.Decision.NOT_GRANTED, "switched off")
        check(Grants.newCapabilities(g, setOf("tweaks", "logs")) == setOf("logs"), "update adds a cap")
        Grants.install(g, setOf("tweaks"), false)
        check(
            g.keys == setOf("tweaks") && Grants.decide(g, "tweaks", "b3") == Grants.Decision.NOT_GRANTED,
            "update keeps off, drops root",
        )
        Grants.install(g, setOf("tweaks"), true)
        check(Grants.decide(g, "tweaks", "b3") == Grants.Decision.ALLOW, "new certificate starts over")
        check(Grants.summary(g, "b3")["tweaks"] == "always", "summary")
    }

    private fun refused(block: () -> Unit): Boolean = try {
        block()
        false
    } catch (ignored: IllegalArgumentException) {
        true
    }

    private fun refusedIo(bytes: ByteArray): Boolean = try {
        Wire.read(ByteArrayInputStream(bytes))
        false
    } catch (ignored: IOException) {
        true
    }

    private fun versions() {
        check(Versions.compare("1.10", "1.9") > 0, "1.10 > 1.9")
        check(Versions.compare("1.0", "1") == 0, "1.0 == 1")
        check(Versions.compare("1.4", "1.4.1") < 0, "1.4 < 1.4.1")
        check(Versions.compare("2", "10") < 0, "2 < 10")
        val r = Versions.requirement("cabinwright.menu>=1.1")
        check(r != null && r.id == "cabinwright.menu" && r.op == ">=" && r.version == "1.1", "parse >=")
        check(
            r!!.satisfiedBy("1.1") && r.satisfiedBy("1.10") && !r.satisfiedBy("1.0") && !r.satisfiedBy(null),
            ">= semantics",
        )
        val any = Versions.requirement("cabinwright.menu")
        check(any != null && any.satisfiedBy("0.1") && !any.satisfiedBy(null), "bare requirement")
        check(Versions.requirement("cabinwright.menu>=") == null, "op without version")
        val eq = Versions.requirement("a.b = 2.0")
        check(eq != null && eq.satisfiedBy("2") && !eq.satisfiedBy("2.1"), "= with spaces")
    }

    private fun json() {
        val m = Json.parseObject("{\"a\":[1,2.5,true,null,\"x\\u00e9\\n\"],\"b\":{}}")
        val a = Json.list(m, "a")
        check(a.size == 5 && a[0] == 1L && a[1] == 2.5 && a[2] == true && a[3] == null, "json values")
        check(a[4] == "xé\n", "json escapes")
        check(Json.write(Json.parse(Json.write(m))) == Json.write(m), "json round trip")
        check(Json.write(Json.of("q" to "a\"b\\\u0001")) == "{\"q\":\"a\\\"b\\\\\\u0001\"}", "json writes escapes")
        for (bad in listOf("{\"a\":1} x", "[1,]", "{\"a\" 1}", "\"\\x\"", "-", "[1.2.3]", "nul")) {
            check(refusedJson(bad), "json refused: $bad")
        }
    }

    private fun refusedJson(text: String): Boolean = try {
        Json.parse(text)
        false
    } catch (expected: IllegalArgumentException) {
        true
    }

    private fun nemesisData() {
        val d = NemesisData("[nemesis]\nversion=1.4\nid=ev6.fixes\ntypes=application,service\n\n[app.0]\nkind=apk\n")
        check(d.id() == "ev6.fixes" && d.version() == "1.4" && d.get("app.0", "kind") == "apk", "NEMESIS.data")
    }

    private fun retirement() {
        val v = "\"versions\":[{\"version\":\"1.0\",\"firmware\":[\"*\"]}]"
        val i = Index(
            Json.parseObject(
                "{\"format\":1,\"sequence\":3,\"apps\":[" +
                    "{\"id\":\"a\",$v}," +
                    "{\"id\":\"b\",\"deprecated\":\"Built into a now.\",\"replaced_by\":\"a\",$v}," +
                    "{\"id\":\"c\",\"deprecated\":\"Old.\",\"withdrawn\":\"Unsafe.\",$v}," +
                    "{\"id\":\"d\",\"withdrawn\":true,$v}]}",
            ),
        )
        val a = i.app("a")!!
        val b = i.app("b")!!
        val c = i.app("c")!!
        val d = i.app("d")!!
        check(!a.retired() && a.deprecated.isEmpty() && a.replacedBy.isEmpty(), "not retired")
        check(b.retired() && b.replacedBy == "a" && b.retiredReason() == "Built into a now.", "deprecated")
        check(b.versions.size == 1, "a retired app keeps its versions")
        check(c.retired() && c.retiredReason() == "Unsafe.", "withdrawn wins")
        check(d.retired() && d.withdrawn.isNotEmpty(), "a malformed withdrawn still retires")
    }

    private fun axml(f: File) {
        val m = Axml(Files.readAllBytes(f.toPath()))
        check(m.pkg == "app.cabinwright.menu", "manifest package: " + m.pkg)
        check(m.launcher.startsWith("app.cabinwright.menu/"), "launcher activity: " + m.launcher)
        check(refused { Axml(ByteArray(16)) }, "not binary xml")
    }

    private fun read(dir: File, name: String): ByteArray = Files.readAllBytes(File(dir, name).toPath())

    private fun vectors(dir: File) {
        val v = Json.parseObject(String(read(dir, "vectors.json"), Charsets.UTF_8))
        val pem = String(read(dir, Json.str(v, "public_key")), Charsets.US_ASCII)
        val b64 = pem.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
        val keys = listOf(IndexCheck.Key(Json.str(v, "key_id"), b64))
        val now = IndexCheck.parseTime(Json.str(v, "now"))
        check(now > 0, "vector time parses")
        var cases = 0
        for (o in Json.list(v, "cases")) {
            val c = Json.asObject(o)!!
            val name = Json.str(c, "index")
            val index = read(dir, name)
            val sig = read(dir, Json.str(c, "sig"))
            check(IndexCheck.sha256(index) == Json.str(c, "sha256"), "$name: sha256")
            val valid = Json.bool(c, "valid")
            val parsed = try {
                IndexCheck.check(index, sig, keys, -1, now)
            } catch (expected: IndexCheck.Refused) {
                null
            }
            check((parsed != null) == valid, "$name: accepted=${parsed != null}, expected $valid")
            if (parsed == null) continue
            cases++
            validVector(Vector(name, index, sig, keys, now), parsed)
        }
        check(cases > 0, "at least one valid vector")
    }

    /** A signing test case: its bytes, the key that signed them, and the time to check them at. */
    private class Vector(
        val name: String,
        val index: ByteArray,
        val sig: ByteArray,
        val keys: List<IndexCheck.Key>,
        val now: Long,
    )

    /** The same bytes, but rolled back, expired, or checked with another key; then what the store reads. */
    private fun validVector(vector: Vector, parsed: Index) {
        val name = vector.name
        val index = vector.index
        val sig = vector.sig
        val keys = vector.keys
        val now = vector.now
        check(refused(index, sig, keys, parsed.sequence + 1, now), "$name: rollback refused")
        check(!refused(index, sig, keys, parsed.sequence, now), "$name: same sequence accepted")
        check(refused(index, sig, keys, -1, IndexCheck.parseTime(parsed.expires) + 1000), "$name: expiry")
        check(refused(index, sig, listOf(IndexCheck.Key("other", OTHER_KEY)), -1, now), "$name: other key refused")
        val flipped = index.clone()
        flipped[flipped.size / 2] = (flipped[flipped.size / 2].toInt() xor 1).toByte()
        check(refused(flipped, sig, keys, -1, now), "$name: one flipped bit refused")
        val ver = parsed.apps[0].versions[0]
        check(ver.size > 0 && ver.sha256.length == 64, "$name: version fields")
        check(ver.fits("CVEV.USA.0000.V015.012.260507") || ver.firmware.contains("*"), "$name: firmware")
        check(!ver.fits(null) || ver.firmware.contains("*"), "$name: unknown firmware fits only *")
        for (a in parsed.apps) {
            for (x in a.versions) {
                if (x.notes.contains("Caf")) check(x.notes == "Café \"quotes\" and a\nnewline.", "unicode notes")
            }
        }
    }

    private fun refused(index: ByteArray, sig: ByteArray, keys: List<IndexCheck.Key>, last: Long, now: Long): Boolean =
        try {
            IndexCheck.check(index, sig, keys, last, now)
            false
        } catch (expected: IndexCheck.Refused) {
            true
        }

    /** cabinwright-2026a's public key: a real key that did not sign the test vectors. */
    private const val OTHER_KEY = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAs4LDzXGPHuhH+Dl7IvxM" +
        "gJ/ogDxSHCZAe+5rPS+qjDwMQqF6EUz9KVE739+TZcCnoEMdLf/Pd8SiV0v8ChX3" +
        "i+Jx1iRhrZvfU8h1wVP8wweMMqjoOifYVCgpesJW32QJe8RriBjFUJK54RahRepy" +
        "qIL6Fj4pK9j0wqXrcawqIqHIK3/49qe3ls6piW/yieB4QH3Bc2YjOCyvkFxfLlSR" +
        "5ylNhpEmwAUXoaWazMNb4NEFMnH33Fwj+AL5T7nTk0m/E3l3/GV/4rClBMMcMVX3" +
        "9ZR+z4uhaImcKIoXOPiK9YfpLdmdLC+YI4xCL58cwooiDvhKLuXnleUwCTLkYJ6e" +
        "mm4CgyOLtcEOVHx95aOOA1RaOEjaKb51mr5ClxnNReIlRANYeAG5+1165/sn7H0l" +
        "qqyqrdC9Gip+ddJBIMtxC0ma7neXv0pU1qvIeVFppxVdjPjeluzQvJopF5xjawnZ" +
        "LJqr9PnPxDv0lPQI0ozZyooEiFp0/aLbhs3Fx9d8Z9fxAgMBAAE="
}
