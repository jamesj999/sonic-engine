# S3K CNZ Workstream D - CNZ1 Mini-Boss (`Obj_CNZMiniboss`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Also consult the `s3k-implement-boss` skill for the 10-phase procedural template.

**Goal:** Port the S3K CNZ Act 1 mini-boss (`Obj_CNZMiniboss`) and its child objects so that `TestS3kCnzTraceReplay`'s error count drops from **1954** (post-C baseline) to **<= 1800**, with the first-20 divergences in the frame 193..292 window (baseline rows 6..20) displaced off the list.

**Architecture:** Promote the existing minimal `CnzMinibossInstance` to extend `AbstractBossInstance`, implement the ROM's 8-routine state machine (`CNZMiniboss_Index`), complete the `CnzMinibossTopInstance` bouncing-ball physics, preserve the narrow `CnzMinibossScrollControlInstance` accumulator already in place, and wire arena setup (camera lock, music, PLC, palette, wall-grab disable, boss flag) through `Sonic3kCNZEvents`. Keep the module boundary between object state and event state intact — **do not** collapse CNZ into an `AizMinibossInstance`-style monolith.

**Tech Stack:** Java 21, JUnit 5, existing S3K boss infrastructure (`AbstractBossInstance`, `BossStateContext`, `BossHitHandler`, `BossDefeatSequencer`), existing CNZ event bridge (`Sonic3kCNZEvents`, `S3kCnzEventWriteSupport`), `HeadlessTestFixture` (package `com.openggf.tests`, NOT `com.openggf.tests.util`), `SharedLevel.load(SonicGame.SONIC_3K, zone, act)` bootstrap.

