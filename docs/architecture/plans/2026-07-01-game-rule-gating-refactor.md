# Per-Game Rule Gating Refactor Implementation Plan

> **Status note:** This is a future/refactor execution plan for the `feature/ai-game-rule-gating-refactor` branch. It intentionally references files, rule records, tests, and guard behavior before they exist in the current codebase; follow task order when using it as implementation guidance.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split the overloaded `PhysicsFeatureSet` and scattered per-game capability gates into typed runtime rule groups, preserving ROM parity while making future game differences easier to place and review.

**Architecture:** Add a behavior-preserving `GameRules` aggregate that is initially derived from the existing `PhysicsFeatureSet`, then migrate call sites by subsystem. Cross-game donation must compose host-owned level/runtime rules with donor-owned player capability rules explicitly instead of producing one ambiguous hybrid feature set.

**Boundary rule:** `GameRules` is only for cross-game ROM behavior gates consumed by shared runtime code. Data ownership, art availability, zone-local mechanics, and object-family behavior stay with existing providers, profiles, registries, or object-local hooks. Do not create catch-all rule records; if a proposed rule record grows past 20 components, split it by consumer before migrating more call sites.

**Tech Stack:** Java 17 records/classes, JUnit 5, Maven, existing `GameModule`, `PhysicsProvider`, `CrossGameFeatureProvider`, and trace replay infrastructure.

---

## File Structure

Create:
- `src/main/java/com/openggf/game/rules/GameRules.java` - aggregate record containing all typed rule groups.
- `src/main/java/com/openggf/game/rules/PlayerMovementRules.java` - movement, slope, roll, water-exit, control-latch, and boundary rules currently used by `PlayableSpriteMovement` and `AbstractPlayableSprite`.
- `src/main/java/com/openggf/game/rules/PlayerCapabilityRules.java` - player abilities and capability flags such as spindash, elemental shields, insta-shield, lightning shield, and spindash speed tables.
- `src/main/java/com/openggf/game/rules/CollisionRules.java` - collision model, terrain probe, wall-push, platform-contact, and collision ordering rules used by `CollisionSystem` and solid contact execution.
- `src/main/java/com/openggf/game/rules/PlayerAnimationRules.java` - player animation-state divergences such as balance animation sets and push-clear animation behavior.
- `src/main/java/com/openggf/game/rules/CameraRules.java` - fast scroll cap, leftward scroll cap, visibility wrap, and vertical wrap control rules.
- `src/main/java/com/openggf/game/rules/RingRules.java` - placed/lost ring collision, floor cadence, collection model, and camera-window sweep rules.
- `src/main/java/com/openggf/game/rules/ObjectInteractionRules.java` - solid-object, touch-response, boss-hit, object-order, and respawn-table behavior.
- `src/main/java/com/openggf/game/rules/SidekickCpuRules.java` - sidekick follow, panic, despawn, catch-up flight, flying carry, and death-despawn rules.
- `src/main/java/com/openggf/game/rules/PowerUpRules.java` - speed-shoes timer cadence, fixed shield/invincibility object slots, water splash dust ownership.
- `src/main/java/com/openggf/game/rules/DrowningBubbleRules.java` - countdown and mouth-bubble object cadence rules.
- `src/main/java/com/openggf/game/rules/CrossGameRuleComposer.java` - host/donor rule composition for cross-game feature donation.
- `docs/architecture/per-game-rule-placement.md` - focused decision ruleset for choosing the smallest owner for per-game divergences.
- `src/test/java/com/openggf/tests/game/TestGameRulesFromPhysicsFeatureSet.java` - characterization and exhaustive ownership tests for S1/S2/S3K rule mapping.
- `src/test/java/com/openggf/tests/game/TestCrossGameRuleComposer.java` - tests for explicit host/donor rule composition.
- `src/test/java/com/openggf/tests/game/TestNoNewPhysicsFeatureSetCallSites.java` - scanner guard preventing new broad `PhysicsFeatureSet` call sites after migration.

Modify:
- `src/main/java/com/openggf/game/GameModule.java` - add a default `getRules()` method.
- `src/main/java/com/openggf/game/PhysicsProvider.java` - optionally add a default `getRules()` bridge if call sites prefer provider ownership.
- `src/main/java/com/openggf/game/CrossGameFeatureProvider.java` - replace `buildHybridFeatureSet()` usage with `CrossGameRuleComposer`.
- `src/main/java/com/openggf/game/PlayableEntity.java` - expose `GameRules getGameRules()` once call-site migration starts.
- `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java` - hold both legacy `PhysicsFeatureSet` and new `GameRules` during migration.
- `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java` - migrate movement call sites.
- `src/main/java/com/openggf/physics/CollisionSystem.java` - migrate collision call sites.
- `src/main/java/com/openggf/camera/Camera.java` - migrate camera call sites.
- `src/main/java/com/openggf/level/rings/RingManager.java` and `src/main/java/com/openggf/level/rings/LostRingObjectInstance.java` - migrate ring call sites.
- `src/main/java/com/openggf/level/objects/ObjectSolidContactController.java` and `src/main/java/com/openggf/level/objects/ObjectTouchResponseController.java` - migrate object interaction call sites.
- `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` and `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java` - migrate sidekick CPU call sites.
- `src/main/java/com/openggf/sprites/playable/DrowningController.java` - migrate bubble cadence call sites.
- `src/main/java/com/openggf/timer/timers/SpeedShoesTimer.java` - migrate timer cadence call sites.
- `CHANGELOG.md` - update when implementation changes `src/main/` behavior surfaces, even if behavior is intended to be unchanged.

---

### Task 1: Add Typed Rule Records With Legacy Mapping

**Files:**
- Create: `src/main/java/com/openggf/game/rules/GameRules.java`
- Create: `src/main/java/com/openggf/game/rules/PlayerMovementRules.java`
- Create: `src/main/java/com/openggf/game/rules/PlayerCapabilityRules.java`
- Create: `src/main/java/com/openggf/game/rules/CollisionRules.java`
- Create: `src/main/java/com/openggf/game/rules/PlayerAnimationRules.java`
- Create: `src/main/java/com/openggf/game/rules/CameraRules.java`
- Create: `src/main/java/com/openggf/game/rules/RingRules.java`
- Create: `src/main/java/com/openggf/game/rules/ObjectInteractionRules.java`
- Create: `src/main/java/com/openggf/game/rules/SidekickCpuRules.java`
- Create: `src/main/java/com/openggf/game/rules/PowerUpRules.java`
- Create: `src/main/java/com/openggf/game/rules/DrowningBubbleRules.java`
- Test: `src/test/java/com/openggf/tests/game/TestGameRulesFromPhysicsFeatureSet.java`

