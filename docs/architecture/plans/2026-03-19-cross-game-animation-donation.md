# Cross-Game Bidirectional Animation Donation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable any-direction feature donation between S1, S2, and S3K with safe animation fallback mapping, so that any game's sprites can be used in any other game without crashes.

**Architecture:** A `CanonicalAnimation` enum provides a shared animation vocabulary. Each `GameModule` declares its exportable features via a `DonorCapabilities` interface. An `AnimationTranslator` builds pre-translated animation profiles at load time so the hot path has zero overhead. `CrossGameFeatureProvider` becomes a thin coordinator querying capabilities instead of hardcoding per-game logic.

**Tech Stack:** Java 21, Maven, JUnit 5. No new dependencies.

**Spec:** `docs/architecture/designs/2026-03-19-cross-game-animation-donation-design.md`

---

## File Structure

### New Files
| File | Purpose |
|------|---------|
| `src/main/java/com/openggf/game/CanonicalAnimation.java` | Shared animation vocabulary enum (~45 entries) |
| `src/main/java/com/openggf/game/DonorCapabilities.java` | Interface declaring exportable game features |
| `src/main/java/com/openggf/sprites/animation/AnimationTranslator.java` | One-shot profile translation at art load time |
| `src/test/java/com/openggf/game/TestCanonicalAnimationMapping.java` | Tests canonical ↔ per-game ID round-trips |
| `src/test/java/com/openggf/sprites/animation/TestAnimationTranslator.java` | Tests translation produces valid profiles |
| `src/test/java/com/openggf/game/TestDonorCapabilities.java` | Tests capability declarations and fallback tables |
| `src/test/java/com/openggf/game/TestCrossGameFeatureProviderRefactor.java` | Tests same-game guard and capability-driven feature set |

### Modified Files
| File | Change |
|------|--------|
| `src/main/java/com/openggf/game/sonic1/constants/Sonic1AnimationIds.java` | Add `toCanonical()` / `fromCanonical()` |
| `src/main/java/com/openggf/game/sonic2/constants/Sonic2AnimationIds.java` | Add `toCanonical()` / `fromCanonical()` |
| `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kAnimationIds.java` | Add `toCanonical()` / `fromCanonical()` |
| `src/main/java/com/openggf/game/GameModule.java` | Add `getDonorCapabilities()` default method |
| `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java` | Implement `getDonorCapabilities()` |
| `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java` | Implement `getDonorCapabilities()` |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java` | Implement `getDonorCapabilities()` |
| `src/main/java/com/openggf/game/CrossGameFeatureProvider.java` | Refactor to use `DonorCapabilities`, add same-game guard, animation translation |
| `src/main/java/com/openggf/sprites/animation/ScriptedVelocityAnimationProfile.java` | Add missing `getHurtAnimId()` and `getSlideAnimId()` getters |

### Codebase Pitfalls (read before implementing)

**PlayerSpriteArtProvider adapter:** `Sonic1PlayerArt`, `Sonic2PlayerArt`, and `Sonic3kPlayerArt` do NOT implement `PlayerSpriteArtProvider`. They expose `loadForCharacter(String)` not `loadPlayerSpriteArt(String)`. The `DonorCapabilities.getPlayerArtProvider()` lambda must wrap them:
```java
// In Sonic1GameModule.getDonorCapabilities():
(reader) -> (characterCode) -> new Sonic1PlayerArt(reader).loadForCharacter(characterCode)
```

**Game identifier mismatch:** `GameModule.getIdentifier()` returns `"Sonic1"`/`"Sonic2"`/`"Sonic3k"`, but the donation config uses `"s1"`/`"s2"`/`"s3k"` (from `GameId.code()`). The same-game guard must normalize via `GameId`:
```java
GameId donorId = GameId.fromCode(donorGameId);  // "s2" -> GameId.S2
GameId hostId = GameId.fromCode(hostIdentifierToCode()); // "Sonic2" -> GameId.S2
```

**Missing getters:** `ScriptedVelocityAnimationProfile` has `hurtAnimId` and `slideAnimId` fields with setters but NO getters. The `AnimationTranslator` needs to read these. Add `getHurtAnimId()` and `getSlideAnimId()` getters before implementing the translator.

**Profile-field-to-canonical mapping (for AnimationTranslator):**
| Profile field | CanonicalAnimation |
|---|---|
| `idleAnimId` | `WAIT` |
| `walkAnimId` | `WALK` |
| `runAnimId` | `RUN` |
| `rollAnimId` | `ROLL` |
| `roll2AnimId` | `ROLL2` |
| `pushAnimId` | `PUSH` |
| `duckAnimId` | `DUCK` |
| `lookUpAnimId` | `LOOK_UP` |
| `spindashAnimId` | `SPINDASH` |
| `springAnimId` | `SPRING` |
| `deathAnimId` | `DEATH` |
| `hurtAnimId` | `HURT` |
| `skidAnimId` | `SKID` |
| `slideAnimId` | `SLIDE` |
| `drownAnimId` | `DROWN` |
| `airAnimId` | always = `walkAnimId` (no canonical entry) |
| `balanceAnimId` | `BALANCE` |
| `balance2AnimId` | `BALANCE2` |
| `balance3AnimId` | `BALANCE3` |
| `balance4AnimId` | `BALANCE4` |

---

## Task 1: CanonicalAnimation Enum

**Files:**
- Create: `src/main/java/com/openggf/game/CanonicalAnimation.java`
- Test: `src/test/java/com/openggf/game/TestCanonicalAnimationMapping.java`

- [ ] **Step 1: Write test verifying CanonicalAnimation enum has all expected entries**

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestCanonicalAnimationMapping {

    @Test
    void canonicalEnumContainsAllExpectedEntries() {
        // Core movement (all games)
        assertNotNull(CanonicalAnimation.valueOf("WALK"));
        assertNotNull(CanonicalAnimation.valueOf("RUN"));
        assertNotNull(CanonicalAnimation.valueOf("ROLL"));
        assertNotNull(CanonicalAnimation.valueOf("ROLL2"));
        assertNotNull(CanonicalAnimation.valueOf("PUSH"));
        assertNotNull(CanonicalAnimation.valueOf("WAIT"));
        assertNotNull(CanonicalAnimation.valueOf("DUCK"));
        assertNotNull(CanonicalAnimation.valueOf("LOOK_UP"));
        assertNotNull(CanonicalAnimation.valueOf("SPRING"));
        assertNotNull(CanonicalAnimation.valueOf("BALANCE"));
        assertNotNull(CanonicalAnimation.valueOf("BALANCE2"));
        assertNotNull(CanonicalAnimation.valueOf("BALANCE3"));
        assertNotNull(CanonicalAnimation.valueOf("BALANCE4"));
        // Combat/state
        assertNotNull(CanonicalAnimation.valueOf("HURT"));
        assertNotNull(CanonicalAnimation.valueOf("DEATH"));
        assertNotNull(CanonicalAnimation.valueOf("DROWN"));
        // S1-specific
        assertNotNull(CanonicalAnimation.valueOf("STOP"));
        assertNotNull(CanonicalAnimation.valueOf("WARP1"));
        assertNotNull(CanonicalAnimation.valueOf("WARP2"));
        assertNotNull(CanonicalAnimation.valueOf("WARP3"));
        assertNotNull(CanonicalAnimation.valueOf("WARP4"));
        assertNotNull(CanonicalAnimation.valueOf("FLOAT3"));
        assertNotNull(CanonicalAnimation.valueOf("FLOAT4"));
        assertNotNull(CanonicalAnimation.valueOf("LEAP1"));
        assertNotNull(CanonicalAnimation.valueOf("LEAP2"));
        assertNotNull(CanonicalAnimation.valueOf("SURF"));
        assertNotNull(CanonicalAnimation.valueOf("GET_AIR"));
        assertNotNull(CanonicalAnimation.valueOf("BURNT"));
        assertNotNull(CanonicalAnimation.valueOf("SHRINK"));
        assertNotNull(CanonicalAnimation.valueOf("WATER_SLIDE"));
        assertNotNull(CanonicalAnimation.valueOf("NULL_ANIM"));
        // Shared
        assertNotNull(CanonicalAnimation.valueOf("FLOAT"));
        assertNotNull(CanonicalAnimation.valueOf("FLOAT2"));
        assertNotNull(CanonicalAnimation.valueOf("HANG"));
        // S2+
        assertNotNull(CanonicalAnimation.valueOf("SPINDASH"));
        assertNotNull(CanonicalAnimation.valueOf("SKID"));
        assertNotNull(CanonicalAnimation.valueOf("SLIDE"));
        assertNotNull(CanonicalAnimation.valueOf("HANG2"));
        assertNotNull(CanonicalAnimation.valueOf("BUBBLE"));
        assertNotNull(CanonicalAnimation.valueOf("HURT2"));
        assertNotNull(CanonicalAnimation.valueOf("FLY"));
        // S3K-specific
        assertNotNull(CanonicalAnimation.valueOf("BLINK"));
        assertNotNull(CanonicalAnimation.valueOf("GET_UP"));
        assertNotNull(CanonicalAnimation.valueOf("VICTORY"));
        assertNotNull(CanonicalAnimation.valueOf("BLANK"));
        assertNotNull(CanonicalAnimation.valueOf("HURT_FALL"));
        assertNotNull(CanonicalAnimation.valueOf("GLIDE_DROP"));
        assertNotNull(CanonicalAnimation.valueOf("GLIDE_LAND"));
        assertNotNull(CanonicalAnimation.valueOf("GLIDE_SLIDE"));
        // Super
        assertNotNull(CanonicalAnimation.valueOf("SUPER_TRANSFORM"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestCanonicalAnimationMapping -pl . -q`
