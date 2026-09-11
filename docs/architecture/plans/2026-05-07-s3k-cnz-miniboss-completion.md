# S3K CNZ Miniboss Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Sonic 3 and Knuckles Casino Night Zone Act 1 miniboss parity for the route into the arena, including the reported CNZ cylinder carry bug, arena gate, miniboss music, boss/child animations, top spinner behavior, spawned scroll-control object, walls, layout mutations, and vertically scrolling infinite tunnel behavior.

**Architecture:** Keep CNZ behavior split across the existing runtime-owned systems. `Sonic3kCNZEvents` owns act event state, camera clamps, background-event state, and bridge methods into `ZoneLayoutMutationPipeline`. CNZ object instances own object-local behavior and use `ObjectServices`; no object should call singleton managers directly. `SwScrlCnz` owns CNZ scroll/deform publication and consumes CNZ event scroll state. Dynamic art remains in `Sonic3kObjectArtProvider` and runtime PLC paths.

**Tech Stack:** Java 21, Maven, JUnit 5, existing OpenGGF Sonic 3K module, `GameServices`, `ObjectServices`, `ZoneRuntimeRegistry`, `ZoneLayoutMutationPipeline`, `ScrollEffectComposer`, and focused headless tests.

---

## Confirmed ROM Behavior

- CNZ cylinder bug: the player at CNZ1 center X `$662`, center Y `$1757` must be carried down by `CNZCylinder` while grounded/riding; ROM `x_pos/y_pos` map to engine `getCentreX/getCentreY`.
- Miniboss outer gate:
  - Waits until `Camera_X_pos >= $31E0`.
  - Locks camera min X `$31E0`, max X `$3260`, min Y `$1C0`, max Y/target `$2B8`.
  - Runs a `2*60` frame wait.
  - Fades out current music, sets `Boss_flag`, loads PLC `$5D`, and loads `Pal_CNZMiniboss`.
  - After the wait, plays `mus_Miniboss`, records it as current music, and creates `Obj_CNZMinibossScrollControl`.
- Boss body:
  - Runtime hit counter is `$45 = 4`; `collision_property = 6` is not the real damage count.
  - Initial downward velocity is `$80`; inner wait is `$11F`.
  - Creates top and coil children through the child data table.
  - Player attacks open the coil; top piece hitting the exposed coil consumes the real boss hit counter.
- Raw animations:
  - Opening: frames `0,1,2,3,4,5,6` with 3-frame delays.
  - Closing: frames `6,5,4,3,2,1,0` with 3-frame delays.
  - Top: frames `7,7,9` with the ROM raw-animation timing.
  - Debris, sparks, bounce, and explosion children use the CNZ miniboss mapping set and should render through `Sonic3kObjectArtKeys.CNZ_MINIBOSS`.
- Top piece:
  - Uses solid-object/player interaction, terrain/wall/floor/ceiling probes, arena bounces, and base-hit checks.
  - Can break arena blocks and signal event bytes used by the background/tunnel sequence.
- Scroll/tunnel:
  - CNZ background event progresses through normal, boss start, boss scroll, after-boss, foreground refresh, second refresh, and transition gate.
  - Boss scroll computes BG Y as camera Y minus `$100` plus `Events_bg+$08`, and BG X as camera X minus `$2F80`.
  - Scroll control mutates FG/BG layout at init, accelerates `Events_bg+$0C` to `$40000`, accumulates `Events_bg+$08`, decelerates to `$10000`, snaps to a `$100` boundary, resets boss scroll offset, sets camera target max Y `$1000`, enables background collision, performs the upper FG mutation, then exits after the offset reaches `$1C0`.

## Current Engine Gaps

- `Sonic3kCNZEvents` currently gates the boss too early at `$3000` and has a miniboss music comment instead of playback.
- `CnzMinibossInstance` starts in the inner boss sequence and does not wait for the ROM outer gate release.
- `CnzMinibossInstance` uses six hits instead of the ROM four-hit `$45` counter.
- `CnzMinibossInstance` and `CnzMinibossTopInstance` render basic frames, but raw animation scripts are not implemented.
- Top and coil children are not spawned in production; current top tests attach a top instance manually.
- `CnzMinibossScrollControlInstance` is a minimal helper and omits the ROM init, wait, slow, snap, layout-copy, and post-boss stages.
- Arena walls and vertical tunnel layout mutations are represented as event counters rather than real `MutableLevel`/mutation-pipeline writes.
- Full Maven verification may be affected by the unrelated untracked diagnostic test `src/test/java/com/openggf/game/rewind/TestRewindIter1631Diagnostic.java`; focused CNZ verification has compiled successfully, but final full-suite verification must either fix or explicitly disable that local diagnostic test with a reason if it fails normal discovery.

