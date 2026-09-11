# Object implementation reference

On-demand reference for the object/badnik system: what already exists, what to reuse, and
how art gets loaded per game. The per-game `s1-implement-object` / `s2-implement-object` /
`s3k-implement-object` skills are the step-by-step guides; this file is the shared
inventory behind them.

See also: [Engine map](engine-map.md), [AGENTS_S3K.md](../../AGENTS_S3K.md).

## Registration and lifecycle

Objects use a factory pattern with game-specific registries. `ObjectRegistry` creates an
`ObjectInstance` from an `ObjectSpawn`; factories are registered via `AbstractObjectRegistry`
subclasses (`Sonic1ObjectRegistry`, `Sonic2ObjectRegistry`, `Sonic3kObjectRegistry`).

| Class | Purpose |
|---|---|
| `ObjectManager` | Unified manager with `Placement`, `SolidContacts`, `TouchResponses`, `PlaneSwitchers`; injects `ObjectServices` into all objects |
| `ObjectServices` | Per-object service interface (camera, audio, level, game state, …) |
| `DefaultObjectServices` | Production implementation, backed by `GameplayModeContext` + `EngineContext` |
| `AbstractObjectRegistry` | Shared base for the three per-game registries |
| `ObjectFactory` | Functional interface for object creation |

**Adding an object (S2 shown; S1/S3K are analogous):**

1. Add the object ID to `Sonic2ObjectIds.java`.
2. Create the instance class extending `AbstractObjectInstance` (or `AbstractBadnikInstance`
   for enemies).
3. Register the factory in `Sonic2ObjectRegistry.registerDefaultFactories()`.
4. Solid collision is handled automatically via `ObjectManager.SolidContacts`.
5. Enemy touch response is handled via `ObjectManager.TouchResponses`.

**Service access inside objects:**

```java
// CORRECT — injected services:
services().audioManager().playSfx(sfxId);
services().camera().getX();
PatternSpriteRenderer renderer = getRenderer(artKey);   // inherited from AbstractObjectInstance

// WRONG — singletons are prohibited in object code:
AudioManager.getInstance().playSfx(sfxId);
```

**Child spawning:** use `spawnChild(() -> new ChildObject(spawn, params))` so slot
ownership, parent/child lifecycle, and remembered-spawn stay on the shared lifetime path.
Direct `ObjectManager.addDynamicObject(...)` is reserved for manager/framework bridge code
with focused tests.

## Behaviour contracts

New object/boss/badnik/trace work should use the shared vocabulary rather than one-off
flags:

- `ObjectControlState` — control bits and derived predicates.
- `ObjectPlayerQuery` / `ObjectPlayerParticipationPolicy` — which players a routine targets.
- `NativePositionOps` — playable-sprite `x_pos` / `y_pos` writes.
- `ObjectLifetimeOps` — destruction, offscreen, respawn, slot transfer.

Canonical behaviour profiles live under `com.openggf.game.profiles.*`; `level.objects`
hosts execution plus compatibility adapters. Raw setters and direct `setDestroyed(true)`
calls are legacy compatibility — do not grow guard baselines for new implementations
without documenting the exact reason.

**ENEMY touch responses poll every frame.** `ObjectManager.TouchResponses` callbacks for
ENEMY-category contacts fire continuously every frame while the overlap persists (matching
the ROM `Touch_Loop`), not only on the first frame. SPECIAL/monitor contacts remain
edge-triggered. New badnik / damaging-object code should not add consumed-once "already
hit" latches for the enemy touch path — rely on the per-frame poll.

## Base classes (`level.objects`)

Badniks extend `AbstractBadnikInstance` (game-agnostic), which provides touch response
collision, destruction via `DestructionEffects`, and the movement/animation framework.
Subclasses implement `updateMovement()` and `getCollisionSizeIndex()`.

| Base class | Purpose |
|---|---|
| `AbstractBadnikInstance` | All badniks — touch response, destruction via `DestructionEffects` |
| `AbstractSpikeObjectInstance` | Spike objects with retract/extend behaviour |
| `AbstractMonitorObjectInstance` | Monitors — shared icon-rise physics |
| `AbstractPointsObjectInstance` | Floating score popups |
| `AbstractProjectileInstance` | Fire-and-forget projectiles |
| `AbstractFallingFragment` | Collapsing platform fragment physics |
| `GravityDebrisChild` | Debris children with gravity |

