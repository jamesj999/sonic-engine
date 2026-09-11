# MZ1 Audit Sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Audit every S1 physics/collision commit made during the MZ1 trace-debugging window, produce per-mechanism dossiers with verdicts (KEEP / REWRITE / REVERT / INVESTIGATE), and a prioritized remediation plan — without touching any production code.

**Architecture:** Serial Phase 0 (baseline) → Serial Phase 1 (catalog) → Parallel Phase 2 (10 mechanism dossiers) → Serial Phase 3 (synthesis). All output goes to `docs/architecture/designs/2026-04-05-mz1-audit-sweep/`.

**Tech Stack:** Git history analysis, s1disasm/s2disasm/skdisasm 68000 assembly cross-reference, markdown output.

**Spec:** `docs/architecture/designs/2026-04-05-mz1-audit-sweep-design.md`

---

## Important Context

During a ~48-hour autonomous run, an agent made ~40 commits trying to drive MZ1 trace-replay errors to zero. The recording was later found to contain lag frames, meaning some "fixes" may be hacks that bend engine behavior to match a faulty trace rather than the ROM. This audit identifies which changes are legitimate (match s1disasm) and which need to be reverted or rewritten.

**Rules for the auditor:**
1. A **KEEP** verdict requires a specific s1disasm citation: subroutine name + ROM address + code excerpt. No citation = not KEEP.
2. Every mechanism dossier must include a **cross-game surface check**: does the change only affect S1 code paths, or does it touch shared code (`ObjectManager`, `AbstractObjectInstance`, `CollisionSystem`, `PlayableSpriteMovement`, etc.)? Shared code without S2/S3K verification = automatic **REWRITE** (needs feature gating).
3. `fix(test):` commits travel with their physics commit. If the physics verdict is REVERT, the test change probably reverts too.
4. This is a **read-only audit**. No code changes. No fixes applied. Output is markdown only.

## Dossier Template

Every Phase 2 task produces one file following this structure:

```markdown
# Mechanism: <name>

## Summary
One-paragraph: what this mechanism does in the ROM, what problem the commits
were trying to solve.

## Commits in scope
| Commit | Subject | Files touched |
|--------|---------|---------------|

## s1disasm ground truth
- Subroutine name, ROM address
- Cited code (not paraphrased — quote the assembly)
- Plain-english description of what the ROM does

## Engine as-is
- File:line references to current engine code
- Relevant code inline
- Plain-english description of what the engine does

## Divergence analysis
Per commit — does the change match the ROM, partially match, or diverge?

## Verdicts
| Commit | Verdict | Rationale |
|--------|---------|-----------|

- **KEEP** — matches disasm, cited, properly scoped
- **REWRITE** — intent correct, implementation wrong or untethered from ROM
- **REVERT** — change exists only to make the trace fit; no disasm basis
- **INVESTIGATE** — can't determine from disasm alone, defer to stable-retro phase

## Cross-game surface check
- Files touched (list)
- Code-path scope: S1-only (behind feature flag / game check) OR shared
- If shared: does behavior also match s2disasm? skdisasm? Cite subroutines.
- If shared & unverified: automatic REWRITE (must be gated)

## Coupled test changes
- fix(test): commits associated with this mechanism
- Per-test: follows from a KEEP, or masks a REVERT?

## Proposed fixes
For REWRITE/REVERT: specific description of what to change, keyed to file:line.
```

---

### Task 1: Phase 0 — Establish baseline and create output directory

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/00-scope.md`

- [ ] **Step 1: Create the output directory**

```bash
mkdir -p docs/architecture/designs/2026-04-05-mz1-audit-sweep
```

- [ ] **Step 2: Identify the baseline commit**

Walk git log to find the last commit before MZ1-driven work began. The boundary is between infrastructure/S3K work and the first commit that targets MZ1 trace errors.

Run:
```bash
git log --oneline --format="%h %ai %s" b5d85c411..324fadc63
```

Evaluate each commit:
- `b5d85c411` (Mar 27 10:35) "Add Sonic 1 overlay and S3K object profile test" — pre-MZ1 infrastructure
- `044df514a` (Mar 27 10:46) "Document S3K Knuckles..." — documentation, pre-MZ1
- `7e9141cb4` (Mar 27 11:03) "fix: remove services() from all object constructors" — services migration, not MZ1-driven
- `d4c33442f` (Mar 27 11:24) "fix: defer LavaGeyser init" — S1 lava object, possibly MZ1-related
- `d5c471ef9` (Mar 27 16:54) "fix: staircase contact detection" — S1 object, possibly MZ1-related
- `aa2c026c5` (Mar 27 17:12) "fix: replace frame-counter contact detection in 5 S2 objects" — S2, cross-game
- `ed319586a` (Mar 27 14:44) "feat: upgrade trace recorder to v2" — infrastructure
- `c002b62a7` (Mar 27 19:05) "Add MZ1 trace baseline" — clearly MZ1

Criteria: the baseline is the last commit where the subject is clearly NOT targeting MZ1 trace error reduction. `7e9141cb4` (services migration) is the likely candidate — it's an infrastructure change unrelated to trace accuracy.

However, `d4c33442f` and `d5c471ef9` may have been preparatory for MZ1 (they fix S1 object lifecycle issues). Include them in the audit scope to be safe.

**Selected baseline: `7e9141cb4` (services migration)**. Everything from `d4c33442f` onward is in-scope.

- [ ] **Step 3: Record HEAD SHA**

```bash
git rev-parse HEAD
```

- [ ] **Step 4: Write 00-scope.md**

Write the following to `docs/architecture/designs/2026-04-05-mz1-audit-sweep/00-scope.md`:

```markdown
# MZ1 Audit Sweep — Scope