## Worker Order

Implement one task at a time. After each implementation task, run the listed focused command and request review before proceeding to the next task. Review agents must inspect only the files owned by the task plus direct callers/tests.

## Task 0: Restore The Test Compile Gate

**Owner:** Verification worker.

**Files:**
- `src/test/java/com/openggf/game/rewind/TestRewindIter1631Diagnostic.java`

**Steps:**

- [ ] Preserve the untracked diagnostic file unless it fails normal Maven discovery.
- [ ] If `TestRewindIter1631Diagnostic` fails during broad verification, either fix the diagnostic failure without changing its purpose or add an explicit JUnit 5 `@Disabled` reason because it is a local diagnostic, not a product regression test.
- [ ] Run focused CNZ verification with full Maven output once:

```powershell
mvn -Dmse=off "-Dtest=TestS3kCnzAct1EventFlow,TestS3kCnzBossScrollHandler,TestCnzMinibossTopPhysics" test
```

**Acceptance:**

- The focused CNZ tests compile and run without the unrelated rewind diagnostic blocking `testCompile`.

## Task 1: Add CNZ Miniboss Parity Tests First

**Owner:** Test worker.

**Files:**
- `src/test/java/com/openggf/tests/TestS3kCnzDirectedTraversalHeadless.java`
- `src/test/java/com/openggf/tests/TestS3kCnzAct1EventFlow.java`
- `src/test/java/com/openggf/tests/TestS3kCnzBossScrollHandler.java`
- `src/test/java/com/openggf/tests/TestS3kCnzMinibossHeadless.java`
- any existing CNZ miniboss arena/headless tests found by `rg -n "CnzMiniboss|CNZ miniboss" src/test/java`
- `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossTopPhysics.java`
- `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossDefeatPhase.java`
- `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossInstanceBase.java`
- `src/test/java/com/openggf/game/sonic3k/constants/TestCnzMinibossConstants.java`

**Steps:**

- [ ] Keep the existing CNZ cylinder regression test and ensure its assertions use center coordinates.
- [ ] Add an event-flow test that proves the early lower-route remap/tunnel scroll mode can occur at camera X `$3000`, but camera clamp, `Boss_flag`, PLC, palette, release timer, and music do not occur until camera X `$31E0`.
- [ ] Add an event-flow test that simulates the 120-frame outer wait and verifies `Sonic3kMusic.MINIBOSS.id` is played once when the boss is released.
- [ ] Add a boss object test that verifies production child creation creates a top piece and coil child when the boss starts.
- [ ] Update hit-count tests so the player attack opens the coil but does not decrement remaining hits, and the top-to-coil hit decrements a four-hit boss counter.
- [ ] Add a scroll-control test that steps acceleration, wait, deceleration, snap, camera target max Y `$1000`, background collision enablement, and delete-after-offset `$1C0`.
- [ ] Add a scroll-handler test for boss BG Y formula: `cameraY - 0x100 + bossScrollOffsetY`.
- [ ] Add a layout-mutation test that observes actual level/mutation-pipeline writes for the initial tunnel rows and the upper post-scroll FG row.

**Acceptance:**

- New tests fail on the current incomplete implementation for the intended CNZ reasons.
- Tests do not assert top-left debug coordinates as ROM positions.

## Task 2: Split Early Scroll Mode From ROM Arena Lock

**Owner:** Events worker.

**Files:**
- `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- `src/main/java/com/openggf/game/sonic3k/scroll/SwScrlCnz.java`
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossScrollControlInstance.java`

**Steps:**

- [ ] Keep the lower-route remap and boss scroll-mode entry that prevents the player from disappearing near the lower route.
- [ ] Introduce a separate ROM arena threshold constant `$31E0` for the real arena gate.
- [ ] Preserve the two-phase contract: camera X `$3000` may enter lower-route/tunnel mode, but it must not clamp the camera, set `Boss_flag`, load PLC/palette, start the release timer, play music, or spawn scroll control.
- [ ] On the real gate, apply the ROM camera bounds, set boss flag, load PLC `$5D`, prepare the CNZ miniboss palette, fade out current music, and start a 120-frame release timer.
- [ ] After the timer expires, set a `minibossStartReleased` flag, play `Sonic3kMusic.MINIBOSS.id`, and spawn `CnzMinibossScrollControlInstance` exactly once from `Sonic3kCNZEvents` through `Sonic3kZoneEvents.spawnObject(Supplier<T>)`.
- [ ] Expose only the narrow CNZ event methods needed by boss/scroll-control objects:
  - `isMinibossStartReleased()`
  - `getBossScrollOffsetY()`
  - `setBossScrollOffsetY(int)`
