# AIZ flame-curtain outro design

Status: implementation design for review

## Problem

The AIZ fire curtain now remains continuous through the AIZ1-to-AIZ2 handoff,
but its ending is abrupt. The curtain covers the whole screen until the
engine changes from `AIZ2_WAIT_FIRE` to `AIZ2_BG_REDRAW`; on the next rendered
frame it emits no fire tiles, so the AIZ2 scene appears without the ROM's
outro/reveal.

The solution must satisfy both uses of the engine:

* ordinary play must show the same gradual curtain release as the ROM;
* trace replay must retain the recent continuity fix and must not gain a new
  gameplay, load-timing, or trace-progress regression.

No trace row may be used to decide the visual state. The renderer must consume
the existing ROM-derived event state only.

## Evidence and source of truth

The ROM routines define the handoff as follows:

1. `AIZ2BGE_FireRedraw` continues `AIZ1_FireRise`,
   `AIZTrans_WavyFlame`, and the plane redraw until its delayed row counter
   is exhausted (`docs/skdisasm/sonic3k.asm:105036-105050`).
2. `AIZ2BGE_WaitFire` continues the rise and wave effect. Before
   `Events_bg+$00` is latched it waits for
   `(Camera_Y_pos_BG_copy & $7F)` to enter `[$20,$30)`, then writes
   `$180 + residue` and latches the flag (`docs/skdisasm/sonic3k.asm:105052-105078`).
3. After that latch, every pass performs `Draw_TileRow` and compares the
   unsigned background-copy word with `$310`. The first pass at or beyond
   `$310` releases the transition, seeds the delayed background redraw, and
   advances to `AIZ2BGE_BGRedraw`
   (`docs/skdisasm/sonic3k.asm:105079-105107`).
4. `AIZ2BGE_BGRedraw` calls `AIZ2_Deform` and drains the delayed plane redraw;
   it does not call `AIZ1_FireRise`, `AIZTrans_WavyFlame`, or a fire draw
   (`docs/skdisasm/sonic3k.asm:105128-105138`).
5. The rise itself is ROM-owned: the speed ramps by `$280`, caps at `$A000`,
   and advances the fixed background position by `speed << 4`
   (`docs/skdisasm/s3.asm:70383-70400`).

The original ROM was also run read-only with the committed AIZ BK2 and locked-on
ROM. Around BK2 frames 6030-6052, while the ROM is still in `WaitFire`, the
fire recedes from the bottom of the screen as the effective background position
approaches `$310`. At BK2 frame 6053 the routine enters `BGRedraw`; the normal
AIZ2 scene is already visible and the fire curtain is no longer present. This
confirms that the outro belongs to the latched `WaitFire` interval, not to an
additional artificial delay after the state transition.

The current implementation has the corresponding state split, but its
wrapping decision is phase-only:

* `Sonic3kAIZEvents.FireSequencePhase.wrapFireTiles()` returns `true` for all
  of `AIZ2_FIRE_REDRAW` and `AIZ2_WAIT_FIRE`;
* `AizFireCurtainRenderer.wrapFireTileY()` repeats the dense body once a tile
  row reaches `$250` while wrapping is enabled;
* the source position is already at or beyond `$310` when the event enters
  `AIZ2_BG_REDRAW`, where wrapping is disabled, so the cached renderer rejects
  every row and emits an empty plan.

The ROM-backed renderer diagnostic reproduced that exact shape: seven
`AIZ2_BG_REDRAW` frames were active but emitted no overlay pattern. The engine
capture showed full-screen fire immediately followed by the normal scene.

## Design

Make the wrapping decision depend on the ROM-owned `WaitFire` latch, while
leaving event progression unchanged:

* `AIZ1_FIRE_TRANSITION`, `AIZ1_FIRE_REFRESH`, `AIZ1_FINISH`, and
  `AIZ2_FIRE_REDRAW` keep their current wrapping behavior. This preserves the
  continuity fix for the carried fire plane and covers timing variations before
  the release latch.
* `AIZ2_WAIT_FIRE` wraps only while `act2WaitFireDrawActive` is false. This is
  exactly the pre-latch branch guarded by `tst.w (Events_bg+$00)` in the ROM.
