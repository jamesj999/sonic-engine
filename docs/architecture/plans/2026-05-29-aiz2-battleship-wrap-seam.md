# AIZ2 Battleship Wrap-Seam Faithful Fix — Implementation Plan (Rev 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `$80` post-bombing wrap approximation with the true ROM `$200` camera/object wrap, made visually seamless by looping only the `$200` forest background during the battleship loop (excluding the empty `$200–$400` BG filler that currently scrolls into view).

**Architecture:** Restore the ROM `$200` camera/object renormalization. During the post-bombing loop only, enable the existing wrapped-BG build path for AIZ so `BGTextureWidth` becomes the BG period (`$200`) and the BG cache is built from the first `$200` of source (the forest) — `bgTilemapBaseX` stays `0` and the existing continuous `smoothScrollX` deform scrolls/wraps it. No camera-following window, no source-query normalization (a composition trace proved those would inject a `$200` BG jump). All AIZ-specific decisions live in `Sonic3kAIZEvents`/`AizZoneRuntimeState`/`Sonic3kZoneFeatureProvider`; shared BG code consumes a semantic predicate only.

**Tech Stack:** Java 21, Maven, JUnit 5, `HeadlessTestFixture`, S3K ROM-gated tests (`-Ds3k.rom.path`). Reference: `docs/architecture/designs/2026-05-29-aiz2-battleship-wrap-seam-design.md` (Rev 2).

---

## Investigation findings (Task 0 — already completed)

A headless spike established (and the spike code was reverted, tree clean):

- AIZ2 BG layer = 8 blocks `$400` wide, 128px blocks; **forest = columns 0–3
  (`$0–$200`)**, columns 4–7 (`$200–$400`) are **empty filler** (`0xbf` only).
- Renderer/shader composition:
  `fboX = mod((gameX − bgTilemapBaseX) − hScrollThis, BGTextureWidth)`.
  For AIZ today `bgTilemapBaseX = 0` (no `getBgCameraX` override), `hScrollThis`
  comes from continuous `smoothScrollX`, and `bgWrap == false` →
  `BGTextureWidth = $400`. So the BG wraps at `$400` and the empty filler scrolls
  into view = the seam.
- **AIZ trace baseline (current tree, pre-change):** `TestS3kAizTraceReplay`
  first error frame **8941** (`camera_y` expected=`0x02C1` actual=`0x02B8`),
  243 errors / 23 warnings. This is the Task 5 comparison point.
- **Gate: PASS.** Loop period `$200`, forest origin 0; the fix loops the forest
  and excludes the filler.

---

## File Structure

- `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java` — restore `$200` wrap; remove `$80` constant; add `isBattleshipForestLoopActive()` and `getForestLoopBgWrapPeriod()`.
- `src/main/java/com/openggf/game/sonic3k/runtime/AizZoneRuntimeState.java` — delegate the two new signals.
- `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java` — add a narrow AIZ clause to `bgWrapsHorizontally()` (mirror `isCnzBossBackgroundWindowActive`).
- `src/main/java/com/openggf/level/LevelManager.java` — set `currentBgPeriodWidth` to the loop period during the loop (robustness guard) in `applyBackgroundTilemapWindowSelection`.
- `src/test/java/com/openggf/tests/TestS3kAiz2SidekickBoundsSync.java` — reinstate exact `0x44C0` wrap assertion.
- `src/test/java/com/openggf/game/sonic3k/events/TestSonic3kAizForestLoopSignals.java` *(new)* — predicate/period unit tests.
- `src/test/java/com/openggf/game/sonic3k/TestSonic3kAizBgWrapActivation.java` *(new)* — bgWrap activation + loop BG-window forest-only guard.
- `docs/S3K_KNOWN_DISCREPANCIES.md`, `CHANGELOG.md`, `docs/status/trace-frontier-log.md` — cleanup/attestation.

---