- [ ] Keep background-collision and camera-target writes internal to event/scroll-control code unless an object bridge method is required; use `camera().setMaxYTarget(...)` and the existing game-state background-collision API directly from event-owned code.
- [ ] Keep `SwScrlCnz` boss scroll formula aligned with ROM: BG Y equals camera Y minus `$100` plus the boss scroll offset.

**Acceptance:**

- Event-flow and scroll-handler tests from Task 1 pass.
- No object class calls `GameServices` or direct singleton getters for services available through `ObjectServices`.

## Task 3: Boss Startup, Music Release, Child Creation, And Four-Hit Damage

**Owner:** Boss object worker.

**Files:**
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossTopInstance.java`
- `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- `src/test/java/com/openggf/game/sonic3k/constants/TestCnzMinibossConstants.java`
- all tests found by `rg -n "CNZ_MINIBOSS_HIT_COUNT|remainingHits|SixthHit|6 hits" src/test/java`
- New CNZ miniboss child class files only where needed under `src/main/java/com/openggf/game/sonic3k/objects/`

**Steps:**

- [ ] Make the boss body wait for `Sonic3kCNZEvents.isMinibossStartReleased()` before running the inner boss init sequence.
- [ ] Split constants into `CNZ_MINIBOSS_COLLISION_PROPERTY = 0x06` and `CNZ_MINIBOSS_REAL_HITS = 0x04`; keep `CNZ_MINIBOSS_HIT_COUNT` only if it is redefined as an alias for the real hit counter and all collision-property callers move to the new name.
- [ ] During inner init, set the real remaining-hit counter from `CNZ_MINIBOSS_REAL_HITS` while preserving collision metadata separately.
- [ ] Spawn the top piece and coil child through `spawnChild`.
- [ ] Do not spawn scroll control from the boss body; `Sonic3kCNZEvents` is the single owner of scroll-control creation after the 120-frame release timer.
- [ ] Keep player attack behavior as coil-open only; it must not decrement remaining hits.
- [ ] Decrement remaining hits only when the top piece collides with the open coil/base hit area.
- [ ] Enter defeat only after the fourth top-to-coil hit.
- [ ] Ensure support art readiness before rendering CNZ miniboss frames, using the same pattern as MGZ miniboss support art if renderer readiness is missing after dynamic PLC.

**Acceptance:**

- Boss startup/child/hit tests pass.
- Existing CNZ object tests are updated for the four-hit damage model.

## Task 4: Raw Animations And Visible Child Effects

**Owner:** Animation/render worker.

**Files:**
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossTopInstance.java`
- CNZ miniboss child effect classes added in Task 3
- `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossTopPhysics.java`

**Steps:**

- [ ] Add a small object-local raw-animation runner that supports frame/delay pairs and terminal repeat/end markers needed by the CNZ miniboss scripts.
- [ ] Port the opening and closing frame sequences exactly: `0..6` and `6..0`, each with 3-frame delays.
- [ ] Port the top animation frames used by `AniRaw_CNZMinibossTop` and `AniRaw_CNZMinibossTop2`.
- [ ] Render coil, top, sparks, bounce flashes, debris, and boss explosion pieces through `PatternSpriteRenderer` and `Sonic3kObjectArtKeys.CNZ_MINIBOSS`.
- [ ] Add tests that step animation frames and assert the visible mapping frame sequence.

**Acceptance:**

- The boss no longer uses the fixed 64-frame close wait as a replacement for raw animation.
- The top piece and spawned effects render with CNZ miniboss art frames instead of invisible logic-only objects.

## Task 5: Scroll-Control Object And Infinite Tunnel Layout Mutations

**Owner:** Scroll/layout worker.

**Files:**
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossScrollControlInstance.java`
- `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- `src/main/java/com/openggf/game/sonic3k/scroll/SwScrlCnz.java`
- `src/main/java/com/openggf/game/mutation/` files only if the current mutation API cannot express the required copy/write operation
- `src/test/java/com/openggf/game/mutation/` tests only if mutation API behavior is extended
- Focused scroll/layout tests from Task 1

**Steps:**

- [ ] Replace the minimal scroll-control helper with ROM routine phases: init, main acceleration, wait, slow deceleration, snap/reset, wait-for-offset, delete.
- [ ] In init, use `ZoneLayoutMutationPipeline.queue(...)` or `applyImmediately(...)` with `LayoutMutationIntent`, `LayoutMutationContext`, and `LevelMutationSurface.forLevel(...)` to perform the ROM-equivalent lower FG/BG tunnel row writes.
- [ ] Express the ROM layout-copy operations as explicit source/destination cells in the implementation comments after verifying the source/destination map coordinates from `Obj_CNZMinibossScrollControl`; tests must assert the chosen cells rather than only event fields.
- [ ] In main, increase velocity by `$200` until capped at `$40000`, and add it to the boss scroll offset.
- [ ] When the boss defeat signal is set, wait for `(offset & $FF) < 4`, then decelerate by `$400` until velocity reaches `$10000`.
- [ ] At the snap phase, align the offset to a `$100` boundary, reset boss scroll offset when the ROM routine resets it, call `camera().setMaxYTarget((short) 0x1000)`, enable the game-state background-collision flag through the existing API, and queue the upper FG mutation.
- [ ] Keep the object alive until the final offset reaches `$1C0`, then mark the event state and delete the object.
- [ ] Ensure scroll state is rewind-captured through existing object/event state mechanisms or document the field policy in the object if automatic capture omits it.

**Acceptance:**

- Scroll-control tests pass through every phase.
- Layout tests verify changed `MutableLevel` cells and consumed dirty map/block/chunk regions after mutation processing, not only CNZ event counters.
- The miniboss arena no longer loses its surroundings; the vertical tunnel uses real layout/scroll state instead of only a visual offset.

## Task 6: Top Piece Physics, Walls, And Arena Block Destruction

**Owner:** Top physics worker.

**Files:**
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossTopInstance.java`
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`
- `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- `src/test/java/com/openggf/game/sonic3k/objects/TestCnzMinibossTopPhysics.java`

