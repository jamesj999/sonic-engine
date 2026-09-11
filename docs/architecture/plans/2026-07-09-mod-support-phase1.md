# Mod Support Phase 1 (Loader + Music Packs) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship drop-in music-pack mods: jars in a `mods/` folder are scanned at startup, managed from an in-game screen, and their wav/ogg tracks replace base-game music through a streamed audio layer beside the SMPS driver.

**Architecture:** A new `com.openggf.mods` package (manifest parsing, jar scanning, enable-state persistence, catalog) feeds a new `com.openggf.audio.streamed` package (decoders, loop-aware `AudioStream` track, music player sidecar, per-game music resolver). The sidecar hooks into `AbstractSmpsAudioBackend` so the existing jingle push/pop, speed-shoes, fade, and pause semantics drive streams for free; streamed playback stays out of the `AudioCommand` rewind timeline. A `ModManagerScreen` (modeled on `TestModeTracePicker`) rides the master title as a sub-screen. No mod code executes in Phase 1 — data-only mods.

**Tech Stack:** Java 21, Jackson (`YAMLMapper`/`ObjectMapper` — already dependencies), LWJGL `stb_vorbis` (already a dependency), existing `WavDecoder`, JUnit 5.

**Spec:** `docs/architecture/designs/2026-07-09-mod-support-design.md` (§1, §2, §4, §7, §8 Phase 1). Out of scope here: mp3 (spec open question — wav/ogg only), mod code loading, art overrides, GamePatch work (Phase 0/2 plans), and **SFX overrides** — this plan delivers the music half of `audioOverrides`; streamed one-shot SFX is a deliberate follow-on plan once the music path is proven in-engine (the manifest already parses SFX-capable int keys, so no format change is needed later).

**Disclosed narrowings vs the spec (confirm with spec owner at review):**
- `engineApiVersion` is a single required major version int in Phase 1 (spec says "semver range"); ranges arrive with the Phase 2 code API.
- Dependency `version` is a **minimum version** (dot-segment numeric compare), not a full range syntax.
- The spec's "cascades a disable prompt" is a two-press confirm in the mod manager (first press shows what would cascade, second press does it).
- §7 "pattern-ID budget per mod" is deferred to the Phase 2 plan — no art ships here, no `PatternAtlas` range is claimed.
- Loop metadata is `loopStartSample` only (loop region always extends to end-of-file); a `loopEndSample` field can be added to the audio manifest later without breaking existing packs. Creators trim trailing silence in the file instead.

## Global Constraints