## Baseline
- **Commit:** `7e9141cb4` — "fix: remove services() from all object constructors to prevent IllegalStateException"
- **Date:** 2026-03-27
- **Rationale:** Last infrastructure commit before MZ1-driven physics changes began. Services migration is unrelated to trace accuracy.

## HEAD at audit start
- **Commit:** `<HEAD SHA from step 3>`
- **Branch:** `feature/ai-s3k-bonus-stage-framework`

## Audit window
All commits from `d4c33442f` through `d19f90a2a` that touch S1 physics, collision, object interaction, or their tests. S3K-only feature commits (AIZ objects, gumball machine, bonus stage) are excluded.

## Key shared files to watch
These files are used by all games (S1/S2/S3K). Changes here need cross-game verification:
- `src/main/java/com/openggf/level/objects/ObjectManager.java`
- `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- `src/main/java/com/openggf/level/objects/ObjectInstance.java`
- `src/main/java/com/openggf/level/LevelManager.java`
- `src/main/java/com/openggf/physics/CollisionSystem.java`
- `src/main/java/com/openggf/physics/GroundSensor.java`
- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- `src/main/java/com/openggf/game/PhysicsFeatureSet.java`
- `src/main/java/com/openggf/level/objects/DestructionEffects.java`
- `src/main/java/com/openggf/level/rings/RingManager.java`
```

- [ ] **Step 5: Commit scope file**

```bash
git add docs/architecture/designs/2026-04-05-mz1-audit-sweep/00-scope.md
git commit -m "docs: MZ1 audit sweep — scope and baseline"
```

---