**Design spec:** `docs/architecture/designs/2026-04-22-s3k-cnz-trace-replay-design.md` §7.2 (CNZ Act 1 mini-boss — 2 lines; scope intentionally minimal)
**Parent plan:** `docs/architecture/plans/2026-04-22-s3k-cnz-trace-replay-ab-plan.md`
**Template:** `docs/architecture/plans/2026-04-22-s3k-cnz-workstream-c-tails-carry.md` (workstream C, just completed)
**Post-C baseline:** `docs/architecture/validation/s3k-zones/cnz-post-workstream-c.md` (errors 6..20 in frames 193..292 are D's territory)
**Procedural skill:** `.claude/skills/s3k-implement-boss/skill.md`

**Critical ROM-only constraint.** All ROM offsets/line numbers MUST come from `sonic3k.asm` (< 0x200000 / S&K-side). Always run `RomOffsetFinder` with `--game s3k`. If a label returns both halves, pick `sonic3k.asm`. Never substitute an `s3.asm` address.

**Commit trailer policy:** every commit in this workstream MUST carry the 7 required policy trailers per `CLAUDE.md` §Branch Documentation Policy (Changelog / Guide / Known-Discrepancies / S3K-Known-Discrepancies / Agent-Docs / Configuration-Docs / Skills), with `Co-Authored-By` INSIDE the policy block and NO blank line separating them. Intermediate task commits use `Changelog: n/a` / `S3K-Known-Discrepancies: n/a` etc.; the documentation rollup in Task 12 is the only commit that stages `CHANGELOG.md` / `docs/S3K_KNOWN_DISCREPANCIES.md` with matching `updated` trailers. The policy validator rejects `Changelog: updated` without a staged `CHANGELOG.md` edit AND rejects `Changelog: n/a` if `CHANGELOG.md` IS staged — so each commit must be self-consistent.

**Test fixture conventions** (same as workstream C; see `TestS3kAiz1SkipHeadless` and `TestS3kCnzCarryHeadless` for live examples):
- All new S3K tests MUST be annotated `@RequiresRom(SonicGame.SONIC_3K)`.
- Prefer `SharedLevel.load(SonicGame.SONIC_3K, zone, act)` in `@BeforeAll`, then `HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build()`.
- Step frames via `fixture.stepFrame(up, down, left, right, jump)` (5 booleans, all required) or `fixture.stepIdleFrames(count)`.
- Main sprite: `fixture.sprite()`. Sidekick sprite: `GameServices.sprites().getSidekicks().get(0)`.

**Maven command convention.** Every `mvn` command in this plan uses `"-Dmse=off"`. The repo default `-Dmse=relaxed` silently ignores `-Dtest=` filters. Paths with spaces MUST be double-quoted for the shell (Windows/bash). ROM path:
```
"-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

---

## File Structure

### New files
| File | Responsibility |
|------|----------------|
| `src/test/java/com/openggf/tests/TestS3kCnzMinibossHeadless.java` | Headless integration: boss spawns on camera-X threshold, descends, swings, accepts hits, defeats ~200 frames after trigger |

### Modified files
| File | Change |
|------|--------|
| `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` | Fix `PLC_CNZ_MINIBOSS` from `0x5C` to `0x5D` (ROM `moveq #$5D,d0` at line 144844). Add arena bounds `CNZ_MINIBOSS_ARENA_MIN_X=0x31E0`, `CNZ_MINIBOSS_ARENA_MAX_X=0x3260`, `CNZ_MINIBOSS_ARENA_MIN_Y=0x01C0`, `CNZ_MINIBOSS_ARENA_MAX_Y=0x02B8`. Add state-machine literals (hit count, y_vel init, x_vel swing, timers), plus `PAL_CNZ_MINIBOSS_ADDR` if not already present. |
| `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java` | Promote to extend `AbstractBossInstance`. Implement 8-routine state machine (Init, Lower, Move, Opening, WaitHit, Closing, Lower2, End). Wire `onHitTaken` / `onDefeatStarted` to CNZ event bridge. Preserve existing `onArenaChunkDestroyed()` hook for top-piece integration tests. |
| `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossTopInstance.java` | Extend minimal Task-7 skeleton with the ROM `Obj_CNZMinibossTop` 4-routine state machine (TopInit, TopWait, TopWait2, TopMain — the bouncing-ball physics). Publish centre X/Y to `Events_bg+$00/$02` via `S3kCnzEventWriteSupport`. Preserve test hooks (`attachBossForTest`, `forceArenaCollisionForTest`). |
| `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java` | Adjust `MINIBOSS_CAM_X_THRESHOLD` from `0x3000` (scaffold) to `0x31E0` (ROM `Obj_CNZMiniboss` at line 144824: `move.w #$31E0,d0`). Add arena-entry hook (camera clamp, music fade + miniboss theme, PLC 0x5D, palette load, wall-grab disable, boss flag). Keep stage transition sequencing intact. |
| `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java` | Verify `CNZ_MINIBOSS` (0xA6) factory is active (it already is, at line 632). No change unless audit in T1 finds otherwise. |
| `src/main/java/com/openggf/tools/Sonic3kObjectProfile.java` | Verify `S3KL_IMPLEMENTED_IDS` already contains `0xA6` at line 203. No change unless audit in T1 finds otherwise. |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java` | Retain existing `registerSheet(CNZ_MINIBOSS, ...)` call. The PLC-load call path already exists; a corrected `PLC_CNZ_MINIBOSS=0x5D` in constants propagates automatically. Headless tests do not assert art contents. |
| `CHANGELOG.md` | Single rollup entry in Task 12 covering all workstream-D behavioural changes. |
| `docs/architecture/validation/s3k-zones/cnz-post-workstream-d.md` | New file, created in Task 12 with post-D divergence summary. |
| `docs/S3K_KNOWN_DISCREPANCIES.md` | Task 12 — only modify if boss phase has a ROM-divergent residual (e.g. hit-response x_vel sign). If clean parity achieved, leave untouched (trailer `n/a`). |

### Unchanged
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossScrollControlInstance.java` (Task 7 scaffold is already ROM-faithful for the post-defeat scroll handoff)
- `src/main/java/com/openggf/game/sonic3k/events/S3kCnzEventWriteSupport.java` (pure bridge helpers; no new writes needed)
- `src/main/java/com/openggf/game/sonic3k/Sonic3kPlcLoader.java` (no new PLCs registered; 0x5D parses through the shared `PlcParser` once the constant is corrected)

---

### Task 1: Audit existing scaffolding — no code change

**Why first:** Before adding a single state-machine transition, confirm that the factory is registered, `0xA6` is in the S3KL implemented set, `AbstractBossInstance` is the right base, and the `PLC_CNZ_MINIBOSS` constant discrepancy (0x5C vs ROM's 0x5D) is real. Produce a short audit note in this task's commit body so downstream tasks have a shared reference.

**Files:**
- No code change. Audit only.

- [ ] **Step 1: Verify factory registration**

Run: `grep -n "CNZ_MINIBOSS\|CnzMinibossInstance" src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`

Expected: factory at line 632, `(spawn, registry) -> new CnzMinibossInstance(spawn)` — already present. If absent, downgrade later tasks to include registry wiring; **do not re-plan tasks in place, add a step to T9**.

- [ ] **Step 2: Verify `S3KL_IMPLEMENTED_IDS` contains 0xA6**

Run: `grep -n "0xA6" src/main/java/com/openggf/tools/Sonic3kObjectProfile.java`

Expected: line 203 has `0xA6, // CNZMiniboss` in the `S3KL_IMPLEMENTED_IDS` block. If absent, add a step to T9 to register it; **note in audit output**.

- [ ] **Step 3: Verify `PLC_CNZ_MINIBOSS` value against ROM**

Run: `grep -n "PLC_CNZ_MINIBOSS" src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`

Expected current engine value: `0x5C` at line 1379. Cross-check against ROM: `sonic3k.asm` line 144844 reads `moveq #$5D,d0` immediately before `jsr (Load_PLC).l` for CNZ Miniboss. **The engine constant is off-by-one.** Fix lands in Task 2.

Also verify via `RomOffsetFinder`:
```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search Load_PLC" -q
```
and for completeness:
```bash
mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search CNZMiniboss" -q
```
Expected: `sonic3k.asm` hits only (never `s3.asm`). If both are returned, pick `sonic3k.asm`.

- [ ] **Step 4: Verify `AbstractBossInstance` is the correct base**

Run: `grep -n "abstract class AbstractBossInstance\|extends AbstractBossInstance" src/main/java/com/openggf/level/objects/boss/AbstractBossInstance.java src/main/java/com/openggf/game/sonic3k/objects/AizMinibossInstance.java`

Expected: `AbstractBossInstance` exists at `level/objects/boss/`. `AizMinibossInstance` extends it. There is NO separate `Sonic3kBossInstance` subclass — the parent spec wording at §7.2 referring to "Sonic3kBossInstance base class" is **incorrect**; the actual base is `AbstractBossInstance`. Note this explicitly in the audit commit so downstream tasks do not try to extend a non-existent class.

- [ ] **Step 5: Record audit findings in scoping note**

Create the audit output inline in the commit body. Example:

```
Audit results (no code changed):
  Registry factory: present at Sonic3kObjectRegistry.java:632
  Implemented IDs: 0xA6 present in S3KL_IMPLEMENTED_IDS (Sonic3kObjectProfile.java:203)
  Base class: AbstractBossInstance (level.objects.boss), not Sonic3kBossInstance
    (which does not exist in this repo). Parent spec §7.2 wording corrected in T3.
  PLC_CNZ_MINIBOSS in engine: 0x5C. ROM: 0x5D (sonic3k.asm:144844).
    Off-by-one, fixed in T2.
  Arena X trigger in Sonic3kCNZEvents.MINIBOSS_CAM_X_THRESHOLD: 0x3000.
    ROM: 0x31E0 (sonic3k.asm:144824). Fixed in T8.
```

- [ ] **Step 6: Commit the audit note (empty-tree commit via `--allow-empty`)**

```bash
git commit --allow-empty -m "$(cat <<'EOF'
chore(s3k): audit CNZ1 miniboss scaffolding before state-machine port

No code change. Confirms:
- Factory registered (Sonic3kObjectRegistry.java:632)
- 0xA6 in S3KL_IMPLEMENTED_IDS (Sonic3kObjectProfile.java:203)
- Base is AbstractBossInstance (no Sonic3kBossInstance exists;
  parent spec §7.2 naming corrected here)
- Engine PLC_CNZ_MINIBOSS=0x5C is off-by-one vs ROM moveq #$5D,d0
  at sonic3k.asm:144844 (fixed in T2)
- Engine MINIBOSS_CAM_X_THRESHOLD=0x3000 is short of ROM $31E0 at
  sonic3k.asm:144824 (fixed in T8)

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: ROM constants in `Sonic3kConstants.java`

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`

All ROM references below are from `sonic3k.asm` (S&K-side).

- [ ] **Step 1: Write the failing constants test**

Create `src/test/java/com/openggf/game/sonic3k/constants/TestCnzMinibossConstants.java`:

```java
package com.openggf.game.sonic3k.constants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the CNZ Act 1 miniboss literal set against accidental drift.
 *
 * <p>ROM sources (all sonic3k.asm, S&K-side < 0x200000):
 * <ul>
 *   <li>line 144824: {@code move.w #$31E0,d0} — camera trigger X</li>
 *   <li>line 144832: {@code move.w #$1C0,(Camera_min_Y_pos).w} — arena min Y</li>
 *   <li>line 144834: {@code addi.w #$80,d0} — arena X span 0x31E0..0x3260</li>
 *   <li>line 144836: {@code move.w #$2B8,(Camera_max_Y_pos).w} — arena max Y</li>
 *   <li>line 144844: {@code moveq #$5D,d0} then {@code Load_PLC} — PLC id</li>
 *   <li>line 144888: {@code move.b #6,collision_property(a0)} — hit count</li>
 *   <li>line 144891: {@code move.w #$80,y_vel(a0)} — descent velocity</li>
 *   <li>line 144919: {@code move.w #$100,x_vel(a0)} — swing velocity magnitude</li>
 *   <li>line 144892: {@code move.w #$11F,$2E(a0)} — init wait</li>
 *   <li>line 144907: {@code move.w #$90,$2E(a0)} — go2 wait</li>
 *   <li>line 144920: {@code move.w #$9F,$2E(a0)} — go3 wait (swing duration)</li>
 *   <li>line 144937: {@code move.w #$13F,$2E(a0)} — direction-change wait</li>
 * </ul>
 */
class TestCnzMinibossConstants {

    @Test
    void plcIdMatchesRom() {
        assertEquals(0x5D, Sonic3kConstants.PLC_CNZ_MINIBOSS,
                "ROM sonic3k.asm:144844 reads `moveq #$5D,d0`");
    }

    @Test
    void arenaBounds() {
        assertEquals(0x31E0, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);
        assertEquals(0x3260, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X);
        assertEquals(0x01C0, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y);
        assertEquals(0x02B8, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_Y);
    }

    @Test
    void stateMachineLiterals() {
        assertEquals(0x06,  Sonic3kConstants.CNZ_MINIBOSS_HIT_COUNT);
        assertEquals(0x80,  Sonic3kConstants.CNZ_MINIBOSS_INIT_Y_VEL);
        assertEquals(0x100, Sonic3kConstants.CNZ_MINIBOSS_SWING_X_VEL);
        assertEquals(0x11F, Sonic3kConstants.CNZ_MINIBOSS_INIT_WAIT);
        assertEquals(0x90,  Sonic3kConstants.CNZ_MINIBOSS_GO2_WAIT);
        assertEquals(0x9F,  Sonic3kConstants.CNZ_MINIBOSS_SWING_WAIT);
        assertEquals(0x13F, Sonic3kConstants.CNZ_MINIBOSS_CHANGEDIR_WAIT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossConstants" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: FAILS because the new constants do not exist AND `PLC_CNZ_MINIBOSS` is `0x5C` not `0x5D`.

- [ ] **Step 3: Fix `PLC_CNZ_MINIBOSS` and add arena + state-machine literals**

Open `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`. At line 1379 replace `0x5C` with `0x5D`, and update the nearby comment. Then add a new block (below the existing `MAP_CNZ_MINIBOSS_ADDR` entry) with the state-machine and arena constants:

```java
    /**
     * CNZ Act 1 miniboss PLC id.
     *
     * <p>ROM: {@code sonic3k.asm:144844} — {@code moveq #$5D,d0} then
     * {@code jsr (Load_PLC).l}. The engine previously held {@code 0x5C}
     * (off-by-one); corrected in workstream D.
     */
    public static final int PLC_CNZ_MINIBOSS = 0x5D;

    // ... existing MAP_CNZ_MINIBOSS_ADDR line ...

    // =====================================================================
    // CNZ Act 1 miniboss state machine
    // ROM refs (all sonic3k.asm, S&K-side):
    //   Obj_CNZMiniboss        line 144823  outer gate
    //   loc_6D9A8              line 144830  arena setup
    //   Obj_CNZMinibossInit    line 144885  routine 0
    //   Obj_CNZMinibossLower   line 144898  routine 2
    //   Obj_CNZMinibossMove    line 144912  routine 4
    //   Obj_CNZMinibossOpening line 144941  routine 6
    //   Obj_CNZMinibossWaitHit line 144954  routine 8
    //   Obj_CNZMinibossClosing line 144968  routine A
    //   Obj_CNZMinibossLower2  line 144972  routine C
    //   Obj_CNZMinibossEnd     line 144984  routine E (defeat)
    // =====================================================================

    /** Arena camera X minimum. ROM: loc_6D9A8 `move.w d0,(Camera_min_X_pos).w`
     *  after `move.w #$31E0,d0`. */
    public static final int CNZ_MINIBOSS_ARENA_MIN_X = 0x31E0;

    /** Arena camera X maximum. ROM: loc_6D9A8 `addi.w #$80,d0`. */
    public static final int CNZ_MINIBOSS_ARENA_MAX_X = 0x3260;

    /** Arena camera Y minimum. ROM: loc_6D9A8 `move.w #$1C0,(Camera_min_Y_pos).w`. */
    public static final int CNZ_MINIBOSS_ARENA_MIN_Y = 0x01C0;

    /** Arena camera Y maximum / target max Y. ROM: loc_6D9A8 `move.w #$2B8,...`. */
    public static final int CNZ_MINIBOSS_ARENA_MAX_Y = 0x02B8;

    /** Boss hit count. ROM: Obj_CNZMinibossInit `move.b #6,collision_property(a0)`. */
    public static final int CNZ_MINIBOSS_HIT_COUNT = 0x06;

    /** Initial descent y_vel. ROM: Obj_CNZMinibossInit `move.w #$80,y_vel(a0)`. */
    public static final short CNZ_MINIBOSS_INIT_Y_VEL = (short) 0x0080;

    /** Swing x_vel magnitude. ROM: Obj_CNZMinibossGo3 `move.w #$100,x_vel(a0)`. */
    public static final short CNZ_MINIBOSS_SWING_X_VEL = (short) 0x0100;

    /** Init wait timer. ROM: Obj_CNZMinibossInit `move.w #$11F,$2E(a0)`. */
    public static final int CNZ_MINIBOSS_INIT_WAIT = 0x11F;

    /** Go2 wait timer. ROM: Obj_CNZMinibossGo2 `move.w #$90,$2E(a0)`. */
    public static final int CNZ_MINIBOSS_GO2_WAIT = 0x90;

    /** Swing (Go3) wait timer. ROM: Obj_CNZMinibossGo3 `move.w #$9F,$2E(a0)`. */
    public static final int CNZ_MINIBOSS_SWING_WAIT = 0x9F;

    /** Direction-change wait. ROM: Obj_CNZMinibossChangeDir `move.w #$13F,$2E(a0)`. */
    public static final int CNZ_MINIBOSS_CHANGEDIR_WAIT = 0x13F;
```

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossConstants" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: all 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java \
        src/test/java/com/openggf/game/sonic3k/constants/TestCnzMinibossConstants.java
git commit -m "$(cat <<'EOF'
feat(s3k): add CNZ1 miniboss arena + state-machine constants

Corrects PLC_CNZ_MINIBOSS 0x5C -> 0x5D (ROM sonic3k.asm:144844
`moveq #$5D,d0` before Load_PLC). Adds the arena camera clamps
(0x31E0..0x3260, 0x01C0..0x02B8) and the state-machine literals
(hit count 6, init y_vel 0x80, swing x_vel 0x100, wait timers 0x11F /
0x90 / 0x9F / 0x13F) extracted from Obj_CNZMiniboss and its routine
targets. All addresses verified against sonic3k.asm (S&K-side,
< 0x200000).

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Promote `CnzMinibossInstance` to `AbstractBossInstance`

**Why this split:** The state machine lands across T3/T4/T5/T6 to keep each commit scoped. T3 only changes the class hierarchy and re-exposes the existing `onArenaChunkDestroyed()` + centre-coordinate accessors atop `AbstractBossInstance`, so the Task-7 arena test stays green before any routine bodies are filled in.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`

- [ ] **Step 1: Write the failing boss-base test**

Create `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossInstanceBase.java`:

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.boss.AbstractBossInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzMinibossInstanceBase {

    @Test
    void extendsAbstractBossInstance() {
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS,
                        0, 0, false, 0));
        assertTrue(boss instanceof AbstractBossInstance,
                "CnzMinibossInstance must extend AbstractBossInstance");
    }

    @Test
    void reportsRomHitCount() {
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS,
                        0, 0, false, 0));
        // state.hitCount initialised from getInitialHitCount()
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_HIT_COUNT,
                boss.getRemainingHits());
    }

    @Test
    void preservesArenaChunkHook() {
        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS,
                        0, 0, false, 0));
        int originalY = boss.getCentreY();
        boss.onArenaChunkDestroyed();
        assertEquals(originalY + 0x20, boss.getCentreY(),
                "onArenaChunkDestroyed must still advance centreY by one arena row");
    }
}
```

Note: `getRemainingHits()` is a public convenience accessor backed by `state.hitCount`. If `AbstractBossInstance` does not expose it, either add a thin accessor on `CnzMinibossInstance` (returning `state.hitCount`) or replace the assertion with a reflection-free `boss.initialHitCountForTest()` helper added to the new subclass.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossInstanceBase" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: `extendsAbstractBossInstance` FAILS (current class extends `AbstractObjectInstance`).

- [ ] **Step 3: Promote the class**

Rewrite `CnzMinibossInstance` to extend `AbstractBossInstance`. Keep `LOWERING_STEP_PIXELS`, `onArenaChunkDestroyed()`, and `getCentreX/Y()` intact; replace the old local `centreX/centreY` fields with `state.x / state.y` from `BossStateContext`. Keep `appendRenderCommands` empty-bodied (rendering out of scope per spec §9). Add the abstract-method stubs that T4..T6 will fill:

```java
public final class CnzMinibossInstance extends AbstractBossInstance {
    // existing LOWERING_STEP_PIXELS block

    public CnzMinibossInstance(ObjectSpawn spawn) {
        super(spawn, "CNZMiniboss");
    }

    @Override protected void initializeBossState() {
        state.routine = 0;  // ROM Obj_CNZMinibossInit
        // state.hitCount set via getInitialHitCount() in parent constructor
    }

    @Override protected int getInitialHitCount() {
        return Sonic3kConstants.CNZ_MINIBOSS_HIT_COUNT;
    }

    @Override protected int getCollisionSizeIndex() {
        return 0x0F; // placeholder until Task 6 wires the real bounding box
    }

    @Override protected void updateBossLogic(int frameCounter, PlayableEntity player) {
        // T4-T6: dispatch routine 0/2/4/6/8/A/C/E
    }

    @Override protected void onHitTaken(int remainingHits) { /* T6 */ }
    @Override protected void onDefeatStarted() { /* T6 */ }

    public int getRemainingHits() { return state.hitCount; }

    public void onArenaChunkDestroyed() {
        state.y += LOWERING_STEP_PIXELS;
    }

    public int getCentreX() { return state.x; }
    public int getCentreY() { return state.y; }

    @Override public int getX() { return state.x; }
    @Override public int getY() { return state.y; }

    @Override public void appendRenderCommands(List<GLCommand> commands) {
        // Visual rendering out of scope for workstream D (spec §9).
    }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossInstanceBase+TestS3kCnzMinibossArenaHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: both classes PASS. The pre-existing arena test must not regress.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java \
        src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossInstanceBase.java
git commit -m "$(cat <<'EOF'
refactor(s3k): promote CnzMinibossInstance to AbstractBossInstance

Moves the Task-7 minimal instance onto the shared boss base so
downstream tasks can add the 8-routine state machine, hit handler,
and defeat sequencer without re-threading boss-state plumbing.
Preserves Task-7 seams: onArenaChunkDestroyed, getCentreX/Y, and
the empty appendRenderCommands contract. Initial hit count wired
to CNZ_MINIBOSS_HIT_COUNT (6) from ROM sonic3k.asm:144888.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: State machine routines 0/2/4 — Init, Lower, Move (swing)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`

ROM reference: `Obj_CNZMinibossInit` (line 144885), `Obj_CNZMinibossLower` (144898), `Obj_CNZMinibossGo2` (144903), `Obj_CNZMinibossMove` (144912), `Obj_CNZMinibossGo3` (144918).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossSwingPhase.java`:

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.game.RuntimeManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossSwingPhase {

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void initSetsDescentVelocityAndWait() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        boss.update(0, fixture.sprite());

        // Routine 0 -> 2 (advance to Lower after Init). Init runs once, sets y_vel and wait.
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_INIT_Y_VEL,
                boss.getCurrentYVel(), "Init must write y_vel = 0x80");
        assertTrue(boss.getCurrentRoutine() >= 2,
                "Init must advance state.routine past 0");
    }

    @Test
    void lowerAdvancesPositionUntilWaitExpires() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        int initialY = boss.getCentreY();
        for (int i = 0; i < Sonic3kConstants.CNZ_MINIBOSS_INIT_WAIT + 5; i++) {
            boss.update(i, fixture.sprite());
        }
        assertTrue(boss.getCentreY() > initialY,
                "Lower must move the boss downward (y_vel 0x80 positive)");
    }

    @Test
    void moveAssignsSwingVelocityAfterGo3() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        // Fast-forward through Init+Lower+Go2 so we reach routine 4 (Move)
        int totalWait = Sonic3kConstants.CNZ_MINIBOSS_INIT_WAIT
                + Sonic3kConstants.CNZ_MINIBOSS_GO2_WAIT + 10;
        for (int i = 0; i < totalWait; i++) {
            boss.update(i, fixture.sprite());
        }
        assertEquals(4, boss.getCurrentRoutine() & 0xFF,
                "Boss must be in routine 4 (Move) after Init+Lower+Go2 waits");
        assertEquals(Sonic3kConstants.CNZ_MINIBOSS_SWING_X_VEL,
                Math.abs(boss.getCurrentXVel()),
                "Go3 sets x_vel magnitude = 0x100 (sign depends on swing direction)");
    }
}
```

`CnzMinibossInstance` needs small test accessors (`getCurrentXVel`, `getCurrentYVel`, `getCurrentRoutine`) that expose internal boss-state fields without breaking encapsulation. Keep them package-private or suffixed `ForTest`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossSwingPhase" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: all 3 tests FAIL because `updateBossLogic` is still a stub.

- [ ] **Step 3: Implement routines 0, 2, 4 (plus Go2 / Go3 sub-states)**

In `CnzMinibossInstance`, add routine constants, scratch-state fields (x_vel, y_vel, wait timer, go-next pointer), and the three routine bodies. Use the wait-timer / `go_next` pattern from `AizMinibossInstance` (it mirrors the ROM's `$2E(a0)` timer + `$34(a0)` post-wait dispatch). Key ROM lines:

- `Obj_CNZMinibossInit` (144885): set `collision_property=6`, `y_vel=0x80`, wait `0x11F`, go-next `Obj_CNZMinibossGo2`.
- `Obj_CNZMinibossLower` (144898): `MoveSprite2` (advance y_pos by y_vel) + `Obj_Wait` (decrement $2E, dispatch on expiry).
- `Obj_CNZMinibossGo2` (144903): advance routine to 4, clear y_vel, wait `0x90`, go-next `Obj_CNZMinibossGo3`, then `SetUp_CNZMinibossSwing`.
- `Obj_CNZMinibossMove` (144912): `Swing_UpAndDown` + `MoveSprite2` + `Obj_Wait`.
- `Obj_CNZMinibossGo3` (144918): set `x_vel=0x100`, wait `0x9F`, then same timer dispatches to `Obj_CNZMinibossCloseGo` which is routine 6 (T5).

Use `com.openggf.physics.SwingMotion` for `Swing_UpAndDown` if available (see `s3k-implement-boss` skill §Reusable Engine Utilities). If the utility is a poor match for the CNZ pattern, replicate the ROM inline and add a comment citing the label.

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossSwingPhase+TestCnzMinibossInstanceBase+TestS3kCnzMinibossArenaHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java \
        src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossSwingPhase.java
git commit -m "$(cat <<'EOF'
feat(s3k): port CNZ1 miniboss routines 0/2/4 (Init, Lower, Move+swing)

Implements Obj_CNZMinibossInit, Obj_CNZMinibossLower, Obj_CNZMinibossGo2,
Obj_CNZMinibossMove, and Obj_CNZMinibossGo3 from sonic3k.asm:144885..144920.
Descent at y_vel 0x80 for $11F frames, clear y_vel + $90 frame wait,
then x_vel 0x100 swing for $9F frames. Timer dispatch mirrors the ROM's
$2E(a0)/$34(a0) pair. Hit count (6) and ID 0xA6 unchanged.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: State machine routines 6/8/A — Opening, WaitHit, Closing

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`

ROM reference: `Obj_CNZMinibossCloseGo` (144922), `Obj_CNZMinibossChangeDir` (144935), `Obj_CNZMinibossOpening` (144941), `Obj_CNZMinibossOpenGo` (144945), `Obj_CNZMinibossWaitHit` (144954), `loc_6DB4E` (144960), `Obj_CNZMinibossClosing` (144968).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossOpeningPhase.java`:

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.RuntimeManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossOpeningPhase {

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void enteringRoutine6FlipsSwingDirection() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(6);
        boss.forceXVelForTest((short) 0x0100);
        boss.update(0, fixture.sprite());
        // Obj_CNZMinibossChangeDir negates x_vel on entry to routine 6
        assertEquals((short) -0x0100, boss.getCurrentXVel(),
                "Routine 6 entry should have flipped x_vel");
    }

    @Test
    void waitHitRoutineStallsUntilOpenBitSet() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(8);  // Obj_CNZMinibossWaitHit
        int routineBefore = boss.getCurrentRoutine();
        for (int i = 0; i < 30; i++) boss.update(i, fixture.sprite());
        assertEquals(routineBefore, boss.getCurrentRoutine(),
                "WaitHit must idle (btst #6,status — no hit yet)");
    }

    @Test
    void hitDuringOpeningAdvancesToClosing() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0100, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(8);  // WaitHit
        boss.simulateHitForTest();     // toggles status bit 6 (ROM loc_6DB4E)
        boss.update(0, fixture.sprite());
        assertEquals(0x0C, boss.getCurrentRoutine() & 0xFF,
                "Hit during WaitHit advances routine to C (Closing)");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossOpeningPhase" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: FAILS — routines 6/8/A still unimplemented.

- [ ] **Step 3: Implement routines 6, 8, A**

In `CnzMinibossInstance`, add the three routine bodies. Key ROM behaviour:

- `Obj_CNZMinibossCloseGo` (144922): enters routine 6 — advances `routine` to 6, sets go-next = `Obj_CNZMinibossChangeDir`, clears open-bit, loads "normal" palette rotation pointer, installs `CNZMiniboss_MakeTimedSparks` as custom rotation.
- `Obj_CNZMinibossChangeDir` (144935): called when `$2E` timer expires — `neg.w x_vel(a0)` + new wait `0x13F`.
- `Obj_CNZMinibossOpenGo` (144945): drives routine 6 -> 8 transition after `Animate_RawMultiDelay` completes — sets open-bit, arms hit window, spawns `CNZCoilOpenSparks`.
- `Obj_CNZMinibossWaitHit` (144954): reads `status(a0) bit 6`; if clear, `rts` (stall); if set, advance to routine C (Closing), clear open-bit, load closing animation, set `go_next = Obj_CNZMinibossCloseGo`.
- `Obj_CNZMinibossClosing` (144968): `Animate_RawMultiDelay` — engine reduces to "advance after animation completes" flag in absence of full animation pipeline.

Add test hooks `forceRoutineForTest(int)`, `forceXVelForTest(short)`, `simulateHitForTest()` to `CnzMinibossInstance`.

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossOpeningPhase+TestCnzMinibossSwingPhase+TestCnzMinibossInstanceBase+TestS3kCnzMinibossArenaHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java \
        src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossOpeningPhase.java
git commit -m "$(cat <<'EOF'
feat(s3k): port CNZ1 miniboss routines 6/8/A (Opening, WaitHit, Closing)

Implements Obj_CNZMinibossCloseGo, Obj_CNZMinibossChangeDir,
Obj_CNZMinibossOpenGo, Obj_CNZMinibossWaitHit, loc_6DB4E, and
Obj_CNZMinibossClosing from sonic3k.asm:144922..144968. Direction flip
on swing boundary, hit-window gate via status bit 6, and closing
transition back into routine C for the next swing cycle.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: State machine routines C/E — Lower2 and End-defeat, plus `onHitTaken` wiring

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`

ROM reference: `Obj_CNZMinibossLower2` (144972), `loc_6DB7E` (144979), `Obj_CNZMinibossEnd` (144984), `Obj_CNZMinibossEndGo` (144996). Also `CNZMiniboss_CheckTopHit` and the `collision_property`-decrement flow that lives inside the shared boss hit handler.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossDefeatPhase.java`:

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.RuntimeManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.GameServices;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossDefeatPhase {

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void lower2AdvancesOnePixelPerFrameForCounter() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0200, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        boss.forceRoutineForTest(0x0C);  // Lower2
        boss.setLower2CounterForTest(4); // $43(a0) = 4, decremented per frame
        int startY = boss.getCentreY();

        for (int i = 0; i < 6; i++) boss.update(i, fixture.sprite());
        assertTrue(boss.getCentreY() >= startY + 4,
                "Lower2 must add #1 to y_pos each frame");
    }

    @Test
    void hitsReduceCounterAndSixthHitTriggersDefeat() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0200, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        for (int i = 0; i < Sonic3kConstants.CNZ_MINIBOSS_HIT_COUNT; i++) {
            boss.simulateHitForTest();
        }
        assertEquals(0, boss.getRemainingHits());
        assertTrue(boss.isDefeated(),
                "After the 6th hit the boss must be in defeat state");
    }

    @Test
    void defeatClearsBossFlagAndReleasesWallGrab() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x0200, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);

        for (int i = 0; i < Sonic3kConstants.CNZ_MINIBOSS_HIT_COUNT; i++) {
            boss.simulateHitForTest();
        }
        // After defeat sequencer advances
        for (int i = 0; i < 30; i++) boss.update(i, fixture.sprite());

        Sonic3kCNZEvents cnz = getCnzEvents();
        assertFalse(cnz.isBossFlag(),
                "Obj_CNZMinibossEndGo must clr.b Boss_flag");
    }

    private static Sonic3kCNZEvents getCnzEvents() {
        Sonic3kLevelEventManager events =
                (Sonic3kLevelEventManager) GameServices.level().getLevelEventManager();
        return (Sonic3kCNZEvents) events.getZoneEvents();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossDefeatPhase" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: FAILS — routine C + defeat wiring absent.

- [ ] **Step 3: Implement routines C, E, and `onHitTaken` / `onDefeatStarted`**

In `CnzMinibossInstance`:

- Routine C (Lower2 — `Obj_CNZMinibossLower2` at line 144972): increment y_pos by 1, decrement `$43(a0)` counter (represented by a new `lower2Counter` field), when counter goes negative fall back to `routine = $42(a0)` (stored previous-routine — add a field `lower2PreviousRoutine`).
- Routine E (End — `Obj_CNZMinibossEnd` at 144984): set `_unkFAA8` flag equivalent (use `Sonic3kCNZEvents.setEventsFg5(true)` or a dedicated "end-of-boss-level" event seam on the zone events; re-use whatever mechanism Task 7 exposes, do not reinvent), spawn debris (skip — spec §9 defers visual children), then flip to `Obj_CNZMinibossEndGo` behaviour: `clr.b Boss_flag` via `cnz.setBossFlag(false)`, re-enable wall-grab via `cnz.setWallGrabSuppressed(false)`.
- `onHitTaken(remainingHits)`: if `remainingHits > 0`, stay in routine 8 or return to Move cycle as ROM does (hit handler manages invuln timer). If `remainingHits == 0`, call `onDefeatStarted()`.
- `onDefeatStarted()`: force routine E; leave defeat sequencer (inherited from `AbstractBossInstance`) to manage the rest.

Add `setLower2CounterForTest(int)`, `simulateHitForTest()` (decrements hit count + fires hit handler), and `isDefeated()` delegating to `state.defeated`.

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossDefeatPhase+TestCnzMinibossOpeningPhase+TestCnzMinibossSwingPhase+TestCnzMinibossInstanceBase+TestS3kCnzMinibossArenaHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: all green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java \
        src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossDefeatPhase.java
git commit -m "$(cat <<'EOF'
feat(s3k): port CNZ1 miniboss routines C/E + hit/defeat wiring

Implements Obj_CNZMinibossLower2 (one-pixel-per-frame descent with
$43 counter), Obj_CNZMinibossEnd (end-of-boss flag, signpost hand-off),
and Obj_CNZMinibossEndGo (clr.b Boss_flag, re-enable wall grab) from
sonic3k.asm:144972..145002. onHitTaken / onDefeatStarted route the
final hit into routine E via the shared defeat sequencer.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `CnzMinibossTopInstance` full physics (`Obj_CNZMinibossTop`)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossTopInstance.java`

ROM reference: `Obj_CNZMinibossTop` at lines 145004-145673 (4-routine state machine: TopInit, TopWait, TopWait2, TopMain), `CNZMinibossTop_Index` at 145011.

Preserve existing test seams (`attachBossForTest`, `forceArenaCollisionForTest`) so `TestS3kCnzMinibossArenaHeadless` continues to pass unchanged.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossTopPhysics.java`:

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.RuntimeManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossTopPhysics {

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void topAdvancesThroughInitAndWaitToMain() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);

        for (int i = 0; i < 240; i++) top.update(i, fixture.sprite());
        assertTrue(top.getCurrentRoutineForTest() >= 6,
                "After 240 frames the top should reach routine 6 (TopMain)");
    }

    @Test
    void topMainBouncesVerticallyBetweenArenaBounds() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.forceTopMainForTest();
        int startY = top.getY();
        for (int i = 0; i < 60; i++) top.update(i, fixture.sprite());
        assertNotEquals(startY, top.getY(), "TopMain must be moving the top vertically");
    }

    @Test
    void preservesArenaCollisionSeam() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        DefaultObjectServices services = new DefaultObjectServices(RuntimeManager.getCurrent());

        CnzMinibossInstance boss = new CnzMinibossInstance(
                new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        boss.setServices(services);
        CnzMinibossTopInstance top = new CnzMinibossTopInstance(
                new ObjectSpawn(0x3240, 0x0300, Sonic3kObjectIds.CNZ_MINIBOSS, 0, 0, false, 0));
        top.setServices(services);
        top.attachBossForTest(boss);

        int originalBossY = boss.getCentreY();
        top.forceArenaCollisionForTest(0x3200, 0x0300);
        top.update(0, fixture.sprite());
        assertTrue(boss.getCentreY() > originalBossY,
                "Arena collision seam must still advance boss centre Y");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossTopPhysics" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: FAILS — top is still the minimal Task-7 skeleton.

- [ ] **Step 3: Port `Obj_CNZMinibossTop` state machine**

Read ROM lines 145004-145673 end-to-end. Key structure (fill in exact addresses from the disassembly):

- Routine 0 (TopInit at 145018): `SetUp_ObjAttributes`, assigned x_vel / y_vel, installs `Obj_CNZMinibossTopWait` go-next.
- Routine 2 (TopWait): wait frames, then advance.
- Routine 4 (TopWait2): secondary wait, then advance to TopMain.
- Routine 6 (TopMain): bouncing-ball physics — gravity + x_vel + wall/ceiling/floor collision checks inside the arena. On arena wall hit, reverse x_vel; on floor hit, call `CNZMiniboss_BlockExplosion` (already abstracted as `S3kCnzEventWriteSupport.queueArenaChunkDestruction` in the Task-7 scaffold — use that) and reverse y_vel.

Use `SubpixelMotion` (see CLAUDE.md §Reusable Object Utilities) for 16:8 position updates. Gravity is ROM `0x38` per frame (same as `AbstractBossInstance.GRAVITY`). Keep `attachBossForTest` / `forceArenaCollisionForTest` test seams intact; add `forceTopMainForTest()` and `getCurrentRoutineForTest()`.

Publish live centre X/Y to `Events_bg+$00/$02` via `S3kCnzEventWriteSupport` each frame (matches ROM publish behaviour; the base instance's arena-chunk hook already reads this side-channel).

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossTopPhysics+TestS3kCnzMinibossArenaHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: both green. The Task-7 arena test **must not** regress.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossTopInstance.java \
        src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossTopPhysics.java
git commit -m "$(cat <<'EOF'
feat(s3k): port CNZ1 miniboss top bouncing-ball physics

Implements Obj_CNZMinibossTop's 4-routine state machine
(sonic3k.asm:145004..145673): TopInit attribute setup, TopWait /
TopWait2 delay gates, and TopMain's gravity + reflective wall/floor
collision inside the miniboss arena. Impact coordinates route through
the existing S3kCnzEventWriteSupport bridge so the Task-7 arena
chunk-destruction seam stays intact.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Arena wiring in `Sonic3kCNZEvents`

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`

ROM reference: `Obj_CNZMiniboss` outer gate (144823) + `loc_6D9A8` (144830): camera lock, music fade-out + miniboss theme, PLC 0x5D, palette load, `Boss_flag = 1`, wall-grab disable.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/sonic3k/events/TestCnzMinibossArenaEntry.java`:

```java
package com.openggf.game.sonic3k.events;

import com.openggf.game.GameServices;
import com.openggf.game.RuntimeManager;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestCnzMinibossArenaEntry {

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void cameraAtThresholdLocksArenaAndSetsBossFlag() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        GameServices.camera().setX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);

        Sonic3kCNZEvents cnz = getCnzEvents();
        // Invoke the normal event update loop
        cnz.update(0, 0);

        assertTrue(cnz.isBossFlag(),  "Boss_flag must be set");
        assertTrue(cnz.isWallGrabSuppressed(),
                "Disable_wall_grab bit 7 must be set (wall-grab suppression)");
        assertEquals(Sonic3kCNZEvents.BG_BOSS_START, cnz.getBackgroundRoutine(),
                "BG routine must be BG_BOSS_START after threshold crossing");
    }

    @Test
    void arenaThresholdMatchesRom() {
        // The hard number: ROM sonic3k.asm:144824 reads `move.w #$31E0,d0`.
        // The scaffold previously held 0x3000; workstream D corrects it.
        assertEquals(0x31E0, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);
    }

    private static Sonic3kCNZEvents getCnzEvents() {
        Sonic3kLevelEventManager events =
                (Sonic3kLevelEventManager) GameServices.level().getLevelEventManager();
        return (Sonic3kCNZEvents) events.getZoneEvents();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossArenaEntry" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: `arenaThresholdMatchesRom` FAILS because current `MINIBOSS_CAM_X_THRESHOLD=0x3000`. `cameraAtThresholdLocksArenaAndSetsBossFlag` FAILS because `isBossFlag()` never flips during the entry event (current code only changes `bossBackgroundMode` and `bgRoutine`).

- [ ] **Step 3: Correct the threshold and flesh out arena entry**

In `Sonic3kCNZEvents`:

1. Replace the local `MINIBOSS_CAM_X_THRESHOLD = 0x3000` with a reference to `Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X`.
2. In `handleAct1Entry()` `case NORMAL`, when the threshold fires:
   - set camera clamps `(MIN_X, MAX_X)` and `(MIN_Y, MAX_Y)` via `GameServices.camera().setMinX/setMaxX/setMinY/setMaxY` (use the existing camera-clamp API — match the pattern used by `AizMinibossInstance` arena setup if present).
   - call `GameServices.audio().playMusicCmd(cmd_FadeOut)` then schedule `Sonic3kMusic.MINIBOSS` via `playMusic(...)`. Use `Sonic3kLevelEventManager` hooks if a direct audio call is unavailable. (If neither is available, raise a TODO note only for the audio call and keep the rest of the wiring; surface it in the Task 12 CHANGELOG entry as a deferred polish item — audio is out-of-scope per spec §9.)
   - call `setBossFlag(true)`.
   - call `setWallGrabSuppressed(true)` (already present in the current scaffold — keep it).
   - load PLC 0x5D (`Sonic3kConstants.PLC_CNZ_MINIBOSS`) through the existing `applyPlc(int)` helper (already used by `handleSeamlessReloadStage`).
   - load the miniboss palette `Pal_CNZMiniboss` (ROM line 145726 — look up the S&K-side ROM offset via `RomOffsetFinder --game s3k search Pal_CNZMiniboss`; record the offset in `Sonic3kConstants.PAL_CNZ_MINIBOSS_ADDR` if it does not already exist, and invoke `PaletteWriteSupport` / palette ownership registry to install it).
3. When `bossFlag` is cleared (by `Obj_CNZMinibossEndGo` in Task 6), release wall-grab and camera clamps (already partially wired; audit and finish).

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossArenaEntry" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: both tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java \
        src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java \
        src/test/java/com/openggf/game/sonic3k/events/TestCnzMinibossArenaEntry.java
git commit -m "$(cat <<'EOF'
feat(s3k): wire CNZ1 miniboss arena entry (camera lock, PLC, palette)

Corrects MINIBOSS_CAM_X_THRESHOLD 0x3000 -> 0x31E0 to match ROM
sonic3k.asm:144824 (`move.w #$31E0,d0`). On threshold crossing, the
CNZ event manager now sets arena camera clamps (0x31E0..0x3260,
0x01C0..0x02B8), raises Boss_flag, suppresses wall grab, triggers
PLC 0x5D, and installs Pal_CNZMiniboss via the shared palette
ownership path — mirroring loc_6D9A8. Post-defeat cleanup already
resets Boss_flag + wall-grab via the Task-6 defeat-sequencer path.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Registry audit — promote to full implementation if needed

**Why this task exists:** If the Task 1 audit confirmed the factory is already registered and `0xA6` is already in `S3KL_IMPLEMENTED_IDS`, this task shrinks to a no-op commit. The task is kept as a placeholder slot so that if the audit **did** find gaps, they land in this commit.

**Files:**
- If audit clean: **no file changes**. Task reduces to a one-line acknowledgement commit or is skipped entirely.
- If gaps: modify `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java` and/or `src/main/java/com/openggf/tools/Sonic3kObjectProfile.java`.

- [ ] **Step 1: Re-check audit output from T1**

If T1 commit body reports registry + implemented-IDs both present, skip this task entirely (no commit). Proceed to T10.

- [ ] **Step 2: If gaps, apply them with a test-first cycle**

Write `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossRegistered.java` asserting `Sonic3kObjectRegistry.createInstance(spawn, S3KL)` returns a `CnzMinibossInstance` (non-placeholder) and that `Sonic3kObjectProfile.SHARED_IMPLEMENTED_IDS.contains(0xA6)` is false while the S3KL set contains it. Run, fail, add the missing entries, run, pass.

- [ ] **Step 3: Commit (only if any file staged)**

```bash
git commit -m "$(cat <<'EOF'
chore(s3k): ensure CNZ1 miniboss is registered and implemented (audit fallout)

Noops if T1 audit reported the factory + S3KL_IMPLEMENTED_IDS as
already good. Otherwise adds the missing registration + id.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Art + PLC 0x5D registration sanity check

**Why this task is small:** `Sonic3kObjectArtProvider` already has `registerSheet(CNZ_MINIBOSS, ...)` wired to `PLC_CNZ_MINIBOSS` (Task 2 corrected the id). `Sonic3kObjectArtKeys.CNZ_MINIBOSS` already exists. Per spec §9, art/rendering is out of scope; headless tests do not assert visuals. This task exists to confirm nothing blew up after the 0x5D id change and to catch any VRAM-overlap surprise that would crash the integration test.

**Files:**
- No code change unless a runtime failure surfaces.

- [ ] **Step 1: Run the art-loading smoke test via the arena entry path**

Run: `mvn test "-Dmse=off" "-Dtest=TestCnzMinibossArenaEntry+TestS3kCnzMinibossArenaHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: both green, no `PlcParser` warnings or `IOException` logged. If `Sonic3kObjectArtProvider.loadArtForZone` raises an uncaught exception when PLC 0x5D is applied:
1. Wrap the load in a try/catch logging a single warning (spec §9: headless tests must not gate on art).
2. Add a failing unit test to `TestCnzMinibossConstants` documenting the residual as a known deferred item, and list it in Task 12's CHANGELOG rollup.

- [ ] **Step 2: If defensive try/catch required, commit it**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java
git commit -m "$(cat <<'EOF'
chore(s3k): defensive guard for CNZ1 miniboss PLC 0x5D art load

Catches any PlcParser / decompression exception during arena entry
art registration so the headless trace-replay test does not abort on
visual-only failures. Per spec §9, workstream D does not gate on art
parity; visual recovery is deferred.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

If Step 1 is green, skip Step 2 entirely (no commit).

---

### Task 11: Integration headless test `TestS3kCnzMinibossHeadless`

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS3kCnzMinibossHeadless.java`

**Why this is a fresh class, not an extension of `TestS3kCnzMinibossArenaHeadless`:** that test covers only the bridge contract (top -> events -> base). The integration test exercises the full fight loop through `HeadlessTestFixture`, letting the real `Sonic3kCNZEvents.update` run, the camera advance naturally, and the boss + top state machines co-operate.

- [ ] **Step 1: Write the failing integration test**

```java
package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.RuntimeManager;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.objects.CnzMinibossInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless end-to-end test for the CNZ1 miniboss encounter. Drives
 * Sonic to the arena threshold X, lets the fight state machine run,
 * and confirms that the fight eventually terminates within the
 * trace-budget frame window.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzMinibossHeadless {

    @AfterEach
    void tearDown() {
        RuntimeManager.destroyCurrent();
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void arenaEntryFiresBossFlagAtThreshold() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        GameServices.camera().setX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);
        fixture.stepFrame(false, false, false, false, false);

        assertTrue(getCnzEvents().isBossFlag(),
                "Boss_flag must be set when camera reaches arena min X");
    }

    @Test
    void bossSpawnsAndRunsStateMachine() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        GameServices.camera().setX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);
        for (int i = 0; i < 60; i++) fixture.stepFrame(false, false, false, false, false);

        Optional<CnzMinibossInstance> boss = findBoss();
        assertTrue(boss.isPresent(), "CNZ miniboss instance must exist within 60 frames of arena entry");
        assertTrue(boss.get().getCurrentRoutine() >= 2,
                "Boss must leave routine 0 (Init) within 60 frames");
    }

    @Test
    void fightResolvesWithin400FramesOfArenaEntry() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build();
        GameServices.camera().setX((short) Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X);

        // Stub six hits across the fight window by nudging the boss's simulateHitForTest
        // at roughly the ROM's expected hit cadence. This is a headless smoke test, not a
        // bit-perfect replay: the goal is to confirm the defeat path completes.
        Optional<CnzMinibossInstance> boss = Optional.empty();
        for (int i = 0; i < 400 && !getCnzEvents().isBossFlag() == false; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (boss.isEmpty()) boss = findBoss();
            if (boss.isPresent() && i % 60 == 0) boss.get().simulateHitForTest();
        }
        assertFalse(getCnzEvents().isBossFlag(),
                "Boss_flag must be cleared (defeat path) within 400 frames of arena entry");
    }

    private static Sonic3kCNZEvents getCnzEvents() {
        Sonic3kLevelEventManager events =
                (Sonic3kLevelEventManager) GameServices.level().getLevelEventManager();
        return (Sonic3kCNZEvents) events.getZoneEvents();
    }

    private static Optional<CnzMinibossInstance> findBoss() {
        ObjectManager mgr = GameServices.sprites().getObjectManager();
        return mgr.getAllObjects().stream()
                .filter(CnzMinibossInstance.class::isInstance)
                .map(CnzMinibossInstance.class::cast)
                .findFirst();
    }
}
```

If `ObjectManager.getAllObjects()` is not the right accessor, substitute the existing `getAllDynamic()` / `findByType()` helpers used by comparable S3K tests.

- [ ] **Step 2: Run the test**

Run: `mvn test "-Dmse=off" "-Dtest=TestS3kCnzMinibossHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: all 3 tests PASS.

