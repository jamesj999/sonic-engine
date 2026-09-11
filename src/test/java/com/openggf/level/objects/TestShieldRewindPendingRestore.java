package com.openggf.level.objects;

import com.openggf.game.PowerUpObject;
import com.openggf.game.PlayableEntity;
import com.openggf.game.ShieldType;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.graphics.GLCommand;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestShieldRewindPendingRestore {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void shieldDynamicRestoreQueuesPendingEntryThroughGenericRecreateWithoutSharedCodec() {
        ObjectManager objectManager = new ObjectManager(List.of(), null, 0, null, null);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        ShieldObjectInstance source = ObjectConstructionContext.construct(
                objectManager.services(), () -> new ShieldObjectInstance(sonic));
        source.setSlotIndex(60);
        objectManager.addDynamicObject(source);

        ObjectManagerSnapshot snapshot = objectManager.rewindSnapshottable().capture();

        objectManager.rewindSnapshottable().restore(snapshot);

        PowerUpObject restoredPowerUp = new DefaultPowerUpSpawner(objectManager)
                .spawnShield(sonic, ShieldType.BASIC);
        ShieldObjectInstance restoredShield = assertInstanceOf(ShieldObjectInstance.class, restoredPowerUp);
        assertSame(sonic, restoredShield.getPlayer());
        assertEquals(60, restoredShield.getSlotIndex(),
                "generic recreate should queue the captured dynamic entry for player refresh");
    }

    @Test
    void objectManagerRoundTripPreservesDynamicThenFixedCollisionOrderAndPartialBuild() {
        ObjectManager objectManager = new ObjectManager(List.of(), null, 0, null, null);
        TestablePlayableSprite sonic =
                new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        RewindablePublisher dynamic = new RewindablePublisher(
                new ObjectSpawn(100, 200, 1, 0, 0, false, 0));
        objectManager.addDynamicObject(dynamic);
        FixedPowerUpPublisher fixed = new FixedPowerUpPublisher();
        objectManager.addDynamicObjectAtSlot(fixed, 50);
        objectManager.registerInitialFixedDispatchObject(fixed);

        try (InitialObjectDispatchScope scope =
                     objectManager.beginInitialProcessSprites(0, sonic, List.of())) {
            objectManager.freezeInitialCollisionResponseReadView();
            objectManager.resetInitialCollisionResponseBuild();
            objectManager.processInitialDynamicSlots(scope);
            objectManager.processInitialFixedDispatchObject(scope, fixed);
            objectManager.finishInitialProcessSprites(scope);
        }
        assertEquals(1, fixed.updates);
        ObjectManagerSnapshot snapshot;
        objectManager.registerInitialFixedDispatchObject(fixed);
        try (InitialObjectDispatchScope scope =
                     objectManager.beginInitialProcessSprites(0, sonic, List.of())) {
            objectManager.freezeInitialCollisionResponseReadView();
            objectManager.resetInitialCollisionResponseBuild();
            objectManager.processInitialDynamicSlots(scope);
        }
        snapshot = objectManager.rewindSnapshottable().capture();

        assertEquals(2, snapshot.collisionResponseState().previousCollisionObjectIds().size());
        assertEquals(1, snapshot.collisionResponseState().currentCollisionBuildObjectIds().size());
        assertTrue(snapshot.collisionResponseState().usePreviousCollisionList());
        assertEquals(1, snapshot.collisionResponseState().currentCollisionBuildCursor());
        assertEquals(ObjectManagerSnapshot.CollisionBuildStage.CURRENT_BUILDING,
                snapshot.collisionResponseState().collisionBuildStage());

        objectManager.rewindSnapshottable().restore(snapshot);
        RewindablePublisher restoredDynamic =
                objectManager.activeObjectsOfType(RewindablePublisher.class).getFirst();
        FixedPowerUpPublisher restoredFixed =
                objectManager.activeObjectsOfType(FixedPowerUpPublisher.class).getFirst();

        assertEquals(List.of(restoredDynamic, restoredFixed),
                objectManager.getTouchResponseObjects());
        ObjectManagerSnapshot roundTrip = objectManager.rewindSnapshottable().capture();
        assertEquals(snapshot.collisionResponseState(), roundTrip.collisionResponseState(),
                "production capture/restore must preserve ordered previous/current ids "
                        + "and the selected read buffer");
        assertEquals(snapshot.dynamicObjectIdCounter(), roundTrip.dynamicObjectIdCounter(),
                "restoring the captured fixed power-up id must not consume a fresh ordinal");

        NullSpawnPublisher next = new NullSpawnPublisher();
        objectManager.addDynamicObject(next);
        ObjectManagerSnapshot afterNextSpawn = objectManager.rewindSnapshottable().capture();
        ObjectManagerSnapshot.DynamicObjectEntry nextEntry = afterNextSpawn.dynamicObjects()
                .stream()
                .filter(entry -> entry.className().equals(NullSpawnPublisher.class.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals(snapshot.dynamicObjectIdCounter(), nextEntry.objectId().dynamicId(),
                "the next genuine spawn must mint the same id it would have without restore");
    }

    @Test
    void reversedStarsRecreationRetainsEachPlayersCapturedIdentity() {
        ObjectManager objectManager = new ObjectManager(List.of(), null, 0, null, null);
        TestablePlayableSprite p1 =
                new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        TestablePlayableSprite p2 =
                new TestablePlayableSprite("tails", (short) 140, (short) 200);
        DefaultPowerUpSpawner sourceSpawner = new DefaultPowerUpSpawner(objectManager);
        sourceSpawner.spawnInvincibilityStars(p1);
        sourceSpawner.spawnInvincibilityStars(p2);
        ObjectManagerSnapshot source = objectManager.rewindSnapshottable().capture();
        var p1Id = source.dynamicObjects().stream()
                .filter(entry -> entry.playerOwner() == p1)
                .findFirst().orElseThrow().objectId();
        var p2Id = source.dynamicObjects().stream()
                .filter(entry -> entry.playerOwner() == p2)
                .findFirst().orElseThrow().objectId();
        assertNotEquals(p1Id, p2Id);

        objectManager.rewindSnapshottable().restore(source);
        DefaultPowerUpSpawner restoredSpawner = new DefaultPowerUpSpawner(objectManager);
        ObjectInstance restoredP2 =
                (ObjectInstance) restoredSpawner.spawnInvincibilityStars(p2);
        ObjectInstance restoredP1 =
                (ObjectInstance) restoredSpawner.spawnInvincibilityStars(p1);
        ObjectManagerSnapshot reversed = objectManager.rewindSnapshottable().capture();

        assertEquals(p1Id, idFor(reversed, restoredP1));
        assertEquals(p2Id, idFor(reversed, restoredP2));
        assertNotEquals(idFor(reversed, restoredP1), idFor(reversed, restoredP2));
    }

    @Test
    void pendingShieldRespawnMatchesOwnerInsteadOfFifoForSameConcreteType() {
        ObjectManager objectManager = new ObjectManager(List.of(), null, 0, null, null);
        DefaultPowerUpSpawner spawner = new DefaultPowerUpSpawner(objectManager);
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 100, (short) 200);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 140, (short) 200);

        objectManager.enqueuePendingPlayerBoundEntry(
                ShieldObjectInstance.class, shieldEntryFor(objectManager, tails, 50));
        objectManager.enqueuePendingPlayerBoundEntry(
                ShieldObjectInstance.class, shieldEntryFor(objectManager, sonic, 60));

        PowerUpObject sonicShield = spawner.spawnShield(sonic, ShieldType.BASIC);
        ShieldObjectInstance sonicShieldObject = assertInstanceOf(ShieldObjectInstance.class, sonicShield);
        assertSame(sonic, sonicShieldObject.getPlayer(),
                "respawned shield must stay bound to the requesting player");
        assertEquals(60, sonicShieldObject.getSlotIndex(),
                "sonic must consume sonic's pending shield entry, not the FIFO head");

        PowerUpObject tailsShield = spawner.spawnShield(tails, ShieldType.BASIC);
        ShieldObjectInstance tailsShieldObject = assertInstanceOf(ShieldObjectInstance.class, tailsShield);
        assertSame(tails, tailsShieldObject.getPlayer());
        assertEquals(50, tailsShieldObject.getSlotIndex(),
                "tails' entry should remain pending until tails refreshes");
    }

    private static ObjectManagerSnapshot.DynamicObjectEntry shieldEntryFor(
            ObjectManager objectManager, TestablePlayableSprite player, int slotIndex) {
        ShieldObjectInstance source = ObjectConstructionContext.construct(
                objectManager.services(), () -> new ShieldObjectInstance(player));
        source.setSlotIndex(slotIndex);
        return new ObjectManagerSnapshot.DynamicObjectEntry(
                ShieldObjectInstance.class.getName(),
                null,
                slotIndex,
                source.captureRewindState(),
                player);
    }

    private static com.openggf.game.rewind.identity.ObjectRefId idFor(
            ObjectManagerSnapshot snapshot, ObjectInstance object) {
        return snapshot.dynamicObjects().stream()
                .filter(entry -> entry.slotIndex()
                        == ((AbstractObjectInstance) object).getSlotIndex())
                .filter(entry -> entry.playerOwner()
                        == ((PowerUpObject) object).boundPlayer())
                .findFirst()
                .orElseThrow()
                .objectId();
    }

    private static final class RewindablePublisher extends AbstractObjectInstance
            implements RewindRecreatable, TouchResponseProvider {
        private RewindablePublisher(ObjectSpawn spawn) {
            super(spawn, "RewindablePublisher");
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override public int getCollisionFlags() { return 0xC7; }
        @Override public int getCollisionProperty() { return 0; }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext context) {
            return new RewindablePublisher(getSpawn());
        }
    }

    private static final class NullSpawnPublisher extends AbstractObjectInstance
            implements RewindRecreatable {
        private NullSpawnPublisher() {
            super(null, "NullSpawnPublisher");
        }

        @Override public void update(int vIntRunCount, PlayableEntity player) {}
        @Override public void appendRenderCommands(List<GLCommand> commands) {}
        @Override public AbstractObjectInstance recreateForRewind(RewindRecreateContext context) {
            return new NullSpawnPublisher();
        }
    }

    private static final class FixedPowerUpPublisher extends AbstractObjectInstance
            implements PowerUpObject, TouchResponseProvider, RewindRecreatable {
        private int updates;
        private FixedPowerUpPublisher() {
            super(null, "FixedPowerUpPublisher");
        }

        @Override public void update(int vIntRunCount, PlayableEntity player) { updates++; }
        @Override public void appendRenderCommands(List<GLCommand> commands) {}
        @Override public void destroy() { setDestroyed(true); }
        @Override public void setVisible(boolean visible) {}
        @Override public int getCollisionFlags() { return 0xC7; }
        @Override public int getCollisionProperty() { return 0; }
        @Override public AbstractObjectInstance recreateForRewind(RewindRecreateContext context) {
            return new FixedPowerUpPublisher();
        }
    }
}