### Task 2: Phase 1 — Commit catalog

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/01-commit-catalog.md`

- [ ] **Step 1: Generate the raw commit list**

```bash
git log --oneline --format="%h %s" 7e9141cb4..d19f90a2a -- "*.java"
```

Filter out commits that are purely S3K feature work (AIZ objects, gumball, bonus stage) — these are out of scope. Keep everything that touches S1 physics, shared infrastructure, or tests.

- [ ] **Step 2: For each commit, identify files touched**

For each in-scope commit SHA:
```bash
git show --stat <sha> -- "*.java"
```

- [ ] **Step 3: Classify each commit into a mechanism group**

Assign each commit to exactly one mechanism group. Expected groups:

| # | Mechanism slug | Description | Expected commits |
|---|----------------|-------------|------------------|
| 1 | `platform-carry` | MvSonicOnPtfm2 X/Y carry, offset 9 vs 8, release-frame behavior | `1f3cc06fd`, `8dece743e`, `f5eb05500`, `fdc82aecb` |
| 2 | `touch-response` | Pre-update collision flags, first-frame skip, on-screen X-only guard, HURT continuous | `e022f95d1`, `534dedd1d`, `dc65b04f7` |
| 3 | `solid-object` | Solid_Landed width (obActWid), ExitPlatform path exclusivity, sticky buffer | `013ebb6a9`, `081ead57e`, `91dfcc300` |
| 4 | `out-of-range` | S1 OOR check timing, isInRange 16-bit wrap, bubble spawner deletion | `5499c24bf`, `324fadc63`, `b944fddb5` |
| 5 | `spike-params` | Spik_Hurt Y rewind, sideways spike d3 (d2+1) | `f15726849`, `7144e27ae` |
| 6 | `player-velocity` | Airborne side speed zeroing revert, airSuperspeedPreserved, air control cap, velocity pre-apply skip | `8d0fd944d`, `7047a99c9`, `c5dc4c257`, `d19f90a2a` |
| 7 | `spawn-system` | OPL cursor diagnostics, desync recovery, dormant spawn tracking, SpawnCallback | `f7c27fc24`, `f57e3c35c`, `0b040b430`, `345dab858` |
| 8 | `ring-refactor` | Sonic1RingInstance replacing phantom rings, ring physics features, sparkle delay | `032255f92`, `d07708127`, `cef836cbb`, `bf124cb1a`, `babec749b`, `52f3ce43b`, `7ea3fba9f`, `c5beba3c7`, `7d9bc0b88`, `b5307319c`, `9d4a53cf8` |
| 9 | `object-lifecycle` | Lavafall cascade prevention, LavaGeyser deferred init, contact detection boolean flags, services cleanup | `a840cbe05`, `cbe4fa944`, `d0ff4ec30`, `d4c33442f`, `d5c471ef9`, `aa2c026c5` |
| 10 | `animation` | Animation fallback chain in resolveAnimationId for S1 bubble breathing | `accf5d9a3` |

Commits that are diagnostic/cleanup only (no physics impact):
- `8d5255111` — diag: BizHawk Y-write watcher (diagnostic only, may be already cleaned up)
- `ce3dcd4fb` — diag: tile-level collision probe (diagnostic only)
- `d7e2d0aca` — diag: wall and floor probe (diagnostic only)
- `2fffdc0e5` — cleanup: remove temporary diagnostic logging
- `4506ce853` — remove stale diagnostic dump
- `a924c85b3` — docs: explain isOnObject guard

Coupled `fix(test):` commits — assign to the mechanism they adjusted tests for:
- `9b376f472` "correct S1 top-solid edge landing test" → `solid-object`
- `c1b006cb9` "S1 air/object desync expects no jump" → `player-velocity`
- `e5c053b50` "staircase test tracks peak descent" → `object-lifecycle`
- `ef152cfd5` "rising lava route split test setup" → `object-lifecycle`
- `a7e2f5ba7` "spring loop test allows spawn window" → `spawn-system`
- `8c7e3ecf4` "HTZ boss touch response test properly initializes" → `touch-response`
- `3aa2386b7` "S3K SS entry ring test calls update" → `ring-refactor`

- [ ] **Step 4: Write 01-commit-catalog.md**

Write the catalog with:
1. Full table of in-scope commits: SHA, subject, mechanism group, files touched
2. Diagnostic/cleanup commits (excluded from mechanism dossiers but listed)
3. `fix(test):` coupling table
4. Any commits that couldn't be classified (for user triage)

- [ ] **Step 5: Commit catalog**

```bash
git add docs/architecture/designs/2026-04-05-mz1-audit-sweep/01-commit-catalog.md
git commit -m "docs: MZ1 audit sweep — commit catalog"
```

---

### Task 3: Mechanism — `platform-carry` (MvSonicOnPtfm2)

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-platform-carry.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `1f3cc06fd` | fix: use groundHalfHeight (d3) for MvSonicOnPtfm standing Y and ROM-exact spike params |
| `8dece743e` | fix: S1 MovingBlock riding Y uses MvSonicOnPtfm2 offset (9, not 8) |
| `f5eb05500` | fix: MvSonicOnPtfm2 offset for Platform/CollapsingFloor + touch skip |
| `fdc82aecb` | fix: on-screen guard for touch responses + MvSonicOnPtfm2 platform fixes |

**Coupled test changes:** None directly identified.

- [ ] **Step 1: Read the commit diffs**

For each commit:
```bash
git show <sha>
```

Note what changed and the commit message's claimed justification.

- [ ] **Step 2: Read the s1disasm ground truth**

Find and read the relevant S1 subroutines in `docs/s1disasm/`:
- `MvSonicOnPtfm` / `MvSonicOnPtfm2` — search for label in `_incObj/` or `_maps/` files
- `MvSonic2` — the shared carry routine at ROM $0081DC
- `MBlock_StandOn` — MovingBlock standing logic
- `PlatformObject` / `Plat_StandOn` — Platform standing logic

```bash
grep -r "MvSonicOnPtfm" docs/s1disasm/ --include="*.asm" -l
grep -r "MvSonic2" docs/s1disasm/ --include="*.asm" -l
grep -r "MBlock_StandOn\|loc_C76E" docs/s1disasm/ --include="*.asm" -l
```

Read the matched files. Quote the assembly code exactly.

- [ ] **Step 3: Read the engine code as-is**

Read the current implementation in:
- `src/main/java/com/openggf/level/objects/ObjectManager.java` — search for `MvSonicOnPtfm`, `clearRidingObject`, `SolidContacts`
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1MovingBlockObjectInstance.java`
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1PlatformObjectInstance.java`
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1CollapsingFloorObjectInstance.java`

- [ ] **Step 4: Divergence analysis**

For each commit, compare the change against the s1disasm code:
- Does the offset value (9 vs 8) match the assembly?
- Does the X carry delta logic match `sub.w D2,8(A1)` in MvSonic2?
- Does the on-screen guard change match ROM behavior?
- Is the standing Y calculation correct per the disasm?

- [ ] **Step 5: Cross-game surface check**

Check each touched file:
- `ObjectManager.java` (SolidContacts inner class) — **shared across all games**. Check if the platform-carry change is gated to S1 or applies globally. If global, verify the same behavior in `docs/s2disasm/` (search for `MvSonicOnPtfm2`) and `docs/skdisasm/` (search for `MvSonicOnPtfm2`).
- S1 object files — **S1-only**, no cross-game concern.