If `fightResolvesWithin400FramesOfArenaEntry` fails:
- If hits stop landing after 4-5 cycles, boss is stuck in routine 8 without the open-bit flipping: re-audit Task 5.
- If `bossFlag` never clears after 6 hits, re-audit the Task 6 defeat path.
- If no boss spawns at all, re-audit Task 1 registry + Task 8 arena wiring (the ROM relies on `Obj_Wait` and `AllocateObject` chaining — the headless bootstrap may need a direct `ObjectManager.addDynamicObject(new CnzMinibossInstance(...))` call in `Sonic3kCNZEvents` arena entry if AllocateObject is not emulated).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/tests/TestS3kCnzMinibossHeadless.java
git commit -m "$(cat <<'EOF'
test(s3k): end-to-end headless test for CNZ1 miniboss fight

Drives Sonic to the arena threshold, lets Sonic3kCNZEvents arm the
fight, and confirms the boss spawns, executes the state machine, and
resolves defeat within a 400-frame budget. Uses simulateHitForTest to
substitute player hits so the test does not depend on player-side
collision parity that workstream D is not tasked to fix.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: Run `TestS3kCnzTraceReplay`, capture post-D baseline, roll up CHANGELOG

**Files:**
- Create: `docs/architecture/validation/s3k-zones/cnz-post-workstream-d.md`
- Modify: `CHANGELOG.md`
- Possibly modify: `docs/S3K_KNOWN_DISCREPANCIES.md` (only if a residual divergence remains)

