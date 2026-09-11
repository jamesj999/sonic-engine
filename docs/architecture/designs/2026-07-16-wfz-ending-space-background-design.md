# WFZ Ending Sky→Space Background Transition — Design Spec

> **Status:** Original background bridge implemented. Visual-parity follow-up corrected and
> approved 2026-07-16; Section 7 supersedes the failed 8192-pixel Plane-B workaround and adds
> the missing Tornado rocket-booster correction.
> **For agentic workers:** once approved, use superpowers:subagent-driven-development or
> superpowers:executing-plans to implement the tasks. Steps use checkbox (`- [ ]`) syntax.

**Goal:** As Sonic rides Robotnik's getaway ship up at the end of Wing Fortress Zone,
the background should scroll gradually from the WFZ sky up into space, exactly as the ROM
does — a vertical background scroll driven by `Camera_BG_Y_offset`, not a palette fade.

**Non-goals:** No change to the DEZ starfield credits (`SwScrl_DEZ`); no change to the
WFZ→DEZ hard cut timing; no new render subsystem. Sections 1-6 describe the original
wiring + one-method-rewrite bridge; the approved Section 7 follow-up extends the existing
tilemap and runtime-object-art paths without adding a parallel renderer.

---

## 1. Background: how the ROM drives the transition

Confirmed from `docs/s2disasm/s2.asm` (see the WFZ-ending background investigation). The
sky→space reveal is a **vertical background scroll**, not a palette effect:

| Step | ROM routine | s2.asm | Effect |
|------|-------------|--------|--------|
| Trigger | `ObjB2_Jump_to_ship` sets `Dynamic_Resize_Routine = 6` | 79090 | selects `LevEvents_WFZ_Routine4` |
| Offset ramp | `LevEvents_WFZ_Routine4` | 20657-20678 | ramps `Camera_BG_Y_offset` up toward `#$1B81` and `Camera_BG_X_offset` down toward `-$2C0`, then `bra ScrollBG` |
| Diff calc | `ScrollBG` | 20885-20920 | `Camera_BG_{X,Y}_pos_diff = clamp(cam − BG_pos − BG_offset, ±16)` |
| Advance + reveal | `SetVertiScrollFlagsBG` | 18400-18429 | `Camera_BG_Y_pos += diff`; flags newly-revealed BG rows for tile upload |
| Display | `SwScrl_WFZ` | 15645 | `move.w Camera_BG_Y_pos, Vscroll_Factor_BG` |
| Hard cut | `ObjB2_Start_DEZ` | 79162-79169 | at `objoff_2A ≥ $9C0`: `Current_ZoneAndAct = DEZ`, `Level_Inactive_flag = 1` |

Net effect: as `Camera_BG_Y_offset` climbs, the `ScrollBG` Y-diff pins to `−16`, so
`Camera_BG_Y_pos` decreases ~16 px/frame, scrolling the WFZ background plane **up** to
reveal the space/star region at the top of that plane. It converges toward
`cameraY − Camera_BG_Y_offset`, chased at ≤16 px/frame.

**Not a palette effect:** `PalCycle_WFZ` (s2.asm:2998-3038) only cycles the fire/conveyor
colours on palette line 2; there is no sky→space palette script.

---

## 2. Root cause in the engine (two connected gaps)

The offset ramp is **already implemented correctly**:
`Sonic2WFZEvents.primaryRoutine6_reverse()` (`Sonic2WFZEvents.java:323-349`) ramps
`bgYOffset → $1B81` and `bgXOffset → −$2C0`, mirroring `LevEvents_WFZ_Routine4`. The state
just dead-ends inside `Sonic2WFZEvents`:

**Gap A — `syncBgDiffs()` does not implement `ScrollBG`.**
`Sonic2WFZEvents.syncBgDiffs()` (`Sonic2WFZEvents.java:366-371`) currently snaps the BG
position straight to the camera and ignores `bgXOffset`/`bgYOffset` and the ±16 clamp:
```java
bgXPosDiff = camera().getX() - bgXPos;      // ignores bgXOffset, no clamp
bgYPosDiff = camera().getY() - bgYPos;      // ignores bgYOffset, no clamp
bgXPos = camera().getX();                    // snaps, never scrolls up
bgYPos = camera().getY();
```
So the rising offset has zero effect on `bgYPos`.

**Gap B — the WFZ BG position never reaches the scroll handler.**
`SwScrlWfz.update()` reads a **separate** `BackgroundCamera` instance
(`SwScrlWfz.java:97` → `composer.setVscrollFactorBG((short) bgCamera.getBgYPos())`), owned
by `Sonic2ScrollHandlerProvider`. That `bgCamera`'s WFZ Y is seeded once at level init and
then frozen — `BackgroundCamera.setBgYPos()` has no production caller, and the WFZ branch of
`updateFromForeground()` is a no-op (only MTZ updates the BG camera per-frame). So even a
correct `Sonic2WFZEvents.bgYPos` is never consumed.

