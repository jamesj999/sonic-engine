# S3K CNZ Traversal Objects Design

## Scope

This design covers the **first follow-on object phase** after the completed
S3K CNZ bring-up: the **traversal-first gimmick slice** for
**Carnival Night Zone** in **Sonic 3 & Knuckles**.

It explicitly does **not** mean Sonic 2 Casino Night Zone.

This spec covers only these CNZ traversal objects:

- `Obj_CNZBalloon`
- `Obj_CNZCannon`
- `Obj_CNZRisingPlatform`
- `Obj_CNZTrapDoor`
- `Obj_CNZHoverFan`
- `Obj_CNZCylinder`
- `Obj_CNZVacuumTube`
- `Obj_CNZSpiralTube`

Pinned object IDs for this phase:

| Object | ID |
|--------|----|
| `Obj_CNZBalloon` | `0x41` |
| `Obj_CNZCannon` | `0x42` |
| `Obj_CNZRisingPlatform` | `0x43` |
| `Obj_CNZTrapDoor` | `0x44` |
| `Obj_CNZHoverFan` | `0x46` |
| `Obj_CNZCylinder` | `0x47` |
| `Obj_CNZVacuumTube` | `0x48` |
| `Obj_CNZSpiralTube` | `0x4C` |

Deferred adjacent CNZ-facing slots for clarity:

| Deferred object/slot | ID |
|----------------------|----|
| `Obj_CNZLightBulb` | `0x45` |
| `Obj_CNZGiantWheel` | `0x49` |
| `Obj_Bumper` / shared bumper slot used in this range | `0x4A` |
| `Obj_CNZTriangleBumpers` | `0x4B` |
| `Obj_CNZBarberPoleSprite` | `0x4D` |
| `Obj_CNZWireCage` | `0x4E` |

This is the first document in a split CNZ object follow-up. Later specs will
cover:

- the remaining CNZ gimmick range outside this traversal slice
- CNZ badniks `Clamer`, `Sparkle`, and `Batbot`
- deeper Act 2 end-boss parity beyond the bounded route/capsule handoff already
  implemented

## Goals

- Replace placeholder-backed traversal gimmicks with ROM-accurate gameplay
  behavior.
- Deliver authored art, mappings, animation, and DPLC parity alongside behavior
  so validation is meaningful.
- Keep object implementations traceable to the S3K disassembly and ROM data.
- Preserve the project's framework-first architecture without hiding object
  behavior inside CNZ-local one-off managers.
- Require heavy Javadoc and comments that justify behavior against ROM
  evidence or explain any engine-side adaptation.

## Non-Goals

- Completing every remaining CNZ gimmick in one pass.
- Completing CNZ badniks or the full Act 2 boss.
- Refactoring unrelated object systems or shared engine subsystems without a
  direct need from this object slice.

## Naming Rule

To avoid ambiguity with Sonic 2:

- documentation and comments must prefer `S3K CNZ` or `Carnival Night Zone`
- bare `CNZ` is acceptable only where the existing codebase already fixes the
  naming, such as class names or constants
- any comparison to Sonic 2 must say `S2 CNZ` or `Casino Night Zone`

## Source Of Truth

Primary sources:

- `docs/skdisasm/sonic3k.asm`
- `docs/skdisasm/Levels/CNZ/...`
- `docs/skdisasm/SonLVL INI Files/CNZ/...` as a locator aid only
- `docs/architecture/research/s3k-zones/cnz-analysis.md`

Behavior authority comes from the disassembly and ROM behavior, not from SonLVL
metadata, placeholder behavior, or assumptions carried over from Sonic 2.

Important object data anchors for this phase include:

- `Map - Balloon.asm`
- `Map - Cannon.asm`
- `DPLC - Cannon.asm`
- `Map - Rising Platform.asm`
- `Anim - Rising Platform.asm`
- `Map - Trap Door.asm`
- `Anim - Trap Door.asm`
- `Map - Hover Fan.asm`
- `Map - Cylinder.asm`
- SonLVL CNZ object metadata for subtype/placement hints
- the object code paths in `sonic3k.asm` for each target object

## Critical ROM Addressing Constraint

This must be treated as a hard guardrail for agents and reviewers.

Because the playable ROM is **Sonic & Knuckles locked on to Sonic 3**, Sonic 3
side addresses do **not** map directly to final ROM offsets. When an object's
code, mappings, DPLC data, or art lives on the Sonic 3 side of the lock-on ROM,
workers must account for the **Sonic & Knuckles ROM-length offset** before using
that address in engine code or in ROM readers.

This is a frequent failure mode when implementing S3 stages and art.

Rules:

- do not treat a Sonic 3-side disassembly address as a final ROM offset without
  checking whether the lock-on offset applies
- every object section in the implementation plan must state whether its
  anchors are S&K-side or S3-side
- if an object uses Sonic 3-side art or tables, the code comments or Javadocs
  must call that out explicitly