Expected: FAIL — `CanonicalAnimation` class does not exist.

- [ ] **Step 3: Create CanonicalAnimation enum**

Create `src/main/java/com/openggf/game/CanonicalAnimation.java`:

```java
package com.openggf.game;

/**
 * Shared animation vocabulary covering the union of all player animations
 * across S1, S2, and S3K. Used by the cross-game donation system to map
 * between game-specific animation IDs.
 */
public enum CanonicalAnimation {
    // Core movement (all games)
    WALK, RUN, ROLL, ROLL2, PUSH, WAIT, DUCK, LOOK_UP, SPRING,
    BALANCE, BALANCE2, BALANCE3, BALANCE4,

    // Combat/state (all games)
    HURT, DEATH, DROWN,

    // S1-specific
    STOP,
    WARP1, WARP2, WARP3, WARP4,
    FLOAT3, FLOAT4,
    LEAP1, LEAP2, SURF, GET_AIR, BURNT, SHRINK, WATER_SLIDE,
    NULL_ANIM,

    // Shared across S1/S2/S3K (present in all but at different IDs)
    FLOAT, FLOAT2,
    HANG,

    // S2+ (spindash era)
    SPINDASH, SKID, SLIDE, HANG2, BUBBLE,
    HURT2, FLY,

    // S3K-specific
    BLINK, GET_UP, VICTORY, BLANK, HURT_FALL,
    GLIDE_DROP, GLIDE_LAND, GLIDE_SLIDE,

    // Super variants
    SUPER_TRANSFORM
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestCanonicalAnimationMapping -pl . -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/CanonicalAnimation.java src/test/java/com/openggf/game/TestCanonicalAnimationMapping.java
git commit -m "feat: add CanonicalAnimation enum for cross-game animation vocabulary"
```

---

## Task 2: Canonical Mappings on Per-Game AnimationIds

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/constants/Sonic1AnimationIds.java`
- Modify: `src/main/java/com/openggf/game/sonic2/constants/Sonic2AnimationIds.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kAnimationIds.java`
- Test: `src/test/java/com/openggf/game/TestCanonicalAnimationMapping.java` (extend)

- [ ] **Step 1: Write tests for S1 canonical round-trip**

Add to `TestCanonicalAnimationMapping.java`:

```java
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;

