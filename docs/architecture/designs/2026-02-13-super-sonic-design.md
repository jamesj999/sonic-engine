# Super Sonic Design

## Scope

- **S2:** Super Sonic (7 Chaos Emeralds + 50 rings + jump)
- **S3K:** Super Sonic only (7 Chaos Emeralds + 50 rings + jump)
- **Deferred (TODO):** Hyper Sonic, Super Tails, Super Knuckles, Hyper Knuckles
- ROM-exact palette cycling, animation frames, and physics values
- Full emerald gating + debug toggle for testing
- Ring drain (1 ring/sec), auto-revert at 0 rings

## Architecture: SuperStateController

A new controller class following the established sub-controller pattern (`DrowningController`, `SpindashDustController`), attached to `PlayableSpriteController`.

Game-specific subclasses handle per-game differences:
- `Sonic2SuperStateController` - S2 palette data, animation IDs, physics
- `Sonic3kSuperStateController` - S3K palette data, animation IDs, physics

### State Machine

```
NORMAL → TRANSFORMING → SUPER → REVERTING → NORMAL
```

| State | Entry Condition | Behavior |
|-------|----------------|----------|
| NORMAL | Default / after revert | Standard Sonic. Check transformation trigger each frame. |
| TRANSFORMING | Trigger met | Play transformation animation, fade palette, flash screen, start Super music. |
| SUPER | Animation complete | Ring drain, palette cycling, Super physics, invincibility, enemy destruction on contact. |
| REVERTING | Rings hit 0 | Restore palette, physics, music. Destroy stars. |

### Transformation Trigger

Checked each frame while NORMAL:
1. Player presses jump while airborne (double-tap pattern)
2. `GameStateManager.hasAllEmeralds()` == true
3. Ring count >= 50
4. Not in: hurt, dying, debug mode, object-controlled, underwater (S2)

### Transformation Sequence

1. State → TRANSFORMING
2. Switch to transformation animation (S2: `SupSonAni_Transform`, frames 0x6D-0x71)
3. Palette fade: normal Sonic colors → Super gold colors
4. Screen flash (1-2 frames white palette override)
5. Play Super Sonic music (`MusID_SuperSonic`)
6. On animation complete: state → SUPER
7. Swap physics profile to Super values
8. Spawn `SuperSonicStarsObjectInstance`
9. Grant invincibility flag

### De-transformation (rings reach 0)

1. State → REVERTING
2. Restore normal Sonic palette
3. Restore normal physics profile
4. Destroy stars object
5. Resume zone music
6. State → NORMAL

## Palette Cycling

Super palette cycling is character-bound (not zone-bound), managed directly by `SuperStateController`.

### S2 Palette Data