- [ ] **Step 1: Write the failing mapping test**

Create `src/test/java/com/openggf/tests/game/TestGameRulesFromPhysicsFeatureSet.java`:

```java
package com.openggf.tests.game;

import com.openggf.game.PhysicsFeatureSet;
import com.openggf.game.rules.GameRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestGameRulesFromPhysicsFeatureSet {
    @Test
    void mapsSonic1CoreRules() {
        GameRules rules = GameRules.fromLegacy(PhysicsFeatureSet.SONIC_1);

        assertFalse(rules.playerCapabilities().spindashEnabled());
        assertFalse(rules.playerCapabilities().elementalShieldsEnabled());
        assertTrue(rules.camera().uncappedLeftwardHorizontalScroll());
        assertEquals(16, rules.camera().fastScrollCap());
        assertTrue(rules.playerMovement().fixedAnglePosThreshold());
        assertTrue(rules.rings().stageRingsUseObjectTouchCollection());
    }

    @Test
    void mapsSonic2CoreRules() {
        GameRules rules = GameRules.fromLegacy(PhysicsFeatureSet.SONIC_2);

        assertTrue(rules.playerCapabilities().spindashEnabled());
        assertFalse(rules.playerCapabilities().elementalShieldsEnabled());
        assertEquals(16, rules.camera().fastScrollCap());
        assertEquals(0x10, rules.sidekickCpu().followSnapThreshold());
        assertTrue(rules.objectInteraction().objectsExecuteAfterPlayerPhysics());
        assertEquals(8, rules.rings().ringFloorCheckCadence());
    }

    @Test
    void mapsSonic3kCoreRules() {
        GameRules rules = GameRules.fromLegacy(PhysicsFeatureSet.SONIC_3K);

        assertTrue(rules.playerCapabilities().spindashEnabled());
        assertTrue(rules.playerCapabilities().elementalShieldsEnabled());
        assertTrue(rules.playerCapabilities().instaShieldEnabled());
        assertEquals(24, rules.camera().fastScrollCap());
        assertEquals(0x30, rules.sidekickCpu().followSnapThreshold());
        assertTrue(rules.playerMovement().levelBoundaryRightStrict());
    }
}
```

- [ ] **Step 2: Run the failing test**

Before running, extend the same test class with an exhaustive ownership check. It must fail unless every `PhysicsFeatureSet` record component has exactly one destination: one typed rule record, or the explicit string `provider-owned` when the behavior belongs in an existing provider/profile instead of `GameRules`.

```java
@Test
void everyLegacyFeatureSetComponentHasExactlyOneDestination() {
    java.util.Map<String, String> destinations = legacyComponentDestinations();
    java.util.Set<String> actual = java.util.Arrays.stream(PhysicsFeatureSet.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

    assertEquals(actual, new java.util.TreeSet<>(destinations.keySet()));
    assertTrue(destinations.values().stream().noneMatch(String::isBlank));
}

private static java.util.Map<String, String> legacyComponentDestinations() {
    java.util.Map<String, String> destinations = new java.util.TreeMap<>();
    destinations.put("spindashEnabled", "PlayerCapabilityRules");
    destinations.put("spindashSpeedTable", "PlayerCapabilityRules");
    destinations.put("collisionModel", "CollisionRules");
    destinations.put("lookScrollDelay", "CameraRules");
    destinations.put("waterShimmerEnabled", "provider-owned"); // render/water provider owns visual shimmer.
    destinations.put("singleFacingBalanceAnimationSet", "PlayerAnimationRules");
    destinations.put("waterSplashUsesFixedDustObject", "PowerUpRules");
    return java.util.Collections.unmodifiableMap(destinations);
}
```

Before Task 1 is complete, expand `legacyComponentDestinations()` to one entry for every current component reported by `PhysicsFeatureSet.class.getRecordComponents()`. The review checklist must explicitly account for `lookScrollDelay`, `waterShimmerEnabled`, `advanceWaterLevelBeforePlayerPhysics`, `collisionModel`, `singleFacingBalanceAnimationSet`, fixed power-up slot indices, and `waterSplashUsesFixedDustObject`.

Run:

```powershell
mvn "-Dtest=com.openggf.tests.game.TestGameRulesFromPhysicsFeatureSet" test
```

Expected: compile failure because `com.openggf.game.rules` does not exist.

- [ ] **Step 3: Add the rule records**

Create the rule records with `fromLegacy(PhysicsFeatureSet fs)` factories. Keep field names close to the existing names to make review mechanical.

Core shape for `GameRules.java`:

```java
package com.openggf.game.rules;

import com.openggf.game.PhysicsFeatureSet;

public record GameRules(
        PlayerMovementRules playerMovement,
        PlayerCapabilityRules playerCapabilities,
        CollisionRules collision,
        PlayerAnimationRules playerAnimation,
        CameraRules camera,
        RingRules rings,
        ObjectInteractionRules objectInteraction,
        SidekickCpuRules sidekickCpu,
        PowerUpRules powerUps,
        DrowningBubbleRules drowningBubbles
) {
    public static GameRules fromLegacy(PhysicsFeatureSet fs) {
        if (fs == null) {
            throw new IllegalArgumentException("PhysicsFeatureSet is required");
        }
        return new GameRules(
                PlayerMovementRules.fromLegacy(fs),
                PlayerCapabilityRules.fromLegacy(fs),
                CollisionRules.fromLegacy(fs),
                PlayerAnimationRules.fromLegacy(fs),
                CameraRules.fromLegacy(fs),
                RingRules.fromLegacy(fs),
                ObjectInteractionRules.fromLegacy(fs),
                SidekickCpuRules.fromLegacy(fs),
                PowerUpRules.fromLegacy(fs),
                DrowningBubbleRules.fromLegacy(fs)
        );
    }
}
```

For each rule record, copy only the fields used by that subsystem. Example for `CameraRules.java`:

```java
package com.openggf.game.rules;

import com.openggf.game.PhysicsFeatureSet;

public record CameraRules(
        int fastScrollCap,
        boolean uncappedLeftwardHorizontalScroll,
        boolean useScreenYWrapValueForVisibility,
        boolean playerControlAppliesVerticalWrapMask
) {
    public static CameraRules fromLegacy(PhysicsFeatureSet fs) {
        return new CameraRules(
                fs.fastScrollCap(),
                fs.uncappedLeftwardHorizontalScroll(),
                fs.useScreenYWrapValueForVisibility(),
                fs.playerControlAppliesVerticalWrapMask()
        );
    }
}
```

Use the same direct mapping style for every new record. Do not change values in this task. When the ownership test marks a legacy component as `provider-owned`, do not copy it into a `GameRules` record; migrate that consumer to the named provider/profile in the later task that touches the subsystem.

- [ ] **Step 4: Run the mapping test**

Run:

```powershell
mvn "-Dtest=com.openggf.tests.game.TestGameRulesFromPhysicsFeatureSet" test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/game/rules src/test/java/com/openggf/tests/game/TestGameRulesFromPhysicsFeatureSet.java
git commit -m "refactor: add typed per-game rule groups"
```

Commit trailers:

```text
Changelog: n/a: behavior-preserving rule mapping only
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 2: Expose Rules Without Replacing Call Sites

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java`
- Modify: `src/main/java/com/openggf/game/PhysicsProvider.java`
- Modify: `src/main/java/com/openggf/game/PlayableEntity.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Test: `src/test/java/com/openggf/tests/game/TestGameRulesFromPhysicsFeatureSet.java`

- [ ] **Step 1: Extend the mapping test to verify module exposure**

Append this test method:

```java
@Test
void gameModulesExposeRulesFromTheirPhysicsProvider() {
    assertEquals(
            PhysicsFeatureSet.SONIC_1.fastScrollCap(),
            new com.openggf.game.sonic1.Sonic1GameModule().getRules().camera().fastScrollCap());
    assertEquals(
            PhysicsFeatureSet.SONIC_2.fastScrollCap(),
            new com.openggf.game.sonic2.Sonic2GameModule().getRules().camera().fastScrollCap());
    assertEquals(
            PhysicsFeatureSet.SONIC_3K.fastScrollCap(),
            new com.openggf.game.sonic3k.Sonic3kGameModule().getRules().camera().fastScrollCap());
}
```

- [ ] **Step 2: Run the failing test**

Run:

```powershell
mvn "-Dtest=com.openggf.tests.game.TestGameRulesFromPhysicsFeatureSet" test
```

Expected: compile failure because `GameModule.getRules()` does not exist.

- [ ] **Step 3: Add default rule access**

In `GameModule.java`, add:

```java
default com.openggf.game.rules.GameRules getRules() {
    PhysicsProvider provider = getPhysicsProvider();
    if (provider == null) {
        throw new IllegalStateException("No PhysicsProvider for " + getIdentifier());
    }
    return provider.getRules();
}
```

In `PhysicsProvider.java`, add:

```java
default com.openggf.game.rules.GameRules getRules() {
    return com.openggf.game.rules.GameRules.fromLegacy(getFeatureSet());
}
```

In `PlayableEntity.java`, add:

```java
com.openggf.game.rules.GameRules getGameRules();
```

In `AbstractPlayableSprite.java`, add a field near `physicsFeatureSet`:

```java
private com.openggf.game.rules.GameRules gameRules;
```

Update the existing `setPhysicsFeatureSet(PhysicsFeatureSet fs)` helper used by tests and subclasses so it keeps the bridge fields synchronized:

```java
protected void setPhysicsFeatureSet(PhysicsFeatureSet fs) {
    this.physicsFeatureSet = fs;
    this.gameRules = fs != null ? com.openggf.game.rules.GameRules.fromLegacy(fs) : null;
}
```

If the current method has additional side effects, preserve them and add the `gameRules` assignment next to the `physicsFeatureSet` assignment. This is required because many tests call `setPhysicsFeatureSetForTest(...)` directly and migrated code will read `getGameRules()`.

Set it in `resolvePhysicsProfile(GameModule module)` immediately after `physicsFeatureSet` is resolved:

```java
this.gameRules = module != null ? module.getRules() : null;
```

If cross-game donation is active, leave the old `physicsFeatureSet` override intact for now and set:

```java
this.gameRules = com.openggf.game.rules.GameRules.fromLegacy(this.physicsFeatureSet);
```

Add the getter:

```java
@Override
public com.openggf.game.rules.GameRules getGameRules() {
    return gameRules;
}
```

- [ ] **Step 4: Run focused tests**

Run:

```powershell
mvn "-Dtest=com.openggf.tests.game.TestGameRulesFromPhysicsFeatureSet" test
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/game/GameModule.java src/main/java/com/openggf/game/PhysicsProvider.java src/main/java/com/openggf/game/PlayableEntity.java src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java src/test/java/com/openggf/tests/game/TestGameRulesFromPhysicsFeatureSet.java
git commit -m "refactor: expose typed game rules"
```

Commit trailers:

```text
Changelog: n/a: behavior-preserving rule accessors only
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 3: Replace Cross-Game Hybrid Feature Set With Explicit Rule Composition

**Files:**
- Create: `src/main/java/com/openggf/game/rules/CrossGameRuleComposer.java`
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Test: `src/test/java/com/openggf/tests/game/TestCrossGameRuleComposer.java`

- [ ] **Step 1: Write the composition test**

Create `TestCrossGameRuleComposer.java`:

```java
package com.openggf.tests.game;

import com.openggf.game.DonorCapabilities;
import com.openggf.game.PhysicsFeatureSet;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rules.CrossGameRuleComposer;
import com.openggf.game.rules.GameRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestCrossGameRuleComposer {
    @Test
    void donorCapabilitiesAffectOnlyPlayerCapabilities() {
        GameRules host = GameRules.fromLegacy(PhysicsFeatureSet.SONIC_1);
        GameRules donor = GameRules.fromLegacy(PhysicsFeatureSet.SONIC_3K);

        GameRules composed = CrossGameRuleComposer.compose(host, donor, new DonorCapabilities() {
            @Override public java.util.Set<PlayerCharacter> getPlayableCharacters() {
                return java.util.Set.of(PlayerCharacter.SONIC_ALONE);
            }
            @Override public boolean hasSpindash() { return true; }
            @Override public boolean hasElementalShields() { return true; }
            @Override public boolean hasInstaShield() { return true; }
            @Override public boolean hasSuperTransform() { return true; }
            @Override public boolean hasHyperTransform() { return false; }
            @Override public boolean hasSidekick() { return true; }
            @Override public int resolveNativeId(com.openggf.game.CanonicalAnimation canonical) { return -1; }
            @Override public java.util.Map<com.openggf.game.CanonicalAnimation, com.openggf.game.CanonicalAnimation> getAnimationFallbacks() {
                return java.util.Map.of();
            }
            @Override public com.openggf.data.PlayerSpriteArtProvider getPlayerArtProvider(com.openggf.data.RomByteReader reader) {
                return null;
            }
        });

        assertTrue(composed.playerCapabilities().spindashEnabled());
        assertTrue(composed.playerCapabilities().elementalShieldsEnabled());
        assertTrue(composed.playerCapabilities().instaShieldEnabled());
        assertTrue(composed.playerCapabilities().lightningShieldEnabled());
        assertArrayEquals(donor.playerCapabilities().spindashSpeedTable(), composed.playerCapabilities().spindashSpeedTable());
        assertSame(host.playerCapabilities().superSpindashSpeedTable(), composed.playerCapabilities().superSpindashSpeedTable());

        assertEquals(host.playerMovement(), composed.playerMovement());
        assertEquals(host.collision(), composed.collision());
        assertEquals(host.playerAnimation(), composed.playerAnimation());
        assertEquals(host.camera(), composed.camera());
        assertEquals(host.rings(), composed.rings());
        assertEquals(host.objectInteraction(), composed.objectInteraction());
        assertEquals(host.sidekickCpu(), composed.sidekickCpu());
        assertEquals(host.powerUps(), composed.powerUps());
        assertEquals(host.drowningBubbles(), composed.drowningBubbles());
    }
}
```

Also port the preservation intent from `TestCrossGameFeatureProviderRefactor.assertHybridPreservesBaseExceptDonatedCapabilities`: compare the composed rules to host rules for every top-level rule group except `playerCapabilities`, and compare every `PlayerCapabilityRules` component except this exact donor-owned set: `spindashEnabled`, `spindashSpeedTable`, `elementalShieldsEnabled`, `instaShieldEnabled`, and `lightningShieldEnabled`.

- [ ] **Step 2: Run the failing test**

```powershell
mvn "-Dtest=com.openggf.tests.game.TestCrossGameRuleComposer" test
```

Expected: compile failure because `CrossGameRuleComposer` does not exist.

- [ ] **Step 3: Implement `CrossGameRuleComposer`**

```java
package com.openggf.game.rules;

import com.openggf.game.DonorCapabilities;

public final class CrossGameRuleComposer {
    private CrossGameRuleComposer() {}

    public static GameRules compose(GameRules host, GameRules donor, DonorCapabilities donorCapabilities) {
        if (host == null) {
            throw new IllegalArgumentException("host rules are required");
        }
        if (donor == null) {
            throw new IllegalArgumentException("donor rules are required");
        }
        if (donorCapabilities == null) {
            return host;
        }
        PlayerCapabilityRules capabilities = host.playerCapabilities().withDonorCapabilities(
                donor.playerCapabilities(),
                donorCapabilities.hasSpindash(),
                donorCapabilities.hasElementalShields(),
                donorCapabilities.hasInstaShield());
        return new GameRules(
                host.playerMovement(),
                capabilities,
                host.collision(),
                host.playerAnimation(),
                host.camera(),
                host.rings(),
                host.objectInteraction(),
                host.sidekickCpu(),
                host.powerUps(),
                host.drowningBubbles()
        );
    }
}
```

Add this method to `PlayerCapabilityRules`:

```java
public PlayerCapabilityRules withDonorCapabilities(
        PlayerCapabilityRules donor,
        boolean donorSpindash,
        boolean donorElementalShields,
        boolean donorInstaShield) {
    return new PlayerCapabilityRules(
            donorSpindash,
            donorSpindash ? donor.spindashSpeedTable() : null,
            donorElementalShields,
            donorInstaShield,
            donorElementalShields,
            superSpindashSpeedTable()
    );
}
```

Do not donate `superSpindashSpeedTable` in this task. Current legacy hybrid construction preserves the host value and only overrides `spindashEnabled`, `spindashSpeedTable`, `elementalShieldsEnabled`, `instaShieldEnabled`, and `lightningShieldEnabled`.

- [ ] **Step 4: Replace cross-game rule construction**

In `CrossGameFeatureProvider`, add:

```java
private com.openggf.game.rules.GameRules hybridRules;
```

Set it next to `hybridFeatureSet`:

```java
hybridRules = buildHybridRules();
```

Add:

```java
public com.openggf.game.rules.GameRules getHybridRules() {
    return hybridRules;
}
```

Add:

```java
private com.openggf.game.rules.GameRules buildHybridRules() {
    com.openggf.game.rules.GameRules hostRules = GameServices.module().getRules();
    com.openggf.game.rules.GameRules donorRules = com.openggf.game.rules.GameRules.fromLegacy(resolveDonorFeatureSet());
    return com.openggf.game.rules.CrossGameRuleComposer.compose(hostRules, donorRules, donorCapabilities);
}
```

In `AbstractPlayableSprite.resolvePhysicsProfile`, replace the cross-game `gameRules` assignment with:

```java
if (CrossGameFeatureProvider.isActive()) {
    this.physicsFeatureSet = currentCrossGameFeatures().getHybridFeatureSet();
    this.gameRules = currentCrossGameFeatures().getHybridRules();
}
```

Keep `getHybridFeatureSet()` until all legacy call sites are migrated.

- [ ] **Step 5: Run tests**

```powershell
mvn "-Dtest=com.openggf.tests.game.TestCrossGameRuleComposer,com.openggf.tests.game.TestGameRulesFromPhysicsFeatureSet" test
```

Expected: all tests pass.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/openggf/game/rules/CrossGameRuleComposer.java src/main/java/com/openggf/game/rules/PlayerCapabilityRules.java src/main/java/com/openggf/game/CrossGameFeatureProvider.java src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java src/test/java/com/openggf/tests/game/TestCrossGameRuleComposer.java
git commit -m "refactor: compose cross-game rules explicitly"
```

Commit trailers:

```text
Changelog: n/a: cross-game rule composition mirrors existing hybrid feature behavior
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 4: Migrate Low-Risk Read-Only Consumers

