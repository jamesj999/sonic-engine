# Plane-B Nametable Ring Adoption Qualification — Design Spec

> **Status:** Backlog task; qualification-first. No additional zone is approved for
> migration by this document.
> **For agentic workers:** use `superpowers:executing-plans` or
> `superpowers:subagent-driven-development` when this task is scheduled. Complete the
> evidence gate for a candidate before changing its production scroll mode.

**Goal:** Determine whether the persistent 64×32 Plane-B nametable introduced for the
WFZ ending provides a genuine parity or rendering-cost benefit in LBZ2 or GHZ, and adopt
it only for candidates that pass an explicit evidence gate.

**Non-goals:** Broadly migrate zones to the new mode; replace S3K `Draw_BG` semantics
with a single-camera approximation; refactor working AIZ, HCZ, CNZ, MGZ, ICZ, FBZ, SOZ,
LRZ, SSZ, SBZ, or FZ backgrounds without a separately demonstrated defect or cost.

---

## 1. Decision

This is a **qualification task**, not an adoption task with a predetermined outcome.

- **LBZ2 is a parity candidate.** Its Death Egg launch mutates background layout rows,
  and the current engine immediately rebuilds Plane B. The ROM updates layout memory and
  continues through incremental `Draw_TileRow` work. This is plausibly the same class of
  retained-plane timing error exposed by the WFZ ending, but it has not yet been proven
  visible or descriptor-observable.
- **GHZ is an efficiency candidate.** `SwScrlGhz.getBgPeriodWidth()` grows the rendered
  background period from 512 px to as much as 8192 px to keep static-window seams outside
  independently scrolling cloud bands. The hardware instead retains and updates a 512 px
  nametable ring. A 512 px ring could materially reduce background FBO width and tile work,
  but it must first reproduce all GHZ bands pixel-for-pixel.
- **No other zone has sufficient evidence today.** AIZ is already visually validated for
  its complicated fire/battleship transitions. Several S3K zones contain horizontal-wrap
  or partial-draw workarounds, but that alone is not a demonstrated user-visible or
  measurable benefit.

Each candidate is independent. A candidate that fails or cannot prove its gate receives
an evidence note and **no production change**. It must not be migrated merely to reuse the
new mechanism.

---

## 2. Existing capability and its limit

`BgTilemapUpdateMode.PERSISTENT_NAMETABLE_64X32` currently models one retained 64×32 tile
Plane-B ring. `LevelTilemapManager` follows a single aligned background X/Y origin,
updates entering rows and columns for one-tile camera steps, and reseeds after jumps or
invalidation. Only `SwScrlWfz` opts into it.

That is sufficient for WFZ, but it is not yet a general implementation of the S3K
`Draw_BG` family. In particular, the existing mode does not express:

- multiple independently tracked background camera lanes;
- event-owned row or column draw commands with explicit source coordinates;
- a layout-RAM mutation that intentionally does not redraw every retained descriptor;
- per-band source selection for line-scrolled backgrounds.

Therefore LBZ2 must not simply return the existing persistent mode. GHZ may use the
existing camera-driven ring only if its qualification capture proves that all cloud,
mountain, hill, and water bands sample the correct retained descriptors.

---

## 3. Phase 0 — establish reproducible baselines

- [ ] Record the exact commit, ROM hash, trace/input source, viewport, and capture flags
  for every comparison.
- [ ] Add diagnostic counters that can be read headlessly without changing rendering:
  selected Plane-B mode, descriptor dimensions, requested/rendered background period,
  full rebuild count, incremental row/column count, and bytes submitted for the background
  tile/FBO path.
- [ ] Keep all reference trace data read-only. Do not synchronize engine state from a
  trace to manufacture parity.
- [ ] Store the qualification results in a short report under `docs/` even if neither
  candidate passes. The report must include the rejected hypotheses as well as any
  accepted migration.

Temporary probes may be used during investigation, but retained metrics should be small,
generic renderer diagnostics or test-only accessors rather than zone-specific logging.

---

## 4. Candidate A — LBZ2 Death Egg background timing

### 4.1 Hypothesis

`Sonic3kLBZEvents.applyDeathEggSmallBackgroundReframe()` copies background map rows 2/3
into rows 0/1 through the normal mutation surface. That path invalidates and rebuilds the
current background tilemap immediately. In the ROM, `LBZ2BGE_Normal` changes background
layout words and proceeds through incremental tile-row drawing. Retained Plane-B cells
outside those draws should keep their previous descriptors until the ROM draw schedule
replaces them.

The existing `TestSonic3kLbzLaunchSignals` verifies the layout-RAM row copies but does not
verify when those changes become visible in Plane B.

### 4.2 Qualification gate

- [ ] Capture a stable-retro reference and an engine headless capture spanning the small
  Death Egg reframe and launch transition.
- [ ] At each relevant frame, compare both the final image and the logical 64×32 Plane-B
  descriptors/source coordinates. A screenshot alone is insufficient if foreground art
  temporarily covers the changed cells.
- [ ] Identify the ROM row/column draw commands that make the new layout visible and map
  them to frame boundaries.
- [ ] Add a failing regression that demonstrates an actual early/late descriptor update
  or visible pixel difference in the current engine.