Extracted from `CyclingPal_SSTransformation` in disassembly:
- 4 palette frames cycling at ~8 frames per advance
- Replaces palette line 0, colors 2-7 (Sonic's body colors)
- Separate transformation fade sequence (normal → gold)
- Zone-specific underwater variants for CPZ/ARZ underwater sections

### S3K Palette Data

- Similar cycling pattern with S3K-specific color values
- Extracted from S3K ROM palette cycling tables

### Priority

Super palette overrides invincibility palette cycling. Invincibility stars are not shown during Super; the Super Sonic stars replace them.

## Animation

Super Sonic uses unique animation IDs referencing different mapping frames within Sonic's existing art/mapping/DPLC data. No separate art file loading required.

### S2 Super Sonic Animations

From `SupSonAni_*` in s2disasm:
- `SupSonAni_Walk` - Walking/running (arms-back pose)
- `SupSonAni_Run` - Fast run
- `SupSonAni_Roll` - Rolling
- `SupSonAni_Stand` - Standing idle
- `SupSonAni_Transform` - Transformation sequence (frames 0x6D-0x71)

### Integration

`SpriteAnimationProfile.resolveAnimationId()` checks `isSuperSonic()` and returns Super animation IDs instead of normal IDs. The existing DPLC system loads correct tiles automatically.

## Physics

### Profile Switching

`PhysicsProvider.getProfileForCharacter()` returns Super profile when `isSuperSonic()` is true.

### S2 Super Sonic Values (from disassembly)

| Field | Normal | Super |
|-------|--------|-------|
| Top speed | 0x600 | 0xA00 |
| Acceleration | 0x0C | 0x30 |
| Deceleration | 0x80 | 0x100 |

### S3K Super Sonic Values

Already defined: `PhysicsProfile.SONIC_3K_SUPER_SONIC`

### Speed Shoes Interaction

Values stack: Super physics * speed shoes multiplier.

## Combat

### Invincibility

- `getInvulnerable()` returns true when Super
- Enemies destroyed on contact (touch response treats Super contact like rolling/jumping hit)
- **Exceptions:** Crushing, drowning, bottomless pits still kill

### Shield Interaction

- S2: Super Sonic cannot pick up shields
- S3K: Shields can coexist with Super state (game-specific via feature flag)

## Ring Drain

- 60-frame counter in `SuperStateController.update()`
- Decrements ring count via `LevelGamestate.addRings(-1)` each second
- At 0 rings: trigger de-transformation
- Pauses when game is paused

## Visual Effects

### Super Sonic Stars Object

New `SuperSonicStarsObjectInstance` class (separate from `InvincibilityStarsObjectInstance`):
- Art: `ArtNem_SuperSonic_stars` loaded from ROM (Nemesis-compressed)
- 4 golden sparkles orbiting the player
- Spawned on transformation complete, destroyed on de-transformation

### Screen Flash

Brief white flash on transformation (1-2 frames white palette override, then fade back).

## Emerald Gating

### Current State

`GameStateManager` already tracks:
- `emeraldCount` (int, 0-7)
- `gotEmeralds[]` (boolean[7])
- `hasAllEmeralds()` method

### Additions

- `canTransformToSuper()`: `hasAllEmeralds() && rings >= 50 && !superSonic`
- S3K TODO: `superEmeraldCount`, `hasSuperEmeralds()` for Hyper gating

## Debug Toggle

- Configurable debug key binding via `SonicConfigurationService`
- Only active when `DEBUG_VIEW_ENABLED` is true
- Toggle ON: skip transformation animation, immediately apply Super state
- Toggle OFF: immediately revert to normal
- Bypasses emerald/ring requirements

## Key Files to Create

| File | Purpose |
|------|---------|
| `sprites/playable/SuperStateController.java` | Base controller with state machine |
| `game/sonic2/Sonic2SuperStateController.java` | S2-specific palette, anim, physics |
| `game/sonic3k/Sonic3kSuperStateController.java` | S3K-specific palette, anim, physics |
| `game/sonic2/objects/SuperSonicStarsObjectInstance.java` | S2 golden sparkles |

## Key Files to Modify

| File | Change |
|------|--------|
| `sprites/playable/PlayableSpriteController.java` | Attach SuperStateController |
| `game/PhysicsProfile.java` | Add `SONIC_2_SUPER_SONIC` profile |
| `game/GameStateManager.java` | Add `canTransformToSuper()` |
| `sprites/playable/AbstractPlayableSprite.java` | Wire Super state into invulnerability/touch checks |
| `level/objects/ObjectManager.java` | Super Sonic enemy destruction in TouchResponses |
| `game/sonic2/Sonic2PlayerArt.java` | Load Super Sonic stars art |
| `game/sonic2/constants/Sonic2Constants.java` | Add Super Sonic ROM addresses |
| `sprites/animation/SpriteAnimationProfile` | Super-aware animation ID resolution |

## Future Extensions (TODO)

- Hyper Sonic (S3K): afterimage trail, flash attack (double-jump screen nuke), unique palette flash
- Super Tails (S3K): 4 Super Flickies that auto-target enemies
- Super Knuckles (S3K): gliding/climbing with Super physics
- Hyper Knuckles (S3K): gliding earthquake, wall climb shake
