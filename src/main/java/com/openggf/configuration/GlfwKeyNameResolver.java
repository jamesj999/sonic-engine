package com.openggf.configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_WORLD_1;

/**
 * Resolves human-readable key name strings (e.g. "Q", "SPACE", "LEFT_SHIFT")
 * to GLFW integer key codes and vice versa. Uses reflection on
 * {@code org.lwjgl.glfw.GLFW} to build the mappings, so new keys added in
 * future LWJGL versions are picked up automatically.
 *
 * <p>Accepted input formats for {@link #resolve(String)}:
 * <ul>
 *   <li>{@code "Q"} — short key name</li>
 *   <li>{@code "GLFW_KEY_Q"} — full GLFW constant name (prefix stripped)</li>
 *   <li>{@code "KEY_Q"} — partial prefix (stripped)</li>
 *   <li>All lookups are case-insensitive</li>
 * </ul>
 */
public final class GlfwKeyNameResolver {

    private static final String GLFW_KEY_PREFIX = "GLFW_KEY_";
    private static final String KEY_PREFIX = "KEY_";

    private GlfwKeyNameResolver() {
    }

    private static class Holder {
        static final Map<String, Integer> NAME_TO_CODE;
        static final Map<Integer, String> CODE_TO_NAME;

        static {
            Map<String, Integer> ntc = new HashMap<>();
            Map<Integer, String> ctn = new HashMap<>();

            try {
                Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW");
                for (Field field : glfwClass.getDeclaredFields()) {
                    if (field.getType() == int.class
                            && Modifier.isPublic(field.getModifiers())
                            && Modifier.isStatic(field.getModifiers())
                            && Modifier.isFinal(field.getModifiers())
                            && field.getName().startsWith(GLFW_KEY_PREFIX)) {
                        int code = field.getInt(null);
                        if (code < 0) {
                            continue; // Skip GLFW_KEY_UNKNOWN (-1)
                        }
                        String shortName = field.getName().substring(GLFW_KEY_PREFIX.length());
                        ntc.put(shortName, code);
                        // First constant wins for reverse lookup (avoids aliases)
                        ctn.putIfAbsent(code, shortName);
                    }
                }
            } catch (ReflectiveOperationException e) {
                Logger.getLogger(GlfwKeyNameResolver.class.getName())
                        .log(Level.WARNING, "Failed to build GLFW key name map via reflection; "
                                + "all key name lookups will return empty", e);
            }

            ntc.put("#", GLFW_KEY_WORLD_1);

            NAME_TO_CODE = Map.copyOf(ntc);
            CODE_TO_NAME = Map.copyOf(ctn);
        }
    }

    /**
     * Resolves a key name string to its GLFW key code.
     *
     * @param name the key name (e.g. "Q", "SPACE", "GLFW_KEY_ENTER"); case-insensitive
     * @return the GLFW key code, or {@link OptionalInt#empty()} if unrecognised
     */
    public static OptionalInt resolve(String name) {
        if (name == null || name.isEmpty()) {
            return OptionalInt.empty();
        }
        String normalised = normalise(name);
        Integer code = Holder.NAME_TO_CODE.get(normalised);
        return code != null ? OptionalInt.of(code) : OptionalInt.empty();
    }

    /**
     * Returns the short key name for a GLFW key code (e.g. 81 &rarr; "Q",
     * 32 &rarr; "SPACE"). Returns the numeric string if the code has no
     * known GLFW_KEY_* constant.
     */
    public static String nameOf(int keyCode) {
        String name = Holder.CODE_TO_NAME.get(keyCode);
        return name != null ? name : Integer.toString(keyCode);
    }

    private static String normalise(String name) {
        String upper = name.toUpperCase();
        if (upper.startsWith(GLFW_KEY_PREFIX)) {
            return upper.substring(GLFW_KEY_PREFIX.length());
        }
        if (upper.startsWith(KEY_PREFIX)) {
            return upper.substring(KEY_PREFIX.length());
        }
        return upper;
    }
}