---

## 3. Phase 0 gate — RESOLVED 2026-07-16: PASS

**Question:** does the loaded WFZ background plane have room for the revealed "space" region,
or is it only a short sky band (which would make this a BG-layout task)?

**Findings** (from a throwaway `SharedLevel.load(SONIC_2, 9, 0)` probe, since removed):
- The WFZ background is **layer 1 of a 128×16-block, 2-layer map — 2048px (`$800`) tall**,
  exactly matching the ROM's `Camera_BG_Y_pos & $7FF` segment structure. It is **not** a
  256px sky band, and `computeBuildParams` builds the **full** BG height for WFZ
  (`bgLoopBand` is false; `LevelTilemapManager.java:605`).
- Sampling the BG plane (layer 1) top-to-bottom: **rows 0–3 (the top ~512px, the region the
  ending scroll reveals) are blank (block 0)**; rows 4–15 are the repeating sky/cloud
  background (a 4-block period of indices `$25/$00/$50/$1F`).

**Conclusion:** the "space" is the **VDP backdrop revealed above the sky** as the plane
scrolls up — the blank top rows show the backdrop colour. It is **not** a star-tile region in
the WFZ BG layout (the actual starfield is the separate DEZ intro, `SwScrl_DEZ`, after the
hard cut). So this is the **wiring task** below (Tasks 1–3), **not** a BG-layout/tile-load
task. Gate **passes**.

**Follow-up correction (2026-07-16):** headless capture plus an authoritative reference frame
show that the revealed transparent rows must resolve to black during the escape phase. The
original implementation correctly advances the horizon geometry, but leaves the normal WFZ
blue backdrop active. Section 7 defines the state-driven correction.

---

## 4. Design decisions

### 4.1 `ScrollBG` math + seeding (Gap A)
Rewrite `syncBgDiffs()` to the ROM `ScrollBG` chase, incorporating the offsets and the ±16
clamp, advancing a persistent `bgXPos`/`bgYPos`:
```java
int dx = clamp16(camera().getX() - bgXPos - bgXOffset);
int dy = clamp16(camera().getY() - bgYPos - bgYOffset);
bgXPosDiff = dx; bgYPosDiff = dy;
bgXPos += dx;    bgYPos += dy;
```
**Seeding — no new flag needed.** `bgXPos`/`bgYPos` are already seeded to the camera by the
existing `primaryRoutine0_initBgSync()` (`Sonic2WFZEvents.java:243-247`: `bgXPos = camera().getX()`,
`bgYPos = camera().getY()`), which runs before routine 2/4/6 ever calls `syncBgDiffs()`. So
the `ScrollBG` chase starts from the seeded position; there is **no `bgSeeded` flag** and
**no rewind-snapshot change** (`SNAPSHOT_BYTES` stays `Integer.BYTES * 8`; `bgXPos`/`bgYPos`/
`bgYOffset`/`bgXOffset`/`bgXPosDiff`/`bgYPosDiff` are already in those 8 ints). *(An earlier
draft added a `bgSeeded` flag — dropped: it was redundant with routine 0 and would have both
discarded camera movement between init and the first routine-2 frame and overflowed the
fixed 8-int snapshot buffer.)*

**Trace safety:** during normal play `bgXOffset = bgYOffset = 0` and the S2 camera moves
≤16 px/frame, so `dy = clamp16(cameraY − bgYPos)` keeps `bgYPos == cameraY` — identical to
the current snap. The clamp/offset only diverge during the ending ramp. This is why the
rewrite must NOT regress the WFZ or DEZ-ending traces (see verification).

### 4.2 The event→scroll bridge (Gap B) — a live `WfzRuntimeStateView` (mirrors HTZ)

`SwScrlWfz` must consume the event-owned BG position without any cross-lifetime reference.
An earlier draft proposed injecting the session-owned `BackgroundCamera` into the
module-owned `Sonic2LevelEventManager` — **rejected** on review: (a) the event manager
survives gameplay-context rebuilds while each `Sonic2ScrollHandlerProvider` creates a *new*
session-owned `BackgroundCamera`, so a retained reference goes stale after an editor/session
rebuild; (b) exposing `BackgroundCamera` through `ParallaxManager` violates that manager's
game-agnostic boundary; (c) the duplicated BG position leaves the rendered VScroll stale for
one frame after a rewind restore (the post-restore parallax recompute runs before the next
event frame re-pushes).

**Use the existing zone-runtime-state pattern instead.** `SwScrlHtz` already consumes a
**live** `HtzRuntimeStateView` (over `Sonic2HTZEvents`) via
`GameServices.zoneRuntimeRegistry().currentAs(HtzRuntimeState.class)`
(`HtzRuntimeStateView.java`, `SwScrlHtz.java:61-63`; registered at
`Sonic2LevelEventManager.java:132`). Mirror it exactly:

- **`WfzRuntimeState`** (interface, `com.openggf.game.sonic2.runtime`, extends
  `ZoneRuntimeState`): **both** axes of the ROM `ScrollBG` — `int bgVscrollFactor();`
  (= `Camera_BG_Y_pos`) **and** `int bgXPos();` (= `Camera_BG_X_pos`). Both are required:
  `LevEvents_WFZ_Routine4` ramps `Camera_BG_X_offset` to `-$2C0` as well as the Y offset, and
  `SwScrlWfz` drives the static-BG and getaway-ship layers from the X position (line 102 →
  layers 0/1 at `:118-119`).
- **`WfzRuntimeStateView implements WfzRuntimeState`**: a **live** view holding a
  `Sonic2WFZEvents` reference — `bgVscrollFactor() { return events.getBgYPos(); }`,
  `bgXPos() { return events.getBgXPos(); }`. No copied state.
- **Register** it in `Sonic2LevelEventManager` alongside HTZ/CNZ
  (`installOwnedRuntimeState(registry, new WfzRuntimeStateView(zone, act, wfzEvents))`,
  next to `:132`), so it is (re)installed per session with the current event manager — no
  durable→session retention.
- **`SwScrlWfz`** reads `GameServices.zoneRuntimeRegistry().currentAs(WfzRuntimeState.class)`
  instead of `bgCamera` for **all three** BG-position reads: the VScroll factor
  (`SwScrlWfz.java:97`), the `bgXPosLong` static/ship horizontal scroll (`:102`), and the
  segment-select `bgY` (`:141`). (Bridging only the Y reads would leave the horizontal half
  of `ScrollBG` frozen on `bgCamera`.)