```bash
grep -r "MvSonicOnPtfm" docs/s2disasm/ --include="*.asm" -l
grep -r "MvSonicOnPtfm" docs/skdisasm/ --include="*.asm" -l
```

- [ ] **Step 6: Assign verdicts**

For each commit:
- If the change matches the s1disasm code with a specific citation → **KEEP**
- If the intent is right but the implementation is wrong → **REWRITE** + describe fix
- If there's no disasm basis and the change was made to reduce trace errors → **REVERT**
- If ambiguous → **INVESTIGATE**

- [ ] **Step 7: Write the dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-platform-carry.md` following the dossier template at the top of this plan.

---

### Task 4: Mechanism — `touch-response`

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-touch-response.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `e022f95d1` | fix: pre-update collision flags for ROM-accurate touch response timing |
| `534dedd1d` | fix: touch response on-screen check uses X-only (ROM MarkObjGone) |
| `dc65b04f7` | fix: make HURT touch category continuous to match ROM Touch_ChkHurt behavior |

**Coupled test changes:**
- `8c7e3ecf4` — "HTZ boss touch response test properly initializes collision pipeline"

**Cross-reference:** Commit `fdc82aecb` is owned by `platform-carry` but also touches touch-response (on-screen guard). Check the platform-carry dossier for its verdict on that commit's on-screen guard changes.

- [ ] **Step 1: Read the commit diffs**

```bash
git show e022f95d1
git show 534dedd1d
git show dc65b04f7
git show 8c7e3ecf4
```

- [ ] **Step 2: Read the s1disasm ground truth**

Find and read:
- `Touch_ChkHurt` / `Touch_Hurt` — touch response collision dispatch
- `MarkObjGone` — on-screen check that gates object updates
- The collision flag update order in the main loop

```bash
grep -r "Touch_ChkHurt\|Touch_Hurt" docs/s1disasm/ --include="*.asm" -l
grep -r "MarkObjGone" docs/s1disasm/ --include="*.asm" -l
```

Read and quote the assembly.

- [ ] **Step 3: Read the engine code as-is**

- `src/main/java/com/openggf/level/objects/ObjectManager.java` — search for `TouchResponses`, `processTouch`, `isOnScreen`
- `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java` — search for `collisionFlags`, `preUpdateCollision`, `isOnScreen`
- `src/main/java/com/openggf/level/objects/ObjectInstance.java` — check interface additions

- [ ] **Step 4: Divergence analysis**

Key questions:
- Does `pre-update collision flags` match when the ROM updates touch-response flags relative to object execution?
- Does `X-only on-screen check` match `MarkObjGone`'s actual implementation (does it check Y?)
- Does `HURT touch category continuous` match how `Touch_ChkHurt` really works in the ROM?
- Does the HTZ boss test change mask a regression or correctly adapt to a fix?

- [ ] **Step 5: Cross-game surface check**

All three commits touch `ObjectManager.java` and/or `AbstractObjectInstance.java` — **shared code**.
- Check s2disasm for `Touch_ChkHurt`, `MarkObjGone`:
```bash
grep -r "Touch_ChkHurt" docs/s2disasm/ --include="*.asm" -l
grep -r "MarkObjGone" docs/s2disasm/ --include="*.asm" -l
```
- Check skdisasm for the same:
```bash
grep -r "Touch_ChkHurt" docs/skdisasm/ --include="*.asm" -l
grep -r "MarkObjGone" docs/skdisasm/ --include="*.asm" -l
```
- Are these behaviors identical across games, or do they differ? If they differ, the engine code needs gating.

- [ ] **Step 6: Assign verdicts**

Apply the verdict criteria from the dossier template.

- [ ] **Step 7: Write the dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-touch-response.md`.

---

### Task 5: Mechanism — `solid-object`

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-solid-object.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `013ebb6a9` | fix: S1 solid object landing accuracy (147→113 MZ1 errors) |
| `081ead57e` | fix: global Solid_Landed width uses obActWid, not collision halfWidth |
| `91dfcc300` | fix: S1 ExitPlatform path exclusivity and sticky buffer |

**Coupled test changes:**
- `9b376f472` — "correct S1 top-solid edge landing test expectations to match ROM"

- [ ] **Step 1: Read the commit diffs**

```bash
git show 013ebb6a9
git show 081ead57e
git show 91dfcc300
git show 9b376f472
```

- [ ] **Step 2: Read the s1disasm ground truth**

Find and read:
- `SolidObject` / `Solid_Landed` — solid object landing routine
- `ExitPlatform` — player release from platform
- How `obActWid` (actual width) is used vs collision response halfWidth

```bash
grep -r "SolidObject\|Solid_Landed" docs/s1disasm/ --include="*.asm" -l
grep -r "ExitPlatform" docs/s1disasm/ --include="*.asm" -l
```

- [ ] **Step 3: Read the engine code as-is**

