package com.openggf.configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a nested YAML map (sections → leaves) into the flat
 * {@code SonicConfiguration.name() → value} map the engine uses internally.
 * Unrecognized leaf paths are collected rather than dropped silently.
 */
public final class ConfigFlattener {

    public record Result(Map<String, Object> flat, List<String> unknownKeys) {
    }

    private ConfigFlattener() {
    }

    public static Result flatten(Map<String, Object> nested) {
        Map<String, Object> flat = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        if (nested != null) {
            walk("", nested, flat, unknown);
        }
        return new Result(flat, unknown);
    }

    @SuppressWarnings("unchecked")
    private static void walk(String prefix, Map<String, Object> node,
                             Map<String, Object> flat, List<String> unknown) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            if (prefix.isEmpty() && ConfigYamlWriter.FORMAT_KEY.equals(e.getKey())) {
                // The file-format marker is metadata, not a setting.
                continue;
            }
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map<?, ?> child) {
                walk(path, (Map<String, Object>) child, flat, unknown);
            } else {
                SonicConfiguration key = ConfigCatalog.byPath(path);
                if (key != null) {
                    flat.put(key.name(), value);
                } else {
                    unknown.add(path);
                }
            }
        }
    }
}
