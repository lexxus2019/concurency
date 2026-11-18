package ru.lab.concurrent.core.cli;

import java.util.LinkedHashMap;
import java.util.Map;

/** Разбор аргументов вида {@code --server=http://host:8080 --ops=500 --dry}. Без внешних библиотек. */
public final class Args {

    private final Map<String, String> values = new LinkedHashMap<>();
    private final Map<String, String> usage = new LinkedHashMap<>();

    private Args() {
    }

    public static Args parse(String[] argv) {
        Args a = new Args();
        for (String s : argv) {
            if (!s.startsWith("--")) {
                continue;
            }
            String body = s.substring(2);
            int eq = body.indexOf('=');
            if (eq < 0) {
                a.values.put(body, "true");
            } else {
                a.values.put(body.substring(0, eq), body.substring(eq + 1));
            }
        }
        return a;
    }

    public Args explain(String key, String description, String defaultValue) {
        usage.put(key, description + " [по умолчанию " + defaultValue + "]");
        return this;
    }

    public String str(String key, String def) {
        return values.getOrDefault(key, def);
    }

    public int integer(String key, int def) {
        String v = values.get(key);
        return v == null ? def : Integer.parseInt(v.trim());
    }

    public long lng(String key, long def) {
        String v = values.get(key);
        return v == null ? def : Long.parseLong(v.trim());
    }

    public boolean flag(String key, boolean def) {
        String v = values.get(key);
        if (v == null) {
            return def;
        }
        return !"false".equalsIgnoreCase(v.trim());
    }

    public boolean helpRequested() {
        return values.containsKey("help") || values.containsKey("h");
    }

    public String usageText() {
        StringBuilder sb = new StringBuilder("ключи:\n");
        usage.forEach((k, d) -> sb.append("  --").append(pad(k)).append(" ").append(d).append('\n'));
        sb.append("  --help                            эта справка\n");
        return sb.toString();
    }

    private static String pad(String s) {
        return s.length() >= 20 ? s : s + " ".repeat(20 - s.length());
    }
}
