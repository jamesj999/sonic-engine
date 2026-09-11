# Cross-Game Bidirectional Feature & Animation Donation

## Problem

Feature donation currently only works backward: S2/S3K sprites donated *into* S1. Forward donation (e.g. S1 sprites into S3K) crashes because:

1. `CrossGameFeatureProvider` hardcodes S2/S3K as donors with no S1 path.
2. Animation IDs are not contiguous between games. When the host game requests an animation the donor doesn't have (e.g. S3K requests BLINK from S1), `SpriteAnimationSet.getScript()` returns null, causing a crash in `PlayableSpriteAnimation`.

The three games have overlapping but non-identical animation sets:
- S1: 30 animations (0x00-0x1E) — has WARP1-4, no SPINDASH
- S2: 32 animations (0x00-0x20) — has SPINDASH, SKID, FLY
- S3K: 36 animations (0x00-0x23) — adds BLINK, GET_UP, VICTORY, GLIDE states

## Design

### 1. CanonicalAnimation Enum

A shared vocabulary covering the union of all animations across all three games. Each game's `AnimationIds` enum maps to/from this canonical set.

```java
public enum CanonicalAnimation {
    // Core movement (all games)
    WALK, RUN, ROLL, ROLL2, PUSH, WAIT, DUCK, LOOK_UP, SPRING,
    BALANCE, BALANCE2, BALANCE3, BALANCE4,

    // Combat/state (all games)
    HURT, DEATH, DROWN,

    // S1-specific
    STOP,   // S1's braking animation (0x0D)
    WARP1, WARP2, WARP3, WARP4,
    FLOAT3, FLOAT4,  // S1-only float variants (0x1D, 0x1E)
    LEAP1, LEAP2, SURF, GET_AIR, BURNT, SHRINK, WATER_SLIDE,
    NULL_ANIM,  // S1's null/blank animation (0x1C)

    // Shared across S1/S2/S3K (present in all but at different IDs)
    FLOAT, FLOAT2,  // S1: 0x0E/0x0F, S2: 0x0E/0x0F, S3K: 0x0E/0x0F
    HANG,           // All games: 0x11

    // S2+ (spindash era)
    SPINDASH, SKID, SLIDE, HANG2, BUBBLE,
    HURT2,  // Tails variant
    FLY,    // Tails flight

    // S3K-specific
    BLINK, GET_UP, VICTORY, BLANK, HURT_FALL,
    GLIDE_DROP, GLIDE_LAND, GLIDE_SLIDE,

    // Super variants
    SUPER_TRANSFORM
}
```

**FLOAT disambiguation:** `FLOAT` and `FLOAT2` are present in all three games (identity mapping). `FLOAT3` and `FLOAT4` are S1-only and require fallbacks in S2/S3K donors.

**Naming bridge:** S1's enum uses `FLOAT1` (0x0E) where S2/S3K use `FLOAT`. When implementing `Sonic1AnimationIds.toCanonical()`, map `S1.FLOAT1 -> CanonicalAnimation.FLOAT`.

**`airAnimId` note:** All three games set `airAnimId = WALK` in their profiles. The canonical enum has no separate AIR entry. The translator must always set `airAnimId` to the donor's WALK animation ID.

Each game's `AnimationIds` enum gains:
- `toCanonical()` — returns the corresponding `CanonicalAnimation`
- Static `fromCanonical(CanonicalAnimation)` — returns the game-local int ID, or -1 if unsupported

### 2. DonorCapabilities Interface

A new interface returned by `GameModule.getDonorCapabilities()` that declares what a game can export when used as a donor.

```java
public interface DonorCapabilities {
    /** Characters this game has art for */
    Set<PlayerCharacter> getPlayableCharacters();

    boolean hasSpindash();
    boolean hasSuperTransform();
    boolean hasHyperTransform();
    boolean hasInstaShield();
    boolean hasElementalShields();
    boolean hasSidekick();

    /**
     * Maps each CanonicalAnimation to the best available substitute
     * in this game's animation set. Animations the game natively has
     * map to themselves. Animations it doesn't have map to the closest
     * equivalent.
     */
    Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks();

    /** Art provider for loading this game's player sprites */
    PlayerSpriteArtProvider getPlayerArtProvider(RomByteReader reader);
}
```

