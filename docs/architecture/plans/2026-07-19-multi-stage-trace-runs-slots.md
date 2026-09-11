# Multi-Stage Trace Runs — Slots-Depth Plan: Slot-Machine Bonus Trace Replay

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend bonus-stage trace replay to the slot machine (spec engine addition #4): accept the `slots` token, invoke the deferred-setup seam headlessly, align the comparator with the slot runtime's player swap, and prove the slots boot headlessly — per the spec's slots-depth roadmap entry.

**Architecture (verified 2026-07-19 exploration):** The recorder already captures slots segments (zone 0x15, level schema) — the replay side is blocked in exactly three places: (1) `bonusStageTypeForToken` throws on `"slots"`; (2) `applyBonusStageEntry` never calls `onDeferredSetupComplete()` (the ONLY builder of `S3kSlotBonusStageRuntime`, which is proven headless-safe — pure Java + ROM data, existing unit suite boots it dozens of times); (3) **the player swap**: `bootstrap()` constructs a NEW `S3kSlotBonusPlayer`, swaps it into the sprite manager and focuses the camera on it, while `HeadlessTestFixture.sprite()` is a cached final field still pointing at the orphaned original — every sprite column would compare the WRONG object (the ROM's `$B000` slot player is what the recorder captured). Fix via a `comparedSprite()` seam on the replay base that the bonus base overrides to follow the CAMERA-FOCUSED sprite — ROM-faithful (the camera tracks the `$B000` object), uniform across all bonus types (gumball/pachinko focus the original player, so behavior is unchanged there), no type branch. Slot frame-driving needs NO new wiring: `LevelFrameStep` already calls `onFrameUpdate()` when `updateDuringLevelFrame()` is true, and the slot camera (custom tracking, default step suppressed) is read live by the comparator so camera columns stay meaningful. No rewind interaction (supportsRewind false for slots; headless replay never reaches `updateBonusStageMode`).

**Tech Stack:** Java 21 + JUnit 5 (Jupiter only). No lua changes (recorder v6.31 already handles zone 0x15).

## Global Constraints

- Spec: `docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md` (slots-depth = addition #4 + token acceptance + comparator alignment). S1/S2 are later plans. MVP red-allowed posture.
- Comparison-only invariant. No zone/route/frame carve-outs (the comparedSprite seam keys on camera focus — engine state — not on bonus type).
- JUnit 5 only; commit policy as prior plans (trailer block; src/main feat → `Changelog: updated` + CRLF-verified CHANGELOG.md; stage exact paths; every commit ends with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01LPPPMPSUQBgYpxpA82bad5`).
- Recordings absent: the slots replay test lands skip-if-missing on `src/test/resources/traces/s3k/bonus_slots/`; the boot smoke test runs TODAY.
- Guard awareness: `TestBuildToolingGuard` may react to `applyBonusStageEntry` changes (its registered baseline names the profile-gate line — verify the registered signal line is UNCHANGED by the edit; if the edit shifts it, re-register per convention). Test naming: the concrete slots replay test ends in `*TraceReplay.java` → its base `AbstractS3kBonusStageTraceReplayTest` is ALREADY registered in `TestTraceReplayInvariantGuard` (plan-b) — no new registration expected.

---

### Task 1: Token acceptance + deferred-setup call in `applyBonusStageEntry`

**Files:**
- Modify: `src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java`
- Test: extend `src/test/java/com/openggf/trace/replay/TestTraceReplaySessionBootstrapBonus.java`

**Interfaces:**
- Consumes: `bonusStageTypeForToken` (`:429-438`, currently throws on "slots"); `applyBonusStageEntry` (`:451-499`, live-order mirror: setActiveBonusStageProvider → onEnter → registerBonusStageAdapter → rings/HUD/unhide → pachinko inject); `BonusStageProvider.onDeferredSetupComplete()` (default no-op, `AbstractBonusStageCoordinator.java:57-59`; SLOT_MACHINE-guarded runtime build, `Sonic3kBonusStageCoordinator.java:76-90`); live call order precedent `GameLoop.java:3021-3039` (deferred-setup AFTER onEnter, post-title-card).
- Produces: `bonusStageTypeForToken("slots")` returns `BonusStageType.SLOT_MACHINE` (the throw remains for unknown/null); `applyBonusStageEntry` calls `provider.onDeferredSetupComplete()` **unconditionally, at the very END of the method (after the pachinko-inject block, which is a null-spawn no-op for slots)** — this genuinely matches the live order (rings/HUD/unhide happen at title-card prep, deferred setup fires AFTER the title card, `GameLoop.java:3034`), keeps the unhide loop operating on the pre-swap sprite set as live does, and makes the javadoc claim accurate. (No-op for gumball/pachinko by the base default `AbstractBonusStageCoordinator.java:56-59`; builds the slot runtime for SLOT_MACHINE, `Sonic3kBonusStageCoordinator.java:77-90`; NOT idempotent — exactly one call, and `applyBonusStageEntry` is already once-per-boot.) Javadoc updated: the method now mirrors the live sequence THROUGH deferred setup (spec addition #4).

- [ ] **Step 1:** Failing test additions: `bonusTypeMappingCoversGumballAndPachinko` gains `assertEquals(BonusStageType.SLOT_MACHINE, ...bonusStageTypeForToken("slots"))` and keeps the unknown/null throws (use a different bogus token, e.g. `"casino"`).
- [ ] **Step 2:** Implement both edits → green. Run `TestBuildToolingGuard` — verify the registered `s3k_bonus_stage` signal line is unchanged (the edits don't touch the profile gate); if the guard fires anyway, re-register per convention with justification.
- [ ] **Step 3:** CHANGELOG line `- Trace replay: slot-machine bonus segments accepted (deferred-setup seam wired).` + commit (feat, src/main → Changelog: updated).

---

### Task 2: Comparator alignment — the `comparedSprite()` seam

**Files:**
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Modify: `src/test/java/com/openggf/tests/trace/s3k/AbstractS3kBonusStageTraceReplayTest.java`
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kBonusComparedSpriteSeam.java` (new; unit-level, no ROM)

**Interfaces:**
- Consumes: the SEVEN `fixture.sprite()` reads in `AbstractTraceReplayTest` (verified enumeration — trust the grep over any stale refs): comparator reads at `:320` (non-S3K compare loop), `:481`/`:482` (S3K seeded-state comparison), `:611` (S3K checkpoint probe), `:620`/`:622` (S3K main compare — **slots replay runs the S3K path, so 481/482/611/620/622 are the load-bearing ones**), plus `:433` (`captureEngineSnapshot` — a BOOTSTRAP frame-0 read, not a comparator read; routing it through the hook is harmless but classify it as such). `captureEngineDiagnostics` takes the sprite as a PARAMETER — routing happens at its call sites, not inside it. `GameServices.camera().getFocusedSprite()` (`Camera.java:823`; the slot bootstrap focuses the camera on the swapped player, `S3kSlotBonusStageRuntime.java:99-101`; gumball/pachinko leave focus on the original player).
- Produces: a `protected AbstractPlayableSprite comparedSprite(HeadlessTestFixture fixture)` hook on `AbstractTraceReplayTest`, default `return fixture.sprite();`, with EVERY comparator sprite read routed through it (mechanical substitution — the default preserves byte-identical behavior for all existing tests). `AbstractS3kBonusStageTraceReplayTest` overrides it: return the camera-focused sprite when non-null (`getFocusedSprite()` already returns `AbstractPlayableSprite`), else fall back to `fixture.sprite()` — with a javadoc explaining the ROM-faithful rationale (the recorder captured `$B000`, which is the object the ROM camera tracks; the slot runtime swaps the tracked player, gumball/pachinko don't, so this is uniform across bonus kinds — engine-state-keyed, not type-keyed).

- [ ] **Step 1:** Failing unit test: subclass a minimal stub of the bonus base (or test the override method directly with a stubbed camera/fixture — read how `GameServices.camera()` is resolvable headlessly in unit context; if a full GameServices boot is too heavy for a unit test, test the override's selection logic through a small extracted package-private helper `selectComparedSprite(AbstractPlayableSprite focused, AbstractPlayableSprite fixtureSprite)` — pure function: returns focused when non-null playable, else fixture sprite).
- [ ] **Step 2:** Route ALL SEVEN reads through the hook (grep-verified — state the count in your report; the 6 comparator reads are mandatory, `:433` bootstrap read included for uniformity); default-hook behavior proves itself via the existing trace suites: run `TestTraceDataParsing` + one existing concrete replay test class (compilation + no behavior change on the level path) + the bonus replay tests (still SKIP).
- **Known seam bypass (documented, not fixed here):** `captureFirstSidekickState()`/`captureFirstSidekickCpuState()` do not route through the seam, and the slot runtime's `suppressCpuSidekicks()` removes all sidekicks — a team (Sonic+Tails) slots recording would show sidekick-column divergence. MVP red-allowed tolerates it; Task 4's README steers the slots recording to SONIC-SOLO to avoid the noise entirely.
- [ ] **Step 3:** Commit (test-only trailers).

---

### Task 3: Slots headless boot smoke test (runs today)

**Files:**
- Modify: `src/test/java/com/openggf/tests/TestS3kBonusStageHeadlessBoot.java` (add the slots method)

**Interfaces:**
- Consumes: the class's existing `performBonusStageEntry` mirror + fixture idioms (gumball/pachinko methods); `Sonic3kZoneIds.ZONE_SLOT_MACHINE` (0x15, `Sonic3kZoneIds.java:38`); the coordinator cast for `getActiveType()`; `((Sonic3kBonusStageCoordinator) provider)` accessors for the runtime (read the coordinator for the runtime getter the unit suite uses — `TestS3kSlotBonusStageRuntime`/`TestS3kSlotBonusStageCoordinator` idioms at `:44-76`).
- Produces: `slotsZoneBootsHeadlesslyWithRuntime()`: fixture `withZoneAndAct(0x15, 0)`, full entry sequence INCLUDING `onDeferredSetupComplete()` LAST (Task 1's placement — mirror `applyBonusStageEntry`'s exact order), assert active type SLOT_MACHINE via the coordinator cast, the slot runtime non-null + `isInitialized()` — **the runtime getter needs the CONCRETE `Sonic3kBonusStageCoordinator` cast (`activeSlotRuntime()`/the accessor the slot unit suite uses), not the `AbstractBonusStageCoordinator` cast the existing methods use**; the camera-focused sprite is NOT the fixture's original sprite (the swap happened — validates Task 2's premise); step 60 idle frames without exceptions; **"player alive" asserted on the CAMERA-FOCUSED sprite** (the fixture's cached original is orphaned post-swap and frozen — asserting on it is meaningless).

- [ ] **Step 1:** Write + run with ROM present → green or honest BLOCKED with the stack trace (a boot gap discovery is the task's purpose; do not mask).
- [ ] **Step 2:** Commit (test-only trailers, subject `test(s3k): slots headless boot smoke coverage`).

---

### Task 4: Skip-if-missing slots replay test + gate + docs

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/s3k/TestS3kSlotsBonusTraceReplay.java` (mirror the gumball concrete test: `@RequiresRom(SonicGame.SONIC_3K)`, `game()/zone()=0x15/act()=0/traceDirectory()=traces/s3k/bonus_slots`)
- Modify: `tools/bizhawk/README.md` (extend the bonus recording subsection: slots = 20–34 rings at the star post — remainder 0; movie naming `s3k-aiz-slots.bk2`; **record SONIC-SOLO** — the slot runtime suppresses sidekicks and the sidekick comparator columns bypass the sprite seam, so a team recording adds pure noise; the slots segment records under the level schema like gumball/pachinko)
- Modify: `docs/status/trace-frontier-log.md` (entry: slots replay accepted; test skips pending the recording; comparator swap-alignment seam landed)

- [ ] **Step 1:** Concrete test SKIPs (failed=0). README + frontier entries (line-ending-clean; ring-range accuracy per the verified selector: 20–34 → remainder 0 → SLOT_MACHINE).
- [ ] **Step 2:** Full-suite gate (detached + monitor): no NEW failures vs baseline (known flakes: geyser, wire-cage — isolated-pass verify if present).
- [ ] **Step 3:** Commit docs. Merge-time README release-log reminder stands.

## Plan-level notes

- The recorder needs NO changes: zone 0x15 already produces `s3k_bonus_stage` segments with `bonus_stage_type: "slots"` (plan-a state machine).
- Rewind: no interaction (supportsRewind false for slots; headless replay never reaches updateBonusStageMode) — verified, no work.
- The camera columns are meaningful under the slot runtime's custom tracking (comparator reads the live camera; the suppressed default step is exactly what the ROM does).
- Chain/visual integration inherits automatically: the walker/advancer treat slots as any bonus_stage segment; `bonusStageTypeForToken` was the only type-keyed gate.