@Test
void s1AnimationsRoundTripThroughCanonical() {
    // Every S1 animation should map to a canonical and back
    for (Sonic1AnimationIds s1Anim : Sonic1AnimationIds.values()) {
        CanonicalAnimation canonical = s1Anim.toCanonical();
        assertNotNull(canonical, "S1 " + s1Anim + " should map to a canonical animation");
        int resolved = Sonic1AnimationIds.fromCanonical(canonical);
        assertEquals(s1Anim.id(), resolved,
                "S1 " + s1Anim + " -> " + canonical + " should round-trip back to " + s1Anim.id());
    }
}

@Test
void s1FloatNameBridge() {
    // S1.FLOAT1 maps to CanonicalAnimation.FLOAT (not FLOAT1 which doesn't exist)
    assertEquals(CanonicalAnimation.FLOAT, Sonic1AnimationIds.FLOAT1.toCanonical());
    assertEquals(Sonic1AnimationIds.FLOAT1.id(), Sonic1AnimationIds.fromCanonical(CanonicalAnimation.FLOAT));
}

@Test
void s2AnimationsRoundTripThroughCanonical() {
    for (Sonic2AnimationIds s2Anim : Sonic2AnimationIds.values()) {
        // Skip SUPER_ variants — they index a separate table
        if (s2Anim.name().startsWith("SUPER_")) continue;
        CanonicalAnimation canonical = s2Anim.toCanonical();
        assertNotNull(canonical, "S2 " + s2Anim + " should map to a canonical animation");
        int resolved = Sonic2AnimationIds.fromCanonical(canonical);
        assertEquals(s2Anim.id(), resolved,
                "S2 " + s2Anim + " -> " + canonical + " should round-trip back to " + s2Anim.id());
    }
}

@Test
void s3kAnimationsRoundTripThroughCanonical() {
    for (Sonic3kAnimationIds s3kAnim : Sonic3kAnimationIds.values()) {
        if (s3kAnim.name().startsWith("SUPER_")) continue;
        CanonicalAnimation canonical = s3kAnim.toCanonical();
        assertNotNull(canonical, "S3K " + s3kAnim + " should map to a canonical animation");
        int resolved = Sonic3kAnimationIds.fromCanonical(canonical);
        assertEquals(s3kAnim.id(), resolved,
                "S3K " + s3kAnim + " -> " + canonical + " should round-trip back to " + s3kAnim.id());
    }
}

