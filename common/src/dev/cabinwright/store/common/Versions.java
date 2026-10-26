package dev.cabinwright.store.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version strings and requirements, as the index defines them: split on ".", compare
 * numerically part by part, missing parts are 0 ("1.10" > "1.9", "1.0" == "1").
 */
public final class Versions {
    private static final Pattern REQUIRE = Pattern.compile("^\\s*([A-Za-z0-9._-]+?)\\s*(>=|<=|==|=|>|<)?\\s*([0-9][0-9.]*)?\\s*$");

    private Versions() {
    }

    public static int compare(String a, String b) {
        String[] x = a.trim().split("\\."), y = b.trim().split("\\.");
        for (int k = 0; k < Math.max(x.length, y.length); k++) {
            long p = k < x.length ? part(x[k]) : 0, q = k < y.length ? part(y[k]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static long part(String s) {
        // a non-numeric tail ("2rc1") counts by its leading digits
        int end = 0;
        while (end < s.length() && end < 18 && Character.isDigit(s.charAt(end))) end++;
        return end == 0 ? 0 : Long.parseLong(s.substring(0, end));
    }

    /** A requirement such as "cabinwright.menu>=1.1" or "cabinwright.menu". */
    public static final class Requirement {
        public final String id, op, version;

        Requirement(String id, String op, String version) {
            this.id = id;
            this.op = op;
            this.version = version;
        }

        public boolean satisfiedBy(String installed) {
            if (installed == null) return false;
            if (op == null || version == null) return true;
            int c = compare(installed, version);
            if (op.equals(">=")) return c >= 0;
            if (op.equals(">")) return c > 0;
            if (op.equals("<=")) return c <= 0;
            if (op.equals("<")) return c < 0;
            return c == 0;
        }

        @Override
        public String toString() {
            return op == null || version == null ? id : id + " " + op + " " + version;
        }
    }

    /** Parses a requirement; null if it isn't one. */
    public static Requirement requirement(String s) {
        Matcher m = REQUIRE.matcher(s);
        if (!m.matches()) return null;
        if ((m.group(2) == null) != (m.group(3) == null)) return null;
        return new Requirement(m.group(1), m.group(2), m.group(3));
    }
}