`GameModule` gains a new default method:
```java
default DonorCapabilities getDonorCapabilities() { return null; }
```

Each concrete game module (`Sonic1GameModule`, `Sonic2GameModule`, `Sonic3kGameModule`) returns a properly filled implementation.

#### Capability Matrix

| Capability | S1 | S2 | S3K |
|---|---|---|---|
| Characters | Sonic | Sonic, Tails | Sonic, Tails, Knuckles |
| Spindash | no | yes | yes |
| Super Transform | no | yes | yes |
| Hyper Transform | no | no | yes |
| Insta-shield | no | no | yes |
| Elemental shields | no | no | yes |
| Sidekick | no | yes | yes |

### 3. AnimationTranslator

Builds a pre-translated `ScriptedVelocityAnimationProfile` at art load time. Zero per-frame overhead.

```java
public class AnimationTranslator {
    /**
     * Builds a translated profile from the donor's existing profile and
     * animation set, ensuring all animation ID fields resolve to valid
     * scripts in the donor's SpriteAnimationSet.
     *
     * For each animation state (idle, walk, hurt, etc.):
     * 1. Map the host's expected CanonicalAnimation to the donor's fallback table
     * 2. Resolve the fallback CanonicalAnimation to the donor's native ID
     * 3. Verify the script exists in the donor's SpriteAnimationSet
     * 4. Assign the donor's native ID into the translated profile field
     *
     * Non-animation profile properties (anglePreAdjust, compactSuperRunSlope,
     * walkSpeedThreshold, runSpeedThreshold, fallbackFrame) are always
     * preserved from the donor's profile, since they must match the donor's
     * art layout (e.g. slope frame multiplier depends on mapping frame count).
     *
     * SWITCH end-action validation: since the entire SpriteAnimationSet comes
     * from the donor, SWITCH targets within the donor's own scripts are
     * already valid (they reference other donor-native IDs). The translator
     * only needs to verify that no SWITCH target references a script that
     * was excluded during loading. If a target is invalid, it is remapped
     * to the donor's WAIT animation as a safe fallback.
     */
    public static ScriptedVelocityAnimationProfile translate(
            DonorCapabilities donor,
            ScriptedVelocityAnimationProfile donorProfile,
            SpriteAnimationSet donorAnimSet) { ... }
}
```

The translation runs once during `CrossGameFeatureProvider.loadPlayerSpriteArt()`. The resulting profile has all ID fields pre-resolved to donor-native IDs with guaranteed backing scripts. `PlayableSpriteAnimation` never knows translation happened.

**SpriteArtSet immutability:** `SpriteArtSet` is a record. After translation, `CrossGameFeatureProvider` creates a new `SpriteArtSet` instance with the translated profile replacing the donor's original:

```java
SpriteArtSet donorArt = donorArtProvider.loadPlayerSpriteArt(characterCode);
ScriptedVelocityAnimationProfile translated = AnimationTranslator.translate(
        donorCapabilities, donorArt.animationProfile(), donorArt.animationSet());
return new SpriteArtSet(donorArt.artTiles(), donorArt.mappingFrames(),
        donorArt.dplcFrames(), donorArt.paletteIndex(), donorArt.basePatternIndex(),
        donorArt.frameDelay(), donorArt.bankSize(), translated, donorArt.animationSet());
```

### 4. Super Sonic Animation Set Handling

S2 and S3K use a **separate** `SpriteAnimationSet` for Super Sonic, swapped in by `SuperStateController.onSuperActivated()` and swapped back on revert. This requires special handling:

- **Donor has Super Transform (S2/S3K donor):** The donor provides both normal and Super animation sets. The Super `SpriteAnimationSet` is loaded from the donor ROM and stored directly on the `SuperStateController` (as the existing code already does — controllers store `SpriteAnimationSet`, not `SpriteArtSet`). The Super animation scripts within this set are validated by the translator to ensure all SWITCH targets are valid. `CrossGameFeatureProvider.createSuperStateController()` uses the donor's `SuperStateController` implementation and pre-loads the donor's Super art. At runtime, `onSuperActivated()` swaps in the donor's Super `SpriteAnimationSet` via `player.setAnimationSet()`, and the base class `swapToSuperAnimProfile()` creates a modified profile copy with the Super run threshold — both operating on the sprite's fields directly, not through `SpriteArtSet`.