## Task 1: Restore the ROM `$200` post-bombing wrap

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java` (wrap-delta ~line 1625; constant ~line 200)
- Test: `src/test/java/com/openggf/tests/TestS3kAiz2SidekickBoundsSync.java` (~line 105)

- [ ] **Step 1: Tighten the test to the ROM value (red).** In
  `aizBattleshipPrePhysicsWrapRefreshesSidekickBoundsBeforeMovement`, replace the
  relaxed boundary check with the exact ROM result:

```java
        // ROM AIZ2_DoShipLoop subtracts $200 on wrap; from boundary $46C0 the
        // post-wrap Camera_max_X_pos is $46C0 - $200 = $44C0.
        assertEquals(0x44C0, fixture.camera().getMaxX() & 0xFFFF,
                "AIZ2_DoShipLoop wraps Camera_max_X_pos back by the ROM $200 before Process_Sprites");
        assertEquals(0x44C0, controller.getMaxXBound(Integer.MIN_VALUE) & 0xFFFF,
                "S3K sidekick boundary mirror must be refreshed immediately after the pre-physics camera wrap");
```

(If the current assertions use `assertTrue(... < 0x46C0 ...)` / a relaxed form,
replace them; ensure `assertEquals` is imported.)

- [ ] **Step 2: Run to verify it fails.**

Run: `mvn -Dmse=off "-Dsurefire.forkCount=1" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=com.openggf.tests.TestS3kAiz2SidekickBoundsSync#aizBattleshipPrePhysicsWrapRefreshesSidekickBoundsBeforeMovement" test "-DfailIfNoTests=false"`
Expected: FAIL — actual `0x4640` (current `$80`), expected `0x44C0`.

- [ ] **Step 3: Restore the `$200` wrap.** In
  `Sonic3kAIZEvents.updateBattleshipAutoScroll`, replace:

```java
            int wrapDelta = battleshipWrapX == BATTLESHIP_WRAP_X_BOMBING
                    ? BATTLESHIP_WRAP_DIST
                    : BATTLESHIP_WRAP_DIST_POST_BOMBING;
```

with:

```java
            int wrapDelta = BATTLESHIP_WRAP_DIST; // ROM AIZ2_DoShipLoop subtracts $200 in both phases
```

Delete the unused constant `private static final int BATTLESHIP_WRAP_DIST_POST_BOMBING = 0x80;` (~line 200).

- [ ] **Step 4: Run to verify it passes.**

Run: `mvn -Dmse=off "-Dsurefire.forkCount=1" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=com.openggf.tests.TestS3kAiz2SidekickBoundsSync" test "-DfailIfNoTests=false"`
Expected: PASS (both methods).

- [ ] **Step 5: Commit.**

```bash
git add src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java src/test/java/com/openggf/tests/TestS3kAiz2SidekickBoundsSync.java
git commit -m "fix(s3k): restore ROM \$200 AIZ2 battleship post-bombing wrap

Removes BATTLESHIP_WRAP_DIST_POST_BOMBING (\$80) so the post-bombing
camera/object wrap matches ROM AIZ2_DoShipLoop (\$200). The BG-loop fix that
keeps this seamless follows next.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: Add the forest-loop predicate + period signal

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/runtime/AizZoneRuntimeState.java`
- Test: `src/test/java/com/openggf/game/sonic3k/events/TestSonic3kAizForestLoopSignals.java` *(new)*

- [ ] **Step 1: Write the failing unit test.** Create the file:

```java
package com.openggf.game.sonic3k.events;

import com.openggf.game.sonic3k.Sonic3kLoadBootstrap;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kAizForestLoopSignals {

    @Test
    void forestLoopActiveOnlyDuringPostBombingAutoScroll() {
        Sonic3kAIZEvents events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);

        events.setBattleshipAutoScrollActiveRaw(false);
        events.setBattleshipWrapX(0x46C0);
        assertFalse(events.isBattleshipForestLoopActive(),
                "No loop while auto-scroll is inactive");

        events.setBattleshipAutoScrollActiveRaw(true);
        events.setBattleshipWrapX(0x4440);
        assertFalse(events.isBattleshipForestLoopActive(),
                "No forest loop during the bombing phase");

        events.setBattleshipWrapX(0x46C0);
        assertTrue(events.isBattleshipForestLoopActive(),
                "Forest loop active post-bombing with auto-scroll running");
    }

    @Test
    void forestLoopPeriodIsThe200Window() {
        Sonic3kAIZEvents events = new Sonic3kAIZEvents(Sonic3kLoadBootstrap.NORMAL);
        assertEquals(0x200, events.getForestLoopBgWrapPeriod(),
                "Forest loop BG period is the ROM $200 (the forest occupies BG cols 0-3)");
    }
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `mvn -Dmse=off "-Dsurefire.forkCount=1" "-Dtest=com.openggf.game.sonic3k.events.TestSonic3kAizForestLoopSignals" test "-DfailIfNoTests=false"`
Expected: FAIL — methods do not exist (compile error).

- [ ] **Step 3: Add the signals.** In `Sonic3kAIZEvents.java`, near the battleship
  constants (~line 200) add:

```java
    /** AIZ2 forest loop BG period: the forest occupies BG cols 0-3 = the first $200. */
    public static final int FOREST_LOOP_BG_PERIOD = 0x200;
```

Near the battleship accessors (~line 1739, by `isBattleshipAutoScrollActive`) add:

```java
    /**
     * True only while the post-bombing forest loop is running: auto-scroll active
     * AND the wrap boundary has moved to the post-bombing $46C0 (set by
     * {@link #onBattleshipComplete()}). Narrower than
     * {@link #isBattleshipForestFrontPhaseActive()} (a display-bucket predicate
     * spanning $4380..$4880) so the BG-loop override is scoped to the loop only.
     */
    public boolean isBattleshipForestLoopActive() {
        return battleshipAutoScrollActive
                && battleshipWrapX == BATTLESHIP_WRAP_X_POST_BOMBING;
    }

    public int getForestLoopBgWrapPeriod() {
        return FOREST_LOOP_BG_PERIOD;
    }
```

- [ ] **Step 4: Delegate via `AizZoneRuntimeState`.** In `AizZoneRuntimeState.java`,
  after `isBattleshipForestFrontPhaseActive()` (line 30) add:

```java
    public boolean isBattleshipForestLoopActive() { return events.isBattleshipForestLoopActive(); }
    public int getForestLoopBgWrapPeriod() { return events.getForestLoopBgWrapPeriod(); }
```

- [ ] **Step 5: Run to verify it passes.**

Run: `mvn -Dmse=off "-Dsurefire.forkCount=1" "-Dtest=com.openggf.game.sonic3k.events.TestSonic3kAizForestLoopSignals" test "-DfailIfNoTests=false"`
Expected: PASS.

- [ ] **Step 6: Commit.**

```bash
git add src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java src/main/java/com/openggf/game/sonic3k/runtime/AizZoneRuntimeState.java src/test/java/com/openggf/game/sonic3k/events/TestSonic3kAizForestLoopSignals.java
git commit -m "feat(s3k): add AIZ2 forest-loop predicate + BG period signal

