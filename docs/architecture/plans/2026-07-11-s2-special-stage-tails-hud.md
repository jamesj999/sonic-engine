# Sonic 2 Special Stage Tails and HUD Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render Sonic 2 special-stage Tails and the Sonic/Tails/total team HUD exactly from the original game's Obj10, Obj88, and Obj5E data.

**Architecture:** Add ROM-derived Tails body and appendage mapping/DPLC data beside the existing Obj09 mapping data, then make the renderer select character-specific mappings and source patterns. Replace the single-counter HUD entry point with a mode-aware composition that consumes the existing per-player ring state.

**Tech Stack:** Java 21, JUnit 5, Maven, OpenGL rendering abstractions, Sonic 2 disassembly reference data.

---

### Task 1: Lock down Tails body and appendage art selection

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSpriteMappings.java`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageTailsSpriteMappingsTest.java`

- [ ] **Step 1: Write failing mapping tests**

Add tests that request Tails body mapping frames 0 and 12 and Obj88 appendage frames 0 and 14. Assert their piece counts and representative offsets/tile indices against `mappings/sprite/obj10.asm` and `mappings/sprite/obj88.asm`. Add assertions that the body source groups are `$183/$1C0/$264/$29E` and appendage groups are `$2AE/$2E3/$31E`. For representative nonzero destination tile indices on both sides of a reverse-DPLC run boundary, assert the resolved source pattern from the exact `Obj09_MapRUnc_345FA` frame record.

- [ ] **Step 2: Verify the tests fail for missing Tails APIs**

Run: `mvn "-Dtest=Sonic2SpecialStageTailsSpriteMappingsTest" test`

Expected: FAIL because `getTailsFrame`, `getTailsTailsFrame`, and ROM source-group accessors do not exist.

- [ ] **Step 3: Add the minimal ROM-derived mapping data**

Add immutable `TAILS_FRAMES` and `TAILS_TAILS_FRAMES` tables and bounded accessors. Add named source-group constants. Encode the reverse-DPLC records used by Obj10 and Obj88 and expose a frame-specific destination-to-source resolver: Obj10 indexes record `mappingFrame + $12`; Obj88 indexes `mappingFrame + $24`. Decode each record's destination length/source start runs exactly like `LoadSSPlayerDynPLC`; reject an unmapped destination slot. Preserve signed offsets, VDP piece sizes, tile offsets, and flips verbatim from Obj10/Obj88.

- [ ] **Step 4: Verify the mapping tests pass**

Run: `mvn "-Dtest=Sonic2SpecialStageTailsSpriteMappingsTest" test`

Expected: PASS.

### Task 2: Reproduce Tails rendering through character-specific art

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageConstants.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePlayer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Modify: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRendererDeterminismTest.java`
- Modify: `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStagePlayerSnapshot.java`

- [ ] **Step 1: Write failing renderer recording tests**

Use the existing recording graphics manager to render representative upright and horizontal Tails frames. Assert the emitted pattern IDs resolve from the Tails source groups, use palette 2, and never equal the corresponding Sonic source IDs. With overlapping Sonic and Tails, assert Obj88 is a separate render entry at Tails body priority minus one rather than an unconditional nested body draw.

- [ ] **Step 2: Verify the renderer tests fail for the current Sonic-mapping shortcut**

Run: `mvn "-Dtest=Sonic2SpecialStageRendererDeterminismTest" test`

Expected: FAIL because Tails currently calls `getSonicFrame` and adds `TAILS_PATTERN_OFFSET`.

- [ ] **Step 3: Implement character-specific body rendering**

Remove `TAILS_PATTERN_OFFSET`. Select Obj09 or Obj10 mappings from `PlayerType`; for Tails, translate each mapping destination index through that frame's reverse-DPLC runs before adding the orientation source-group base. Retain mapping/player flip XOR behavior and column-major VDP traversal.

- [ ] **Step 4: Add independent Obj88 animation and rewind state**

Add Tails-only appendage animation frame, duration, and previous-animation fields to `Sonic2SpecialStagePlayer`. When parent animation is 0, 1, or 2, run the corresponding seven-frame `Ani_obj88` script (`0..6`, `7..$D`, `$E..$14`) with duration 3 and loop behavior; reset on parent animation changes. For parent animation 3 or later, suppress update/render as Obj88 does. Extend `PlayerSnapshot`, capture, restore, equality-oriented tests, and reset/init paths with these fields.

- [ ] **Step 5: Implement Obj88 as a priority-sorted render entry**

Resolve the appendage's Obj88 mapping and `$2AE/$2E3/$31E` source group, inherit body position/status flips and palette 2, and add it to the same ordered render-entry sequence as player bodies at `tailsPriority - 1`. This preserves ordering if Sonic's body priority falls between the appendage and Tails body.

- [ ] **Step 6: Verify focused renderer and snapshot tests pass**

Run: `mvn "-Dtest=Sonic2SpecialStageTailsSpriteMappingsTest,Sonic2SpecialStageRendererDeterminismTest,TestSonic2SpecialStagePlayerSnapshot" test`

Expected: PASS.

### Task 3: Reproduce the Obj5E team ring HUD

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageHudLayoutTest.java`