**Files:**
- Modify: `src/main/java/com/openggf/camera/Camera.java`
- Modify: `src/main/java/com/openggf/timer/timers/SpeedShoesTimer.java`
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java`
- Modify: `src/main/java/com/openggf/level/rings/LostRingObjectInstance.java`
- Test: reuse existing camera, timer, and ring tests.

- [ ] **Step 1: Replace camera rule reads**

In `Camera.java`, replace local reads such as:

```java
com.openggf.game.PhysicsFeatureSet fs = sprite.getPhysicsFeatureSet();
```

with:

```java
com.openggf.game.rules.CameraRules rules = sprite.getGameRules() != null
        ? sprite.getGameRules().camera()
        : null;
```

Then replace:

```java
fs.useScreenYWrapValueForVisibility()
```

with:

```java
rules != null && rules.useScreenYWrapValueForVisibility()
```

Use the same pattern for `fastScrollCap()` and `uncappedLeftwardHorizontalScroll()`.

- [ ] **Step 2: Replace speed-shoes timer reads**

In `SpeedShoesTimer.java`, replace:

```java
sprite.getPhysicsFeatureSet().speedShoesTimerDecimation()
sprite.getPhysicsFeatureSet().speedShoesTimerPrePhysicsExtraTicks()
```

with:

```java
sprite.getGameRules().powerUps().speedShoesTimerDecimation()
sprite.getGameRules().powerUps().speedShoesTimerPrePhysicsExtraTicks()
```

Keep the existing fallback value when `sprite` or `getGameRules()` is null.

- [ ] **Step 3: Replace ring rule reads**

In `RingManager.java` and `LostRingObjectInstance.java`, replace:

```java
PhysicsFeatureSet featureSet = player.getPhysicsFeatureSet();
```

with:

```java
com.openggf.game.rules.RingRules ringRules = player.getGameRules() != null
        ? player.getGameRules().rings()
        : null;
```

Map old fields directly:

```java
ringRules.ringFloorCheckMask()
ringRules.ringFloorProbeRequiresRenderFlag()
ringRules.ringCollisionWidth()
ringRules.ringCollisionHeight()
ringRules.stageRingsUseObjectTouchCollection()
ringRules.stageRingSweepUsesRawCameraWindow()
```

- [ ] **Step 4: Run focused tests**

```powershell
mvn "-Dtest=*Camera*,*Ring*,*SpeedShoes*" test
```

Expected: all selected tests pass or Maven reports no matching speed-shoes-specific tests. If no matching timer tests exist, run:

```powershell
mvn "-Dtest=com.openggf.tests.game.TestGameRulesFromPhysicsFeatureSet,com.openggf.tests.game.TestCrossGameRuleComposer" test
```

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/camera/Camera.java src/main/java/com/openggf/timer/timers/SpeedShoesTimer.java src/main/java/com/openggf/level/rings/RingManager.java src/main/java/com/openggf/level/rings/LostRingObjectInstance.java
git commit -m "refactor: route camera timer and ring rules through typed groups"
```

Commit trailers:

```text
Changelog: n/a: behavior-preserving rule read migration
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 5: Migrate Movement, Collision, and Object Interaction Consumers

**Files:**
- Modify: `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`
- Modify: `src/main/java/com/openggf/physics/CollisionSystem.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectSolidContactController.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectTouchResponseController.java`
- Modify: `src/main/java/com/openggf/sprites/managers/PlayableSpriteAnimation.java`
- Test: focused physics, collision, object, and trace smoke tests.

- [ ] **Step 1: Replace movement rule reads**

At each migrated method in `PlayableSpriteMovement.java`, introduce:

```java
com.openggf.game.rules.PlayerMovementRules rules = sprite.getGameRules() != null
        ? sprite.getGameRules().playerMovement()
        : null;
```

Replace movement fields directly:

```java
fixedAnglePosThreshold()
inputAlwaysCapsGroundSpeed()
jumpRepressClearsRollJumpBeforeAbility()
angleDiffCardinalSnap()
extendedEdgeBalance()
singleFacingBalanceAnimationSet()
movingCrouchThreshold()
groundWallCollisionEnabled()
groundWallPushRequiresFacingIntoWall()
repeatedObjectRideGroundWallResponseDeferred()
animationChangeClearsPush()
airSuperspeedPreserved()
slopeResistStartsFromRest()
slopeRepelChecksOnObject()
slopeRepelUsesS3kSlipKick()
pinballLandingPreservesRoll()
pinballLandingPreservesPinballMode()
rollingJumpPinballGateRequiresSpindashFlag()
landingRollClearUsesCurrentYRadiusDelta()
rollStopsBelowMinimumSpeed()
rollControlledDecelUsesEffectiveDecelQuarter()
levelBoundaryRightStrict()
levelBoundaryUsesCentreY()
levelBoundaryLockUsesScreenLockFlag()
controlLockLatchesLogicalInput()
hurtRoutineLatchesLogicalInput()
waterExitBoostSkipsFastUpwardVelocity()
```

- [ ] **Step 2: Replace collision rule reads**

At each migrated method in `CollisionSystem.java`, introduce:

```java
com.openggf.game.rules.PlayerMovementRules movementRules = sprite.getGameRules() != null
        ? sprite.getGameRules().playerMovement()
        : null;
```

Replace collision movement fields directly:

```java
collisionModel()
airRightWallHitContinuesIntoCeilingSeparation()
airLeftWallHitContinuesIntoCeilingSeparation()
rightWallDeepProbePreservesPenetration()
playerControlAppliesVerticalWrapMask()
```

- [ ] **Step 3: Replace object interaction rule reads**

At each migrated method in `ObjectSolidContactController.java` and `ObjectTouchResponseController.java`, introduce:

```java
com.openggf.game.rules.ObjectInteractionRules rules = player.getGameRules() != null
        ? player.getGameRules().objectInteraction()
        : null;
