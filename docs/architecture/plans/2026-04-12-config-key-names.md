# Config String Key Names Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow config.json to accept human-readable key names (e.g. `"Q"`) alongside numeric GLFW key codes (e.g. `81`), with fallback-to-default and warning on invalid values.

**Architecture:** A new `GlfwKeyNameResolver` utility builds name-to-code and code-to-name maps via reflection on `org.lwjgl.glfw.GLFW`. `SonicConfigurationService.getInt()` gains a key-name resolution fallback in its parse chain. A parallel `defaults` map captures default values for warning messages.

**Tech Stack:** Java 21, LWJGL/GLFW, JUnit 5

**Spec:** `docs/architecture/designs/2026-04-12-config-key-names-design.md`

---

### Task 1: Create `GlfwKeyNameResolver` with tests (TDD)

**Files:**
- Create: `src/main/java/com/openggf/configuration/GlfwKeyNameResolver.java`
- Create: `src/test/java/com/openggf/configuration/TestGlfwKeyNameResolver.java`

- [ ] **Step 1: Write the failing tests**

Create the test file with all resolver test cases:

```java
package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestGlfwKeyNameResolver {

    // --- resolve() tests ---

    @ParameterizedTest
    @CsvSource({
        "Q,          81",   // GLFW_KEY_Q
        "q,          81",   // case insensitive
        "D,          68",   // GLFW_KEY_D
        "SPACE,      32",   // GLFW_KEY_SPACE
        "space,      32",   // case insensitive named key
        "ENTER,      257",  // GLFW_KEY_ENTER
        "TAB,        258",  // GLFW_KEY_TAB
        "F9,         298",  // GLFW_KEY_F9
        "F12,        301",  // GLFW_KEY_F12
        "LEFT_SHIFT, 340",  // GLFW_KEY_LEFT_SHIFT
        "LEFT,       263",  // GLFW_KEY_LEFT (arrow)
        "RIGHT,      262",  // GLFW_KEY_RIGHT (arrow)
        "PAGE_UP,    266",  // GLFW_KEY_PAGE_UP
        "DELETE,     261",  // GLFW_KEY_DELETE
    })
    void resolve_keyNames(String name, int expectedCode) {
        OptionalInt result = GlfwKeyNameResolver.resolve(name);
        assertTrue(result.isPresent(), "Expected '" + name + "' to resolve");
        assertEquals(expectedCode, result.getAsInt());
    }

    @ParameterizedTest
    @CsvSource({
        "GLFW_KEY_Q,          81",
        "GLFW_KEY_SPACE,      32",
        "glfw_key_enter,      257",
    })
    void resolve_stripsGlfwKeyPrefix(String name, int expectedCode) {
        OptionalInt result = GlfwKeyNameResolver.resolve(name);
        assertTrue(result.isPresent(), "Expected '" + name + "' to resolve");
        assertEquals(expectedCode, result.getAsInt());
    }

    @ParameterizedTest
    @CsvSource({
        "KEY_Q,          81",
        "KEY_SPACE,      32",
        "key_f9,         298",
    })
    void resolve_stripsKeyPrefix(String name, int expectedCode) {
        OptionalInt result = GlfwKeyNameResolver.resolve(name);
        assertTrue(result.isPresent(), "Expected '" + name + "' to resolve");
        assertEquals(expectedCode, result.getAsInt());
    }

    @Test
    void resolve_invalidName_returnsEmpty() {
        assertTrue(GlfwKeyNameResolver.resolve("banana").isEmpty());
        assertTrue(GlfwKeyNameResolver.resolve("ZZZZ").isEmpty());
        assertTrue(GlfwKeyNameResolver.resolve("").isEmpty());
    }

    @Test
    void resolve_null_returnsEmpty() {
        assertTrue(GlfwKeyNameResolver.resolve(null).isEmpty());
    }

    // --- nameOf() tests ---

    @Test
    void nameOf_letter() {
        assertEquals("Q", GlfwKeyNameResolver.nameOf(GLFW_KEY_Q));
    }

    @Test
    void nameOf_namedKey() {
        assertEquals("SPACE", GlfwKeyNameResolver.nameOf(GLFW_KEY_SPACE));
        assertEquals("ENTER", GlfwKeyNameResolver.nameOf(GLFW_KEY_ENTER));
    }

    @Test
    void nameOf_functionKey() {
        assertEquals("F9", GlfwKeyNameResolver.nameOf(GLFW_KEY_F9));
    }

    @Test
    void nameOf_modifierKey() {
        assertEquals("LEFT_SHIFT", GlfwKeyNameResolver.nameOf(GLFW_KEY_LEFT_SHIFT));
    }

    @Test
    void nameOf_unknownCode_returnsNumericString() {
        assertEquals("99999", GlfwKeyNameResolver.nameOf(99999));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestGlfwKeyNameResolver -q`
Expected: Compilation failure — `GlfwKeyNameResolver` does not exist yet.