@Test
void unsupportedAnimationsReturnMinusOne() {
    // S1 doesn't have SPINDASH
    assertEquals(-1, Sonic1AnimationIds.fromCanonical(CanonicalAnimation.SPINDASH));
    // S2 doesn't have WARP1
    assertEquals(-1, Sonic2AnimationIds.fromCanonical(CanonicalAnimation.WARP1));
    // S1 doesn't have BLINK
    assertEquals(-1, Sonic1AnimationIds.fromCanonical(CanonicalAnimation.BLINK));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestCanonicalAnimationMapping -pl . -q`
Expected: FAIL — `toCanonical()` and `fromCanonical()` methods do not exist.

- [ ] **Step 3: Add toCanonical/fromCanonical to Sonic1AnimationIds**

Add to `Sonic1AnimationIds.java`:

```java
import com.openggf.game.CanonicalAnimation;

// Add instance method to enum:
public CanonicalAnimation toCanonical() {
    return switch (this) {
        case WALK -> CanonicalAnimation.WALK;
        case RUN -> CanonicalAnimation.RUN;
        case ROLL -> CanonicalAnimation.ROLL;
        case ROLL2 -> CanonicalAnimation.ROLL2;
        case PUSH -> CanonicalAnimation.PUSH;
        case WAIT -> CanonicalAnimation.WAIT;
        case BALANCE -> CanonicalAnimation.BALANCE;
        case LOOK_UP -> CanonicalAnimation.LOOK_UP;
        case DUCK -> CanonicalAnimation.DUCK;
        case WARP1 -> CanonicalAnimation.WARP1;
        case WARP2 -> CanonicalAnimation.WARP2;
        case WARP3 -> CanonicalAnimation.WARP3;
        case WARP4 -> CanonicalAnimation.WARP4;
        case STOP -> CanonicalAnimation.STOP;
        case FLOAT1 -> CanonicalAnimation.FLOAT;   // naming bridge
        case FLOAT2 -> CanonicalAnimation.FLOAT2;
        case SPRING -> CanonicalAnimation.SPRING;
        case HANG -> CanonicalAnimation.HANG;
        case LEAP1 -> CanonicalAnimation.LEAP1;
        case LEAP2 -> CanonicalAnimation.LEAP2;
        case SURF -> CanonicalAnimation.SURF;
        case GET_AIR -> CanonicalAnimation.GET_AIR;
        case BURNT -> CanonicalAnimation.BURNT;
        case DROWN -> CanonicalAnimation.DROWN;
        case DEATH -> CanonicalAnimation.DEATH;
        case SHRINK -> CanonicalAnimation.SHRINK;
        case HURT -> CanonicalAnimation.HURT;
        case WATER_SLIDE -> CanonicalAnimation.WATER_SLIDE;
        case NULL -> CanonicalAnimation.NULL_ANIM;
        case FLOAT3 -> CanonicalAnimation.FLOAT3;
        case FLOAT4 -> CanonicalAnimation.FLOAT4;
    };
}

public static int fromCanonical(CanonicalAnimation canonical) {
    for (Sonic1AnimationIds anim : values()) {
        if (anim.toCanonical() == canonical) {
            return anim.id();
        }
    }
    return -1;
}
```

- [ ] **Step 4: Add toCanonical/fromCanonical to Sonic2AnimationIds**

Add to `Sonic2AnimationIds.java` (same pattern). Key mappings:
- `WALK -> WALK`, `RUN -> RUN`, `ROLL -> ROLL`, `ROLL2 -> ROLL2`
- `SPINDASH -> SPINDASH`, `SKID -> SKID`, `SLIDE -> SLIDE`
- `FLOAT -> FLOAT`, `FLOAT2 -> FLOAT2`, `HANG -> HANG`, `HANG2 -> HANG2`
- `BUBBLE -> BUBBLE`, `HURT2 -> HURT2`, `FLY -> FLY`
- `BALANCE2 -> BALANCE2`, `BALANCE3 -> BALANCE3`, `BALANCE4 -> BALANCE4`
- Super variants: `SUPER_TRANSFORM -> SUPER_TRANSFORM`; other `SUPER_*` entries return null from `toCanonical()` (they index a separate table, not the canonical set)

The `fromCanonical()` loop skips entries where `toCanonical()` returns null.

- [ ] **Step 5: Add toCanonical/fromCanonical to Sonic3kAnimationIds**

Add to `Sonic3kAnimationIds.java` (same pattern). Key mappings:
- All S2 shared animations map identically
- `BLINK -> BLINK`, `GET_UP -> GET_UP`, `VICTORY -> VICTORY`
- `BLANK -> BLANK`, `HURT_FALL -> HURT_FALL`
- `GLIDE_DROP -> GLIDE_DROP`, `GLIDE_LAND -> GLIDE_LAND`, `GLIDE_SLIDE -> GLIDE_SLIDE`
- `SUPER_TRANSFORM -> SUPER_TRANSFORM`

Note: S3K has no native `HURT` at 0x19 (gap in enum) — check if `Sonic3kAnimationIds` defines `HURT`. If it maps `HURT(0x1A)`, then `HURT -> HURT`. Verify against the actual enum.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=TestCanonicalAnimationMapping -pl . -q`
Expected: PASS — all round-trip and unsupported tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/constants/Sonic1AnimationIds.java \
        src/main/java/com/openggf/game/sonic2/constants/Sonic2AnimationIds.java \
        src/main/java/com/openggf/game/sonic3k/constants/Sonic3kAnimationIds.java \
        src/test/java/com/openggf/game/TestCanonicalAnimationMapping.java
git commit -m "feat: add canonical animation mappings to all per-game AnimationIds"
```

---

## Task 3: DonorCapabilities Interface

**Files:**
- Create: `src/main/java/com/openggf/game/DonorCapabilities.java`
- Modify: `src/main/java/com/openggf/game/GameModule.java`
- Test: `src/test/java/com/openggf/game/TestDonorCapabilities.java`

- [ ] **Step 1: Write test that GameModule has getDonorCapabilities()**

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestDonorCapabilities {

    @Test
    void donorCapabilitiesInterfaceHasRequiredMethods() {
        // Verify interface shape by calling methods on a mock implementation
        DonorCapabilities caps = new DonorCapabilities() {
            public java.util.Set<PlayerCharacter> getPlayableCharacters() {
                return java.util.Set.of(PlayerCharacter.SONIC_ALONE);
            }
            public boolean hasSpindash() { return false; }
            public boolean hasSuperTransform() { return false; }
            public boolean hasHyperTransform() { return false; }
            public boolean hasInstaShield() { return false; }
            public boolean hasElementalShields() { return false; }
            public boolean hasSidekick() { return false; }
            public java.util.Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks() {
                return java.util.Map.of();
            }
            public int resolveNativeId(CanonicalAnimation canonical) { return -1; }
            public com.openggf.data.PlayerSpriteArtProvider getPlayerArtProvider(
                    com.openggf.data.RomByteReader reader) {
                return null;
            }
        };

        assertEquals(1, caps.getPlayableCharacters().size());
        assertFalse(caps.hasSpindash());
        assertFalse(caps.hasSuperTransform());
        assertFalse(caps.hasHyperTransform());
        assertFalse(caps.hasInstaShield());
        assertFalse(caps.hasElementalShields());
        assertFalse(caps.hasSidekick());
        assertTrue(caps.getAnimationFallbacks().isEmpty());
        assertEquals(-1, caps.resolveNativeId(CanonicalAnimation.WALK));
        assertNull(caps.getPlayerArtProvider(null));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestDonorCapabilities -pl . -q`
Expected: FAIL — `DonorCapabilities` does not exist.

- [ ] **Step 3: Create DonorCapabilities interface**

Create `src/main/java/com/openggf/game/DonorCapabilities.java`:

```java
package com.openggf.game;

import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.RomByteReader;

import java.util.Map;
import java.util.Set;

/**
 * Declares what a game can export when used as a donor in cross-game
 * feature donation. Each GameModule implements this to describe its
 * available characters, features, and animation fallback mappings.
 */
public interface DonorCapabilities {

    /** Characters this game has player sprite art for. */
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
     * must map to themselves (identity). Animations it doesn't have
     * map to the closest equivalent. Any CanonicalAnimation absent
     * from the map is treated as unsupported (falls back to WAIT).
     */
    Map<CanonicalAnimation, CanonicalAnimation> getAnimationFallbacks();

    /**
     * Resolves a canonical animation to this game's native animation ID.
     * Returns -1 if the animation is not natively supported.
     * Each game module delegates to its AnimationIds.fromCanonical().
     */
    int resolveNativeId(CanonicalAnimation canonical);

    /**
     * Returns an art provider that can load this game's player sprites
     * from the given ROM reader. Note: the PlayerArt classes use
     * loadForCharacter(), so the lambda must adapt to PlayerSpriteArtProvider:
     * {@code (reader) -> (code) -> new SonicXPlayerArt(reader).loadForCharacter(code)}
     */
    PlayerSpriteArtProvider getPlayerArtProvider(RomByteReader reader);
}
```

- [ ] **Step 4: Add getDonorCapabilities() default method to GameModule**

Add to `src/main/java/com/openggf/game/GameModule.java`:

```java
/**
 * Returns the donor capabilities for this game, describing what features
 * and art can be exported when this game is used as a sprite donor.
 * Returns null if this game does not support being a donor.
 */
default DonorCapabilities getDonorCapabilities() {
    return null;
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=TestDonorCapabilities -pl . -q`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/DonorCapabilities.java \
        src/main/java/com/openggf/game/GameModule.java \
        src/test/java/com/openggf/game/TestDonorCapabilities.java
git commit -m "feat: add DonorCapabilities interface and GameModule.getDonorCapabilities()"
```

---

## Task 4: DonorCapabilities Implementations for All Three Games

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Test: `src/test/java/com/openggf/game/TestDonorCapabilities.java` (extend)

- [ ] **Step 1: Write tests for each game's capabilities**

Add to `TestDonorCapabilities.java`:

```java
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;

@Test
void s1DonorCapabilitiesMatchSpec() {
    DonorCapabilities caps = new Sonic1GameModule().getDonorCapabilities();
    assertNotNull(caps, "S1 should support being a donor");

    // Characters: Sonic only
    assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.SONIC_ALONE));
    assertFalse(caps.getPlayableCharacters().contains(PlayerCharacter.TAILS_ALONE));
    assertFalse(caps.getPlayableCharacters().contains(PlayerCharacter.KNUCKLES));

    // Features: none
    assertFalse(caps.hasSpindash());
    assertFalse(caps.hasSuperTransform());
    assertFalse(caps.hasHyperTransform());
    assertFalse(caps.hasInstaShield());
    assertFalse(caps.hasElementalShields());
    assertFalse(caps.hasSidekick());

    // Fallback table should have entries for all non-native animations
    var fallbacks = caps.getAnimationFallbacks();
    assertEquals(CanonicalAnimation.DUCK, fallbacks.get(CanonicalAnimation.SPINDASH));
    assertEquals(CanonicalAnimation.STOP, fallbacks.get(CanonicalAnimation.SKID));
    assertEquals(CanonicalAnimation.WAIT, fallbacks.get(CanonicalAnimation.BLINK));
    assertEquals(CanonicalAnimation.WAIT, fallbacks.get(CanonicalAnimation.VICTORY));
    assertEquals(CanonicalAnimation.SPRING, fallbacks.get(CanonicalAnimation.GLIDE_DROP));

    // Identity mappings for native animations
    assertEquals(CanonicalAnimation.WALK, fallbacks.get(CanonicalAnimation.WALK));
    assertEquals(CanonicalAnimation.WAIT, fallbacks.get(CanonicalAnimation.WAIT));
    assertEquals(CanonicalAnimation.STOP, fallbacks.get(CanonicalAnimation.STOP));
}