- `src/main/java/com/openggf/level/objects/ObjectManager.java` — search for `SolidContacts`, `Solid_Landed`, `ExitPlatform`, `obActWid`

- [ ] **Step 4: Divergence analysis**

Key questions:
- Commit `013ebb6a9` explicitly says "(147→113 MZ1 errors)" — this is a red flag. Was it made to reduce errors, or does it genuinely match the disasm?
- Does `obActWid` vs `collision halfWidth` actually match what `Solid_Landed` uses in the ROM?
- Does `ExitPlatform` path exclusivity and sticky buffer match S1 disasm?
- The test change `9b376f472` adjusted landing expectations — if the physics change is a hack, this test is now wrong.

- [ ] **Step 5: Cross-game surface check**

`ObjectManager.java` is shared. Check:
```bash
grep -r "SolidObject\|Solid_Landed" docs/s2disasm/ --include="*.asm" -l
grep -r "ExitPlatform" docs/s2disasm/ --include="*.asm" -l
grep -r "SolidObject\|Solid_Landed" docs/skdisasm/ --include="*.asm" -l
```

Note whether S2/S3K use different width values in `Solid_Landed`.

- [ ] **Step 6: Assign verdicts and write dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-solid-object.md`.

---

### Task 6: Mechanism — `out-of-range`

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-out-of-range.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `5499c24bf` | fix: move S1 out-of-range check to during-execution for ROM parity |
| `324fadc63` | fix: add ROM-accurate out_of_range check; fix S1 bubble spawner deletion |
| `b944fddb5` | fix: use 16-bit unsigned wrap for isInRange() to match ROM sub.w + bhi |

**Coupled test changes:** None directly identified, but check `TestAbstractObjectInstanceRange.java`.

- [ ] **Step 1: Read the commit diffs**

```bash
git show 5499c24bf
git show 324fadc63
git show b944fddb5
```

- [ ] **Step 2: Read the s1disasm ground truth**

Find and read:
- `DisplaySprite` / `out_of_range` — the label that checks if objects should be removed
- `MarkObjGone` — the X-range check variant
- `DeleteObject_Respawn` — how respawn flags interact with OOR

```bash
grep -r "out_of_range\|DisplaySprite" docs/s1disasm/ --include="*.asm" -l
grep -r "DeleteObject_Respawn" docs/s1disasm/ --include="*.asm" -l
```

Pay particular attention to the `sub.w` + `bhi` pattern — this is unsigned comparison. The engine commit claims to replicate this with 16-bit unsigned wrap. Verify.

- [ ] **Step 3: Read the engine code as-is**

- `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java` — search for `isInRange`, `outOfRange`, `isOnScreen`
- `src/main/java/com/openggf/game/sonic1/objects/Sonic1BubblesObjectInstance.java`

- [ ] **Step 4: Divergence analysis**

Key questions:
- Does the `isInRange` 16-bit unsigned wrap correctly model `sub.w` + `bhi`?
- Is during-execution vs post-execution OOR timing correct per the disasm?
- Is the bubble spawner deletion a legitimate fix or a workaround?

- [ ] **Step 5: Cross-game surface check**

`AbstractObjectInstance.java` is shared:
```bash
grep -r "out_of_range\|DisplaySprite" docs/s2disasm/ --include="*.asm" -l
grep -r "out_of_range\|DisplaySprite" docs/skdisasm/ --include="*.asm" -l
```

Is the OOR mechanism the same across games? Different? Does the S1-specific timing change affect S2/S3K?

- [ ] **Step 6: Assign verdicts and write dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-out-of-range.md`.

---

### Task 7: Mechanism — `spike-params`

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-spike-params.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `f15726849` | fix: S1 spike Y rewind in Spik_Hurt — 129 → 147 errors, first error 4065 → 4360 |
| `7144e27ae` | fix: ROM-exact sideways spike d3 (d2+1) matching Spik_SideWays addq |

**Coupled test changes:** None.

- [ ] **Step 1: Read the commit diffs**

```bash
git show f15726849
git show 7144e27ae
```

- [ ] **Step 2: Read the s1disasm ground truth**

```bash
grep -r "Spik_Hurt\|Spik_SideWays\|Spik_Wait" docs/s1disasm/ --include="*.asm" -l
```

Read the spike object source. Focus on:
- `Spik_Hurt` — how it handles Y position on hurt
- `Spik_SideWays` — the `addq` instruction that creates `d3 = d2 + 1`

- [ ] **Step 3: Read the engine code as-is**

- `src/main/java/com/openggf/game/sonic1/objects/Sonic1SpikeObjectInstance.java`

- [ ] **Step 4: Divergence analysis**

Note: commit `f15726849` says "129 → 147 errors" — this change **increased** errors! Was it a correction that exposed a different issue downstream, or a revert of a previous fix?

- [ ] **Step 5: Cross-game surface check**

Spike object instance is S1-only. But check if the spike base class or shared collision system was touched:
```bash
grep -r "Spik_Hurt" docs/s2disasm/ --include="*.asm" -l
```

