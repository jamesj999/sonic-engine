# S3K Insta-Shield Implementation Design

## Summary

Implement the Sonic 3&K insta-shield ability: a momentary hitbox expansion and visual effect triggered when Sonic presses jump mid-air without any elemental shield equipped. This is Sonic's "bare" double-jump ability — the fallback when no shield is present.

The implementation must be ROM-accurate and support cross-game feature donation (S3K donor → S1/S2 base game).

## ROM Reference

- **Activation:** sonic3k.asm:23473-23479 (`Sonic_InstaShield`)
- **Shield ability decision tree:** sonic3k.asm:23397-23479 (`Sonic_ShieldMoves`)
- **Hitbox expansion:** sonic3k.asm:20620-20640 (touch response insta-shield path)
- **Visual object:** sonic3k.asm:34566-34611 (`Obj_InstaShield` / `Obj_InstaShield_Main`)
- **Animation:** `General/Sprites/Shields/Anim - Insta-Shield.asm` (2 scripts, 8 mapping frames)
- **Mappings:** `General/Sprites/Shields/Map - Insta-Shield.asm` (8 frames, 0-7, frame 7 = empty)
- **DPLC:** `General/Sprites/Shields/DPLC - Insta-Shield.asm` (8 entries)
- **Art:** `General/Sprites/Shields/Insta-Shield.bin` (1664 bytes uncompressed, 52 tiles)

## ROM Addresses (Verified, S&K Side)

| Constant | Address | Notes |
|----------|---------|-------|
| `ART_UNC_INSTA_SHIELD_ADDR` | `0x18C084` | 1664 bytes uncompressed |
| `ART_UNC_INSTA_SHIELD_SIZE` | `1664` | 52 tiles × 32 bytes |
| `ANI_INSTA_SHIELD_ADDR` | `0x0199EA` | 2 animation scripts |
| `ANI_INSTA_SHIELD_COUNT` | `2` | Script 0: hold frame 6; Script 1: attack sequence |
| `MAP_INSTA_SHIELD_ADDR` | `0x01A0D0` | 8 mapping frames |
| `DPLC_INSTA_SHIELD_ADDR` | `0x01A154` | 8 DPLC entries |

## Design

### 1. PhysicsFeatureSet: New `instaShieldEnabled` Flag

Add `boolean instaShieldEnabled` to `PhysicsFeatureSet`.

| Game | `elementalShieldsEnabled` | `instaShieldEnabled` |
|------|--------------------------|---------------------|
| S1 | false | false |
| S2 | false | false |
| S3K | true | true |
| Hybrid (S3K donor) | false | **true** |

This separates the insta-shield gate from the elemental shield gate. Elemental abilities (fire dash, lightning jump, bubble bounce) require `elementalShieldsEnabled` because they need S3K monitors to acquire shields. The insta-shield activates when Sonic has *no* shield, making it valid in cross-game donation mode.

**Files:**
- `PhysicsFeatureSet.java` — add field + accessor
- `Sonic1PhysicsProvider` — pass `false`
- `Sonic2PhysicsProvider` — pass `false`
- `Sonic3kPhysicsProvider` — pass `true`
- `CrossGameFeatureProvider.buildHybridFeatureSet()` — pass `true` when donor is S3K, `false` for S2

### 2. Ability Activation in PlayableSpriteMovement

Modify `tryShieldAbility()` to handle the insta-shield as the null-shield fallback.

**Priority chain** (matching sonic3k.asm:23397-23479):

```
1. Neither elementalShieldsEnabled nor instaShieldEnabled → return false
2. Super Sonic (sprite.isSuperSonic()) → set doubleJumpFlag=1, return true (suppress all abilities)
3. Invincible (sprite.getInvulnerable()) → return false (ROM: btst #Status_Invincible at line 23412)
4. shield == FIRE → fireShieldDash()         [requires elementalShieldsEnabled]
5. shield == LIGHTNING → lightningShieldJump() [requires elementalShieldsEnabled]
6. shield == BUBBLE → bubbleShieldBounce()     [requires elementalShieldsEnabled]
7. shield == BASIC → return false (S2 blue shield has no ability; ROM: btst #Status_Shield at line 23474)
8. shield == null && instaShieldEnabled → activateInstaShield()
```

**`activateInstaShield()` method:**
- Sets `doubleJumpFlag = 1`
- Triggers the persistent `InstaShieldObjectInstance` by setting its animation to 1 (attack sequence)
- Plays `GameSound.INSTA_SHIELD` SFX (already mapped to S3K SFX 0x42)