- if needed, use existing S3K tooling such as `RomOffsetFinder` and the
  `s3k-disasm-guide` workflow to verify the final offset instead of copying the
  disassembly number blindly

## Current Branch State

The completed CNZ bring-up already covers:

- CNZ runtime/event translation
- deform, animated tiles, and palette cycling
- Act 1 miniboss chain
- Act 2 teleporter route seam
- water helper objects
- bounded end-boss/capsule handoff

The remaining traversal gimmick surface in the CNZ gimmick range still resolves
primarily to placeholders inside `Sonic3kObjectRegistry`.

This spec therefore focuses on object completion for the traversal-heavy subset
that most directly affects moment-to-moment play.

## Target Object Set

Included in this phase:

- `Balloon`
- `Cannon`
- `RisingPlatform`
- `TrapDoor`
- `HoverFan`
- `Cylinder`
- `VacuumTube`
- `SpiralTube`

Explicitly deferred to later specs:

- `LightBulb`
- `GiantWheel`
- `Bumper`
- `TriangleBumpers`
- `BarberPoleSprite`
- `WireCage`
- other decorative or lower-priority CNZ gimmicks outside the traversal set
- CNZ badniks and deeper boss parity

## Subtype Complexity Overview

The implementation plan must verify exact subtype semantics from the disassembly
before coding each object, but the current scope estimate should assume:

| Object | Expected subtype complexity | Planning note |
|--------|-----------------------------|---------------|
| `Balloon` | low | likely variant/animation or bounce-selection behavior rather than path-heavy dispatch |
| `Cannon` | medium | likely direction, launch arc, or power selection |
| `RisingPlatform` | medium | likely travel-distance or trigger-mode selection |
| `TrapDoor` | medium | likely open timing or orientation selection |
| `HoverFan` | medium | likely force window, orientation, or activation variant |
| `Cylinder` | medium-high | temporary player control with variant-specific movement rules |
| `VacuumTube` | high | likely path or route selection with substantial path-data volume |
| `SpiralTube` | high | likely path or route selection with substantial path-data volume |

`VacuumTube` and `SpiralTube` must be treated as potentially path-heavy objects
until the disassembly confirms otherwise.

## Architecture

### Object-Local Ownership

Each traversal gimmick must be implemented as a normal CNZ object class owned
by `Sonic3kObjectRegistry`, not by a new CNZ-global object manager.

Preferred structure:

- one focused object instance class per ROM object family
- subtype dispatch kept inside the owning object where the ROM does so
- shared helpers or base classes used only when they reduce duplication without
  obscuring the ROM behavior

No new CNZ-local traversal controller should be introduced unless multiple
objects prove to share the same state machine or routing machinery and the shared
abstraction remains more traceable than the duplicated ROM logic.

### Integration Rules

Workers must reuse existing framework surfaces where appropriate:

- shared object bases and helpers in `level.objects`
- existing S3K object-art loading patterns
- runtime/event bridges only when an object truly writes state that other CNZ
  subsystems consume

These traversal objects must not publish new CNZ event/runtime state unless
the ROM actually couples them to another subsystem.

### Reuse Candidates

The implementation plan must treat the following existing classes as the
primary reuse candidates before introducing new traversal/path machinery:

- `WaypointPathFollower` for generic waypoint stepping, dominant-axis speed
  handling, and index progression
- `AutomaticTunnelObjectInstance` for subtype-driven capture/path-follow/release
  structure and large path-table handling
- `HCZTwistingLoopObjectInstance` for spiral/tube-style player routing,
  control-lock, and forced rolling/animation/radius parity

These are reuse candidates, not mandatory base classes. If a traversal object
does not match one of these patterns closely enough, the implementation plan
must say why and define the object-local alternative explicitly.

### Coordinate Rule

When porting object code:

- ROM `x_pos` maps to `getCentreX()` / `setCentreX(...)`
- ROM `y_pos` maps to `getCentreY()` / `setCentreY(...)`

Temporary control-lock or routing objects such as `Cannon`, `Cylinder`,
`VacuumTube`, and `SpiralTube` must be especially careful not to drift into
top-left sprite coordinate semantics.

## Decomposition

This spec will be executed as one design but three implementation slices.

### Slice 1: Local Traversal Gimmicks

Objects:

- `Balloon`
- `RisingPlatform`
- `TrapDoor`
- `HoverFan`

Why first:

- spatially local behavior
- lower control-lock complexity
- immediate gameplay value
- easier headless assertions

Expected outcomes:

- placeholder replacement
- art/mapping/animation parity
- deterministic activation and motion tests

### Slice 2: Directed Traversal Launchers

Objects:

- `Cannon`
- `Cylinder`

Why second:

- these objects temporarily take control of the player
- they are more sensitive to centre-coordinate correctness
- launch curves, subtype behavior, and release states need tighter validation