```

Replace object/touch fields directly:

```java
topSolidLandingAllowsZeroDist()
airBottomSolidHitClearsGroundSpeed()
fullSolidBottomOverlapUsesCurrentYRadiusOnly()
bossHitNegatesGroundSpeed()
bossHitHalvesBounceVelocity()
solidObjectOffscreenGate()
solidObjectRequiresSidekickOnScreen()
solidObjectTopBranchAlwaysLiftsOnUpwardVelocity()
permanentRespawnTableLatch()
objectsExecuteAfterPlayerPhysics()
touchResponseUsesRenderFlagYGate()
touchResponseUsesPreviousCollisionResponseList()
solidObjectBarelyPokingResolvesAsSide()
solidObjectKeepsOnObjWhenJumpedOffSameFrame()
animalObjectPreservesObjectMoveXSubpixel()
animalObjectUsesRenderFlagDeleteBounds()
```

- [ ] **Step 4: Replace animation rule reads**

In `PlayableSpriteAnimation.java`, replace:

```java
sprite.getPhysicsFeatureSet().animationChangeClearsPush()
```

with:

```java
sprite.getGameRules().playerMovement().animationChangeClearsPush()
```

Keep null fallback behavior matching the current code.

- [ ] **Step 5: Run focused tests**

```powershell
mvn "-Dtest=*Collision*,*Object*,*Physics*,*Playable*" test
```

Expected: all selected tests pass. If this set is too broad for one local pass, split by package and record the exact failures before changing behavior.

- [ ] **Step 6: Run required S3K smoke tests**

```powershell
mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test
```

Expected: all tests pass.

- [ ] **Step 7: Run representative trace replay tests**

```powershell
mvn "-Dtest=com.openggf.tests.trace.s1.TestS1Ghz1CompleteRunTraceReplay,com.openggf.tests.trace.s2.TestS2Ehz1TraceReplay,com.openggf.tests.trace.s3k.TestS3kAizCompleteRunTraceReplay" test
```

Expected: all selected trace replay tests pass. If a local ROM is absent, run the subset whose ROMs are present and record every skipped game explicitly in the implementation summary.

- [ ] **Step 8: Commit**

```powershell
git add src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java src/main/java/com/openggf/physics/CollisionSystem.java src/main/java/com/openggf/level/objects/ObjectSolidContactController.java src/main/java/com/openggf/level/objects/ObjectTouchResponseController.java src/main/java/com/openggf/sprites/managers/PlayableSpriteAnimation.java
git commit -m "refactor: route movement collision and object rules through typed groups"
```

Commit trailers:

```text
Changelog: n/a: behavior-preserving rule read migration
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 6: Migrate Sidekick, Power-Up, and Drowning Consumers

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Modify: `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java`
- Modify: `src/main/java/com/openggf/sprites/playable/DrowningController.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
- Modify: `src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java`
- Test: sidekick, drowning, and cross-game rule tests.

- [ ] **Step 1: Replace sidekick CPU rule reads**

In `SidekickCpuController.java`, introduce local rule accessors:

```java
private com.openggf.game.rules.SidekickCpuRules sidekickRules() {
    return sidekick.getGameRules() != null ? sidekick.getGameRules().sidekickCpu() : null;
}
```

Replace all sidekick-specific legacy reads with `sidekickRules()`:

```java
followSnapThreshold()
despawnX()
followLeadOffset()
followNudgeBlockedByObjectControlBit0()
delayedJumpPressUsesHistoryEdge()
panicTreatsPinballModeAsSpindashFlag()
spawningRequiresGroundedLeader()
despawnUsesObjectIdMismatch()
normalDespawnDelaysFreshRenderEntry()
flyLandStatusBlockerMask()
flyLandRequiresLeaderAlive()
catchUpYOffset()
flightAutoLandFrames()
flightMaxXStep()
flightYStep()
flightLeadXOffset()
flightLeadSuppressGSpeed()
despawnUsesRidingInstanceLoss()
respawnEntersCatchUpFlight()
pushBypassUsesGraceStatus()
suppressesFastLeaderTinyFollowNudge()
clearsStalePushVelocityBeforeGroundMove()
cpuUsesLevelFrameCounter()
normalCpuSkipsHurtRoutine()
deathUsesDeferredDespawn()
```

- [ ] **Step 2: Replace Tails respawn strategy rule reads**

In `TailsRespawnStrategy.java`, replace `PhysicsFeatureSet` reads with:

```java
com.openggf.game.rules.SidekickCpuRules rules = sidekick.getGameRules() != null
        ? sidekick.getGameRules().sidekickCpu()
        : null;
```

Keep the existing fallback constants when `rules` is null.

- [ ] **Step 3: Replace drowning and power-up rule reads**

In `DrowningController.java`, replace bubble cadence reads with:

```java
com.openggf.game.rules.DrowningBubbleRules rules = player.getGameRules() != null
        ? player.getGameRules().drowningBubbles()
        : null;
```

Use:

```java
initialDrowningCountdownFrameTimer()
mouthBubbleTimerBias()
breathingBubbleDefersFirstObjectPass()
mouthBubbleRiseVelocity()
```

In `AbstractPlayableSprite.java` and `DefaultPowerUpSpawner.java`, replace ability checks with `playerCapabilities()` and fixed object slot/dust checks with `powerUps()`.

- [ ] **Step 4: Run focused tests**

```powershell
mvn "-Dtest=*Sidekick*,*Drowning*,*CrossGame*,com.openggf.tests.game.TestGameRulesFromPhysicsFeatureSet" test
```

Expected: all selected tests pass.

- [ ] **Step 5: Run sidekick and power-up trace replay tests**

```powershell
mvn "-Dtest=com.openggf.tests.trace.s2.TestS2MczLevelSelectTraceReplay,com.openggf.tests.trace.s2.TestS2Cnz2LevelSelectTraceReplay,com.openggf.tests.trace.s3k.TestS3kAizCompleteRunTraceReplay" test
```

Expected: all selected trace replay tests pass. If a local ROM is absent, run the subset whose ROMs are present and record every skipped game explicitly in the implementation summary.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/com/openggf/sprites/playable/SidekickCpuController.java src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java src/main/java/com/openggf/sprites/playable/DrowningController.java src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java src/main/java/com/openggf/level/objects/DefaultPowerUpSpawner.java
git commit -m "refactor: route sidekick power-up and drowning rules through typed groups"
```

Commit trailers:

```text
Changelog: n/a: behavior-preserving rule read migration
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 7: Add a Guard Against New Broad `PhysicsFeatureSet` Usage

**Files:**
- Create: `src/test/java/com/openggf/tests/game/TestNoNewPhysicsFeatureSetCallSites.java`
- Create: `src/test/resources/architecture/physics-feature-set-bridge-baseline.txt`
- Modify: `src/main/java/com/openggf/game/PhysicsFeatureSet.java`
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`

- [ ] **Step 1: Write the baseline scanner guard**

Create `TestNoNewPhysicsFeatureSetCallSites.java`:

```java
package com.openggf.tests.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestNoNewPhysicsFeatureSetCallSites {
    private static final List<String> ALLOWED = List.of(
            "src/main/java/com/openggf/game/PhysicsFeatureSet.java",
            "src/main/java/com/openggf/game/PhysicsProvider.java",
            "src/main/java/com/openggf/game/PlayableEntity.java",
            "src/main/java/com/openggf/game/CrossGameFeatureProvider.java",
            "src/main/java/com/openggf/game/rules/",
            "src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java",
            "src/main/java/com/openggf/game/sonic1/Sonic1PhysicsProvider.java",
            "src/main/java/com/openggf/game/sonic2/Sonic2PhysicsProvider.java",
            "src/main/java/com/openggf/game/sonic3k/Sonic3kPhysicsProvider.java"
    );

    @Test
    void runtimeCodeUsesTypedRulesInsteadOfBroadPhysicsFeatureSet() throws IOException {
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> ALLOWED.stream().noneMatch(allowed ->
                            path.toString().replace('\\', '/').contains(allowed)))
                    .filter(this::containsLegacyFeatureSetRead)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .toList();

            assertEquals(loadBaseline(), new java.util.TreeSet<>(offenders),
                    "Update the typed GameRules migration before adding new PhysicsFeatureSet runtime users");
        }
    }

    private Set<String> loadBaseline() throws IOException {
        Path baseline = Path.of("src/test/resources/architecture/physics-feature-set-bridge-baseline.txt");
        if (!Files.exists(baseline)) {
            return Set.of();
        }
        return Files.readAllLines(baseline).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .filter(line -> !line.startsWith("#"))
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    private boolean containsLegacyFeatureSetRead(Path path) {
        try {
            String text = Files.readString(path);
            return text.contains("getPhysicsFeatureSet()")
                    || text.contains("PhysicsFeatureSet;")
                    || text.contains("PhysicsFeatureSet ")
                    || text.contains("PhysicsFeatureSet.");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 2: Generate and commit the initial bridge baseline**

Run the guard once. It should fail and print the current offender list. Copy only intentional bridge-period paths into `src/test/resources/architecture/physics-feature-set-bridge-baseline.txt`.

```powershell
mvn "-Dtest=com.openggf.tests.game.TestNoNewPhysicsFeatureSetCallSites" test
```

Expected: first run fails with the current offender list; after creating the baseline, the same command passes. Do not add module providers, `PhysicsFeatureSet.java`, `GameRules` files, `PlayableEntity.java`, `AbstractPlayableSprite.java`, or `CrossGameFeatureProvider.java` to the baseline because those paths are already explicit bridge allowances. Every other baseline entry is a migration checklist item that must shrink as Tasks 4-6 land.

- [ ] **Step 3: Remove obsolete hybrid legacy access**

After the guard exposes no real runtime users of `getHybridFeatureSet()`, remove from `CrossGameFeatureProvider`:

```java
private PhysicsFeatureSet hybridFeatureSet;
public PhysicsFeatureSet getHybridFeatureSet()
private PhysicsFeatureSet buildHybridFeatureSet()
```

In `AbstractPlayableSprite`, keep `getPhysicsFeatureSet()` only if tests or public APIs still require it; otherwise remove it from `PlayableEntity` and restrict it to package-private test support.

- [ ] **Step 4: Add deprecation marker to remaining legacy bridge**

If `PhysicsFeatureSet` must remain for provider constants, add this class-level Javadoc line:

```java
 * @deprecated Runtime call sites should use {@link com.openggf.game.rules.GameRules}
 * typed groups. This record remains as the legacy source for S1/S2/S3K constants
 * until the constants are moved into the rule records directly.
```

And annotate:

```java
@Deprecated
public record PhysicsFeatureSet(...)
```

- [ ] **Step 5: Add rule record cohesion assertions**

In `TestNoNewPhysicsFeatureSetCallSites`, add a second test that reflects every record in `com.openggf.game.rules` except `GameRules` and `CrossGameRuleComposer` and fails if any record has more than 20 components. The fix for a failure is to split the rule record by consumer; do not raise the threshold without an architecture review.

```java
@Test
void ruleRecordsStayNarrowEnoughToReview() {
    java.util.List<Class<?>> ruleRecords = java.util.List.of(
            com.openggf.game.rules.PlayerMovementRules.class,
            com.openggf.game.rules.PlayerCapabilityRules.class,
            com.openggf.game.rules.CollisionRules.class,
            com.openggf.game.rules.PlayerAnimationRules.class,
            com.openggf.game.rules.CameraRules.class,
            com.openggf.game.rules.RingRules.class,
            com.openggf.game.rules.ObjectInteractionRules.class,
            com.openggf.game.rules.SidekickCpuRules.class,
            com.openggf.game.rules.PowerUpRules.class,
            com.openggf.game.rules.DrowningBubbleRules.class
    );
    java.util.Map<String, Integer> oversized = ruleRecords.stream()
            .filter(type -> type.getRecordComponents().length > 20)
            .collect(java.util.stream.Collectors.toMap(
                    Class::getSimpleName,
                    type -> type.getRecordComponents().length,
                    (left, right) -> left,
                    java.util.TreeMap::new));
    assertEquals(java.util.Map.of(), oversized);
}
```

- [ ] **Step 6: Run guard and mapping tests**

```powershell
mvn "-Dtest=com.openggf.tests.game.TestNoNewPhysicsFeatureSetCallSites,com.openggf.tests.game.TestGameRulesFromPhysicsFeatureSet,com.openggf.tests.game.TestCrossGameRuleComposer" test
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```powershell
git add src/test/java/com/openggf/tests/game/TestNoNewPhysicsFeatureSetCallSites.java src/test/resources/architecture/physics-feature-set-bridge-baseline.txt src/main/java/com/openggf/game/PhysicsFeatureSet.java src/main/java/com/openggf/game/CrossGameFeatureProvider.java src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java
git commit -m "test: guard typed per-game rule boundaries"
```

Commit trailers:

