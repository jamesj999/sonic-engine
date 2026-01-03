# Object Implementation Plan

This plan tracks object implementation progress across sessions. The goal is to deliver accurate, ROM-faithful behavior while keeping the engine modular and debuggable.

## Guiding Principles
- **Accuracy first:** render/physics/collision should converge to pixel-accurate behavior.
- **Manager-based design:** keep logic in managers and object classes (avoid `Engine.java`).
- **Layered delivery:** render-only ? interactions ? accurate physics/collision.
- **Deterministic debugging:** every object must be visible, named, and traceable.

## Current Pipeline (Baseline)
- **Placement:** `ObjectPlacementManager` windows spawns based on camera position.
- **Runtime:** `ObjectManager` instantiates objects from active spawns and owns their lifecycle.
- **Registry:** `ObjectRegistryData` provides built-in `id ? name` lookup; `ObjectRegistry` assigns factories.
- **Render (now):** object instances append GL line commands; high/low priority passes handled by `ObjectManager`.

## Wave Roadmap

### Wave 1 — Visibility + Minimal Shells (EHZ-critical)
Goal: ensure common objects appear with clear visual identity and a stable spawning pipeline.

Scope:
- Springs (0x41)
- Spikes (0x36)
- Monitors (0x26)
- Checkpoints (0x79)
- Platforms (0x11/0x15/0x18/0x19)

Deliverables:
- Render-only object classes with distinct colors and approximate sizes (debug-only).
- Factory wiring in `ObjectRegistry` for the IDs above.
- Coverage logging for active object IDs per level.

Progress:
- [x] Object runtime pipeline (`ObjectManager` + placement sync).
- [x] Built-in object registry data (`ObjectRegistryData`).
- [x] Render-only factories for wave 1 objects.
- [ ] Object name labels in debug overlay (optional but useful).

### Wave 2 — Collision Scaffolding
Goal: introduce object interaction without altering core physics.

Deliverables:
- `ObjectCollisionManager` for player ? object AABB tests.
- Base interaction types: `SOLID`, `HURT`, `COLLECT`, `TRIGGER`.
- Per-object “reaction hooks” (e.g., spring boost, spike hurt, monitor collect).

Progress:
- [ ] Collision manager and integration.
- [ ] Basic player ? object AABB tests.
- [ ] Initial reactions for wave 1 objects.

### Wave 3 — Accuracy Passes
Goal: per-object behavior matches ROM (subtypes, flip, timers, and hitboxes).

Deliverables:
- Subtype decoding per object.
- Accurate hitboxes + trigger bounds per subtype.
- Moving platform logic: riders, offsets, and stepping behavior.
- EHZ object parity verification (debug overlay + ROM comparison).

Progress:
- [ ] Subtype-specific behaviors.
- [ ] Hitbox accuracy for wave 1 objects.
- [ ] Platform rider/offset fidelity.

## Object Class Strategy
- **Base:** `AbstractObjectInstance` + `BoxObjectInstance` for render-only shells.
- **Typed classes:** one class per object family (spring/spike/monitor/etc.).
- **Future expansion:** each object class owns its subtype logic and collision profile.

## Testing / Validation
- **Coverage:** log unique IDs seen per zone and compare against expected list.
- **Visual:** ensure objects align with known spawn positions in EHZ1/2.
- **Behavior:** compare object response to ROM (speed, angle, animation timing).

## Known Gaps / Risks
- Sizes in Wave 1 are **approximate** and should not be reused for collisions.
- Placement `renderFlags` are not equivalent to sprite flip flags.
- Runtime object art is not yet loaded; boxes are placeholders only.

## Next Up (default)
1) Add object name labels to the debug overlay.
2) Build `ObjectCollisionManager` and wire up spring + spike reactions.
3) Implement accurate hitboxes for EHZ springs/spikes/monitors.