@Test
void s2DonorCapabilitiesMatchSpec() {
    DonorCapabilities caps = new Sonic2GameModule().getDonorCapabilities();
    assertNotNull(caps);

    assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.SONIC_ALONE));
    assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.TAILS_ALONE));
    assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.SONIC_AND_TAILS));
    assertFalse(caps.getPlayableCharacters().contains(PlayerCharacter.KNUCKLES));

    assertTrue(caps.hasSpindash());
    assertTrue(caps.hasSuperTransform());
    assertFalse(caps.hasHyperTransform());
    assertFalse(caps.hasInstaShield());
    assertFalse(caps.hasElementalShields());
    assertTrue(caps.hasSidekick());

    var fallbacks = caps.getAnimationFallbacks();
    assertEquals(CanonicalAnimation.ROLL, fallbacks.get(CanonicalAnimation.WARP1));
    assertEquals(CanonicalAnimation.WAIT, fallbacks.get(CanonicalAnimation.BLINK));
    assertEquals(CanonicalAnimation.BUBBLE, fallbacks.get(CanonicalAnimation.GET_AIR));
}

@Test
void s3kDonorCapabilitiesMatchSpec() {
    DonorCapabilities caps = new Sonic3kGameModule().getDonorCapabilities();
    assertNotNull(caps);

    assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.SONIC_ALONE));
    assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.TAILS_ALONE));
    assertTrue(caps.getPlayableCharacters().contains(PlayerCharacter.KNUCKLES));

    assertTrue(caps.hasSpindash());
    assertTrue(caps.hasSuperTransform());
    assertTrue(caps.hasHyperTransform());
    assertTrue(caps.hasInstaShield());
    assertTrue(caps.hasElementalShields());
    assertTrue(caps.hasSidekick());

    // S3K has fewest fallbacks — most animations are native
    var fallbacks = caps.getAnimationFallbacks();
    assertEquals(CanonicalAnimation.ROLL, fallbacks.get(CanonicalAnimation.WARP1));
    assertEquals(CanonicalAnimation.SKID, fallbacks.get(CanonicalAnimation.STOP));
    assertEquals(CanonicalAnimation.HURT_FALL, fallbacks.get(CanonicalAnimation.SLIDE));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestDonorCapabilities -pl . -q`
Expected: FAIL — `getDonorCapabilities()` returns null on all modules.

- [ ] **Step 3: Implement Sonic1GameModule.getDonorCapabilities()**

Add `getDonorCapabilities()` to `Sonic1GameModule.java` returning a new anonymous `DonorCapabilities` with:
- Characters: `Set.of(PlayerCharacter.SONIC_ALONE)`
- All feature booleans: false
- Fallback table: all `CanonicalAnimation` values mapped. Native S1 animations map to themselves. Non-native animations map per the spec's S1 fallback table (SPINDASH→DUCK, SKID→STOP, BLINK→WAIT, etc.)
- Art provider: `(reader) -> new Sonic1PlayerArt(reader)`

Build the fallback map using a helper that first adds identity entries for all S1 native animations (iterate `Sonic1AnimationIds.values()`, call `toCanonical()`, add identity mapping), then overrides with the spec's fallback entries.

- [ ] **Step 4: Implement Sonic2GameModule.getDonorCapabilities()**

Same pattern. Characters: `Set.of(SONIC_ALONE, SONIC_AND_TAILS, TAILS_ALONE)`. Spindash=true, superTransform=true, sidekick=true, rest=false. Fallback table per spec's S2 table. Art provider: `(reader) -> new Sonic2PlayerArt(reader)`.

- [ ] **Step 5: Implement Sonic3kGameModule.getDonorCapabilities()**

Same pattern. Characters: `Set.of(SONIC_ALONE, SONIC_AND_TAILS, TAILS_ALONE, KNUCKLES)`. All features=true. Fallback table per spec's S3K table. Art provider: `(reader) -> new Sonic3kPlayerArt(reader)`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest=TestDonorCapabilities -pl . -q`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/Sonic1GameModule.java \
        src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java \
        src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java \
        src/test/java/com/openggf/game/TestDonorCapabilities.java