Mirrors workstream C Task 9.

- [ ] **Step 1: Run the full trace test**

Run: `mvn test "-Dmse=off" "-Dtest=TestS3kCnzTraceReplay" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`

Expected: test still fails (workstream D does not aim to drive it to zero), but:
- Errors 6..20 from the post-C baseline (frames 193..292) are replaced by later-frame divergences.
- Total error count drops from 1954 to **<= 1800** (target: <= 1700 if hit-response timing lines up cleanly).

- [ ] **Step 2: Inspect the divergence report**

Open the generated `target/trace-reports/s3k_0300_report.json` and `_context.txt` (or the equivalent path the trace framework writes to on this branch). Record the new first-20 divergences.

Create `docs/architecture/validation/s3k-zones/cnz-post-workstream-d.md`:

```markdown
# CNZ Trace Replay - Post-Workstream-D Baseline

Captured: <YYYY-MM-DD>
Test: `TestS3kCnzTraceReplay`
After: workstream D (CNZ1 miniboss) merge at <commit SHA>

## Summary

- Pre-D baseline (post-C): 1954 errors, first divergence frame 4
- Post-D baseline: <NNN> errors, first divergence frame <NNN>
- Delta vs post-C: <delta> errors, first-frame shift <delta>

## First 20 divergences (post-D)

| # | Frame (start) | Frame (end) | Field | Expected | Actual | Cascading | Likely workstream |
|---|---------------|-------------|-------|----------|--------|-----------|-------------------|

(fill in from report)

## Cascade analysis

- Workstream C residue (frames 4..107): <still visible? if yes, same rows as post-C baseline>
- Workstream D mini-boss window (frames 193..292): <displaced off first-20? which fields remain?>
- Workstream E end-boss window (frames >= TBD): <any visible now that D cleared?>
- Workstream F Knuckles cutscenes: <any?>
- Workstream G stragglers: <any?>

## Next dispatch

- [ ] C-follow-up (Tails flying-with-cargo physics): <dispatch or defer>
- [ ] D-follow-up (boss hit-response timing): <dispatch or defer>
- [ ] E (CNZ2 end-boss): <dispatch or defer>
- [ ] F (Knuckles cutscenes): <dispatch or defer>
- [ ] G (Stragglers): <dispatch or defer>

## Wider guard status

`mvn test "-Dmse=off" "-Dtest=TestS3kAiz1SkipHeadless+TestSonic3kLevelLoading+TestSonic3kBootstrapResolver+TestSonic3kDecodingUtils+TestS3kCnzCarryHeadless+TestSidekickCpuControllerCarry+TestSonic3kCnzCarryTrigger+TestObjectControlledGravity+TestPhysicsProfile+TestCollisionModel"` — <result>.

## New D-owned tests

| Class | Result |
|-------|--------|
| `TestCnzMinibossConstants` | <result> |
| `TestCnzMinibossInstanceBase` | <result> |
| `TestCnzMinibossSwingPhase` | <result> |
| `TestCnzMinibossOpeningPhase` | <result> |
| `TestCnzMinibossDefeatPhase` | <result> |
| `TestCnzMinibossTopPhysics` | <result> |
| `TestCnzMinibossArenaEntry` | <result> |
| `TestS3kCnzMinibossHeadless` | <result> |
| `TestS3kCnzMinibossArenaHeadless` | <result> |

## AIZ replay regression

`TestS3kAizTraceReplay` continues to fail on the pre-existing `aiz1_fire_transition_begin`
checkpoint issue (workstream H). Not a D regression.
```

