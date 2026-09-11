# S1/S2 Emerald Save Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert donated Sonic 1 and Sonic 2 saves to use `chaosEmeralds` as the only emerald field, restore that data into gameplay, and render host-game emerald icons on the donated S3K save card using host-game palette colors.

**Architecture:** Use `chaosEmeralds` as the single cross-game emerald payload for S1/S2. Keep the shared runtime restore path list-based, push emerald identity into donated slot presentation, and extend the donated S3K asset source with host-specific emerald art and palette support while leaving native S3K behavior untouched.

**Tech Stack:** Java 21, Maven, JUnit 5, Mockito, existing donated Data Select presentation/render pipeline

---

### Task 1: Migrate S1/S2 Save Payloads To `chaosEmeralds`

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/dataselect/S1SaveSnapshotProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/dataselect/S2SaveSnapshotProvider.java`
- Modify: `src/test/java/com/openggf/game/save/TestDonatedSaveSession.java`

- [ ] Write failing tests asserting S1 and S2 snapshot payloads expose `chaosEmeralds` and do not expose `emeraldCount`.
- [ ] Run: `mvn -Dmse=off "-Dtest=TestDonatedSaveSession" test`
Expected: failures on old `emeraldCount` assertions or missing `chaosEmeralds`.
- [ ] Implement minimal payload change in both snapshot providers:
  - replace runtime `getEmeraldCount()` save capture with `getCollectedChaosEmeraldIndices()`
  - remove `payload.put("emeraldCount", ...)`
- [ ] Update donated save session tests to assert `chaosEmeralds`.
- [ ] Re-run: `mvn -Dmse=off "-Dtest=TestDonatedSaveSession" test`
Expected: PASS

### Task 2: Restore S1/S2 Emerald Progress From `chaosEmeralds`

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`
- Modify: `src/test/java/com/openggf/TestEngine.java`

- [ ] Write failing tests for `Engine.restoreRuntimeFromDataSelectPayload(...)` using S1/S2-style payloads with `chaosEmeralds`.
- [ ] Run: `mvn -Dmse=off "-Dtest=TestEngine" test`
Expected: failures showing emerald progress is not restored as expected by the new tests.
- [ ] Implement minimal restore-path change, keeping the shared list-based model and removing any need for `emeraldCount`.
- [ ] Re-run: `mvn -Dmse=off "-Dtest=TestEngine" test`
Expected: PASS

### Task 3: Remove `emeraldCount` Dependencies From Donated Host Profiles

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/dataselect/S1DataSelectProfile.java`
- Modify: `src/main/java/com/openggf/game/sonic2/dataselect/S2DataSelectProfile.java`
- Modify: `src/main/java/com/openggf/game/dataselect/SimpleDataSelectManager.java`
- Modify: `src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java`

- [ ] Write failing tests covering S1/S2 payload validity and summary behavior without `emeraldCount`.
- [ ] Run: `mvn -Dmse=off "-Dtest=TestS1DataSelectProfile,TestS2DataSelectProfile" test`
Expected: failures from payloads still keyed on `emeraldCount`.
- [ ] Update validation/summary/profile code to rely on `chaosEmeralds` and remove S1/S2 `E<n>` assumptions.
- [ ] Re-run: `mvn -Dmse=off "-Dtest=TestS1DataSelectProfile,TestS2DataSelectProfile" test`
Expected: PASS

### Task 4: Add Donated S1/S2 Emerald Sprite Rendering With Host Palettes

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectPresentation.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/dataselect/S3kDataSelectRenderer.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java`

- [ ] Write failing presentation/render tests for donated S1 and S2 slots that supply `chaosEmeralds` and expect individual emerald frames plus host-palette rendering metadata.
- [ ] Run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation" test`
Expected: failures because donated S1/S2 emeralds do not populate the sprite layer or use host palettes.
- [ ] Implement minimal donated asset/presentation changes:
  - resolve host emerald mapping frames from `chaosEmeralds`
  - source S1/S2 emerald art and palette bytes from the donor ROM
  - keep native S3K emerald path unchanged
- [ ] Re-run: `mvn -Dmse=off "-Dtest=TestS3kDataSelectPresentation" test`
Expected: PASS

### Task 5: Full Targeted Verification

**Files:**
- Verify only

- [ ] Run: `mvn -Dmse=off "-Dtest=TestDonatedSaveSession,TestEngine,TestS1DataSelectProfile,TestS2DataSelectProfile,TestS3kDataSelectPresentation,TestGameLoop,TestTornadoObjectInstance" test`
Expected: PASS
- [ ] Review for any remaining `emeraldCount` usage in S1/S2 donated-save code paths with:
`rg -n "emeraldCount" src/main/java/com/openggf/game/sonic1 src/main/java/com/openggf/game/sonic2 src/main/java/com/openggf/game/dataselect src/main/java/com/openggf/game/sonic3k/dataselect src/test/java/com/openggf`
Expected: no remaining S1/S2 donated-save dependencies on `emeraldCount`; unrelated gameplay-only uses may remain outside save code.