git commit -m "feat: implement DonorCapabilities for S1, S2, and S3K game modules"
```

---

## Task 5: AnimationTranslator

**Files:**
- Create: `src/main/java/com/openggf/sprites/animation/AnimationTranslator.java`
- Test: `src/test/java/com/openggf/sprites/animation/TestAnimationTranslator.java`

- [ ] **Step 1: Write tests for AnimationTranslator**

```java
package com.openggf.sprites.animation;

import com.openggf.game.CanonicalAnimation;
import com.openggf.game.DonorCapabilities;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.data.PlayerSpriteArtProvider;
import com.openggf.data.RomByteReader;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TestAnimationTranslator {

    /**
     * Builds a minimal SpriteAnimationSet with scripts for the given IDs.
     * Each script has a single frame (frame 0) with LOOP end action.
     */
    private SpriteAnimationSet buildAnimSet(int... scriptIds) {
        SpriteAnimationSet set = new SpriteAnimationSet();
        for (int id : scriptIds) {
            set.addScript(id, new SpriteAnimationScript(1, List.of(0),
                    SpriteAnimationEndAction.LOOP, 0));
        }
        return set;
    }

    /** Builds a minimal S1-like donor profile. */
    private ScriptedVelocityAnimationProfile buildS1Profile() {
        return new ScriptedVelocityAnimationProfile()
                .setIdleAnimId(Sonic1AnimationIds.WAIT)
                .setWalkAnimId(Sonic1AnimationIds.WALK)
                .setRunAnimId(Sonic1AnimationIds.RUN)
                .setRollAnimId(Sonic1AnimationIds.ROLL)
                .setAirAnimId(Sonic1AnimationIds.WALK)
                .setSpringAnimId(Sonic1AnimationIds.SPRING)
                .setDeathAnimId(Sonic1AnimationIds.DEATH)
                .setHurtAnimId(Sonic1AnimationIds.HURT)
                .setDrownAnimId(Sonic1AnimationIds.DROWN)
                .setDuckAnimId(Sonic1AnimationIds.DUCK)
                .setLookUpAnimId(Sonic1AnimationIds.LOOK_UP)
                .setPushAnimId(Sonic1AnimationIds.PUSH)
                .setBalanceAnimId(Sonic1AnimationIds.BALANCE)
                .setAnglePreAdjust(false)
                .setCompactSuperRunSlope(false)
                .setRunSpeedThreshold(0x600);
    }

    /** Builds a minimal S1 DonorCapabilities with fallback table. */
    private DonorCapabilities buildS1Donor() {
        // Use the real S1 game module's capabilities
        return new com.openggf.game.sonic1.Sonic1GameModule().getDonorCapabilities();
    }

    @Test
    void translatedProfileHasAllFieldsFromDonor() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        // S1 anim IDs: WALK=0, RUN=1, ROLL=2, DUCK=8, WAIT=5, etc.
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 3, 4, 5, 6, 7, 8,
                0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16,
                0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        // Non-animation properties preserved from donor
        assertFalse(translated.isAnglePreAdjust());
        assertFalse(translated.isCompactSuperRunSlope());
        assertEquals(0x600, translated.getRunSpeedThreshold());

        // Core animation IDs should be valid (backed by scripts in donorSet)
        assertNotNull(donorSet.getScript(translated.getIdleAnimId()));
        assertNotNull(donorSet.getScript(translated.getWalkAnimId()));
        assertNotNull(donorSet.getScript(translated.getRunAnimId()));
        assertNotNull(donorSet.getScript(translated.getRollAnimId()));
        assertNotNull(donorSet.getScript(translated.getAirAnimId()));
    }

    @Test
    void spindashFallsToDuckForS1Donor() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 3, 4, 5, 6, 7, 8,
                0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x17, 0x18, 0x1A);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        // S1 has no spindash. Fallback: SPINDASH -> DUCK.
        // S1 DUCK = 0x08. The translated spindash field should point to 0x08.
        int spindashId = translated.getSpindashAnimId();
        assertEquals(Sonic1AnimationIds.DUCK.id(), spindashId);
        assertNotNull(donorSet.getScript(spindashId));
    }

    @Test
    void airAnimIdAlwaysMapsToWalk() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 5, 8, 0x10, 0x17, 0x18, 0x1A);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        assertEquals(translated.getWalkAnimId(), translated.getAirAnimId());
    }

    @Test
    void disabledAnimIdStaysMinusOne() {
        DonorCapabilities donor = buildS1Donor();
        ScriptedVelocityAnimationProfile donorProfile = buildS1Profile();
        // Don't set skidAnimId on donor profile (stays -1)
        SpriteAnimationSet donorSet = buildAnimSet(0, 1, 2, 5, 8);

        ScriptedVelocityAnimationProfile translated =
                AnimationTranslator.translate(donor, donorProfile, donorSet);

        // If the donor profile had -1 for skid AND the fallback resolves to a
        // valid script, the translator should fill it in. But if the resolved
        // fallback script doesn't exist in the set, it stays -1.
        // S1 fallback for SKID -> STOP (0x0D), which is NOT in our minimal set.
        assertEquals(-1, translated.getSkidAnimId());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestAnimationTranslator -pl . -q`
Expected: FAIL — `AnimationTranslator` does not exist.

- [ ] **Step 3: Implement AnimationTranslator**

Create `src/main/java/com/openggf/sprites/animation/AnimationTranslator.java`:

The `translate()` method:
1. Creates a new `ScriptedVelocityAnimationProfile`.
2. Copies non-animation properties from `donorProfile` (anglePreAdjust, compactSuperRunSlope, walkSpeedThreshold, runSpeedThreshold, fallbackFrame).
3. For each profile field (idle, walk, run, roll, roll2, push, duck, lookUp, spindash, spring, death, hurt, skid, slide, drown, air, balance, balance2, balance3, balance4):
   a. Determine the canonical animation this field represents (hardcoded mapping, e.g. `idle -> CanonicalAnimation.WAIT`, `walk -> CanonicalAnimation.WALK`).
   b. Look up the donor's fallback: `donor.getAnimationFallbacks().getOrDefault(canonical, CanonicalAnimation.WAIT)`.
   c. Resolve the fallback canonical back to the donor's native ID using the donor's `AnimationIds.fromCanonical()`. To do this generically, iterate the donor animation set's scripts and find the one whose game-local ID matches. Alternatively, the fallback table already maps to canonical entries the donor natively has, so resolve via the donor game's `fromCanonical()`.
   d. If the resolved ID has a script in `donorAnimSet`, set it on the translated profile. Otherwise set -1 (disabled).
4. Special case: `airAnimId` is always set to the translated `walkAnimId`.
5. Returns the translated profile.

**Implementation note:** The translator resolves `CanonicalAnimation -> donor native ID` via `donor.resolveNativeId(canonical)` (already on the `DonorCapabilities` interface from Task 3). Each game module's implementation delegates to its `AnimationIds.fromCanonical()`.

**Missing getters prerequisite:** Before implementing the translator, add the missing `getHurtAnimId()` and `getSlideAnimId()` getters to `ScriptedVelocityAnimationProfile.java` (fields exist, setters exist, getters were omitted). Place them alongside the existing getter block (after `getDeathAnimId()`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=TestAnimationTranslator -pl . -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/sprites/animation/AnimationTranslator.java \
        src/main/java/com/openggf/sprites/animation/ScriptedVelocityAnimationProfile.java \
        src/test/java/com/openggf/sprites/animation/TestAnimationTranslator.java
git commit -m "feat: add AnimationTranslator for cross-game profile translation"
```

---

## Task 6: Refactor CrossGameFeatureProvider

**Files:**
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- Test: `src/test/java/com/openggf/game/TestCrossGameFeatureProviderRefactor.java`

- [ ] **Step 1: Write test for same-game guard**

```java
package com.openggf.game;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestCrossGameFeatureProviderRefactor {

    @AfterEach
    void cleanup() {
        CrossGameFeatureProvider.resetInstance();
    }

    @Test
    void sameGameDonationIsDisabled() {
        // S2 is the default host. Trying to donate S2 into S2 should no-op.
        GameModuleRegistry.setCurrent(new com.openggf.game.sonic2.Sonic2GameModule());
        try {
            CrossGameFeatureProvider.getInstance().initialize("s2");
        } catch (Exception e) {
            // May throw if ROM not available, but the guard should fire first
        }
        assertFalse(CrossGameFeatureProvider.isActive(),
                "Same-game donation should be disabled");
    }

    @Test
    void hybridFeatureSetReflectsDonorCapabilities() {
        // This test verifies the concept: if donor has no spindash,
        // the hybrid feature set should disable spindash.
        // Full integration requires ROM, so we test the buildHybridFeatureSet logic.
        DonorCapabilities s1Caps = new com.openggf.game.sonic1.Sonic1GameModule()
                .getDonorCapabilities();
        assertFalse(s1Caps.hasSpindash());
        assertFalse(s1Caps.hasSuperTransform());
        assertFalse(s1Caps.hasInstaShield());
    }
}
```

- [ ] **Step 2: Run test to verify it fails (or identify adjustment needed)**

Run: `mvn test -Dtest=TestCrossGameFeatureProviderRefactor -pl . -q`
Expected: May pass if guard already exists, or fail if not implemented.

- [ ] **Step 3: Add same-game guard to CrossGameFeatureProvider.initialize()**

At the top of `initialize()`, before opening the donor ROM. **Important:** `GameModule.getIdentifier()` returns `"Sonic1"`/`"Sonic2"`/`"Sonic3k"` while `donorGameId` is `"s1"`/`"s2"`/`"s3k"`. Use `GameId` to normalize:

```java
GameId donorId = GameId.fromCode(donorGameId);
GameId hostId = GameId.fromCode(resolveHostCode());
if (donorId == hostId) {
    LOGGER.info("Donor same as host (" + donorGameId + "), donation disabled");
    active = false;
    return;
}
```

Add a private helper to map `getIdentifier()` to `GameId` code:

```java
private String resolveHostCode() {
    String id = GameModuleRegistry.getCurrent().getIdentifier();
    return switch (id) {
        case "Sonic1" -> "s1";
        case "Sonic2" -> "s2";
        case "Sonic3k" -> "s3k";
        default -> id.toLowerCase();
    };
}
```

- [ ] **Step 4: Add donorModule/donorCapabilities fields and use in initialize()**

Replace the hardcoded `if ("s3k")` branches with:

```java
// After same-game guard, before ROM opening:
GameModule donorModule = resolveModule(donorGameId);
DonorCapabilities donorCapabilities = donorModule != null
        ? donorModule.getDonorCapabilities() : null;
if (donorCapabilities == null) {
    LOGGER.warning("No donor capabilities for: " + donorGameId);
    active = false;
    return;
}

// Store as fields for use in loadPlayerSpriteArt(), buildHybridFeatureSet(), etc.
this.donorCapabilities = donorCapabilities;
```

Add the `resolveModule()` helper (simple factory — `GameModuleRegistry` has no lookup-by-code):

```java
private static GameModule resolveModule(String gameCode) {
    return switch (gameCode.toLowerCase()) {
        case "s1" -> new com.openggf.game.sonic1.Sonic1GameModule();
        case "s2" -> new com.openggf.game.sonic2.Sonic2GameModule();
        case "s3k" -> new com.openggf.game.sonic3k.Sonic3kGameModule();
        default -> null;
    };
}
```

- [ ] **Step 5: Refactor loadPlayerSpriteArt() to use donorCapabilities**

Replace the `if (s3kPlayerArt != null) / if (s2PlayerArt != null)` branches:

```java
@Override
public SpriteArtSet loadPlayerSpriteArt(String characterCode) throws IOException {
    PlayerSpriteArtProvider artProvider = donorCapabilities.getPlayerArtProvider(donorReader);
    SpriteArtSet donorArt = artProvider.loadPlayerSpriteArt(characterCode);
    if (donorArt == null || donorArt.animationProfile() == null) {
        return donorArt;
    }
    // Translate the animation profile for host compatibility
    if (donorArt.animationProfile() instanceof ScriptedVelocityAnimationProfile donorProfile) {
        ScriptedVelocityAnimationProfile translated = AnimationTranslator.translate(
                donorCapabilities, donorProfile, donorArt.animationSet());
        return new SpriteArtSet(donorArt.artTiles(), donorArt.mappingFrames(),
                donorArt.dplcFrames(), donorArt.paletteIndex(), donorArt.basePatternIndex(),
                donorArt.frameDelay(), donorArt.bankSize(), translated, donorArt.animationSet());
    }
    return donorArt;
}
```

- [ ] **Step 6: Refactor buildHybridFeatureSet() to use donorCapabilities**

Replace the hardcoded spindash/insta-shield logic:

```java
private PhysicsFeatureSet buildHybridFeatureSet() {
    short[] spindashSpeedTable = donorCapabilities.hasSpindash()
            ? new short[]{0x0800, 0x0880, 0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00}
            : null;

    PhysicsFeatureSet baseFeatureSet = GameModuleRegistry.getCurrent()
            .getPhysicsProvider().getFeatureSet();

    return new PhysicsFeatureSet(
            donorCapabilities.hasSpindash(),
            spindashSpeedTable,
            baseFeatureSet.collisionModel(),
            baseFeatureSet.fixedAnglePosThreshold(),
            baseFeatureSet.lookScrollDelay(),
            baseFeatureSet.waterShimmerEnabled(),
            baseFeatureSet.inputAlwaysCapsGroundSpeed(),
            donorCapabilities.hasElementalShields(),
            donorCapabilities.hasInstaShield(),
            baseFeatureSet.angleDiffCardinalSnap(),
            baseFeatureSet.extendedEdgeBalance(),
            baseFeatureSet.ringFloorCheckMask()
    );
}
```

- [ ] **Step 7: Refactor supportsSidekick() and hasSeparateTailsTailArt()**

```java
public boolean supportsSidekick() {
    return donorCapabilities != null && donorCapabilities.hasSidekick();
}
```

- [ ] **Step 8: Refactor createSuperStateController()**

```java
public SuperStateController createSuperStateController(AbstractPlayableSprite player) {
    if (!active || donorReader == null || donorCapabilities == null) {
        return null;
    }
    if (!donorCapabilities.hasSuperTransform()) {
        return null;  // Donor doesn't support super transformation
    }
    // Delegate to existing per-game controller logic
    // (S2 donor -> Sonic2SuperStateController, S3K donor -> Sonic3kSuperStateController)
    SuperStateController ctrl;
    if ("s3k".equalsIgnoreCase(donorGameId)) {
        ctrl = new Sonic3kSuperStateController(player);
    } else {
        ctrl = new Sonic2SuperStateController(player);
    }
    try {
        ctrl.loadRomData(donorReader);
        ctrl.setRomDataPreLoaded(true);
    } catch (Exception e) {
        LOGGER.warning("Failed to load donor Super ROM data: " + e.getMessage());
        return null;
    }
    return ctrl;
}
```

Note: The Super controller selection still uses game ID since the controller implementations are inherently game-specific (different palette formats, mapping tables). This is acceptable — `DonorCapabilities.hasSuperTransform()` gates whether we even attempt it.

- [ ] **Step 9: Run all tests**

Run: `mvn test -pl . -q`
Expected: All existing tests pass. New tests pass. No regressions.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/openggf/game/CrossGameFeatureProvider.java \
        src/test/java/com/openggf/game/TestCrossGameFeatureProviderRefactor.java
git commit -m "refactor: CrossGameFeatureProvider uses DonorCapabilities instead of hardcoded branches"
```

---

## Task 7: Wire Up S1 as Donor (Forward Donation)

**Files:**
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java` (if needed)
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java` (if needed)

This task verifies the full forward-donation path works. The previous tasks created all the pieces — this task ensures they connect.

- [ ] **Step 1: Verify S1 donor art provider creates Sonic1PlayerArt**

The `Sonic1GameModule.getDonorCapabilities().getPlayerArtProvider(reader)` should return a provider that calls `new Sonic1PlayerArt(reader)`. Verify in the code that `Sonic1PlayerArt.loadForCharacter("sonic")` returns a valid `SpriteArtSet`.

- [ ] **Step 2: Verify character gating prevents Tails/Knuckles with S1 donor**

Check that when `CrossGameFeatureProvider` is active with S1 donor, `donorCapabilities.getPlayableCharacters()` does not contain `TAILS_ALONE` or `KNUCKLES`. The host's character select code should query this to disable those options.

- [ ] **Step 3: Verify S3K host skips Super transformation with S1 donor**

`Sonic3kGameModule.createSuperStateController()` currently doesn't check `CrossGameFeatureProvider`. It needs to be updated to check for cross-game donation:

```java
@Override
public SuperStateController createSuperStateController(AbstractPlayableSprite player) {
    if (CrossGameFeatureProvider.isActive()) {
        return CrossGameFeatureProvider.getInstance().createSuperStateController(player);
    }
    return new Sonic3kSuperStateController(player);
}
```

With S1 donor, `CrossGameFeatureProvider.createSuperStateController()` returns null because `!donorCapabilities.hasSuperTransform()`.

- [ ] **Step 4: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass. No regressions.

- [ ] **Step 5: Commit**

```bash
git add -u
git commit -m "feat: wire up S1 as donor for forward donation into S2/S3K"
```

---

## Task 8: Integration Verification

**Files:**
- All test files from previous tasks

- [ ] **Step 1: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass.

- [ ] **Step 2: Verify existing S3K tests still pass**

Run: `mvn test -Dtest="com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading,sonic3k.com.openggf.game.TestSonic3kBootstrapResolver,sonic3k.com.openggf.game.TestSonic3kDecodingUtils" -pl . -q`
Expected: All 4 S3K tests pass (these are listed in CLAUDE.md as must-stay-green).

- [ ] **Step 3: Verify existing cross-game donation tests still pass**

Search for existing donation-related tests and run them:

Run: `mvn test -Dtest="*CrossGame*,*Donation*,*InstaShield*" -pl . -q`
Expected: All pass.

- [ ] **Step 4: Commit any final fixes**

If any tests needed adjustments, commit them:

```bash
git add -u
git commit -m "test: fix test adjustments for cross-game animation donation"
```