- [ ] **Step 3: Re-run the wider-guard suite**

Run:
```
mvn test "-Dmse=off" "-Dtest=TestS3kAiz1SkipHeadless+TestSonic3kLevelLoading+TestSonic3kBootstrapResolver+TestSonic3kDecodingUtils+TestS3kCnzCarryHeadless+TestSidekickCpuControllerCarry+TestSonic3kCnzCarryTrigger+TestObjectControlledGravity+TestPhysicsProfile+TestCollisionModel" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

Expected: 115 tests, 0 failures, 0 errors (same as post-C baseline). `TestS3kAizTraceReplay` permitted to remain in its pre-existing red state (workstream H owns that).

Also run all D-owned test classes:
```
mvn test "-Dmse=off" "-Dtest=TestCnzMinibossConstants+TestCnzMinibossInstanceBase+TestCnzMinibossSwingPhase+TestCnzMinibossOpeningPhase+TestCnzMinibossDefeatPhase+TestCnzMinibossTopPhysics+TestCnzMinibossArenaEntry+TestS3kCnzMinibossHeadless+TestS3kCnzMinibossArenaHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

Expected: all green.

- [ ] **Step 4: Roll up the CHANGELOG entry**

Open `CHANGELOG.md`. Add a new sub-section under `## Unreleased`:

```markdown
### Sonic 3&K CNZ1 Mini-Boss (Workstream D)

- Ported `Obj_CNZMiniboss` (sonic3k.asm:144823..145002) to
  `CnzMinibossInstance` atop `AbstractBossInstance`: full 8-routine
  state machine (Init, Lower, Move, Opening, WaitHit, Closing, Lower2,
  End), ROM hit count of 6, and the Lower2 per-pixel descent.
- Ported `Obj_CNZMinibossTop` (sonic3k.asm:145004..145673) to
  `CnzMinibossTopInstance`: 4-routine state machine (TopInit, TopWait,
  TopWait2, TopMain) with bouncing-ball physics and arena-chunk
  destruction publication.
- Wired CNZ1 arena entry in `Sonic3kCNZEvents`: camera clamps
  (0x31E0..0x3260, 0x01C0..0x02B8), `Boss_flag`, wall-grab
  suppression, PLC 0x5D, and `Pal_CNZMiniboss` installation — mirroring
  ROM `loc_6D9A8`. Corrected the miniboss camera-trigger X from 0x3000
  to the ROM's 0x31E0.
- Corrected `PLC_CNZ_MINIBOSS` from 0x5C to 0x5D (ROM
  `sonic3k.asm:144844`). No external behaviour depended on the
  off-by-one value in prior commits; the now-corrected PLC load is
  guarded per spec §9.
- Reduced `TestS3kCnzTraceReplay` error count from 1954 to <NNN>
  (delta <delta>). Baseline row 6..20 from the post-C report are
  displaced off the first-20 divergence list; new first-20 captured in
  `docs/architecture/validation/s3k-zones/cnz-post-workstream-d.md`.
```