- **Donor lacks Super Transform (S1 donor):** `DonorCapabilities.hasSuperTransform()` returns false. `CrossGameFeatureProvider.createSuperStateController()` returns null. The host game's Super transformation code checks for a null controller and skips transformation entirely — the emeralds are collected but no transformation occurs. This is the intended "challenge mode" behavior.

- **Cross-game Super art:** When an S2 donor provides Super Sonic for an S3K host, the S2 Super animation set is used with its own mapping frames. The S2 Super profile's `anglePreAdjust` and `compactSuperRunSlope` flags are preserved since they must match the S2 Super art layout.

### 5. Refactored CrossGameFeatureProvider

The provider becomes a thin coordinator that queries `DonorCapabilities` instead of hardcoded if/else chains.

**Key changes:**

- **Same-game guard:** If donor game ID matches host game ID, donation is disabled (early return, `active = false`).
- **Art loading:** Delegates to `donorCapabilities.getPlayerArtProvider(donorReader)` instead of `if s3k -> Sonic3kPlayerArt else -> Sonic2PlayerArt`.
- **Hybrid feature set:** Built from `donorCapabilities` booleans. `!hasSpindash()` -> spindash disabled. `!hasInstaShield()` -> insta-shield disabled. Etc.
- **Character gating:** `donorCapabilities.getPlayableCharacters()` determines available characters. The host's character select disables unavailable ones.
- **Animation translation:** After loading donor art, calls `AnimationTranslator.translate()` to build a host-safe profile.
- **Sidekick:** `donorCapabilities.hasSidekick()` replaces the current hardcoded check.

**What stays the same:** Donor render context / palette isolation, secondary ROM opening, audio donation, singleton lifecycle.

### 5. Per-Game Fallback Tables

Each game defines what it substitutes for animations it doesn't have. Animations the game natively has map to themselves (omitted below).

#### S1 Fallbacks (donor for S2/S3K hosts)

| Host requests | S1 provides | Rationale |
|---|---|---|
| SPINDASH | DUCK | Closest crouching pose |
| SKID | STOP | S1's braking animation |
| SLIDE | ROLL | Sliding motion |
| BLINK | WAIT | Idle variant |
| GET_UP | WAIT | Recovery to idle |
| VICTORY | WAIT | No celebration pose |
| GLIDE_DROP | SPRING | Falling pose |
| GLIDE_LAND | WAIT | Landing recovery |
| GLIDE_SLIDE | PUSH | Ground sliding motion |
| HANG2 | HANG | S1 has basic hang |
| BALANCE2 | BALANCE | Single balance state |
| BALANCE3 | BALANCE | Single balance state |
| BALANCE4 | BALANCE | Single balance state |
| BLANK | WAIT | Invisible state -> idle |
| FLY | SPRING | N/A (Tails unavailable, table completeness) |
| SUPER_TRANSFORM | WAIT | No transformation |
| BUBBLE | GET_AIR | Breathing animation |
| HURT2 | HURT | Single hurt animation |
| HURT_FALL | HURT | Falling hurt variant |

#### S2 Fallbacks (donor for S1/S3K hosts)

| Host requests | S2 provides | Rationale |
|---|---|---|
| STOP | SKID | S2 equivalent braking animation |
| WARP1-4 | ROLL | Closest spinning motion |
| FLOAT3/4 | SPRING | S1-only float variants -> airborne pose |
| BLINK | WAIT | No idle interrupt |
| GET_UP | WAIT | No recovery anim |
| VICTORY | WAIT | No celebration |
| GLIDE_DROP | SPRING | Falling |
| GLIDE_LAND | WAIT | Landing |
| GLIDE_SLIDE | SLIDE | Ground sliding |
| LEAP1/2 | SPRING | Airborne |
| SURF | WAIT | No surfboard |
| GET_AIR | BUBBLE | Closest breathing animation |
| WATER_SLIDE | SLIDE | Sliding motion |
| BURNT | HURT | Damage variant |
| SHRINK | DEATH | End-of-level shrink |
| BLANK | WAIT | Invisible -> idle |
| HURT_FALL | HURT | Falling hurt variant |
| NULL_ANIM | WAIT | S1 null animation -> idle |

