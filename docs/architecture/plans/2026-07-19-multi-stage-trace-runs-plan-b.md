# Multi-Stage Trace Runs — Plan (b): Gumball/Pachinko Per-Segment Headless Slice

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the headless replay slice for S3K gumball and pachinko (glowing-sphere) bonus-stage trace segments — the bonus-aware bootstrap seam (spec engine-side addition #7), skip-if-missing replay tests, a ROM-backed boot smoke test that runs today, and the recording workflow docs — per plan (b) of `docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md`.

**Architecture:** Bonus zones are real engine zones (0x13/0x14 map to `Sonic3kZoneRegistry` entries 19/20 with `LevelData` start positions), so `AbstractTraceReplayTest`'s existing fixture path loads them. The only new production seam is `TraceReplaySessionBootstrap.applyBonusStageEntry(...)`: post-load provider registration + `onEnter` + HUD/ring preconditions + the pachinko bootstrap-object injection — mirroring what `GameLoop.doEnterBonusStage`/`prepareBonusStageForTitleCard` do in live play, minus title card and music. `onDeferredSetupComplete` is a no-op for these two types (slots-only) and is NOT called. Comparison uses the unmodified level-schema `TraceBinder`.

**Tech Stack:** Java 21 + JUnit 5 (Jupiter only). No lua changes in this plan (the recorder shipped in plan (a)).

## Global Constraints

- **Spec:** `docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md` (plan (b) scope: gumball/pachinko per-segment headless slice + bootstrap branch, addition #7). Slots is a LATER plan — do not touch `S3kSlotBonusStageRuntime` or call `onDeferredSetupComplete`.
- **Comparison-only invariant:** no per-frame hydration from trace data. Bootstrap-time seeding is limited to the established "load save state" set; this plan adds frame-0 ring count (from `TraceFrame.rings()` at frame 0) to that set for bonus segments — it models the ROM's `Saved_ring_count` restore on bonus entry and is applied once before frame 0, like start position.
- No zone/route/frame carve-outs: the bootstrap branch gates on `"s3k_bonus_stage".equals(meta.traceProfile())` (a data-driven profile discriminator, the approved pattern), never on zone id in shared code.
- JUnit 5 / Jupiter only.
- **Commit policy:** trailer block on every commit; `feat`/`fix` touching `src/main/` → `Changelog: updated` + staged CHANGELOG.md (CRLF — verify `git diff CHANGELOG.md` shows only your lines). Stage by exact path; never `git add -A`. End every commit message with:
  `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` and
  `Claude-Session: https://claude.ai/code/session_01LPPPMPSUQBgYpxpA82bad5`
- Maven in PowerShell/Git Bash: quote `-D` args; mvn runs need sandbox off (lwjgl.dll).
- Recordings do not exist yet: replay tests MUST land skip-if-missing (`Assumptions.assumeTrue` on trace dir/payload — the mechanism `AbstractTraceReplayTest.replayMatchesTrace` already uses at lines 160-183). The Task 3 smoke test must pass TODAY with only the ROM present.

---

### Task 1: `applyBonusStageEntry` bootstrap seam (spec addition #7)

**Files:**
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java` (new public static method + private helpers)
- Modify: `src/main/java/com/openggf/GameLoop.java` (widen `resolveBonusStageBootstrapSpawn` from package-private static to **public** static — factory only, no behavior change)
- Test: `src/test/java/com/openggf/trace/replay/TestTraceReplaySessionBootstrapBonus.java` (new; unit-level, no ROM)

**Interfaces:**
- Consumes: `TraceMetadata.traceProfile()/bonusStageType()` (plan (a)), `TraceData.getFrame(0).rings()`, `GameServices.module().getBonusStageProvider()` (`GameModule.java:132`), `SessionManager.getCurrentGameplayMode()` (`SessionManager.java:112` — GameServices has no public gameplayMode accessor), `GameplayModeContext.setActiveBonusStageProvider(provider)` (`:643`) + `registerBonusStageAdapter(provider)` (`:542`), `BonusStageProvider.onEnter(BonusStageType, BonusStageState)`, `BonusStageState` 17-component record (`com/openggf/game/BonusStageState.java:9-26`), `GameLoop.resolveBonusStageBootstrapSpawn(type)` (`GameLoop.java:1758`, returns the `PachinkoEnergyTrapObjectInstance` spawn for GLOWING_SPHERE, null otherwise), `LevelManager.setBonusStageHudLayout(true)`, `ObjectManager.addDynamicObject`.
- Produces: `public static boolean TraceReplaySessionBootstrap.applyBonusStageEntry(TraceData trace)` — returns false (no-op) unless the profile matches; throws `IllegalStateException` for unsupported `bonus_stage_type` values (anything other than `"gumball"`/`"pachinko"`). Task 2's test hook calls it.

- [ ] **Step 1: Write the failing unit test** (profile gating + type mapping only — the full boot path is Task 3's ROM test):

```java
package com.openggf.trace.replay;

import com.openggf.game.BonusStageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceReplaySessionBootstrapBonus {

    @Test
    void bonusTypeMappingCoversGumballAndPachinko() {
        assertEquals(BonusStageType.GUMBALL,
            TraceReplaySessionBootstrap.bonusStageTypeForToken("gumball"));
        assertEquals(BonusStageType.GLOWING_SPHERE,
            TraceReplaySessionBootstrap.bonusStageTypeForToken("pachinko"));
        assertThrows(IllegalStateException.class,
            () -> TraceReplaySessionBootstrap.bonusStageTypeForToken("slots"));
        assertThrows(IllegalStateException.class,
            () -> TraceReplaySessionBootstrap.bonusStageTypeForToken(null));
    }
}
```

(Package `com.openggf.trace.replay` in the TEST tree so the package-visible mapper is reachable.)

- [ ] **Step 2: Run it** — `mvn "-Dtest=com.openggf.trace.replay.TestTraceReplaySessionBootstrapBonus" test` — expect COMPILE FAILURE (method missing).

- [ ] **Step 3: Implement.** In `TraceReplaySessionBootstrap`:

```java
    /** Maps the recorder's bonus_stage_type token to the engine enum. */
    static BonusStageType bonusStageTypeForToken(String token) {
        if ("gumball".equals(token)) {
            return BonusStageType.GUMBALL;
        }
        if ("pachinko".equals(token)) {
            return BonusStageType.GLOWING_SPHERE;
        }
        throw new IllegalStateException(
            "Unsupported bonus_stage_type for headless replay: " + token);
    }

    /**
     * Post-load bonus-stage entry for an s3k_bonus_stage trace segment
     * (spec 2026-07-18, engine-side addition #7). Mirrors the live
     * doEnterBonusStage/prepareBonusStageForTitleCard sequence minus title
     * card and music: registers the module's bonus provider on the gameplay
     * mode, fires onEnter with a synthetic BonusStageState (frame-0 ring
     * count from the trace; interior replay never exits, so return fields
     * are zero), applies the bonus HUD layout and ring count, un-hides the
     * player, and injects the pachinko bootstrap object when the type needs
     * one. Returns false untouched for any other trace profile.
     */
    public static boolean applyBonusStageEntry(TraceData trace) {
        TraceMetadata meta = trace.metadata();  // accessor is metadata(), not getMetadata()
        if (!"s3k_bonus_stage".equals(meta.traceProfile())) {
            return false;
        }
        BonusStageType type = bonusStageTypeForToken(meta.bonusStageType());
        int frame0Rings = trace.getFrame(0).rings();

        BonusStageProvider provider = GameServices.module().getBonusStageProvider();
        // GameServices has NO public gameplayMode() accessor — resolve the
        // context the way the live path does (GameLoop.doEnterBonusStage).
        // The replay fixture guarantees an open gameplay session, so a null
        // here is a real fixture bug and an NPE is the correct failure.
        GameplayModeContext gameplayMode = SessionManager.getCurrentGameplayMode();
        // Mirror the live ordering exactly (GameLoop.java:2178/2181/2184):
        // setActiveBonusStageProvider -> onEnter -> registerBonusStageAdapter.
        gameplayMode.setActiveBonusStageProvider(provider);
        provider.onEnter(type, new BonusStageState(
            0, 0, frame0Rings, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            (byte) 0x0C, (byte) 0x0D, 0, 0L));
        gameplayMode.registerBonusStageAdapter(provider);

        // Rings live on LevelState, not GameStateManager — same call the live
        // path makes (GameLoop.prepareBonusStageForTitleCard, :2274).
        GameServices.level().getLevelGamestate().setRings(frame0Rings);
        GameServices.level().setBonusStageHudLayout(true);
        for (var sprite : GameServices.sprites().getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                playable.setHidden(false);
                playable.setObjectControlled(false);
            }
        }
        ObjectSpawn bootstrapSpawn = GameLoop.resolveBonusStageBootstrapSpawn(type);
        var objectManager = GameServices.level().getObjectManager();
        if (bootstrapSpawn != null && objectManager != null) {
            // Mirror ensureBonusStageBootstrapObjectPresent's duplicate guard
            // (GameLoop.java:2288-2293).
            boolean present = objectManager.getActiveObjects().stream()
                .anyMatch(PachinkoEnergyTrapObjectInstance.class::isInstance);
            if (!present) {
                objectManager.addDynamicObject(
                    new PachinkoEnergyTrapObjectInstance(bootstrapSpawn));
            }
        }
        return true;
    }
```

Use the 16-arg compact `BonusStageState` constructor (`BonusStageState.java:28`, verified 16 args with `topSolidBit`/`lrbSolidBit` as bytes at positions 13/14) — it omits `meanWaterLevel`. Add the needed imports (`BonusStageProvider`, `BonusStageType`, `BonusStageState`, `GameplayModeContext`, `SessionManager` from `com.openggf.game.session`, `ObjectSpawn`, `PachinkoEnergyTrapObjectInstance`, `AbstractPlayableSprite`, `GameLoop`). In `GameLoop.java`, change `static ObjectSpawn resolveBonusStageBootstrapSpawn(` to `public static ObjectSpawn resolveBonusStageBootstrapSpawn(` — nothing else.

The player-unhide loop deliberately mirrors `GameLoop.restorePlayableStateForBonusTitleCard` (`GameLoop.java:2297-2301`). Two live-path calls are deliberately SKIPPED as render-only (headless comparison never reads them) — `forcePlayerHighPriorityInBonusStage` (high-priority art bucket) and `refreshPlayableSpriteArtCaches` (DPLC cache); say so in a one-line comment.

- [ ] **Step 4: Run the unit test** — PASS. Also compile the tree: `mvn "-Dtest=com.openggf.trace.replay.TestTraceReplaySessionBootstrapBonus" test` compiles src/main.

- [ ] **Step 5: CHANGELOG + commit** — CHANGELOG line: `- Trace replay: bonus-stage bootstrap seam (applyBonusStageEntry) for s3k_bonus_stage segments.`

```bash
git add src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java src/main/java/com/openggf/GameLoop.java src/test/java/com/openggf/trace/replay/TestTraceReplaySessionBootstrapBonus.java CHANGELOG.md
git commit -m "feat(trace): bonus-stage bootstrap seam for s3k_bonus_stage replay" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 2: Replay-test hook + skip-if-missing bonus replay tests

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java` (one protected no-op hook)
- Create: `src/test/java/com/openggf/tests/trace/s3k/AbstractS3kBonusStageTraceReplayTest.java`
- Create: `src/test/java/com/openggf/tests/trace/s3k/TestS3kGumballBonusTraceReplay.java`
- Create: `src/test/java/com/openggf/tests/trace/s3k/TestS3kPachinkoBonusTraceReplay.java`

**Interfaces:**
- Consumes: `TraceReplaySessionBootstrap.applyBonusStageEntry(TraceData)` (Task 1); `AbstractTraceReplayTest`'s existing template methods (`zone()`, `act()`, `traceDirectory()` — read the class to confirm the exact template-method names concrete tests override, and use those).
- Produces: `protected void afterFixtureBuild(TraceData trace)` hook on `AbstractTraceReplayTest` (no-op default), called once immediately after the fixture is built and BEFORE `applyStartPositionAndGroundSnap`/`applyBootstrap`; trace dirs `src/test/resources/traces/s3k/bonus_gumball/` and `.../bonus_pachinko/` (not created in this plan — tests skip).

- [ ] **Step 1: Add the hook.** In `AbstractTraceReplayTest.replayMatchesTrace`, directly after the fixture build completes (the statement at ~line 208-215 that produces the fixture; locate by content) and before the `applyStartPositionAndGroundSnap` call at ~line 216, insert `afterFixtureBuild(trace);` and add:

```java
    /**
     * Post-fixture-build hook for profile-specific entry setup (e.g. bonus
     * stage provider registration). Default: no-op.
     */
    protected void afterFixtureBuild(TraceData trace) {
    }
```

If the S3K replay path (`replayS3kTrace`) builds its fixture in a separate branch, add the same single call there too — the hook must run for whichever branch a bonus test takes; check which branch a zone-0x13 S3K test flows through and say so in the report.

- [ ] **Step 2: The abstract bonus test:**

```java
package com.openggf.tests.trace.s3k;

import com.openggf.trace.TraceData;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.tests.trace.AbstractTraceReplayTest;

/**
 * Shared base for S3K bonus-stage trace replay (gumball/pachinko).
 * Bonus zones run on the LEVEL pipeline, so the entire level replay stack
 * applies; the only addition is the bonus-entry bootstrap after load.
 */
public abstract class AbstractS3kBonusStageTraceReplayTest extends AbstractTraceReplayTest {

    @Override
    protected void afterFixtureBuild(TraceData trace) {
        TraceReplaySessionBootstrap.applyBonusStageEntry(trace);
    }
}
```

Concrete classes carry `@RequiresRom(SonicGame.SONIC_3K)` (the convention on every existing concrete S3K replay test, e.g. `TestS3kCnzCompleteRunTraceReplay.java:2-8` — without it, a missing ROM hard-fails the fixture build instead of skipping once recordings land) and override the real template methods `game()` (returns the `SonicGame` enum value for S3K, not a string), `zone()` = 0x13 (gumball) / 0x14 (pachinko), `act()` = 0, and `traceDirectory()` = trace dirs `traces/s3k/bonus_gumball` / `traces/s3k/bonus_pachinko` — copy the exact override set from an existing concrete S3K test (e.g. `TestS3kCnzCompleteRunTraceReplay` or the `s3k` package's dedicated-zone test; match whichever template methods it overrides, including any required-checkpoint/report-name overrides, adapting values).

- [ ] **Step 3: Run both new test classes** — expect SKIPPED (assumption failure on missing trace dir), NOT failure: `mvn "-Dtest=com.openggf.tests.trace.s3k.TestS3kGumballBonusTraceReplay+TestS3kPachinkoBonusTraceReplay" test` → surefire shows skipped=…, failed=0.

- [ ] **Step 4: Commit** (test-only):

```bash
git add src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java src/test/java/com/openggf/tests/trace/s3k/AbstractS3kBonusStageTraceReplayTest.java src/test/java/com/openggf/tests/trace/s3k/TestS3kGumballBonusTraceReplay.java src/test/java/com/openggf/tests/trace/s3k/TestS3kPachinkoBonusTraceReplay.java
git commit -m "test(trace): skip-if-missing S3K bonus-stage replay tests + fixture hook" -m "Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 3: ROM-backed headless boot smoke test (runs today)

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS3kBonusStageHeadlessBoot.java`

**Interfaces:**
- Consumes: the same fixture path the replay tests use (`HeadlessTestFixture` builder + `withZoneAndAct`), `TraceReplaySessionBootstrap.applyBonusStageEntry` — but since no trace exists, this test exercises the SEAM's effects by building a minimal in-memory `TraceData`? NO — keep it simpler and honest: this test boots the zones and performs the same entry actions via the production pieces directly (provider resolve + onEnter + HUD + injection via `GameLoop.resolveBonusStageBootstrapSpawn`), then steps frames. It validates the boot path that `applyBonusStageEntry` performs, without fabricating a TraceData.
- Model it on an existing ROM-gated headless S3K test: read `TestS3kAiz1SkipHeadless` first and copy its ROM-availability gating (assumeTrue on the ROM path/property), singleton reset pattern (`@FullReset` / `SingletonResetExtension`), module bootstrap, and fixture construction idioms exactly.

- [ ] **Step 1: Write the test.** Two test methods (gumball, pachinko). Shape (adapt to the copied idioms — the assertions are the contract):

```java
@Test
void gumballZoneBootsHeadlesslyWithMachineFromLayout() {
    // fixture with zone 0x13 act 0, S3K module, team sonic(+tails per default config)
    // then perform the FULL bonus-entry sequence in applyBonusStageEntry's order:
    // setActiveBonusStageProvider(provider) -> onEnter(GUMBALL, state) ->
    // registerBonusStageAdapter(provider) -> HUD layout -> rings 25.
    // (GameServices.bonusStage() returns the gameplayMode's ACTIVE provider —
    // null until setActiveBonusStageProvider runs, so the assertion below
    // NPEs if that step is skipped.)
    // step 60 idle frames via the fixture's frame stepper
    // getActiveType() lives on AbstractBonusStageCoordinator (:85), NOT the
    // BonusStageProvider interface — the cast is required to compile.
    assertEquals(BonusStageType.GUMBALL,
        ((AbstractBonusStageCoordinator) GameServices.bonusStage()).getActiveType());
    assertTrue(objectManager.getActiveObjects().stream()
        .anyMatch(GumballMachineObjectInstance.class::isInstance),
        "gumball machine object must load from the ROM level layout");
    // player alive, on the level pipeline, at a sane position (y within level bounds)
}

@Test
void pachinkoZoneBootsHeadlesslyWithInjectedTrap() {
    // fixture with zone 0x14 act 0; entry actions for GLOWING_SPHERE incl.
    // GameLoop.resolveBonusStageBootstrapSpawn injection
    assertEquals(BonusStageType.GLOWING_SPHERE,
        ((AbstractBonusStageCoordinator) GameServices.bonusStage()).getActiveType());
    assertTrue(objectManager.getActiveObjects().stream()
        .anyMatch(PachinkoEnergyTrapObjectInstance.class::isInstance),
        "pachinko energy trap must be injected");
    // step 60 idle frames without exceptions
}
```

Every assertion must check real engine state (no assertion-free diagnostics — repo guard `TestNoAssertionFreeDiagnostics`). Verify the exact accessor for the active bonus type (`GameServices.bonusStage()` vs provider getter — grep `getActiveType` and use the real one).

- [ ] **Step 2: Run** — `mvn "-Dtest=com.openggf.tests.TestS3kBonusStageHeadlessBoot" test` with the ROM present at repo root: both tests PASS. If a boot gap surfaces (e.g. missing art/PLC for bonus zones headlessly), STOP and report DONE_WITH_CONCERNS or BLOCKED with the exact failure — that discovery is precisely this task's purpose; do not paper over it.

- [ ] **Step 3: Commit** (test-only, same trailer shape as Task 2, subject `test(s3k): headless bonus-zone boot smoke coverage`).

---

### Task 4: Recording workflow documentation

**Files:**
- Modify: `tools/bizhawk/README.md` (new subsection under the run-manifest section from plan (a))

**Content:** "Recording S3K bonus round-trip traces" — the exact human + tooling procedure:
1. In BizHawk 2.11 (Genplus-gx), record a bk2 movie: play AIZ1 from power-on, collect 50–64 rings for gumball or 35–49 for pachinko (selector `((rings-20)/15)%3`: 2→GUMBALL, 1→GLOWING_SPHERE — ROM `sonic3k.asm:61891-61920`; warning: 20–34 rings selects slot-machine, deferred), hit a star post, jump into the star circle, play the bonus stage to its exit, land back in the level, run a few seconds to re-arm control, stop the movie. Save as `s3k-aiz-gumball.bk2` / `s3k-aiz-pachinko.bk2`.
2. Run the plan-(a) recorder over the movie: `run_bizhawk_lua.bat` + the movie + `s3k_complete_run_recorder.lua` with `OGGF_TRACE_OUTPUT_DIR=<scratch>` and `OGGF_TRACE_RUN_ID=s3k-aiz-gumball-roundtrip` (ditto pachinko).
3. Expected output: `run_manifest.json` + `aiz/` + `gumball/` (or `pachinko/`) + `aiz_2/` segment dirs; the bonus segment's metadata carries `trace_profile: "s3k_bonus_stage"`.
4. Commit layout: gzip `physics.csv`/`aux_state.jsonl` per segment; place the bonus segment at `src/test/resources/traces/s3k/bonus_gumball/` (`bonus_pachinko/`) with the bk2 alongside (or in `_movies/` with `source_bk2`), keeping the run dir + manifest under `src/test/resources/traces/s3k/runs/<run_id>/` for the plan-(c) chain test.
5. The skip-if-missing tests (`TestS3kGumballBonusTraceReplay`, `TestS3kPachinkoBonusTraceReplay`) activate automatically once the dirs exist.

- [ ] **Step 1: Write it** (match the README's existing style; line-ending-clean diff).
- [ ] **Step 2: Commit** (docs-only, all-n/a trailers, subject `docs: S3K bonus round-trip recording procedure`).

---

### Task 5: Gate + frontier log

- [ ] **Step 1:** Full suite `mvn test` (sandbox off): no NEW failures vs the develop baseline (baseline: 29F/6E pre-existing, recorded in the plan-(a) ledger).
- [ ] **Step 2:** `docs/status/trace-frontier-log.md` entry: plan-(b) slice landed; bonus replay tests present-but-skipped pending recordings (name the two bk2s needed); smoke-boot coverage green.
- [ ] **Step 3:** Commit docs (all-n/a trailers), subject `docs: record bonus-slice replay scaffolding status`.

**Merge-time reminder:** merging into develop needs a staged README.md release-log line (repo policy).

## Plan-level notes

- **What this plan does NOT do:** no recordings (human action — the two bk2s above), no comparator changes (level `TraceBinder` used as-is), no slots, no chained driver (plan (c)), no catalog/visual work (plan (d)).
- **Honest DoD:** the pipeline is proven up to "engine boots the bonus zones headlessly and the replay stack accepts the profile"; end-to-end trace replay proof activates when the recordings land — the same skip-if-missing posture the S3K complete-run suite used for unimplemented zones.
