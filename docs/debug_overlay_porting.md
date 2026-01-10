# Debug overlay porting ideas (from `SPGSonic2Overlay.Lua`)

This file captures candidate debug features to port from the Gens Lua overlay
into the engine’s built‑in debug view. It is intentionally a “menu of options”
so we can pick the highest ROI items first.

## High‑value, low‑effort
- **Player status panel** (left sidebar):
  - Position (pixel + subpixel), x/y/g speeds (raw + px), angle (byte + degrees),
    ground mode, direction, state flags (air/roll/spindash/crouch),
    layer & priority, radii, top/lrb solidity bits, animation id/frame/timer.
- **Sensor A–F readout**:
  - A–F labels mapped to our sensor order with direction, distance, and angle.
  - Should reflect rotated direction based on ground mode.
- **Camera bounds overlay**:
  - Draw camera viewport rectangle + center crosshair.
  - Draw clamp bounds rectangle (min/max + viewport extents).

## Medium effort, high value
- **Terrain overlay modes** (None / Plain / Degrees / “Real”):
  - *Plain*: draw solid pixels (current).
  - *Degrees*: annotate with surface angle values per tile sample.
  - *Real*: draw slope sample lines for sanity‑checking flips/angles.
- **Layer filter** (Mixed / Current / Both):
  - Show plane A/B collision overlays independently.
- **Smoothing toggle**:
  - Show “true collision positions” vs “smoothed” render positions.

## Longer‑term / optional
- **Object overlays**:
  - Hitboxes (player, enemy, boss, ring), triggers, solidity boxes,
    platform surfaces, walking edges, slopes.
- **Object info**:
  - Name, ID/sub‑ID, frame, render flags.
- **Overlay controls**:
  - On‑screen toggles with shortcuts (like the Lua overlay).

## Notes
- The Lua script uses RAM reads; we should only port *visual concepts* and
  tie them to our current runtime data sources.
- Sensor A–F mapping should remain stable across ground mode rotations so
  A/B are always “floor pair”, C/D “ceiling pair”, E/F “push pair”.