#### S3K Fallbacks (donor for S1/S2 hosts)

| Host requests | S3K provides | Rationale |
|---|---|---|
| STOP | SKID | S3K equivalent braking animation |
| WARP1-4 | ROLL | No warp zones |
| FLOAT3/4 | SPRING | S1-only float variants -> airborne |
| LEAP1/2 | SPRING | Airborne |
| SLIDE | HURT_FALL | S3K repurposed 0x1B as HURT_FALL, closest sliding equivalent |
| SURF | WAIT | No surfboard |
| GET_AIR | BLANK | Closest breathing equivalent |
| WATER_SLIDE | HURT_FALL | Sliding/falling motion |
| BURNT | HURT | Damage variant |
| SHRINK | DEATH | End-of-level shrink |
| NULL_ANIM | BLANK | Blank/invisible animation |

S3K has the fewest fallbacks since it is the most feature-complete animation set.

These tables are tunable — initial best-guess mappings that can be refined through playtesting.

## Feature Donation Behavior Matrix

When playing as host with a donor, the donor's capability flags gate features:

| S3K Feature | S1 Donor | S2 Donor |
|---|---|---|
| Elemental shields | Plain shield substituted | Plain shield substituted |
| Insta-shield | Disabled | Disabled |
| Super Transform | Not available | Available |
| Hyper Transform | Not available | Not available |
| Spindash | Disabled | Available |
| Play as Tails | Not available | Available |
| Play as Knuckles | Not available | Not available |
| AI Sidekick | Disabled | Available |
| Cutscene Knuckles | Host art (persists) | Host art (persists) |
| Cutscene Tails events | Host art (persists) | Host art (persists) |

NPC/cutscene appearances always use the host game's own object art — only playable characters use donor sprites.

## Invariants

- `PlayableSpriteAnimation` is never modified — the translation is invisible to it.
- Every animation ID in a translated profile has a backing script in the donor's `SpriteAnimationSet`.
- SWITCH end-action targets within donor scripts are valid by construction (same animation set). Invalid targets (excluded scripts) fall back to donor WAIT.
- Same-game donation (donor == host) is a no-op (early return, `active = false`).
- The hot path (per-frame animation update) has zero translation overhead.
- Non-animation profile properties (`anglePreAdjust`, `compactSuperRunSlope`, thresholds) always come from the donor, not the host.
- `SpriteArtSet` remains an immutable record — translation creates a new instance with the translated profile.
- When `DonorCapabilities.hasSuperTransform()` is false, `createSuperStateController()` returns null and the host skips transformation.
- Fallback maps must contain identity entries for animations the donor natively supports. Any `CanonicalAnimation` absent from the map is treated as unsupported (falls back to WAIT).
- `GameModule.supportsSidekick()` governs the host game's native sidekick support. When donation is active, `CrossGameFeatureProvider` uses `donorCapabilities.hasSidekick()` instead.

## Net New Types

| Type | Package | Purpose |
|---|---|---|
| `CanonicalAnimation` | `com.openggf.game` | Shared animation vocabulary enum |
| `DonorCapabilities` | `com.openggf.game` | Interface for exportable game features |
| `AnimationTranslator` | `com.openggf.sprites.animation` | One-shot profile translation |

## Modified Types

| Type | Change |
|---|---|
| `GameModule` | Add `getDonorCapabilities()` default method |
| `Sonic1GameModule` | Implement `getDonorCapabilities()` |
| `Sonic2GameModule` | Implement `getDonorCapabilities()` |
| `Sonic3kGameModule` | Implement `getDonorCapabilities()` |
| `Sonic1AnimationIds` | Add `toCanonical()` / `fromCanonical()` |
| `Sonic2AnimationIds` | Add `toCanonical()` / `fromCanonical()` |
| `Sonic3kAnimationIds` | Add `toCanonical()` / `fromCanonical()` |
| `CrossGameFeatureProvider` | Refactor to use `DonorCapabilities`, add same-game guard |