Expected outcomes:

- ROM-accurate launch/control windows
- ROM-backed art/DPLC/mapping parity
- deterministic tests for control lock, movement, and release

### Slice 3: Path-Following Transport

Objects:

- `VacuumTube`
- `SpiralTube`

Why last:

- highest routing/path-data complexity
- stronger chance of authored-path interpretation or transport tables
- need both numeric and visual validation

Expected outcomes:

- path-following transport parity
- control lock/release parity
- explicit documentation if the engine uses a faithful approximation instead of
  a direct ROM path structure

Preconditions:

- verify whether `VacuumTube` and `SpiralTube` path data lives on the S&K side
  or the Sonic 3 side of the lock-on ROM
- verify whether the path format matches the engine's existing
  `AutomaticTunnelObjectInstance` waypoint-table style or uses a different
  structure
- verify whether both objects share one path-data format/table family or use
  separate data sources

Scope risk:

If the CNZ tube objects use AutomaticTunnel-scale path volume or a distinct
format, Slice 3 should be treated as the highest-risk slice in the follow-on
plan and costed accordingly.

## Per-Object Implementation Contract

Each object in this spec is a full vertical port.

Required per object:

- registry hookup in `Sonic3kObjectRegistry`
- correct S3KL-only gating where the numeric slot remaps in other zone sets
- dedicated object instance class unless an existing shared base is clearly
  suitable
- ROM-backed art, mappings, animation, and DPLC integration where applicable
- headless tests for deterministic gameplay seams
- targeted visual validation for motion-heavy or path-heavy objects
- forced player animation, rolling state, and collision-radius changes during
  object-controlled traversal where the ROM applies them

Per object, the implementation plan must explicitly document:

- disassembly labels used
- whether code/data anchors are on the S&K side or Sonic 3 side
- whether mappings/DPLC/art are ROM-parsed or hand-authored
- why a hand-authored fallback is acceptable if a ROM table cannot be consumed
  directly

## Documentation And Commenting Rule

Heavy documentation is mandatory for this phase.

This is not optional style polish. It is part of the completion gate.

Required:

- every new CNZ traversal object class must have class-level Javadoc naming the
  ROM object and its gameplay role
- every non-obvious state machine, subtype split, control-lock window,
  launch/routing rule, or art-loading decision must have Javadoc or a concise
  code comment tied to ROM behavior
- every forced player animation or radius change during traversal control must
  be documented where it is applied
- when the Java structure differs from the ROM, the code must justify the
  adaptation locally
- tests should include short ROM notes wherever they are locking a specific
  disassembly seam

Comments should explain *why this behavior exists* and *which ROM behavior it
comes from*, not restate trivial code.

## Validation Strategy

### Behavioral Validation

Every targeted object needs deterministic coverage where possible.

Examples of required assertions by object family:

- `Balloon`: bounce/launch response, activation timing, release position
- `RisingPlatform`: motion trigger, travel bounds, rider behavior
- `TrapDoor`: open/close state progression, collision changes, rider handling
- `HoverFan`: force application window, push direction/strength, gating
- `Cannon`: player capture, forced animation/radii, launch state, release
  vector, control restoration
- `Cylinder`: player routing onto and off the cylinder path, forced
  animation/radii, and control ownership
- `VacuumTube` / `SpiralTube`: route entry conditions, path progression, exit
  position, forced animation/radii, and control unlock

Each slice also needs registry canaries proving the covered objects no longer
resolve to `PlaceholderObjectInstance`.

### Visual Validation

Visual validation is required, but focused.

Required visual targets:

- `Cannon`
- `Cylinder`
- `HoverFan`
- `VacuumTube`
- `SpiralTube`

Optional but encouraged when useful:

- `Balloon`
- `RisingPlatform`
- `TrapDoor`

Visual validation must compare ROM/disassembly-backed reference beats against
engine output, and must explicitly label the zone as **Carnival Night Zone**
to prevent Sonic 2 CNZ contamination.

## Completion Gates

This spec is complete only when:

- the targeted traversal objects no longer resolve to placeholders
- behavior parity is covered by deterministic tests where practical
- art/mappings/animation/DPLC parity is present for the targeted objects
- heavy ROM-backed Javadoc/comments are present in the code
- visual validation exists for the high-motion transport objects
- any engine-side adaptation is documented and justified

## Deliverables

This spec produces:

- one implementation plan for the traversal-first CNZ object slice
- code and tests only for the targeted traversal objects
- a follow-up validation artifact recording `PASS`, `LIKELY`, `FAIL`, or `SKIP`
  per object family or validation beat

## Future Work

After this spec lands, later CNZ object specs will cover:

- the remaining `$41-$4E` CNZ gimmick objects outside the traversal slice
- CNZ badniks `Clamer`, `Sparkle`, and `Batbot`
- deeper Act 2 end-boss parity and any remaining route/polish gaps