- **JUnit 5 / Jupiter only** — no `org.junit.*` (JUnit 4) imports.
- **Commit trailers:** every commit message must end with the 7 trailers (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`), each `updated` or `n/a`. A `feat`/`fix` commit touching `src/main/` must set `Changelog: updated` and stage `CHANGELOG.md` (or justify `n/a: <reason>`). The final task stages the CHANGELOG; intermediate `feat` commits that touch `src/main/` use `Changelog: n/a: covered by final phase-1 changelog entry in this branch`.
- **Never `git add -A`** — stage exact paths only (shared repo, concurrent sessions).
- **No new singletons.** New classes take their roots (paths, resolvers) as constructor/method parameters (`SaveManager` / `SonicConfigurationService.createStandalone` style). Do not touch `EngineServices`/`TestEnvironment`.
- **No new Maven dependencies.**
- **Menu screens must consume gamepad-aware input**: `input.logical().menuUp()/menuDown()/menuAccept()/menuBack()` alongside raw keys.
- **New-file line endings LF; do not reformat existing files.** In PowerShell, quote `-Dtest=...`.
- **Branch:** create `feature/ai-mod-support-phase1` off `develop` before Task 1.
- **Test runs:** `mvn "-Dtest=<Class>" test` runs the full-suite guard classes too under MSE; judge pass/fail by the named class's results.
- **Verify against recon line numbers before editing** — files like `AbstractSmpsAudioBackend.java` and `MasterTitleScreen.java` are actively developed; anchors below are from 2026-07-09 recon. Read the target region first; if it moved, follow the named methods, not the line numbers.

---

### Task 1: Mod manifest model + parser

**Files:**
- Create: `src/main/java/com/openggf/mods/ModApiVersion.java`
- Create: `src/main/java/com/openggf/mods/ModType.java`
- Create: `src/main/java/com/openggf/mods/ModDependency.java`
- Create: `src/main/java/com/openggf/mods/ModManifest.java`
- Create: `src/main/java/com/openggf/mods/ModManifestException.java`
- Create: `src/main/java/com/openggf/mods/ModManifestParser.java`
- Test: `src/test/java/com/openggf/mods/TestModManifestParser.java`

**Interfaces:**
- Consumes: Jackson `YAMLMapper` (existing dependency).
- Produces: `ModManifest ModManifestParser.parse(InputStream in) throws ModManifestException`; `record ModManifest(String id, String name, String version, List<String> authors, String description, String engineApiVersion, ModType type, String baseGame, List<ModDependency> dependencies, Map<Integer,String> audioOverrides)`; `ModApiVersion.CURRENT`, `boolean ModApiVersion.isSatisfiedBy(String requiredRange)`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.mods;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TestModManifestParser {

    private static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final String VALID = """
            id: my-music-pack
            name: My Music Pack
            version: 1.0.0
            authors: [Farrell]
            description: Replaces EHZ music.
            engineApiVersion: "1"
            type: patch
            baseGame: s2
            audioOverrides:
              "0x8C": ehz-remix
            """;

    @Test
    void parsesValidPatchManifest() throws Exception {
        ModManifest m = new ModManifestParser().parse(yaml(VALID));
        assertEquals("my-music-pack", m.id());
        assertEquals(ModType.PATCH, m.type());
        assertEquals("s2", m.baseGame());
        assertEquals("ehz-remix", m.audioOverrides().get(0x8C));
        assertTrue(m.dependencies().isEmpty());
    }

    @Test
    void rejectsBadId() {
        ModManifestException e = assertThrows(ModManifestException.class,
                () -> new ModManifestParser().parse(yaml(VALID.replace("my-music-pack", "My Pack!"))));
        assertTrue(e.getMessage().contains("id"));
    }

    @Test
    void rejectsPatchWithoutBaseGame() {
        assertThrows(ModManifestException.class,
                () -> new ModManifestParser().parse(yaml(VALID.replace("baseGame: s2\n", ""))));
    }

    @Test
    void rejectsMalformedYamlAndBadOverrideKey() {
        assertThrows(ModManifestException.class, () -> new ModManifestParser().parse(yaml("{{{")));
        assertThrows(ModManifestException.class,
                () -> new ModManifestParser().parse(yaml(VALID.replace("\"0x8C\"", "\"notAnInt\""))));
    }

    @Test
    void ignoresUnknownKeysForForwardCompat() throws Exception {
        ModManifest m = new ModManifestParser().parse(yaml(VALID + "artOverrides:\n  base/key: some/path\n"));
        assertEquals("my-music-pack", m.id());
    }

    @Test
    void apiVersionCompatibility() {
        assertTrue(ModApiVersion.isSatisfiedBy("1"));
        assertFalse(ModApiVersion.isSatisfiedBy("2"));
        assertFalse(ModApiVersion.isSatisfiedBy("banana"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.mods.TestModManifestParser" test`
Expected: COMPILE FAILURE (classes don't exist).

- [ ] **Step 3: Write minimal implementation**

`ModApiVersion.java`:
```java
package com.openggf.mods;

/**
 * Engine-published mod API version. Phase 1 covers only the manifest and
 * audio data formats; the code API (Phase 2) will bump this per semver.
 * A manifest's engineApiVersion is a single required major version string.
 */
public final class ModApiVersion {
    public static final int CURRENT_MAJOR = 1;

    private ModApiVersion() {
    }

    public static boolean isSatisfiedBy(String requiredMajor) {
        if (requiredMajor == null) {
            return false;
        }
        try {
            return Integer.parseInt(requiredMajor.trim()) == CURRENT_MAJOR;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
```

`ModType.java`:
```java
package com.openggf.mods;

public enum ModType {
    PATCH, STANDALONE
}
```

`ModDependency.java`:
```java
package com.openggf.mods;

public record ModDependency(String id, String version) {
}
```

`ModManifestException.java`:
```java
package com.openggf.mods;

public class ModManifestException extends Exception {
    public ModManifestException(String message) {
        super(message);
    }

    public ModManifestException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`ModManifest.java`:
```java
package com.openggf.mods;

import java.util.List;
import java.util.Map;

/**
 * Parsed META-INF/openggf-mod.yaml. audioOverrides maps a base-game music id
 * (the int consumed by AudioManager.playMusic) to a track id declared in the
 * mod's audio-manifest.yaml. Unknown manifest keys are ignored for forward
 * compatibility (e.g. artOverrides arrives in Phase 2).
 */
public record ModManifest(
        String id,
        String name,
        String version,
        List<String> authors,
        String description,
        String engineApiVersion,
        ModType type,
        String baseGame,
        List<ModDependency> dependencies,
        Map<Integer, String> audioOverrides) {
}
```

`ModManifestParser.java`:
```java
package com.openggf.mods;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ModManifestParser {
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]*");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    public ModManifest parse(InputStream in) throws ModManifestException {
        Map<String, Object> root;
        try {
            root = new YAMLMapper().readValue(in, MAP_TYPE);
        } catch (Exception e) {
            throw new ModManifestException("manifest is not valid YAML: " + e.getMessage(), e);
        }
        if (root == null) {
            throw new ModManifestException("manifest is empty");
        }
        String id = requiredString(root, "id");
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new ModManifestException("id must match [a-z0-9-]+: '" + id + "'");
        }
        String name = requiredString(root, "name");
        String version = requiredString(root, "version");
        String apiVersion = requiredString(root, "engineApiVersion");
        ModType type = parseType(requiredString(root, "type"));
        String baseGame = optionalString(root, "baseGame");
        if (type == ModType.PATCH && baseGame == null) {
            throw new ModManifestException("type 'patch' requires baseGame (s1|s2|s3k)");
        }
        return new ModManifest(id, name, version, parseAuthors(root),
                optionalString(root, "description"), apiVersion, type, baseGame,
                parseDependencies(root), parseAudioOverrides(root));
    }

    private static ModType parseType(String raw) throws ModManifestException {
        try {
            return ModType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ModManifestException("type must be patch or standalone: '" + raw + "'");
        }
    }

    private static List<String> parseAuthors(Map<String, Object> root) {
        Object raw = root.get("authors");
        List<String> authors = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(a -> authors.add(String.valueOf(a)));
        }
        return List.copyOf(authors);
    }

    private static List<ModDependency> parseDependencies(Map<String, Object> root)
            throws ModManifestException {
        Object raw = root.get("dependencies");
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw new ModManifestException("dependencies must be a list");
        }
        List<ModDependency> deps = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map) {
                Object depId = map.get("id");
                if (depId == null) {
                    throw new ModManifestException("dependency entry missing id");
                }
                Object depVersion = map.get("version");
                deps.add(new ModDependency(String.valueOf(depId),
                        depVersion == null ? null : String.valueOf(depVersion)));
            } else {
                deps.add(new ModDependency(String.valueOf(entry), null));
            }
        }
        return List.copyOf(deps);
    }

    private static Map<Integer, String> parseAudioOverrides(Map<String, Object> root)
            throws ModManifestException {
        Object raw = root.get("audioOverrides");
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ModManifestException("audioOverrides must be a map");
        }
        Map<Integer, String> overrides = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            try {
                overrides.put(Integer.decode(key.trim()), String.valueOf(e.getValue()));
            } catch (NumberFormatException nfe) {
                throw new ModManifestException(
                        "audioOverrides key must be an int music id (e.g. 0x8C): '" + key + "'");
            }
        }
        return Map.copyOf(overrides);
    }

    private static String requiredString(Map<String, Object> root, String key)
            throws ModManifestException {
        String value = optionalString(root, key);
        if (value == null || value.isBlank()) {
            throw new ModManifestException("manifest missing required field '" + key + "'");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> root, String key) {
        Object value = root.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.mods.TestModManifestParser" test`
Expected: all 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/mods/ModApiVersion.java src/main/java/com/openggf/mods/ModType.java src/main/java/com/openggf/mods/ModDependency.java src/main/java/com/openggf/mods/ModManifest.java src/main/java/com/openggf/mods/ModManifestException.java src/main/java/com/openggf/mods/ModManifestParser.java src/test/java/com/openggf/mods/TestModManifestParser.java
git commit -m "feat: mod manifest model and parser

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 2: Jar scanner + descriptors

**Files:**
- Create: `src/main/java/com/openggf/mods/ModDescriptor.java`
- Create: `src/main/java/com/openggf/mods/ModRepositoryScanner.java`
- Test: `src/test/java/com/openggf/mods/TestModRepositoryScanner.java`

**Interfaces:**
- Consumes: `ModManifestParser.parse(InputStream)` (Task 1).
- Produces: `record ModDescriptor(Path jarPath, ModManifest manifest, String error, boolean containsCode)` with `boolean loadable()` (manifest != null && error == null) and static factories `ModDescriptor.ok(Path, ModManifest, boolean)` / `ModDescriptor.failed(Path, String)`; `static List<ModDescriptor> ModRepositoryScanner.scan(Path modsDir)` (sorted by jar filename; never throws; missing dir → empty list). Static test helper `TestModRepositoryScanner.writeJar(Path jar, Map<String,byte[]> entries)` is reused by Task 9's test (same package).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.mods;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TestModRepositoryScanner {

    static final String MANIFEST_PATH = "META-INF/openggf-mod.yaml";

    static final String VALID_MANIFEST = """
            id: pack-a
            name: Pack A
            version: 1.0.0
            engineApiVersion: "1"
            type: patch
            baseGame: s2
            """;

    static void writeJar(Path jar, Map<String, byte[]> entries) throws Exception {
        try (OutputStream fileOut = Files.newOutputStream(jar);
             JarOutputStream out = new JarOutputStream(fileOut)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
    }

    @Test
    void scansValidJarAndDetectsNoCode(@TempDir Path tmp) throws Exception {
        writeJar(tmp.resolve("pack-a.jar"),
                Map.of(MANIFEST_PATH, VALID_MANIFEST.getBytes(StandardCharsets.UTF_8)));
        List<ModDescriptor> mods = ModRepositoryScanner.scan(tmp);
        assertEquals(1, mods.size());
        assertTrue(mods.get(0).loadable());
        assertEquals("pack-a", mods.get(0).manifest().id());
        assertFalse(mods.get(0).containsCode());
    }

    @Test
    void flagsCodeJars(@TempDir Path tmp) throws Exception {
        writeJar(tmp.resolve("code.jar"), Map.of(
                MANIFEST_PATH, VALID_MANIFEST.getBytes(StandardCharsets.UTF_8),
                "com/example/Foo.class", new byte[]{(byte) 0xCA, (byte) 0xFE}));
        assertTrue(ModRepositoryScanner.scan(tmp).get(0).containsCode());
    }

    @Test
    void malformedJarAndMissingManifestBecomeErrorDescriptors(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("broken.jar"), "not a jar");
        writeJar(tmp.resolve("nomanifest.jar"), Map.of("readme.txt", new byte[0]));
        List<ModDescriptor> mods = ModRepositoryScanner.scan(tmp);
        assertEquals(2, mods.size());
        assertTrue(mods.stream().noneMatch(ModDescriptor::loadable));
        assertTrue(mods.stream().allMatch(d -> d.error() != null));
    }

    @Test
    void duplicateIdsMarkLaterJarAsError(@TempDir Path tmp) throws Exception {
        byte[] manifest = VALID_MANIFEST.getBytes(StandardCharsets.UTF_8);
        writeJar(tmp.resolve("a-first.jar"), Map.of(MANIFEST_PATH, manifest));
        writeJar(tmp.resolve("b-second.jar"), Map.of(MANIFEST_PATH, manifest));
        List<ModDescriptor> mods = ModRepositoryScanner.scan(tmp);
        assertTrue(mods.get(0).loadable());
        assertFalse(mods.get(1).loadable());
        assertTrue(mods.get(1).error().contains("duplicate"));
    }

    @Test
    void missingDirectoryYieldsEmptyList(@TempDir Path tmp) {
        assertTrue(ModRepositoryScanner.scan(tmp.resolve("nope")).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.mods.TestModRepositoryScanner" test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write minimal implementation**

`ModDescriptor.java`:
```java
package com.openggf.mods;

import java.nio.file.Path;

/**
 * One scanned jar in mods/. error != null means the jar is listed in the mod
 * manager with an error badge and can never be enabled.
 */
public record ModDescriptor(Path jarPath, ModManifest manifest, String error, boolean containsCode) {

    public static ModDescriptor ok(Path jarPath, ModManifest manifest, boolean containsCode) {
        return new ModDescriptor(jarPath, manifest, null, containsCode);
    }

    public static ModDescriptor failed(Path jarPath, String error) {
        return new ModDescriptor(jarPath, null, error, false);
    }

    public boolean loadable() {
        return manifest != null && error == null;
    }

    /** Manifest id, or the jar filename for unparseable jars. */
    public String displayId() {
        return manifest != null ? manifest.id() : jarPath.getFileName().toString();
    }
}
```

`ModRepositoryScanner.java`:
```java
package com.openggf.mods;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Scans mods/ for *.jar, parsing each jar's META-INF/openggf-mod.yaml WITHOUT
 * loading any code. Never throws: unreadable jars become error descriptors so
 * the mod manager can surface them. Deterministic order: jar filename.
 */
public final class ModRepositoryScanner {
    public static final String MANIFEST_ENTRY = "META-INF/openggf-mod.yaml";

    private ModRepositoryScanner() {
    }

    public static List<ModDescriptor> scan(Path modsDir) {
        if (!Files.isDirectory(modsDir)) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        try (Stream<Path> children = Files.list(modsDir)) {
            children.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(jars::add);
        } catch (IOException e) {
            return List.of();
        }
        List<ModDescriptor> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (Path jar : jars) {
            ModDescriptor descriptor = scanOne(jar);
            if (descriptor.loadable() && !seenIds.add(descriptor.manifest().id())) {
                descriptor = ModDescriptor.failed(jar,
                        "duplicate mod id '" + descriptor.manifest().id() + "'");
            }
            result.add(descriptor);
        }
        return List.copyOf(result);
    }

    private static ModDescriptor scanOne(Path jar) {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            JarEntry manifestEntry = jarFile.getJarEntry(MANIFEST_ENTRY);
            if (manifestEntry == null) {
                return ModDescriptor.failed(jar, "missing " + MANIFEST_ENTRY);
            }
            ModManifest manifest;
            try (InputStream in = jarFile.getInputStream(manifestEntry)) {
                manifest = new ModManifestParser().parse(in);
            }
            boolean containsCode = jarFile.stream()
                    .anyMatch(e -> e.getName().endsWith(".class"));
            return ModDescriptor.ok(jar, manifest, containsCode);
        } catch (ModManifestException e) {
            return ModDescriptor.failed(jar, e.getMessage());
        } catch (IOException e) {
            return ModDescriptor.failed(jar, "unreadable jar: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.mods.TestModRepositoryScanner" test`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/mods/ModDescriptor.java src/main/java/com/openggf/mods/ModRepositoryScanner.java src/test/java/com/openggf/mods/TestModRepositoryScanner.java
git commit -m "feat: mods/ jar scanner producing descriptors without code loading

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 3: Enable-state persistence (`mods/modstate.json`)

**Files:**
- Create: `src/main/java/com/openggf/mods/ModState.java`
- Create: `src/main/java/com/openggf/mods/ModStateStore.java`
- Test: `src/test/java/com/openggf/mods/TestModStateStore.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`; atomic-write pattern from `com.openggf.game.save.SaveManager` (temp file + `Files.move(..., ATOMIC_MOVE)`, non-atomic fallback).
- Produces: `record ModState(List<Entry> entries)` with `record Entry(String id, boolean enabled)`, `ModState.EMPTY`, helpers `boolean isEnabled(String id)` and `int orderOf(String id)` (index, or `Integer.MAX_VALUE` if absent); `ModStateStore(Path modsDir)` with `ModState load()` (missing or corrupt file → `ModState.EMPTY`, corrupt file renamed aside to `modstate.json.corrupt-<n>`) and `void save(ModState state)`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.mods;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TestModStateStore {

    @Test
    void roundTripsEnableStateAndOrder(@TempDir Path tmp) {
        ModStateStore store = new ModStateStore(tmp);
        store.save(new ModState(List.of(
                new ModState.Entry("pack-b", true),
                new ModState.Entry("pack-a", false))));
        ModState reloaded = new ModStateStore(tmp).load();
        assertTrue(reloaded.isEnabled("pack-b"));
        assertFalse(reloaded.isEnabled("pack-a"));
        assertTrue(reloaded.orderOf("pack-b") < reloaded.orderOf("pack-a"));
    }

    @Test
    void missingFileLoadsEmpty(@TempDir Path tmp) {
        assertEquals(ModState.EMPTY, new ModStateStore(tmp).load());
    }

    @Test
    void corruptFileIsQuarantinedAndLoadsEmpty(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("modstate.json"), "{nope");
        assertEquals(ModState.EMPTY, new ModStateStore(tmp).load());
        try (Stream<Path> files = Files.list(tmp)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt")));
        }
    }

    @Test
    void unknownIdIsDisabledAndLast(@TempDir Path tmp) {
        ModState state = new ModStateStore(tmp).load();
        assertFalse(state.isEnabled("never-seen"));
        assertEquals(Integer.MAX_VALUE, state.orderOf("never-seen"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.mods.TestModStateStore" test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write minimal implementation**

`ModState.java`:
```java
package com.openggf.mods;

import java.util.List;

/** Persisted user intent: which mods are enabled, in what order. */
public record ModState(List<Entry> entries) {

    public record Entry(String id, boolean enabled) {
    }

    public static final ModState EMPTY = new ModState(List.of());

    public boolean isEnabled(String id) {
        return entries.stream().anyMatch(e -> e.id().equals(id) && e.enabled());
    }

    /** List index of id, or Integer.MAX_VALUE when absent (sorts last). */
    public int orderOf(String id) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(id)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }
}
```

`ModStateStore.java`:
```java
package com.openggf.mods;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mods/modstate.json — {"version":1,"mods":[{"id":..,"enabled":..}, ...]}.
 * List order is load order. Corrupt files are renamed aside (never deleted)
 * and state resets to EMPTY; a broken state file must not block startup.
 */
public final class ModStateStore {
    private static final String FILE_NAME = "modstate.json";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path modsDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public ModStateStore(Path modsDir) {
        this.modsDir = modsDir;
    }

    public ModState load() {
        Path file = modsDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return ModState.EMPTY;
        }
        try {
            Map<String, Object> root = mapper.readValue(file.toFile(), MAP_TYPE);
            Object mods = root.get("mods");
            List<ModState.Entry> entries = new ArrayList<>();
            if (mods instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m && m.get("id") != null) {
                        entries.add(new ModState.Entry(String.valueOf(m.get("id")),
                                Boolean.TRUE.equals(m.get("enabled"))));
                    }
                }
            }
            return new ModState(List.copyOf(entries));
        } catch (IOException e) {
            quarantine(file);
            return ModState.EMPTY;
        }
    }

    public void save(ModState state) {
        List<Map<String, Object>> mods = new ArrayList<>();
        for (ModState.Entry entry : state.entries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", entry.id());
            m.put("enabled", entry.enabled());
            mods.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("mods", mods);
        try {
            Files.createDirectories(modsDir);
            Path temp = modsDir.resolve(FILE_NAME + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), root);
            try {
                Files.move(temp, modsDir.resolve(FILE_NAME),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, modsDir.resolve(FILE_NAME), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Persistence failure loses user toggles for next boot but must not crash.
        }
    }

    private void quarantine(Path file) {
        for (int i = 0; i < 100; i++) {
            Path target = file.resolveSibling(FILE_NAME + ".corrupt-" + i);
            if (!Files.exists(target)) {
                try {
                    Files.move(file, target);
                } catch (IOException ignored) {
                    // leave the corrupt file in place; load() already returned EMPTY
                }
                return;
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.mods.TestModStateStore" test`
Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/mods/ModState.java src/main/java/com/openggf/mods/ModStateStore.java src/test/java/com/openggf/mods/TestModStateStore.java
git commit -m "feat: mod enable-state persistence with corrupt-file quarantine

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 4: Eligibility + catalog (dependencies, api version, ordering, toggles)

**Files:**
- Create: `src/main/java/com/openggf/mods/ModEligibility.java`
- Create: `src/main/java/com/openggf/mods/ModCatalog.java`
- Test: `src/test/java/com/openggf/mods/TestModCatalog.java`

**Interfaces:**
- Consumes: Tasks 1–3 (`ModDescriptor`, `ModState`, `ModStateStore`, `ModApiVersion`).
- Produces:
  - `ModEligibility.blockReason(ModDescriptor d, Map<String, ModDescriptor> byId)` → `String` or `null` (checks: descriptor error, apiVersion, containsCode ["mod code is not supported yet" — Phase 1 refuses code jars], missing deps, dependency **minimum-version** violations ("requires 'x' >= 2.0, found 1.0.0"), cycles **with the path in the reason** ("dependency cycle: a -> b -> a")). Includes `static int compareVersions(String a, String b)` (dot-segment numeric compare; missing segments = 0; non-numeric segments compare as strings).
  - `ModCatalog` (constructor `ModCatalog(List<ModDescriptor> descriptors, ModStateStore store)`; static `ModCatalog load(Path modsDir)` = scan + store):
    - `List<Row> rows()` — display order; `record Row(ModDescriptor descriptor, boolean enabled, String blockReason)`
    - `boolean toggle(String id)` — flips enable if not blocked; disabling a mod auto-disables enabled dependents (cascade); returns whether anything changed
    - `List<String> cascadePreview(String id)` — the transitively-dependent enabled ids that disabling `id` would also disable, WITHOUT changing state; the manager screen's two-press confirm (Task 11) uses it
    - `boolean move(String id, int delta)` — reorder within topological constraints (a mod never moves before a mod it depends on, nor after a mod that depends on it)
    - `List<ModDescriptor> orderedEnabled()` — enabled, unblocked, in display order; **empty when force-disabled**
    - `void setForceDisabled(boolean)` / `boolean isForceDisabled()` — trace/test-mode gate
    - `void persist()` — writes current rows to the store.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.mods;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestModCatalog {

    private static ModManifest manifest(String id, List<ModDependency> deps) {
        return new ModManifest(id, id, "1.0.0", List.of(), null, "1",
                ModType.PATCH, "s2", deps, Map.of());
    }

    private static ModDescriptor mod(String id, String... depIds) {
        List<ModDependency> deps = java.util.Arrays.stream(depIds)
                .map(d -> new ModDependency(d, null)).toList();
        return ModDescriptor.ok(Path.of(id + ".jar"), manifest(id, deps), false);
    }

    private static ModCatalog catalog(Path tmp, ModDescriptor... descriptors) {
        return new ModCatalog(List.of(descriptors), new ModStateStore(tmp));
    }

    @Test
    void toggleEnablesAndPersistsAcrossReload(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, mod("a"));
        assertTrue(c.toggle("a"));
        c.persist();
        ModCatalog reloaded = new ModCatalog(List.of(mod("a")), new ModStateStore(tmp));
        assertEquals(1, reloaded.orderedEnabled().size());
    }

    @Test
    void blockedMods_missingDep_badApi_code_cycle(@TempDir Path tmp) {
        ModDescriptor badApi = ModDescriptor.ok(Path.of("api.jar"),
                new ModManifest("api-mod", "x", "1", List.of(), null, "99",
                        ModType.PATCH, "s2", List.of(), Map.of()), false);
        ModDescriptor codeJar = ModDescriptor.ok(Path.of("code.jar"),
                manifest("code-mod", List.of()), true);
        ModCatalog c = catalog(tmp, mod("needs-x", "x"), badApi, codeJar,
                mod("cyc-a", "cyc-b"), mod("cyc-b", "cyc-a"));
        for (ModCatalog.Row row : c.rows()) {
            assertNotNull(row.blockReason(), row.descriptor().displayId());
            assertFalse(c.toggle(row.descriptor().displayId()));
        }
        assertTrue(c.orderedEnabled().isEmpty());
    }

    @Test
    void disablingDependencyCascades(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, mod("base"), mod("addon", "base"));
        assertTrue(c.toggle("base"));
        assertTrue(c.toggle("addon"));
        assertEquals(2, c.orderedEnabled().size());
        assertTrue(c.toggle("base")); // disable base
        assertTrue(c.orderedEnabled().isEmpty()); // addon cascaded off
    }

    @Test
    void dependentCannotBeEnabledBeforeDependency(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, mod("base"), mod("addon", "base"));
        assertFalse(c.toggle("addon"));
        assertTrue(c.toggle("base"));
        assertTrue(c.toggle("addon"));
    }

    @Test
    void moveRespectsDependencyTopology(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, mod("base"), mod("addon", "base"));
        assertFalse(c.move("addon", -1)); // cannot go before its dependency
        assertFalse(c.move("base", +1));  // cannot go after its dependent
    }

    @Test
    void moveReordersIndependentMods(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, mod("a"), mod("b"));
        assertEquals("a", c.rows().get(0).descriptor().displayId());
        assertTrue(c.move("b", -1));
        assertEquals("b", c.rows().get(0).descriptor().displayId());
    }

    @Test
    void forceDisabledEmptiesEnabledListButKeepsRows(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, mod("a"));
        c.toggle("a");
        c.setForceDisabled(true);
        assertTrue(c.orderedEnabled().isEmpty());
        assertEquals(1, c.rows().size());
        assertFalse(c.toggle("a")); // toggles refused while force-disabled
    }

    @Test
    void dependencyMinimumVersionIsEnforced(@TempDir Path tmp) {
        ModDescriptor oldBase = ModDescriptor.ok(Path.of("base.jar"),
                new ModManifest("base", "base", "1.0.0", List.of(), null, "1",
                        ModType.PATCH, "s2", List.of(), Map.of()), false);
        ModDescriptor needsNewer = ModDescriptor.ok(Path.of("addon.jar"),
                manifest("addon", List.of(new ModDependency("base", "2.0"))), false);
        ModCatalog c = catalog(tmp, oldBase, needsNewer);
        ModCatalog.Row addonRow = c.rows().stream()
                .filter(r -> r.descriptor().displayId().equals("addon")).findFirst().orElseThrow();
        assertNotNull(addonRow.blockReason());
        assertTrue(addonRow.blockReason().contains("2.0"));
        assertTrue(ModEligibility.compareVersions("1.0.0", "2.0") < 0);
        assertEquals(0, ModEligibility.compareVersions("2.0", "2")); // missing segments = 0
        assertEquals(0, ModEligibility.compareVersions("1.2", "1.2.0"));
        assertTrue(ModEligibility.compareVersions("2.1", "2") > 0);
    }

    @Test
    void cycleReasonListsThePath(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, mod("cyc-a", "cyc-b"), mod("cyc-b", "cyc-a"));
        String reason = c.rows().get(0).blockReason();
        assertTrue(reason.contains("cyc-a") && reason.contains("cyc-b"), reason);
    }

    @Test
    void cascadePreviewListsDependentsWithoutMutating(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, mod("base"), mod("addon", "base"), mod("addon2", "addon"));
        c.toggle("base");
        c.toggle("addon");
        c.toggle("addon2");
        List<String> preview = c.cascadePreview("base");
        assertTrue(preview.contains("addon") && preview.contains("addon2"));
        assertEquals(3, c.orderedEnabled().size()); // nothing changed
        assertTrue(c.cascadePreview("addon2").isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.mods.TestModCatalog" test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write minimal implementation**

`ModEligibility.java`:
```java
package com.openggf.mods;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static per-mod block-reason rules (spec §2 failure handling). */
public final class ModEligibility {

    private ModEligibility() {
    }

    /** Returns a user-facing block reason, or null when the mod is enableable. */
    public static String blockReason(ModDescriptor d, Map<String, ModDescriptor> byId) {
        if (d.error() != null) {
            return d.error();
        }
        ModManifest m = d.manifest();
        if (!ModApiVersion.isSatisfiedBy(m.engineApiVersion())) {
            return "requires mod API " + m.engineApiVersion()
                    + " (engine provides " + ModApiVersion.CURRENT_MAJOR + ")";
        }
        if (d.containsCode()) {
            return "mod code is not supported yet";
        }
        for (ModDependency dep : m.dependencies()) {
            ModDescriptor target = byId.get(dep.id());
            if (target == null || !target.loadable()) {
                return "missing dependency '" + dep.id() + "'";
            }
            if (dep.version() != null
                    && compareVersions(target.manifest().version(), dep.version()) < 0) {
                return "requires '" + dep.id() + "' >= " + dep.version()
                        + ", found " + target.manifest().version();
            }
        }
        List<String> cycle = findCycle(m.id(), byId);
        if (cycle != null) {
            return "dependency cycle: " + String.join(" -> ", cycle);
        }
        return null;
    }

    /**
     * Dot-segment version compare; dependency versions are minimums
     * (disclosed narrowing of the spec's "version ranges"). Missing segments
     * count as 0; non-numeric segments compare as strings.
     */
    public static int compareVersions(String a, String b) {
        String[] as = a.trim().split("\\.");
        String[] bs = b.trim().split("\\.");
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            String sa = i < as.length ? as[i] : "0";
            String sb = i < bs.length ? bs[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
            } catch (NumberFormatException e) {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** Returns the cycle path (e.g. [a, b, a]) reachable from startId, or null. */
    private static List<String> findCycle(String startId, Map<String, ModDescriptor> byId) {
        return visit(startId, startId, byId, new HashSet<>(), new ArrayList<>(List.of(startId)));
    }

    private static List<String> visit(String current, String target,
                                      Map<String, ModDescriptor> byId, Set<String> seen,
                                      List<String> path) {
        if (!seen.add(current)) {
            return null;
        }
        ModDescriptor d = byId.get(current);
        if (d == null || !d.loadable()) {
            return null;
        }
        for (ModDependency dep : d.manifest().dependencies()) {
            path.add(dep.id());
            if (dep.id().equals(target)) {
                return List.copyOf(path);
            }
            List<String> found = visit(dep.id(), target, byId, seen, path);
            if (found != null) {
                return found;
            }
            path.remove(path.size() - 1);
        }
        return null;
    }
}
```

`ModCatalog.java`:
```java
package com.openggf.mods;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runtime mod list: scan results + persisted enable state + eligibility.
 * Owned by Engine (no singleton); handed to the mod manager screen and the
 * audio-resolver builder. setForceDisabled(true) is the trace/test-mode gate:
 * rows stay visible for the manager UI, but orderedEnabled() is empty and
 * toggles are refused.
 */
public final class ModCatalog {

    public record Row(ModDescriptor descriptor, boolean enabled, String blockReason) {
    }

    private final List<ModDescriptor> ordered = new ArrayList<>();
    private final Map<String, Boolean> enabled = new HashMap<>();
    private final Map<String, String> blockReasons = new HashMap<>();
    private final ModStateStore store;
    private boolean forceDisabled;

    public ModCatalog(List<ModDescriptor> descriptors, ModStateStore store) {
        this.store = store;
        ModState state = store.load();
        Map<String, ModDescriptor> byId = new LinkedHashMap<>();
        for (ModDescriptor d : descriptors) {
            if (d.loadable()) {
                byId.put(d.manifest().id(), d);
            }
        }
        List<ModDescriptor> sorted = new ArrayList<>(descriptors);
        sorted.sort(Comparator.comparingInt(d -> state.orderOf(d.displayId())));
        for (ModDescriptor d : sorted) {
            String reason = ModEligibility.blockReason(d, byId);
            ordered.add(d);
            blockReasons.put(d.displayId(), reason);
            enabled.put(d.displayId(), reason == null && state.isEnabled(d.displayId()));
        }
        enforceTopology();
        // A persisted "enabled" for a mod whose dependency is now disabled/missing
        // must not survive the reload.
        for (ModDescriptor d : ordered) {
            if (isEnabled(d) && !dependenciesEnabled(d)) {
                enabled.put(d.displayId(), false);
            }
        }
    }

    public static ModCatalog load(Path modsDir) {
        return new ModCatalog(ModRepositoryScanner.scan(modsDir), new ModStateStore(modsDir));
    }

    public List<Row> rows() {
        return ordered.stream()
                .map(d -> new Row(d, isEnabled(d), blockReasons.get(d.displayId())))
                .toList();
    }

    public boolean toggle(String id) {
        if (forceDisabled || blockReasons.get(id) != null) {
            return false;
        }
        ModDescriptor d = find(id);
        if (d == null) {
            return false;
        }
        if (isEnabled(d)) {
            enabled.put(id, false);
            cascadeDisableDependents(id);
        } else {
            if (!dependenciesEnabled(d)) {
                return false;
            }
            enabled.put(id, true);
        }
        return true;
    }

    public boolean move(String id, int delta) {
        if (forceDisabled) {
            return false;
        }
        int from = indexOf(id);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= ordered.size()) {
            return false;
        }
        ModDescriptor moving = ordered.get(from);
        ModDescriptor neighbour = ordered.get(to);
        if (dependsOn(moving, neighbour) || dependsOn(neighbour, moving)) {
            return false;
        }
        ordered.set(from, neighbour);
        ordered.set(to, moving);
        return true;
    }

    public List<ModDescriptor> orderedEnabled() {
        if (forceDisabled) {
            return List.of();
        }
        return ordered.stream().filter(this::isEnabled).toList();
    }

    /** Enabled ids that disabling `id` would transitively disable. Read-only. */
    public List<String> cascadePreview(String id) {
        List<String> result = new ArrayList<>();
        collectDependents(id, result);
        return result;
    }

    private void collectDependents(String disabledId, List<String> result) {
        for (ModDescriptor d : ordered) {
            if (isEnabled(d) && d.loadable() && !result.contains(d.displayId())
                    && d.manifest().dependencies().stream()
                            .anyMatch(dep -> dep.id().equals(disabledId))) {
                result.add(d.displayId());
                collectDependents(d.displayId(), result);
            }
        }
    }

    public void setForceDisabled(boolean value) {
        this.forceDisabled = value;
    }

    public boolean isForceDisabled() {
        return forceDisabled;
    }

    public void persist() {
        List<ModState.Entry> entries = ordered.stream()
                .map(d -> new ModState.Entry(d.displayId(), isEnabled(d)))
                .toList();
        store.save(new ModState(entries));
    }

    private boolean isEnabled(ModDescriptor d) {
        return Boolean.TRUE.equals(enabled.get(d.displayId()));
    }

    private boolean dependenciesEnabled(ModDescriptor d) {
        if (!d.loadable()) {
            return false;
        }
        return d.manifest().dependencies().stream()
                .allMatch(dep -> {
                    ModDescriptor target = find(dep.id());
                    return target != null && isEnabled(target);
                });
    }

    private void cascadeDisableDependents(String disabledId) {
        for (ModDescriptor d : ordered) {
            if (isEnabled(d) && d.loadable()
                    && d.manifest().dependencies().stream()
                            .anyMatch(dep -> dep.id().equals(disabledId))) {
                enabled.put(d.displayId(), false);
                cascadeDisableDependents(d.displayId());
            }
        }
    }

    /** Stable pass moving each mod after its dependencies (persisted-order repair). */
    private void enforceTopology() {
        for (int i = 0; i < ordered.size(); i++) {
            ModDescriptor d = ordered.get(i);
            int minIndex = -1;
            if (d.loadable()) {
                for (ModDependency dep : d.manifest().dependencies()) {
                    minIndex = Math.max(minIndex, indexOf(dep.id()));
                }
            }
            if (minIndex > i) {
                ordered.remove(i);
                ordered.add(minIndex, d);
                i--;
            }
        }
    }

    private boolean dependsOn(ModDescriptor a, ModDescriptor b) {
        return a.loadable() && a.manifest().dependencies().stream()
                .anyMatch(dep -> dep.id().equals(b.displayId()));
    }

    private ModDescriptor find(String id) {
        return ordered.stream().filter(d -> d.displayId().equals(id)).findFirst().orElse(null);
    }

    private int indexOf(String id) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).displayId().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.mods.TestModCatalog" test`
Expected: all 10 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/mods/ModEligibility.java src/main/java/com/openggf/mods/ModCatalog.java src/test/java/com/openggf/mods/TestModCatalog.java
git commit -m "feat: mod catalog with eligibility, dependency cascade, and ordering

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 5: Audio manifest parser

**Files:**
- Create: `src/main/java/com/openggf/mods/ModAudioTrack.java`
- Create: `src/main/java/com/openggf/mods/ModAudioManifestParser.java`
- Test: `src/test/java/com/openggf/mods/TestModAudioManifestParser.java`

**Interfaces:**
- Consumes: Jackson `YAMLMapper`.
- Produces: `record ModAudioTrack(String id, String file, boolean loop, long loopStartSample, float gain, boolean tempoEffects)`; `Map<String, ModAudioTrack> ModAudioManifestParser.parse(InputStream) throws ModManifestException` (keyed by track id). Constant `ModAudioManifestParser.MANIFEST_ENTRY = "audio-manifest.yaml"`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.mods;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestModAudioManifestParser {

    private static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesTracksWithDefaults() throws Exception {
        Map<String, ModAudioTrack> tracks = new ModAudioManifestParser().parse(yaml("""
                tracks:
                  - id: ehz-remix
                    file: audio/ehz.ogg
                    loopStartSample: 480000
                  - id: jingle
                    file: audio/jingle.wav
                    loop: false
                    gain: 0.8
                    tempoEffects: false
                """));
        ModAudioTrack ehz = tracks.get("ehz-remix");
        assertEquals("audio/ehz.ogg", ehz.file());
        assertTrue(ehz.loop());
        assertEquals(480000L, ehz.loopStartSample());
        assertEquals(1.0f, ehz.gain());
        assertTrue(ehz.tempoEffects());
        ModAudioTrack jingle = tracks.get("jingle");
        assertFalse(jingle.loop());
        assertEquals(0.8f, jingle.gain(), 1e-6);
        assertFalse(jingle.tempoEffects());
    }

    @Test
    void rejectsMissingIdOrFileOrUnsupportedExtension() {
        assertThrows(ModManifestException.class, () -> new ModAudioManifestParser()
                .parse(yaml("tracks:\n  - file: a.ogg\n")));
        assertThrows(ModManifestException.class, () -> new ModAudioManifestParser()
                .parse(yaml("tracks:\n  - id: x\n")));
        assertThrows(ModManifestException.class, () -> new ModAudioManifestParser()
                .parse(yaml("tracks:\n  - id: x\n    file: a.mp3\n")));
    }

    @Test
    void rejectsDuplicateTrackIds() {
        assertThrows(ModManifestException.class, () -> new ModAudioManifestParser().parse(yaml("""
                tracks:
                  - id: x
                    file: a.ogg
                  - id: x
                    file: b.ogg
                """)));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.mods.TestModAudioManifestParser" test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write minimal implementation**

`ModAudioTrack.java`:
```java
package com.openggf.mods;

/**
 * One track in a mod's audio-manifest.yaml. loopStartSample is in sample
 * frames at the file's native rate; loop=true with loopStartSample=0 loops
 * the whole file. tempoEffects controls speed-shoes rate shifting.
 * mp3 is deliberately unsupported (spec open question) — wav/ogg only.
 */
public record ModAudioTrack(String id, String file, boolean loop, long loopStartSample,
                            float gain, boolean tempoEffects) {
}
```

`ModAudioManifestParser.java`:
```java
package com.openggf.mods;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ModAudioManifestParser {
    public static final String MANIFEST_ENTRY = "audio-manifest.yaml";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    public Map<String, ModAudioTrack> parse(InputStream in) throws ModManifestException {
        Map<String, Object> root;
        try {
            root = new YAMLMapper().readValue(in, MAP_TYPE);
        } catch (Exception e) {
            throw new ModManifestException("audio manifest is not valid YAML: " + e.getMessage(), e);
        }
        Object rawTracks = root == null ? null : root.get("tracks");
        if (!(rawTracks instanceof List<?> list)) {
            throw new ModManifestException("audio manifest missing 'tracks' list");
        }
        Map<String, ModAudioTrack> tracks = new LinkedHashMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new ModManifestException("track entry must be a map");
            }
            ModAudioTrack track = parseTrack(m);
            if (tracks.putIfAbsent(track.id(), track) != null) {
                throw new ModManifestException("duplicate track id '" + track.id() + "'");
            }
        }
        return Map.copyOf(tracks);
    }

    private static ModAudioTrack parseTrack(Map<?, ?> m) throws ModManifestException {
        Object id = m.get("id");
        Object file = m.get("file");
        if (id == null) {
            throw new ModManifestException("track missing id");
        }
        if (file == null) {
            throw new ModManifestException("track '" + id + "' missing file");
        }
        String fileName = String.valueOf(file);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".wav") && !lower.endsWith(".ogg")) {
            throw new ModManifestException(
                    "track '" + id + "': only .wav and .ogg are supported: " + fileName);
        }
        boolean loop = !(m.get("loop") instanceof Boolean b) || b;
        long loopStart = m.get("loopStartSample") instanceof Number n ? n.longValue() : 0L;
        float gain = m.get("gain") instanceof Number n ? n.floatValue() : 1.0f;
        boolean tempoEffects = !(m.get("tempoEffects") instanceof Boolean t) || t;
        return new ModAudioTrack(String.valueOf(id), fileName, loop, loopStart, gain, tempoEffects);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.mods.TestModAudioManifestParser" test`
Expected: all 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/mods/ModAudioTrack.java src/main/java/com/openggf/mods/ModAudioManifestParser.java src/test/java/com/openggf/mods/TestModAudioManifestParser.java
git commit -m "feat: mod audio-manifest parser with loop and gain metadata

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 6: PCM decode + resample (`PcmData`, wav/ogg decoders)

**Files:**
- Create: `src/main/java/com/openggf/audio/streamed/PcmData.java`
- Create: `src/main/java/com/openggf/audio/streamed/StreamedAudioDecoders.java`
- Test: `src/test/java/com/openggf/audio/streamed/TestStreamedAudioDecoders.java`

**Interfaces:**
- Consumes: existing `com.openggf.audio.WavDecoder` (`static WavDecoder decode(InputStream)`, public fields `channels`, `sampleRate`, `bitsPerSample`, `byte[] data`); LWJGL `org.lwjgl.stb.STBVorbis.stb_vorbis_decode_memory(ByteBuffer, IntBuffer, IntBuffer)`.
- Produces: `record PcmData(short[] stereoPcm, int sampleRate)` (interleaved L/R; `int frames()` = `stereoPcm.length / 2`) with `PcmData resampledTo(int targetRate)` (linear interpolation; returns `this` when rates match); `StreamedAudioDecoders.decode(String fileName, byte[] bytes) throws StreamedAudioException` (dispatch on extension, mono→stereo duplication, 16-bit only for wav). Also create `StreamedAudioException extends Exception` in the same file-set.
- **Note:** ogg decoding needs natives; the unit test covers wav + resample only. Ogg is exercised in Task 12 Step 5's manual smoke and guarded by a clear error message path.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.audio.streamed;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class TestStreamedAudioDecoders {

    /** Minimal 16-bit PCM RIFF/WAVE with the given samples. */
    static byte[] wav(int sampleRate, int channels, short... samples) {
        int dataLen = samples.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(sampleRate).putInt(sampleRate * channels * 2)
                .putShort((short) (channels * 2)).putShort((short) 16);
        b.put("data".getBytes()).putInt(dataLen);
        for (short s : samples) {
            b.putShort(s);
        }
        return b.array();
    }

    @Test
    void decodesStereoWav() throws Exception {
        PcmData pcm = StreamedAudioDecoders.decode("x.wav",
                wav(48000, 2, (short) 100, (short) -100, (short) 200, (short) -200));
        assertEquals(48000, pcm.sampleRate());
        assertEquals(2, pcm.frames());
        assertArrayEquals(new short[]{100, -100, 200, -200}, pcm.stereoPcm());
    }

    @Test
    void duplicatesMonoToStereo() throws Exception {
        PcmData pcm = StreamedAudioDecoders.decode("x.wav", wav(48000, 1, (short) 7, (short) 9));
        assertArrayEquals(new short[]{7, 7, 9, 9}, pcm.stereoPcm());
    }

    @Test
    void resamplesToTargetRate() throws Exception {
        PcmData pcm = StreamedAudioDecoders.decode("x.wav",
                wav(24000, 1, (short) 0, (short) 1000)).resampledTo(48000);
        assertEquals(48000, pcm.sampleRate());
        assertEquals(4, pcm.frames());
        // Frame 1 is interpolated halfway between 0 and 1000.
        assertEquals(500, pcm.stereoPcm()[2]);
    }

    @Test
    void sameRateResampleReturnsSameInstance() throws Exception {
        PcmData pcm = StreamedAudioDecoders.decode("x.wav", wav(48000, 1, (short) 1));
        assertSame(pcm, pcm.resampledTo(48000));
    }

    @Test
    void rejectsUnknownExtensionAndTruncatedWav() {
        assertThrows(StreamedAudioException.class,
                () -> StreamedAudioDecoders.decode("x.mp3", new byte[16]));
        assertThrows(StreamedAudioException.class,
                () -> StreamedAudioDecoders.decode("x.wav", new byte[4]));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.audio.streamed.TestStreamedAudioDecoders" test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write minimal implementation**

`PcmData.java`:
```java
package com.openggf.audio.streamed;

/** Decoded interleaved-stereo 16-bit PCM. */
public record PcmData(short[] stereoPcm, int sampleRate) {

    public int frames() {
        return stereoPcm.length / 2;
    }

    /** Linear-interpolation resample. Returns this when already at targetRate. */
    public PcmData resampledTo(int targetRate) {
        if (targetRate == sampleRate) {
            return this;
        }
        int srcFrames = frames();
        int dstFrames = (int) ((long) srcFrames * targetRate / sampleRate);
        short[] out = new short[dstFrames * 2];
        double step = (double) sampleRate / targetRate;
        for (int i = 0; i < dstFrames; i++) {
            double srcPos = i * step;
            int f0 = (int) srcPos;
            int f1 = Math.min(f0 + 1, srcFrames - 1);
            double t = srcPos - f0;
            for (int ch = 0; ch < 2; ch++) {
                double v = stereoPcm[f0 * 2 + ch] * (1.0 - t) + stereoPcm[f1 * 2 + ch] * t;
                out[i * 2 + ch] = (short) Math.round(v);
            }
        }
        return new PcmData(out, targetRate);
    }
}
```

`StreamedAudioDecoders.java` (includes the exception type as a top-level class in its own file — create `StreamedAudioException.java` alongside):
```java
package com.openggf.audio.streamed;

public class StreamedAudioException extends Exception {
    public StreamedAudioException(String message) {
        super(message);
    }

    public StreamedAudioException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

```java
package com.openggf.audio.streamed;

import com.openggf.audio.WavDecoder;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Locale;

/** wav/ogg → PcmData. mp3 is intentionally unsupported (spec open question). */
public final class StreamedAudioDecoders {

    private StreamedAudioDecoders() {
    }

    public static PcmData decode(String fileName, byte[] bytes) throws StreamedAudioException {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".wav")) {
            return decodeWav(fileName, bytes);
        }
        if (lower.endsWith(".ogg")) {
            return decodeOgg(fileName, bytes);
        }
        throw new StreamedAudioException("unsupported audio format (wav/ogg only): " + fileName);
    }

    private static PcmData decodeWav(String fileName, byte[] bytes) throws StreamedAudioException {
        WavDecoder wav;
        try {
            wav = WavDecoder.decode(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new StreamedAudioException("bad wav '" + fileName + "': " + e.getMessage(), e);
        }
        if (wav.bitsPerSample != 16) {
            throw new StreamedAudioException(
                    "wav '" + fileName + "' must be 16-bit PCM, was " + wav.bitsPerSample + "-bit");
        }
        if (wav.channels < 1 || wav.channels > 2) {
            throw new StreamedAudioException(
                    "wav '" + fileName + "' must be mono or stereo, was " + wav.channels + "ch");
        }
        ByteBuffer data = ByteBuffer.wrap(wav.data).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        int totalSamples = wav.data.length / 2;
        short[] samples = new short[totalSamples];
        for (int i = 0; i < totalSamples; i++) {
            samples[i] = data.getShort();
        }
        return new PcmData(toStereo(samples, wav.channels), wav.sampleRate);
    }

    private static PcmData decodeOgg(String fileName, byte[] bytes) throws StreamedAudioException {
        ByteBuffer input = MemoryUtil.memAlloc(bytes.length);
        try {
            input.put(bytes).flip();
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                IntBuffer channels = stack.mallocInt(1);
                IntBuffer rate = stack.mallocInt(1);
                ShortBuffer decoded = STBVorbis.stb_vorbis_decode_memory(input, channels, rate);
                if (decoded == null) {
                    throw new StreamedAudioException("stb_vorbis failed to decode: " + fileName);
                }
                try {
                    short[] samples = new short[decoded.remaining()];
                    decoded.get(samples);
                    return new PcmData(toStereo(samples, channels.get(0)), rate.get(0));
                } finally {
                    // stb malloc'd this buffer — free with the libc allocator,
                    // NOT MemoryUtil.memFree (allocator mismatch can crash).
                    org.lwjgl.system.libc.LibCStdlib.free(decoded);
                }
            }
        } finally {
            MemoryUtil.memFree(input);
        }
    }

    private static short[] toStereo(short[] samples, int channels) throws StreamedAudioException {
        if (channels == 2) {
            return samples;
        }
        if (channels != 1) {
            throw new StreamedAudioException("only mono/stereo supported, got " + channels + "ch");
        }
        short[] stereo = new short[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            stereo[i * 2] = samples[i];
            stereo[i * 2 + 1] = samples[i];
        }
        return stereo;
    }
}
```

Note: verify `WavDecoder.decode` signature/field names against `src/main/java/com/openggf/audio/WavDecoder.java` before wiring; adjust the adapter accordingly (recon says `static WavDecoder decode(InputStream)`, public `channels`/`sampleRate`/`bitsPerSample`/`data`).

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.audio.streamed.TestStreamedAudioDecoders" test`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/audio/streamed/PcmData.java src/main/java/com/openggf/audio/streamed/StreamedAudioException.java src/main/java/com/openggf/audio/streamed/StreamedAudioDecoders.java src/test/java/com/openggf/audio/streamed/TestStreamedAudioDecoders.java
git commit -m "feat: streamed-audio PCM model with wav/ogg decode and resample

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 7: `StreamedTrack` — loop-aware `AudioStream` with rate + volume

**Files:**
- Create: `src/main/java/com/openggf/audio/streamed/StreamedTrackData.java`
- Create: `src/main/java/com/openggf/audio/streamed/StreamedTrack.java`
- Test: `src/test/java/com/openggf/audio/streamed/TestStreamedTrack.java`

**Interfaces:**
- Consumes: `PcmData` (Task 6); implements existing `com.openggf.audio.AudioStream` (`int read(short[])`, `int read(short[], int length)`, `boolean isComplete()`).
- Produces: `record StreamedTrackData(String key, PcmData pcm, boolean loop, long loopStartFrame, float gain, boolean tempoEffects)`; `StreamedTrack implements AudioStream` with `setRate(double)` (1.0 normal, e.g. 1.25 speed shoes — resamples on the fly), `setVolume(float)` (0..1, multiplied with gain), `double positionFrames()`, `void seekToFrame(double)`. Non-loop tracks emit silence and report `isComplete()` after the end.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.audio.streamed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestStreamedTrack {

    private static StreamedTrackData data(boolean loop, long loopStart, float gain, short... frames) {
        short[] stereo = new short[frames.length * 2];
        for (int i = 0; i < frames.length; i++) {
            stereo[i * 2] = frames[i];
            stereo[i * 2 + 1] = frames[i];
        }
        return new StreamedTrackData("t", new PcmData(stereo, 48000), loop, loopStart, gain, true);
    }

    @Test
    void readsSequentiallyThenLoopsFromLoopStart() {
        StreamedTrack track = new StreamedTrack(data(true, 2, 1.0f, (short) 1, (short) 2, (short) 3, (short) 4));
        short[] buf = new short[8]; // 4 frames
        assertEquals(8, track.read(buf, 8));
        assertArrayEquals(new short[]{1, 1, 2, 2, 3, 3, 4, 4}, buf);
        assertEquals(8, track.read(buf, 8)); // wraps to frame 2
        assertArrayEquals(new short[]{3, 3, 4, 4, 3, 3, 4, 4}, buf);
        assertFalse(track.isComplete());
    }

    @Test
    void nonLoopTrackEndsWithSilence() {
        StreamedTrack track = new StreamedTrack(data(false, 0, 1.0f, (short) 5));
        short[] buf = new short[4];
        track.read(buf, 4);
        assertArrayEquals(new short[]{5, 5, 0, 0}, buf);
        assertTrue(track.isComplete());
    }

    @Test
    void gainAndVolumeScaleSamples() {
        StreamedTrack track = new StreamedTrack(data(true, 0, 0.5f, (short) 1000));
        track.setVolume(0.5f);
        short[] buf = new short[2];
        track.read(buf, 2);
        assertEquals(250, buf[0]);
    }

    @Test
    void rateShiftAdvancesFaster() {
        StreamedTrack track = new StreamedTrack(
                data(true, 0, 1.0f, (short) 0, (short) 100, (short) 200, (short) 300));
        track.setRate(2.0);
        short[] buf = new short[4]; // 2 output frames consume 4 source frames
        track.read(buf, 4);
        assertEquals(0, buf[0]);
        assertEquals(200, buf[2]);
    }

    @Test
    void seekAndPositionRoundTrip() {
        StreamedTrack track = new StreamedTrack(data(true, 0, 1.0f, new short[64]));
        track.seekToFrame(10.0);
        assertEquals(10.0, track.positionFrames(), 1e-9);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.audio.streamed.TestStreamedTrack" test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write minimal implementation**

`StreamedTrackData.java`:
```java
package com.openggf.audio.streamed;

/**
 * A fully decoded, playback-rate-normalized track ready for StreamedTrack.
 * loopStartFrame is in output-rate frames (converted from the manifest's
 * native-rate loopStartSample by the resolver at decode time).
 */
public record StreamedTrackData(String key, PcmData pcm, boolean loop, long loopStartFrame,
                                float gain, boolean tempoEffects) {
}
```

`StreamedTrack.java`:
```java
package com.openggf.audio.streamed;

import com.openggf.audio.AudioStream;

/**
 * Loop-aware PCM playback over decoded track data. rate > 1.0 pitches up and
 * speeds up (speed shoes); volume is a live multiplier used for fades and
 * jingle ducking. Reads past the end of a non-looping track emit silence.
 */
public final class StreamedTrack implements AudioStream {
    private final StreamedTrackData data;
    private double positionFrames;
    private double rate = 1.0;
    private float volume = 1.0f;
    private boolean complete;

    public StreamedTrack(StreamedTrackData data) {
        this.data = data;
    }

    public void setRate(double rate) {
        this.rate = rate;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0f, Math.min(1f, volume));
    }

    public float volume() {
        return volume;
    }

    public double positionFrames() {
        return positionFrames;
    }

    public void seekToFrame(double frame) {
        int frames = data.pcm().frames();
        this.positionFrames = frames == 0 ? 0 : Math.min(frame, frames - 1);
        this.complete = false;
    }

    public StreamedTrackData trackData() {
        return data;
    }

    @Override
    public int read(short[] buffer) {
        return read(buffer, buffer.length);
    }

    @Override
    public int read(short[] buffer, int length) {
        short[] pcm = data.pcm().stereoPcm();
        int totalFrames = data.pcm().frames();
        float amplitude = volume * data.gain();
        for (int out = 0; out + 1 < length; out += 2) {
            if (positionFrames >= totalFrames) {
                if (data.loop() && totalFrames > 0) {
                    positionFrames = Math.min(data.loopStartFrame(), totalFrames - 1)
                            + (positionFrames - totalFrames);
                } else {
                    complete = true;
                    buffer[out] = 0;
                    buffer[out + 1] = 0;
                    continue;
                }
            }
            int f0 = (int) positionFrames;
            int f1 = Math.min(f0 + 1, totalFrames - 1);
            double t = positionFrames - f0;
            for (int ch = 0; ch < 2; ch++) {
                double v = pcm[f0 * 2 + ch] * (1.0 - t) + pcm[f1 * 2 + ch] * t;
                int scaled = (int) Math.round(v * amplitude);
                buffer[out + ch] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            }
            positionFrames += rate;
        }
        return length;
    }

    @Override
    public boolean isComplete() {
        return complete;
    }
}
```

Note: verify the exact `AudioStream` method set against `src/main/java/com/openggf/audio/AudioStream.java` before implementing (recon: `read(short[])`, `read(short[], int)`, `isComplete()`); implement whatever the interface actually requires.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.audio.streamed.TestStreamedTrack" test`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/audio/streamed/StreamedTrackData.java src/main/java/com/openggf/audio/streamed/StreamedTrack.java src/test/java/com/openggf/audio/streamed/TestStreamedTrack.java
git commit -m "feat: loop-aware streamed track implementing AudioStream

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 8: `StreamedMusicPlayer` — play/stop/fade/pause state machine

**Files:**
- Create: `src/main/java/com/openggf/audio/streamed/StreamedMusicPlayer.java`
- Test: `src/test/java/com/openggf/audio/streamed/TestStreamedMusicPlayer.java`

**Interfaces:**
- Consumes: `StreamedTrack`, `StreamedTrackData` (Task 7).
- Produces (the sidecar the backend drives — Task 10):
  - `void play(StreamedTrackData data)` / `void stop()`
  - `boolean active()` — a track is loaded and not stopped
  - `String currentKey()` — active track key or null
  - `void mixInto(short[] buffer, int length)` — saturating-add into an existing mix buffer; silent when paused for any reason
  - `void fadeOut(int steps, int delay)` — volume ramps to 0 over `steps * delay` mix windows, then stops
  - `void setSpeedShoes(boolean on)` — rate 1.25 / 1.0 (only when `tempoEffects`)
  - pause reasons, each independent: `setOverridePaused(boolean)` (jingle push/pop), `setRewindPaused(boolean)`, `setAppPaused(boolean)`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.audio.streamed;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestStreamedMusicPlayer {

    private static StreamedTrackData constantTrack(short value) {
        short[] stereo = new short[]{value, value, value, value};
        return new StreamedTrackData("t", new PcmData(stereo, 48000), true, 0, 1.0f, true);
    }

    private static short mixOnce(StreamedMusicPlayer player) {
        short[] buf = new short[2];
        player.mixInto(buf, 2);
        return buf[0];
    }

    @Test
    void mixesIntoBufferWithSaturation() {
        StreamedMusicPlayer player = new StreamedMusicPlayer();
        player.play(constantTrack((short) 30000));
        short[] buf = new short[]{10000, 10000};
        player.mixInto(buf, 2);
        assertEquals(Short.MAX_VALUE, buf[0]);
    }

    @Test
    void pauseReasonsAreIndependent() {
        StreamedMusicPlayer player = new StreamedMusicPlayer();
        player.play(constantTrack((short) 100));
        player.setOverridePaused(true);
        player.setRewindPaused(true);
        assertEquals(0, mixOnce(player));
        player.setOverridePaused(false);
        assertEquals(0, mixOnce(player)); // still rewind-paused
        player.setRewindPaused(false);
        assertEquals(100, mixOnce(player));
        assertTrue(player.active());
    }

    @Test
    void fadeOutRampsToZeroThenStops() {
        StreamedMusicPlayer player = new StreamedMusicPlayer();
        player.play(constantTrack((short) 1000));
        player.fadeOut(4, 1);
        short first = mixOnce(player);
        short second = mixOnce(player);
        assertTrue(first > second, first + " > " + second);
        mixOnce(player);
        mixOnce(player);
        mixOnce(player);
        assertFalse(player.active());
    }

    @Test
    void speedShoesShiftsRateOnlyForTempoTracks() {
        StreamedMusicPlayer player = new StreamedMusicPlayer();
        StreamedTrackData noTempo = new StreamedTrackData("n",
                new PcmData(new short[]{1, 1}, 48000), true, 0, 1.0f, false);
        player.play(noTempo);
        player.setSpeedShoes(true);
        assertEquals(1.0, player.currentRateForTest(), 1e-9);
        player.play(constantTrack((short) 1));
        player.setSpeedShoes(true);
        assertEquals(StreamedMusicPlayer.SPEED_SHOES_RATE, player.currentRateForTest(), 1e-9);
    }

    @Test
    void playReplacesAndStopClears() {
        StreamedMusicPlayer player = new StreamedMusicPlayer();
        player.play(constantTrack((short) 1));
        assertEquals("t", player.currentKey());
        player.stop();
        assertFalse(player.active());
        assertNull(player.currentKey());
        assertEquals(0, mixOnce(player));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.audio.streamed.TestStreamedMusicPlayer" test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.audio.streamed;

/**
 * Streamed music sidecar driven by the SMPS backend (Task 10). Deliberately
 * has no knowledge of music ids, the AudioCommand rewind timeline, or the
 * deterministic runtime — spec 2026-07-09 mod-support design section 4:
 * streamed playback is excluded from command replay and follows a simple
 * pause/resume model.
 */
public final class StreamedMusicPlayer {
    /**
     * Fixed speed-shoes rate for streams. The ROM's tempo bump differs per
     * game/track; a single pitch/rate shift is the documented divergence for
     * mod audio (spec section 4).
     */
    public static final double SPEED_SHOES_RATE = 1.25;

    private StreamedTrack track;
    private boolean overridePaused;
    private boolean rewindPaused;
    private boolean appPaused;
    private boolean speedShoes;
    private int fadeStepsRemaining;
    private int fadeDelay;
    private int fadeDelayCounter;
    private float fadeStepAmount;
    private short[] scratch = new short[0];

    public void play(StreamedTrackData data) {
        track = new StreamedTrack(data);
        fadeStepsRemaining = 0;
        applyRate();
    }

    public void stop() {
        track = null;
        fadeStepsRemaining = 0;
    }

    public boolean active() {
        return track != null;
    }

    public String currentKey() {
        return track == null ? null : track.trackData().key();
    }

    public void setOverridePaused(boolean paused) {
        this.overridePaused = paused;
    }

    public void setRewindPaused(boolean paused) {
        this.rewindPaused = paused;
    }

    public void setAppPaused(boolean paused) {
        this.appPaused = paused;
    }

    public void setSpeedShoes(boolean on) {
        this.speedShoes = on;
        applyRate();
    }

    public void fadeOut(int steps, int delay) {
        if (track == null || steps <= 0) {
            stop();
            return;
        }
        fadeStepsRemaining = steps;
        fadeDelay = Math.max(1, delay);
        fadeDelayCounter = fadeDelay;
        fadeStepAmount = track.volume() / steps;
    }

    /** Saturating-add this player's samples into buffer[0..length). */
    public void mixInto(short[] buffer, int length) {
        if (track == null || overridePaused || rewindPaused || appPaused) {
            return;
        }
        advanceFade();
        if (track == null) {
            return;
        }
        if (scratch.length < length) {
            scratch = new short[length];
        }
        track.read(scratch, length);
        for (int i = 0; i < length; i++) {
            int mixed = buffer[i] + scratch[i];
            buffer[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
        }
    }

    double currentRateForTest() {
        return track != null && track.trackData().tempoEffects() && speedShoes
                ? SPEED_SHOES_RATE : 1.0;
    }

    private void applyRate() {
        if (track != null) {
            track.setRate(track.trackData().tempoEffects() && speedShoes ? SPEED_SHOES_RATE : 1.0);
        }
    }

    private void advanceFade() {
        if (fadeStepsRemaining <= 0 || track == null) {
            return;
        }
        if (--fadeDelayCounter > 0) {
            return;
        }
        fadeDelayCounter = fadeDelay;
        fadeStepsRemaining--;
        track.setVolume(track.volume() - fadeStepAmount);
        if (fadeStepsRemaining <= 0) {
            stop();
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.audio.streamed.TestStreamedMusicPlayer" test`
Expected: all 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/audio/streamed/StreamedMusicPlayer.java src/test/java/com/openggf/audio/streamed/TestStreamedMusicPlayer.java
git commit -m "feat: streamed music player with fade, pause reasons, speed shoes

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 9: `ModMusicResolver` — music id → decoded track, later-wins

**Files:**
- Create: `src/main/java/com/openggf/audio/streamed/ModMusicResolver.java`
- Create: `src/main/java/com/openggf/mods/ModAudioIntegration.java`
- Test: `src/test/java/com/openggf/mods/TestModAudioIntegration.java`

**Interfaces:**
- Consumes: `ModCatalog.orderedEnabled()` (Task 4), `ModAudioManifestParser` (Task 5), `StreamedAudioDecoders`/`StreamedTrackData` (Tasks 6–7), `ModManifest.audioOverrides()`.
- Produces:
  - `ModMusicResolver` (in `audio.streamed`, so the audio package never depends on `mods`): constructor `ModMusicResolver(Map<Integer, TrackSource> sources)` where `record TrackSource(Path jar, String entryPath, ModAudioTrack meta)` is nested (use plain field types — `ModAudioTrack` would invert the dependency, so nest a minimal `record TrackMeta(boolean loop, long loopStartSample, float gain, boolean tempoEffects)` instead and keep `mods` types out of `audio.streamed`); method `Optional<StreamedTrackData> resolve(int musicId, int outputRate)` — lazy: reads the jar entry, decodes, resamples to `outputRate`, converts `loopStartSample` native-rate → output-rate frames, caches by musicId; decode failure logs once and resolves empty forever (a broken track must not crash `playMusic`).
  - `ModMusicResolver.EMPTY` — resolves nothing.
  - `ModAudioIntegration.buildResolver(ModCatalog catalog, String gameId)` (in `mods`): walks `orderedEnabled()`, filters `type == PATCH && baseGame.equals(gameId)`, parses each jar's `audio-manifest.yaml`, maps every `audioOverrides` entry to a `TrackSource` (later mods overwrite earlier — spec §7 "later wins"); missing audio manifest or unknown track id → that mod's overrides are skipped with a log line, never a throw.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.mods;

import com.openggf.audio.streamed.ModMusicResolver;
import com.openggf.audio.streamed.StreamedTrackData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestModAudioIntegration {

    private static byte[] wav(int sampleRate, short... samples) {
        int dataLen = samples.length * 2;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes()).putInt(dataLen);
        for (short s : samples) {
            b.putShort(s);
        }
        return b.array();
    }

    private static void writePack(Path jar, String id, int musicId, String trackYamlExtras)
            throws Exception {
        String manifest = """
                id: %s
                name: %s
                version: 1.0.0
                engineApiVersion: "1"
                type: patch
                baseGame: s2
                audioOverrides:
                  "%d": main
                """.formatted(id, id, musicId);
        String audioManifest = """
                tracks:
                  - id: main
                    file: audio/main.wav
                %s""".formatted(trackYamlExtras);
        TestModRepositoryScanner.writeJar(jar, Map.of(
                "META-INF/openggf-mod.yaml", manifest.getBytes(StandardCharsets.UTF_8),
                "audio-manifest.yaml", audioManifest.getBytes(StandardCharsets.UTF_8),
                "audio/main.wav", wav(48000, (short) 1234, (short) 1234)));
    }

    @Test
    void resolvesOverriddenIdAndDecodesTrack(@TempDir Path tmp) throws Exception {
        writePack(tmp.resolve("pack.jar"), "pack", 0x8C, "    loopStartSample: 1\n");
        ModCatalog catalog = ModCatalog.load(tmp);
        catalog.toggle("pack");
        ModMusicResolver resolver = ModAudioIntegration.buildResolver(catalog, "s2");
        Optional<StreamedTrackData> track = resolver.resolve(0x8C, 48000);
        assertTrue(track.isPresent());
        assertEquals(1234, track.get().pcm().stereoPcm()[0]);
        assertEquals(1L, track.get().loopStartFrame());
        assertTrue(resolver.resolve(0x99, 48000).isEmpty());
    }

    @Test
    void laterModWinsOnConflict(@TempDir Path tmp) throws Exception {
        writePack(tmp.resolve("a-pack.jar"), "a-pack", 0x8C, "");
        writePack(tmp.resolve("b-pack.jar"), "b-pack", 0x8C, "    gain: 0.5\n");
        ModCatalog catalog = ModCatalog.load(tmp);
        catalog.toggle("a-pack");
        catalog.toggle("b-pack");
        ModMusicResolver resolver = ModAudioIntegration.buildResolver(catalog, "s2");
        assertEquals(0.5f, resolver.resolve(0x8C, 48000).orElseThrow().gain(), 1e-6);
    }

    @Test
    void wrongGameAndDisabledModsResolveNothing(@TempDir Path tmp) throws Exception {
        writePack(tmp.resolve("pack.jar"), "pack", 0x8C, "");
        ModCatalog catalog = ModCatalog.load(tmp);
        assertTrue(ModAudioIntegration.buildResolver(catalog, "s2").resolve(0x8C, 48000).isEmpty());
        catalog.toggle("pack");
        assertTrue(ModAudioIntegration.buildResolver(catalog, "s1").resolve(0x8C, 48000).isEmpty());
    }

    @Test
    void unknownTrackIdSkipsModWithoutThrowing(@TempDir Path tmp) throws Exception {
        String manifest = """
                id: broken
                name: broken
                version: 1.0.0
                engineApiVersion: "1"
                type: patch
                baseGame: s2
                audioOverrides:
                  "0x8C": no-such-track
                """;
        TestModRepositoryScanner.writeJar(tmp.resolve("broken.jar"), Map.of(
                "META-INF/openggf-mod.yaml", manifest.getBytes(StandardCharsets.UTF_8),
                "audio-manifest.yaml", "tracks:\n  - id: other\n    file: a.wav\n"
                        .getBytes(StandardCharsets.UTF_8)));
        ModCatalog catalog = ModCatalog.load(tmp);
        catalog.toggle("broken");
        ModMusicResolver resolver = ModAudioIntegration.buildResolver(catalog, "s2");
        assertTrue(resolver.resolve(0x8C, 48000).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.mods.TestModAudioIntegration" test`
Expected: COMPILE FAILURE.

- [ ] **Step 3: Write minimal implementation**

`ModMusicResolver.java` (package `com.openggf.audio.streamed` — no `com.openggf.mods` imports):
```java
package com.openggf.audio.streamed;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Maps base-game music ids to mod-supplied streamed tracks. Decoding is lazy
 * (first playMusic for the id) and cached; a track that fails to decode is
 * remembered as failed and resolves empty from then on — a broken ogg must
 * degrade to base music, never crash playback.
 *
 * Non-final with overridable overrides()/resolve() so backend tests can stub
 * it without jar IO (Task 10).
 */
public class ModMusicResolver {

    public record TrackMeta(boolean loop, long loopStartSample, float gain, boolean tempoEffects) {
    }

    public record TrackSource(Path jar, String entryPath, TrackMeta meta) {
    }

    public static final ModMusicResolver EMPTY = new ModMusicResolver(Map.of());

    private final Map<Integer, TrackSource> sources;
    private final Map<Integer, StreamedTrackData> cache = new HashMap<>();
    private final Set<Integer> failed = new HashSet<>();

    public ModMusicResolver(Map<Integer, TrackSource> sources) {
        this.sources = Map.copyOf(sources);
    }

    public boolean overrides(int musicId) {
        return sources.containsKey(musicId);
    }

    public Optional<StreamedTrackData> resolve(int musicId, int outputRate) {
        StreamedTrackData cached = cache.get(musicId);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (failed.contains(musicId)) {
            return Optional.empty();
        }
        TrackSource source = sources.get(musicId);
        if (source == null) {
            return Optional.empty();
        }
        try {
            PcmData nativePcm = decodeEntry(source);
            TrackMeta meta = source.meta();
            // Manifest loop points are native-rate sample frames; convert
            // before resampling changes the frame count.
            long loopStartFrame = meta.loopStartSample() * outputRate / nativePcm.sampleRate();
            StreamedTrackData data = new StreamedTrackData(
                    source.jar().getFileName() + "!" + source.entryPath(),
                    nativePcm.resampledTo(outputRate),
                    meta.loop(), loopStartFrame, meta.gain(), meta.tempoEffects());
            cache.put(musicId, data);
            return Optional.of(data);
        } catch (Exception e) {
            System.err.println("[mods] failed to decode " + source.entryPath()
                    + " from " + source.jar() + ": " + e.getMessage());
            failed.add(musicId);
            return Optional.empty();
        }
    }

    private PcmData decodeEntry(TrackSource source) throws IOException, StreamedAudioException {
        try (JarFile jar = new JarFile(source.jar().toFile())) {
            JarEntry entry = jar.getJarEntry(source.entryPath());
            if (entry == null) {
                throw new StreamedAudioException("missing jar entry " + source.entryPath());
            }
            byte[] bytes;
            try (InputStream in = jar.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            return StreamedAudioDecoders.decode(source.entryPath(), bytes);
        }
    }
}
```

`ModAudioIntegration.java` (package `com.openggf.mods`):
```java
package com.openggf.mods;

import com.openggf.audio.streamed.ModMusicResolver;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Builds the per-session music resolver from enabled patch mods matching the
 * detected game. Later mods in load order win conflicting overrides
 * (spec section 7). Any per-mod failure skips that mod's overrides with a log
 * line — mod problems must never abort a game launch.
 */
public final class ModAudioIntegration {

    private ModAudioIntegration() {
    }

    public static ModMusicResolver buildResolver(ModCatalog catalog, String gameId) {
        Map<Integer, ModMusicResolver.TrackSource> sources = new HashMap<>();
        for (ModDescriptor d : catalog.orderedEnabled()) {
            ModManifest m = d.manifest();
            if (m.type() != ModType.PATCH || !gameId.equals(m.baseGame())
                    || m.audioOverrides().isEmpty()) {
                continue;
            }
            try (JarFile jar = new JarFile(d.jarPath().toFile())) {
                JarEntry entry = jar.getJarEntry(ModAudioManifestParser.MANIFEST_ENTRY);
                if (entry == null) {
                    System.err.println("[mods] " + m.id() + " declares audioOverrides but has no "
                            + ModAudioManifestParser.MANIFEST_ENTRY + "; skipping");
                    continue;
                }
                Map<String, ModAudioTrack> tracks;
                try (InputStream in = jar.getInputStream(entry)) {
                    tracks = new ModAudioManifestParser().parse(in);
                }
                for (Map.Entry<Integer, String> override : m.audioOverrides().entrySet()) {
                    ModAudioTrack track = tracks.get(override.getValue());
                    if (track == null) {
                        System.err.println("[mods] " + m.id() + " override for music 0x"
                                + Integer.toHexString(override.getKey())
                                + " names unknown track '" + override.getValue() + "'; skipping");
                        continue;
                    }
                    sources.put(override.getKey(), new ModMusicResolver.TrackSource(
                            d.jarPath(), track.file(),
                            new ModMusicResolver.TrackMeta(track.loop(), track.loopStartSample(),
                                    track.gain(), track.tempoEffects())));
                }
            } catch (Exception e) {
                System.err.println("[mods] skipping audio overrides of " + m.id()
                        + ": " + e.getMessage());
            }
        }
        return sources.isEmpty() ? ModMusicResolver.EMPTY : new ModMusicResolver(sources);
    }
}
```

Note: if the repo has an established logger (check neighbors in `com.openggf.audio` for `slf4j`/JUL usage), use that instead of `System.err`; keep the message text.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.mods.TestModAudioIntegration" test`
Expected: all 4 tests PASS.

- [ ] **Step 5: Extend the ArchUnit top-level-edge ratchet.** `ModAudioIntegration` creates a new top-level slice edge `mods -> audio`, and `core_runtime_cycle_cluster_does_not_gain_top_level_edges` (`src/test/java/com/openggf/tests/TestArchUnitRules.java:496-505`) rejects any edge touching the core cluster (`audio` is in it) that is not in `CORE_RUNTIME_TOP_LEVEL_DEPENDENCY_EDGES` (~lines 112-234). Add `"mods -> audio"` to that allowlist in the list's existing format, with a comment citing this plan. Run: `mvn "-Dtest=com.openggf.tests.TestArchUnitRules" test` — expected PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/audio/streamed/ModMusicResolver.java src/main/java/com/openggf/mods/ModAudioIntegration.java src/test/java/com/openggf/mods/TestModAudioIntegration.java src/test/java/com/openggf/tests/TestArchUnitRules.java
git commit -m "feat: mod music resolver with lazy decode and later-wins overrides

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 10: Backend integration — route, mix, jingle pause, speed shoes, rewind exclusion

This is the delicate task: it modifies `AbstractSmpsAudioBackend`. **Read the whole file first.** Recon anchors (2026-07-09): music-state push/pop stack `Deque<MusicState> musicStack` (~line 85), `playSmps(...)` override handling (~lines 268/365), `restoreMusic()`/`doRestoreMusic()` (~606/614), mixing loop in `fillBuffer(int)` (~665, mix at 704–711), `setSpeedShoes` (~1181), `changeMusicTempo` (~1201), `stopPlayback`, `pause()`/`resume()`, `fadeOutMusic`.

**Files:**
- Modify: `src/main/java/com/openggf/audio/AudioBackend.java` (one new default method)
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java` (sticky resolver field, re-applied in `setBackend`)
- Test: `src/test/java/com/openggf/audio/streamed/TestStreamedBackendIntegration.java`

**Interfaces:**
- Consumes: `ModMusicResolver`, `StreamedMusicPlayer`, `StreamedTrackData` (Tasks 8–9).
- Produces:
  - `AudioBackend`: `default void setModMusicResolver(com.openggf.audio.streamed.ModMusicResolver resolver) {}`
  - `AbstractSmpsAudioBackend` overrides it: stores resolver (never null — default `ModMusicResolver.EMPTY`), owns a `StreamedMusicPlayer streamedMusic`.
  - `AudioManager`: `public void setModMusicResolver(ModMusicResolver resolver)` → `backend.setModMusicResolver(resolver)`; call sites in Task 12.

**Behavioral contract (encode in the modification, guided by the file's actual structure):**

1. **Route on music start.** `playSmps(AbstractSmpsData data, DacData dacData)` derives `int musicId = data.getId()` (~line 268-269; there is no `playSmps(int, ...)`). In the non-jingle branch (where `audioProfile.isMusicOverride(musicId)` is FALSE): if `tryStartStreamedMusic(musicId)` succeeds, **suppress the SMPS music stream** (do not set `currentStream`/`currentSmps`; still update the logical current-music bookkeeping the file already does, so `captureLogicalSnapshot`/`restoreLogicalSnapshot` keep working) and clear any pending stream-override pause (point 2). On resolve failure, fall through to normal SMPS playback. **Idempotence guard:** if the resolved track's key equals `streamedMusic.currentKey()` and the player is active, keep the existing stream position instead of restarting — this makes the rewind command replay (`AudioManager.replayMusic` → `backend.playSmps`, AudioManager.java:625-633) re-issue the *current* stream state rather than restarting from frame 0, which is the spec §4 "resume in place" model. (Accepted inefficiency: `AudioManager.playMusic` still loads the SMPS data before the backend consults the resolver; harmless, one ROM read per overridden play.)
2. **Jingle push/pop — do NOT rely on `pushCurrentState()`/`doRestoreMusic()`.** Both early-return when `currentStream`/`currentSmps`/`smpsDriver` are null (`pushCurrentState` ~1254-1257, `doRestoreMusic` ~616-619), which is exactly the state while streamed music plays, so the existing stack machinery never sees the stream. Instead add a backend field `boolean streamPausedForOverride`:
   - In `playSmps`'s override-jingle branch (where `audioProfile.isMusicOverride(musicId)` is TRUE): if `streamedMusic.active()`, set `streamedMusic.setOverridePaused(true); streamPausedForOverride = true;` then let the jingle play through the normal SMPS path.
   - **Resume intercept** — extract a helper `boolean resumeStreamFromOverride()`: if `streamPausedForOverride && streamedMusic.active() && musicStack.isEmpty()`, it (a) **stops the still-playing jingle SMPS** the same way `doRestoreMusic()`'s stop phase does (`hookStopAndUnqueueAllMusicBuffers()` + `smpsDriver.stopAll()`, ~623-628, each null-guarded — in the headless unit test no jingle ever played so `smpsDriver`/`currentSmps` may be null), (b) clears `currentSmps`/`currentStream` jingle bookkeeping and restores the logical `currentMusicId` (and `currentMusicDescriptor`, so snapshots don't carry the stale jingle descriptor) to the stream's overridden music id (track it in a field `int streamedMusicId` set by `tryStartStreamedMusic`) so later `fadeOutMusic`/`setSpeedShoes`/snapshots see the stream's id, not the stale jingle, then (c) `streamedMusic.setOverridePaused(false); streamPausedForOverride = false;` and returns true. **The `musicStack.isEmpty()` gate matters for stacked jingles:** a 1-up ending during invincibility must restore the invincibility jingle via the normal `doRestoreMusic` pop (the stream stays paused, the flag stays set); only when the *last* jingle ends is the stack empty and the stream resumed. Call sites:
     - Head of `restoreMusic()`: `if (resumeStreamFromOverride()) return;`
     - In `endMusicOverride(int musicId)`: intercept **only when the ending override is the currently-playing music** (the same `currentMusicId == musicId` distinction the method already makes before choosing restore-vs-`removeSavedOverride`, ~1120-1126). Stacked case (1-up ends while invincibility still plays, or vice versa): fall through to `removeSavedOverride` unchanged and do NOT clear `streamPausedForOverride` — the stream resumes only when the *current* jingle ends.
   - **Any music start stops or replaces the stream.** In `playSmps`'s non-jingle branch: when `tryStartStreamedMusic(musicId)` returns false (id not overridden, or resolve failed) and normal SMPS playback proceeds, call `streamedMusic.stop(); streamPausedForOverride = false;` — otherwise a boss-music or next-act SMPS track would play over the still-looping (or worse, jingle-paused-then-unmuted) stream. `tryStartStreamedMusic` itself already clears the pause flags on success.
3. **Stop/fade.** `stopPlayback()` → `streamedMusic.stop()`. `fadeOutMusic(steps, delay)` → if `streamedMusic.active()`, `streamedMusic.fadeOut(steps, delay)` (and do not fade SMPS music that isn't playing).
4. **Speed shoes.** `setSpeedShoes(boolean)` → also `streamedMusic.setSpeedShoes(on)`. (`setSpeedMultiplier` likewise maps `multiplier != 0` → on.)
5. **App pause.** `pause()`/`resume()` → `streamedMusic.setAppPaused(true/false)`.
6. **Mixing.** Extract a protected helper `void mixStreamedInto(short[] buffer, int length) { streamedMusic.mixInto(buffer, length); }` and call it in `fillBuffer(int)` after the existing music+sfx mix into the output buffer and before `hookUploadStreamBuffer` (match the buffer/length variables actually used at the 704–711 mix site; the call goes in BOTH presentation branches — the `currentStream` path and the deterministic-runtime `drainPcm` path at ~688 — since `mixInto` is additive into the final upload buffer either way). Do NOT add stream mixing inside `StreamBackedDeterministicAudioRuntime` itself — streams are excluded from the deterministic/capture path by design.
7. **Rewind exclusion (spec §4).** Do NOT touch `captureLogicalSnapshot`/`restoreLogicalSnapshot` state shape. Hook the reverse-presentation begin/end hooks the backend already receives (find the `AudioBackend` default methods invoked by `AudioManager.beginReverseAudioPresentation()`/`endReverseAudioPresentation()` — recon calls them "beginReversePresentation"-style defaults): begin → `streamedMusic.setRewindPaused(true)`; end → `setRewindPaused(false)`. In `restoreLogicalSnapshot(...)`: after the existing restore, reconcile the stream — if the restored current music id is overridden and the active stream key matches, keep it playing (resume in place); if it differs, route the restored id through step 1; if the restored state has no music, `streamedMusic.stop()`. This is the "re-issue only the current stream state" model; position is intentionally NOT rolled back (documented divergence).

- [ ] **Step 1: Read `AbstractSmpsAudioBackend.java`, `AudioBackend.java`, and the `HeadlessSmpsAudioBackend` subclass end-to-end.** Map the seven contract points above onto real method/field names. If jingle handling or fillBuffer differs from the recon anchors, follow the file, keep the contract.

- [ ] **Step 2: Write the failing test**

Use `HeadlessSmpsAudioBackend` (no OpenAL). Its only constructor is `HeadlessSmpsAudioBackend(SonicConfigurationService, PerformanceProfiler)` with a non-null config requirement (`AbstractSmpsAudioBackend` ~line 122); existing tests construct it as `new HeadlessSmpsAudioBackend(config, null)` (see `TestLiveRewindManagerAudioCleanup` ~line 131) with `SonicConfigurationService.createStandalone(tempDir)`.

```java
package com.openggf.audio.streamed;

import com.openggf.audio.HeadlessSmpsAudioBackend;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TestStreamedBackendIntegration {

    @TempDir
    Path tempDir;

    private HeadlessSmpsAudioBackend newBackend(int overriddenMusicId, short value) {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        HeadlessSmpsAudioBackend backend = new HeadlessSmpsAudioBackend(config, null);
        backend.setModMusicResolver(constantResolver(overriddenMusicId, value));
        return backend;
    }

    /** Resolver stub avoiding jar IO: one overridden id with constant PCM. */
    private static ModMusicResolver constantResolver(int musicId, short value) {
        short[] pcm = new short[9600]; // 4800 frames of constant signal
        java.util.Arrays.fill(pcm, value);
        StreamedTrackData data = new StreamedTrackData("test-track",
                new PcmData(pcm, 48000), true, 0, 1.0f, true);
        return new ModMusicResolver(Map.of()) {
            @Override
            public boolean overrides(int id) {
                return id == musicId;
            }

            @Override
            public Optional<StreamedTrackData> resolve(int id, int rate) {
                return id == musicId ? Optional.of(data) : Optional.empty();
            }
        };
    }

    @Test
    void overriddenMusicIdStartsStream_unknownIdDoesNot() {
        HeadlessSmpsAudioBackend backend = newBackend(0x8C, (short) 500);
        assertFalse(backend.tryStartStreamedMusic(0x99));
        assertTrue(backend.tryStartStreamedMusic(0x8C));
        assertTrue(backend.streamedMusicForTest().active());
    }

    @Test
    void replayOfSameTrackKeepsStreamPosition() {
        HeadlessSmpsAudioBackend backend = newBackend(0x8C, (short) 500);
        backend.tryStartStreamedMusic(0x8C);
        short[] buf = new short[512];
        backend.streamedMusicForTest().mixInto(buf, 512); // advance position
        assertTrue(backend.tryStartStreamedMusic(0x8C));  // rewind replay re-issue
        // Contract point 1 idempotence: same key + active -> not restarted.
        // Assert via the position-sensitive first sample after a re-issue: the
        // track is constant PCM, so instead assert the player instance kept
        // its track (currentKey unchanged) and remained active without a
        // restart flag — expose positionFramesForTest() on the player if
        // stronger assertion is wanted.
        assertEquals("test-track", backend.streamedMusicForTest().currentKey());
    }

    @Test
    void speedShoesAndPauseReachStreamedPlayer() {
        HeadlessSmpsAudioBackend backend = newBackend(0x8C, (short) 500);
        backend.tryStartStreamedMusic(0x8C);
        backend.setSpeedShoes(true);
        backend.pause();
        StreamedMusicPlayer player = backend.streamedMusicForTest();
        short[] buf = new short[4];
        player.mixInto(buf, 4);
        assertEquals(0, buf[0]); // app-paused: silent
        backend.resume();
        player.mixInto(buf, 4);
        assertNotEquals(0, buf[0]);
    }

    @Test
    void jinglePausesStreamAndRestoreResumesIt() {
        HeadlessSmpsAudioBackend backend = newBackend(0x8C, (short) 500);
        backend.tryStartStreamedMusic(0x8C);
        backend.pauseStreamForOverride(); // what playSmps' jingle branch calls
        short[] buf = new short[4];
        backend.streamedMusicForTest().mixInto(buf, 4);
        assertEquals(0, buf[0]); // ducked under the jingle
        backend.restoreMusic();  // jingle over -> resume the stream, not SMPS
        backend.streamedMusicForTest().mixInto(buf, 4);
        assertNotEquals(0, buf[0]);
        assertTrue(backend.streamedMusicForTest().active());
    }

    @Test
    void nonOverriddenSmpsMusicStopsTheStream() {
        HeadlessSmpsAudioBackend backend = newBackend(0x8C, (short) 500);
        backend.tryStartStreamedMusic(0x8C);
        assertFalse(backend.tryStartStreamedMusic(0x99)); // boss music: not overridden
        backend.stopStreamForSmpsMusic(); // what the non-jingle branch then calls
        assertFalse(backend.streamedMusicForTest().active());
    }

    @Test
    void mixStreamedIntoAddsSamplesToUploadBuffer() {
        HeadlessSmpsAudioBackend backend = newBackend(0x8C, (short) 500);
        backend.tryStartStreamedMusic(0x8C);
        short[] mix = new short[64];
        backend.mixStreamedInto(mix, 64);
        assertEquals(500, mix[0]);
    }

    @Test
    void stopPlaybackStopsStream() {
        HeadlessSmpsAudioBackend backend = newBackend(0x8C, (short) 500);
        backend.tryStartStreamedMusic(0x8C);
        backend.stopPlayback();
        assertFalse(backend.streamedMusicForTest().active());
    }
}
```

Notes for the implementer:
- `ModMusicResolver` is non-final with overridable `overrides`/`resolve` (Task 9) precisely for this stub.
- The test hooks must be **public** (the test lives in `com.openggf.audio.streamed`, the backend in `com.openggf.audio`): `public boolean tryStartStreamedMusic(int musicId)` (the real routing helper `playSmps` calls — contract point 1), `public StreamedMusicPlayer streamedMusicForTest()`, `public void pauseStreamForOverride()` (the real method the jingle branch calls — contract point 2), and the `protected`→`public` mixing helper `mixStreamedInto` (contract point 6). Production callers and the test share the same seams; nothing is test-only except `streamedMusicForTest()`.
- Contract point 7's snapshot-restore reconcile has no unit test here (constructing `AudioBackendLogicalSnapshot` fixtures is disproportionate); it is covered by Task 12 Step 5's manual rewind check — listed there explicitly.

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.audio.streamed.TestStreamedBackendIntegration" test`
Expected: COMPILE FAILURE.

- [ ] **Step 4: Implement the seven contract points** in `AbstractSmpsAudioBackend` + the `AudioBackend` default + the `AudioManager` pass-through:

`AudioBackend.java` addition:
```java
/**
 * Installs the mod music resolver for streamed overrides (Phase 1 mod
 * support). Default no-op: backends without streaming ignore mods.
 */
default void setModMusicResolver(com.openggf.audio.streamed.ModMusicResolver resolver) {
}
```

`AudioManager.java` addition — the resolver must be **sticky**: `Engine.initializeGame()` installs it and then `initializeGlobalGameplayServices()` replaces the backend (`setBackend(new LWJGLAudioBackend(...))`, Engine.java ~965), which would silently discard a pass-through-only setter. Store it and re-apply on every backend swap:
```java
private com.openggf.audio.streamed.ModMusicResolver modMusicResolver =
        com.openggf.audio.streamed.ModMusicResolver.EMPTY;

public void setModMusicResolver(com.openggf.audio.streamed.ModMusicResolver resolver) {
    this.modMusicResolver = resolver == null
            ? com.openggf.audio.streamed.ModMusicResolver.EMPTY : resolver;
    backend.setModMusicResolver(this.modMusicResolver);
}
```
And inside the existing `setBackend(AudioBackend)` (~line 212), after the field assignment:
```java
backend.setModMusicResolver(modMusicResolver);
```

`AbstractSmpsAudioBackend` core additions (adapt names to the file):
```java
private com.openggf.audio.streamed.ModMusicResolver modMusicResolver =
        com.openggf.audio.streamed.ModMusicResolver.EMPTY;
private final com.openggf.audio.streamed.StreamedMusicPlayer streamedMusic =
        new com.openggf.audio.streamed.StreamedMusicPlayer();

@Override
public void setModMusicResolver(com.openggf.audio.streamed.ModMusicResolver resolver) {
    this.modMusicResolver = resolver == null
            ? com.openggf.audio.streamed.ModMusicResolver.EMPTY : resolver;
}

private boolean streamPausedForOverride;
private int streamedMusicId = -1; // logical id of the active stream (contract point 2b)

/** Contract point 1. Returns true when the id was routed to a stream. */
public boolean tryStartStreamedMusic(int musicId) {
    if (!modMusicResolver.overrides(musicId)) {
        return false;
    }
    var track = modMusicResolver.resolve(musicId, outputSampleRate());
    if (track.isEmpty()) {
        return false;
    }
    streamPausedForOverride = false;
    streamedMusic.setOverridePaused(false);
    streamedMusicId = musicId;
    if (streamedMusic.active() && track.get().key().equals(streamedMusic.currentKey())) {
        return true; // same track already playing: resume in place, don't restart
    }
    streamedMusic.play(track.get());
    return true;
}

/** Contract point 2 (non-jingle SMPS start): the stream yields to real SMPS music. */
public void stopStreamForSmpsMusic() {
    streamedMusic.stop();
    streamPausedForOverride = false;
    streamedMusicId = -1;
}

/** Contract point 2: called from playSmps' override-jingle branch. */
public void pauseStreamForOverride() {
    if (streamedMusic.active()) {
        streamedMusic.setOverridePaused(true);
        streamPausedForOverride = true;
    }
}

/**
 * Contract point 2 resume intercept. Fires only when the LAST jingle ends
 * (musicStack empty — stacked jingles restore through doRestoreMusic's pop
 * while the stream stays paused). Stops the still-looping jingle SMPS
 * (null-guarded — headless tests never started one), restores the logical
 * current-music bookkeeping to the stream's id, and unpauses the stream.
 */
protected boolean resumeStreamFromOverride() {
    if (!streamPausedForOverride || !streamedMusic.active() || !musicStack.isEmpty()) {
        return false;
    }
    // (a) stop the jingle exactly as doRestoreMusic's stop phase does —
    // hookStopAndUnqueueAllMusicBuffers() / smpsDriver.stopAll() per the real
    // field names found in Step 1, each behind a null check.
    // (b) clear currentSmps/currentStream; set currentMusicId = streamedMusicId
    //     and refresh currentMusicDescriptor to match.
    streamedMusic.setOverridePaused(false);
    streamPausedForOverride = false;
    return true;
}

/** Contract point 6: additive stream mix into the final upload buffer. */
public void mixStreamedInto(short[] buffer, int length) {
    streamedMusic.mixInto(buffer, length);
}
```
Then wire contract points 2–7 at the sites found in Step 1: `pauseStreamForOverride()` in the jingle branch; `stopStreamForSmpsMusic()` in the non-jingle branch when `tryStartStreamedMusic` returned false; `if (resumeStreamFromOverride()) return;` at the head of `restoreMusic()` and (current-jingle case only) in `endMusicOverride(int)`; `mixStreamedInto(<real upload buffer>, <real length>)` in `fillBuffer` after the existing mix (both presentation branches); stop/fade/speed-shoes/pause/reverse-presentation forwarding.

- [ ] **Step 5: Run the new test plus the existing audio suite**

Run: `mvn "-Dtest=com.openggf.audio.streamed.TestStreamedBackendIntegration" test` then the existing audio suite with an explicit package glob: `mvn "-Dtest=com.openggf.audio.*Test,com.openggf.audio.Test*,com.openggf.audio.rewind.Test*,com.openggf.audio.runtime.Test*" test` (adjust to the class names actually present under `src/test/java/com/openggf/audio`; avoid the dotted `.**` Surefire form, which may match nothing).
Expected: new tests PASS; no regressions in existing audio tests (rewind audio tests especially — streamed code must be inert when the resolver is EMPTY).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/audio/AudioBackend.java src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java src/main/java/com/openggf/audio/AudioManager.java src/test/java/com/openggf/audio/streamed/TestStreamedBackendIntegration.java
git commit -m "feat: route mod-overridden music ids to streamed playback in SMPS backend

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 11: `ModManagerScreen`

**Files:**
- Create: `src/main/java/com/openggf/mods/ui/ModManagerScreen.java`
- Modify: `src/main/java/com/openggf/game/MasterTitleScreen.java`
- Test: `src/test/java/com/openggf/mods/ui/TestModManagerScreen.java`

**Interfaces:**
- Consumes: `ModCatalog` (Task 4); `PixelFont` rendering + `InputHandler` input, copying the sub-screen pattern of `TestModeTracePicker` (`com.openggf.testmode.TestModeTracePicker`) and its ownership wiring in `MasterTitleScreen.update()` (~line 317) / `draw()` (~428–501).
- Produces: `ModManagerScreen(ModCatalog catalog, PixelFont font)`; `enum Result { NONE, BACK }`; `void update(InputHandler input)`; `void render()`; `Result consumeResult()`. Controls: Up/Down cursor, Enter/menuAccept toggle, `[`/`]` (or PageUp/PageDown) move in order, Esc/menuBack → BACK (catalog persisted). Rows show name, version, ENABLED/disabled, and the block reason as an error badge; a banner line shows "mods disabled (test mode)" when `catalog.isForceDisabled()`. **Cascade confirm (spec §2 "disable prompt"):** disabling a mod whose `cascadePreview` is non-empty requires a second accept — the first press arms a confirm banner ("also disables: a, b — press again"), the second press (still on the same row) performs the toggle; moving the cursor disarms.

- [ ] **Step 1: Read `TestModeTracePicker.java` end-to-end** and the `MasterTitleScreen` sub-screen wiring for `tracePicker`/`launchConfigPanel`. Copy the structure (cursor + windowed scroll + mega-batch text rendering + `consumeResult()`), not the code verbatim.

- [ ] **Step 2: Write the failing test** (logic only — rendering is not unit-tested; keep `render()` free of logic):

```java
package com.openggf.mods.ui;

import com.openggf.mods.ModCatalog;
import com.openggf.mods.ModDescriptor;
import com.openggf.mods.ModManifest;
import com.openggf.mods.ModStateStore;
import com.openggf.mods.ModType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestModManagerScreen {

    private static ModCatalog catalog(Path tmp, String... ids) {
        List<ModDescriptor> descriptors = java.util.Arrays.stream(ids)
                .map(id -> ModDescriptor.ok(Path.of(id + ".jar"),
                        new ModManifest(id, id, "1.0.0", List.of(), null, "1",
                                ModType.PATCH, "s2", List.of(), Map.of()), false))
                .toList();
        return new ModCatalog(descriptors, new ModStateStore(tmp));
    }

    @Test
    void cursorMovesAndClamps(@TempDir Path tmp) {
        ModManagerScreen screen = new ModManagerScreen(catalog(tmp, "a", "b"), null);
        assertEquals(0, screen.cursorForTest());
        screen.moveCursor(1);
        assertEquals(1, screen.cursorForTest());
        screen.moveCursor(1);
        assertEquals(1, screen.cursorForTest());
        screen.moveCursor(-5);
        assertEquals(0, screen.cursorForTest());
    }

    @Test
    void acceptTogglesSelectedRow(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, "a");
        ModManagerScreen screen = new ModManagerScreen(c, null);
        screen.activateSelected();
        assertEquals(1, c.orderedEnabled().size());
        screen.activateSelected();
        assertTrue(c.orderedEnabled().isEmpty());
    }

    @Test
    void backRequestsExitAndPersists(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, "a");
        ModManagerScreen screen = new ModManagerScreen(c, null);
        screen.activateSelected();
        screen.requestBack();
        assertEquals(ModManagerScreen.Result.BACK, screen.consumeResult());
        assertEquals(ModManagerScreen.Result.NONE, screen.consumeResult());
        // persisted: a fresh catalog over the same store sees the toggle
        assertEquals(1, catalog(tmp, "a").orderedEnabled().size());
    }

    @Test
    void reorderDelegatesToCatalog(@TempDir Path tmp) {
        ModCatalog c = catalog(tmp, "a", "b");
        ModManagerScreen screen = new ModManagerScreen(c, null);
        screen.moveCursor(1); // select "b"
        screen.moveSelected(-1);
        assertEquals("b", c.rows().get(0).descriptor().displayId());
        assertEquals(0, screen.cursorForTest()); // cursor follows the row
    }

    @Test
    void disablingWithDependentsNeedsSecondPress(@TempDir Path tmp) {
        ModDescriptor base = ModDescriptor.ok(Path.of("base.jar"),
                new ModManifest("base", "base", "1.0.0", List.of(), null, "1",
                        ModType.PATCH, "s2", List.of(), Map.of()), false);
        ModDescriptor addon = ModDescriptor.ok(Path.of("addon.jar"),
                new ModManifest("addon", "addon", "1.0.0", List.of(), null, "1",
                        ModType.PATCH, "s2",
                        List.of(new com.openggf.mods.ModDependency("base", null)), Map.of()), false);
        ModCatalog c = new ModCatalog(List.of(base, addon), new ModStateStore(tmp));
        c.toggle("base");
        c.toggle("addon");
        ModManagerScreen screen = new ModManagerScreen(c, null); // cursor on "base"
        screen.activateSelected(); // arms the confirm, does NOT toggle
        assertEquals("base", screen.pendingCascadeConfirmIdForTest());
        assertEquals(2, c.orderedEnabled().size());
        screen.activateSelected(); // second press performs the cascade
        assertTrue(c.orderedEnabled().isEmpty());
        assertNull(screen.pendingCascadeConfirmIdForTest());
    }

    @Test
    void movingCursorDisarmsCascadeConfirm(@TempDir Path tmp) {
        ModDescriptor base = ModDescriptor.ok(Path.of("base.jar"),
                new ModManifest("base", "base", "1.0.0", List.of(), null, "1",
                        ModType.PATCH, "s2", List.of(), Map.of()), false);
        ModDescriptor addon = ModDescriptor.ok(Path.of("addon.jar"),
                new ModManifest("addon", "addon", "1.0.0", List.of(), null, "1",
                        ModType.PATCH, "s2",
                        List.of(new com.openggf.mods.ModDependency("base", null)), Map.of()), false);
        ModCatalog c = new ModCatalog(List.of(base, addon), new ModStateStore(tmp));
        c.toggle("base");
        c.toggle("addon");
        ModManagerScreen screen = new ModManagerScreen(c, null); // cursor on "base"
        screen.activateSelected(); // arms
        assertEquals("base", screen.pendingCascadeConfirmIdForTest());
        screen.moveCursor(1);      // moving disarms
        assertNull(screen.pendingCascadeConfirmIdForTest());
        assertEquals(2, c.orderedEnabled().size()); // nothing toggled
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.mods.ui.TestModManagerScreen" test`
Expected: COMPILE FAILURE.

- [ ] **Step 4: Implement `ModManagerScreen`.** Structure (input parsing in `update`, logic in the package-visible helpers the test drives, drawing in `render`):

```java
package com.openggf.mods.ui;

import com.openggf.mods.ModCatalog;
// PixelFont + InputHandler imports per the real packages found in Step 1.

public final class ModManagerScreen {

    public enum Result { NONE, BACK }

    private final ModCatalog catalog;
    private final Object font; // real type: PixelFont (null allowed in unit tests)
    private int cursor;
    private int firstVisible;
    private Result pendingResult = Result.NONE;
    private String pendingCascadeConfirmId;

    public ModManagerScreen(ModCatalog catalog, /* PixelFont */ Object font) {
        this.catalog = catalog;
        this.font = font;
    }

    public void update(/* InputHandler */ Object input) {
        // Per Step 1's read: logical().menuUp()/menuDown()/menuAccept()/menuBack()
        // plus raw UP/DOWN/ENTER/ESCAPE and [ ] for reorder, each edge-triggered,
        // dispatching to moveCursor / activateSelected / moveSelected / requestBack.
    }

    public void render() {
        // PixelFont mega-batch: title, force-disabled banner when
        // catalog.isForceDisabled(), one row per catalog.rows() entry with
        // "> " cursor prefix, [ON]/[off], and blockReason badge; footer with
        // key hints. Windowed by firstVisible like TestModeTracePicker.
    }

    public Result consumeResult() {
        Result r = pendingResult;
        pendingResult = Result.NONE;
        return r;
    }

    void moveCursor(int delta) {
        int max = Math.max(0, catalog.rows().size() - 1);
        cursor = Math.max(0, Math.min(max, cursor + delta));
        pendingCascadeConfirmId = null; // moving disarms the confirm
    }

    void activateSelected() {
        var rows = catalog.rows();
        if (rows.isEmpty()) {
            return;
        }
        var row = rows.get(cursor);
        String id = row.descriptor().displayId();
        // Spec section 2 "cascades a disable prompt": disabling with enabled
        // dependents needs a second press on the same row.
        if (row.enabled() && !catalog.cascadePreview(id).isEmpty()
                && !id.equals(pendingCascadeConfirmId)) {
            pendingCascadeConfirmId = id; // render() shows the confirm banner
            return;
        }
        pendingCascadeConfirmId = null;
        catalog.toggle(id);
    }

    String pendingCascadeConfirmIdForTest() {
        return pendingCascadeConfirmId;
    }

    void moveSelected(int delta) {
        var rows = catalog.rows();
        if (!rows.isEmpty() && catalog.move(rows.get(cursor).descriptor().displayId(), delta)) {
            moveCursor(delta);
        }
    }

    void requestBack() {
        catalog.persist();
        pendingResult = Result.BACK;
    }

    int cursorForTest() {
        return cursor;
    }
}
```
Replace the `Object` placeholders with the real `PixelFont`/`InputHandler` types and fill `update`/`render` following `TestModeTracePicker`. The unit test passes `null` for the font and never calls `update`/`render`, so rendering types must not be touched in the logic helpers.

- [ ] **Step 5: Wire into `MasterTitleScreen`.** Add a nullable `ModManagerScreen modManagerScreen` field plus a `ModCatalog` handle (constructor param or setter — follow how `MasterTitleScreen` receives other collaborators; Engine passes it in Task 12). In `update()`'s ACTIVE block, open on the `M` key (`isKeyPressedWithoutModifiers(GLFW_KEY_M)`); while open, delegate `update`/`draw` exactly like `tracePicker` (early-return blocks at ~317–362 / ~428–501) and null the field on `Result.BACK`. Add "M: MODS" to the on-screen hint text where the other key hints are drawn. If the catalog is null (Engine didn't provide one), the key does nothing.

- [ ] **Step 6: Extend the ArchUnit top-level-edge ratchet — THREE new edges.** `MasterTitleScreen` (slice `game`, core cluster) now references `ModManagerScreen`/`ModCatalog` (slice `mods`), and `ModManagerScreen` itself uses `PixelFont` (`com.openggf.graphics`) and `InputHandler` (`com.openggf.control`) — both core-cluster slices. Add `"game -> mods"`, `"mods -> graphics"`, and `"mods -> control"` to `CORE_RUNTIME_TOP_LEVEL_DEPENDENCY_EDGES` in `src/test/java/com/openggf/tests/TestArchUnitRules.java` (same list Task 9 Step 5 touched), with a comment citing this plan. Run: `mvn "-Dtest=com.openggf.tests.TestArchUnitRules" test` — expected PASS; if it still fails, add exactly the edges it names (the rule's failure message lists them).

- [ ] **Step 7: Run tests**

Run: `mvn "-Dtest=com.openggf.mods.ui.TestModManagerScreen" test`
Expected: all 6 tests PASS. Then `mvn package` to confirm `MasterTitleScreen` compiles.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/mods/ui/ModManagerScreen.java src/main/java/com/openggf/game/MasterTitleScreen.java src/test/java/com/openggf/mods/ui/TestModManagerScreen.java src/test/java/com/openggf/tests/TestArchUnitRules.java
git commit -m "feat: mod manager screen on master title

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 12: Engine wiring — startup scan, per-launch resolver, trace/test force-disable

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`
- Test: `src/test/java/com/openggf/mods/TestModEngineWiringSeams.java` (seam-level; full Engine boot is not unit-testable)

**Interfaces:**
- Consumes: `ModCatalog.load(Path)`, `setForceDisabled`, `ModAudioIntegration.buildResolver`, `AudioManager.setModMusicResolver` (Tasks 4/9/10); recon anchors: `Engine.init()` boot-screen branch (~line 446), `initializeGame()` (~704), config flag `SonicConfiguration.TEST_MODE_ENABLED`, `TraceSessionLauncher.active()`, master-title construction sites (initial + `returnToMasterTitleScreen()` ~844).
- Produces: an `Engine`-owned `ModCatalog modCatalog` field; a package-visible static seam `static ModMusicResolver resolveModMusicForLaunch(ModCatalog catalog, String gameId, boolean traceSessionActive)` in `ModAudioIntegration` so the gating logic is unit-testable without booting Engine.

- [ ] **Step 1: Write the failing test** (the gate logic lives in `ModAudioIntegration` so it is testable):

```java
package com.openggf.mods;

import com.openggf.audio.streamed.ModMusicResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestModEngineWiringSeams {

    @Test
    void traceSessionForcesEmptyResolver(@TempDir Path tmp) {
        ModCatalog catalog = ModCatalog.load(tmp);
        ModMusicResolver r = ModAudioIntegration.resolveModMusicForLaunch(catalog, "s2", true);
        assertSame(ModMusicResolver.EMPTY, r);
    }

    @Test
    void nullCatalogYieldsEmptyResolver() {
        assertSame(ModMusicResolver.EMPTY,
                ModAudioIntegration.resolveModMusicForLaunch(null, "s2", false));
    }

    @Test
    void normalLaunchBuildsFromCatalog(@TempDir Path tmp) {
        ModCatalog catalog = ModCatalog.load(tmp); // empty mods dir → EMPTY resolver
        assertSame(ModMusicResolver.EMPTY,
                ModAudioIntegration.resolveModMusicForLaunch(catalog, "s2", false));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.mods.TestModEngineWiringSeams" test`
Expected: COMPILE FAILURE (`resolveModMusicForLaunch` missing).

- [ ] **Step 3: Implement.** Add to `ModAudioIntegration`:

```java
/**
 * Launch-time gate (spec section 7): trace sessions and absent catalogs get
 * the EMPTY resolver so replays stay byte-deterministic with mods installed.
 */
public static ModMusicResolver resolveModMusicForLaunch(
        ModCatalog catalog, String gameId, boolean traceSessionActive) {
    if (catalog == null || traceSessionActive) {
        return ModMusicResolver.EMPTY;
    }
    return buildResolver(catalog, gameId);
}
```

Engine wiring (adapt to the real code at the anchors; read each site first):

1. In `Engine.init()` just before the boot-screen branch (~446):
```java
modCatalog = ModCatalog.load(Path.of(System.getProperty("user.dir")).resolve("mods"));
if (configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)) {
    modCatalog.setForceDisabled(true);
}
```
2. Pass `modCatalog` to every `new MasterTitleScreen(...)` site — there are **four**: initial boot in `init()`, `exitLegalDisclaimer()`, `returnToMasterTitleScreen()` (~844), and `showStartupRomError` (~743) — matching the constructor/setter chosen in Task 11. (Grep for `new MasterTitleScreen` to be sure none were added since.)
3. In `initializeGame()` after `romDetectionService.detectAndCreateModule(rom)` resolves the game id (use the same id string the master title uses, e.g. `"s1"/"s2"/"s3k"` — read how the detected module/game id is exposed there):
```java
audioManager.setModMusicResolver(ModAudioIntegration.resolveModMusicForLaunch(
        modCatalog, detectedGameId, TraceSessionLauncher.active() != null));
```
(Use the actual `AudioManager` handle field Engine already caches.)

- [ ] **Step 4: Run tests + full build**

Run: `mvn "-Dtest=com.openggf.mods.TestModEngineWiringSeams" test` then `mvn package`
Expected: tests PASS; package succeeds.

- [ ] **Step 5: Manual smoke (requires a ROM + a real speaker check).** Build a throwaway music pack:
  - Make `mods/test-pack.jar` containing `META-INF/openggf-mod.yaml` (id `test-pack`, type patch, baseGame s2, `audioOverrides: {"0x82": main}` — 0x82 is EHZ music; verify the id in `Sonic2Music`), `audio-manifest.yaml` (track `main`, an ogg), and the ogg file. (`jar cf mods/test-pack.jar -C pack-src .`)
  - Run the engine, open mod manager with M on master title, enable the pack, launch S2 EHZ: the ogg plays, loops, ducks for the invincibility jingle, resumes after, rate-shifts with speed shoes.
  - **Rewind (Task 10 contract point 7's only end-to-end check):** hold rewind — the stream pauses; release — it resumes in place; seek far back and resume — the stream continues (or restarts if the restored music id differs) without SMPS music double-playing.
  - Disable the pack: EHZ SMPS music is back.
  - Record what was verified in the PR/commit body.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/Engine.java src/main/java/com/openggf/mods/ModAudioIntegration.java src/test/java/com/openggf/mods/TestModEngineWiringSeams.java
git commit -m "feat: engine startup mod scan and per-launch streamed-music wiring

Changelog: n/a: covered by final phase-1 changelog entry in this branch
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 13: Docs, changelog, discrepancies, full suite

**Files:**
- Create: `docs/modding/music-packs.md`
- Modify: `CHANGELOG.md`, `docs/status/known-discrepancies.md`, `CLAUDE.md` + `AGENTS.md` (one short "Mod support (Phase 1)" pointer paragraph each)

**Interfaces:** none (documentation).

- [ ] **Step 1: Write `docs/modding/music-packs.md`** — creator-facing: mod jar layout, full `openggf-mod.yaml` and `audio-manifest.yaml` field reference (from Tasks 1/5), wav/ogg constraints (16-bit wav; no mp3 + why), loop-point semantics, how to find music ids (`game.sonicN.audio` constants, e.g. `Sonic2Music`), `jar cf` packaging example, mod manager usage (M on master title), and the documented divergences (fixed 1.25 speed-shoes rate; streams pause/resume across rewind instead of replaying; streamed audio absent from trace/capture runs).

- [ ] **Step 2: Update `CHANGELOG.md`** (one entry: drop-in music-pack mods — mods/ folder, mod manager screen, streamed wav/ogg layer). **Update `docs/status/known-discrepancies.md`**: add the three mod-audio divergences above. **Update `CLAUDE.md` and `AGENTS.md`**: a short paragraph pointing at `com.openggf.mods`, `com.openggf.audio.streamed`, the spec, and `docs/modding/music-packs.md`.

- [ ] **Step 3: Run the full default suite**

Run: `mvn test`
Expected: green (same pre-existing failures as `develop` baseline at branch point, none new). Also re-run the S3K must-keep-green set if audio files changed: `mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test`.

- [ ] **Step 4: Commit** (docs commit carries the real trailer attestations for the whole branch):

```bash
git add docs/modding/music-packs.md CHANGELOG.md docs/status/known-discrepancies.md CLAUDE.md AGENTS.md
git commit -m "docs: music-pack modding guide, changelog, and discrepancy notes

Changelog: updated
Guide: n/a
Known-Discrepancies: updated
S3K-Known-Discrepancies: n/a
Agent-Docs: updated
Configuration-Docs: n/a
Skills: n/a"
```

---

## Execution notes

- Tasks 1–9 are pure new code and independently committable; Tasks 10–12 touch live engine files — re-read targets before editing (shared repo, concurrent sessions).
- If `HeadlessSmpsAudioBackend` construction proves heavier than expected in Task 10, testing `tryStartStreamedMusic` + the player hooks through a minimal subclass of `AbstractSmpsAudioBackend` is acceptable; keep the three behavioral assertions.
- Merge flow at the end: `superpowers:finishing-a-development-branch`; merging to `develop` requires the README release-log note per repo policy.
