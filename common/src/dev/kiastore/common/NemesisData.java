package dev.kiastore.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads a NEMESIS.data file: INI sections of key=value lines. */
public final class NemesisData {
    /** section -> (key -> value); keys outside any section go under "". */
    public final Map<String, Map<String, String>> sections = new LinkedHashMap<String, Map<String, String>>();

    public NemesisData(String text) {
        Map<String, String> current = section("");
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                current = section(line.substring(1, line.length() - 1).trim());
                continue;
            }
            int eq = line.indexOf('=');
            if (eq > 0) current.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
    }

    private Map<String, String> section(String name) {
        Map<String, String> m = sections.get(name);
        if (m == null) {
            m = new LinkedHashMap<String, String>();
            sections.put(name, m);
        }
        return m;
    }

    public String get(String section, String key) {
        Map<String, String> m = sections.get(section);
        String v = m == null ? null : m.get(key);
        return v == null ? "" : v;
    }

    public String id() {
        return get("nemesis", "id");
    }

    public String version() {
        return get("nemesis", "version");
    }

    public String name() {
        return get("nemesis", "name");
    }
}
