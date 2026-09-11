# Design: Widescreen Support

## Problem

OpenGGF renders at the Mega Drive native gameplay resolution of 320x224 pixels
(H40 mode), upscaled with integer scaling and letterboxing to the display
window. Users with modern 16:9, 16:10, and ultrawide monitors see large pillarbox
bars and a play area that does not use their screen. There is currently no way to
widen the gameplay view to a modern aspect ratio.

This is an **engine extension**, not a parity feature. The native pipeline
reproduces the ROM pixel-for-pixel, including the 320px-wide object spawn/despawn
window and camera scroll behavior. Any widescreen view is wider than the ROM ever
rendered, so it deliberately diverges from hardware behavior.

## Goals

- Allow the in-level gameplay view to render at wider aspect ratios while keeping
  the vertical resolution fixed at 224.
- Expose a small set of monitor-matched aspect presets through config.
- Keep `NATIVE_4_3` (320x224) behaving exactly as today, as both default and
  safety fallback.
- Drive all widescreen-affected logic from the configured width (via
  `camera.getWidth()` / config), never from hardcoded `320` literals.
- Make the camera scroll feel on wide screens configurable.
- Keep full-screen UI surfaces and scripted set-pieces correct by pillarboxing
  them by default, deferring the work of widening them to later phases.

## Non-Goals

- **No ROM/physics parity in widescreen.** Widening the view changes which
  objects are loaded and visible; this is accepted and intentional. The
  parity-critical native path is preserved only at `NATIVE_4_3`. One concrete,
  deliberate divergence — widening the ROM-cited `+$128` player right-boundary — is
  documented in "Parity Divergence: Right-Boundary Widening" below.
- No taller-than-224 or otherwise non-224 vertical modes in this work.
- No widening of the S2 special-stage track renderer; special stages stay
  pillarboxed permanently.
- No changes to the trace-replay comparison invariant. Trace replay tests run at
  `NATIVE_4_3` and are unaffected.

## Resolution Model

Height is **fixed at 224**. Width is data-driven through a new aspect preset enum.

**Width invariant: every preset width must be a multiple of 16.** Tile rendering
needs ×8 alignment (8px patterns), but the per-16px vertical-scroll column system
(`VScrollBuffer`, parallax column arrays) needs ×16 alignment. Requiring ×16 for
all presets avoids any partial trailing column. As defense-in-depth for any future
non-aligned width, the column count is defined as `ceil(width / 16)` and the
trailing column covers the remaining 8px; preset widths never exercise this path.

| Preset | Width | Cols (16px) | Actual ratio | Notes |
|--------|-------|-------------|--------------|-------|
| `NATIVE_4_3` | 320 | 20 | 1.43 | Current behavior; default and fallback |
| `WIDE_16_10` | 352 | 22 | 1.57 | Closest ×16 to 16:10 (exact = 358.4); gentle widescreen |
| `WIDE_16_9` | 400 | 25 | 1.79 | ~16:9 (exact = 398.2); primary target |
| `ULTRA_21_9` | 528 | 33 | 2.36 | ~21:9 (exact = 530.7) |
| `SUPER_32_9` | 800 | 50 | 3.57 | Super-ultrawide; best-effort |

### Display window sizing

`SCREEN_HEIGHT_PIXELS` stays 224; only `SCREEN_WIDTH_PIXELS` changes per preset.
But the **display window** (`SCREEN_WIDTH` / `SCREEN_HEIGHT`) must also be derived
from the preset, or widescreen presets regress to a smaller window than today.

`Engine.snapWindowToIntegerScale()` keys integer scaling off `realWidth`/
`realHeight`. With the current static defaults (`SCREEN_WIDTH = 640`,
`SCREEN_HEIGHT = 448` — i.e. 2× of 320×224), a `WIDE_16_9` 400×224 native floors
to `min(640/400, 448/224) = 1×` and snaps the window down to 400×224 — smaller
than today's 640×448.