Narrow isBattleshipForestLoopActive() (post-bombing, wrapX==\$46C0) and
getForestLoopBgWrapPeriod()=\$200, delegated through AizZoneRuntimeState;
consumed by the BG-loop activation next.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Loop the `$200` forest BG during the battleship loop

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java` (`bgWrapsHorizontally`, ~line 117)
- Modify: `src/main/java/com/openggf/level/LevelManager.java` (`applyBackgroundTilemapWindowSelection`, ~line 1815 — period guard)
- Test: `src/test/java/com/openggf/game/sonic3k/TestSonic3kAizBgWrapActivation.java` *(new)*

- [ ] **Step 1: Write the failing test.** ROM-gated; drives the loop state with
  the side-effect-free setters (not `onBattleshipComplete()`), refreshes parallax
  after camera moves (`getBgCameraX()`/window are cache-refreshed by parallax
  update), rebuilds the BG tilemap, and asserts the visible BG window is
  forest-only and stable across a `$200` camera renormalization:

```java
package com.openggf.game.sonic3k;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kAizBgWrapActivation {

    @AfterEach
    void tearDown() { com.openggf.game.session.SessionManager.clear(); }

    @Test
    void aizForestLoopWrapsBgAtThe200ForestWindowExcludingFiller() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_AIZ, 1)
                .build();
        Sonic3kZoneFeatureProvider provider =
                (Sonic3kZoneFeatureProvider) GameServices.module().getZoneFeatureProvider();
        Sonic3kAIZEvents events =
                ((Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider()).getAizEvents();

        // Outside the loop, AIZ keeps the full-width BG (no horizontal wrap).
        events.setBattleshipAutoScrollActiveRaw(false);
        assertFalse(provider.bgWrapsHorizontally(),
                "AIZ must not wrap BG horizontally outside the forest loop");

        // Enter the forest loop with the side-effect-free setters.
        events.setBattleshipAutoScrollActiveRaw(true);
        events.setBattleshipWrapX(0x46C0);
        assertTrue(provider.bgWrapsHorizontally(),
                "AIZ must wrap BG horizontally during the post-bombing forest loop");

        // Build the BG window just below the wrap boundary and again after a $200
        // renormalization; the visible window must be forest-only and unchanged.
        GameServices.camera().setX((short) (0x46C0 - 0x10));
        GameServices.level().recomputeParallaxAfterRewindRestore();
        GameServices.level().ensureBackgroundTilemapData();
        assertEquals(0x200, GameServices.level().getTilemapManager().getCurrentBgPeriodWidth(),
                "BG period must be the $200 forest window during the loop");
        int[] before = GameServices.level().bgVisibleSourceColumnsForTest();

        GameServices.camera().setX((short) (0x46C0 - 0x10 - 0x200));
        GameServices.level().recomputeParallaxAfterRewindRestore();
        GameServices.level().ensureBackgroundTilemapData();
        int[] after = GameServices.level().bgVisibleSourceColumnsForTest();

        // Forest-only: no source column lands in the empty filler half ($200-$400,
        // i.e. block columns 4-7) and the window is identical across the wrap.
        for (int col : before) {
            assertTrue(col * 128 < 0x200,
                    "BG window must contain only forest source columns (0-3), got col " + col);
        }
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, after,
                "Forest BG window must be identical across the $200 wrap (no seam, no filler)");
    }
}
```

> If `bgVisibleSourceColumnsForTest()` / `getCurrentBgPeriodWidth()` accessors are
> not reachable from the test, add thin package/test-visible getters:
> `getCurrentBgPeriodWidth()` already exists on `LevelTilemapManager` (line 923);
> for the visible source columns, add a small `LevelManager` accessor that returns
> the source-layout columns the current built BG window maps to (via
> `bgTilemapBaseX` + `bgXQueryOffset`), reading already-computed build state.

- [ ] **Step 2: Run to verify it fails.**

Run: `mvn -Dmse=off "-Dsurefire.forkCount=1" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=com.openggf.game.sonic3k.TestSonic3kAizBgWrapActivation" test "-DfailIfNoTests=false"`
Expected: FAIL — `bgWrapsHorizontally()` is false for AIZ; the BG is full-width `$400` and the window includes filler columns.

- [ ] **Step 3: Add the AIZ clause.** In `Sonic3kZoneFeatureProvider.java` extend
  `bgWrapsHorizontally()` (line 117-126):

```java
    @Override
    public boolean bgWrapsHorizontally() {
        var levelManager = GameServices.levelOrNull();
        if (levelManager == null) {
            return false;
        }
        int zoneId = levelManager.getFeatureZoneId();
        return zoneId == Sonic3kZoneIds.ZONE_MGZ
                || zoneId == Sonic3kZoneIds.ZONE_ICZ
                || isCnzBossBackgroundWindowActive(zoneId)
                || isAizForestLoopBackgroundWindowActive(zoneId);
    }

    private boolean isAizForestLoopBackgroundWindowActive(int zoneId) {
        if (zoneId != Sonic3kZoneIds.ZONE_AIZ || !GameServices.hasRuntime()) {
            return false;
        }
        return S3kRuntimeStates.currentAiz(GameServices.zoneRuntimeRegistry())
                .map(AizZoneRuntimeState::isBattleshipForestLoopActive)
                .orElse(false);
    }
```