**Super Sonic gate (sonic3k.asm:23404-23408):**
When `sprite.isSuperSonic()` is true, set `doubleJumpFlag = 1` and return `true` without activating any ability. This prevents Super Sonic from using insta-shield or any elemental ability.

**Invincibility gate (sonic3k.asm:23412-23413):**
When invincibility stars are active (`Status_Invincible`), all abilities are suppressed — return `false` without setting `doubleJumpFlag`. This applies to fire/lightning/bubble AND insta-shield. Note: the existing elemental abilities are also missing this gate; it should be added to the shared path before the shield-type switch.

Hyper Sonic uses a different ability (`Sonic_HyperDash`) — deferred to future work per user direction.

### 3. Hitbox Expansion in TouchResponses

Modify `TouchResponses.update()` to expand the player's collision box when the insta-shield is active.

**ROM mechanism (sonic3k.asm:20620-20640):**
The ROM temporarily sets `Status_Invincible`, uses a 48×48 hitbox for `Touch_Process`, then clears `Status_Invincible`. This makes Sonic destroy all overlapping enemies without taking damage.

**Implementation:**

Before the object loop in `update()`, check for insta-shield expansion. The ROM uses a `$73` mask (`Status_Invincible | Status_Shield | Status_FireShield | Status_LtngShield | Status_BublShield`) at line 20617 to skip the expansion when the player has any shield or invincibility. This prevents edge cases where a shield is acquired mid-animation:
```java
boolean instaShieldActive = false;
PhysicsFeatureSet fs = player.getPhysicsFeatureSet();
if (fs != null && fs.instaShieldEnabled()
        && player.getDoubleJumpFlag() == 1
        && player.getShieldType() == null     // ROM: $73 mask — no shield present
        && !player.getInvulnerable()) {       // ROM: $73 mask — not invincible
    instaShieldActive = true;
    // ROM: d2 = x_pos - $18, d3 = y_pos - $18, d4 = $30, d5 = $30
    // Overwrites normal playerX/playerY/playerHeight entirely
    playerX = player.getCentreX() - 0x18;     // 24px offset (vs normal 8px)
    playerY = player.getCentreY() - 0x18;     // 24px offset (vs normal yRadius-based)
    playerHeight = 0x30;                      // 48px height (vs normal yRadius*2)
    // Player width passed to isOverlapping: 0x30 (vs normal 0x10)
}
```

**`isOverlapping()` change:** Add a `playerWidth` parameter to replace the hardcoded `> 0x10` X-axis threshold. When `instaShieldActive`, pass `0x30`; otherwise pass `0x10`. The Y-axis uses the already-overwritten `playerY` and `playerHeight` variables, so no Y-axis change to `isOverlapping()` is needed. Same change for `isOverlappingXY()`.

**Inside the object loop:** When `instaShieldActive` is true and an enemy-category touch overlaps, resolve as enemy destruction without checking hurt. Rather than temporarily setting `invincibleFrames` (which has side effects like hiding shield objects), add `instaShieldActive` as an additional condition in `isPlayerAttacking()` — treating it the same as rolling/jumping for the purpose of enemy destruction. This is functionally equivalent to the ROM's temporary `Status_Invincible` trick but avoids polluting the invincibility state.

**No deflection:** The insta-shield does NOT participate in the `shield_reaction` projectile deflection system. It grants temporary invincibility through the expanded hitbox, not shield-specific deflection bits.

**Sidekick:** The `updateSidekick()` path does not need insta-shield logic — only Sonic uses it.

### 4. InstaShieldObjectInstance (Visual) — Persistent Model

**New file: `com.openggf.game.sonic3k.objects.InstaShieldObjectInstance`**

Extends `ShieldObjectInstance`, follows the same DPLC-driven pattern as `FireShieldObjectInstance`.

**ROM-accurate persistent lifecycle:** In the ROM, `Obj_InstaShield` lives in the dedicated `(Shield)` RAM slot for the entire level (initialized at `SpawnLevelMainSprites_SpawnPlayers`, line 8361). It idles on anim 0 (empty mapping frame — nothing renders) until `Sonic_InstaShield` sets `(Shield+anim)` to 1 to trigger the attack animation. When elemental shields are destroyed, their destroy routines replace themselves with `Obj_InstaShield` in the same slot.