- [ ] **Step 6: Assign verdicts and write dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-spike-params.md`.

---

### Task 8: Mechanism — `player-velocity`

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-player-velocity.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `8d0fd944d` | fix: revert airborne side speed zeroing — ROM zeros speed before air check |
| `7047a99c9` | fix: restore airSuperspeedPreserved and resolve merge conflicts |
| `c5dc4c257` | fix: ROM-accurate air control speed capping and TwistedRamp tumble animation |
| `d19f90a2a` | fix: skip velocity pre-apply when player is object-controlled |

**Coupled test changes:**
- `c1b006cb9` — "S1 air/object desync correctly expects no jump (matches ROM MdJump dispatch)"

- [ ] **Step 1: Read the commit diffs**

```bash
git show 8d0fd944d
git show 7047a99c9
git show c5dc4c257
git show d19f90a2a
git show c1b006cb9
```

- [ ] **Step 2: Read the s1disasm ground truth**

```bash
grep -r "Sonic_Jump\|Sonic_JumpHeight\|Sonic_MoveJump" docs/s1disasm/ --include="*.asm" -l
grep -r "Sonic_Move\b" docs/s1disasm/ --include="*.asm" -l
```

Focus on:
- Air control speed capping — does the ROM cap X speed during air control?
- Airborne side speed zeroing — when does the ROM zero the speed vs when does it check for air?
- MdJump dispatch — what happens when the player is on an object and presses jump?

- [ ] **Step 3: Read the engine code as-is**

- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java` — search for air control, airborne speed
- `src/main/java/com/openggf/level/LevelManager.java` — search for velocity pre-apply, object-controlled

- [ ] **Step 4: Divergence analysis**

Key questions:
- `d19f90a2a` "skip velocity pre-apply when player is object-controlled" — this is in `LevelManager.java` (shared). Does this match how the ROM's main loop orders player update vs object update?
- `c5dc4c257` mentions "ROM-accurate air control speed capping" — verify the cap value and direction against the disasm.
- The "revert" commit `8d0fd944d` suggests a previous change was reverted — was the original change or the revert correct?

- [ ] **Step 5: Cross-game surface check**

`PlayableSpriteMovement.java` and `LevelManager.java` are **shared across all games**.

```bash
grep -r "Sonic_Jump\|Sonic_MoveJump" docs/s2disasm/ --include="*.asm" -l
grep -r "Sonic_Jump\|Sonic_MoveJump" docs/skdisasm/ --include="*.asm" -l
```

Is the air control behavior identical in S2/S3K, or does it differ? Is the velocity pre-apply skip S1-specific?

- [ ] **Step 6: Assign verdicts and write dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-player-velocity.md`.

---

### Task 9: Mechanism — `spawn-system`

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-spawn-system.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `f7c27fc24` | feat: OPL cursor diagnostics and S1 desync recovery fix |
| `f57e3c35c` | feat: add OPL cursor state diagnostics for ROM/engine comparison |
| `0b040b430` | fix: implement dormant spawn tracking for ROM-accurate OOR re-loading |
| `345dab858` | infra: add SpawnCallback for future inline ObjPosLoad creation |

**Coupled test changes:**
- `a7e2f5ba7` — "spring loop test allows spawn window to populate before simulation"

- [ ] **Step 1: Read the commit diffs**

```bash
git show f7c27fc24
git show f57e3c35c
git show 0b040b430
git show 345dab858
git show a7e2f5ba7
```

- [ ] **Step 2: Read the s1disasm ground truth**

```bash
grep -r "ObjPosLoad\|ObjectsLoad" docs/s1disasm/ --include="*.asm" -l
grep -r "RespawnTable\|sst_\|obRespawnNo" docs/s1disasm/ --include="*.asm" -l
```

Focus on:
- ObjPosLoad cursor management — how the ROM tracks which objects are in the spawn window
- Respawn table — how the ROM marks objects as "already destroyed, don't respawn"
- Dormant vs deleted objects — does the ROM have this distinction?

- [ ] **Step 3: Read the engine code as-is**

- `src/main/java/com/openggf/level/objects/ObjectManager.java` — search for `ObjPosLoad`, `cursor`, `spawn`, `dormant`
- `src/main/java/com/openggf/level/objects/ObjectSpawn.java`
- `src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java`

- [ ] **Step 4: Divergence analysis**

Key questions:
- Does the "desync recovery fix" add a workaround, or does it fix a genuine cursor tracking bug?
- Is dormant spawn tracking a real ROM mechanism (cite the disasm) or an engine invention?
- Is `SpawnCallback` needed, or is it scaffolding for a feature that was never completed?

- [ ] **Step 5: Cross-game surface check**

`ObjectManager.java`, `ObjectSpawn.java`, `AbstractPlacementManager.java` are all shared.

```bash
grep -r "ObjPosLoad\|ObjectsLoad" docs/s2disasm/ --include="*.asm" -l
grep -r "ObjPosLoad\|ObjectsLoad" docs/skdisasm/ --include="*.asm" -l
```

- [ ] **Step 6: Assign verdicts and write dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-spawn-system.md`.

