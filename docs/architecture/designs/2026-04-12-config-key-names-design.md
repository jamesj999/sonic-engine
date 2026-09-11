# Design: String Key Names in config.json

## Problem

Key bindings in `config.json` require raw GLFW numeric codes (e.g. `68` for D, `81` for Q). Users must look up codes externally. The config should accept human-readable key names.

## Solution

Add a `GlfwKeyNameResolver` utility that maps key name strings to GLFW key codes via reflection, and integrate it into `SonicConfigurationService.getInt()` as a fallback when numeric parsing fails.

## Accepted Formats

All of the following resolve to the same GLFW key code and can be used interchangeably in config.json:

| Format | Example | Resolves to |
|--------|---------|-------------|
| JSON number | `81` | 81 (GLFW_KEY_Q) |
| Numeric string | `"81"` | 81 |
| Key name | `"Q"` | 81 |
| Case-insensitive | `"q"`, `"Space"` | 81, 32 |
| With GLFW prefix | `"GLFW_KEY_Q"` | 81 |
| With KEY_ prefix | `"KEY_Q"` | 81 |
| Invalid | `"banana"` | Falls back to default with warning |

## New Class: `GlfwKeyNameResolver`

**Package:** `com.openggf.configuration`

**Public API:**
```java
public static OptionalInt resolve(String name)
public static String nameOf(int keyCode)
```

**Internals:**
- A `Map<String, Integer>` (name-to-code) built once, lazily, via reflection over `org.lwjgl.glfw.GLFW`. Collects every `public static final int` field matching `GLFW_KEY_*`.
- Keys stored stripped of the `GLFW_KEY_` prefix and uppercased (e.g. `"Q"`, `"SPACE"`, `"LEFT_SHIFT"`).
- A reverse `Map<Integer, String>` (code-to-name) built from the same reflection pass.
- `resolve(String name)`: uppercases input, strips `GLFW_KEY_` or `KEY_` prefix if present, looks up in the name-to-code map. Returns `OptionalInt.empty()` on miss.
- `nameOf(int keyCode)`: returns the short name (e.g. `"Q"`, `"SPACE"`). Returns the numeric string as fallback if no GLFW constant matches.

## Changes to `SonicConfigurationService`

### New field: `defaults`

```java
private final Map<String, Object> defaults = new HashMap<>();
```

Populated during `applyDefaults()`. Every `putDefault()` call stores into `defaults` unconditionally (in addition to the existing `putIfAbsent` into `config`).

### Updated `getInt()` resolution order

1. Value is already an `Integer` -> return it directly (unchanged)
2. Value is a `String` -> try `Integer.parseInt()` (unchanged; handles `81` and `"81"`)
3. `parseInt` fails -> try `GlfwKeyNameResolver.resolve()` (new; handles `"Q"`, `"space"`, `"GLFW_KEY_Q"`)
4. `resolve` fails ->
   - Look up default for this `SonicConfiguration` key from the `defaults` map
   - If default exists and is a valid key code (> 0):
     - Log: `WARNING: 'banana' could not be interpreted as a valid input for FRAME_STEP_KEY. Defaulting to 'Q'`
     - Return the default value
   - If no default exists, or default is <= 0:
     - Log: `WARNING: 'banana' could not be interpreted as a valid input for FRAME_STEP_KEY. Defaulting to unbound`
     - Return -1

The warning includes both the bad value and the config key name so users can identify which binding failed when multiple keys are misconfigured.

## Testing

New test class: `TestGlfwKeyNameResolver`

| Test case | Input | Expected |
|-----------|-------|----------|
| Single letter | `"Q"` | `OptionalInt.of(81)` |
| Case insensitive | `"q"` | `OptionalInt.of(81)` |
| Named key | `"SPACE"` | `OptionalInt.of(32)` |
| Function key | `"F9"` | `OptionalInt.of(298)` |
| Modifier key | `"LEFT_SHIFT"` | `OptionalInt.of(340)` |
| GLFW prefix stripped | `"GLFW_KEY_Q"` | `OptionalInt.of(81)` |
| KEY_ prefix stripped | `"KEY_Q"` | `OptionalInt.of(81)` |
| Invalid name | `"banana"` | `OptionalInt.empty()` |
| Reverse lookup | `nameOf(81)` | `"Q"` |
| Reverse lookup named | `nameOf(32)` | `"SPACE"` |
| Reverse unknown code | `nameOf(99999)` | `"99999"` |

Integration-level verification that `getInt()` resolves string key names and falls back with warning for invalid values can be covered in an existing or new `SonicConfigurationService` test.

## Files Changed

| File | Change |
|------|--------|
| `GlfwKeyNameResolver.java` (new) | Reflection-based name/code resolver |
| `SonicConfigurationService.java` | Add `defaults` map, update `getInt()` fallback |
| `TestGlfwKeyNameResolver.java` (new) | Unit tests for resolver |

## Non-Goals

- No changes to `saveConfig()` output format (saved values remain as-is; if the user wrote `"Q"` it stays as `"Q"`)
- No migration of existing numeric values to string names
- No changes to `InputHandler` or any call sites of `getInt()`