Our implementation follows this model. The `InstaShieldObjectInstance` is created once and stored in a dedicated `instaShieldObject` field on `AbstractPlayableSprite` (separate from `shieldObject` which holds elemental shields). It persists for the level's lifetime.

**Comparison with elemental shields:**

| Aspect | Elemental Shields | Insta-Shield |
|--------|------------------|--------------|
| Lifecycle | Created on pickup, destroyed on damage/water | Created at level init, persists for level |
| Storage | `sprite.shieldObject` | `sprite.instaShieldObject` (new field) |
| Idle state | Loops idle animation (visible) | Anim 0 = empty frame (invisible, no-op render) |
| Attack state | `setAnimation(1)` on ability use | `setAnimation(1)` on ability use |
| doubleJumpFlag | Not managed by shield object | Transitions 1→2 at frame 7 |

**Shield slot transitions:**
- Level init (`instaShieldEnabled`): create `InstaShieldObjectInstance`, store in `sprite.instaShieldObject`
- `giveShield(FIRE/LIGHTNING/BUBBLE)`: elemental shield goes into `sprite.shieldObject`. Insta-shield object stays in its field but is not rendered (elemental shield takes visual priority via `shieldObject != null` check)
- Elemental shield destroyed (damage/water): `sprite.shieldObject` cleared. Insta-shield object resumes rendering on next activation
- Ability activation: `sprite.instaShieldObject.setAnimation(1)` — no allocation, no registration

**Constructor:**
- Takes `AbstractPlayableSprite` parent reference
- Loads art via `Sonic3kObjectArtProvider.getShieldDplcRenderer(INSTA_SHIELD)` (native S3K) or `CrossGameFeatureProvider` (donation mode)
- Starts on animation index 0 (idle — empty frame)

**`update()` per frame:**
1. Step animation
2. If `mapping_frame == 7` (final frame of attack) and parent's `doubleJumpFlag == 1`:
   - Set parent's `doubleJumpFlag = 2` (attack over, normal hitbox resumes)
3. Animation's SWITCH end-action returns to anim 0 (idle) automatically

**`appendRenderCommands()`:**
- If `shieldObject != null` (elemental shield active): don't render (elemental takes priority)
- If current mapping frame has 0 pieces (anim 0 idle): don't render (nothing to draw)
- Otherwise: DPLC renderer at parent's centre position, inheriting facing direction
- Hidden when parent has `Status_Invincible` (star sparkles take visual priority)
- Wireframe fallback: white/cyan diamond, 24px half-size

**Animation scripts:**
- **Anim 0 (idle):** `delay=0x1F, frames: [6], endAction: HOLD` — frame 6 maps to `word_1A152` which has `dc.w 0` (0 pieces = invisible). This is the default state.
- **Anim 1 (attack):** `delay=0, frames: [0, 0, 1, 2, 3, 4, 5, 6, 6, 6, 6, 6, 6, 6, 7], endAction: SWITCH to anim 0` — plays the attack sequence, then returns to idle.

**Initialization site:** When `instaShieldEnabled` is true, the insta-shield object is created during player sprite initialization (alongside spindash dust, shield object, etc.). For cross-game donation, this happens when `CrossGameFeatureProvider.isActive()` and the donor is S3K.

### 5. Art Loading

**File: `Sonic3kObjectArtProvider.loadShieldArt()`**

Add one more `loadSingleShieldArt()` call:
```java
loadSingleShieldArt(reader, Sonic3kObjectArtKeys.INSTA_SHIELD,
    Sonic3kConstants.ART_UNC_INSTA_SHIELD_ADDR, Sonic3kConstants.ART_UNC_INSTA_SHIELD_SIZE,
    Sonic3kConstants.MAP_INSTA_SHIELD_ADDR, Sonic3kConstants.DPLC_INSTA_SHIELD_ADDR,
    Sonic3kConstants.ANI_INSTA_SHIELD_ADDR, Sonic3kConstants.ANI_INSTA_SHIELD_COUNT,
    Sonic3kConstants.ART_TILE_SHIELD, 0);
```

**File: `Sonic3kObjectArtKeys.java`**

Add: `public static final String INSTA_SHIELD = "insta_shield";`

**File: `Sonic3kConstants.java`**

Add the 6 ROM address constants from the table above.

### 6. Cross-Game Feature Donation

**File: `CrossGameFeatureProvider.java`**

Add a method to load insta-shield art from the donor ROM:

```java
public PlayerSpriteRenderer getInstaShieldRenderer() { ... }
public SpriteArtSet getInstaShieldArtSet() { ... }
```

These load the insta-shield art/map/dplc/anim from the S3K donor ROM using the same `loadSingleShieldArt()` pattern. Loaded eagerly during `CrossGameFeatureProvider.initialize()` alongside the other donor assets.

**`InstaShieldObjectInstance` art resolution order:**
1. If `CrossGameFeatureProvider.isActive()` → use donor art
2. Else if `Sonic3kObjectArtProvider` available → use native S3K art
3. Else → wireframe fallback

### 7. doubleJumpFlag State Lifecycle

No new states. The existing 3-state system matches the ROM exactly:

| State | Meaning | Set by | Cleared by |
|-------|---------|--------|------------|
| 0 | Ready for ability | Landing via `resetOnFloor()` | — |
| 1 | Attacking (expanded hitbox, enemy invincibility) | `tryShieldAbility()` | `InstaShieldObjectInstance` at frame 7 |
| 2 | Post-attack (normal hitbox, visual fading) | `InstaShieldObjectInstance` at frame 7 | Landing via `resetOnFloor()` |

The 1→2 transition happens inside `InstaShieldObjectInstance.update()` when `mapping_frame == 7`, matching sonic3k.asm:34607-34611. The animation then SWITCHes back to anim 0 (idle), and the object is ready for the next activation.

## Files Changed

| File | Change |
|------|--------|
| `PhysicsFeatureSet.java` | Add `instaShieldEnabled` field (record — all construction sites update) |
| `Sonic1PhysicsProvider.java` | Pass `instaShieldEnabled = false` |
| `Sonic2PhysicsProvider.java` | Pass `instaShieldEnabled = false` |
| `Sonic3kPhysicsProvider.java` | Pass `instaShieldEnabled = true` |
| `CrossGameFeatureProvider.java` | Set `instaShieldEnabled = "s3k".equalsIgnoreCase(donorGameId)` in `buildHybridFeatureSet()`; add insta-shield art loading methods |
| `PlayableSpriteMovement.java` | Add invincibility gate, Super Sonic gate, and insta-shield branch in `tryShieldAbility()` |
| `ObjectManager.java` (TouchResponses) | Hitbox expansion when `doubleJumpFlag == 1` with `$73` mask guard; `isOverlapping()`/`isOverlappingXY()` gain `playerWidth` param; `isPlayerAttacking()` gains `instaShieldActive` condition |
| `InstaShieldObjectInstance.java` (new) | Persistent DPLC visual, idles on anim 0, transitions doubleJumpFlag 1→2 at frame 7 |
| `AbstractPlayableSprite.java` | Add `instaShieldObject` field, create during init when `instaShieldEnabled` |
| `Sonic3kObjectArtKeys.java` | Add `INSTA_SHIELD` key |
| `Sonic3kObjectArtProvider.java` | Add insta-shield to `loadShieldArt()` |
| `Sonic3kConstants.java` | Add 6 ROM address constants |
| `TestHybridPhysicsFeatureSet.java` | Update for new `instaShieldEnabled` parameter in `PhysicsFeatureSet` record |

## Known Omissions

- **Reverse gravity:** ROM's `Obj_InstaShield_Main` (line 34594) checks `Reverse_gravity_flag` to flip vertical rendering. Not applicable to current engine state; deferred.
- **Hyper Dash:** Super Emerald Hyper Sonic ability (`Sonic_HyperDash`) uses a different code path. Deferred per user direction.

## Testing Strategy

1. **Unit test: `TestInstaShieldGating`** — Verify activation conditions: fires when `instaShieldEnabled && shield == null && !super && !invincible`, blocked when super, blocked when invincible, blocked when shield equipped (including BASIC), blocked when `doubleJumpFlag != 0`. Uses `TestableSprite` pattern (no ROM/OpenGL).

2. **Unit test: `TestInstaShieldHitbox`** — Verify hitbox expansion in TouchResponses: 48×48 when `doubleJumpFlag == 1` and no shield/invincibility, normal when 0 or 2. Verify enemy destruction (not hurt) during active state. Also verify expanded hitbox does NOT activate when player has a shield (even if `doubleJumpFlag == 1`).

3. **Integration test (headless):** Load AIZ1, activate insta-shield mid-jump near a badnik, verify enemy is destroyed and Sonic takes no damage. Verify `doubleJumpFlag` transitions 0→1→2→0 across jump/attack/land.