---

### Task 10: Mechanism — `ring-refactor`

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-ring-refactor.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `7ea3fba9f` | fix: add missing Sonic1PhantomRingInstance.java |
| `032255f92` | feat: add Sonic1RingInstance — individual ring object replacing phantom |
| `d07708127` | feat: Sonic1RingPlacement returns ObjectSpawn→RingSpawn mapping |
| `cef836cbb` | feat: wire Sonic1RingInstance into registry and level loading |
| `bf124cb1a` | refactor: remove phantom ring infrastructure from ObjectManager |
| `babec749b` | chore: clean up stale phantom ring references in Javadoc and method names |
| `52f3ce43b` | fix: restore ROM-parity child slot pre-allocation for S1 ring objects |
| `c5beba3c7` | fix: gate lightning shield ring attraction behind elementalShieldsEnabled |
| `7d9bc0b88` | feat: add ringSparkleDelay to PhysicsFeatureSet for per-game sparkle timing |
| `b5307319c` | fix: S3K sparkle delay corrected to 5 VBlanks, S2 made explicit |
| `9d4a53cf8` | feat: add ringCollisionWidth, ringCollisionHeight, lightningShieldEnabled to PhysicsFeatureSet |

**Coupled test changes:**
- `3aa2386b7` — "S3K SS entry ring test calls update before checking destroyed state"
- `d0ff4ec30` — "clean up dead config, hardcoded countdown, imports, and add lifecycle tests"

- [ ] **Step 1: Read the commit diffs**

Read all 11 commits + 2 test commits. This is a large group.

- [ ] **Step 2: Read the s1disasm ground truth**

```bash
grep -r "Obj25\|CollectRing\|_Rings" docs/s1disasm/ --include="*.asm" -l
```

Focus on how S1 rings work vs S2 rings — S1 uses individual ring objects (`Obj25`) while S2 uses a batch ring system. This refactor may be implementing the correct S1 model.

- [ ] **Step 3: Read the engine code as-is**

- `src/main/java/com/openggf/level/rings/RingManager.java`
- `src/main/java/com/openggf/game/PhysicsFeatureSet.java` — ring-related fields
- `src/main/java/com/openggf/level/objects/ObjectManager.java` — phantom ring removal

- [ ] **Step 4: Divergence analysis**

This mechanism is likely **mostly legitimate** — S1 genuinely uses per-object rings. The audit should focus on:
- Is the `PhysicsFeatureSet` gating correct (ring collision width, sparkle delay)?
- Is the child slot pre-allocation change correct per the disasm?
- Was the phantom ring removal premature (are there other consumers)?

- [ ] **Step 5: Cross-game surface check**

`PhysicsFeatureSet.java` and `RingManager.java` are shared. The ring physics features should be correctly gated per game. `ObjectManager.java` phantom ring removal must not break S2/S3K.

- [ ] **Step 6: Assign verdicts and write dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-ring-refactor.md`.

---

### Task 11: Mechanism — `object-lifecycle`

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-object-lifecycle.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `d4c33442f` | fix: defer LavaGeyser init to first update; add pre-registration call guard |
| `d5c471ef9` | fix: staircase contact detection uses boolean flags instead of frame counters |
| `aa2c026c5` | fix: replace frame-counter contact detection with boolean flags in 5 S2 objects |
| `a840cbe05` | fix: prevent lavafall third piece from cascade-spawning infinite children |
| `cbe4fa944` | fix: re-apply lavafall third piece cascade-spawn prevention |

**Coupled test changes:**
- `e5c053b50` — "staircase test tracks peak descent instead of final position"
- `ef152cfd5` — "rising lava route split test setup corrected"

- [ ] **Step 1: Read the commit diffs**

```bash
git show d4c33442f
git show d5c471ef9
git show aa2c026c5
git show a840cbe05
git show cbe4fa944
git show e5c053b50
git show ef152cfd5
```

- [ ] **Step 2: Read the s1disasm ground truth**

```bash
grep -r "LavaGeyser\|obj_LavaGeyser\|Obj2F" docs/s1disasm/ --include="*.asm" -l
grep -r "Obj36\|obj_Staircase\|_ChainedStomper" docs/s1disasm/ --include="*.asm" -l
grep -r "Obj30\|Lavafall\|Fireball" docs/s1disasm/ --include="*.asm" -l
```

Focus on contact detection mechanisms — does the ROM use frame counters or boolean flags?

- [ ] **Step 3: Read the engine code as-is**

- Sonic1LavaGeyserObjectInstance.java, Sonic1LavaGeyserMakerObjectInstance.java
- Sonic1ChainedStomperObjectInstance.java
- Sonic1LavaBallObjectInstance.java (lavafall)

- [ ] **Step 4: Divergence analysis**

Key questions:
- Is the boolean-vs-frame-counter change S1-accurate, or was it done to prevent timing glitches in the trace?
- Does the lavafall cascade-spawn prevention match the ROM, or is it masking a deeper spawning bug?
- The S2 objects change (`aa2c026c5`) — were these S2 objects also using wrong contact detection?

- [ ] **Step 5: Cross-game surface check**

`aa2c026c5` explicitly touches S2 objects — verify against s2disasm:
```bash
grep -r "contact\|standing\|obSubtype" docs/s2disasm/ --include="*.asm" -l | head -10
```

- [ ] **Step 6: Assign verdicts and write dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-object-lifecycle.md`.

