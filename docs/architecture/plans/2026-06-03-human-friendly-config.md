# Human-Friendly Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat, undocumented, unordered `config.json` with a grouped, commented, deterministically-ordered `config.yaml` that is a projection of a metadata catalog over the `SonicConfiguration` enum, with all debug settings fenced into a single `debug:` block.

**Architecture:** A `ConfigCatalog` holds an ordered list of `ConfigKeyMeta` records (section path, leaf name, type, description, allowed values, persisted flag) — one per `SonicConfiguration` constant. `ConfigYamlWriter` emits YAML text from the catalog + the existing flat config map; `ConfigFlattener` parses nested YAML back into the existing enum-name-keyed flat map. The in-memory representation (`Map<String,Object>` keyed by `SonicConfiguration.name()`) and all getters are unchanged — nesting is purely a disk concern. Legacy `config.json` files migrate to `config.yaml` on first load (old file renamed to `.bak`).

**Tech Stack:** Java 21, JUnit 5 (Jupiter), Jackson (`jackson-databind` 2.17.2 already present; add `jackson-dataformat-yaml` 2.17.2), GraalVM native-image config.

**Design reference:** `docs/architecture/designs/2026-06-03-human-friendly-config-design.md`

**Note on mechanism:** The spec describes "metadata on each enum constant." This plan realizes that with a co-located `ConfigCatalog` (a registry keyed by the enum) rather than enum constructor arguments. Rationale: it keeps the `SonicConfiguration` diff at zero, avoids depending on enum declaration/ordinal order (which other code may rely on), and puts all metadata in one readable, testable table. A completeness test guarantees every constant has an entry, so the enum + catalog together remain the single source of truth.

**Commit trailers:** This branch is non-`master`, so every commit MUST end with the trailer block below. Adjust `Changelog`/`Configuration-Docs` per task as noted; all others stay `n/a` unless that task stages the mapped file.

```
Changelog: n/a: aggregated CHANGELOG entry added in Task 9 of this plan
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a: CONFIGURATION.md rewritten in Task 9 of this plan
Skills: n/a

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
```

---

## File Structure

**New files (all under `src/main/java/com/openggf/configuration/`):**
- `ConfigType.java` — enum of value kinds (`BOOL`, `INT`, `DOUBLE`, `STRING`, `KEY`, `ENUM`).
- `ConfigKeyMeta.java` — immutable record describing one key (section, leaf, type, description, allowedValues, persisted).
- `ConfigCatalog.java` — the ordered metadata table for every `SonicConfiguration`; emit order, reverse lookup (`section.leaf` → key), section titles.
- `ConfigYamlWriter.java` — renders the flat config map to grouped/commented YAML text.
- `ConfigFlattener.java` — parses nested YAML map → flat enum-name map + unknown-key list.

**New tests (under `src/test/java/com/openggf/configuration/`):**
- `TestConfigCatalog.java`, `TestConfigYamlWriter.java`, `TestConfigFlattener.java`, `TestLegacyConfigMigration.java`, `TestBundledConfigResource.java`, `TestYamlDependencyAvailable.java`.