## Shared utilities — check before implementing

Do **not** reimplement these.

| Utility | Package | Purpose |
|---|---|---|
| `SubpixelMotion` | `level.objects` | 16:8 fixed-point position updates (`moveSprite`, `moveSprite2`, `moveX`) |
| `PatrolMovementHelper` | `level.objects` | Left-right patrol with edge detection |
| `PlatformBobHelper` | `level.objects` | Sine-based standing-nudge for platforms |
| `SpringBounceHelper` / `SpringHelper` | `level.objects` | Shared spring bounce physics |
| `DestructionEffects` | `level.objects` | Badnik explosion + animal + points |
| `WaypointPathFollower` | `level.objects` | Conveyor / path-following objects |
| `ObjectControlledSolidContactController` | `level.objects` | Object-driven solid contact handling |
| `SlopedSolidProvider` / `MultiPieceSolidProvider` | `level.objects` | Non-rectangular and multi-piece solidity |
| `AnimationTimer` | `util` | Cyclic frame animation timer |
| `LazyMappingHolder` | `util` | Lazy-loading sprite mapping holder |
| `PatternDecompressor` | `util` | Bytes → `Pattern[]` conversion |
| `FboHelper` | `util` | FBO creation/destruction + viewport |

Inherited from `AbstractObjectInstance`: `getRenderer(artKey)`, `buildSpawnAt(x, y)`,
`isPlayerRiding()`, `isOnScreen(margin)`.

## Art loading

**Keep `ObjectArtData` game-agnostic.** Game-specific sprites (badniks, zone objects) go
through the provider pattern: add the ROM address to `SonicNConstants.java`, add a key to
`SonicNObjectArtKeys.java`, add a loader method to `SonicNObjectArt.java`, and register it
in `SonicNObjectArtProvider.loadArtForZone()`.

Prefer ROM-parsed mappings over hardcoded pieces:

- **S1** — `Sonic1ObjectArt.buildArtSheet(artAddr, mappings, palette, bankSize)` for
  Nemesis-compressed art with mappings; `S1SpriteDataLoader.loadMappingFrames(reader,
  mappingAddr)` for ROM-parsed mappings (5-byte pieces, byte piece count).
  `buildArtSheetFromRom()` exists, but most S1 object mappings are inline `spritePiece`
  assembly macros rather than separate binary tables, so many objects still use hardcoded
  mappings.
- **S2** — `S2SpriteDataLoader.loadMappingFrames(reader, mappingAddr)`; call the shared
  utility from `Sonic2ObjectArt` instead of copying an inline parser into the object file.
  `loadMappingFramesWithTileOffset()` supports VRAM tile index adjustment.
- **S3K** — `Sonic3kObjectArt.buildLevelArtSheetFromRom(mappingAddr, artTileBase, palette)`.
  Add the mapping ROM address to `Sonic3kConstants.java` (find it with `RomOffsetFinder`)
  and extract the `art_tile` base and palette from the object code's `make_art_tile()`
  call. Only hardcode mapping pieces when the ROM table can't be used directly.

**Hard rule — ROM-only runtime assets.** Object art, mappings, DPLCs, animation scripts,
PLC data, and any other gameplay/runtime asset bytes must come from the user-supplied ROM
through the engine's ROM-loading pipeline. Never read runtime asset bytes from the
disassembly/reference trees under `docs/` as a fallback — that tree is for research,
labels, and offset discovery only. If a ROM-backed source is missing, find or verify the
ROM address instead.

## Constants files

| File | Contents |
|---|---|
| `Sonic1Constants.java` | S1 ROM offsets (zone IDs, level data, collision, palettes, art) |
| `Sonic2Constants.java` | S2 primary ROM offsets |
| `Sonic2ObjectIds.java` | S2 object type IDs (e.g. `0x41` Spring, `0x26` Monitor) |
| `Sonic2ObjectConstants.java` | S2 touch collision data |
| `Sonic2AnimationIds.java` | S2 animation script IDs |
| `Sonic2AudioConstants.java` | S2 SFX IDs (music IDs live in `game.sonic2.audio.Sonic2Music`) |
| `Sonic3kConstants.java` | S3K ROM offsets — prefer S&K-half (`sonic3k.asm`) addresses |

S2 constants live in `game.sonic2.constants`.