**Policy:** window sizing is gated by the explicit `DISPLAY_WINDOW_AUTOSIZE` flag
(default `true`) — **not** by inspecting whether `SCREEN_WIDTH`/`SCREEN_HEIGHT`
differ from any default. Key-presence cannot signal intent, because existing
`config.json` files already ship the legacy `640`/`448` values; a "differs from
default" heuristic is also fragile once the widescreen default changes. The
explicit flag removes the ambiguity:

- **`DISPLAY_WINDOW_AUTOSIZE = true` (default):** on preset resolution the window
  is derived at the existing **2× baseline** — `SCREEN_WIDTH = 2 × presetWidth`,
  `SCREEN_HEIGHT = 448` — and the resolved values are written back to
  `SCREEN_WIDTH`/`SCREEN_HEIGHT`, overwriting any legacy `640`/`448`. So
  `WIDE_16_9` → 800×448, `ULTRA_21_9` → 1056×448, `SUPER_32_9` → 1600×448
  (clamped to monitor by the existing `maxScale` logic). Vertical window size stays
  identical to today; horizontal scales proportionally. The writeback is
  **in-memory only** (the resolved config object); it does not rewrite the user's
  `config.json` on disk.
- **`DISPLAY_WINDOW_AUTOSIZE = false`:** `SCREEN_WIDTH`/`SCREEN_HEIGHT` are used
  verbatim and never overwritten by preset derivation — the opt-out for users who
  want a fixed window size.

Either way the window continues to integer-scale and letterbox in
`Engine.reshape()` as today.

## Configuration

Add two `SonicConfiguration` entries:

| Key | Type | Default | Purpose |
|-----|------|---------|---------|
| `DISPLAY_ASPECT` | string (enum) | `NATIVE_4_3` | Selected aspect preset; resolves to native pixel width |
| `WIDESCREEN_DEADZONE_MODE` | string (enum) | `PROPORTIONAL` | Camera horizontal deadzone behavior on wide screens |
| `DISPLAY_WINDOW_AUTOSIZE` | boolean | `true` | When true, the display window is derived from the preset; when false, `SCREEN_WIDTH`/`SCREEN_HEIGHT` are used verbatim |

`DISPLAY_ASPECT` accepts known enum names case-insensitively and falls back to
`NATIVE_4_3` with a warning on invalid values. On load, `SCREEN_WIDTH_PIXELS` is
resolved from the preset table; an explicitly configured `SCREEN_WIDTH_PIXELS`
that disagrees with the preset is overridden by the preset (the preset is the
source of truth) and a warning is logged.

`WIDESCREEN_DEADZONE_MODE` values:

| Mode | Behavior |
|------|----------|
| `CENTER_SCALED` | ROM's fixed 16px-wide deadzone, re-centered at `width/2`. Faithful scroll feel, wider view only. |
| `PROPORTIONAL` | Deadzone half-width scales with `width/320`, centered at `width/2`. Looser, modern roam. Default. |

Both modes keep the deadzone centered horizontally and leave the vertical
(Y-axis) scroll logic untouched. The enum leaves room for a future `FIXED_NATIVE`
or similar mode without further schema changes.

## Affected Subsystems

The following are the points where 320 (or its derivatives) is currently baked in
and must become width-driven. File references reflect the current tree and may
drift; treat them as starting points.

### Tier 1 — Core (required for any widescreen)

1. **Config plumbing** — `SonicConfiguration.java`,
   `SonicConfigurationService.java`, `config.json`. Add `DISPLAY_ASPECT` and
   `WIDESCREEN_DEADZONE_MODE`; resolve preset -> `SCREEN_WIDTH_PIXELS`.