Add the import `com.openggf.game.sonic3k.runtime.AizZoneRuntimeState` if missing
(`S3kRuntimeStates` is already imported for the CNZ clause).

- [ ] **Step 4: Pin the BG period to `$200` during the loop (robustness guard).**
  In `LevelManager.applyBackgroundTilemapWindowSelection` (~line 1815, after
  `newBgPeriodWidth` is initialized from `parallaxManager.getBgPeriodWidth()`),
  ensure the loop uses the forest period even if the handler default changes:

```java
        if (zoneFeatureProvider != null) {
            int forestPeriod = zoneFeatureProvider.backgroundLoopSourcePeriod(currentZone, currentAct);
            if (forestPeriod > 0) {
                newBgPeriodWidth = forestPeriod;
            }
        }
```

Add a default hook to the `ZoneFeatureProvider` interface
(`src/main/java/com/openggf/game/ZoneFeatureProvider.java`, near
`backgroundLoopBandBaseY` ~line 222):

```java
    default int backgroundLoopSourcePeriod(int zoneIndex, int actIndex) { return -1; }
```

and override it in `Sonic3kZoneFeatureProvider` to return the forest period only
during the loop:

```java
    @Override
    public int backgroundLoopSourcePeriod(int zoneIndex, int actIndex) {
        if (!isAizForestLoopBackgroundWindowActive(getFeatureZoneId())) {
            return -1;
        }
        return S3kRuntimeStates.currentAiz(GameServices.zoneRuntimeRegistry())
                .map(AizZoneRuntimeState::getForestLoopBgWrapPeriod).orElse(-1);
    }
```

- [ ] **Step 5: Verify the full-width path does not suppress the loop wrap.** The
  assertion in Step 1 (`getCurrentBgPeriodWidth() == 0x200` and forest-only
  columns) already fails if `zoneRuntimeRequiresFullWidthBgTilemap()` (the AIZ
  intro path) forces full width during the loop. If it does, exclude the loop
  phase there. Re-run Step 1's test.

- [ ] **Step 6: Run to verify it passes.**

Run: `mvn -Dmse=off "-Dsurefire.forkCount=1" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=com.openggf.game.sonic3k.TestSonic3kAizBgWrapActivation" test "-DfailIfNoTests=false"`
Expected: PASS — BG period `$200`, forest-only window, identical across the `$200` wrap.

- [ ] **Step 7: Commit.**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java src/main/java/com/openggf/game/ZoneFeatureProvider.java src/main/java/com/openggf/level/LevelManager.java src/test/java/com/openggf/game/sonic3k/TestSonic3kAizBgWrapActivation.java
# include LevelManager.java if a bgVisibleSourceColumnsForTest accessor was added
git commit -m "feat(s3k): loop the \$200 forest BG during the AIZ2 battleship loop

Enables the wrapped-BG build path for AIZ only during isBattleshipForestLoopActive()
(mirroring the CNZ-boss precedent) so BGTextureWidth becomes the \$200 forest period
and the empty \$200-\$400 filler is no longer built or scrolled into view. Window
base stays 0; the existing continuous smoothScrollX deform scrolls/wraps it
seamlessly. Pins currentBgPeriodWidth to the forest period during the loop.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Trace check + frontier log

**Files:**
- Modify (if frontier moves): `docs/status/trace-frontier-log.md`

- [ ] **Step 1: Recall the baseline.** Pre-change AIZ first error = frame **8941**
  (`camera_y` expected=`0x02C1` actual=`0x02B8`), recorded in the investigation
  findings above on the current tree.

- [ ] **Step 2: Run the AIZ trace with the change.**

Run: `mvn -Dmse=off "-Dsurefire.forkCount=1" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=com.openggf.tests.trace.s3k.TestS3kAizTraceReplay#replayMatchesTrace" test "-DfailIfNoTests=false"`
Expected: a first-error frame. Acceptance: **no new earlier mismatch than f8941
unless explained by the intended `$200` wrap change** (inspect the first-error
field/frame; the `$200` restore changes camera/object coordinates and may move
the frontier either direction).