**Modified files:**
- `pom.xml` — add `jackson-dataformat-yaml`; change native-image copy step `config.json` → `config.yaml`.
- `src/main/resources/config.json` → replaced by generated `src/main/resources/config.yaml`.
- `src/main/resources/META-INF/native-image/com.openggf/OpenGGF/resource-config.json` — register `config\.yaml`.
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java` — load/save/resolve, migration flow, log strings.
- `src/main/java/com/openggf/testmode/TestModeTracePicker.java:82` — on-screen string.
- `src/packaging/assemble-macos-app.sh` — copy `config.yaml`.
- `CONFIGURATION.md` — rewrite for YAML layout.
- `CHANGELOG.md` — entry.
- Existing tests: `TestSonicConfigurationFileBootstrap.java`, `src/test/java/com/openggf/tests/TestSonicConfigurationService.java`, `src/test/java/com/openggf/widescreen/TestWidescreenNativeRegression.java`.

---

## Task 1: Add the YAML dependency

**Files:**
- Modify: `pom.xml` (dependencies block ends at line 475)
- Test: `src/test/java/com/openggf/configuration/TestYamlDependencyAvailable.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestYamlDependencyAvailable {
    @Test
    void yamlMapperParsesNestedDocument() throws Exception {
        ObjectMapper yaml = new YAMLMapper();
        Map<String, Object> parsed = yaml.readValue("audio:\n  enabled: true\n",
                yaml.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> audio = (Map<String, Object>) parsed.get("audio");
        assertEquals(Boolean.TRUE, audio.get("enabled"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails (compile error: package does not exist)**

Run: `mvn "-Dtest=TestYamlDependencyAvailable" test`
Expected: FAIL — `package com.fasterxml.jackson.dataformat.yaml does not exist`.

- [ ] **Step 3: Add the dependency**

In `pom.xml`, immediately after the closing `</dependency>` of `jackson-databind` (line 474) and before `</dependencies>` (line 475), insert:

```xml
        <dependency>
            <groupId>com.fasterxml.jackson.dataformat</groupId>
            <artifactId>jackson-dataformat-yaml</artifactId>
            <version>2.17.2</version>
        </dependency>
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=TestYamlDependencyAvailable" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/test/java/com/openggf/configuration/TestYamlDependencyAvailable.java
git commit   # message: "build: add jackson-dataformat-yaml for human-friendly config" + trailer block
```
Use `Changelog: n/a: build dependency only, no engine behavior change` for this commit's Changelog trailer.

---

## Task 2: Value-kind enum and key-metadata record

**Files:**
- Create: `src/main/java/com/openggf/configuration/ConfigType.java`
- Create: `src/main/java/com/openggf/configuration/ConfigKeyMeta.java`
- Test: `src/test/java/com/openggf/configuration/TestConfigKeyMeta.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigKeyMeta {
    @Test
    void persistedFactoryPopulatesFields() {
        ConfigKeyMeta m = ConfigKeyMeta.of("audio", "enabled", ConfigType.BOOL, "Music + SFX");
        assertEquals("audio", m.section());
        assertEquals("enabled", m.leaf());
        assertEquals(ConfigType.BOOL, m.type());
        assertEquals("Music + SFX", m.description());
        assertTrue(m.persisted());
        assertTrue(m.allowedValues().isEmpty());
    }

    @Test
    void enumFactoryCarriesAllowedValues() {
        ConfigKeyMeta m = ConfigKeyMeta.ofEnum("audio", "region", "NTSC/PAL timing", Set.of("NTSC", "PAL"));
        assertEquals(ConfigType.ENUM, m.type());
        assertEquals(Set.of("NTSC", "PAL"), m.allowedValues());
    }

    @Test
    void derivedKeyIsNotPersistedAndHasNoSection() {
        ConfigKeyMeta m = ConfigKeyMeta.derived(ConfigType.INT, "Derived screen pixel width");
        assertFalse(m.persisted());
        assertNull(m.section());
        assertNull(m.leaf());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestConfigKeyMeta" test`
Expected: FAIL — `ConfigType`/`ConfigKeyMeta` do not exist.

- [ ] **Step 3: Create `ConfigType.java`**

```java
package com.openggf.configuration;

/** The kind of a configuration value, used to format and validate it. */
public enum ConfigType {
    BOOL,
    INT,
    DOUBLE,
    STRING,
    /** A GLFW key binding, rendered as a key name (e.g. {@code SPACE}). */
    KEY,
    /** A string restricted to a fixed set of allowed values. */
    ENUM
}
```

- [ ] **Step 4: Create `ConfigKeyMeta.java`**

```java
package com.openggf.configuration;

import java.util.Set;

/**
 * Metadata for a single {@link SonicConfiguration} key: where it lives on disk,
 * its value kind, its human description, and whether it is persisted at all.
 *
 * <p>A {@code persisted == false} key is DERIVED: it is computed at runtime and
 * never read from or written to disk, so it has no {@code section}/{@code leaf}.
 */
public record ConfigKeyMeta(
        String section,
        String leaf,
        ConfigType type,
        String description,
        Set<String> allowedValues,
        boolean persisted) {

    public static ConfigKeyMeta of(String section, String leaf, ConfigType type, String description) {
        return new ConfigKeyMeta(section, leaf, type, description, Set.of(), true);
    }

    public static ConfigKeyMeta ofEnum(String section, String leaf, String description, Set<String> allowedValues) {
        return new ConfigKeyMeta(section, leaf, ConfigType.ENUM, description, Set.copyOf(allowedValues), true);
    }

    public static ConfigKeyMeta derived(ConfigType type, String description) {
        return new ConfigKeyMeta(null, null, type, description, Set.of(), false);
    }

    /** Full dotted path used as the flatten reverse-lookup key, e.g. {@code "input.player1.jump"}. */
    public String path() {
        return section + "." + leaf;
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn "-Dtest=TestConfigKeyMeta" test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/configuration/ConfigType.java src/main/java/com/openggf/configuration/ConfigKeyMeta.java src/test/java/com/openggf/configuration/TestConfigKeyMeta.java
git commit   # "feat(config): add ConfigType and ConfigKeyMeta" + trailer block
```
Changelog trailer: `Changelog: n/a: internal scaffolding, no user-visible change yet`.

---

## Task 3: The configuration catalog (all keys)

This is the metadata table for every `SonicConfiguration` constant, in emit order. Write the completeness test first so the table is provably exhaustive.

**Files:**
- Create: `src/main/java/com/openggf/configuration/ConfigCatalog.java`
- Test: `src/test/java/com/openggf/configuration/TestConfigCatalog.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigCatalog {

    @Test
    void everyConstantExceptVersionHasMeta() {
        for (SonicConfiguration key : SonicConfiguration.values()) {
            if (key == SonicConfiguration.VERSION) {
                continue;
            }
            assertNotNull(ConfigCatalog.meta(key), "missing catalog meta for " + key);
            ConfigKeyMeta m = ConfigCatalog.meta(key);
            assertNotNull(m.type(), "missing type for " + key);
            assertNotNull(m.description(), "missing description for " + key);
            assertFalse(m.description().isBlank(), "blank description for " + key);
        }
    }

    @Test
    void persistedKeysHaveSectionAndLeaf() {
        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
            ConfigKeyMeta m = ConfigCatalog.meta(key);
            assertTrue(m.persisted(), "emitOrder must contain only persisted keys: " + key);
            assertNotNull(m.section(), "missing section for " + key);
            assertNotNull(m.leaf(), "missing leaf for " + key);
        }
    }

    @Test
    void enumTypedKeysHaveAllowedValues() {
        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
            ConfigKeyMeta m = ConfigCatalog.meta(key);
            if (m.type() == ConfigType.ENUM) {
                assertFalse(m.allowedValues().isEmpty(), "ENUM key without allowedValues: " + key);
            }
        }
    }

    @Test
    void derivedKeysAreNotInEmitOrder() {
        assertFalse(ConfigCatalog.emitOrder().contains(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertFalse(ConfigCatalog.emitOrder().contains(SonicConfiguration.SCREEN_HEIGHT_PIXELS));
        assertFalse(ConfigCatalog.meta(SonicConfiguration.SCREEN_WIDTH_PIXELS).persisted());
    }

    @Test
    void debugSectionsAreContiguousAndLast() {
        List<SonicConfiguration> order = ConfigCatalog.emitOrder();
        boolean seenDebug = false;
        for (SonicConfiguration key : order) {
            boolean isDebug = ConfigCatalog.meta(key).section().startsWith("debug");
            if (isDebug) {
                seenDebug = true;
            } else {
                assertFalse(seenDebug, "normal key " + key + " appears after a debug key");
            }
        }
        assertTrue(seenDebug, "expected at least one debug.* key");
    }

    @Test
    void reverseLookupRoundTrips() {
        SonicConfiguration key = ConfigCatalog.byPath("input.player1.jump");
        assertEquals(SonicConfiguration.JUMP, key);
        assertNull(ConfigCatalog.byPath("nope.not.real"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestConfigCatalog" test`
Expected: FAIL — `ConfigCatalog` does not exist.

- [ ] **Step 3: Create `ConfigCatalog.java`**

```java
package com.openggf.configuration;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.openggf.configuration.ConfigKeyMeta.derived;
import static com.openggf.configuration.ConfigKeyMeta.of;
import static com.openggf.configuration.ConfigKeyMeta.ofEnum;
import static com.openggf.configuration.ConfigType.BOOL;
import static com.openggf.configuration.ConfigType.DOUBLE;
import static com.openggf.configuration.ConfigType.INT;
import static com.openggf.configuration.ConfigType.KEY;
import static com.openggf.configuration.ConfigType.STRING;
import static com.openggf.configuration.SonicConfiguration.*;

/**
 * Metadata table over {@link SonicConfiguration}. The declaration order of
 * {@link #META} is the on-disk emit order: all normal sections first, then the
 * fenced {@code debug.*} block. DERIVED keys (never persisted) are included for
 * completeness but excluded from {@link #emitOrder()}.
 */
public final class ConfigCatalog {

    private static final Map<SonicConfiguration, ConfigKeyMeta> META = new EnumMap<>(SonicConfiguration.class);
    private static final List<SonicConfiguration> EMIT_ORDER;
    private static final Map<String, SonicConfiguration> BY_PATH = new LinkedHashMap<>();
    private static final Map<String, String> SECTION_TITLES = new LinkedHashMap<>();

    private static void put(SonicConfiguration key, ConfigKeyMeta meta) {
        META.put(key, meta);
    }

    static {
        // ---- DERIVED (never on disk) ----
        put(SCREEN_WIDTH_PIXELS, derived(INT, "Derived screen width in pixels (from display.aspect)"));
        put(SCREEN_HEIGHT_PIXELS, derived(INT, "Derived screen height in pixels (always 224)"));

        // ───────────────── NORMAL SECTIONS ─────────────────

        // display
        put(DISPLAY_ASPECT, ofEnum("display", "aspect",
                "Display aspect preset; resolves screen pixel width, height stays 224",
                Set.of("NATIVE_4_3", "WIDE_16_10", "WIDE_16_9", "ULTRA_21_9", "SUPER_32_9")));
        put(DISPLAY_WINDOW_AUTOSIZE, of("display", "windowAutosize", BOOL,
                "Derive the window size from the aspect preset at the 2x baseline"));
        put(WIDESCREEN_DEADZONE_MODE, ofEnum("display", "deadzoneMode",
                "Camera horizontal deadzone behaviour on wide screens",
                Set.of("CENTER_SCALED", "PROPORTIONAL")));
        put(DISPLAY_COLOR_PROFILE, ofEnum("display", "colorProfile",
                "Display-only color profile for Mega Drive palette presentation",
                Set.of("RAW_RGB", "MD_ANALOG", "NTSC_SOFT")));
        put(DISPLAY_COLOR_PROFILE_TOGGLE_KEY, of("display", "colorProfileToggleKey", KEY,
                "Runtime key to cycle the display color profile"));
        put(FPS, of("display", "fps", INT, "Frames per second to render (changes game speed)"));

        // input (player-agnostic leaf first, then per-player subsections)
        put(PAUSE_KEY, of("input", "pause", KEY, "Toggle pause"));
        put(UP, of("input.player1", "up", KEY, "Player 1: look up"));
        put(DOWN, of("input.player1", "down", KEY, "Player 1: crouch/roll"));
        put(LEFT, of("input.player1", "left", KEY, "Player 1: move left"));
        put(RIGHT, of("input.player1", "right", KEY, "Player 1: move right"));
        put(JUMP, of("input.player1", "jump", KEY, "Player 1: jump"));
        put(P2_UP, of("input.player2", "up", KEY, "Player 2: look up"));
        put(P2_DOWN, of("input.player2", "down", KEY, "Player 2: crouch/roll"));
        put(P2_LEFT, of("input.player2", "left", KEY, "Player 2: move left"));
        put(P2_RIGHT, of("input.player2", "right", KEY, "Player 2: move right"));
        put(P2_JUMP, of("input.player2", "jump", KEY, "Player 2: jump"));
        put(P2_START, of("input.player2", "start", KEY, "Player 2: start"));

        // audio
        put(AUDIO_ENABLED, of("audio", "enabled", BOOL, "Enable music and SFX"));
        put(REGION, ofEnum("audio", "region", "Region for audio timing", Set.of("NTSC", "PAL")));
        put(DAC_INTERPOLATE, of("audio", "dacInterpolate", BOOL, "DAC interpolation (smoother sound)"));
        put(AUDIO_INTERNAL_RATE_OUTPUT, of("audio", "internalRateOutput", BOOL,
                "Output audio at the internal YM2612 rate (~53kHz)"));
        put(PSG_NOISE_SHIFT_EVERY_TOGGLE, of("audio", "psgNoiseShiftEveryToggle", BOOL,
                "PSG noise LFSR clock mode: true=shift on every toggle (MAME), false=positive edges (GPGX)"));
        put(FM6_DAC_OFF, of("audio", "fm6DacOff", BOOL,
                "Mute FM6 when a note plays on it while DAC is enabled (SMPSPlay parity hack)"));

        // characters
        put(MAIN_CHARACTER_CODE, of("characters", "main", STRING, "Sprite code of the main playable character"));
        put(SIDEKICK_CHARACTER_CODE, of("characters", "sidekick", STRING,
                "Sprite code of the CPU sidekick; empty string disables the sidekick"));
        put(DATA_SELECT_EXTRA_PLAYER_COMBOS, of("characters", "dataSelectExtraCombos", STRING,
                "Semicolon-separated extra player combos for the data select screen"));

        // roms
        put(SONIC_1_ROM, of("roms", "sonic1", STRING, "Filename of the Sonic 1 ROM"));
        put(SONIC_2_ROM, of("roms", "sonic2", STRING, "Filename of the Sonic 2 ROM"));
        put(SONIC_3K_ROM, of("roms", "sonic3k", STRING, "Filename of the Sonic 3&K ROM"));
        put(DEFAULT_ROM, ofEnum("roms", "default", "Which game to load by default",
                Set.of("s1", "s2", "s3k")));

        // startup
        put(TITLE_SCREEN_ON_STARTUP, of("startup", "titleScreen", BOOL, "Show the title screen on startup"));
        put(MASTER_TITLE_SCREEN_ON_STARTUP, of("startup", "masterTitleScreen", BOOL,
                "Show the master (game-selection) title screen on startup"));
        put(SHOW_LEGAL_DISCLAIMER_ON_STARTUP, of("startup", "legalDisclaimer", BOOL,
                "Show the legal disclaimer screen before the master title screen"));

        // rewind (player-facing live rewind)
        put(LIVE_REWIND_ENABLED, of("rewind", "liveEnabled", BOOL,
                "Enable held-key rewind during ordinary live play"));
        put(LIVE_REWIND_KEY, of("rewind", "liveKey", KEY, "Key held to rewind during live play"));
        put(LIVE_REWIND_TAPE_COAST_ENABLED, of("rewind", "tapeCoastEnabled", BOOL,
                "Continue rewinding with a decelerating tape-coast after key release"));
        put(LIVE_REWIND_TAPE_COAST_ACCELERATION, of("rewind", "tapeCoastAcceleration", DOUBLE,
                "Per-tick speed increase while tape-coast is held"));
        put(LIVE_REWIND_TAPE_COAST_DECELERATION, of("rewind", "tapeCoastDeceleration", DOUBLE,
                "Per-tick speed decrease after release"));
        put(LIVE_REWIND_TAPE_COAST_MAX_STEPS, of("rewind", "tapeCoastMaxSteps", DOUBLE,
                "Maximum rewind steps per tick"));
        put(LIVE_REWIND_TAPE_COAST_MIN_STEPS, of("rewind", "tapeCoastMinSteps", DOUBLE,
                "Minimum rewind steps per tick; below 1.0 gives slow-motion rewind"));
        put(REWIND_AUDIO_HISTORY_LIMIT_TYPE, ofEnum("rewind", "audioHistoryLimitType",
                "How the rewind audio PCM history ring is sized", Set.of("time", "size")));
        put(REWIND_AUDIO_HISTORY_SECONDS, of("rewind", "audioHistorySeconds", INT,
                "Seconds of PCM history kept when audioHistoryLimitType=time"));
        put(REWIND_AUDIO_HISTORY_SIZE_MB, of("rewind", "audioHistorySizeMb", INT,
                "Megabytes of PCM history kept when audioHistoryLimitType=size"));

        // crossGame (user-facing donation toggles)
        put(CROSS_GAME_FEATURES_ENABLED, of("crossGame", "enabled", BOOL,
                "Enable cross-game feature donation (e.g. S2 sprites in S1)"));
        put(CROSS_GAME_SOURCE, ofEnum("crossGame", "source",
                "Donor game for cross-game features", Set.of("s2", "s3k")));

        // discord
        put(DISCORD_RICH_PRESENCE_ENABLED, of("discord", "enabled", BOOL,
                "Enable Discord Rich Presence updates"));
        put(DISCORD_RICH_PRESENCE_SHOW_TIMER, of("discord", "showTimer", BOOL,
                "Show the level timer in Rich Presence"));
        put(DISCORD_RICH_PRESENCE_SHOW_ZONE, of("discord", "showZone", BOOL,
                "Show the zone and act in Rich Presence"));

        // ───────────────── DEBUG BLOCK ─────────────────

        // debug.flags
        put(DEBUG_VIEW_ENABLED, of("debug.flags", "debugView", BOOL, "Show the on-screen debug HUD"));
        put(EDITOR_ENABLED, of("debug.flags", "editor", BOOL, "Allow entering the level editor from gameplay"));
        put(DEBUG_COLLISION_VIEW_ENABLED, of("debug.flags", "collisionView", BOOL,
                "Draw the collision overlay"));

        // debug.keys
        put(TEST, of("debug.keys", "test", KEY, "Debug-only test button"));
        put(NEXT_ACT, of("debug.keys", "nextAct", KEY, "Advance to the next act"));
        put(NEXT_ZONE, of("debug.keys", "nextZone", KEY, "Advance to the next zone"));
        put(DEBUG_MODE_KEY, of("debug.keys", "debugMode", KEY, "Toggle debug movement mode"));
        put(FRAME_STEP_KEY, of("debug.keys", "frameStep", KEY, "Step forward one frame while paused"));
        put(DEBUG_LAST_CHECKPOINT_KEY, of("debug.keys", "lastCheckpoint", KEY,
                "Teleport to the last checkpoint"));
        put(LEVEL_SELECT_KEY, of("debug.keys", "levelSelect", KEY, "Open the level select screen"));
        put(SUPER_SONIC_DEBUG_KEY, of("debug.keys", "superSonic", KEY, "Toggle Super Sonic debug mode"));
        put(GIVE_EMERALDS_KEY, of("debug.keys", "giveEmeralds", KEY, "Give all chaos emeralds"));
        put(SPECIAL_STAGE_KEY, of("debug.keys", "specialStage", KEY, "Toggle special stage mode"));
        put(SPECIAL_STAGE_COMPLETE_KEY, of("debug.keys", "specialStageComplete", KEY,
                "Complete the special stage with an emerald"));
        put(SPECIAL_STAGE_FAIL_KEY, of("debug.keys", "specialStageFail", KEY, "Fail the special stage"));
        put(SPECIAL_STAGE_SPRITE_DEBUG_KEY, of("debug.keys", "specialStageSpriteDebug", KEY,
                "Toggle the special stage sprite debug viewer"));
        put(SPECIAL_STAGE_PLANE_DEBUG_KEY, of("debug.keys", "specialStagePlaneDebug", KEY,
                "Cycle special stage plane visibility debug modes"));

        // debug.startup
        put(LEVEL_SELECT_ON_STARTUP, of("debug.startup", "levelSelectOnStartup", BOOL,
                "Open Level Select on startup instead of loading the first zone"));
        put(S3K_SKIP_INTROS, of("debug.startup", "s3kSkipIntros", BOOL,
                "Skip S3K zone intro sequences (AIZ biplane, etc.)"));

        // debug.playback
        put(PLAYBACK_MOVIE_PATH, of("debug.playback", "moviePath", STRING,
                "Path to a BizHawk BK2 movie for playback debugging"));
        put(PLAYBACK_TOGGLE_KEY, of("debug.playback", "toggleKey", KEY, "Toggle playback mode"));
        put(PLAYBACK_LOAD_KEY, of("debug.playback", "loadKey", KEY, "Load/reload the BK2 movie"));
        put(PLAYBACK_PLAY_PAUSE_KEY, of("debug.playback", "playPauseKey", KEY, "Toggle playback play/pause"));
        put(PLAYBACK_STEP_BACK_KEY, of("debug.playback", "stepBackKey", KEY, "Step the cursor back one frame"));
        put(PLAYBACK_STEP_FORWARD_KEY, of("debug.playback", "stepForwardKey", KEY,
                "Step the cursor forward one frame"));
        put(PLAYBACK_JUMP_BACK_KEY, of("debug.playback", "jumpBackKey", KEY,
                "Jump the cursor back by a larger interval"));
        put(PLAYBACK_JUMP_FORWARD_KEY, of("debug.playback", "jumpForwardKey", KEY,
                "Jump the cursor forward by a larger interval"));
        put(PLAYBACK_FAST_RATE_KEY, of("debug.playback", "fastRateKey", KEY,
                "Cycle playback rate (1x/2x/4x/8x)"));
        put(PLAYBACK_RESET_TO_START_KEY, of("debug.playback", "resetToStartKey", KEY,
                "Reset the cursor to the start offset"));
        put(PLAYBACK_START_OFFSET_FRAME, of("debug.playback", "startOffsetFrame", INT,
                "Starting frame offset for BK2 playback"));

        // debug.traceRewind
        put(TRACE_REWIND_KEY, of("debug.traceRewind", "key", KEY,
                "Key held in Trace Test Mode to rewind deterministic engine state"));

        // debug.testMode
        put(TEST_MODE_ENABLED, of("debug.testMode", "enabled", BOOL,
                "Replace the master title with the trace picker (dev-only)"));
        put(TRACE_CATALOG_DIR, of("debug.testMode", "catalogDir", STRING,
                "Directory scanned for traces when test mode is enabled"));

        // debug.crossGame (debug tooling)
        put(CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE, of("debug.crossGame",
                "s1DataSelectImageGenOverride", BOOL, "Force regeneration of the S1 data-select image cache"));
        put(CROSS_GAME_S2_DATA_SELECT_IMAGE_GEN_OVERRIDE, of("debug.crossGame",
                "s2DataSelectImageGenOverride", BOOL, "Force regeneration of the S2 data-select image cache"));
        put(CROSS_GAME_S1_DATA_SELECT_IMAGE_COORD_LOG_KEY, of("debug.crossGame",
                "s1DataSelectImageCoordLogKey", KEY,
                "Log the current camera position as an S1 data-select preview override"));

        // debug.window (DEPRECATED manual window/scale)
        put(SCREEN_WIDTH, of("debug.window", "width", INT,
                "DEPRECATED manual window width; used only when display.windowAutosize=false"));
        put(SCREEN_HEIGHT, of("debug.window", "height", INT,
                "DEPRECATED manual window height; used only when display.windowAutosize=false"));
        put(SCALE, of("debug.window", "scale", DOUBLE,
                "DEPRECATED AWT debug-viewer scale factor"));

        // ---- Derived index structures ----
        java.util.List<SonicConfiguration> order = new java.util.ArrayList<>();
        for (Map.Entry<SonicConfiguration, ConfigKeyMeta> e : META.entrySet()) {
            ConfigKeyMeta m = e.getValue();
            if (m.persisted()) {
                order.add(e.getKey());
                BY_PATH.put(m.path(), e.getKey());
            }
        }
        EMIT_ORDER = List.copyOf(order);

        SECTION_TITLES.put("display", "Display");
        SECTION_TITLES.put("input", "Input");
        SECTION_TITLES.put("audio", "Audio");
        SECTION_TITLES.put("characters", "Characters");
        SECTION_TITLES.put("roms", "ROMs");
        SECTION_TITLES.put("startup", "Startup");
        SECTION_TITLES.put("rewind", "Rewind (live)");
        SECTION_TITLES.put("crossGame", "Cross-Game");
        SECTION_TITLES.put("discord", "Discord Rich Presence");
    }

    private ConfigCatalog() {
    }

    public static ConfigKeyMeta meta(SonicConfiguration key) {
        return META.get(key);
    }

    /** Persisted keys only, in on-disk emit order (EnumMap preserves the insertion-described order via the list built above). */
    public static List<SonicConfiguration> emitOrder() {
        return EMIT_ORDER;
    }

    /** Reverse lookup: dotted {@code section.leaf} path → key, or {@code null} if unknown. */
    public static SonicConfiguration byPath(String path) {
        return BY_PATH.get(path);
    }

    /** Human title for a top-level section, or the raw name if none registered. */
    public static String title(String topLevelSection) {
        return SECTION_TITLES.getOrDefault(topLevelSection, topLevelSection);
    }
}
```

> **IMPORTANT — emit order:** `EnumMap` iterates in *enum ordinal* order, not insertion order. The `emitOrder()` built above therefore follows `SonicConfiguration` declaration order filtered to persisted keys — which is **not** the section grouping written in this catalog. To make emit order match the catalog's declaration order (normal sections first, debug last, grouped), replace the backing `META` map with a `LinkedHashMap<>` instead of `EnumMap<>` (insertion-ordered). Change the field declaration to `new LinkedHashMap<>()`. Keep the `put(...)` order exactly as above. The `debugSectionsAreContiguousAndLast` test enforces this; if it fails, you used `EnumMap`.

- [ ] **Step 4: Fix the backing map ordering**

In `ConfigCatalog.java`, change:
```java
private static final Map<SonicConfiguration, ConfigKeyMeta> META = new EnumMap<>(SonicConfiguration.class);
```
to:
```java
private static final Map<SonicConfiguration, ConfigKeyMeta> META = new LinkedHashMap<>();
```
(The `java.util.LinkedHashMap` import is already present.) Also remove the now-unused `import java.util.EnumMap;` line.

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn "-Dtest=TestConfigCatalog" test`
Expected: PASS (all 6 tests). If `debugSectionsAreContiguousAndLast` or `everyConstantExceptVersionHasMeta` fails, you either used `EnumMap` (Step 4) or missed a key — the failure message names the missing constant.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/configuration/ConfigCatalog.java src/test/java/com/openggf/configuration/TestConfigCatalog.java
git commit   # "feat(config): add ConfigCatalog metadata table for all keys" + trailer block
```
Changelog trailer: `Changelog: n/a: internal metadata table, no user-visible change yet`.

---

## Task 4: Flatten nested YAML into the flat map

**Files:**
- Create: `src/main/java/com/openggf/configuration/ConfigFlattener.java`
- Test: `src/test/java/com/openggf/configuration/TestConfigFlattener.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigFlattener {

    private Map<String, Object> nested() {
        Map<String, Object> player1 = new LinkedHashMap<>();
        player1.put("jump", "SPACE");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("player1", player1);
        input.put("pause", "ENTER");
        Map<String, Object> audio = new LinkedHashMap<>();
        audio.put("enabled", Boolean.TRUE);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("input", input);
        root.put("audio", audio);
        return root;
    }

    @Test
    void flattensKnownLeavesToEnumNames() {
        ConfigFlattener.Result r = ConfigFlattener.flatten(nested());
        assertEquals("SPACE", r.flat().get("JUMP"));
        assertEquals("ENTER", r.flat().get("PAUSE_KEY"));
        assertEquals(Boolean.TRUE, r.flat().get("AUDIO_ENABLED"));
        assertTrue(r.unknownKeys().isEmpty());
    }

    @Test
    void collectsUnknownLeaves() {
        Map<String, Object> root = nested();
        @SuppressWarnings("unchecked")
        Map<String, Object> audio = (Map<String, Object>) root.get("audio");
        audio.put("bogusKey", 1);
        ConfigFlattener.Result r = ConfigFlattener.flatten(root);
        assertTrue(r.unknownKeys().contains("audio.bogusKey"));
        assertFalse(r.flat().containsKey("bogusKey"));
    }

    @Test
    void emptyOrNullInputYieldsEmptyResult() {
        assertTrue(ConfigFlattener.flatten(null).flat().isEmpty());
        assertTrue(ConfigFlattener.flatten(Map.of()).flat().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestConfigFlattener" test`
Expected: FAIL — `ConfigFlattener` does not exist.

- [ ] **Step 3: Create `ConfigFlattener.java`**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=TestConfigFlattener" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/configuration/ConfigFlattener.java src/test/java/com/openggf/configuration/TestConfigFlattener.java
git commit   # "feat(config): add ConfigFlattener (nested YAML -> flat map)" + trailer block
```
Changelog trailer: `Changelog: n/a: internal read helper, not yet wired in`.

---

## Task 5: Emit grouped, commented YAML

**Files:**
- Create: `src/main/java/com/openggf/configuration/ConfigYamlWriter.java`
- Test: `src/test/java/com/openggf/configuration/TestConfigYamlWriter.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigYamlWriter {

    private Map<String, Object> defaults() {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
            ConfigKeyMeta m = ConfigCatalog.meta(key);
            Object v = switch (m.type()) {
                case BOOL -> Boolean.FALSE;
                case INT -> 0;
                case DOUBLE -> 0.0;
                case KEY -> "SPACE";
                default -> "x";
            };
            flat.put(key.name(), v);
        }
        // a value that must be quoted (spaces, brackets, '!')
        flat.put(SonicConfiguration.SONIC_2_ROM.name(), "Sonic The Hedgehog 2 (W) (REV01) [!].gen");
        flat.put(SonicConfiguration.PLAYBACK_MOVIE_PATH.name(), "");
        return flat;
    }

    @Test
    void emitsParseableYamlWithSectionsAndDebugFence() throws Exception {
        String yaml = new ConfigYamlWriter().write(defaults());
        // Has a top-level display section and the debug block.
        assertTrue(yaml.contains("display:"), yaml);
        assertTrue(yaml.contains("# ── Display ──"), yaml);
        assertTrue(yaml.contains("\ndebug:"), yaml);
        assertTrue(yaml.contains("DEBUG"), yaml);
        // Round-trips through a real YAML parser back to the same flat values.
        ObjectMapper mapper = new YAMLMapper();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(yaml, Map.class);
        ConfigFlattener.Result r = ConfigFlattener.flatten(parsed);
        assertTrue(r.unknownKeys().isEmpty(), "unexpected unknown keys: " + r.unknownKeys());
        assertEquals("Sonic The Hedgehog 2 (W) (REV01) [!].gen",
                r.flat().get(SonicConfiguration.SONIC_2_ROM.name()));
    }

    @Test
    void derivedKeysAreNeverEmitted() {
        String yaml = new ConfigYamlWriter().write(defaults());
        assertFalse(yaml.contains("SCREEN_WIDTH_PIXELS"));
        assertFalse(yaml.contains("pixelWidth"));
    }

    @Test
    void outputIsDeterministic() {
        ConfigYamlWriter w = new ConfigYamlWriter();
        assertEquals(w.write(defaults()), w.write(defaults()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestConfigYamlWriter" test`
Expected: FAIL — `ConfigYamlWriter` does not exist.

- [ ] **Step 3: Create `ConfigYamlWriter.java`**

```java
package com.openggf.configuration;

import java.util.Map;
import java.util.OptionalInt;

/**
 * Renders the flat config map to grouped, commented, deterministically-ordered
 * YAML. Walks {@link ConfigCatalog#emitOrder()} (persisted keys only), opening
 * nested mapping blocks as section paths deepen, emitting a {@code # ── Title ──}
 * banner per top-level normal section and a single fence banner when the
 * {@code debug.*} block begins.
 */
public final class ConfigYamlWriter {

    public String write(Map<String, Object> flat) {
        StringBuilder sb = new StringBuilder();
        sb.append("# OpenGGF configuration — grouped and documented.\n");
        sb.append("# Indentation is significant (YAML). This file is rewritten cleanly on save.\n");

        String[] prev = new String[0];
        boolean debugOpened = false;

        for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
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
        if (v instanceof Number n) {
            return GlfwKeyNameResolver.nameOf(n.intValue());
        }
        String s = String.valueOf(v).trim();
        try {
            return GlfwKeyNameResolver.nameOf(Integer.parseInt(s));
        } catch (NumberFormatException ignored) {
            // already a key name (or empty)
        }
        OptionalInt resolved = GlfwKeyNameResolver.resolve(s);
        return resolved.isPresent() ? GlfwKeyNameResolver.nameOf(resolved.getAsInt()) : s;
    }

    /** Always double-quote string/enum scalars so special characters (spaces, [], !, :) are safe. */
    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
```

> **Verify before relying on it:** confirm `GlfwKeyNameResolver` exposes `static String nameOf(int)` and `static OptionalInt resolve(String)` (both are used by `SonicConfigurationService` already — see `getInt()` and `putDefaultKey()`). If `nameOf` returns `null`/empty for an unmapped code, `formatKey` will emit an empty token; that is acceptable (unbound), and the round-trip test still passes because the defaults map uses `"SPACE"`.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=TestConfigYamlWriter" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/configuration/ConfigYamlWriter.java src/test/java/com/openggf/configuration/TestConfigYamlWriter.java
git commit   # "feat(config): add ConfigYamlWriter (grouped/commented emitter)" + trailer block
```
Changelog trailer: `Changelog: n/a: internal write helper, not yet wired in`.

---

## Task 6: Wire YAML load/save into the service

Switch `SonicConfigurationService` to read/write `config.yaml` (flatten on read, `ConfigYamlWriter` on write) while leaving every getter and the `defaults`/`transientResolved` logic unchanged.

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Test: `src/test/java/com/openggf/configuration/TestConfigServiceYamlRoundTrip.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class TestConfigServiceYamlRoundTrip {

    private final File yaml = new File("config.yaml");

    @AfterEach
    void cleanup() {
        yaml.delete();
    }

    @Test
    void saveWritesGroupedYaml() throws Exception {
        SonicConfigurationService svc = SonicConfigurationService.createStandalone();
        svc.saveConfig();
        assertTrue(yaml.exists(), "saveConfig must write config.yaml");
        String text = Files.readString(yaml.toPath());
        assertTrue(text.contains("display:"), text);
        assertTrue(text.contains("debug:"), text);
        assertFalse(text.contains("SCREEN_WIDTH_PIXELS"), "derived keys must not be persisted");
    }

    @Test
    void loadReadsBackWrittenValues() throws Exception {
        SonicConfigurationService svc = SonicConfigurationService.createStandalone();
        svc.setConfigValue(SonicConfiguration.AUDIO_ENABLED, false);
        svc.saveConfig();

        SonicConfigurationService reloaded = SonicConfigurationService.createStandalone();
        assertFalse(reloaded.getBoolean(SonicConfiguration.AUDIO_ENABLED));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestConfigServiceYamlRoundTrip" test`
Expected: FAIL — service still writes `config.json` (or `saveWritesGroupedYaml` fails because no `config.yaml`).

- [ ] **Step 3: Add a YAML resolve helper + change `resolveConfigFile` and `findConfigNextToExecutable`**

In `SonicConfigurationService.java`, change the working-directory/executable filename from `config.json` to `config.yaml`:

In `findConfigNextToExecutable()` (line ~458) change:
```java
                        return new File(execDir, "config.json");
```
to:
```java
                        return new File(execDir, "config.yaml");
```

In `resolveConfigFile()` (lines ~482-488) change both `resolveRelativeFile("config.json")` occurrences to `resolveRelativeFile("config.yaml")`.

- [ ] **Step 4: Replace `saveConfig()` to use the writer**

Replace the body of `saveConfig()` (lines ~279-287) with:
```java
	public void saveConfig() {
		File target = resolveConfigFile();
		try {
			String yaml = new ConfigYamlWriter().write(config);
			java.nio.file.Files.writeString(target.toPath(), yaml);
		} catch (IOException e) {
			LOGGER.log(Level.WARNING, "Failed to save config.yaml", e);
		}
	}
```

- [ ] **Step 5: Add a YAML loader and switch the constructor's working-dir/classpath reads**

Add this private helper method to the class:
```java
	private Map<String, Object> readYamlFlat(File file) throws IOException {
		com.fasterxml.jackson.databind.ObjectMapper yaml =
				new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
		TypeReference<Map<String, Object>> type = new TypeReference<>() {};
		Map<String, Object> nested = yaml.readValue(file, type);
		ConfigFlattener.Result result = ConfigFlattener.flatten(nested);
		for (String unknown : result.unknownKeys()) {
			LOGGER.warning("Unknown config key ignored: " + unknown);
		}
		return result.flat();
	}
```

In the constructor's JAR-mode branch (lines ~45-54), change it to read YAML:
```java
			// JAR mode: look for config.yaml in the working directory
			File file = resolveRelativeFile("config.yaml");
			if (file.exists()) {
				try {
					config = readYamlFlat(file);
					loadedFromExistingFile = true;
				} catch (IOException e) {
					LOGGER.log(Level.WARNING, "Failed to load config.yaml from working directory", e);
				}
			}
```

In the native-image branch (lines ~35-43), change the `mapper.readValue(execConfig, type)` call to `config = readYamlFlat(execConfig);` (the resolved `execConfig` is now `config.yaml`).

In the classpath-fallback block (lines ~57-69), change the resource name and parser:
```java
		if (config == null) {
			try (InputStream is = getClass().getResourceAsStream("/config.yaml")) {
				if (is != null) {
					com.fasterxml.jackson.databind.ObjectMapper yaml =
							new com.fasterxml.jackson.dataformat.yaml.YAMLMapper();
					Map<String, Object> nested = yaml.readValue(is, type);
					config = ConfigFlattener.flatten(nested).flat();
				} else {
					LOGGER.log(Level.WARNING, "Could not find config.yaml, using defaults.");
					config = new HashMap<>();
				}
			} catch (IOException e) {
				LOGGER.log(Level.WARNING, "Failed to load config.yaml from classpath", e);
				config = new HashMap<>();
			}
		}
```

> Legacy `config.json` migration is added in Task 7; for now, a missing `config.yaml` falls through to the classpath default + `applyDefaults()`, which is correct.

- [ ] **Step 6: Update the unused-import / Jackson JSON `mapper` references**

The constructor no longer uses the JSON `ObjectMapper mapper` for working-dir/classpath reads. Leave the `mapper`/`type` declarations in place (Task 7 reuses `type`); if the compiler warns about an unused `mapper`, remove only the now-dead `ObjectMapper mapper = new ObjectMapper();` line at the top of the constructor and keep the `TypeReference type` declaration.

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn "-Dtest=TestConfigServiceYamlRoundTrip" test`
Expected: PASS (2 tests).

- [ ] **Step 8: Run the existing config tests to catch regressions**

Run: `mvn "-Dtest=TestSonicConfigurationFileBootstrap+TestDisplayAspectResolution+TestConfigMigrationService" test`
Expected: `TestDisplayAspectResolution` and `TestConfigMigrationService` PASS. `TestSonicConfigurationFileBootstrap` will FAIL where it asserts on `config.json` — fix it in Step 9.

- [ ] **Step 9: Update `TestSonicConfigurationFileBootstrap`**

In `src/test/java/com/openggf/configuration/TestSonicConfigurationFileBootstrap.java`, replace each `tempDir.resolve("config.json")` (lines 27, 61) with `tempDir.resolve("config.yaml")`, and update the assertion message at line 73 from `"First startup should materialize config.json"` to `"First startup should materialize config.yaml"`. Keep the behavioral assertions (file exists, non-empty) unchanged.

- [ ] **Step 10: Run the full configuration test package**

Run: `mvn "-Dtest=com.openggf.configuration.*" test`
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfigurationService.java src/test/java/com/openggf/configuration/TestConfigServiceYamlRoundTrip.java src/test/java/com/openggf/configuration/TestSonicConfigurationFileBootstrap.java
git commit   # "feat(config): read/write config.yaml in SonicConfigurationService" + trailer block
```
Changelog trailer: `Changelog: updated` — and stage `CHANGELOG.md` here is NOT required yet because the aggregate entry lands in Task 9; use `Changelog: n/a: aggregated CHANGELOG entry added in Task 9 of this plan`.

---

## Task 7: Migrate legacy `config.json` to `config.yaml`

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Test: `src/test/java/com/openggf/configuration/TestLegacyConfigMigration.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

class TestLegacyConfigMigration {

    private final File json = new File("config.json");
    private final File yaml = new File("config.yaml");
    private final File bak = new File("config.json.bak");

    @AfterEach
    void cleanup() {
        json.delete();
        yaml.delete();
        bak.delete();
    }

    @Test
    void legacyFlatJsonMigratesToYamlAndBacksUp() throws Exception {
        Files.writeString(json.toPath(), "{ \"AUDIO_ENABLED\": false, \"FPS\": 50 }");

        SonicConfigurationService svc = SonicConfigurationService.createStandalone();

        assertFalse(svc.getBoolean(SonicConfiguration.AUDIO_ENABLED), "migrated value preserved");
        assertEquals(50, svc.getInt(SonicConfiguration.FPS));
        assertTrue(yaml.exists(), "config.yaml written");
        assertTrue(bak.exists(), "old config.json renamed to .bak");
        assertFalse(json.exists(), "original config.json removed");
        String text = Files.readString(yaml.toPath());
        assertTrue(text.contains("audio:"), text);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestLegacyConfigMigration" test`
Expected: FAIL — no migration; `config.yaml`/`.bak` not produced.

- [ ] **Step 3: Add legacy migration to the constructor**

In `SonicConfigurationService.java`, immediately after the JAR-mode `config.yaml` read block (added in Task 6) and before the `if (config == null)` classpath fallback, insert:

```java
			// Migrate a legacy flat config.json to config.yaml on first run.
			if (config == null) {
				File legacy = resolveRelativeFile("config.json");
				if (legacy.exists()) {
					try {
						config = mapper.readValue(legacy, type); // flat enum-name keys, as before
						loadedFromExistingFile = true;
						migratedFromLegacyJson = true;
					} catch (IOException e) {
						LOGGER.log(Level.WARNING, "Failed to read legacy config.json for migration", e);
					}
				}
			}
```

Add the field near the other instance fields (after `loadedFromExistingFile`):
```java
	private boolean migratedFromLegacyJson;
```

> Keep the `ObjectMapper mapper = new ObjectMapper();` line in the constructor (this branch needs the JSON mapper). If you removed it in Task 6 Step 6, re-add it.

- [ ] **Step 4: Trigger the rename + save after defaults are applied**

Find the save condition near the end of the constructor (lines ~87-89):
```java
		if (configChanged || (loadedFromExistingFile && defaultsInserted)) {
			saveConfig();
		}
```
Replace it with:
```java
		if (configChanged || migratedFromLegacyJson || (loadedFromExistingFile && defaultsInserted)) {
			saveConfig();
		}
		if (migratedFromLegacyJson) {
			File legacy = resolveRelativeFile("config.json");
			File backup = resolveRelativeFile("config.json.bak");
			if (legacy.exists() && legacy.renameTo(backup)) {
				LOGGER.info("Migrated legacy config.json to config.yaml (backup at config.json.bak)");
			}
		}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn "-Dtest=TestLegacyConfigMigration" test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfigurationService.java src/test/java/com/openggf/configuration/TestLegacyConfigMigration.java
git commit   # "feat(config): migrate legacy config.json to config.yaml on load" + trailer block
```
Changelog trailer: `Changelog: n/a: aggregated CHANGELOG entry added in Task 9 of this plan`.

---

## Task 7b: Validate enumerated values on load

Spec §6 requires warning on illegal `ENUM` values and falling back to the registered default (unknown-key warnings are already handled by `ConfigFlattener`/Task 6).

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Test: `src/test/java/com/openggf/configuration/TestConfigEnumValidation.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestConfigEnumValidation {

    private final File yaml = new File("config.yaml");

    @AfterEach
    void cleanup() {
        yaml.delete();
    }

    @Test
    void illegalEnumValueFallsBackToDefault() throws Exception {
        Files.writeString(yaml.toPath(), "audio:\n  region: KLINGON\n");
        SonicConfigurationService svc = SonicConfigurationService.createStandalone();
        // REGION default is NTSC (see applyDefaults); the bogus value is rejected.
        assertEquals("NTSC", svc.getString(SonicConfiguration.REGION));
    }

    @Test
    void legalEnumValueIsKept() throws Exception {
        Files.writeString(yaml.toPath(), "audio:\n  region: PAL\n");
        SonicConfigurationService svc = SonicConfigurationService.createStandalone();
        assertEquals("PAL", svc.getString(SonicConfiguration.REGION));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestConfigEnumValidation" test`
Expected: FAIL — `illegalEnumValueFallsBackToDefault` returns `KLINGON`.

- [ ] **Step 3: Add the validation method and call it**

Add this method to `SonicConfigurationService.java`:
```java
	/** Warn on ENUM-typed values outside their allowed set and reset them to the registered default. */
	private void validateEnumeratedValues() {
		for (SonicConfiguration key : ConfigCatalog.emitOrder()) {
			ConfigKeyMeta meta = ConfigCatalog.meta(key);
			if (meta.type() != ConfigType.ENUM) {
				continue;
			}
			Object value = config.get(key.name());
			if (value == null) {
				continue;
			}
			if (!meta.allowedValues().contains(value.toString())) {
				Object fallback = defaults.get(key.name());
				LOGGER.warning("Invalid value '" + value + "' for " + meta.path()
						+ "; allowed " + meta.allowedValues() + ". Defaulting to '" + fallback + "'.");
				if (fallback != null) {
					config.put(key.name(), fallback);
				}
			}
		}
	}
```

Call it in the constructor immediately after `applyDefaults()` returns (after the `boolean defaultsInserted = applyDefaults();` line, before the save condition):
```java
		validateEnumeratedValues();
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=TestConfigEnumValidation" test`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfigurationService.java src/test/java/com/openggf/configuration/TestConfigEnumValidation.java
git commit   # "feat(config): validate enumerated values against allowed sets" + trailer block
```
Changelog trailer: `Changelog: n/a: aggregated CHANGELOG entry added in Task 9 of this plan`.

---

## Task 8: Bundled default resource + native-image registration

Replace the bundled `src/main/resources/config.json` with a generated `config.yaml`, register it for native image, and update the pom copy step.

**Files:**
- Create: `src/main/resources/config.yaml` (generated)
- Delete: `src/main/resources/config.json`
- Modify: `src/main/resources/META-INF/native-image/com.openggf/OpenGGF/resource-config.json`
- Modify: `pom.xml:183-184`
- Test: `src/test/java/com/openggf/configuration/TestBundledConfigResource.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestBundledConfigResource {

    @Test
    void bundledYamlExistsAndFlattensCleanly() throws Exception {
        try (InputStream is = getClass().getResourceAsStream("/config.yaml")) {
            assertNotNull(is, "bundled /config.yaml must exist");
            ObjectMapper mapper = new YAMLMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> nested = mapper.readValue(is, Map.class);
            ConfigFlattener.Result r = ConfigFlattener.flatten(nested);
            assertTrue(r.unknownKeys().isEmpty(), "bundled config has unknown keys: " + r.unknownKeys());
            assertTrue(r.flat().containsKey(SonicConfiguration.DEFAULT_ROM.name()));
        }
    }

    @Test
    void legacyJsonResourceIsGone() {
        assertNull(getClass().getResourceAsStream("/config.json"),
                "bundled config.json should be replaced by config.yaml");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestBundledConfigResource" test`
Expected: FAIL — `/config.yaml` resource missing; `/config.json` still present.

- [ ] **Step 3: Generate the bundled `config.yaml` from defaults**

Generate the file the writer produces for a fresh default config by running the existing round-trip test (which calls `saveConfig()`), then copy its output into resources. From the repo root, with no `config.yaml`/`config.json` in the working dir:

```bash
rm -f config.yaml config.json
mvn "-Dtest=TestConfigServiceYamlRoundTrip#saveWritesGroupedYaml" test
cp config.yaml src/main/resources/config.yaml
rm -f config.yaml
```

Verify `src/main/resources/config.yaml` contains the `display:` section and the `debug:` fence. Then delete the old bundled JSON:

```bash
git rm src/main/resources/config.json
```

- [ ] **Step 4: Register the YAML resource for native image**

In `src/main/resources/META-INF/native-image/com.openggf/OpenGGF/resource-config.json`, replace:
```json
      { "pattern": "config\\.json" },
```
with:
```json
      { "pattern": "config\\.yaml" },
      { "pattern": "config\\.json" },
```
(Keep `config\\.json` too so a legacy file copied next to a native binary can still be read for migration.)

- [ ] **Step 5: Update the pom native-image copy step**

In `pom.xml`, change the copy at lines 183-184 from:
```xml
                                        <copy file="${basedir}/src/main/resources/config.json"
                                              todir="${project.build.directory}"/>
```
to:
```xml
                                        <copy file="${basedir}/src/main/resources/config.yaml"
                                              todir="${project.build.directory}"/>
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn "-Dtest=TestBundledConfigResource" test`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/config.yaml pom.xml "src/main/resources/META-INF/native-image/com.openggf/OpenGGF/resource-config.json" src/test/java/com/openggf/configuration/TestBundledConfigResource.java
# (the config.json deletion was already staged by `git rm` in Step 3)
git commit   # "feat(config): ship bundled config.yaml + native-image resource" + trailer block
```
Changelog trailer: `Changelog: n/a: aggregated CHANGELOG entry added in Task 9 of this plan`.

---

## Task 9: Peripheral references, docs, and changelog

**Files:**
- Modify: `src/main/java/com/openggf/testmode/TestModeTracePicker.java:82`
- Modify: `src/packaging/assemble-macos-app.sh:70-77`
- Modify: `CONFIGURATION.md`
- Modify: `CHANGELOG.md`
- Test: existing `src/test/java/com/openggf/tests/TestSonicConfigurationService.java`, `src/test/java/com/openggf/widescreen/TestWidescreenNativeRegression.java`

- [ ] **Step 1: Update the on-screen trace-picker string**

In `TestModeTracePicker.java:82`, change:
```java
                font.drawText("Check TRACE_CATALOG_DIR in config.json", 8, 32, SCALE,
```
to:
```java
                font.drawText("Check debug.testMode.catalogDir in config.yaml", 8, 32, SCALE,
```

- [ ] **Step 2: Update the macOS packaging script**

In `src/packaging/assemble-macos-app.sh`, lines 70-72, change the three `config.json` references to `config.yaml`:
```bash
# Copy config.yaml next to the .app bundle so it can be edited without rebuilding
CONFIG_SRC="$(cd "$(dirname "${BINARY}")" && pwd)/config.yaml"
CONFIG_DST="$(cd "${OUTPUT_DIR}" && pwd)/config.yaml"
```
and update the two echo strings (lines 75, 77) from `config.json` to `config.yaml`.

- [ ] **Step 3: Update the two remaining tests that hard-code `config.json`**

In `src/test/java/com/openggf/tests/TestSonicConfigurationService.java`, change `new File("config.json")` (line 37) to `new File("config.yaml")` and the two assertion messages (lines 44-45) from `config.json` to `config.yaml`. Keep behavior assertions intact.

In `src/test/java/com/openggf/widescreen/TestWidescreenNativeRegression.java`, update the comment at line 20 referencing `config.json` to `config.yaml` (comment only; verify no functional `config.json` path is used — if one is, point it at `config.yaml`).

- [ ] **Step 4: Run the updated tests**

Run: `mvn "-Dtest=TestSonicConfigurationService+TestWidescreenNativeRegression" test`
Expected: PASS.

- [ ] **Step 5: Rewrite `CONFIGURATION.md`**

Update `CONFIGURATION.md` to describe the YAML layout. Concretely:
- Intro (lines 3-4): state settings live in `config.yaml` (working dir, next to the JAR); bundled `src/main/resources/config.yaml` is the default; on first run a legacy `config.json` is migrated to `config.yaml` and backed up to `config.json.bak`.
- Replace the `## Example config.json` section (line ~214) with `## Example config.yaml` and paste the contents of `src/main/resources/config.yaml`.
- Add a short "Sections" subsection listing: `display`, `input`, `audio`, `characters`, `roms`, `startup`, `rewind`, `crossGame`, `discord`, then the fenced `debug:` block (flags, keys, startup, playback, traceRewind, testMode, crossGame, window). Note that `debug.window` (width/height/scale) is deprecated in favour of `display.aspect` + `display.windowAutosize`, and that `SCREEN_WIDTH_PIXELS`/`SCREEN_HEIGHT_PIXELS` are derived and not stored.
- Update the `DISPLAY_COLOR_PROFILE_TOGGLE_KEY` row note (line ~25) to say the profile is saved to `config.yaml`.

- [ ] **Step 6: Add the CHANGELOG entry**

In `CHANGELOG.md`, under the current unreleased/working section, add:
```markdown
- Configuration moved from a flat `config.json` to a grouped, commented, deterministically-ordered `config.yaml`. All developer/debug settings are compartmentalised into a single `debug:` block. Existing `config.json` files are migrated automatically on first run (backed up to `config.json.bak`). Window size/scale are deprecated under `debug.window`; widescreen is driven by `display.aspect` profiles.
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/testmode/TestModeTracePicker.java src/packaging/assemble-macos-app.sh CONFIGURATION.md CHANGELOG.md src/test/java/com/openggf/tests/TestSonicConfigurationService.java src/test/java/com/openggf/widescreen/TestWidescreenNativeRegression.java
git commit   # "docs(config): YAML migration — CONFIGURATION.md, CHANGELOG, peripheral refs" + trailer block
```
For this commit set: `Changelog: updated` (CHANGELOG.md is staged) and `Configuration-Docs: updated` (CONFIGURATION.md is staged). Other trailers `n/a`.

---

## Task 10: Full-suite verification

**Files:** none (verification only)

- [ ] **Step 1: Run the whole configuration + dependent suites**

Run: `mvn "-Dtest=com.openggf.configuration.*+TestSonicConfigurationService+TestWidescreenNativeRegression+TestModeTracePicker" test`
Expected: PASS.

- [ ] **Step 2: Full build + test**

Run: `mvn test`
Expected: BUILD SUCCESS, no failures. Investigate any failure that names `config`, `Configuration`, or a key formerly read from `config.json`.

- [ ] **Step 3: Confirm no stale `config.json` references remain in source/build**

Run: `git grep -n "config\.json" -- src pom.xml CONFIGURATION.md src/packaging`
Expected: only intentional legacy-migration references remain (the `config.json` read + `.bak` rename in `SonicConfigurationService.java`, the kept `config\.json` native-image pattern, and any migration note in `CONFIGURATION.md`). No bundled-resource, copy-step, or test references to `config.json` as the live format.

- [ ] **Step 4: (If a native image is built in CI) smoke-test resource loading**

Native image uses `resource-config.json`; confirm the built binary finds `config.yaml` next to it (the copy step in Task 8 places it in `target/`). If a native build is not part of the local workflow, note this for CI and rely on the registered `config\.yaml` resource pattern.

- [ ] **Step 5: Final commit (only if Steps fixed anything)**

```bash
git add -A
git commit   # "test(config): full-suite verification fixes" + trailer block (only if changes were needed)
```

---

## Notes for the implementer

- **Singleton resets:** tests that construct `SonicConfigurationService` directly use `createStandalone()` (no singleton). If a test needs the singleton fresh, the package-private `resetStaticInstance()` exists. Working-dir `config.yaml`/`config.json`/`.bak` files written by tests must be deleted in `@AfterEach` (see Task 6/7 tests) to avoid cross-test pollution — this repo runs concurrent sessions, so never leave stray config files in the repo root.
- **DRY:** the YAML mapper construction appears in `readYamlFlat` and the classpath block; if you prefer, extract a `private static Map<String,Object> parseYaml(...)` helper. Not required.
- **Do not** change `SonicConfiguration` enum ordering or add constructor args — the catalog owns ordering/metadata by design.
