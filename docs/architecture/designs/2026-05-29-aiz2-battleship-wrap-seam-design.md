# AIZ2 Battleship Background Wrap Seam — Faithful Fix Design (Rev 2)

**Date:** 2026-05-29
**Status:** IMPLEMENTED THEN REVERTED — failed visual validation. See "Outcome" below.

> **Outcome (2026-05-29):** Rev 2 was implemented and reverted. Visual validation
> (running the engine) showed the **static background forest loses elements during
> the battleship/forest loop**. Root cause of the mis-fix: the empirical BG dump
> read columns 4–7 (`$200–$400`) as "empty filler" because they hold only block
> `0xbf` at one row — but `0xbf` is a **visible forest treeline band**, so the
> forest BG actually spans the full `$400`, not the first `$200`. Looping at
> `$200` (origin 0) therefore dropped the cols 4–7 forest band. The headless seam
> guard could not catch this: it asserted the looped window was forest-*only*
> (no filler), not forest-*complete* (no missing forest) — a near-tautology given
> the normalization. **Lessons for any reattempt:** (1) the AIZ2 forest period is
> not `$200`; do not treat sparse single-row blocks as filler — verify what each
> block renders. (2) This is a visual fix; a headless guard that the chosen
> mechanism satisfies by construction is not validation. Wire real visual
> validation (stable-retro AIZ2 reference vs engine, the `s3k-zone-validate`
> skill) before reattempting. The `$80` approximation + its
> `S3K_KNOWN_DISCREPANCIES` entry were restored.

**Original status:** Design (revised after composition investigation; pending re-review)
**Scope:** S3K Angel Island Zone Act 2 (Flying Battery battleship auto-scroll → end-boss approach)

> **Rev 2 note:** The original design (Rev 1) proposed making the BG tilemap
> *window* follow the camera and normalizing the source query with an
> origin-anchored modulo ("approach B"). A composition trace of the actual
> renderer/shader (recorded below) proved approach B is **wrong for this engine**
> — it would inject a `$200` background jump. This revision replaces it with a
> simpler, genuinely faithful fix grounded in the empirical AIZ2 BG layout.

## Problem

During the AIZ2 battleship auto-scroll the camera scrolls on rails and the
background is a looping forest strip. To keep coordinates bounded, the ROM
renormalizes ("wraps") the camera, both players, and active sequence objects
backward by a fixed distance once the camera reaches a wrap boundary:

| Phase | Wrap boundary | Wrap distance |
|-------|---------------|---------------|
| Bombing | `$4440` | `$200` |
| Post-bombing | `$46C0` | `$200` |

The engine matches the ROM during bombing (`$200`) but uses a **`$80`**
approximation post-bombing (`BATTLESHIP_WRAP_DIST_POST_BOMBING` in
`Sonic3kAIZEvents`), documented in `docs/S3K_KNOWN_DISCREPANCIES.md`. The goal is
to use the true ROM `$200` wrap with no visible background seam, and remove the
discrepancy entry.

Fidelity bar (chosen): **seamless-correct, not bit-exact** — keep the
ROM-observable state exact (the `$200` camera/object wrap, which drives physics,
object positions, and trace replay) and fix the engine-internal reason a seam
appears, by the cleanest engine-side means, without inventing a one-zone
framework.

## Empirical AIZ2 BG layout (measured)

A headless dump of the AIZ2 level-2 (BG) map:

- BG layer is **8 blocks = `$400` (1024px)** wide, 128px blocks, 10 rows tall.
- **The forest content is exactly the first `$200`** — block columns 0–3 hold the
  real sky/tree tiles (e.g. `e6,e5,ed,f4,fc,…`).
- **Columns 4–7 (`$200`–`$400`) are empty filler** — a single `0xbf` block at one
  row, otherwise zero.

So the meaningful forest is a `$200` strip at **origin 0**; the remaining `$200`
is empty filler.

## Renderer/shader composition (measured)

The background's on-screen horizontal sample position is
(`shader_parallax_bg.glsl`, driven from `LevelRenderer` and `LevelTilemapManager`):

```
fboX = mod( (gameX − bgTilemapBaseX) − hScrollThis , BGTextureWidth )
```

