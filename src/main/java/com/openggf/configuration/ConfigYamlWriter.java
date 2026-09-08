package com.openggf.configuration;

import java.util.Map;
import java.util.OptionalInt;

/**
 * Renders the flat config map to grouped, commented, deterministically-ordered
 * YAML. Only keys present in the map are written -- the file is the player's
 * own settings, never a copy of the defaults -- and it opens with the
 * {@link #FORMAT_KEY} marker so a loader can tell it from a legacy file that
 * materialised every default. Walks {@link ConfigCatalog#emitOrder()}, opening
 * nested mapping blocks as section paths deepen, emitting a {@code # ── Title ──}
 * banner per top-level normal section and a single fence banner when the
 * {@code debug.*} block begins.
 */
public final class ConfigYamlWriter {
    /** Top-level marker declaring the sparse user-settings format. */
    public static final String FORMAT_KEY = "configFormat";
    /** Format 2: the file holds only settings the player set; defaults live in code. */
    public static final int SPARSE_FORMAT = 2;

    public String write(Map<String, Object> flat) {
        StringBuilder sb = new StringBuilder();
        sb.append("# OpenGGF configuration — only the settings you changed.\n");
        sb.append("# Every other setting uses its built-in default; see config.yaml.example\n");
        sb.append("# beside this file for the full documented list. Indentation is\n");
        sb.append("# significant (YAML). This file is rewritten cleanly on save.\n");
        sb.append(FORMAT_KEY).append(": ").append(SPARSE_FORMAT).append('\n');

        String[] prev = new String[0];
        boolean debugOpened = false;

        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
            if (!flat.containsKey(key.name())) {
                continue;
            }
            ConfigKeyMeta m = ConfigCatalog.meta(key);
            String[] segs = m.section().split("\\.");
            boolean isDebug = segs[0].equals("debug");

            if (isDebug && !debugOpened) {
                sb.append('\n')
                  .append("# ════════════════════════════════════════════\n")
                  .append("#  DEBUG  (developer tooling — safe to ignore for normal play)\n")
                  .append("# ════════════════════════════════════════════\n");
                debugOpened = true;
                prev = new String[0]; // force re-open of debug: and its subsections
            } else if (!isDebug && (prev.length == 0 || !prev[0].equals(segs[0]))) {
                sb.append('\n').append("# ── ").append(ConfigCatalog.title(segs[0])).append(" ──\n");
            }

            int common = commonPrefix(prev, segs);
            for (int d = common; d < segs.length; d++) {
                indent(sb, d);
                sb.append(segs[d]).append(":\n");
            }
            indent(sb, segs.length);
            sb.append(m.leaf()).append(": ").append(format(m, flat.get(key.name())));
            if (m.description() != null && !m.description().isBlank()) {
                sb.append("   # ").append(m.description());
            }
            sb.append('\n');
            prev = segs;
        }
        return sb.toString();
    }

    private static int commonPrefix(String[] a, String[] b) {
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i].equals(b[i])) {
            i++;
        }
        return i;
    }

    private static void indent(StringBuilder sb, int depth) {
        sb.append("  ".repeat(depth));
    }

    private static String format(ConfigKeyMeta m, Object value) {
        return switch (m.type()) {
            case BOOL -> String.valueOf(toBool(value));
            case INT -> String.valueOf(toInt(value));
            case DOUBLE -> formatDouble(value);
            case KEY -> formatKey(value);
            case STRING, ENUM -> quote(value == null ? "" : value.toString());
        };
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static long toInt(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatDouble(Object v) {
        double d = (v instanceof Number n) ? n.doubleValue() : parseDoubleOrZero(v);
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return (long) d + ".0";
        }
        return Double.toString(d);
    }

    private static double parseDoubleOrZero(Object v) {
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /** Renders a KEY value as its GLFW key name (handles legacy integer codes). */
    private static String formatKey(Object v) {
        String keyName;
        if (v instanceof Number n) {
            keyName = GlfwKeyNameResolver.nameOf(n.intValue());
        } else {
            String s = String.valueOf(v).trim();
            OptionalInt resolved = GlfwKeyNameResolver.resolve(s);
            if (resolved.isPresent()) {
                keyName = GlfwKeyNameResolver.nameOf(resolved.getAsInt());
            } else {
                try {
                    keyName = GlfwKeyNameResolver.nameOf(Integer.parseInt(s));
                } catch (NumberFormatException ignored) {
                    // A value carrying modifiers is neither a key name nor a
                    // number; write it back in one canonical spelling so a
                    // round trip is stable whatever the player typed.
                    KeyChord chord = KeyChord.parse(s);
                    keyName = chord.isBound() ? chord.format() : s;
                }
            }
        }
        return needsKeyQuote(keyName) ? quote(keyName) : keyName;
    }

    private static boolean needsKeyQuote(String keyName) {
        if (keyName == null || keyName.isEmpty()) {
            return true;
        }
        return keyName.matches("[0-9]+");
    }

    /** Always double-quote string/enum scalars so special characters (spaces, [], !, :) are safe. */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
