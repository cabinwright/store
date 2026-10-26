package dev.cabinwright.store.test

import dev.cabinwright.store.common.Index
import dev.cabinwright.store.common.IndexCheck
import dev.cabinwright.store.common.Json
import dev.cabinwright.store.common.NemesisData
import dev.cabinwright.store.common.Versions
import dev.cabinwright.store.helper.Legacy
import java.io.File
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
        legacyImport()
        vectors(File(args[0]))
        println("ok: $passed checks")
    }

    private fun check(ok: Boolean, what: String) {
        if (!ok) {
            println("FAIL: $what")
            exitProcess(1)
        }
        passed++
    }

    /** The helper's one-time import of kia.store's record (moving from the kia.* packages). */
    private fun legacyImport() {
        check(Legacy.renamed("kia.menu") == "cabinwright.menu", "kia.menu -> cabinwright.menu")
        check(Legacy.renamed("kia.tweaks") == "cabinwright.tweaks", "kia.tweaks -> cabinwright.tweaks")
        check(Legacy.renamed("kia.store") == null, "kia.store dropped")
        check(Legacy.renamed("tweaks.adb") == "tweaks.adb", "packs keep their ids")
        val old = Json.parseObject(
            "{\"installed\":[\"kia.menu\",\"kia.store\",\"kia.tweaks\"," +
                "\"tweaks.adb\",\"tweaks.projection\",\"bad id!\"],\"sequence\":14,\"debug_sequence\":3," +
                "\"seeded\":true,\"done\":[\"u1\",\"u2\"]}",
        )
        val st = Legacy.importedState(old, "1/2", 99)
        check(
            Json.strings(st, "installed") ==
                listOf("cabinwright.menu", "cabinwright.tweaks", "tweaks.adb", "tweaks.projection"),
            "installed ids mapped: " + Json.strings(st, "installed"),
        )
        check(Json.num(st, "sequence", -1) == 14L && Json.num(st, "debug_sequence", -1) == 3L, "sequences kept")
        check(Json.bool(st, "seeded") && Json.strings(st, "done").size == 2, "seeded and done kept")
        val imp = Json.obj(st, "imported")
        check(!Json.bool(imp, "final") && Json.str(imp, "stamp") == "1/2", "import not final yet")
        val none = Legacy.importedState(Json.parseObject("{}"), "", 0)
        check(
            Json.strings(none, "installed").isEmpty() && !none.containsKey("sequence") && !Json.bool(none, "seeded"),
            "empty record",
        )
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