**Pass:** a repeatable current-engine mismatch is attributable to premature full rebuild,
and retained row/column updates reproduce the ROM timing.

**Fail:** the current immediate rebuild is observationally identical throughout the
transition, or the difference is unrelated to Plane-B retention. On failure, document the
result and make no LBZ production change.

### 4.3 Implementation allowed only after PASS

Add a generic Plane-B update-plan seam owned by the scroll/event path. The minimal command
vocabulary should be:

- `FULL_REFRESH(originX, originY)` for explicit reseeding;
- `DRAW_ROW(destinationRow, sourceX, sourceY, width)`;
- `DRAW_COLUMN(destinationColumn, sourceX, sourceY, height)`.

Commands must describe nametable work; they must not embed LBZ identifiers or frame
numbers. LBZ layout edits continue through `ZoneLayoutMutationPipeline`, but the qualifying
event needs a way to edit layout RAM without implicitly promising an immediate full Plane-B
redraw. Rewind capture must include any queued commands and retained-ring origin/state.

### 4.4 Acceptance after implementation

- [ ] The new regression passes at the descriptor and image levels.
- [ ] Existing LBZ launch-signal, scroll-handler, layout-mutation, rewind, and complete-run
  trace tests remain green.
- [ ] A lossless headless video of the launch transition matches the stable-retro event
  order with no newly exposed wrap seam.
- [ ] The implementation contains no zone/route/frame carve-out in shared renderer code.

---

## 5. Candidate B — GHZ background period cost

### 5.1 Hypothesis

GHZ uses one Plane-B layout with several line-scroll bands. Its cloud offsets advance at
different per-frame rates while the mountain base follows `bg3X`. Because the engine uses
a static background window, `SwScrlGhz.getBgPeriodWidth()` expands the background render
period in powers of two from 512 to 8192 px as the spread grows. A correct retained 512 px
nametable ring should keep adjacent map columns adjacent at the wrap and avoid that widening.

### 5.2 Qualification gate

- [ ] Capture representative GHZ act routes long enough to cross the 512, 1024, 2048,
  4096, and 8192 px requested-period thresholds, including stationary-camera cloud motion
  and normal high-speed traversal.
- [ ] Record peak and frame-weighted background FBO width, tile-pass pixels, descriptor
  upload bytes, and background build/update time for the current implementation and a
  test-only ring prototype.
- [ ] Compare lossless images at threshold crossings, camera reversals, vertical camera
  movement, and 16-bit cloud-counter wrap. All visible bands must remain pixel-identical;
  trace state must remain identical because this is a rendering-only change.

**Pass:** the ring prototype is pixel-identical for the qualification corpus and reduces
at least one directly measured background cost by **50% or more** on the long route without
materially increasing another background cost or total frame time.

The primary expected win is peak background render width dropping from the observed wide
period to 512 px. Report actual measurements; the source-level 8192 px cap is not itself a
benchmark result.

**Fail:** any unexplained pixel mismatch, trace regression, or less than 50% measured
benefit. On failure, retain the existing period-width workaround and document why.

### 5.3 Implementation allowed only after PASS

Prefer the existing camera-driven persistent mode if it fully models the captured GHZ
behavior. Extend the update-plan interface from Candidate A only if the evidence shows that
one ring origin cannot correctly feed all line-scroll bands. Do not add a GHZ-only renderer
or special-case cloud counters in `LevelTilemapManager`/`LevelRenderer`.

After adoption, remove or narrow `SwScrlGhz.getBgPeriodWidth()` only to the extent made
obsolete by the passing implementation. Preserve deterministic cloud-offset derivation
from the frame counter and rewind behavior.

### 5.4 Acceptance after implementation

- [ ] `SwScrlGhzTest` remains green and gains coverage for ring behavior at period
  thresholds and counter wrap.
- [ ] All available GHZ trace replays remain green.
- [ ] Lossless before/after headless captures are pixel-identical across the qualification
  corpus.
- [ ] The checked-in evidence report contains raw metric summaries and the calculated
  percentage improvement.

---

## 6. Explicitly deferred zones

The following observations are leads, not justification for migration:

- MGZ, ICZ, phase-scoped HCZ2, and the CNZ boss use horizontal-wrap or wide-background
  policies that approximate VDP behavior.
- AIZ, FBZ, SOZ, LRZ, and SSZ contain multi-lane or partial background draw behavior in the
  disassembly.
- S1 SBZ/FZ may have static-window rendering costs but no demonstrated defect or material
  cost in the current engine.

Do not include these zones in this task. A future candidate must begin with its own visible
parity defect or recorded cost profile, use the same pass/fail discipline, and be rejected
when the benefit is merely architectural tidiness.

---

## 7. Completion criteria

This task is complete when both LBZ2 and GHZ have a written qualification result. It is a
valid successful outcome for one or both candidates to be rejected with no production
change.

If a candidate passes, completion additionally requires its focused tests, relevant full
trace suite, rewind tests, lossless screenshots/video, and metrics report to pass. Any
shared Plane-B extension must remain opt-in and leave all other zones on
`STATIC_WINDOW` by default.