2. **Placement / spawn windowing (all managers)** — every
   `AbstractPlacementManager` (`com.openggf.level.spawn`) subclass bakes a
   320-derived **load-ahead** distance, and each is a distinct stream of visible
   gameplay entities. Fixing only `ObjectManager` would still ship edge pop-in for
   rings and bumpers. The base ctor takes two **separate** params,
   `(spawns, loadAhead, unloadBehind)`:
   - `ObjectManager.Placement` — `LOAD_AHEAD = 0x280` (640), `UNLOAD_BEHIND = 0x80`.
   - `RingManager` (`level.rings.RingManager`) — `LOAD_AHEAD = 0x280` (640),
     `UNLOAD_BEHIND = 0x300`.
   - `CNZBumperManager` (`game.sonic2.bumpers.CNZBumperManager`) —
     `LOAD_AHEAD = 640`, `UNLOAD_BEHIND = 768`.

   **The width term lives only in `loadAhead`.** `unloadBehind` differs per manager
   (`0x80` / `0x300` / `768`) and is **not** width-derived — leave it per-subclass.
   The `640`/`0x280` decomposes as `lead + width + trail` (320 → 640); the exact
   `128 + 320 + 192` split is an interpretation, not disassembly-cited — only the
   `+width` delta is load-bearing, and native stays `640` regardless of how
   lead/trail split.

   **Preferred fix: derive `loadAhead` in the shared `AbstractPlacementManager`
   base** as `lead + width + trail` from the configured width, so all subclasses
   inherit correct windowing; subclasses pass only their `lead`/`trail` and their
   own `unloadBehind`. At `NATIVE_4_3` `loadAhead` must equal the legacy `0x280`
   (640) exactly. The `ObjectManager.java:3736` teleport catch-up guard
   (`abs(cameraX - lastCameraX) > (LOAD_AHEAD + UNLOAD_BEHIND)`, = 768 today) auto-
   scales since it reads the same derived constants. Audit for any other
   `AbstractPlacementManager` subclasses and bring them under the same derivation.