- [ ] **Step 5: If a D-owned residual divergence remains, add to `S3K_KNOWN_DISCREPANCIES.md`**

Example residual to document (only if seen in the post-D report): "CNZ1 miniboss swing direction initial sign." If nothing remains, **do not edit** `docs/S3K_KNOWN_DISCREPANCIES.md` and keep that trailer as `n/a`.

- [ ] **Step 6: Commit the baseline + CHANGELOG rollup**

```bash
git add docs/architecture/validation/s3k-zones/cnz-post-workstream-d.md CHANGELOG.md
# If S3K_KNOWN_DISCREPANCIES.md was edited:
#   git add docs/S3K_KNOWN_DISCREPANCIES.md
git commit -m "$(cat <<'EOF'
docs(s3k): capture post-workstream-D CNZ baseline and changelog

Re-captures the TestS3kCnzTraceReplay divergence summary after the
CNZ1 miniboss port. First-divergence frame shifted from 4 to <NNN>;
total errors reduced from 1954 to <NNN>. Adds a CNZ1 Mini-Boss entry
to the Unreleased section of CHANGELOG.md covering the full boss and
top-piece state machines, the arena-entry wiring, the PLC id
correction (0x5C -> 0x5D), and the replay-delta measurements.

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: <updated or n/a per Step 5>
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Success Criteria (plan-level)

All tasks 1-12 complete with:

- [ ] Task 1: Audit committed (scoping note in commit body), no regressions from audit
- [ ] Task 2: `Sonic3kConstants` carries the corrected PLC id and arena + state-machine literals; `TestCnzMinibossConstants` green
- [ ] Task 3: `CnzMinibossInstance` extends `AbstractBossInstance`; initial hit count from ROM; arena-chunk seam preserved
- [ ] Task 4: Routines 0/2/4 (Init, Lower, Move) implemented; `TestCnzMinibossSwingPhase` green
- [ ] Task 5: Routines 6/8/A (Opening, WaitHit, Closing) implemented; `TestCnzMinibossOpeningPhase` green
- [ ] Task 6: Routines C/E + hit / defeat wiring implemented; `TestCnzMinibossDefeatPhase` green
- [ ] Task 7: `CnzMinibossTopInstance` runs the full 4-routine state machine; `TestCnzMinibossTopPhysics` + `TestS3kCnzMinibossArenaHeadless` green
- [ ] Task 8: `Sonic3kCNZEvents` arena entry threshold corrected to ROM 0x31E0; camera clamps, boss flag, wall-grab suppression, PLC 0x5D, palette install wired; `TestCnzMinibossArenaEntry` green
- [ ] Task 9: Registry + S3KL implemented-IDs set verified (no-op if audit clean; else one extra commit)
- [ ] Task 10: Art/PLC 0x5D smoke test green; optional defensive try/catch if art load explodes
- [ ] Task 11: `TestS3kCnzMinibossHeadless` green (arena entry, boss spawn, 6-hit defeat within 400 frames)
- [ ] Task 12: `TestS3kCnzTraceReplay` error count dropped to <= 1800; post-D baseline + CHANGELOG rollup committed

**Plan-level quantitative goals:**
- `TestS3kCnzTraceReplay` errors: **<= 1800** (down from 1954 post-C baseline)
- Post-C baseline rows 6..20 (frames 193..292): **displaced off** the post-D first-20 list (new first-20 may include previously-hidden later-frame divergences)
- Wider S3K guard (115 tests, CLAUDE.md §Keep these S3K tests green + new carry tests): **all green**
- `TestS3kCnzMinibossArenaHeadless`: **still green** (no Task-7 regressions)
- `TestS3kAizTraceReplay`: permitted to remain in its pre-existing red state (workstream H owns that)

---

## Risks

- **Cascade from workstream C.** C's "ground-release impulse timing" (post-C baseline row 5, frame 107 `y_speed -0100` vs `0x0000`) may shift Sonic's entry frame/position into the arena. Some frame 193+ divergences may be *cascaded* from C, not native to D. If post-D numbers don't improve as expected, investigate cascade dominance before expanding D scope: search the post-D divergence report for early-frame rows that are identical to the post-C report (same field + magnitude at same frame). If dominance is observed, the right response is to document it in the post-D baseline and leave the boss port intact rather than adding D-only patches that try to paper over a C residue.

- **Flying-with-cargo gap (deferred, documented).** Tails grounds ~frame 42 vs ROM 106 (see `docs/S3K_KNOWN_DISCREPANCIES.md` "Tails Flying-With-Cargo Physics"). This means Sonic enters the miniboss approach with slightly different position/velocity than the ROM reference. The boss's own state machine still gets exercised; the player-side cascade is orthogonal.

- **Art / rendering out of scope.** Visuals are not gated per spec §9. If art loading crashes the headless test, Task 10 catches and logs. If the PLC id correction (0x5C -> 0x5D) reveals a latent issue (e.g. decompression assumes the wrong block size), the defensive try/catch in Task 10 isolates it.

- **`AllocateObject` semantics in headless mode.** ROM `Obj_CNZMiniboss` calls `AllocateObject` then installs `Obj_CNZMinibossScrollControl`, and similarly for the top piece via `CreateChild1_Normal`. The engine may not emulate these exact allocation primitives; the arena entry code in Task 8 may need an explicit `ObjectManager.addDynamicObject(new CnzMinibossInstance(...))` call instead of relying on ROM-style spawn. This is the most likely source of "no boss spawns" in Task 11. Mitigation: if `TestS3kCnzMinibossHeadless.bossSpawnsAndRunsStateMachine` fails, add a direct spawn in `Sonic3kCNZEvents` arena entry.

- **ROM label `Sonic3kBossInstance` does not exist in this repo.** Parent spec §7.2 says "Uses `Sonic3kBossInstance` base class"; the actual base is `AbstractBossInstance` (verified in Task 1 audit). This plan uses the correct base class throughout; the spec wording is treated as a minor drafting inaccuracy rather than a scope change.

- **Simultaneous edits with workstream E (CNZ2 end-boss) agent.** If E is running in parallel, both may touch `Sonic3kCNZEvents` arena entry logic. Mitigate by additive editing: D modifies `handleAct1Entry()` only; E should modify `updateAct2Fg()` only. Textual merge conflicts are the easy case; semantic conflicts are unlikely given the `act == 0` / `act != 0` split at the top of `update()`.

- **`Swing_UpAndDown` reuse vs inline port.** The ROM's `Swing_UpAndDown` is used by many bosses, and the engine has a `SwingMotion` helper in `com.openggf.physics`. If the helper's API does not match the CNZ usage (e.g. expects a parent object with a specific timer layout), inline the swing math with a `// ROM: Swing_UpAndDown` comment rather than warping the helper. CNZ's swing period is `$9F` frames (Task 2 constant `CNZ_MINIBOSS_SWING_WAIT`).

