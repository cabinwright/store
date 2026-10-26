package dev.kiastore.test;

import dev.kiastore.common.Index;
import dev.kiastore.common.IndexCheck;
import dev.kiastore.common.Json;
import dev.kiastore.common.NemesisData;
import dev.kiastore.common.Versions;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Plain-main tests (no JUnit on the build machine): exits 1 on the first failure. */
public final class CommonTest {
    private static int passed;

    public static void main(String[] args) throws Exception {
        versions();
        json();
        nemesisData();
        retirement();
        vectors(new File(args[0]));
        System.out.println("ok: " + passed + " checks");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
            System.exit(1);
        }
        passed++;
    }

    private static void versions() {
        check(Versions.compare("1.10", "1.9") > 0, "1.10 > 1.9");
        check(Versions.compare("1.0", "1") == 0, "1.0 == 1");
        check(Versions.compare("1.4", "1.4.1") < 0, "1.4 < 1.4.1");
        check(Versions.compare("2", "10") < 0, "2 < 10");
        Versions.Requirement r = Versions.requirement("kia.menu>=1.1");
        check(r != null && r.id.equals("kia.menu") && r.op.equals(">=") && r.version.equals("1.1"), "parse >=");
        check(r.satisfiedBy("1.1") && r.satisfiedBy("1.10") && !r.satisfiedBy("1.0") && !r.satisfiedBy(null), ">= semantics");
        Versions.Requirement any = Versions.requirement("kia.menu");
        check(any != null && any.satisfiedBy("0.1") && !any.satisfiedBy(null), "bare requirement");
        check(Versions.requirement("kia.menu>=") == null, "op without version");
        Versions.Requirement eq = Versions.requirement("a.b = 2.0");
        check(eq != null && eq.satisfiedBy("2") && !eq.satisfiedBy("2.1"), "= with spaces");
    }

    private static void json() {
        Map<String, Object> m = Json.parseObject("{\"a\":[1,2.5,true,null,\"x\\u00e9\\n\"],\"b\":{}}");
        List<Object> a = Json.list(m, "a");
        check(a.size() == 5 && a.get(0).equals(1L) && a.get(2).equals(Boolean.TRUE) && a.get(3) == null, "json values");
        check("xé\n".equals(a.get(4)), "json escapes");
        check(Json.write(Json.parse(Json.write(m))).equals(Json.write(m)), "json round trip");
        boolean threw = false;
        try {
            Json.parse("{\"a\":1} x");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "json trailing data refused");
    }

    private static void nemesisData() {
        NemesisData d = new NemesisData("[nemesis]\nversion=1.4\nid=ev6.fixes\ntypes=application,service\n\n[app.0]\nkind=apk\n");
        check(d.id().equals("ev6.fixes") && d.version().equals("1.4") && d.get("app.0", "kind").equals("apk"), "NEMESIS.data");
    }

    private static void retirement() {
        String v = "\"versions\":[{\"version\":\"1.0\",\"firmware\":[\"*\"]}]";
        Index i = new Index(Json.parseObject("{\"format\":1,\"sequence\":3,\"apps\":["
                + "{\"id\":\"a\"," + v + "},"
                + "{\"id\":\"b\",\"deprecated\":\"Built into a now.\",\"replaced_by\":\"a\"," + v + "},"
                + "{\"id\":\"c\",\"deprecated\":\"Old.\",\"withdrawn\":\"Unsafe.\"," + v + "},"
                + "{\"id\":\"d\",\"withdrawn\":true," + v + "}]}"));
        Index.App a = i.app("a"), b = i.app("b"), c = i.app("c"), d = i.app("d");
        check(!a.retired() && a.deprecated.isEmpty() && a.replacedBy.isEmpty(), "not retired");
        check(b.retired() && b.replacedBy.equals("a") && b.retiredReason().equals("Built into a now."), "deprecated");
        check(b.versions.size() == 1, "a retired app keeps its versions");
        check(c.retired() && c.retiredReason().equals("Unsafe."), "withdrawn wins");
        check(d.retired() && !d.withdrawn.isEmpty(), "a malformed withdrawn still retires");
    }

    private static void vectors(File dir) throws Exception {
        Map<String, Object> v = Json.parseObject(new String(Files.readAllBytes(new File(dir, "vectors.json").toPath()), "UTF-8"));
        String pem = new String(Files.readAllBytes(new File(dir, Json.str(v, "public_key")).toPath()), "US-ASCII");
        String b64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        List<IndexCheck.Key> keys = Collections.singletonList(new IndexCheck.Key(Json.str(v, "key_id"), b64));
        long now = IndexCheck.parseTime(Json.str(v, "now"));
        check(now > 0, "vector time parses");
        int cases = 0;
        for (Object o : Json.list(v, "cases")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) o;
            byte[] index = Files.readAllBytes(new File(dir, Json.str(c, "index")).toPath());
            byte[] sig = Files.readAllBytes(new File(dir, Json.str(c, "sig")).toPath());
            String name = Json.str(c, "index");
            check(IndexCheck.sha256(index).equals(Json.str(c, "sha256")), name + ": sha256");
            boolean valid = Json.bool(c, "valid");
            boolean accepted;
            Index parsed = null;
            try {
                parsed = IndexCheck.check(index, sig, keys, -1, now);
                accepted = true;
            } catch (IndexCheck.Refused r) {
                accepted = false;
            }
            check(accepted == valid, name + ": accepted=" + accepted + ", expected " + valid);
            if (!valid) continue;
            cases++;
            // the same bytes, but rolled back, expired, or checked with another key
            check(refused(index, sig, keys, parsed.sequence + 1, now), name + ": rollback refused");
            check(!refused(index, sig, keys, parsed.sequence, now), name + ": same sequence accepted");
            check(refused(index, sig, keys, -1, IndexCheck.parseTime(parsed.expires) + 1000), name + ": expiry");
            List<IndexCheck.Key> other = new ArrayList<IndexCheck.Key>();
            other.add(new IndexCheck.Key("other", OTHER_KEY));
            check(refused(index, sig, other, -1, now), name + ": other key refused");
            byte[] flipped = index.clone();
            flipped[flipped.length / 2] ^= 1;
            check(refused(flipped, sig, keys, -1, now), name + ": one flipped bit refused");
            // what the store reads from it
            Index.App app = parsed.apps.get(0);
            Index.Version ver = app.versions.get(0);
            check(ver.size > 0 && ver.sha256.length() == 64, name + ": version fields");
            check(ver.fits("CVEV.USA.0000.V015.012.260507") || ver.firmware.contains("*"), name + ": firmware");
            check(!ver.fits(null) || ver.firmware.contains("*"), name + ": unknown firmware fits only *");
            for (Index.App a : parsed.apps) {
                for (Index.Version x : a.versions) {
                    if (x.notes.contains("Caf")) check(x.notes.equals("Café \"quotes\" and a\nnewline."), "unicode notes");
                }
            }
        }
        check(cases > 0, "at least one valid vector");
    }

    private static boolean refused(byte[] index, byte[] sig, List<IndexCheck.Key> keys, long last, long now) {
        try {
            IndexCheck.check(index, sig, keys, last, now);
            return false;
        } catch (IndexCheck.Refused r) {
            return true;
        }
    }

    /** kia-apps-2026a's public key: a real key that did not sign the test vectors. */
    private static final String OTHER_KEY = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAs4LDzXGPHuhH+Dl7IvxM"
            + "gJ/ogDxSHCZAe+5rPS+qjDwMQqF6EUz9KVE739+TZcCnoEMdLf/Pd8SiV0v8ChX3"
            + "i+Jx1iRhrZvfU8h1wVP8wweMMqjoOifYVCgpesJW32QJe8RriBjFUJK54RahRepy"
            + "qIL6Fj4pK9j0wqXrcawqIqHIK3/49qe3ls6piW/yieB4QH3Bc2YjOCyvkFxfLlSR"
            + "5ylNhpEmwAUXoaWazMNb4NEFMnH33Fwj+AL5T7nTk0m/E3l3/GV/4rClBMMcMVX3"
            + "9ZR+z4uhaImcKIoXOPiK9YfpLdmdLC+YI4xCL58cwooiDvhKLuXnleUwCTLkYJ6e"
            + "mm4CgyOLtcEOVHx95aOOA1RaOEjaKb51mr5ClxnNReIlRANYeAG5+1165/sn7H0l"
            + "qqyqrdC9Gip+ddJBIMtxC0ma7neXv0pU1qvIeVFppxVdjPjeluzQvJopF5xjawnZ"
            + "LJqr9PnPxDv0lPQI0ozZyooEiFp0/aLbhs3Fx9d8Z9fxAgMBAAE=";
}
