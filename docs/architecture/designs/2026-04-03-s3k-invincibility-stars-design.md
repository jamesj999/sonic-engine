# S3K Invincibility Stars Design

## Summary

Implement the S3K invincibility star visual effect as a new game-specific object class, following the same architecture as the existing S3K shield objects (fire, lightning, bubble, insta-shield). The S3K invincibility stars are architecturally different from S2: they combine position-history trailing with per-star orbital sub-sprites.

## Background

The invincibility monitor (type 8) grants temporary damage immunity with a visual sparkle effect. The timer/damage/music logic is already fully implemented in `AbstractPlayableSprite.giveInvincibility()`. What's missing is the S3K-specific visual rendering — currently S3K falls through to the S2 `InvincibilityStarsObjectInstance`, which uses a simple 4-star circular orbit that doesn't match the original game.

### S3K vs S2 Behavior (from disassembly Obj_Invincibility, sonic3k.asm:33751)

| Aspect | S2 (current) | S3K (target) |
|--------|-------------|-------------|
| Structure | 4 stars orbiting player center | 1 parent group + 4 trailing child groups |
| Trailing | None | Children trail via position history (0/3/6/9 frames behind) |
| Sub-sprites per group | 1 | 2 (phase-offset orbit) |
| Orbit table | byte_1DB42 (S2-specific) | byte_189A0 (32 entries, ~16px radius circle) |
| Rotation speed | Uniform | Parent: $12/frame, Children: $02/frame |
| Animation | Single shared sequence | 5 different tables (1 parent + 4 children) |
| Direction sensitivity | No | Rotation reverses when facing left |

## Components

### 1. ROM Constants (`Sonic3kConstants.java`)

New addresses to locate via RomOffsetFinder:

| Constant | Description | Size |
|----------|-------------|------|
| `ART_UNC_INVINCIBILITY_ADDR` | Uncompressed star art (binclude "Invincibility.bin") | $200 bytes |
| `ART_UNC_INVINCIBILITY_SIZE` | Fixed at 0x200 | — |
| `MAP_INVINCIBILITY_ADDR` | 9 mapping frames (Map - Invincibility.asm) | Variable |

### 2. Art Key (`Sonic3kObjectArtKeys.java`)

No new key needed. The existing game-agnostic `ObjectArtKeys.INVINCIBILITY_STARS` key is used, so `ObjectRenderManager.getInvincibilityStarsRenderer()` works transparently for all games.

### 3. Art Loading (`Sonic3kObjectArtProvider.java`)

Add `loadInvincibilityStarArt()` alongside existing shield art loading in `loadShieldArt()`. This follows the non-DPLC path since invincibility star art is static (no dynamic pattern loading):

- Load uncompressed art tiles from ROM
- Parse S3K mapping frames
- Build `PatternSpriteRenderer` (not `PlayerSpriteRenderer` — no DPLCs needed)
- Register under `ObjectArtKeys.INVINCIBILITY_STARS`

### 4. Object Class (`Sonic3kInvincibilityStarsObjectInstance.java`)

New class in `com.openggf.game.sonic3k.objects`. Extends `AbstractObjectInstance`, implements `PowerUpObject`.

#### Embedded Data Tables (from disassembly, hardcoded — no runtime ROM reads)

**Orbit table** (byte_189A0): 32 signed X,Y pairs forming a ~16px radius circle:
```
{ 15, 0}, { 15, 3}, { 14, 6}, { 13, 8}, { 11, 11}, { 8, 13}, { 6, 14}, { 3, 15},
{ 0, 16}, { -4, 15}, { -7, 14}, { -9, 13}, {-12, 11}, {-14, 8}, {-15, 6}, {-16, 3},
{-16, 0}, {-16, -4}, {-15, -7}, {-14, -9}, {-12,-12}, { -9,-14}, { -7,-15}, { -4,-16},
{ -1,-16}, { 3,-16}, { 6,-15}, { 8,-14}, { 11,-12}, { 13, -9}, { 14, -7}, { 15, -4}
```

**Parent animation** (byte_189E0): `{8, 5, 7, 6, 6, 7, 5, 8, 6, 7, 7, 6}` (loops)

**Child animation tables** (4 tables from off_187DE, each with primary + secondary sequences):
- Star 0 (byte_189ED): primary `{8,7,6,5,4,3,4,5,6,7}`, secondary `{3,4,5,6,7,8,7,6,5,4}`
- Star 1 (byte_18A02): primary `{8,7,6,5,4,3,2,3,4,5,6,7}`, secondary `{2,3,4,5,6,7,8,7,6,5,4,3}`
- Star 2 (byte_18A1B): primary `{7,6,5,4,3,2,1,2,3,4,5,6}`, secondary `{1,2,3,4,5,6,7,6,5,4,3,2}`
- Star 3: from implicit off_187DE-6 entry (to be verified against disasm during implementation)

**Per-star init values** (from off_187DE): angle offsets and secondary animation offsets.

#### Rendering Logic (`appendRenderCommands`)

Each frame draws 5 groups x 2 sub-sprites = 10 total sprites:

1. **Parent group**: center = player position, orbit angle advances by 9 entries/frame (masked to 32), draw sub-sprite A at `orbit[angle]`, sub-sprite B at `orbit[(angle + 16) % 32]`
2. **Child groups 0-3**: center = `player.getCentreX/Y(starIndex * 3)`, orbit angle advances by 1 entry/frame, same 2-sub-sprite orbit drawing

Rotation direction: increment is positive when facing right, negated when facing left (matching ROM `btst #Status_Facing`).

Animation: each group independently advances through its animation table, selecting the mapping frame index for both sub-sprites (primary sequence for sub-sprite A, secondary for sub-sprite B).

### 5. Spawner Integration (`DefaultPowerUpSpawner.java`)

Modify `spawnInvincibilityStars()` to detect S3K:

```java
public PowerUpObject spawnInvincibilityStars(PlayableEntity player) {
    AbstractObjectInstance stars;
    if (GameModuleRegistry.getCurrent() instanceof Sonic3kGameModule) {
        stars = new Sonic3kInvincibilityStarsObjectInstance(player);
    } else {
        stars = new InvincibilityStarsObjectInstance(player);
    }
    registerObject(stars);
    return (PowerUpObject) stars;
}
```

### 6. Tests

Unit tests (no ROM/OpenGL required):

- `testTrailingFramesBehind` — verify starIndex * 3 mapping (0, 3, 6, 9)
- `testOrbitAngleWrapping` — verify angle stays within 0-31 with both rotation speeds
- `testDirectionReversal` — verify rotation negates when facing left

## What Stays Unchanged

- `AbstractPlayableSprite.giveInvincibility()` — timer (1200 frames), shield hiding, music
- `PlayableEntity.getCentreX/Y(framesAgo)` — position history ring buffer
- `ObjectRenderManager.getInvincibilityStarsRenderer()` / `ObjectArtKeys.INVINCIBILITY_STARS`
- `InvincibilityStarsObjectInstance` — S1/S2 behavior untouched
- `Sonic3kMonitorObjectInstance` — already calls `giveInvincibility()` correctly
- `PowerUpObject` interface lifecycle — destroyed when timer expires

## Scope Exclusions

- No palette cycling/flashing on the player sprite during invincibility (that's a separate visual effect not part of Obj_Invincibility)
- No Super/Hyper Sonic star interactions (existing `giveInvincibility()` already skips when Super)
- No Player 2 star support (P2 invincibility stars use a separate RAM region in ROM; out of scope)