**Why this fixes the three P1 issues:** no session-owned object is retained in the durable
event manager and no S2 type leaks through `ParallaxManager` (P1#1); the view reads
`Sonic2WFZEvents` **live**, so after a rewind restore of the event's `bgYPos` (already in the
snapshot) the very next parallax recompute reads the restored value — no duplicate state, no
stale frame (P1#2); and there is no new snapshot field (P1#3, see §4.1).

**Ordering (verified):** `LevelFrameStep` runs `levelEvents.update()` (`LevelFrameStep.java:248`)
before the parallax/scroll pass, so the event's `bgYPos` for the frame is set before
`SwScrlWfz` reads the view. (Live read means even out-of-order restore is correct.)

### 4.3 What does NOT change
- The offset ramp (`primaryRoutine6_reverse`) — already correct.
- The DEZ starfield (`SwScrl_DEZ`) and the ending DEZ hard cut.
- Chunks/blocks/collision and the (now-fixed) pattern supplement.

---

## 5. Verification strategy

1. **Phase 0 spike** decides go/no-go on the star tiles (Section 3).
2. **Unit test** for the `ScrollBG` math: given camera/offset/BG-pos inputs, assert the
   clamped diff and advanced `bgXPos`/`bgYPos` (drives `bgYPos` toward `cameraY − bgYOffset`
   at ≤16/frame; equals `cameraY` when offset 0).
3. **Bridge integration test (through the real context, not a direct setter):** boot a WFZ
   gameplay context, drive the WFZ event to non-zero `bgYOffset` **and** `bgXOffset`, run one
   `LevelFrameStep`/`ParallaxManager` pass, and assert **both** rendered axes: the VScroll
   factor equals `Sonic2WFZEvents.getBgYPos()` and the static/ship HScroll word equals
   `Sonic2WFZEvents.getBgXPos()` (what `SwScrlWfz` wrote). This exercises the actual
   `ZoneRuntimeRegistry` registration + `SwScrlWfz` read on both axes — a direct
   `WfzRuntimeStateView` unit test alone would pass even if the level-load registration were
   missing, and a Y-only assertion would miss the frozen horizontal half of `ScrollBG`.
4. **Trace regression gate (release-blocking):** `TestS2WfzLevelSelectTraceReplay` and
   `TestS2DezEndingLevelSelectTraceReplay` must stay green
   (`mvn -Ptrace-replay "-Dtest=…" test`). This is the primary guard that normal-play BG
   scroll is unchanged and the ending path doesn't desync.
5. **Rewind:** the view is live over `Sonic2WFZEvents`, and `bgXPos`/`bgYPos`/`bgYOffset` are
   already in the `Sonic2WFZEvents` snapshot (`SNAPSHOT_BYTES`, unchanged). A WFZ-ending
   rewind round-trip must restore those and then render the restored VScroll on the very
   next parallax pass — assert the rendered VScroll after restore (guards P1#2). No new
   snapshot field, and no separate `BackgroundCamera` state to reconcile.
6. **Visual (manual):** ride the ship at the WFZ ending via `dev.cmd`; the sky should
   scroll up into space. Headless can't confirm this — the trace gate + unit tests cover
   correctness; the visual is the final human check.

---

## 6. Global constraints (from repo policy)
- ROM-faithful: model real ROM state (`Camera_BG_Y_offset`/`ScrollBG`); no zone/frame carve-outs.
- Must not regress `TestS2WfzLevelSelectTraceReplay` or `TestS2DezEndingLevelSelectTraceReplay`.
- Non-`master` commits carry the trailer block; `fix`/`feat` touching `src/main` set
  `Changelog: updated` (+ stage `CHANGELOG.md`).
- No `git stash` (repo-wide across worktrees); base the branch on `develop`.

---

## Implementation Plan (TDD, bite-sized)

### Task 0: Phase 0 spike — confirm the WFZ BG plane supports the reveal — DONE (PASS)

Resolved 2026-07-16 (see Section 3). The WFZ background is a 2048px-tall plane whose blank
top rows are revealed as the sky scrolls up (backdrop = "space"); the engine builds the full
height. This is a wiring task, not a BG-layout task. Proceed to Task 1.

- [x] Probe WFZ BG plane dimensions + top-row content via `SharedLevel.load(SONIC_2, 9, 0)`.
- [x] Record finding + gate decision (PASS) in Section 3.

---

### Task 1: Implement the ROM `ScrollBG` chase in `syncBgDiffs()`

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/events/Sonic2WFZEvents.java` (`syncBgDiffs`; add public `getBgXPos()`/`getBgYPos()` accessors for the view in Task 2). No `bgSeeded`, no `init`/snapshot change (routine 0 seeds; §4.1).
- Test: `src/test/java/com/openggf/game/sonic2/TestSonic2WfzBgScroll.java` (new)

**Interfaces:**
- Produces: `Sonic2WFZEvents#getBgXPos()` / `#getBgYPos()` (public) returning the post-`ScrollBG` BG position; after routine 0 seeds them to the camera, `bgYPos` chases `cameraY − bgYOffset` at ≤16 px/frame.

- [ ] **Step 1: Write the failing test.** Two cases: (a) with `bgYOffset` set, `bgYPos` moves toward `cameraY − bgYOffset` at ≤16/frame; (b) **regression for P2#1** — camera moves between routine 0 (seed) and the first routine-2 `syncBgDiffs`, and `bgYPos` tracks it (no reseed discards it). Drive through the real routine dispatch (`update()` with `eventRoutine` 0 then 2), using the existing `setBgYOffsetForTest` (`:189`) and a stubbed/advanced `camera()`.
```java
@Test
void scrollBgChasesCameraMinusOffsetClampedTo16() throws Exception {
    Sonic2WFZEvents ev = newWfzEventsWithCamera(cam);   // existing test seam
    cam.set(0x200, 0x180); ev.runRoutine(0);            // routine 0 seeds bgYPos=0x180
    ev.setBgYOffsetForTest(0x100);
    ev.runRoutine(2);                                    // one ScrollBG step
    assertEquals(0x180 - 16, ev.getBgYPos());            // chases toward 0x080 at 16/frame
}

@Test
void seedFromRoutine0ThenCameraMovesBeforeFirstScrollBg() throws Exception {
    Sonic2WFZEvents ev = newWfzEventsWithCamera(cam);
    cam.set(0x200, 0x180); ev.runRoutine(0);            // seed at 0x180
    cam.set(0x200, 0x188);                               // camera moved +8 before routine 2
    ev.runRoutine(2);
    assertEquals(0x188, ev.getBgYPos());                 // tracks (offset 0, ≤16 move) — NOT reseeded/discarded
}
```
- [ ] **Step 2: Run test to verify it fails** — `mvn -o "-Dtest=com.openggf.game.sonic2.TestSonic2WfzBgScroll" test` → FAIL (current snap ignores offset).
- [ ] **Step 3: Implement** — rewrite `syncBgDiffs()` (no flag):
```java
private void syncBgDiffs() {
    int dx = clamp16(camera().getX() - bgXPos - bgXOffset);
    int dy = clamp16(camera().getY() - bgYPos - bgYOffset);
    bgXPosDiff = dx; bgYPosDiff = dy;
    bgXPos += dx;    bgYPos += dy;
}
private static int clamp16(int v) { return v > 16 ? 16 : (v < -16 ? -16 : v); }
```
Add public `getBgXPos()`/`getBgYPos()`.
- [ ] **Step 4: Run tests to verify they pass** (both cases).
- [ ] **Step 5: Run the trace gate** (`TestS2WfzLevelSelectTraceReplay` + `TestS2DezEndingLevelSelectTraceReplay`) → still green (normal play: offset 0, camera ≤16/frame ⇒ behaviourally identical to the old snap).
- [ ] **Step 6: Commit** (`fix(s2): WFZ ScrollBG chase for BG scroll ...`, `Changelog: updated`).

---

### Task 2: `WfzRuntimeStateView` bridge — `SwScrlWfz` consumes the live event BG position

**Files:**
- Create: `src/main/java/com/openggf/game/sonic2/runtime/WfzRuntimeState.java` (interface extends `ZoneRuntimeState`)
- Create: `src/main/java/com/openggf/game/sonic2/runtime/WfzRuntimeStateView.java` (live view over `Sonic2WFZEvents`)
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelEventManager.java:~132` (install the WFZ view next to HTZ/CNZ)
- Modify: `src/main/java/com/openggf/game/sonic2/scroll/SwScrlWfz.java:97,102,141` (read the registry view instead of `bgCamera` for VScroll (`:97`), `bgXPosLong` (`:102`), and segment-select `bgY` (`:141`))
- Test: `src/test/java/com/openggf/game/sonic2/TestSonic2WfzBgBridgeIntegration.java` (new — real context)

**Interfaces:**
- Consumes: `Sonic2WFZEvents#getBgYPos()`/`#getBgXPos()` (Task 1), `GameServices.zoneRuntimeRegistry()`, the HTZ/CNZ registration idiom (`installOwnedRuntimeState`, `Sonic2LevelEventManager.java:132-145`), `HtzRuntimeStateView` as the template.
- Produces: `WfzRuntimeState#bgVscrollFactor()` (= `getBgYPos()`) and `#bgXPos()` (= `getBgXPos()`), live; installed in `ZoneRuntimeRegistry` when WFZ loads; read by `SwScrlWfz` for both axes.

- [ ] **Step 1: Write the failing integration test** (through the real context, per §5.3 — NOT a direct setter):
```java
// Boot a WFZ gameplay context (mirror an existing SharedLevel / context-boot test),
// drive the WFZ event to routine 6 with non-zero bgYOffset AND bgXOffset for a few frames,
// run one LevelFrameStep + ParallaxManager pass, and assert BOTH axes of the rendered scroll:
//  - the VScroll factor tracks Sonic2WFZEvents.getBgYPos() (scrolls up), and
//  - the static-BG/ship HScroll word tracks Sonic2WFZEvents.getBgXPos() (from the -$2C0 ramp),
// not the frozen bgCamera values.
@Test
void wfzEndingScrollReachesRenderedVscrollAndHscrollThroughRuntimeRegistry() { /* ... */ }
```
- [ ] **Step 2: Run to verify it fails** (SwScrlWfz still reads the frozen `bgCamera` for both axes).
- [ ] **Step 3: Implement.** Create `WfzRuntimeState` (`int bgVscrollFactor();` + `int bgXPos();`)
  and `WfzRuntimeStateView` (live over `Sonic2WFZEvents`, mirroring `HtzRuntimeStateView`);
  install it in `Sonic2LevelEventManager` beside HTZ/CNZ; change `SwScrlWfz` to read
  `GameServices.zoneRuntimeRegistry().currentAs(WfzRuntimeState.class)` for VScroll (`:97`),
  `bgXPosLong` (`:102`), and segment-select `bgY` (`:141`) (fall back to the existing
  `bgCamera` value if the view is absent, for non-WFZ/test safety).
- [ ] **Step 4: Run to verify it passes.**
- [ ] **Step 5: Rewind check** — a WFZ-ending rewind restores the event `bgYPos`; assert the
  rendered VScroll on the next parallax pass equals the restored value (guards P1#2).
- [ ] **Step 6: Trace gate** (`TestS2WfzLevelSelectTraceReplay` + `TestS2DezEndingLevelSelectTraceReplay`) green.
- [ ] **Step 7: Commit** (`Changelog: updated`).

---

### Task 3: Regression + rewind verification

**Files:** none (verification), or a small WFZ-ending rewind assertion if a gap surfaces.

- [ ] **Step 1:** Run the trace gate:
      `mvn -Ptrace-replay -o "-Dtest=com.openggf.tests.trace.s2.TestS2WfzLevelSelectTraceReplay,com.openggf.tests.trace.s2.TestS2DezEndingLevelSelectTraceReplay" test` → both green.
      If either regresses, bisect the diff (Task 1 vs Task 2) and fix; do not proceed.
- [ ] **Step 2:** Run `TestSonic2WfzBgScroll` + `Sonic2WFZEvents` rewind snapshot tests → green.
- [ ] **Step 3:** Manual visual check via `dev.cmd` at the WFZ ending (record result).
- [ ] **Step 4:** Update `CHANGELOG.md` (single feature entry) and merge to `develop` per policy (README release-log update on merge).

---

## Self-review notes
- Spec coverage: Gap A → Task 1; Gap B → Task 2; Phase 0 gate → resolved (§3, PASS);
  trace/rewind safety → Task 3 + each task's gates.
- Phase 0 gate resolved: the BG plane is 2048px tall and the "space" is the backdrop revealed
  above the sky (§3) — wiring task, not BG-layout task.

## Review resolution (Codex, 2026-07-16)
All five items verified against the codebase and folded in:
- **P1 (cross-lifetime injection):** dropped the `BackgroundCamera` injection; switched to a
  live `WfzRuntimeStateView` in `ZoneRuntimeRegistry`, mirroring `HtzRuntimeStateView`/`SwScrlHtz`
  (verified `Sonic2LevelEventManager.java:132`, `HtzRuntimeStateView.java`, `SwScrlHtz.java:61`). §4.2.
- **P1 (rewind stale):** resolved by the same change — the view is live, so the snapshot-restored
  event `bgYPos` is read on the next parallax pass; no duplicate state. §4.2/§5.5.
- **P1 (snapshot bytes):** moot — the `bgSeeded` field is gone (§4.1), so `SNAPSHOT_BYTES`
  (`Integer.BYTES * 8`) is unchanged (verified `Sonic2WFZEvents.java:30`,
  `Sonic2LevelEventManager.java:49-54,311`).
- **P2 (routine 0 already seeds):** verified `primaryRoutine0_initBgSync()`
  (`Sonic2WFZEvents.java:243-247`); dropped `bgSeeded`; added the movement-between-init
  regression test (Task 1 case b).
- **P2 (bridge test bypassed production):** Task 2's test is now an integration test through the
  real gameplay context/registry asserting rendered VScroll (§5.3).

Review resolution (round 2, 2026-07-16):
- **P2 (bridge the X axis too):** `ScrollBG` advances `bgXPos` as well (the `-$2C0`
  `Camera_BG_X_offset` ramp), and `SwScrlWfz.java:102` drives the static-BG/getaway-ship
  layers from `bgCamera.getBgXPos()`. `WfzRuntimeState` now exposes `bgXPos()` too, `SwScrlWfz`
  reads the view at `:97/:102/:141`, and the integration test asserts both the VScroll and the
  HScroll word (§4.2, Task 2, §5.3).

---

## 7. Approved visual-parity follow-up (2026-07-16)

Headless capture of the implemented bridge exposed additional ROM-parity defects. The foreground
Tornado body and Sonic's grab animation are correct and remain outside this follow-up; the separate
ObjB2 `$5C` rocket pod/flame is missing and is explicitly included in Section 7.6.

### 7.1 Boss laser-wall display cadence

The two ObjC5 laser-wall children use the correct solid-yellow mapping frame `$0C`, but the
ROM toggles their display bit every active frame in `ObjC5_LaserWallWaitDelete`. `SolidObject`
still runs on hidden frames. The engine currently renders the wall continuously until its
defeat-delete animation begins.

Add an explicit wall-local `visibleThisFrame` phase. Toggle it on every non-defeat update and
gate only `appendRenderCommands`; do not gate `getSolidParams()` or alter the art/palette.
The scalar is covered by generic rewind capture, and a graph rewind test must prove that the
cadence resumes from the restored phase. Defeat rendering continues to use the ROM's nested
animation counters, with the visibility latch making the display decision explicit rather
than inferred from a signed counter alone.

### 7.2 Plane-B cache follows the live WFZ background X position

The event-spawned `$58` thrusters are independent sprites. Their associated small getaway-ship
hull is Plane-B content in WFZ background rows 0-1, columns `$53..$68`, selected by the
transition table's layer-offset `$04` spans. `SwScrlWfz.update()` correctly uses live
`WfzRuntimeState.bgXPos()` for the H-scroll words, but it does not override
`ZoneScrollHandler#getBgCameraX()`. The tilemap cache therefore remains anchored to the
sentinel/fallback camera window and may omit the hull while its sprite thrusters remain visible.

Override `SwScrlWfz#getBgCameraX()` to return the same live runtime-state X value used by
`update()`, falling back to `BackgroundCamera` when no gameplay runtime/view is installed.
This keeps tile residency and per-line scrolling on one source of truth and requires no new
state or snapshot field.

### 7.3 Superseding diagnosis: Plane B is a persistent nametable, not a wide map

The 8192-pixel period correction was disproved by the replacement headless capture and an aligned
stable-retro replay of the checked-in WFZ BK2 (`bk2_frame_offset = 2845`). The engine shows
rectangular space/horizon regions around capture frames 14820-15000 (4:07-4:10), whereas the ROM
shows blue sky at frames 14820, 14920, 15000, and 15840. The ROM horizon first enters at the
top-right around frame 15850; black first reaches that point around frame 15870 (4:24.5).

The cause is the update model, not insufficient width. The ROM owns one persistent 64x32-cell
(512x256-pixel) Plane-B nametable. `SwScrl_WFZ` derives row H-scroll values from the static/ship
and cloud accumulators, but only `Camera_BG_X_pos_diff`/`Camera_BG_Y_pos_diff` and the resulting
scroll flags cause `Draw_BG1` to replace crossed 16-pixel columns or rows. Cloud H-scroll never
loads a separate distant map window. Every scanline addresses the same retained 512-pixel ring.

The engine's 8192-pixel static texture instead lets each row sample arbitrary global layout
columns. The active layer spread reaches roughly 8,950 pixels, so it still exceeds the cap; more
importantly, even an unbounded snapshot would expose map content that the VDP nametable has not
loaded. This aliases future ending blocks into the earlier sky and makes them sweep in and out as
the layer values change. The dynamic `SwScrlWfz#getBgPeriodWidth()` override and its 8192-pixel
tests are therefore removed.

### 7.4 Persistent WFZ Plane-B nametable

Add an opt-in Plane-B update mode to the existing scroll/tilemap boundary. The default remains the
current stateless background window for every other zone. WFZ selects a persistent Genesis
nametable mode with fixed dimensions of 64x32 cells:

- Seed the retained ring through the same logical map lookup used by the normal level-start Plane-B
  draw, anchored to the live `WfzRuntimeState.bgXPos()`/`bgVscrollFactor()` values.
- Track the previous 16-pixel-aligned BG X/Y positions and update only the entering column and/or
  row when those positions cross a cell boundary. A one-frame 16-pixel movement performs one
  strip update; a larger discontinuity or invalid baseline performs a complete deterministic seed.
- Keep physical ring slots stable and advance explicit X and Y logical origins modulo 64 columns
  and 32 rows. Each 16-pixel crossing replaces two 8-pixel pattern columns and/or two pattern rows.
  Extend the existing GPU ring metadata from X-only to X+Y tile origins, add partial-row uploads,
  and remap both shader tile coordinates through those origins before the modulo lookup. The
  shader continues to consume per-scanline H-scroll, but samples this one 512x256 ring; cloud
  values do not change residency or enlarge the period.
- Upload only the changed descriptors after a normal strip update; a seed or rewind restore uses
  one coherent full-ring upload. No new renderer or special draw pass is introduced; the
  implementation stays inside the existing `LevelTilemapManager`/`TilemapGpuRenderer`/
  scroll-handler ownership boundary.
- Add a gameplay-scoped `LevelTilemapManager` rewind adapter alongside the existing level rewind
  adapters. It captures the 64x32 descriptors, X/Y logical origins, and previous aligned BG X/Y
  baseline. Restore installs those values, suppresses the current camera/layout-pure BG rebuild
  callback for persistent mode, and completes the full GPU upload before the next draw. The
  existing stateless zones keep `resetTilemapsForRewindRestore()` unchanged. Level/session reset
  clears the persistent baseline and performs a fresh seed.

This is deliberately a generic opt-in capability whose behavior is selected by the WFZ handler,
not a frame, route, or zone-name branch in shared rendering code.

### 7.5 Background ship-thruster lifetime and video cadence

The flames attached to the small Plane-B ship are placed ObjBC objects, not the later ObjB2 `$58`
children. ObjBC deliberately has no `MarkObjGone` call in the ROM: it derives X from
`Camera_BG_X_offset`, flickers, and deletes itself only when that offset reaches `$380`. The engine
currently applies generic Sonic 2 off-screen culling before ObjBC updates, destroying both flames
around capture frame 13600; the ROM retains them through frame 13929 while the hull is visible and
deletes them at frame 13930.

Make `WFZShipFireObjectInstance` persistent so generic placement culling cannot shorten its
lifetime. Its existing `$380` event-offset check remains the sole deletion owner and prevents the
object carrying into DEZ. Do not render ObjB2 subtype `$56`, whose ROM routine is an intentionally
invisible grabber, and do not alter the later `$58` foreground-ship flames.

The ObjBC flames intentionally alternate visible/hidden every source frame. The canonical capture
remains lossless FFV1 at 60 fps. Some 30-fps previews select only the hidden parity, so also produce
a clearly labelled cadence-safe review copy using a repeating visible-visible-hidden-hidden source
selection at 60 fps. It preserves duration and the on/off duty cycle while presenting the flicker
at 15 Hz. The review copy is visual evidence, not a replacement for the canonical timing capture.

### 7.6 Tornado rocket-booster runtime PLC readiness

Aligned stable-retro frame 14760 shows ObjB2 subtype `$5C` as a large gray rocket pod beneath the
Tornado with a bright orange/white flame extending left. Engine auxiliary state proves the child
is alive, positioned at `(parentX-$0C, parentY+$28)`, and animating; nevertheless, the engine frame
contains neither pod nor flame.

The ROM's initial WFZ PLC includes the Tornado body but not `ArtNem_TornadoThruster`. Runtime
`PLCID_Tornado` registers the `TORNADO_THRUSTER` sheet during `LevEvents_WFZ_Routine6`, increments
the object-art load epoch, and creates a new `PatternSpriteRenderer`. The renderer is never passed
through `ObjectRenderManager.ensurePatternsCached(...)`, leaving `patternBase == -1` and
`isReady() == false`; `TornadoObjectInstance.appendRenderCommands()` consequently skips it.

Make runtime Sonic 2 PLC intake finish the existing object-art registration contract. Add a
`LevelManager`-owned object-art refresh method that reuses its package-owned
`OBJECT_PATTERN_BASE` and reruns `ObjectRenderManager.ensurePatternsCached(...)`. At level
initialization, register the full fixed `PatternAtlasRange.OBJECTS` governance range once instead
of registering only the currently used prefix. Before any runtime cache write, the refresh asks
the active object-art provider/manager for its ordered regular-sheet pattern count, computes the
prospective end index, and rejects a value beyond `PatternAtlasRange.OBJECTS.endExclusive()`.
Only after that preflight passes does it call `ensurePatternsCached(...)`; the returned end must
equal the preflight value. The refresh does not register a second/overlapping range. Call it from
the successful Sonic 2 runtime PLC path after a PLC adds sheets.
Existing renderers retain their deterministic bases, newly appended renderers receive
non-overlapping bases, and a no-op/repeated PLC request does not reallocate or duplicate art. Do
not expose/copy the base constant, special-case subtype `$5C`, or bypass
`PatternSpriteRenderer.isReady()`.

Failures remain non-fatal as they are today: an unavailable runtime/level drops the request, and
an IO/runtime load failure logs and leaves the renderer unavailable. No pending-PLC queue is added.
A successful request must make the new renderer ready before the same gameplay frame's render
pass. `Sonic2ObjectArtProvider.restore()` must restore the captured `loadEpoch`; immutable loaded
sheets/renderers may remain monotonic. Rewinding to before the one-shot request and replaying it
must reuse the same renderer identity and pattern base, advance the restored epoch through the
same value again, and never create a second pattern allocation.

### 7.7 Verification and acceptance

- Unit-test draw/skip/draw laser-wall cadence and unchanged solidity.
- Extend the existing boss graph rewind test to restore the wall visibility phase.
- Unit-test `SwScrlWfz#getBgCameraX()` against a live custom `WfzRuntimeState` and fallback.
- Replace the WFZ 8192-period test with a 512x256 persistent-ring test proving cloud H-scroll does
  not change map residency, crossed strips update once, wrap slots remain stable, and reset reseeds.
- Rewind a WFZ ending frame across row/column crossings and assert the retained Plane-B descriptors
  and X/Y ring origins are restored, the camera-derived rebuild is suppressed, and the next
  rendered frame matches the pre-rewind state.
- Unit-test that ObjBC bypasses generic off-screen deletion until its own `$380` threshold.
- Request `PLCID_Tornado` through the production runtime path and assert the newly registered
  `TORNADO_THRUSTER` renderer becomes ready without moving existing pattern bases; repeat the
  request and assert idempotence.
- Assert the single registered `PatternAtlasRange.OBJECTS` range contains all newly appended
  patterns, the preflight rejects an overflow before any atlas/cache write, the post-cache end
  equals the preflight end, and a repeated/no-op PLC request adds no range registration.
- Rewind to before `PLCID_Tornado`, replay the one-shot request, and assert restored load-epoch
  parity plus stable renderer identity and pattern base.
- Render the live `$5C` child after the PLC request and assert both body mappings and alternating
  flame mappings produce commands at the ROM-relative position.
- Remove the obsolete runtime-state backdrop tests while retaining coverage for the existing MCZ
  provider fallback.
- Run the focused WFZ scroll/runtime/boss/rewind suites and both S2 WFZ/DEZ ending trace replays.
- Capture the ending headlessly and compare engine frames with the aligned stable-retro gates:
  uncovered background at frames 14820, 14920, 15000, and 15840 remains blue; the first horizon
  pixels enter around 15850-15860; black first appears around 15870. Frame 14760 contains the
  Tornado rocket pod and left-facing flame. The small Plane-B hull and both ObjBC flames must
  overlap visibly during their interval.
- Deliver the canonical 60-fps FFV1 capture and the labelled cadence-safe review copy. All capture,
  screenshots, and video inspection remain headless; no desktop/computer control is required.

The original follow-up procedure is in
`docs/architecture/plans/2026-07-16-wfz-ending-visual-parity-followup.md`; the approved corrective
procedure for Sections 7.3-7.5 is in
`docs/architecture/plans/2026-07-16-wfz-ending-late-horizon-thrusters.md`.
Those plan sections describe the superseded 8192-pixel approach and must not be re-executed. A new
implementation plan will cover Sections 7.3-7.7 after this corrected spec is approved.