- `bgTilemapBaseX` — 16px-aligned BG window origin; set in
  `LevelManager.applyBackgroundTilemapWindowSelection()` from
  `floorDiv(getBgCameraX(),16)*16` **only when** `getBgCameraX() != Integer.MIN_VALUE`
  **and** `bgWrapsHorizontally()`. `SwScrlAiz` does **not** override
  `getBgCameraX()`, so for AIZ it is `Integer.MIN_VALUE` and `bgTilemapBaseX`
  stays **0**.
- `hScrollThis` — the per-line BG HScroll word. During the battleship loop AIZ
  derives it from **`battleshipSmoothScrollX`** (continuous, never wraps), by
  deliberate existing design (`SwScrlAiz.java:179-185`, comment: "use the smooth
  (non-wrapping) X … to avoid visible background jumps on camera wrap-back").
- `BGTextureWidth` — the built BG cache width. With `bgWrap == false` (AIZ today)
  it is the **full BG layer width `$400`**; with `bgWrap == true` it is the BG
  period (`currentBgPeriodWidth`, default `VDP_BG_PLANE_WIDTH_PX = $200`).

`bgTilemapBaseX` and `hScrollThis` compose by **subtraction**, and stay coherent
only if both derive from the same world reference.

## Corrected Root-Cause Model

Today, for AIZ: `bgTilemapBaseX = 0` (fixed), `hScrollThis` scrolls continuously
from `smoothScrollX`, and `bgWrap == false` so `BGTextureWidth = $400`. The BG
therefore scrolls continuously and **wraps at `$400`** — i.e. the forest
(`$0–$200`) scrolls past, then the **empty filler (`$200–$400`) scrolls into
view**, then it repeats. That exposed empty region is the seam; the `$80` wrap
limits camera/object drift so it stays just off-screen.

Note the prior "HInt screen split hides a seam" claim was wrong, and so was Rev 1's
"jumping window vs continuous deform phase break." The window does **not** jump
today (it is pinned at 0). The seam is simply the **empty half of the BG layout
becoming visible because the BG wraps at `$400` instead of the `$200` forest
period.**

### Why Rev 1's approach B was wrong

Approach B made `bgTilemapBaseX` follow `cameraX` (jumping `$200` on
renormalization) and normalized the source query. But `hScrollThis` is derived
from `smoothScrollX` (continuous). In `fboX = mod((gameX − bgTilemapBaseX) −
hScrollThis, …)`, at the wrap frame `−bgTilemapBaseX` would jump `$200` while
`−hScrollThis` would not → **the background would visibly jump `$200`.** Approach
B introduces a seam rather than removing one. (Its proposed seam guard — "base
jumps `$200`, normalized columns identical" — would have passed by construction
while the screen jumped.)

## The Fix

1. **Restore the ROM `$200` post-bombing wrap.** Remove
   `BATTLESHIP_WRAP_DIST_POST_BOMBING` (`$80`) so the post-bombing camera/object
   wrap subtracts the ROM `$200` at `$46C0` (the bombing-phase `$200` is
   unchanged). This is the physics/trace-faithful half and is independent of the
   BG change.

2. **Loop only the `$200` forest during the battleship loop.** Make the AIZ2 BG
   wrap at the forest period (`$200`) instead of the full `$400`, so the empty
   filler (`$200–$400`) is never built or shown and the forest repeats. Achieved
   by enabling the existing wrapped-BG build path for AIZ **only** while the loop
   is active:
   - Add `Sonic3kZoneFeatureProvider.bgWrapsHorizontally()` an AIZ clause gated on
     a new narrow `isBattleshipForestLoopActive()` predicate
     (`battleshipAutoScrollActive && battleshipWrapX == BATTLESHIP_WRAP_X_POST_BOMBING`),
     mirroring the existing phase-scoped `isCnzBossBackgroundWindowActive`
     precedent. This flips `bgWrap` true → `BGTextureWidth` becomes the period
     (`$200`) and the BG cache is built from the first `$200` of source (the
     forest); `bgTilemapBaseX` stays `0` and `bgXQueryOffset = 0`.
   - Ensure `currentBgPeriodWidth` is `$200` during the loop. It is already the
     default; `applyBackgroundTilemapWindowSelection` sets it from
     `parallaxManager.getBgPeriodWidth()` (default `$200`). If a robustness
     guard is wanted, set it explicitly to `$200` during the loop.

   **No** `getBgCameraX()` override, **no** source-query normalization, **no**
   loop origin — the window stays at base 0 and the existing continuous
   `smoothScrollX` deform does the scrolling, wrapping seamlessly at `$200`. This
   matches the ROM's `$200` Plane B loop.

The forest's col-3 → col-0 wrap has the same gentle gradient reset the ROM shows
(the ROM loops the identical `$200`), so it is faithful by construction.

## Carve-Out Compliance

No shared physics/sidekick/trace code branches on zone id. Camera/object wrap is
`$200` for all phases (no physics branch). BG window selection is a rendering
concern owned by the zone scroll/event layer, exactly like the existing MGZ
state-8 and CNZ-boss cases; the AIZ event exposes a semantic predicate
(`isBattleshipForestLoopActive()`) that shared BG code consumes — it does not test
`zone == AIZ`.

## Testing

- **BG-loop content guard** (`TestS3kAiz2…`, ROM-gated): during the post-bombing
  forest loop, assert the built BG tilemap (a) has period/`BGTextureWidth` `$200`
  and (b) contains **only forest tiles — no empty filler** (no `0xbf`-only/empty
  column from the `$200–$400` half) in the visible BG window. This directly proves
  the empty region is excluded (the actual defect), and a raw `getBlockAtPosition`
  check is *insufficient* (it wraps at full layer width and bypasses the
  cache/period path). Compare the built window before and after a `$200` camera
  renormalization and assert it is unchanged **and** forest-only. (Note: unlike
  Rev 1, do **not** assert `bgTilemapBaseX` jumps — it stays 0 by design.)
- **`bgWrapsHorizontally()` activation:** true for AIZ only during
  `isBattleshipForestLoopActive()`, false otherwise; and verify
  `zoneRuntimeRequiresFullWidthBgTilemap()` (AIZ *intro* full-width path) does not
  suppress the loop wrap.
- **`TestS3kAiz2SidekickBoundsSync`:** with `$200` restored, the post-bombing
  camera `maxX` lands at the true ROM `0x44C0` (`$46C0 − $200`); reinstate the
  exact-value assertion, keep the sidekick-bound-mirror assertion.
- **Trace acceptance (not strict neutrality):** restoring `$200` changes
  coordinates, so the AIZ first-mismatch frame may move. Acceptance: **no new
  earlier mismatch than the baseline (currently f8941) unless explained by the
  intended `$200` wrap change**; update `docs/status/trace-frontier-log.md` if it moves.
- **Full suite:** `mvn clean test` green; ArchUnit unaffected.
- **Visual validation (recommended follow-up):** the on-screen result cannot be
  proven headlessly. A stable-retro AIZ2 reference vs engine screenshot (the
  `s3k-zone-validate` skill) or a manual run should confirm the forest loops with
  no empty gap before this is considered fully verified.

## Risks & Mitigations

- **Forest not the first `$200` / period not `$200`.** Measured: forest is cols
  0–3 (`$200`), filler beyond; period default is `$200`. Low risk; the BG-loop
  content guard catches a wrong window.
- **`bgWrap == true` not reached during the loop.** Confirm
  `zoneRuntimeRequiresFullWidthBgTilemap()` is false in the loop phase (it is the
  AIZ intro path); the activation test asserts the loop window is forest-only,
  which fails if the wrap is suppressed.
- **Other AIZ phases regress.** The clause is gated strictly on the narrow
  `isBattleshipForestLoopActive()`; intro/fire/normal keep `bgWrap == false`
  (full-width) as today.
- **Visual-only validation gap.** The headless guard proves the empty region is
  excluded and the window is stable across the wrap, but not subjective
  correctness; mitigated by the recommended visual-validation follow-up.

## Out of Scope

- Replicating the ROM's exact Plane B nametable-fill bytes.
- A general reusable "looping background" framework (AIZ2 is the only camera-wrap
  loop in the slice).
- Any change to the bombing-phase wrap (already ROM-correct at `$200`).
- The Rev 1 machinery: `getBgCameraX()` override, origin-anchored source-query
  normalization, `getForestLoopBgOrigin()`, and `forestLoopQueryOffset()` —
  removed as wrong for this engine.