- [ ] **Step 3: Update the frontier log if it moved.** Append a dated entry to
  `docs/status/trace-frontier-log.md` with the command, commit context, pass/fail, error
  count, and first-error frame/field, plus the `$200`-wrap explanation. If
  unchanged, no edit.

- [ ] **Step 4: Commit (only if the log changed).**

```bash
git add docs/status/trace-frontier-log.md
git commit -m "docs(s3k): record AIZ trace frontier after \$200 wrap restore

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Cleanup, discrepancy removal, CHANGELOG, full suite

**Files:**
- Modify: `docs/S3K_KNOWN_DISCREPANCIES.md` (remove the AIZ2 wrap entry + TOC line)
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Remove the discrepancy entry.** In `docs/S3K_KNOWN_DISCREPANCIES.md`,
  delete the "AIZ2 Battleship Post-Bombing Wrap Distance" section (and its
  preceding `---`) and its Table-of-Contents line `8. [AIZ2 Battleship
  Post-Bombing Wrap Distance](...)`.

- [ ] **Step 2: Add a CHANGELOG entry.** Under `## Unreleased` in `CHANGELOG.md`:

```markdown
- **AIZ2 battleship background loop is now seamless at the true ROM $200 wrap.**
  Restored the ROM AIZ2_DoShipLoop $200 camera/object renormalization (was a $80
  visual approximation) and made the AIZ2 background loop only the $200 forest
  during the battleship sequence, so the empty filler beyond it is no longer
  scrolled into view. Removes the former "AIZ2 Battleship Post-Bombing Wrap
  Distance" S3K known-discrepancy.
```

- [ ] **Step 3: Run the full default suite.**

Run: `mvn clean test -Dmse=relaxed`
Expected: `MSE:OK` — 0 failed, 0 errors (ArchUnit unaffected).

- [ ] **Step 4: Run the AIZ guard + activation + frontier-green set together.**

Run: `mvn -Dmse=off "-Dsurefire.forkCount=1" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen" "-Dtest=com.openggf.tests.TestS3kAiz2SidekickBoundsSync,com.openggf.game.sonic3k.TestSonic3kAizBgWrapActivation,com.openggf.game.sonic3k.events.TestSonic3kAizForestLoopSignals,com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading" test "-DfailIfNoTests=false"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add docs/S3K_KNOWN_DISCREPANCIES.md CHANGELOG.md
git commit -m "docs(s3k): retire AIZ2 wrap-distance discrepancy after faithful fix

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: updated
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 6: Recommend visual validation.** This is a visual change the headless
  tests cannot fully confirm. Before considering it verified, capture a
  stable-retro AIZ2 reference vs an engine screenshot (the `s3k-zone-validate`
  skill) or run the engine through the AIZ2 battleship loop and confirm the forest
  loops with no empty gap. Report the result.

---

## Self-Review notes

- **Spec coverage (Rev 2):** restore `$200` → Task 1; forest-loop predicate +
  period signal → Task 2; loop the `$200` forest BG (bgWrap clause + period guard)
  + forest-only guard → Task 3; relaxed trace acceptance + frontier log → Task 4;
  cleanup/discrepancy/CHANGELOG + full suite + visual-validation recommendation →
  Task 5. All Rev 2 spec sections mapped.
- **Removed Rev 1 machinery:** no `getBgCameraX()` override, no `forestLoopQueryOffset`
  normalization, no `getForestLoopBgOrigin()`, no "base jumps `$200`" assertion —
  the composition trace proved base must stay 0 (the continuous `smoothScrollX`
  deform scrolls the loop).
- **Type/name consistency:** `isBattleshipForestLoopActive()`,
  `getForestLoopBgWrapPeriod()`, `isAizForestLoopBackgroundWindowActive()`,
  `backgroundLoopSourcePeriod()`, `getCurrentBgPeriodWidth()`,
  `bgVisibleSourceColumnsForTest()` used identically across tasks.
- **Open implementation confirmations (flagged in-task, not placeholders):**
  whether `bgVisibleSourceColumnsForTest()` needs a thin new `LevelManager`
  accessor (Task 3 note); whether `zoneRuntimeRequiresFullWidthBgTilemap()`
  excludes the loop phase (Task 3 Step 5).