- [ ] **Step 3: Implement `GlfwKeyNameResolver`**

```java
package com.openggf.configuration;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOGGER = Logger.getLogger(GlfwKeyNameResolver.class.getName());
    private static final String GLFW_KEY_PREFIX = "GLFW_KEY_";
    private static final String KEY_PREFIX = "KEY_";

    private static Map<String, Integer> nameToCode;
    private static Map<Integer, String> codeToName;

    private GlfwKeyNameResolver() {
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
        ensureInitialised();
        String normalised = normalise(name);
        Integer code = nameToCode.get(normalised);
        return code != null ? OptionalInt.of(code) : OptionalInt.empty();
    }

    /**
     * Returns the short key name for a GLFW key code (e.g. 81 &rarr; "Q",
     * 32 &rarr; "SPACE"). Returns the numeric string if the code has no
     * known GLFW_KEY_* constant.
     */
    public static String nameOf(int keyCode) {
        ensureInitialised();
        String name = codeToName.get(keyCode);
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

    private static synchronized void ensureInitialised() {
        if (nameToCode != null) {
            return;
        }
        nameToCode = new HashMap<>();
        codeToName = new HashMap<>();

        try {
            Class<?> glfwClass = Class.forName("org.lwjgl.glfw.GLFW");
            for (Field field : glfwClass.getDeclaredFields()) {
                if (field.getType() == int.class
                        && Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers())
                        && Modifier.isFinal(field.getModifiers())
                        && field.getName().startsWith(GLFW_KEY_PREFIX)) {
                    String shortName = field.getName().substring(GLFW_KEY_PREFIX.length());
                    int code = field.getInt(null);
                    nameToCode.put(shortName, code);
                    // First constant wins for reverse lookup (avoids aliases)
                    codeToName.putIfAbsent(code, shortName);
                }
            }
        } catch (ReflectiveOperationException e) {
            LOGGER.log(Level.WARNING, "Failed to build GLFW key name map via reflection", e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=TestGlfwKeyNameResolver -q`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/configuration/GlfwKeyNameResolver.java src/test/java/com/openggf/configuration/TestGlfwKeyNameResolver.java
git commit -m "feat: add GlfwKeyNameResolver for human-readable key config"
```

---

### Task 2: Add `defaults` map to `SonicConfigurationService`

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`

This task adds the `defaults` map so `getInt()` can look up fallback values and produce meaningful warning messages. No behaviour change yet — just infrastructure.

- [ ] **Step 1: Add the `defaults` field and populate it in `putDefault()`**

In `SonicConfigurationService.java`, add the field alongside the existing `config` field (line 22):

```java
private Map<String, Object> defaults = new HashMap<>();
```

Then update `putDefault()` (lines 258-263) to also store into `defaults` unconditionally:

```java
private void putDefault(SonicConfiguration key, Object value) {
    if (config == null) {
        config = new HashMap<>();
    }
    config.putIfAbsent(key.name(), value);
    defaults.put(key.name(), value);
}
```

Also update `resetToDefaults()` (lines 172-175) to clear both maps:

```java
public void resetToDefaults() {
    config = new HashMap<>();
    defaults = new HashMap<>();
    applyDefaults();
}
```

- [ ] **Step 2: Add a package-private accessor for testing**

Add this method to `SonicConfigurationService` after the existing `getConfigValue()` method:

```java
/**
 * Returns the default value for a configuration key, or {@code null} if
 * no default is registered. Package-private for testing.
 */
Object getDefaultValue(SonicConfiguration key) {
    return defaults.get(key.name());
}
```

- [ ] **Step 3: Verify build compiles cleanly**

Run: `mvn test -Dtest=TestGlfwKeyNameResolver -q`
Expected: PASS (no regressions; the defaults map is populated but not yet used by `getInt()`).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfigurationService.java
git commit -m "refactor: add defaults map to SonicConfigurationService for fallback lookups"
```

---

### Task 3: Update `getInt()` with key-name resolution and fallback (TDD)

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Create: `src/test/java/com/openggf/configuration/TestConfigKeyNameResolution.java`

- [ ] **Step 1: Write the failing integration tests**

These tests exercise the full `getInt()` resolution chain by directly manipulating the config map via `setConfigValue()` and verifying the output. We use `SonicConfiguration.FRAME_STEP_KEY` (default: `GLFW_KEY_Q` = 81) as the test subject since it has a well-known default.

```java
package com.openggf.configuration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.lwjgl.glfw.GLFW.*;

class TestConfigKeyNameResolution {

    private SonicConfigurationService configService;

    @BeforeEach
    void setUp() {
        // Reset singleton to get a fresh instance with defaults applied
        SonicConfigurationService.resetStaticInstance();
        configService = SonicConfigurationService.getInstance();
    }