---

### Task 12: Mechanism — `animation`

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-animation.md`

**Commits to audit:**
| Commit | Subject |
|--------|---------|
| `accf5d9a3` | fix: apply animation fallback chain in resolveAnimationId() to fix S1 bubble breathing |

**Coupled test changes:** None.

- [ ] **Step 1: Read the commit diff**

```bash
git show accf5d9a3
```

- [ ] **Step 2: Read the s1disasm ground truth**

```bash
grep -r "Bubble\|breathing\|AniObj" docs/s1disasm/ --include="*.asm" -l
```

Find the S1 animation script system and how animation IDs are resolved for the bubble/breathing animation.

- [ ] **Step 3: Read the engine code as-is**

- `src/main/java/com/openggf/sprites/ScriptedVelocityAnimationProfile.java` — search for `resolveAnimationId`, `fallback`

- [ ] **Step 4: Divergence analysis**

Is the "fallback chain" a real ROM mechanism, or an engine-side workaround for a missing animation? If the ROM simply doesn't reference that animation ID, the fallback may be papering over a wrong ID rather than fixing the system.

- [ ] **Step 5: Cross-game surface check**

`ScriptedVelocityAnimationProfile.java` is shared. Does S2/S3K also use animation fallback chains? Check if this change affects non-S1 animation resolution.

- [ ] **Step 6: Assign verdicts and write dossier**

Write `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-animation.md`.

---

### Task 13: Phase 3 — Synthesis

**Depends on:** All Task 3-12 dossiers completed.

**Files:**
- Create: `docs/architecture/designs/2026-04-05-mz1-audit-sweep/03-synthesis.md`

- [ ] **Step 1: Read all dossiers**

Read all 10 mechanism dossier files from `docs/architecture/designs/2026-04-05-mz1-audit-sweep/02-mechanism-*.md`.

Collect:
- Total commits audited
- Verdict counts: how many KEEP, REWRITE, REVERT, INVESTIGATE
- Cross-game hazards: which mechanisms touched shared code without S2/S3K verification

- [ ] **Step 2: Build the remediation queue**

Order by safety:
1. **Clear reverts** — REVERT verdicts where the code is S1-only and the change has no disasm basis. Safest to remove.
2. **Clear rewrites** — REWRITE verdicts where the fix is specified in the dossier. Need careful application.
3. **Cross-game hazards** — shared code changes that need either S2/S3K verification or feature gating before remediation.
4. **Test-expectation reverts** — `fix(test):` commits whose coupled physics change was REVERT. These tests need to be restored to pre-MZ1 expectations.
5. **Investigate bucket** — commits that can't be judged without stable-retro re-recording. Deferred to next phase.

- [ ] **Step 3: Identify mechanism cross-references**

Map which mechanisms interact (share files, share data flow):
- platform-carry ↔ solid-object (both in SolidContacts)
- touch-response ↔ platform-carry (`fdc82aecb` touches both)
- out-of-range ↔ spawn-system (both affect object lifecycle)
- ring-refactor ↔ object-lifecycle (both modify ObjectManager)

Note: if one mechanism's REVERT would break another mechanism's KEEP, flag this as a dependency.

- [ ] **Step 4: List open questions for user decision**

Collect any items where:
- The verdict was INVESTIGATE
- Two dossiers give conflicting recommendations
- A REVERT would break a currently-passing test

- [ ] **Step 5: Write 03-synthesis.md**

Structure:
```markdown
# MZ1 Audit Sweep — Synthesis

## Executive summary
- N commits audited across M mechanisms
- Verdicts: X KEEP, Y REWRITE, Z REVERT, W INVESTIGATE
- Key finding: ...

## Remediation queue
### 1. Clear reverts
...

### 2. Clear rewrites
...

### 3. Cross-game hazards
...

### 4. Test-expectation reverts
...

### 5. Investigate (deferred)
...

## Mechanism cross-references
...

## Open questions
...
```

- [ ] **Step 6: Commit all audit output**

```bash
git add docs/architecture/designs/2026-04-05-mz1-audit-sweep/
git commit -m "docs: MZ1 audit sweep — all dossiers and synthesis"
```

- [ ] **Step 7: Post in-chat summary**

Post a concise summary to the user: top-line findings, recommended remediation order, items needing their decision.
