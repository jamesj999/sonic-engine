# Object Implementation Plan

This plan tracks object implementation progress across sessions. The goal is to deliver accurate, ROM-faithful behavior while keeping the engine modular and debuggable.

## Guiding Principles
- **Accuracy first:** render/physics/collision should converge to pixel-accurate behavior.
- **Manager-based design:** keep logic in managers and object classes (avoid `Engine.java`).
- **Layered delivery:** render-only -> interactions -> accurate physics/collision.
- **Deterministic debugging:** every object must be visible, named, and traceable.

## Current Pipeline (Baseline)
- **Placement:** `ObjectPlacementManager` windows spawns based on camera position.
- **Runtime:** `ObjectManager` instantiates objects from active spawns and owns their lifecycle.
- **Registry:** `ObjectRegistryData` provides built-in `id -> name` lookup; `ObjectRegistry` assigns factories.
- **Render (now):** object instances append GL line commands; high/low priority passes handled by `ObjectManager`.
- **Collision:** `SolidObjectManager` and `TouchResponseManager` handle object interactions (Touch_Sizes read from ROM).

## Wave Roadmap

### Wave 1 -- Visibility + Minimal Shells (EHZ-critical)
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
- [x] Object name labels in debug overlay (optional but useful).

### Wave 2 -- Collision Scaffolding
Goal: introduce object interaction without altering core physics.

Deliverables:
- `SolidObjectManager` for object solidity based on ROM parameters.
- `TouchResponseManager` for collision_flags + Touch_Sizes overlap checks.
- Per-object "reaction hooks" (e.g., spring boost, spike hurt, monitor break).

Progress:
- [x] Collision manager integration (solid + touch response).
- [x] Basic SolidObject parameter checks for spikes/springs/monitors.
- [x] TouchResponse overlap checks for monitors.
- [x] Initial reactions for wave 1 objects (placeholder spring/spike/monitor).
- [x] Monitor subtype handling (rings + broken state; others TODO).

### Wave 3 -- Accuracy Passes
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
- SolidObject manager now snaps and clears velocities for top/side/bottom contact, but remains a simplified solver (no slope handling or moving-platform carry).
- TouchResponse now mirrors the ROM overlap test (player offsets, crouch special case, and asymmetric width/height checks).
- Spring and platform collision parameters still rely on inferred values; verify against ROM data.
- Placement `renderFlags` are not equivalent to sprite flip flags.
- Runtime object art is not yet loaded; boxes are placeholders only.

## Next Up (default)
1) Implement remaining monitor effects (shoes/shield/invincibility/1up).
2) Refine spring orientation handling and strength values.
3) Replace platform/bridge sizes with verified ROM hitboxes.