```text
Changelog: n/a: architecture guard and legacy cleanup only
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

### Task 8: Final Verification and Documentation

**Files:**
- Modify: `CHANGELOG.md`
- Create: `docs/architecture/per-game-rule-placement.md`
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: `docs/agent-workflow/**` files that still instruct agents to add `PhysicsFeatureSet` flags.
- Modify: `.agents/skills/**` and `.claude/skills/**` files that still instruct agents to add `PhysicsFeatureSet` flags.

- [ ] **Step 1: Create the focused rule-placement guide**

Create `docs/architecture/per-game-rule-placement.md`:

```markdown
# Per-Game Rule Placement

Use this guide when adding or moving a Sonic 1, Sonic 2, or Sonic 3&K behavior divergence. The goal is to choose the smallest accurate owner instead of adding another broad feature flag.

## Decision Tree

1. If the behavior is data, art, mappings, DPLC, PLC, animation script, palette data, or ROM asset availability, use the existing data loader, art provider, donor capability, or ROM offset provider. Do not add a `GameRules` field.
2. If the behavior is zone-local or event-local, use `ZoneFeatureProvider`, `ZoneRuntimeState`, zone event handlers, or an existing runtime registry. Do not add a game-wide rule unless the same ROM rule applies across the game.
3. If the behavior belongs to one object family, use an object profile, object-local hook, or shared object execution profile. Do not add a game-wide rule for one object family.
4. If the behavior is character ability availability or cross-game donation, use `PlayerCapabilityRules` or `DonorCapabilities`. Cross-game donation may only donate explicitly listed capability fields.
5. If the behavior is shared runtime logic that differs by game across broad systems, use the narrowest `GameRules` record consumed by that system:
   - `PlayerMovementRules`: movement, roll, slope, jump, control, and boundary movement rules.
   - `CollisionRules`: collision model, terrain probe, wall-push, platform contact, and collision ordering.
   - `PlayerAnimationRules`: animation-state divergences tied to shared player animation logic.
   - `CameraRules`: camera scroll, wrap, visibility, and tracking rules.
   - `RingRules`: placed/lost ring collision, collection, cadence, attraction, and ring object model rules.
   - `ObjectInteractionRules`: solid object, touch response, boss hit, respawn table, and object execution ordering.
   - `SidekickCpuRules`: CPU sidekick follow, panic, despawn, catch-up, and death flow.
   - `PowerUpRules`: timer cadence, fixed power-up object slots, and shield/invincibility/speed-shoes support details.
   - `DrowningBubbleRules`: drowning countdown and mouth-bubble cadence rules.
6. If no existing owner fits, stop and add an architecture note before adding a new rule group.

## Admission Checklist

Every new per-game divergence must document:

- ROM evidence: disassembly location or trace-observed ROM state.
- Scope: game-wide, character-wide, object-family, zone-local, or data/provider-owned.
- Owner: exact rule record, provider, profile, or registry.
- Boundary rationale: why this owner is the smallest accurate owner, and why narrower object/zone/provider owners do or do not apply.
- Cross-game value table: Sonic 1, Sonic 2, and Sonic 3&K values.
- Verification: focused unit test, trace replay, or explicit reason trace coverage is not applicable.

## Review Rules

- Prefer provider/profile ownership when a divergence is not shared runtime behavior.
- Prefer a new narrow rule group over growing an unrelated rule record.
- Do not add raw game-name branches in shared runtime code.
- Do not add new broad `PhysicsFeatureSet` runtime call sites.
- Do not raise rule-record component-count guard thresholds without architecture review.
```

- [ ] **Step 2: Update short agent guidance**

In `AGENTS.md` and `CLAUDE.md`, replace the physics rule sentence that says new divergences must always add flags to `PhysicsFeatureSet` with:

```markdown
Per-game behavioral differences must use the smallest accurate owner: the current `PhysicsFeatureSet` bridge for game-wide shared runtime gates until typed `GameRules` lands in this refactor, or an existing provider/profile/registry for data, art, zone-local, or object-family behavior. Do not add raw game-name branches in shared runtime code, and avoid adding new broad `PhysicsFeatureSet` runtime users. See `docs/architecture/per-game-rule-placement.md` before adding a new per-game gate.
```

- [ ] **Step 3: Update workflow and skill guidance**

Run:

```powershell
rg "PhysicsFeatureSet|feature set" docs/agent-workflow .agents/skills .claude/skills
```

For active workflow guidance and active skills, replace "add a `PhysicsFeatureSet` flag" instructions with a short reference to `docs/architecture/per-game-rule-placement.md` and the smallest-owner rule. Keep historical changelogs and old completed plan files unchanged.

- [ ] **Step 4: Update changelog**

Add an Unreleased entry to `CHANGELOG.md`:

```markdown
- Refactored per-game runtime behavior gates into typed rule groups so player movement, object interaction, sidekick CPU, camera, ring, timer, power-up, and drowning differences no longer share one broad feature surface.
```

- [ ] **Step 5: Run focused architecture and smoke tests**

```powershell
mvn "-Dtest=com.openggf.tests.game.TestNoNewPhysicsFeatureSetCallSites,com.openggf.tests.game.TestGameRulesFromPhysicsFeatureSet,com.openggf.tests.game.TestCrossGameRuleComposer,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test
```

Expected: all tests pass.

- [ ] **Step 6: Run full test suite**

```powershell
mvn test
```

Expected: all tests pass. If ROM-backed tests are skipped because local ROMs are absent, record that in the final implementation summary.

- [ ] **Step 7: Commit**

```powershell
git add CHANGELOG.md AGENTS.md CLAUDE.md docs/architecture/per-game-rule-placement.md docs/agent-workflow .agents/skills .claude/skills
git commit -m "docs: document typed per-game rule boundaries"
```

Commit trailers:

```text
Changelog: updated
Guide: updated
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: updated
Configuration-Docs: n/a
Skills: updated
```

---

## Execution Notes

- Keep each task behavior-preserving unless a test exposes an existing mismatch.
- Do not edit trace data while executing this plan.
- If a trace replay fails after a migration task, use `trace-replay-bug-fixing` before changing behavior.
- Prefer focused tests after each task; run broader tests only after a subsystem migration is complete.
- The migration should not remove `PhysicsFeatureSet` constants until every runtime call site has moved to `GameRules`.

## Self-Review

Spec coverage:
- The plan addresses the overloaded `PhysicsFeatureSet` surface by adding typed rule groups.
- The plan addresses cross-game hybrid ambiguity through `CrossGameRuleComposer`.
- The plan addresses future regression risk through a scanner guard.
- The plan updates agent guidance so future divergence work uses typed rules.

Placeholder scan:
- The plan contains no placeholder markers or unspecified implementation steps.

Type consistency:
- All new rule access flows through `GameRules`.
- Runtime migration steps consistently use `player.getGameRules()` or `sprite.getGameRules()`.
- Cross-game composition changes only `PlayerCapabilityRules`; host runtime rules remain host-owned.