- **Palette installation path.** `Pal_CNZMiniboss` must route through the shared `PaletteOwnershipRegistry` (see CLAUDE.md §Runtime-Owned Framework Stack), not directly poked into `GraphicsManager`. If the existing CNZ event code already owns the boss palette line via the registry, piggy-back that ownership; otherwise register a new owner in Task 8 with precedence ordered above the normal CNZ palette.

---

## Wider-guard command (run after final commit)

```bash
mvn test "-Dmse=off" "-Dtest=TestS3kAiz1SkipHeadless+TestSonic3kLevelLoading+TestSonic3kBootstrapResolver+TestSonic3kDecodingUtils+TestS3kCnzCarryHeadless+TestSidekickCpuControllerCarry+TestSonic3kCnzCarryTrigger+TestObjectControlledGravity+TestPhysicsProfile+TestCollisionModel+TestCnzMinibossConstants+TestCnzMinibossInstanceBase+TestCnzMinibossSwingPhase+TestCnzMinibossOpeningPhase+TestCnzMinibossDefeatPhase+TestCnzMinibossTopPhysics+TestCnzMinibossArenaEntry+TestS3kCnzMinibossArenaHeadless+TestS3kCnzMinibossHeadless" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"
```

Expected: all green. If any red, the controller decides whether to spawn a D-follow-up or roll back.
