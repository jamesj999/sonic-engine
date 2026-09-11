# GamePatch Framework + Knuckles in Sonic 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reusable `GamePatch` framework (code overlay over a base `GameModule`) and ship its first consumer: a faithful, playable Knuckles in Sonic 2 core slice driven by the s2disasm `knuckles-in-sonic-2` branch diffs.

**Architecture:** A `GamePatch` wraps the base `GameModule` via an explicit `DelegatingGameModule`; `GamePatchRegistry` resolves patches at the module-construction choke points (before `SessionManager.openGameplaySession`). KiS2 overrides physics + player art + icon art, reading Knuckles data from the logical S&K ROM (first 2MB of the combined S3K image). `SessionManager` stays patch-agnostic.

**Tech Stack:** Java 21, Maven, JUnit 5 (Jupiter only). Spec: `docs/architecture/designs/2026-06-12-game-patch-kis2-design.md`.

---

## Ground rules for the executor

- **Branch:** `feature/ai-game-patch-kis2` off `develop`. This repo's working tree is shared by concurrent agent sessions: NEVER `git add -A` / `git add .`; stage only the files you created/modified for the current task.
- **Hooks:** run `git config core.hooksPath .githooks` once before the first commit.
- **Commit trailers:** every commit message must end with the 7-trailer block (see commit steps; the `prepare-commit-msg` hook appends an empty block if you forget — fill it in, don't delete it). `feat`/`fix` commits touching `src/main/` must either stage `CHANGELOG.md` with `Changelog: updated`, or give a reason: `Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task`. This plan uses the latter for intermediate commits and updates `CHANGELOG.md` in Task 14.
- **Test invocation (PowerShell):** quote `-D` properties. Focused run:
  `mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.patch.TestGamePatchRegistry" "-DfailIfNoTests=false"`
  Ignore unrelated `lwjgl`/`glfw UnsatisfiedLinkError` noise and `TestBundledConfigResource` flakes; your test's own PASS/FAIL lines are what matter.
- **ROMs:** ROM-gated tests need `Sonic The Hedgehog 2 (W) (REV01) [!].gen` and `Sonic and Knuckles & Sonic 3 (W) [!].gen` in the working directory. Follow the `TestRomLogic` pattern: skip (JUnit 5 `Assumptions.assumeTrue`) when absent.
- **JUnit 5 only.** No `org.junit.*` (JUnit 4) imports.

---

### Task 1: KiS2 branch diff catalogue

Everything downstream cites this document. The s2disasm checkout at `docs/s2disasm` already has the branch as `origin/knuckles-in-sonic-2`.

**Files:**
- Create: `docs/kis2/BRANCH_DIFFS.md`

- [ ] **Step 1: Enumerate the diff**

```bash
cd docs/s2disasm
git diff --stat master origin/knuckles-in-sonic-2
git diff master origin/knuckles-in-sonic-2 -- s2.asm > /tmp/kis2-s2asm.diff
wc -l /tmp/kis2-s2asm.diff
```

Also diff any non-`s2.asm` files the `--stat` shows (constants, art/mapping binaries, build files).

- [ ] **Step 2: Read and classify the diff**

Read `/tmp/kis2-s2asm.diff` in chunks. For each hunk, classify into one of:
`physics-constants`, `player-object-code` (glide/climb/ability dispatch), `art-mapping-dplc-addresses`, `object-placements`, `monitor-life-icon`, `title-level-select` (deferred), `special-stage` (deferred), `two-player` (deferred), `misc`.

Specifically answer (these feed Tasks 5, 9, 10, 11):
1. Knuckles jump velocity and any other movement constant that differs from stock S2 Sonic (`move.w #$680` vs `#$600` sites and friends).
2. Whether KiS2's ability-dispatch / jump-height code (`Sonic_JumpHeight` area) behaves like stock S2 or like S&K (this decides whether `PhysicsFeatureSet.SONIC_2` can be reused verbatim — see Task 5 Step 2).
3. The S&K-cart addresses the patch reads Knuckles art/mappings/DPLCs/animation scripts from (KiS2 references the locked-on S&K cart; record every address with its label). Cross-check against the S&K-side constants already verified for S3K: `ART_UNC_KNUCKLES_ADDR=0x1200E0`, `MAP_KNUCKLES_ADDR=0x14A8D6`, `DPLC_KNUCKLES_ADDR=0x14BD0A`, `KNUCKLES_ANIM_DATA_ADDR=0x017EF4` (`Sonic3kConstants.java:702-718`). Note agreement or divergence explicitly.
4. The Knuckles life-icon art and 1-up monitor face art addresses (S&K side) and how the patch swaps them in.
5. Any changed start positions or object placements (search the diff for `ObjPos`, `StartLoc`, layout binaries). If none, write "None found" — Task 12 then becomes a no-op.
6. No-Tails enforcement: how the patch removes Tails (options removed, Player_mode forced, etc.) — documented for fidelity context.

- [ ] **Step 3: Write `docs/kis2/BRANCH_DIFFS.md`**

Structure: one `##` section per category above; each entry gets: disasm location (file:line on the branch), the stock-S2 behavior, the KiS2 behavior, and (for data) the ROM address. Open with a provenance header recording the two commit SHAs compared (`git rev-parse master origin/knuckles-in-sonic-2`).

- [ ] **Step 4: Commit**

```bash
git add docs/kis2/BRANCH_DIFFS.md
git commit -m "docs(kis2): add Knuckles-in-Sonic-2 branch diff catalogue

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 2: GamePatch contracts + DelegatingGameModule + guard test

**Files:**
- Create: `src/main/java/com/openggf/game/patch/GamePatch.java`
- Create: `src/main/java/com/openggf/game/patch/PatchContext.java`
- Create: `src/main/java/com/openggf/game/patch/GameplayLaunchRequest.java`
- Create: `src/main/java/com/openggf/game/patch/LogicalRom.java`
- Create: `src/main/java/com/openggf/game/patch/DelegatingGameModule.java`
- Test: `src/test/java/com/openggf/game/patch/TestDelegatingGameModuleCoversInterface.java`

- [ ] **Step 1: Write the contracts** (plain interfaces/records — no test needed beyond compilation; the guard test below is this task's TDD driver)

```java
// LogicalRom.java
package com.openggf.game.patch;

/**
 * Logical ROM identity a patch can require, independent of which physical
 * ROM image supplies the bytes. SK is served from a standalone S&K image or
 * from the first 2MB (0x000000-0x1FFFFF) of the combined S&K+S3 ROM.
 */
public enum LogicalRom {
    SK
}
```

```java
// GameplayLaunchRequest.java
package com.openggf.game.patch;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;

import java.util.List;
import java.util.Objects;

/**
 * What the player asked to launch: base game + team. Derived from the
 * sanitized launch profile (written to config by LaunchProfileApplier) or
 * from a data-select slot team. Consumed by GamePatchRegistry.resolveModule.
 */
public record GameplayLaunchRequest(String gameId, String mainCharacter, List<String> sidekicks) {

    public GameplayLaunchRequest {
        Objects.requireNonNull(gameId, "gameId");
        mainCharacter = mainCharacter == null || mainCharacter.isBlank()
                ? "sonic" : mainCharacter.trim().toLowerCase(java.util.Locale.ROOT);
        sidekicks = sidekicks == null ? List.of() : List.copyOf(sidekicks);
    }

    /** Builds a request from the live config (the post-LaunchProfileApplier state). */
    public static GameplayLaunchRequest fromConfig(SonicConfigurationService configService, String gameId) {
        String main = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        String sidekickCsv = configService.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        List<String> sidekicks = sidekickCsv == null || sidekickCsv.isBlank() || "none".equalsIgnoreCase(sidekickCsv.trim())
                ? List.of()
                : java.util.Arrays.stream(sidekickCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new GameplayLaunchRequest(gameId, main, sidekicks);
    }
}
```

(If `SonicConfiguration.SIDEKICK_CHARACTER_CODE` has a different constant name, use the one `ActiveGameplayTeamResolver.resolveSidekicks` reads — check that class and keep the parsing consistent with it.)

```java
// PatchContext.java
package com.openggf.game.patch;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.RomByteReader;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Function;

/**
 * Explicit dependencies handed to GamePatch.apply(). Patches never perform
 * global lookups; everything they need arrives here.
 */
public final class PatchContext {

    @FunctionalInterface
    public interface LogicalRomSource {
        RomByteReader open(LogicalRom rom) throws IOException;
    }

    private final LogicalRomSource logicalRoms;
    private final SonicConfigurationService configService;

    public PatchContext(LogicalRomSource logicalRoms, SonicConfigurationService configService) {
        this.logicalRoms = Objects.requireNonNull(logicalRoms, "logicalRoms");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    /** Opens a reader for a logical ROM the patch declared in romPrerequisites(). */
    public RomByteReader openLogicalRom(LogicalRom rom) throws IOException {
        return logicalRoms.open(rom);
    }

    public SonicConfigurationService configService() {
        return configService;
    }
}
```

```java
// GamePatch.java
package com.openggf.game.patch;

import com.openggf.game.GameModule;

import java.util.List;
import java.util.Set;

/**
 * A code overlay over a base GameModule — the engine's model of an official
 * ROM-hack/lock-on variant (first consumer: Knuckles in Sonic 2).
 *
 * <p>Patches are resolved once, at the module-construction choke points,
 * before SessionManager.openGameplaySession(). They never toggle mid-session
 * and never stack (one patch per session).
 */
public interface GamePatch {

    /** Stable identifier, e.g. "kis2". */
    String id();

    /** Player-facing name, e.g. "Knuckles in Sonic 2". */
    String displayName();

    /** Game id of the base game this patch applies to, e.g. "s2". */
    String baseGameId();

    /** Logical ROMs that must be resolvable for this patch to activate. */
    Set<LogicalRom> romPrerequisites();

    /**
     * Main characters this patch can back for the base game, used to extend
     * launch-screen availability (e.g. ["knuckles"] for KiS2). Only consulted
     * when romPrerequisites() are satisfied.
     */
    List<String> providedMainCharacters();

    /** Whether this patch activates for the given launch request. */
    boolean activatesFor(GameplayLaunchRequest request);

    /** Wraps the base module. Called only when activatesFor() returned true. */
    GameModule apply(GameModule base, PatchContext ctx);
}
```

- [ ] **Step 2: Write the failing reflection guard test**

```java
// TestDelegatingGameModuleCoversInterface.java
package com.openggf.game.patch;

import com.openggf.game.GameModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DelegatingGameModule must declare (forward) EVERY GameModule method,
 * including default methods. Without this, a method later added to
 * GameModule with a default body would silently bypass the wrapped base
 * module and diverge patched-module behavior.
 */
class TestDelegatingGameModuleCoversInterface {

    @Test
    void delegatingGameModuleDeclaresEveryGameModuleMethod() {
        List<String> missing = new ArrayList<>();
        for (Method m : GameModule.class.getMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            try {
                DelegatingGameModule.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(m.getName() + Arrays.toString(m.getParameterTypes()));
            }
        }
        assertTrue(missing.isEmpty(),
                "DelegatingGameModule must forward these GameModule methods to the base module:\n"
                        + String.join("\n", missing));
    }
}
```

- [ ] **Step 3: Run the guard test — expect FAIL** (class doesn't exist yet; create the skeleton below, then the test fails listing every unforwarded method)

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.patch.TestDelegatingGameModuleCoversInterface" "-DfailIfNoTests=false"
```

- [ ] **Step 4: Implement `DelegatingGameModule`**

Skeleton:

```java
// DelegatingGameModule.java
package com.openggf.game.patch;

import com.openggf.game.GameModule;

import java.util.Objects;

/**
 * Forwards every GameModule method to a wrapped base module. Patches extend
 * this and override only the surfaces they change. Completeness is enforced
 * by TestDelegatingGameModuleCoversInterface — when GameModule grows a
 * method, that test fails until the forwarder is added here.
 */
public class DelegatingGameModule implements GameModule {

    private final GameModule base;
    private final String patchId;

    protected DelegatingGameModule(GameModule base, String patchId) {
        this.base = Objects.requireNonNull(base, "base");
        this.patchId = Objects.requireNonNull(patchId, "patchId");
    }

    /** The unpatched module this wrapper delegates to. */
    public final GameModule base() {
        return base;
    }

    /** Identifier of the GamePatch that produced this module. */
    public final String patchId() {
        return patchId;
    }

    // --- forwarders: one per GameModule method, all of the form: ---
    // @Override
    // public X getY(args) { return base.getY(args); }
}
```

Then mechanically add a forwarder for **every** method the guard test lists (the failure message is your worklist — roughly 100 one-liners; an IDE "implement methods by delegation" action or scripted generation from the test output is fine). Each forwarder is exactly `return base.method(args);` (or `base.method(args);` for void).

- [ ] **Step 5: Run the guard test — expect PASS**

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.patch.TestDelegatingGameModuleCoversInterface" "-DfailIfNoTests=false"
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/patch/ src/test/java/com/openggf/game/patch/TestDelegatingGameModuleCoversInterface.java
git commit -m "feat(patch): add GamePatch contracts and DelegatingGameModule with coverage guard

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 3: Logical ROM resolver (combined-ROM backend + bounds guard)

**Files:**
- Create: `src/main/java/com/openggf/game/patch/LogicalRomResolver.java`
- Modify: `src/main/java/com/openggf/data/RomByteReader.java` (add `fromBytes` factory if absent)
- Test: `src/test/java/com/openggf/game/patch/TestLogicalRomResolver.java`

- [ ] **Step 1: Check `RomByteReader` construction** — open `src/main/java/com/openggf/data/RomByteReader.java`. If there is no public way to build a reader from a raw `byte[]`, add:

```java
/** Builds a reader over the given bytes (used for logical-ROM windows and tests). */
public static RomByteReader fromBytes(byte[] data) {
    return new RomByteReader(data);
}
```

(matching however `fromRom` constructs the instance — make the constructor it uses accessible to this factory).

- [ ] **Step 2: Write the failing tests**

```java
// TestLogicalRomResolver.java
package com.openggf.game.patch;

import com.openggf.data.RomByteReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLogicalRomResolver {

    /** Synthetic 4MB "combined" image: SK half = 0x11, S3 half = 0x22. */
    private static byte[] syntheticCombined() {
        byte[] data = new byte[0x400000];
        java.util.Arrays.fill(data, 0, 0x200000, (byte) 0x11);
        java.util.Arrays.fill(data, 0x200000, 0x400000, (byte) 0x22);
        return data;
    }

    @Test
    void skWindowOverCombinedRomServesSkHalfOnly() throws IOException {
        RomByteReader sk = LogicalRomResolver.windowSkFromCombined(syntheticCombined());
        assertEquals(0x200000, sk.size());
        assertEquals(0x11, sk.readU8(0x000000));
        assertEquals(0x11, sk.readU8(0x1FFFFF));
    }

    @Test
    void skWindowBoundsGuardRejectsS3HalfReads() throws IOException {
        RomByteReader sk = LogicalRomResolver.windowSkFromCombined(syntheticCombined());
        assertThrows(Exception.class, () -> sk.readU8(0x200000));
    }

    @Test
    void combinedImageSmallerThanSkHalfIsRejected() {
        assertThrows(IOException.class,
                () -> LogicalRomResolver.windowSkFromCombined(new byte[0x100000]));
    }

    @Test
    void availabilityFalseWhenNoSourceConfigured() {
        LogicalRomResolver resolver = new LogicalRomResolver(() -> null);
        assertFalse(resolver.isAvailable(LogicalRom.SK));
    }

    @Test
    void availabilityTrueWhenCombinedSourcePresent() {
        LogicalRomResolver resolver = new LogicalRomResolver(TestLogicalRomResolver::syntheticCombined);
        assertTrue(resolver.isAvailable(LogicalRom.SK));
        assertEquals(0x200000, resolver.openOrThrow(LogicalRom.SK).size());
    }
}
```

- [ ] **Step 3: Run tests — expect FAIL** (`LogicalRomResolver` not found)

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.patch.TestLogicalRomResolver" "-DfailIfNoTests=false"
```

- [ ] **Step 4: Implement `LogicalRomResolver`**

```java
// LogicalRomResolver.java
package com.openggf.game.patch;

import com.openggf.data.RomByteReader;

import java.io.IOException;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Maps a LogicalRom identity to a byte source from whatever physical ROM
 * images are present. v1: LogicalRom.SK is served from the combined S&K+S3
 * ROM — the S&K cart occupies 0x000000-0x1FFFFF, so S&K-cart-relative
 * addresses equal combined-ROM addresses there. The window enforces a hard
 * bounds guard: a logical S&K read at >= 0x200000 would be reading the S3
 * half and is an error, never silently allowed.
 */
public final class LogicalRomResolver {

    private static final Logger LOGGER = Logger.getLogger(LogicalRomResolver.class.getName());
    static final int SK_CART_SIZE = 0x200000;

    /** Supplies the combined-ROM bytes, or null when no combined ROM is available. */
    @FunctionalInterface
    public interface CombinedRomBytesSource {
        byte[] get();
    }

    private final CombinedRomBytesSource combinedRomBytes;

    public LogicalRomResolver(CombinedRomBytesSource combinedRomBytes) {
        this.combinedRomBytes = combinedRomBytes;
    }

    public boolean isAvailable(LogicalRom rom) {
        byte[] data = combinedRomBytes.get();
        return data != null && data.length >= SK_CART_SIZE;
    }

    public RomByteReader openOrThrow(LogicalRom rom) {
        byte[] data = combinedRomBytes.get();
        if (data == null) {
            throw new IllegalStateException("No physical ROM available for logical ROM " + rom);
        }
        try {
            return windowSkFromCombined(data);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to window logical ROM " + rom, e);
        }
    }

    // public: also used by ROM-gated KiS2 art tests in com.openggf.game.sonic2.kis2
    public static RomByteReader windowSkFromCombined(byte[] combined) throws IOException {
        if (combined.length < SK_CART_SIZE) {
            throw new IOException("Combined ROM smaller than the S&K cart (need >= 0x200000 bytes, got 0x"
                    + Integer.toHexString(combined.length) + ")");
        }
        return RomByteReader.fromBytes(java.util.Arrays.copyOf(combined, SK_CART_SIZE));
    }
}
```

(The 2MB copy happens once per patch activation, at session open — acceptable. Wiring the production `CombinedRomBytesSource` to `RomManager.getSecondaryRom("s3k")` happens in Task 4.)

- [ ] **Step 5: Run tests — expect PASS**, then commit

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.patch.TestLogicalRomResolver" "-DfailIfNoTests=false"
git add src/main/java/com/openggf/game/patch/LogicalRomResolver.java src/main/java/com/openggf/data/RomByteReader.java src/test/java/com/openggf/game/patch/TestLogicalRomResolver.java
git commit -m "feat(patch): add logical ROM resolver with S&K combined-ROM window and bounds guard

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 4: GamePatchRegistry

**Files:**
- Create: `src/main/java/com/openggf/game/patch/GamePatchRegistry.java`
- Test: `src/test/java/com/openggf/game/patch/TestGamePatchRegistry.java`

- [ ] **Step 1: Write the failing tests** (use an in-test fake patch; no ROM needed)

```java
// TestGamePatchRegistry.java
package com.openggf.game.patch;

import com.openggf.game.GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestGamePatchRegistry {

    private static final class FakePatch implements GamePatch {
        boolean romAvailable = true;

        @Override public String id() { return "fake"; }
        @Override public String displayName() { return "Fake Patch"; }
        @Override public String baseGameId() { return "s2"; }
        @Override public Set<LogicalRom> romPrerequisites() { return Set.of(LogicalRom.SK); }
        @Override public List<String> providedMainCharacters() { return List.of("knuckles"); }
        @Override public boolean activatesFor(GameplayLaunchRequest request) {
            return "knuckles".equals(request.mainCharacter());
        }
        @Override
        public GameModule apply(GameModule base, PatchContext ctx) {
            return new DelegatingGameModule(base, id()) { };
        }
    }

    private FakePatch fakePatch;

    @BeforeEach
    void setUp() {
        GamePatchRegistry.resetState();
        fakePatch = new FakePatch();
        GamePatchRegistry.register(fakePatch);
        GamePatchRegistry.setPrerequisiteCheckForTests(rom -> fakePatch.romAvailable);
    }

    @AfterEach
    void tearDown() {
        GamePatchRegistry.resetState();
    }

    @Test
    void resolvesPatchedModuleForActivatingRequest() {
        GameModule base = new Sonic2GameModule();
        GameModule resolved = GamePatchRegistry.resolveModule(base,
                new GameplayLaunchRequest("s2", "knuckles", List.of()));
        assertInstanceOf(DelegatingGameModule.class, resolved);
        assertEquals("fake", ((DelegatingGameModule) resolved).patchId());
        assertSame(base, ((DelegatingGameModule) resolved).base());
    }

    @Test
    void returnsBaseModuleWhenRequestDoesNotActivate() {
        GameModule base = new Sonic2GameModule();
        assertSame(base, GamePatchRegistry.resolveModule(base,
                new GameplayLaunchRequest("s2", "sonic", List.of("tails"))));
    }

    @Test
    void returnsBaseModuleWhenPrerequisitesUnmet() {
        fakePatch.romAvailable = false;
        GameModule base = new Sonic2GameModule();
        assertSame(base, GamePatchRegistry.resolveModule(base,
                new GameplayLaunchRequest("s2", "knuckles", List.of())));
    }

    @Test
    void resolvingAnAlreadyPatchedModuleDoesNotDoubleWrap() {
        GameModule base = new Sonic2GameModule();
        GameplayLaunchRequest request = new GameplayLaunchRequest("s2", "knuckles", List.of());
        GameModule once = GamePatchRegistry.resolveModule(base, request);
        GameModule twice = GamePatchRegistry.resolveModule(once, request);
        assertInstanceOf(DelegatingGameModule.class, twice);
        assertSame(base, ((DelegatingGameModule) twice).base());
    }

    @Test
    void availableMainCharactersReflectsPrerequisites() {
        assertEquals(List.of("knuckles"), GamePatchRegistry.availableMainCharacters("s2"));
        fakePatch.romAvailable = false;
        assertTrue(GamePatchRegistry.availableMainCharacters("s2").isEmpty());
        assertTrue(GamePatchRegistry.availableMainCharacters("s1").isEmpty());
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**, then implement

```java
// GamePatchRegistry.java
package com.openggf.game.patch;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.logging.Logger;

/**
 * Registry of GamePatches, keyed by base game. Resolution happens at the
 * module-construction choke points (Engine.initializeGame, data-select
 * launch, HeadlessGameBoot) BEFORE SessionManager.openGameplaySession —
 * SessionManager itself is not patch-aware.
 *
 * <p>Static facade in the GameModuleRegistry style; resetState() restores a
 * clean slate for tests.
 */
public final class GamePatchRegistry {

    private static final Logger LOGGER = Logger.getLogger(GamePatchRegistry.class.getName());

    private static final List<GamePatch> patches = new ArrayList<>();
    private static boolean defaultsRegistered = false;
    private static Predicate<LogicalRom> prerequisiteCheckOverride = null;

    private GamePatchRegistry() {
    }

    public static synchronized void register(GamePatch patch) {
        patches.add(patch);
    }

    /** Registers built-in patches (KiS2). Idempotent; invoked lazily. */
    private static synchronized void ensureDefaults() {
        if (defaultsRegistered) {
            return;
        }
        defaultsRegistered = true;
        // Task 6 adds: register(new com.openggf.game.sonic2.kis2.Kis2GamePatch());
    }

    /**
     * Returns the patched module for (base, request), or the base module
     * unchanged when no patch applies. Unwraps an already-patched module
     * first so repeated resolution never double-wraps.
     */
    public static synchronized GameModule resolveModule(GameModule base, GameplayLaunchRequest request) {
        ensureDefaults();
        GameModule unwrapped = base instanceof DelegatingGameModule d ? d.base() : base;
        for (GamePatch patch : patches) {
            if (!patch.baseGameId().equals(request.gameId()) || !patch.activatesFor(request)) {
                continue;
            }
            if (!prerequisitesMet(patch)) {
                LOGGER.warning("Patch '" + patch.id() + "' matches launch request but ROM prerequisites are missing; "
                        + "launching unpatched " + request.gameId());
                continue;
            }
            LOGGER.info("Activating game patch '" + patch.id() + "' (" + patch.displayName() + ")");
            return patch.apply(unwrapped, buildContext());
        }
        return unwrapped;
    }

    /** Patch-backed extra main characters for the launch screen (prerequisites already checked). */
    public static synchronized List<String> availableMainCharacters(String gameId) {
        ensureDefaults();
        List<String> result = new ArrayList<>();
        for (GamePatch patch : patches) {
            if (patch.baseGameId().equals(gameId) && prerequisitesMet(patch)) {
                for (String character : patch.providedMainCharacters()) {
                    if (!result.contains(character)) {
                        result.add(character);
                    }
                }
            }
        }
        return result;
    }

    private static boolean prerequisitesMet(GamePatch patch) {
        for (LogicalRom rom : patch.romPrerequisites()) {
            boolean available = prerequisiteCheckOverride != null
                    ? prerequisiteCheckOverride.test(rom)
                    : productionResolver().isAvailable(rom);
            if (!available) {
                return false;
            }
        }
        return true;
    }

    private static PatchContext buildContext() {
        SonicConfigurationService configService = GameServices.configuration();
        if (prerequisiteCheckOverride != null) {
            return new PatchContext(rom -> {
                throw new IOException("No logical ROM source in test context");
            }, configService);
        }
        LogicalRomResolver resolver = productionResolver();
        return new PatchContext(resolver::openOrThrow, configService);
    }

    private static LogicalRomResolver productionResolver() {
        return new LogicalRomResolver(() -> {
            try {
                Rom combined = GameServices.romManager().getSecondaryRom("s3k");
                return combined == null ? null : RomByteReader.fromRom(combined).bytes();
            } catch (IOException e) {
                LOGGER.warning("Combined S3K ROM unavailable for logical SK resolution: " + e.getMessage());
                return null;
            }
        });
    }

    // --- test hooks ---

    public static synchronized void resetState() {
        patches.clear();
        defaultsRegistered = true; // tests register their own; clearTestOverrides() restores production
        prerequisiteCheckOverride = null;
    }

    public static synchronized void setPrerequisiteCheckForTests(Predicate<LogicalRom> check) {
        prerequisiteCheckOverride = check;
    }
}
```

Adapt the two integration seams to reality while implementing:
- `GameServices.romManager()` / `GameServices.configuration()` — use the actual accessor names on `GameServices` (check `GameServices.java`; the CLAUDE.md surface lists `rom()`, `configuration()` — use whichever exposes `RomManager.getSecondaryRom`). If `RomByteReader` has no `bytes()` accessor, add a package-appropriate one or read the secondary ROM's bytes via its existing API — the resolver only needs the raw `byte[]`.
- `resetState()` semantics: after `resetState()`, `ensureDefaults()` must NOT re-register built-ins (tests own the registry). Add a separate `bootstrapDefaultsForProduction()` if the lazy flag interplay gets confusing — clarity over cleverness, keep the tests green.

- [ ] **Step 3: Run tests — expect PASS**, then commit

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.patch.TestGamePatchRegistry" "-DfailIfNoTests=false"
git add src/main/java/com/openggf/game/patch/GamePatchRegistry.java src/test/java/com/openggf/game/patch/TestGamePatchRegistry.java
git commit -m "feat(patch): add GamePatchRegistry with activation resolution and availability

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 5: KiS2 physics profile + provider

**Files:**
- Modify: `src/main/java/com/openggf/game/PhysicsProfile.java` (add `KIS2_KNUCKLES` constant)
- Create: `src/main/java/com/openggf/game/sonic2/kis2/Kis2PhysicsProvider.java`
- Test: `src/test/java/com/openggf/game/sonic2/kis2/TestKis2PhysicsProfile.java`

- [ ] **Step 1: Confirm constants against the diff catalogue.** Open `docs/kis2/BRANCH_DIFFS.md` §physics-constants (Task 1). The expected result: KiS2 Knuckles jump velocity `0x600` (vs Sonic's `0x680`), all other movement constants equal to stock S2 Sonic. **If the catalogue shows anything different, the catalogue wins — adjust the constant below and its ROM-reference comment.**

- [ ] **Step 2: Decide the feature set.** Check `BRANCH_DIFFS.md` §player-object-code answer 2 (ability dispatch / jump-height behavior). If KiS2's behavior matches stock S2 for every existing `PhysicsFeatureSet` flag, **reuse `PhysicsFeatureSet.SONIC_2` unchanged** (note: glide/climb is NOT a feature-set flag — it's driven by `SecondaryAbility.GLIDE`, which the existing `Knuckles` sprite class already resolves; nothing to enable). If any flag must differ, add a `KIS2` `PhysicsFeatureSet` constant copying `SONIC_2` with only that flag changed and a ROM-reference comment citing the branch diff — never branch on game/version at a call site.

- [ ] **Step 3: Write the failing test**

```java
// TestKis2PhysicsProfile.java
package com.openggf.game.sonic2.kis2;

import com.openggf.game.PhysicsFeatureSet;
import com.openggf.game.PhysicsProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * KiS2 physics per docs/kis2/BRANCH_DIFFS.md (s2disasm knuckles-in-sonic-2
 * branch) — NOT inferred from S3K donation.
 */
class TestKis2PhysicsProfile {

    private final Kis2PhysicsProvider provider = new Kis2PhysicsProvider();

    @Test
    void knucklesProfileHasLockOnJumpVelocity() {
        PhysicsProfile profile = provider.getProfile("knuckles");
        assertEquals((short) 0x600, profile.jump(), "KiS2 Knuckles jump (BRANCH_DIFFS.md physics)");
        assertEquals(PhysicsProfile.SONIC_2_SONIC.runAccel(), profile.runAccel());
        assertEquals(PhysicsProfile.SONIC_2_SONIC.max(), profile.max());
        assertEquals(PhysicsProfile.SONIC_2_SONIC.standYRadius(), profile.standYRadius());
    }

    @Test
    void sidekickCharactersFallThroughToStockSonic2Profiles() {
        assertSame(PhysicsProfile.SONIC_2_TAILS, provider.getProfile("tails"));
        assertSame(PhysicsProfile.SONIC_2_SONIC, provider.getProfile("sonic"));
    }

    @Test
    void featureSetIsModuleScopedSonic2() {
        assertSame(PhysicsFeatureSet.SONIC_2, provider.getFeatureSet());
    }

    @Test
    void stockSonic2ProfileUnchangedByKis2Addition() {
        assertEquals((short) 1664, PhysicsProfile.SONIC_2_SONIC.jump(), "stock S2 Sonic jump must stay 0x680");
    }
}
```

(If Step 2 produced a `KIS2` feature-set constant, assert `assertSame(PhysicsFeatureSet.KIS2, ...)` instead and add an assertion on the differing flag.)

- [ ] **Step 4: Run — expect FAIL**, then implement

Add to `PhysicsProfile.java` after `SONIC_3K_KNUCKLES`:

```java
// Knuckles in Sonic 2 (S&K lock-on patch). Values per the s2disasm
// knuckles-in-sonic-2 branch (docs/kis2/BRANCH_DIFFS.md, physics-constants):
// jump $600 (lower than Sonic's $680); all other values identical to stock
// S2 Sonic. Maintained independently of SONIC_3K_KNUCKLES — the lock-on
// branch is authoritative for this profile, not S&K-native Knuckles.
public static final PhysicsProfile KIS2_KNUCKLES = new PhysicsProfile(
        (short) 12,    // runAccel (0x0C)
        (short) 128,   // runDecel (0x80)
        (short) 12,    // friction (0x0C)
        (short) 1536,  // max (0x600)
        (short) 1536,  // jump (0x600) — BRANCH_DIFFS.md physics-constants
        (short) 32,    // slopeRunning
        (short) 20,    // slopeRollingUp
        (short) 80,    // slopeRollingDown
        (short) 32,    // rollDecel
        (short) 128,   // minStartRollSpeed
        (short) 128,   // minRollSpeed
        (short) 4096,  // maxRoll
        (short) 28,    // rollHeight
        (short) 38,    // runHeight
        (short) 9,     // standXRadius
        (short) 19,    // standYRadius (0x13)
        (short) 7,     // rollXRadius
        (short) 14,    // rollYRadius
        false,          // singleFacingBalance (Knuckles uses the four-state set)
        (short) 2      // onObjectBalanceShift
);
```

```java
// Kis2PhysicsProvider.java
package com.openggf.game.sonic2.kis2;

import com.openggf.game.PhysicsFeatureSet;
import com.openggf.game.PhysicsModifiers;
import com.openggf.game.PhysicsProfile;
import com.openggf.game.PhysicsProvider;

/**
 * Physics for the Knuckles-in-Sonic-2 lock-on patch. Knuckles values come
 * from the s2disasm knuckles-in-sonic-2 branch (docs/kis2/BRANCH_DIFFS.md);
 * every other character falls through to stock Sonic 2. Stateless — does not
 * copy Sonic3kPhysicsProvider's lastCharacterType caching.
 */
public class Kis2PhysicsProvider implements PhysicsProvider {

    @Override
    public PhysicsProfile getProfile(String characterType) {
        if ("knuckles".equalsIgnoreCase(characterType)) {
            return PhysicsProfile.KIS2_KNUCKLES;
        }
        if ("tails".equalsIgnoreCase(characterType)) {
            return PhysicsProfile.SONIC_2_TAILS;
        }
        return PhysicsProfile.SONIC_2_SONIC;
    }

    @Override
    public PhysicsModifiers getModifiers() {
        return PhysicsModifiers.STANDARD;
    }

    @Override
    public PhysicsFeatureSet getFeatureSet() {
        return PhysicsFeatureSet.SONIC_2;
    }
}
```

Check `BRANCH_DIFFS.md` for whether KiS2 applies Knuckles-specific modifiers (compare `PhysicsModifiers.KNUCKLES` usage in `Sonic3kPhysicsProvider`); if the branch shows Knuckles modifier behavior, return `PhysicsModifiers.KNUCKLES` for knuckles via a character-keyed `getModifiers` ONLY if the `PhysicsProvider` interface supports it — it does not today, so if the diff requires it, mirror how `Sonic3kPhysicsProvider` is actually consumed (and prefer adding `getModifiers(String characterType)` as a default method delegating to `getModifiers()` over copying the stateful pattern).

- [ ] **Step 5: Run — expect PASS**, then commit

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.sonic2.kis2.TestKis2PhysicsProfile" "-DfailIfNoTests=false"
git add src/main/java/com/openggf/game/PhysicsProfile.java src/main/java/com/openggf/game/sonic2/kis2/Kis2PhysicsProvider.java src/test/java/com/openggf/game/sonic2/kis2/TestKis2PhysicsProfile.java
git commit -m "feat(kis2): add lock-on Knuckles physics profile and provider

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 6: Kis2GamePatch + patched module

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/kis2/Kis2GamePatch.java`
- Create: `src/main/java/com/openggf/game/sonic2/kis2/Kis2GameModule.java`
- Modify: `src/main/java/com/openggf/game/patch/GamePatchRegistry.java` (register default)
- Test: `src/test/java/com/openggf/game/sonic2/kis2/TestKis2GamePatch.java`

- [ ] **Step 1: Write the failing test**

```java
// TestKis2GamePatch.java
package com.openggf.game.sonic2.kis2;

import com.openggf.game.GameModule;
import com.openggf.game.patch.DelegatingGameModule;
import com.openggf.game.patch.GameplayLaunchRequest;
import com.openggf.game.patch.LogicalRom;
import com.openggf.game.patch.PatchContext;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestKis2GamePatch {

    private final Kis2GamePatch patch = new Kis2GamePatch();

    @Test
    void identityAndPrerequisites() {
        assertEquals("kis2", patch.id());
        assertEquals("s2", patch.baseGameId());
        assertEquals(Set.of(LogicalRom.SK), patch.romPrerequisites());
        assertEquals(List.of("knuckles"), patch.providedMainCharacters());
    }

    @Test
    void activatesOnlyForKnucklesMainOnS2() {
        assertTrue(patch.activatesFor(new GameplayLaunchRequest("s2", "knuckles", List.of())));
        assertTrue(patch.activatesFor(new GameplayLaunchRequest("s2", "knuckles", List.of("tails"))));
        assertFalse(patch.activatesFor(new GameplayLaunchRequest("s2", "sonic", List.of())));
        assertFalse(patch.activatesFor(new GameplayLaunchRequest("s2", "tails", List.of())));
    }

    @Test
    void patchedModuleOverridesPhysicsAndDelegatesEverythingElse() throws IOException {
        Sonic2GameModule base = new Sonic2GameModule();
        PatchContext ctx = new PatchContext(rom -> {
            throw new IOException("no ROM in this test");
        }, com.openggf.configuration.SonicConfigurationService.getInstance());
        GameModule patched = patch.apply(base, ctx);

        assertInstanceOf(DelegatingGameModule.class, patched);
        assertEquals("kis2", ((DelegatingGameModule) patched).patchId());
        assertInstanceOf(Kis2PhysicsProvider.class, patched.getPhysicsProvider());
        assertEquals(base.getGameId(), patched.getGameId());
        assertEquals(base.getCheckpointObjectId(), patched.getCheckpointObjectId());
    }
}
```

(If `SonicConfigurationService.getInstance()` isn't the accessor, use whatever construction existing config-consuming tests use — and remember the `@TempDir`/`user.dir` rule if the test triggers config file creation; copy the setup from an existing `SonicConfigurationService` test.)

- [ ] **Step 2: Run — expect FAIL**, then implement

```java
// Kis2GameModule.java
package com.openggf.game.sonic2.kis2;

import com.openggf.game.GameModule;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.patch.DelegatingGameModule;
import com.openggf.game.patch.PatchContext;

/**
 * Sonic 2 patched by the S&K lock-on (Knuckles in Sonic 2). Overrides only
 * the surfaces the lock-on changes; everything else delegates to stock S2.
 * Art overrides (player sprite, life icon, monitor face) are layered in by
 * later tasks.
 */
class Kis2GameModule extends DelegatingGameModule {

    private final PatchContext ctx;
    private PhysicsProvider physicsProvider;

    Kis2GameModule(GameModule base, PatchContext ctx) {
        super(base, Kis2GamePatch.ID);
        this.ctx = ctx;
    }

    @Override
    public PhysicsProvider getPhysicsProvider() {
        if (physicsProvider == null) {
            physicsProvider = new Kis2PhysicsProvider();
        }
        return physicsProvider;
    }
}
```

```java
// Kis2GamePatch.java
package com.openggf.game.sonic2.kis2;

import com.openggf.game.GameModule;
import com.openggf.game.patch.GamePatch;
import com.openggf.game.patch.GameplayLaunchRequest;
import com.openggf.game.patch.LogicalRom;
import com.openggf.game.patch.PatchContext;

import java.util.List;
import java.util.Set;

/**
 * Knuckles in Sonic 2 — the official S&K lock-on patch over Sonic 2,
 * implemented from the s2disasm knuckles-in-sonic-2 branch diffs
 * (docs/kis2/BRANCH_DIFFS.md). Activates when Knuckles is the requested
 * main character for S2.
 */
public class Kis2GamePatch implements GamePatch {

    public static final String ID = "kis2";

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Knuckles in Sonic 2"; }
    @Override public String baseGameId() { return "s2"; }
    @Override public Set<LogicalRom> romPrerequisites() { return Set.of(LogicalRom.SK); }
    @Override public List<String> providedMainCharacters() { return List.of("knuckles"); }

    @Override
    public boolean activatesFor(GameplayLaunchRequest request) {
        return "knuckles".equals(request.mainCharacter());
    }

    @Override
    public GameModule apply(GameModule base, PatchContext ctx) {
        return new Kis2GameModule(base, ctx);
    }
}
```

In `GamePatchRegistry.ensureDefaults()`, replace the comment with:

```java
register(new com.openggf.game.sonic2.kis2.Kis2GamePatch());
```

(keeping the Task 4 test-vs-production `resetState()` semantics intact).

- [ ] **Step 3: Run this test + the registry tests — expect PASS**, then commit

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.sonic2.kis2.TestKis2GamePatch" "-DfailIfNoTests=false"
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.patch.TestGamePatchRegistry" "-DfailIfNoTests=false"
git add src/main/java/com/openggf/game/sonic2/kis2/ src/main/java/com/openggf/game/patch/GamePatchRegistry.java src/test/java/com/openggf/game/sonic2/kis2/TestKis2GamePatch.java
git commit -m "feat(kis2): add Kis2GamePatch and patched module with physics override

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 7: LaunchProfile availability union

**Files:**
- Modify: `src/main/java/com/openggf/game/launch/LaunchProfile.java`
- Test: `src/test/java/com/openggf/game/launch/TestLaunchProfilePatchAvailability.java`

- [ ] **Step 1: Write the failing tests**

```java
// TestLaunchProfilePatchAvailability.java
package com.openggf.game.launch;

import com.openggf.game.MasterTitleScreen;
import com.openggf.game.patch.GamePatchRegistry;
import com.openggf.game.patch.GameplayLaunchRequest;
import com.openggf.game.patch.GamePatch;
import com.openggf.game.patch.LogicalRom;
import com.openggf.game.patch.PatchContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestLaunchProfilePatchAvailability {

    private static GamePatch knucklesPatch() {
        return new GamePatch() {
            @Override public String id() { return "kis2"; }
            @Override public String displayName() { return "Knuckles in Sonic 2"; }
            @Override public String baseGameId() { return "s2"; }
            @Override public Set<LogicalRom> romPrerequisites() { return Set.of(LogicalRom.SK); }
            @Override public List<String> providedMainCharacters() { return List.of("knuckles"); }
            @Override public boolean activatesFor(GameplayLaunchRequest r) { return "knuckles".equals(r.mainCharacter()); }
            @Override public com.openggf.game.GameModule apply(com.openggf.game.GameModule base, PatchContext ctx) { return base; }
        };
    }

    @BeforeEach
    void setUp() {
        GamePatchRegistry.resetState();
    }

    @AfterEach
    void tearDown() {
        GamePatchRegistry.resetState();
    }

    @Test
    void knucklesSurvivesSanitizationOnS2WhenPatchAvailableAndDonationOff() {
        GamePatchRegistry.register(knucklesPatch());
        GamePatchRegistry.setPrerequisiteCheckForTests(rom -> true);
        LaunchProfile profile = new LaunchProfile(false, "off", false, "global", "knuckles", "none");
        LaunchProfile sanitized = profile.sanitizedFor(MasterTitleScreen.GameEntry.SONIC_2);
        assertEquals("knuckles", sanitized.mainCharacter());
        assertEquals("none", sanitized.sidekick());
    }

    @Test
    void knucklesStrippedOnS2WhenNoPatchAndNoS3kDonor() {
        // empty registry: no patch-backed characters
        LaunchProfile profile = new LaunchProfile(false, "off", false, "global", "knuckles", "none");
        LaunchProfile sanitized = profile.sanitizedFor(MasterTitleScreen.GameEntry.SONIC_2);
        assertEquals("sonic", sanitized.mainCharacter());
    }

    @Test
    void knucklesStrippedWhenPatchPrerequisitesUnmet() {
        GamePatchRegistry.register(knucklesPatch());
        GamePatchRegistry.setPrerequisiteCheckForTests(rom -> false);
        LaunchProfile profile = new LaunchProfile(false, "off", false, "global", "knuckles", "none");
        LaunchProfile sanitized = profile.sanitizedFor(MasterTitleScreen.GameEntry.SONIC_2);
        assertEquals("sonic", sanitized.mainCharacter());
    }

    @Test
    void knucklesAloneIsAStandardPairForS2() {
        GamePatchRegistry.register(knucklesPatch());
        GamePatchRegistry.setPrerequisiteCheckForTests(rom -> true);
        LaunchProfile profile = new LaunchProfile(false, "off", false, "global", "knuckles", "none");
        org.junit.jupiter.api.Assertions.assertTrue(
                profile.isCharacterPairStandard(MasterTitleScreen.GameEntry.SONIC_2),
                "Knuckles alone is the faithful lock-on configuration, not a non-standard combo");
    }
}
```

- [ ] **Step 2: Run — expect FAIL**, then modify `LaunchProfile.java`

Replace `mainCharacterValues` (currently `LaunchProfile.java:188-194`):

```java
private List<String> mainCharacterValues(MasterTitleScreen.GameEntry entry) {
    List<String> values = new ArrayList<>(switch (effectiveCharacterDonor(entry)) {
        case "s2" -> List.of(SONIC, TAILS);
        case "s3k" -> List.of(SONIC, TAILS, KNUCKLES);
        default -> List.of(SONIC);
    });
    // Patch-backed characters (e.g. KiS2 Knuckles) are offered whenever the
    // patch's ROM prerequisites are satisfied, independent of donation.
    for (String patchBacked : com.openggf.game.patch.GamePatchRegistry.availableMainCharacters(gameId(entry))) {
        if (!values.contains(patchBacked)) {
            values.add(patchBacked);
        }
    }
    return values;
}
```

And in `isCharacterPairStandard` (currently `LaunchProfile.java:125-133`), extend the `SONIC_2` case:

```java
case SONIC_2 -> pair(SONIC, NONE) || pair(SONIC, TAILS) || pair(TAILS, NONE)
        || pair(KNUCKLES, NONE);
```

(`pair(KNUCKLES, NONE)` is the faithful lock-on configuration. Knuckles+Tails remains selectable but flags as non-standard — the intentional-divergence marker.)

- [ ] **Step 3: Run new tests + existing launch tests — expect PASS**

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.launch.TestLaunchProfilePatchAvailability" "-DfailIfNoTests=false"
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.launch.*" "-DfailIfNoTests=false"
```

If existing `LaunchProfile` tests fail because the registry now contributes characters, those tests must reset the registry in `@BeforeEach` (`GamePatchRegistry.resetState()`) — fix the test setup, not the production behavior. (Registry state is static and the suite runs `forkCount=4`; same hygiene as the ICZ registry/session-leak rule.)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/launch/LaunchProfile.java src/test/java/com/openggf/game/launch/TestLaunchProfilePatchAvailability.java
git commit -m "feat(patch): offer patch-backed characters in launch profile availability

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 8: Choke-point wiring (Engine, HeadlessGameBoot)

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java:496-510` (`initializeGame`)
- Modify: `src/main/java/com/openggf/tools/HeadlessGameBoot.java:174-180`
- Test: `src/test/java/com/openggf/game/patch/TestPatchResolutionAtBoot.java`

- [ ] **Step 1: Wire `Engine.initializeGame`** — after detection succeeds (`Engine.java:505`), before `openGameplaySession`:

```java
module = detectedModule.orElseThrow();
module = com.openggf.game.patch.GamePatchRegistry.resolveModule(module,
        com.openggf.game.patch.GameplayLaunchRequest.fromConfig(configService, module.getGameId().code()));
```

(`getGameId()` returns the `GameId` enum — use its `code()`/string accessor; check `GameId.java` for the exact name. `configService` is the field Engine already holds.)

- [ ] **Step 2: Wire `HeadlessGameBoot`** — same two lines after `detected.orElseThrow(...)` (`HeadlessGameBoot.java:176`), using the config service available in that scope (`SonicConfigurationService.getInstance()` or however the file already obtains config — match its existing style). This automatically covers `TraceReplaySessionBootstrap`, which boots through config + headless paths (`prepareConfiguration` already writes `MAIN_CHARACTER_CODE`), so a future KiS2 trace activates the patch by recording `knuckles` as main character. Verify by reading `TraceReplaySessionBootstrap` — if it opens a session through a path other than `HeadlessGameBoot`, add the same two lines there too.

- [ ] **Step 3: Write the boot resolution test** (headless, no OpenGL; ROM-gated for the S2 ROM only — patch prerequisites are faked)

```java
// TestPatchResolutionAtBoot.java
package com.openggf.game.patch;

import com.openggf.game.GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The choke-point contract: resolveModule applied to the module that boot
 * paths construct. (Engine.initializeGame and HeadlessGameBoot both call
 * GamePatchRegistry.resolveModule with a config-derived request — this test
 * pins the resolution behavior those call sites rely on.)
 */
class TestPatchResolutionAtBoot {

    @BeforeEach
    void setUp() {
        GamePatchRegistry.resetState();
        GamePatchRegistry.register(new com.openggf.game.sonic2.kis2.Kis2GamePatch());
        GamePatchRegistry.setPrerequisiteCheckForTests(rom -> true);
    }

    @AfterEach
    void tearDown() {
        GamePatchRegistry.resetState();
    }

    @Test
    void knucklesRequestOnS2ResolvesKis2Module() {
        GameModule base = new Sonic2GameModule();
        GameModule resolved = GamePatchRegistry.resolveModule(base,
                new GameplayLaunchRequest("s2", "knuckles", List.of()));
        assertInstanceOf(DelegatingGameModule.class, resolved);
        assertInstanceOf(com.openggf.game.sonic2.kis2.Kis2PhysicsProvider.class, resolved.getPhysicsProvider());
    }

    @Test
    void sonicRequestOnS2StaysStock() {
        GameModule base = new Sonic2GameModule();
        assertSame(base, GamePatchRegistry.resolveModule(base,
                new GameplayLaunchRequest("s2", "sonic", List.of("tails"))));
    }
}
```

- [ ] **Step 4: Run + compile-check Engine, then commit**

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.patch.TestPatchResolutionAtBoot" "-DfailIfNoTests=false"
mvn "-Dmse=off" compile
git add src/main/java/com/openggf/Engine.java src/main/java/com/openggf/tools/HeadlessGameBoot.java src/test/java/com/openggf/game/patch/TestPatchResolutionAtBoot.java
git commit -m "feat(patch): resolve game patches at module-construction choke points

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 9: Save-context team sanitization (data-select path)

**Files:**
- Create: `src/main/java/com/openggf/game/patch/GameplayTeamAvailability.java`
- Modify: `src/main/java/com/openggf/Engine.java:816-830` (`launchGameplayFromDataSelect`)
- Test: `src/test/java/com/openggf/game/patch/TestGameplayTeamAvailability.java`

- [ ] **Step 1: Write the failing tests**

```java
// TestGameplayTeamAvailability.java
package com.openggf.game.patch;

import com.openggf.game.save.SaveSessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestGameplayTeamAvailability {

    @BeforeEach
    void setUp() {
        GamePatchRegistry.resetState();
    }

    @AfterEach
    void tearDown() {
        GamePatchRegistry.resetState();
    }

    /** Build a SaveSessionContext with a Knuckles team the way data select does. */
    private static SaveSessionContext knucklesSlotContext() {
        return SaveSessionContext.forSlot("s2", 1,
                new SaveSessionContext.SelectedTeam("knuckles", List.of()), 0, 0);
    }

    @Test
    void unavailableMainCharacterIsReplacedWithStockSonic() {
        // empty registry + donation off: knuckles is not available for s2
        SaveSessionContext sanitized = GameplayTeamAvailability.sanitizeForLaunch(
                knucklesSlotContext(), "s2", List.of("sonic", "tails"));
        assertEquals("sonic", sanitized.selectedTeam().mainCharacter());
    }

    @Test
    void availableTeamPassesThroughUnchanged() {
        SaveSessionContext context = knucklesSlotContext();
        SaveSessionContext sanitized = GameplayTeamAvailability.sanitizeForLaunch(
                context, "s2", List.of("sonic", "tails", "knuckles"));
        assertSame(context, sanitized);
    }

    @Test
    void unavailableSidekicksAreDropped() {
        SaveSessionContext context = SaveSessionContext.forSlot("s2", 1,
                new SaveSessionContext.SelectedTeam("sonic", List.of("knuckles", "tails")), 0, 0);
        SaveSessionContext sanitized = GameplayTeamAvailability.sanitizeForLaunch(
                context, "s2", List.of("sonic", "tails"));
        assertEquals(List.of("tails"), sanitized.selectedTeam().sidekicks());
        assertEquals("sonic", sanitized.selectedTeam().mainCharacter());
    }
}
```

**Adapt the `SaveSessionContext` construction to its real API** (Task explorer notes: `forSlot(gameCode, slot, team, zone, act)`, `SelectedTeam(String mainCharacter, List<String> sidekicks)` — confirm field order in `SaveSessionContext.java` and whether `SelectedTeam` is nested or top-level; adjust imports/calls accordingly, keeping the three behaviors asserted exactly as above).

- [ ] **Step 2: Run — expect FAIL**, then implement

```java
// GameplayTeamAvailability.java
package com.openggf.game.patch;

import com.openggf.game.save.SaveSessionContext;

import java.util.List;
import java.util.logging.Logger;

/**
 * Validates a launch-requested team against the characters actually
 * available for the base game (stock roster + donor-provided +
 * patch-provided). A data-select slot can request Knuckles while the S&K
 * data has since disappeared; ActiveGameplayTeamResolver prefers the
 * session-owned team over config, so an unbackable team must be sanitized
 * BEFORE the session opens. Only the session-owned SaveSessionContext is
 * replaced — the save slot on disk is never rewritten, so the slot launches
 * as Knuckles again once the ROM returns.
 */
public final class GameplayTeamAvailability {

    private static final Logger LOGGER = Logger.getLogger(GameplayTeamAvailability.class.getName());
    private static final String STOCK_MAIN = "sonic";

    private GameplayTeamAvailability() {
    }

    /**
     * Returns the context unchanged when its team is fully available,
     * otherwise a copy with unavailable characters replaced/dropped.
     *
     * @param availableCharacters every character currently launchable for
     *        this game (stock + donor + patch), lowercase codes
     */
    public static SaveSessionContext sanitizeForLaunch(SaveSessionContext context,
                                                       String gameId,
                                                       List<String> availableCharacters) {
        if (context == null || context.selectedTeam() == null) {
            return context;
        }
        var team = context.selectedTeam();
        String main = team.mainCharacter();
        List<String> sidekicks = team.sidekicks();

        boolean mainOk = availableCharacters.contains(main);
        List<String> keptSidekicks = sidekicks.stream().filter(availableCharacters::contains).toList();
        if (mainOk && keptSidekicks.size() == sidekicks.size()) {
            return context;
        }

        String newMain = mainOk ? main : STOCK_MAIN;
        LOGGER.warning("Launch team for " + gameId + " requested unavailable character(s) "
                + "(main=" + main + ", sidekicks=" + sidekicks + "); session team sanitized to "
                + "main=" + newMain + ", sidekicks=" + keptSidekicks
                + ". The save slot on disk is unchanged.");
        return context.withSelectedTeam(new SaveSessionContext.SelectedTeam(newMain, keptSidekicks));
    }
}
```

If `SaveSessionContext` has no `withSelectedTeam` copy method, add one to `SaveSessionContext.java` (a record-style wither copying all other fields verbatim).

- [ ] **Step 3: Wire `Engine.launchGameplayFromDataSelect`** (`Engine.java:816-822`) — between creating `saveContext` and opening the session:

```java
com.openggf.game.save.SaveSessionContext saveContext = createDataSelectSaveContext(module, action, saveManager);

String gameId = module.getGameId().code();
java.util.List<String> available = new java.util.ArrayList<>(java.util.List.of("sonic", "tails"));
available.addAll(com.openggf.game.patch.GamePatchRegistry.availableMainCharacters(gameId));
if (com.openggf.game.CrossGameFeatureProvider.getInstance().isActive()) {
    available.add("knuckles"); // donation can back knuckles when its donor ROM is open
}
saveContext = com.openggf.game.patch.GameplayTeamAvailability.sanitizeForLaunch(saveContext, gameId, available);

GameplayModeContext gameplay = SessionManager.openGameplaySession(
        com.openggf.game.patch.GamePatchRegistry.resolveModule(module,
                new com.openggf.game.patch.GameplayLaunchRequest(gameId,
                        saveContext != null && saveContext.selectedTeam() != null
                                ? saveContext.selectedTeam().mainCharacter() : null,
                        saveContext != null && saveContext.selectedTeam() != null
                                ? saveContext.selectedTeam().sidekicks() : java.util.List.of())),
        saveContext);
```

Adapt the donation-availability line to `CrossGameFeatureProvider`'s real API (`isActive()`/`active` accessor and whether its donor capabilities include knuckles — `getDonorCapabilities().getPlayableCharacters()` is the precise source; use it if reachable here). Note resolution order: **sanitize first, then resolve the patch from the sanitized team** — so a sanitized-away Knuckles boots a stock module, and a kept Knuckles boots the patched one.

- [ ] **Step 4: Run + compile + commit**

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.patch.TestGameplayTeamAvailability" "-DfailIfNoTests=false"
mvn "-Dmse=off" compile
git add src/main/java/com/openggf/game/patch/GameplayTeamAvailability.java src/main/java/com/openggf/Engine.java src/main/java/com/openggf/game/save/SaveSessionContext.java src/test/java/com/openggf/game/patch/TestGameplayTeamAvailability.java
git commit -m "feat(patch): sanitize data-select session team against available characters

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 10: Knuckles player art from the logical S&K ROM

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/kis2/Kis2Constants.java`
- Create: `src/main/java/com/openggf/game/sonic2/kis2/Kis2PlayerArt.java`
- Modify: `src/main/java/com/openggf/game/sonic2/kis2/Kis2GameModule.java`
- Test: `src/test/java/com/openggf/game/sonic2/kis2/TestKis2PlayerArt.java` (ROM-gated)

- [ ] **Step 1: Verify addresses.** Take the art/mapping/DPLC/animation addresses from `docs/kis2/BRANCH_DIFFS.md` §art-mapping-dplc-addresses (Task 1 answer 3). Expected (cross-checked against S3K's verified S&K-side constants — all `< 0x200000`, i.e. inside the logical SK window):

| Constant | Expected | Source |
|---|---|---|
| `ART_UNC_KNUCKLES_ADDR` | `0x1200E0` | `Sonic3kConstants.java:702-718` ↔ branch diff |
| `ART_UNC_KNUCKLES_SIZE` | `0x1FF80` | same |
| `MAP_KNUCKLES_ADDR` | `0x14A8D6` | same |
| `DPLC_KNUCKLES_ADDR` | `0x14BD0A` | same |
| `KNUCKLES_ANIM_DATA_ADDR` | `0x017EF4` | same |

If the branch diff records different addresses for any of these, the branch wins — use its values and note the divergence in `Kis2Constants` javadoc. Verify each with RomOffsetFinder where a label exists:

```bash
java -cp target/classes com.openggf.tools.disasm.RomOffsetFinder --game s3k search ArtUnc_Knuckles
```

- [ ] **Step 2: Read the two reference implementations** before writing code: `Sonic3kPlayerArt.loadKnuckles()` (`Sonic3kPlayerArt.java:223-279` — art/mapping/DPLC/animation loading from these addresses) and `CrossGameFeatureProvider.loadPlayerSpriteArt()` (`CrossGameFeatureProvider.java:154-176` — how a donor-loaded `SpriteArtSet` is adapted for a host game, including animation-profile translation). The KiS2 loader composes the same mechanics, reading from the logical SK reader.

- [ ] **Step 3: Implement `Kis2Constants` + `Kis2PlayerArt`**

```java
// Kis2Constants.java
package com.openggf.game.sonic2.kis2;

/**
 * S&K-cart addresses used by the Knuckles-in-Sonic-2 lock-on patch.
 * All addresses are S&K-cart-relative (= combined-ROM-relative, since the
 * S&K cart occupies 0x000000-0x1FFFFF of the combined image) and are read
 * through the logical SK reader, never the S2 ROM.
 * Source: docs/kis2/BRANCH_DIFFS.md (art-mapping-dplc-addresses), verified
 * with RomOffsetFinder.
 */
public final class Kis2Constants {

    public static final int ART_UNC_KNUCKLES_ADDR = 0x1200E0;
    public static final int ART_UNC_KNUCKLES_SIZE = 0x1FF80;
    public static final int MAP_KNUCKLES_ADDR = 0x14A8D6;
    public static final int DPLC_KNUCKLES_ADDR = 0x14BD0A;
    public static final int KNUCKLES_ANIM_DATA_ADDR = 0x017EF4;

    private Kis2Constants() {
    }
}
```

(Add the life-icon / monitor-face addresses in Task 11 once read from the diff catalogue.)

`Kis2PlayerArt`: a class with `SpriteArtSet loadKnuckles(RomByteReader skReader)` whose body mirrors `Sonic3kPlayerArt.loadKnuckles()` but takes the reader as a parameter and uses `Kis2Constants`. **Prefer refactoring `Sonic3kPlayerArt.loadKnuckles()` to extract a static reader-parameterized helper (e.g. `loadKnucklesFrom(RomByteReader reader, int artAddr, int artSize, int mapAddr, int dplcAddr, int animAddr)`) and calling it from both sites** — the bytes are the identical S&K cart data, so DRY applies; fidelity is owned by the diff-catalogued addresses and the physics/behavior tasks, not by duplicating this loader. If S3K's loader has S3K-specific entanglements (palette handling, art_tile bases) that don't extract cleanly, copy the minimal loading code into `Kis2PlayerArt` instead and say so in its javadoc.

- [ ] **Step 4: Route it through the patched module.** In `Kis2GameModule`, override the player-art surface. `Sonic2`'s `PlayerSpriteArtProvider.loadPlayerSpriteArt(String characterCode)` is the interface (consumed at `LevelManager.java:1213-1225`); find which `GameModule` method exposes it for S2 (the explorer trail: `Sonic2.java` implements `PlayerSpriteArtProvider` directly) and override that method on `Kis2GameModule` to return a provider that answers `"knuckles"` from `Kis2PlayerArt.loadKnuckles(ctx.openLogicalRom(LogicalRom.SK))` (cache the loaded `SpriteArtSet`; reader opened lazily on first request) and delegates every other character code to the base module's provider. Apply the same animation-profile adaptation `CrossGameFeatureProvider.loadPlayerSpriteArt` performs — the `Knuckles` sprite class must see the same animation contract it sees under donation.

- [ ] **Step 5: Write the ROM-gated test**

```java
// TestKis2PlayerArt.java
package com.openggf.game.sonic2.kis2;

import com.openggf.data.RomByteReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestKis2PlayerArt {

    private static final Path COMBINED_ROM = Path.of("Sonic and Knuckles & Sonic 3 (W) [!].gen");

    @Test
    void knucklesArtLoadsFromLogicalSkWindow() throws Exception {
        Assumptions.assumeTrue(Files.exists(COMBINED_ROM), "combined S3K ROM not present");
        byte[] combined = Files.readAllBytes(COMBINED_ROM);
        RomByteReader sk = com.openggf.game.patch.LogicalRomResolver.windowSkFromCombined(combined);
        var artSet = new Kis2PlayerArt().loadKnuckles(sk);
        assertNotNull(artSet);
        // sanity: mappings parsed into a non-empty frame set
        assertTrue(artSetFrameCount(artSet) > 0, "expected non-empty Knuckles mapping frames");
    }

    private static int artSetFrameCount(Object artSet) {
        // use SpriteArtSet's real accessor (frames()/getFrameCount()/mappings size)
        // — fill in from the SpriteArtSet API when implementing.
        throw new UnsupportedOperationException("replace with SpriteArtSet accessor");
    }
}
```

Replace `artSetFrameCount` with the real `SpriteArtSet` accessor while implementing (check `SpriteArtSet`'s API; the donation tests, if any exist for donor art, show the idiomatic assertion — mirror it).

- [ ] **Step 6: Run (with ROM present) — expect PASS**, then commit

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.sonic2.kis2.TestKis2PlayerArt" "-DfailIfNoTests=false"
git add src/main/java/com/openggf/game/sonic2/kis2/ src/main/java/com/openggf/game/sonic3k/Sonic3kPlayerArt.java src/test/java/com/openggf/game/sonic2/kis2/TestKis2PlayerArt.java
git commit -m "feat(kis2): load Knuckles player art from the logical S&K ROM

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 11: Life-icon and 1-up monitor face overlays

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/kis2/Kis2Constants.java` (icon addresses)
- Modify: `src/main/java/com/openggf/game/sonic2/kis2/Kis2GameModule.java`
- Test: `src/test/java/com/openggf/game/sonic2/kis2/TestKis2IconArt.java` (ROM-gated)

- [ ] **Step 1: Get addresses from the catalogue.** `docs/kis2/BRANCH_DIFFS.md` §monitor-life-icon (Task 1 answer 4) records where the KiS2 patch sources the Knuckles HUD life icon and the 1-up monitor face (S&K side). Add them to `Kis2Constants` with the catalogue citation. If the catalogue shows the patch reuses an address already constant-ized for S3K, still declare it in `Kis2Constants` (KiS2 owns its address set; cross-reference in the comment).

- [ ] **Step 2: Find the override seam.** `Sonic2ObjectArtProvider` already loads `hudLivesPatterns` and exposes life-icon override methods (`Sonic2ObjectArtProvider.java:220-234` — added for donation; stock loads `ART_NEM_SONIC_LIFE_ADDR=0x79346` / `ART_NEM_TAILS_LIFE_ADDR=0x7C20C` per `Sonic2Constants.java:128-130`). The monitor sheet is registered at `Sonic2ObjectArtProvider.java:134` with the Sonic face at `MONITOR_LIFE_ICON_TILE = 340` (`Sonic2ObjectArt.java:45`, loading at `:92-100`). Determine how donation swaps these today (search `CrossGameFeatureProvider` usages of the override methods) and route the KiS2 swap through the **same** seam from `Kis2GameModule` — decompress the Knuckles icon art from the logical SK reader and install it via the existing override hooks. If the seam is reachable only via `CrossGameFeatureProvider` statics, add a module-level hook instead (e.g. the patched module overrides whatever `GameModule` method supplies the object-art provider, wrapping S2's provider with the two icon swaps) — do NOT route patch art through `CrossGameFeatureProvider`.

- [ ] **Step 3: ROM-gated test** — same shape as `TestKis2PlayerArt`: window the SK half, decompress the life-icon art (Nemesis-compressed art uses the engine's existing `tools` decompressors — match whatever loader `Sonic2ObjectArt` uses for `ART_NEM_SONIC_LIFE_ADDR`), assert non-empty pattern output. Assert the monitor-face swap by loading the patched monitor sheet and verifying the face tile region differs from the stock-S2 sheet (byte-level inequality at `MONITOR_LIFE_ICON_TILE` is sufficient).

- [ ] **Step 4: Run, commit**

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.sonic2.kis2.TestKis2IconArt" "-DfailIfNoTests=false"
git add src/main/java/com/openggf/game/sonic2/kis2/ src/test/java/com/openggf/game/sonic2/kis2/TestKis2IconArt.java
git commit -m "feat(kis2): swap HUD life icon and 1-up monitor face to Knuckles

Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 12: Placement diffs (conditional on the catalogue)

**Files:** depends on catalogue findings.

- [ ] **Step 1:** Open `docs/kis2/BRANCH_DIFFS.md` §object-placements (Task 1 answer 5).
- [ ] **Step 2 (if "None found"):** add a line to the catalogue's placements section: "No placement changes — task 12 no-op." Skip to Task 13. No commit needed.
- [ ] **Step 3 (if entries exist):** for each changed start position or object placement, express it as overlay data inside `Kis2GameModule` over the surface stock S2 uses for that data (start positions load through the module/level path that reads `Sonic2Constants` start-location tables; object placements through the object-spawn table loading). The patched module overrides the narrowest provider method that supplies that data, returning the catalogued KiS2 values for the affected (zone, act) and delegating everything else. One headless test per entry asserting the patched module reports the KiS2 value and the base module reports the stock value. Commit with the standard trailer block, `Changelog: n/a: incremental kis2 slice step; changelog entry lands in the final docs task`.

---

### Task 13: Headless integration test

**Files:**
- Test: `src/test/java/com/openggf/game/sonic2/kis2/TestKis2HeadlessSession.java` (ROM-gated: needs S2 ROM + combined S3K ROM)

- [ ] **Step 1: Write the integration test.** Model the setup on an existing headless session test (find one with `grep -r "HeadlessGameBoot" src/test --include=*.java -l` or the `HeadlessTestRunner` examples; use `@ExtendWith(SingletonResetExtension.class)` / `@FullReset` for teardown, and `GamePatchRegistry.resetState()` + re-register `Kis2GamePatch` in `@BeforeEach` since the suite runs forked and other tests reset the registry). Assertions:

1. With config `MAIN_CHARACTER_CODE=knuckles`, sidekick none, S2 ROM as primary: the opened session's module is a `DelegatingGameModule` with `patchId()=="kis2"`, and `GameModuleRegistry.getCurrent().getPhysicsProvider()` is `Kis2PhysicsProvider`.
2. The bootstrapped team's main sprite is a `com.openggf.sprites.playable.Knuckles` instance (via `GameplayTeamBootstrap.registerActiveTeam`) and its resolved jump speed reflects `0x600` (read via the sprite's physics getters after `resolvePhysicsProfile()` — see `AbstractPlayableSprite`).
3. No sidekick sprites under the faithful default; with config sidekick `tails`, a Tails sidekick spawns (config override honored).
4. With the combined ROM renamed away (simulate via `GamePatchRegistry.setPrerequisiteCheckForTests(rom -> false)`), the same config boots the stock module with a Sonic main sprite (fallback path) — and the warning path doesn't throw.

- [ ] **Step 2: Run — expect PASS** (with ROMs present), fix integration fallout, commit

```bash
mvn "-Dmse=off" surefire:test "-Dtest=com.openggf.game.sonic2.kis2.TestKis2HeadlessSession" "-DfailIfNoTests=false"
git add src/test/java/com/openggf/game/sonic2/kis2/TestKis2HeadlessSession.java
git commit -m "test(kis2): headless patched-session integration coverage

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 14: Docs, changelog, full-suite verification

**Files:**
- Modify: `CHANGELOG.md`, `docs/status/known-discrepancies.md`, `ROADMAP.md`, `CLAUDE.md`, `AGENTS.md`, guide docs (`docs/guide/` launch/ROM tables)

- [ ] **Step 1: Full test suite**

```bash
mvn "-Dmse=off" test
```

Expected: no regressions attributable to this branch (compare failures against the known-flaky list: lwjgl/glfw link errors, `TestBundledConfigResource`). The S3K gate tests must stay green: `TestS3kAiz1SkipHeadless`, `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`, `TestSonic3kDecodingUtils`.

- [ ] **Step 2: Documentation updates**
  - `CHANGELOG.md`: one entry covering the GamePatch framework + KiS2 core slice.
  - `docs/status/known-discrepancies.md`: "KiS2 faithful mode is Knuckles alone; the config/launch sidekick selection can add sidekicks (intentional divergence). Trace work uses the faithful default."
  - `ROADMAP.md:235-241`: annotate the KiS2 item with what the core slice delivered and what remains (title/level-select flow, special stages, Super Knuckles, 2P, traces).
  - `CLAUDE.md` + `AGENTS.md`: short subsection under Multi-Game Support describing `com.openggf.game.patch` (GamePatch/registry/choke points) and the KiS2 patch package.
  - Guide ROM tables (`docs/guide/playing/getting-started.md` etc.): note Knuckles-in-S2 requires the combined S3K ROM present.

- [ ] **Step 3: Final commit** — trailers must reflect what's staged:

```bash
git add CHANGELOG.md docs/status/known-discrepancies.md ROADMAP.md CLAUDE.md AGENTS.md docs/guide/
git commit -m "docs(kis2): document GamePatch framework and KiS2 core slice

Changelog: updated
Guide: updated
Known-Discrepancies: updated
S3K-Known-Discrepancies: n/a
Agent-Docs: updated
Configuration-Docs: n/a
Skills: n/a"
```

- [ ] **Step 4:** When merging to `develop`, stage a `README.md` release-notes entry per the branch documentation policy.

---

## Deferred / droppable

**Standalone S&K image backend** (spec §logical-ROM, "discrete, droppable step"): a config key for an optional standalone S&K ROM path + `ConfigCatalog` metadata (required for any new `SonicConfiguration` constant; place the key in a normal section BEFORE the `debug.*` block, and keep `TestConfigCatalog` green) + S&K header detection ("SONIC & KNUCKLES" in the ROM name field, see `Sonic2RomDetector.canHandle` for the pattern) + preferring that backend in `LogicalRomResolver`. Ship only if the core slice lands with room to spare; the combined-ROM backend alone satisfies KiS2.

**Explicitly out of scope** (tracked in `BRANCH_DIFFS.md` as catalogued-deferred): title screen / level-select flow, special stages with Knuckles, Super Knuckles, 2P-mode differences, KiS2 trace capture.