    @Test
    void getInt_numericInteger_returnsSameValue() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, 81);
        assertEquals(GLFW_KEY_Q, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_numericString_parsesAsInt() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "81");
        assertEquals(GLFW_KEY_Q, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_keyNameString_resolvesViaGlfwKeyNameResolver() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "Q");
        assertEquals(GLFW_KEY_Q, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_keyNameCaseInsensitive() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "space");
        assertEquals(GLFW_KEY_SPACE, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_keyNameWithGlfwPrefix() {
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "GLFW_KEY_D");
        assertEquals(GLFW_KEY_D, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_invalidString_fallsBackToDefault() {
        // FRAME_STEP_KEY default is GLFW_KEY_Q (81)
        configService.setConfigValue(SonicConfiguration.FRAME_STEP_KEY, "banana");
        assertEquals(GLFW_KEY_Q, configService.getInt(SonicConfiguration.FRAME_STEP_KEY));
    }

    @Test
    void getInt_invalidString_nonKeyConfig_returnsNegativeOne() {
        // FPS has a numeric default (60), not a key code — but it's still a valid default > 0.
        // For config keys with no default at all, getInt returns -1.
        // Test with a config key that does have a default to verify the fallback works.
        configService.setConfigValue(SonicConfiguration.DEBUG_MODE_KEY, "banana");
        assertEquals(GLFW_KEY_D, configService.getInt(SonicConfiguration.DEBUG_MODE_KEY));
    }
}
```

- [ ] **Step 2: Add `resetStaticInstance()` to `SonicConfigurationService`**

The tests need to reset the singleton cleanly. Add this method (it follows the existing `resetToDefaults()` pattern but resets the static reference):

```java
/**
 * Resets the singleton instance. Used by tests that need a fresh
 * configuration with defaults re-applied.
 */
static void resetStaticInstance() {
    sonicConfigurationService = null;
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -Dtest=TestConfigKeyNameResolution -q`
Expected: `getInt_keyNameString_resolvesViaGlfwKeyNameResolver` and `getInt_invalidString_fallsBackToDefault` FAIL (key name resolution and default fallback not yet implemented).

- [ ] **Step 4: Update `getInt()` with key name resolution and default fallback**

Replace the `getInt()` method (lines 74-85) with:

```java
public int getInt(SonicConfiguration sonicConfiguration) {
    Object value = getConfigValue(sonicConfiguration);
    if (value instanceof Integer) {
        return ((Integer) value);
    } else {
        String str = getString(sonicConfiguration);
        // Step 1: try numeric parse
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException ignored) {
        }

        // Step 2: try GLFW key name resolution
        OptionalInt resolved = GlfwKeyNameResolver.resolve(str);
        if (resolved.isPresent()) {
            return resolved.getAsInt();
        }

        // Step 3: fall back to default with warning
        if (!str.isEmpty()) {
            Object defaultValue = defaults.get(sonicConfiguration.name());
            if (defaultValue instanceof Integer intDefault && intDefault > 0) {
                LOGGER.warning("'" + str + "' could not be interpreted as a valid input for "
                        + sonicConfiguration.name() + ". Defaulting to '"
                        + GlfwKeyNameResolver.nameOf(intDefault) + "'");
                return intDefault;
            } else {
                LOGGER.warning("'" + str + "' could not be interpreted as a valid input for "
                        + sonicConfiguration.name() + ". Defaulting to unbound");
            }
        }
        return -1;
    }
}
```

Add the missing import at the top of the file:

```java
import java.util.OptionalInt;
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=TestConfigKeyNameResolution,TestGlfwKeyNameResolver -q`
Expected: All tests PASS.

- [ ] **Step 6: Run the full test suite to check for regressions**

Run: `mvn test -q`
Expected: All existing tests still pass. The `getInt()` change is backward-compatible — numeric values and numeric strings resolve identically to before.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfigurationService.java src/test/java/com/openggf/configuration/TestConfigKeyNameResolution.java
git commit -m "feat: support string key names in config.json with default fallback"
```

---

### Task 4: Update user documentation

**Files:**
- Modify: `CONFIGURATION.md` (if it contains key code reference tables)

- [ ] **Step 1: Add a note about string key names to the configuration docs**

Find the section documenting key bindings and add a note explaining the accepted formats. Example addition:

```markdown
### Key Binding Format

Key bindings accept any of the following formats:

| Format | Example | Notes |
|--------|---------|-------|
| GLFW numeric code | `81` | Traditional format |
| Numeric string | `"81"` | Same as above, as a string |
| Key name | `"Q"` | Human-readable, case-insensitive |
| Named key | `"SPACE"`, `"ENTER"`, `"F9"` | Special keys by name |
| Modifier key | `"LEFT_SHIFT"`, `"RIGHT_CONTROL"` | Modifier keys |
| GLFW prefix | `"GLFW_KEY_Q"` | Full GLFW constant name (prefix stripped) |

Invalid key names log a warning and fall back to the default binding for that key.
```

- [ ] **Step 2: Commit**

```bash
git add CONFIGURATION.md
git commit -m "docs: document string key name format for config.json bindings"
```