3. **Camera scroll, snap, and horizontal bounds** — `Camera.java` has **two
   distinct** 320-derived horizontal literals, both in scope:
   - **Scroll band** (`Camera.java` ~line 334): the `144..160` deadzone. Apply
     `CENTER_SCALED` vs `PROPORTIONAL` half-width per `WIDESCREEN_DEADZONE_MODE`,
     generalized so native reproduces the ROM-cited `144..160` exactly. (The exact
     widescreen band geometry — edges and center relative to width — is derived in
     the plan against the ROM values, not assumed to be a naive `width/2` center.)
   - **Focus/respawn snap** (`Camera.java:127`): `x = centreX - 160`, placing the
     sprite at screen-x=160 on every focus/respawn/transition (ROM: right edge of
     the `144..160` deadzone). This is a **separate code path** from the band; if
     left at `160` the camera snaps off-center on every respawn at widescreen. Must
     move with the view and stay `160` at native.

   Audit min/max X bounds clamps to use live width.

   **Player right-boundary clamp (parity-sensitive — see "Right-boundary
   widening").** `PlayableSpriteMovement.java` computes
   `rightBoundary = maxX + SCREEN_WIDTH - SONIC_WIDTH` (320 − 24 = 296 = `0x128`),
   and `Sonic3kMGZEvents.java:134` carries a **duplicate** of this logic (local
   `SCREEN_WIDTH = 320`, `PLAYER_RIGHT_SCREEN_MARGIN = 24`) applied during MGZ quake
   locks. Both must be brought under the same width-driven derivation and must
   resolve to exactly the native value at `NATIVE_4_3`. This is **not** a plain
   320-swap — the `0x128`/`+$40` semantics are a deliberate divergence covered in
   its own section below.

4. **Background / tilemap draw extent** — `GraphicsManager`,
   `BatchedPatternRenderer`, `TilemapGpuRenderer`, `BackgroundRenderer`. The
   visible-column loop sized to 40 cells / 320px must fetch and draw
   `ceil(width/8)` columns. `BackgroundRenderer` also has its own local
   `SCREEN_WIDTH = 320` (separate from config) that must become width-driven. FBOs
   (`TilePriorityFBO`) already size from `camera.getWidth()/getHeight()` and need
   no change beyond camera returning the new width.

5. **Parallax + vertical-scroll column buffers** — three places bake the 20-column
   (H40) count and must scale to `ceil(width/16)`:
   - `ParallaxManager.java` — `BG_VSCROLL_COLUMN_COUNT` / `FG_VSCROLL_COLUMN_COUNT`
     (20 = 320/16) and their backing arrays.
   - `TilemapGpuRenderer.java` — `new VScrollBuffer(20)` upload buffer.
   - `BackgroundRenderer.java` — `new VScrollBuffer(20)` column buffer.

   Resizing `ParallaxManager` alone is insufficient; the GPU upload/render buffers
   must match. The per-scanline `hScroll[224]` array is height-indexed and stays
   as-is.

6. **UI safe-area composition (pillarbox mechanism)** — the riskiest Phase 1 item;
   there is currently **no** mechanism to center native-coordinate UI in a wider
   buffer. `Engine.java` builds one shared ortho matrix at three sites
   (`ortho2D(0, projectionWidth, 0, realHeight)` at lines 1129, 1264, 1660), and
   every full-screen UI renderer (title/legal/results/data-select) draws into it in
   raw `0..320` coords — so at a wider width those surfaces render left-aligned, not
   centered.

   **Owner: `UiRenderPipeline` (`com.openggf.graphics.pipeline`), not `Engine.java`
   directly.** It already orders Scene → HUD → Fade, which is the correct place to
   scope projections. Define the pass ordering explicitly:
   1. Scene renders at full configured width.
   2. The pipeline enters a **safe-area scope**: swap to a 320-wide projection
      (or apply a `(width - 320)/2` X offset + scissor) for pillarboxed UI.
   3. **Exit the safe-area scope before the fade pass**, so the fade runs at the
      full viewport (see "Full-viewport").

   This is a Phase 1 deliverable because Phase 1 pillarboxes the HUD, title card,
   and all full-screen surfaces; without it they will not be centered. Routing the
   projection swap and fade extent through `UiRenderPipeline` (rather than the three
   raw `Engine` ortho sites) is the main de-risking move.

### Tier 2 — In-level UI widening

7. **HUD** — `HudRenderManager.java`. Left-anchored items (X=16, 56, 64) and
   bottom anchors (Y=200/208) remain valid. Add width-relative anchoring so the
   HUD can optionally spread to the gameplay edges; default may remain a centered
   320 safe-area (decided during Phase 2 planning).

8. **Title card** — `TitleCardManager.java` (`SCREEN_WIDTH=320`,
   `SCREEN_HEIGHT=224`). Widen positioning math to the configured width.

### Tier 3 — Ultrawide and set-pieces

9. **Per-mode pillarbox fallback** — during boss/cutscene camera locks at
   ultrawide presets, optionally pillarbox to a narrower region to avoid
   revealing arena void until Tier 4 widens arenas.

10. **Boss arenas / camera-lock events** — `Sonic*LevelEventManager` and per-zone
   event handlers. Arena camera-lock bounds assume a 320 view; widen bounds so
   ultrawide does not show outside the arena.

### Always pillarboxed (default UI handling)

Full-screen UI surfaces render into a centered 320-wide safe region inside the
wider buffer: title screen, results screen, data-select, legal disclaimer, and
**special stages**. This keeps these surfaces correct without redrawing them.
`TitleCardManager` graduates out of pillarbox in Phase 2; special stages remain
pillarboxed permanently.

### Full-viewport (NOT pillarboxed)

The **fade pass is a final composition effect, not UI content, and must cover the
full viewport** — pillarboxing it would leave the widescreen sides unfaded during
fade-to-black / fade-from-black. `FadeManager` currently draws fixed
`quadRenderer.draw(0, 0, 320, 224)` quads (two call sites); these must become
width-driven (full configured width × 224), and the fade must **not** be routed
through the 320 safe-area pass — `UiRenderPipeline` exits the safe-area scope
before invoking the fade (see item 6). This is a Phase 1 item alongside the
safe-area work, since both gameplay and pillarboxed UI rely on the fade covering
everything.

## Parity Divergence: Right-Boundary Widening

The player's right-boundary clamp is the single most parity-sensitive line in the
inventory and encodes a real decision, not a literal swap.

`PlayableSpriteMovement.java` computes
`rightBoundary = maxX + SCREEN_WIDTH - SONIC_WIDTH`. `SCREEN_WIDTH - SONIC_WIDTH`
is `320 − 24 = 296 = 0x128` — and `0x128` is a **ROM-cited constant**, not a screen
width: the S3K strict path (`PhysicsFeatureSet.levelBoundaryRightStrict()`) uses
`Camera_max_X_pos + $128` directly (`sonic3k.asm:23183-23186, 28418-28421`), with
**no** normal-play `+$40` (`RIGHT_EXTRA = 0x40`) extension, and the `+$40` is gated
off during boss fights and end-of-level. `Sonic3kMGZEvents.java:134` duplicates the
same boundary math for MGZ quake locks.

**Decision: at widescreen, the right boundary widens to track the configured
width** (so the player can reach the visible right edge), which means the S3K
strict `+$128` boundary also widens to `+(width − 24)`. This **deliberately
diverges from a ROM-cited constant** and is only acceptable because widescreen is a
declared non-parity extension. Requirements:

- At `NATIVE_4_3` every path resolves to its exact ROM value (`+$128` strict,
  `+$128 + $40` normal), byte-for-byte unchanged.
- The widening applies uniformly to both `PlayableSpriteMovement` and the
  `Sonic3kMGZEvents` duplicate via the shared width-driven derivation.
- `RIGHT_EXTRA = 0x40` and the boss / end-of-level gate are left semantically
  intact; they interact with Phase 4 arena work and are not widened independently.
- This divergence is recorded in `docs/status/known-discrepancies.md` (and S3K-specific
  notes), per the trailer policy, when implemented.

## Phasing

The implementation plan is built phase-by-phase. Each phase is independently
shippable and leaves the engine in a working state.

- **Phase 1 — Core.** Config presets, fixed-224 width plumbing, width-derived
  placement windowing for **all** `AbstractPlacementManager` streams (objects,
  rings, bumpers), configurable deadzone + player right-boundary, parallax and GPU
  vertical-scroll column buffers, background/tilemap draw extent, full-viewport
  width-driven fade pass, preset-derived display-window sizing, and the UI
  safe-area composition pass. All full-screen UI (including in-level HUD and title
  card) pillarboxed through the safe-area pass; fade covers the full viewport.
  Ships a playable `WIDE_16_9` / `WIDE_16_10` at a window no smaller than today.
- **Phase 2 — In-level UI.** Widen HUD and title card to the configured width.
- **Phase 3 — Ultrawide.** Tune `ULTRA_21_9` / `SUPER_32_9`; add per-mode
  pillarbox fallback during boss/cutscene camera locks.
- **Phase 4 — Boss arenas.** Widen arena bounds and camera locks so ultrawide
  does not reveal void. Special stages stay pillarboxed.

## Testing

- **Regression at native.** A headless test asserts that `DISPLAY_ASPECT` defaults
  to `NATIVE_4_3` and resolves `SCREEN_WIDTH_PIXELS=320`, and that the spawn
  window, deadzone, and parallax column count match current values at native —
  guaranteeing the parity path is byte-for-byte unchanged.
- **Preset resolution.** Unit tests over the preset table: each preset resolves to
  the expected ×16-aligned width at height 224; invalid `DISPLAY_ASPECT` falls back
  to `NATIVE_4_3` with a warning.
- **Deadzone + snap math.** Unit tests for both `CENTER_SCALED` and `PROPORTIONAL`
  at several widths: the band reproduces the ROM `144..160` geometry at native;
  `CENTER_SCALED` keeps a 16px band; `PROPORTIONAL` scales the band by `width/320`.
  Separately assert the focus/respawn snap (`Camera.java:127`) tracks width and
  equals `centreX - 160` at native.
- **Placement window math.** Unit test the shared `AbstractPlacementManager`
  derivation: window scales with width and equals the legacy `0x280` (640) value
  at 320. Cover all three streams (object, ring, bumper) so none retains a baked
  320.
- **Column counts.** Unit test that `ceil(width/16)` is used consistently across
  `ParallaxManager`, `TilemapGpuRenderer`, and `BackgroundRenderer`, and equals 20
  at native. Assert every preset width is a multiple of 16 (invariant guard).
- **Player right-boundary.** Unit test that both `PlayableSpriteMovement` and the
  `Sonic3kMGZEvents` duplicate compute the right boundary from configured width and
  resolve to the exact native values at 320 (`+$128` strict; `+$128 + $40` normal).
- **Literal-320 guard.** A scanner-based guard test (same pattern as
  `TestNoDirectMapMutationsInGameplay` / `TestObjectServicesMigrationGuard`) that
  asserts no `SCREEN_WIDTH = 320` (or equivalent screen-width 320/224) literal
  exists outside an explicit allowlist. This is a far stronger backstop than
  waiting for visible defects, and catches missed gameplay-path literals like
  `Sonic3kMGZEvents`. The allowlist documents each intentional pillarbox/native
  constant (e.g. the 320 safe-area region width).
- **UI safe-area.** Headless test that the pillarbox composition centers a 320-wide
  region (X offset `(width-320)/2`) for non-native widths and is a no-op at native.
- **Fade extent.** Test that the fade quad spans the full configured width (not
  320) and is not routed through the safe-area pass; equals 320 at native.
- **Window sizing.** Test that with `DISPLAY_WINDOW_AUTOSIZE = true` (default),
  preset resolution derives `SCREEN_WIDTH = 2 × presetWidth` / `SCREEN_HEIGHT =
  448` and overwrites legacy `640`/`448` values (so no widescreen preset yields a
  window smaller than today's vertical), and that with
  `DISPLAY_WINDOW_AUTOSIZE = false` the configured `SCREEN_WIDTH`/`SCREEN_HEIGHT`
  are used verbatim.
- Existing trace-replay and physics suites run at `NATIVE_4_3` and must stay
  green, confirming no native-path regression.

## Risks

- **Encounter exposure.** Wider views reveal badniks, springs, monitors, and
  blind drops earlier, changing difficulty. Accepted as inherent to the
  extension; per-mode pillarbox (Phase 3) is the escape hatch for scripted
  moments.
- **Plane edge / unloaded-object visibility at ultrawide.** Mitigated by the
  always-pillarbox default for UI and the Phase 3 fallback; fully resolved for
  bosses only in Phase 4.
- **Hidden 320 literals.** Many `SCREEN_WIDTH = 320` literals exist beyond the core
  inventory — at least one in a live gameplay path (`Sonic3kMGZEvents.java:134`),
  plus the three title-screen managers, `AbstractResultsScreen`,
  `Sonic2EndingCutsceneManager`, `TitleCardElement`, and level-select constants.
  The always-pillarbox default covers the UI ones. The primary backstop is the
  **literal-320 guard test** (see Testing), which fails the build on any
  un-allowlisted screen-width literal — far stronger than relying on visible
  defects. The native-regression test protects the parity path in parallel.
- **Rewind keyframe growth.** A wider spawn window keeps more objects active, so
  each rewind keyframe captures more state and grows with width. Not a correctness
  issue, but memory/throughput scale with the chosen preset; worth measuring at
  `ULTRA_21_9` / `SUPER_32_9`.
- **Special-stage pillarbox at extreme aspects.** At `SUPER_32_9` the permanently
  pillarboxed 320-wide special-stage region leaves ~480px of bars. Accepted per
  Non-Goals; the fixed pillarbox region could be widened later if special-stage
  widening is ever taken on.