- [ ] **Step 1: Write failing HUD layout tests**

Record HUD draw calls for Sonic-only `(sonic=12)` and assert `SSHUD_Sonic` at object X `$D4`. Record Tails-only `(tails=4)` and assert overseas `SSHUD_Tails` frame 2 at object X `$38`, with its digit at X `$9C`, Y `$20`. For team `(sonic=12, tails=4, total=16)`, assert overseas frames 0/2/3 of `SSHUD_SonicTailsTotal` at object X `$80`; Sonic digits start at X `$48`, Tails at `$E0`, and total `16` at `$7C/$84`, all Y `$20`. Add 1-digit and 3-digit totals to lock `$80` and `$78/$80/$88`, and assert leading hundreds/tens are suppressed like Obj87.

- [ ] **Step 2: Verify the HUD test fails**

Run: `mvn "-Dtest=Sonic2SpecialStageHudLayoutTest" test`

Expected: FAIL because the renderer accepts only a combined count and always draws `SSHUD_Sonic`.

- [ ] **Step 3: Implement mode-aware HUD composition**

Introduce a renderer input carrying the active character composition, Sonic count when present, Tails count when present, and combined total. Encode the Obj5E frame/layout anchors from `SSHUDLayout` and `mappings/sprite/obj5E.asm`, including Sonic-only, overseas Tails-only, and overseas team frame 2 (`TAILS`) for the required World ROM. Encode Obj87 digit placement and leading-zero suppression directly from `Obj87_Init`, `loc_7480`, and `loc_753E`. Apply the established H32 viewport offset once and delete the current `-6` approximation. Update the manager to identify SONIC and TAILS players whether main or sidekick and pass their counts without changing ring ownership.

- [ ] **Step 4: Verify HUD and per-player ring tests pass**

Run: `mvn "-Dtest=Sonic2SpecialStageHudLayoutTest,Sonic2SpecialStagePerPlayerRingsTest" test`

Expected: PASS.

### Task 4: Regression verification and documentation

**Files:**
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Document the user-visible fixes**

Add a concise changelog entry covering correct Sonic 2 special-stage Tails art and the original team ring HUD.

- [ ] **Step 2: Run the complete Sonic 2 special-stage test package**

Run: `mvn "-Dtest=com.openggf.game.sonic2.specialstage.*" test`

Expected: PASS with zero failures and errors.

- [ ] **Step 3: Run the project test suite**

Run: `mvn test`

Expected: PASS with zero failures and errors.

- [ ] **Step 4: Inspect the final diff and policy-sensitive files**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors; only task files plus pre-existing unrelated user changes are present.