**Steps:**

- [ ] Replace simple arena-edge-only bounces with terrain-aware probes for right wall, left wall, floor, and ceiling using existing collision services.
- [ ] Preserve ROM arena fallback edges at X `$3200/$3380` and Y `$240/$380` so tests remain deterministic without full GL rendering.
- [ ] Add player bounce interaction equivalent to the ROM top-piece bounce check.
- [ ] When terrain collision breaks arena blocks, signal the CNZ event object and queue the corresponding level mutation through the pipeline.
- [ ] Spawn bounce or block-explosion child effects when the top piece hits walls/floors/blocks.
- [ ] Keep base-hit detection in center-coordinate space and avoid mixing `getX/getY` with ROM object positions.

**Acceptance:**

- Top physics tests cover wall, ceiling/floor, player bounce, block destruction, and base-hit damage.
- The route has visible walls and destructible/refreshing blocks that participate in gameplay.

## Task 7: Defeat Flow, Cleanup, And Post-Boss Transition

**Owner:** Defeat/event worker.

**Files:**
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossInstance.java`
- `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- `src/main/java/com/openggf/game/sonic3k/objects/CnzMinibossScrollControlInstance.java`
- Existing CNZ defeat/event tests

**Steps:**

- [ ] On fourth valid top-to-coil hit, enter the ROM defeat phase and notify scroll control of the boss-defeat signal.
- [ ] Keep boss explosions, palette flash, boss flag clearing, camera unlock, and signpost/post-boss progression aligned with existing S3K boss patterns.
- [ ] Ensure the after-boss background event stages progress in order: after-boss, FG refresh, FG refresh second pass, transition gate.
- [ ] Make all dynamically spawned children delete themselves or become inert at defeat/cleanup.
- [ ] Keep old tests for chunk destruction updated to assert real mutation/event state rather than only `destroyedArenaRows += 0x20`.

**Acceptance:**

- CNZ miniboss defeat tests pass with four hits and the scroll-control post-boss tunnel sequence.
- No logic-only child remains permanently active after the boss is defeated.

## Task 8: Self-Review And Verification

**Owner:** Integration lead plus review agents.

**Steps:**

- [ ] Run a review agent after each implementation task using `superpowers:requesting-code-review`.
- [ ] Review service-boundary compliance: objects use `services()`, event/manager code uses `GameServices` or runtime context.
- [ ] Review coordinate semantics: ROM positions use `getCentreX/getCentreY`.
- [ ] Review animation and hit counter values against the disassembly excerpts in this plan.
- [ ] Run focused verification:

```powershell
mvn -q -DskipTests compile
mvn -q "-Dtest=TestS3kCnzDirectedTraversalHeadless,TestS3kCnzAct1EventFlow,TestS3kCnzBossScrollHandler,TestCnzMiniboss*" test
```

- [ ] Run broader verification when focused tests are green:

```powershell
mvn -q test
```

**Acceptance:**

- Focused CNZ tests are green.
- Full Maven test suite is green, or any remaining failure is documented with exact class, method, command, and reason.
- Final response names the modified files, the verification commands, and any residual risk.