* Once `act2WaitFireDrawActive` becomes true, wrapping is disabled. The
  existing cached-descriptor renderer then draws only rows in the real fire
  interval `[0x100, 0x310)`. As `Camera_Y_pos_BG_copy` rises from the ROM's
  `$180 + residue` re-seat to `$310`, the visible fire band shrinks naturally
  from the bottom, producing the outro.
* `AIZ2_BG_REDRAW` remains unwrapped and emits no fire overlay. Its seven-pass
  duration, background redraw, player unlock, and post-fire haze timing remain
  unchanged.

The state-to-render mapping will therefore be expressed as a semantic method
that accepts the current `act2WaitFireDrawActive` value, rather than by adding a
new frame counter, zone exception, trace condition, or artificial delay. The
existing `usesFireScrollMode()` behavior remains unchanged: the ROM continues
to run `AIZTrans_WavyFlame` during the latched `WaitFire` tail, and the normal
scroll handler must continue to consume that mode until release.

## Invariants

The change must preserve these invariants:

1. `runAiz2WaitFire()` and `updateAct2Continuation()` produce the same state,
   values, phase transitions, art admissions, camera bounds, and frame counts.
2. `FireCurtainRenderState.sourceWorldY`, wave offsets, stage, coverage, and
   overlay tile identity remain unchanged by this fix. The existing ROM-derived
   source-strip switch to `$0200` when the `WaitFire` latch becomes active must
   continue to work; this change only alters the derived wrapping bit after
   that switch.
3. The pre-latch AIZ2 continuation still renders cached fire when the carried
   position is beyond `$310`.
4. The latched tail never maps rows at or beyond `$310` back into the dense
   body, so it cannot refill the bottom of the screen.
5. Trace replay remains comparison-only and no trace metadata or fixture is
   changed.
6. The implementation continues to use the ROM-backed descriptor cache and
   fails closed if that cache is unavailable; it must not synthesize art.

## Rejected alternatives

### Add an artificial post-release delay

Rejected. The ROM already supplies the outro duration through the rise from
the `$180 + residue` latch to `$310`. Adding a delay would keep a full-screen
curtain after the ROM has begun revealing the scene and would make the visual
timing absorb an unrelated approximation.

### Keep wrapping through `AIZ2_BG_REDRAW`

Rejected. That phase does not run the fire-rise or fire-plane routines in the
ROM. Wrapping there would prolong the curtain past its ROM release and conceal
the actual state boundary.

### Add a renderer-only fade or alpha effect

Rejected. The original effect is a tile-plane scroll/reveal, not a fade. A
fade would also introduce behavior that cannot be derived from the ROM's
tile-row scheduling.

### Key the behavior to a trace frame, zone name, or route

Rejected by the project trace and per-game rule constraints. The existing
`act2WaitFireDrawActive` flag is the owning semantic predicate and is already
captured in event state/snapshots.

## Verification strategy

The implementation will be test-first:

* split the existing continuation test into explicit pre-latch and latched
  cases. The latched `AIZ2_WAIT_FIRE` case uses a source position just below
  `$310`; it must report `wrapFireTiles() == false` and emit only the finite
  trailing rows. The pre-latch case continues to assert wrapping and
  non-empty cached output for both `AIZ2_FIRE_REDRAW` and unlatched
  `AIZ2_WAIT_FIRE`;
* add/update an event-state assertion that the latch changes only the render
  wrapping decision in this fix: the phase and `sourceWorldY` remain governed
  by the existing event progression, while the existing `$0200` `sourceWorldX`
  switch remains intact and covered;
* run the focused renderer/event tests through `tools/testing/test-session.sh`;
* strengthen the ROM-backed AIZ curtain diagnostic with assertions:
  latched `AIZ2_WAIT_FIRE` must emit overlay patterns during its release tail,
  while `AIZ2_BG_REDRAW` must remain overlay-free. Printed stage statistics
  remain useful evidence but are not the only pass condition;
* capture the AIZ trace around the release window and inspect consecutive
  frames for a continuous fire tail followed by the normal scene;
* run the relevant AIZ/S3K trace replay tests and the required full baseline,
  development-worktree, merged-workspace, and push verification mandated by
  `AGENTS.md`.

No committed trace payload or runtime asset is required for this visual fix.
