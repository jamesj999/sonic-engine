package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.GameRng;
import com.openggf.game.GameId;
import com.openggf.game.rewind.RewindRoundTripHarness;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.boss.AbstractBossChild;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TestMgzEndBossKnuxInstance {
    private static ObjectSpawn spawn() {
        return new ObjectSpawn(0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS_KNUX,
                0, 0, false, 0);
    }

    private static MgzEndBossKnuxInstance bossWithServices() {
        MgzEndBossKnuxInstance boss = new MgzEndBossKnuxInstance(spawn());
        boss.setServices(new StubObjectServices());
        return boss;
    }

    @Test
    void s3klRegistryCreatesDedicatedKnucklesBoss() {
        ObjectInstance object = new Sonic3kObjectRegistry().create(spawn());
        assertInstanceOf(MgzEndBossKnuxInstance.class, object);
    }

    @Test
    void exposesRomCapsuleCoordinatesAndHighPlane() {
        MgzEndBossKnuxInstance boss = bossWithServices();
        assertEquals(0x3F40, MgzEndBossKnuxInstance.getCapsuleX());
        assertEquals(0x00B0, MgzEndBossKnuxInstance.getCapsuleY());
        assertTrue(boss.isHighPriority());
        assertEquals(6, boss.getPriorityBucket());
        assertEquals(8, boss.getCollisionProperty());
    }

    @Test
    void usesNativeEightHitBossCollisionProperty() {
        MgzEndBossKnuxInstance boss = bossWithServices();
        for (int hit = 7; hit >= 1; hit--) {
            boss.onPlayerAttack(null, null);
            assertEquals(hit, boss.getCollisionProperty());
            for (int frame = 0; frame < 0x20; frame++) boss.update(frame, null);
        }
        boss.onPlayerAttack(null, null);
        assertEquals(0, boss.getCollisionProperty());
    }

    @Test
    void productionObjectGraphPublishesSeedsAndAdvancesBothDrops() throws Exception {
        RewindRoundTripHarness harness = RewindRoundTripHarness.build(GameId.S3K);
        var manager = harness.objectManager();
        ObjectSpawn harnessSpawn = new ObjectSpawn(0x100, 0x100, Sonic3kObjectIds.MGZ_END_BOSS_KNUX,
                0, 0, false, 0);
        MgzEndBossKnuxInstance boss = manager.createDynamicObject(
                () -> new MgzEndBossKnuxInstance(harnessSpawn));
        boolean sawFirstDrop = false;
        boolean sawSecondDrop = false;
        for (int frame = 0; frame < 600 && !sawSecondDrop; frame++) {
            manager.update(0, null, java.util.List.of(), frame, false);
            sawFirstDrop |= boss.getNativeRoutineForTesting() == 0x08;
            sawSecondDrop |= boss.getNativeRoutineForTesting() == 0x0C;
        }
        assertTrue(sawFirstDrop, "boss-owned loc_6C9E8 graph must publish FA82 and enter routine 8");
        assertTrue(sawSecondDrop, "the same production child graph must publish FA8A and enter routine C");
    }

    @Test
    void rewindRoundTripRestoresEveryCompositeChildRoleIntoTheManagedBossGraph() throws Exception {
        RewindRoundTripHarness harness = RewindRoundTripHarness.buildPlaced(
                GameId.S3K, Sonic3kObjectIds.MGZ_END_BOSS_KNUX);
        var manager = harness.objectManager();
        MgzEndBossKnuxInstance sourceBoss = manager.getActiveObjects().stream()
                .filter(MgzEndBossKnuxInstance.class::isInstance)
                .map(MgzEndBossKnuxInstance.class::cast)
                .findFirst()
                .orElseThrow();
        manager.update(0, null, List.of(), 0, false);

        List<MgzEndBossRenderChild> sourceChildren = compositeChildren(sourceBoss);
        assertEquals(8, sourceChildren.size(), "precondition: the live MGZ boss owns all eight composite children");
        assertCompositeRolesAndParents(sourceBoss, sourceChildren);
        List<ObjectRefId> sourceChildIds = sourceChildren.stream()
                .map(child -> manager.captureIdentityContext().requireIdentityTable().idFor(child))
                .toList();

        harness.roundTrip();

        MgzEndBossKnuxInstance restoredBoss = manager.getActiveObjects().stream()
                .filter(MgzEndBossKnuxInstance.class::isInstance)
                .map(MgzEndBossKnuxInstance.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored Knuckles boss; live="
                        + manager.getActiveObjects().stream()
                        .map(object -> object.getClass().getName())
                        .toList()));
        assertNotSame(sourceBoss, restoredBoss, "round-trip must reconstruct the boss");
        List<MgzEndBossRenderChild> restoredChildren = compositeChildren(restoredBoss);
        assertEquals(8, restoredChildren.size(),
                "restored boss childComponents must own every captured composite child exactly once");
        assertCompositeRolesAndParents(restoredBoss, restoredChildren);
        assertEquals(sourceChildIds, restoredChildren.stream()
                .map(child -> manager.captureIdentityContext().requireIdentityTable().idFor(child))
                .toList(), "each composite child role must retain its captured rewind identity");

        List<MgzEndBossRenderChild> managedChildren = manager.getActiveObjects().stream()
                .filter(MgzEndBossRenderChild.class::isInstance)
                .map(MgzEndBossRenderChild.class::cast)
                .toList();
        assertEquals(8, managedChildren.size(), "restore must retain exactly eight managed composite children");
        for (MgzEndBossRenderChild child : restoredChildren) {
            assertTrue(managedChildren.contains(child),
                    "each childComponents entry must be the exact restored object managed by ObjectManager");
        }
    }

    @Test
    void rewindRoundTripRecreatesDrillChildWithRestoredParentAndState() throws Exception {
        RewindRoundTripHarness harness = RewindRoundTripHarness.buildPlaced(
                GameId.S3K, Sonic3kObjectIds.MGZ_END_BOSS_KNUX);
        var manager = harness.objectManager();
        MgzEndBossKnuxInstance sourceBoss = manager.getActiveObjects().stream()
                .filter(MgzEndBossKnuxInstance.class::isInstance)
                .map(MgzEndBossKnuxInstance.class::cast)
                .findFirst().orElseThrow();
        manager.update(0, null, List.of(), 0, false);
        MgzEndBossKnuxDrillChild sourceChild = onlyDrillChild(manager);
        ObjectRefId sourceId = manager.captureIdentityContext().requireIdentityTable().idFor(sourceChild);
        int sourceTimer = intField(sourceChild, "timer");
        int sourceRoutine = intField(sourceChild, "nativeRoutine");

        harness.roundTrip();

        MgzEndBossKnuxInstance restoredBoss = manager.getActiveObjects().stream()
                .filter(MgzEndBossKnuxInstance.class::isInstance)
                .map(MgzEndBossKnuxInstance.class::cast)
                .findFirst().orElseThrow();
        MgzEndBossKnuxDrillChild restoredChild = onlyDrillChild(manager);
        assertNotSame(sourceChild, restoredChild);
        assertEquals(sourceId, manager.captureIdentityContext().requireIdentityTable().idFor(restoredChild));
        assertSame(restoredBoss, parentOf(restoredChild));
        assertEquals(sourceTimer, intField(restoredChild, "timer"));
        assertEquals(sourceRoutine, intField(restoredChild, "nativeRoutine"));
    }

    @Test
    void collapseEmitterPublishesOneParticleEveryFourObjectPasses() {
        StubObjectServices services = new StubObjectServices();
        MgzEndBossKnuxCollapseEmitter emitter = new MgzEndBossKnuxCollapseEmitter(0, 0, false);
        emitter.setServices(services);
        emitter.update(0, null);
        assertEquals(9, emitter.emissionsRemainingForTesting());
        emitter.update(1, null);
        emitter.update(2, null);
        emitter.update(3, null);
        assertEquals(9, emitter.emissionsRemainingForTesting());
        emitter.update(4, null);
        assertEquals(8, emitter.emissionsRemainingForTesting());
    }

    @Test
    void defeatPartsUseChildObjDat6d822PrioritySplit() {
        MgzEndBossKnuxDefeatPart first = new MgzEndBossKnuxDefeatPart(
                new ObjectSpawn(0, 0, 0, 0, 0, false, 0));
        MgzEndBossKnuxDefeatPart second = new MgzEndBossKnuxDefeatPart(
                new ObjectSpawn(0, 0, 0, 2, 0, false, 0));
        MgzEndBossKnuxDefeatPart third = new MgzEndBossKnuxDefeatPart(
                new ObjectSpawn(0, 0, 0, 4, 0, false, 0));
        assertEquals(6, first.getPriorityBucket());
        assertEquals(4, second.getPriorityBucket());
        assertEquals(4, third.getPriorityBucket());
        assertTrue(first.isHighPriority() && second.isHighPriority() && third.isHighPriority());
    }

    @Test
    void rewindRoundTripRecreatesOneBossAndCapsuleWithRestoredOwnerIdentity() throws Exception {
        RewindRoundTripHarness harness = RewindRoundTripHarness.build(GameId.S3K);
        var manager = harness.objectManager();
        MgzEndBossKnuxInstance sourceBoss = manager.createDynamicObject(
                () -> new MgzEndBossKnuxInstance(spawn()));
        manager.createDynamicObject(() -> new MgzEndBossKnuxEggCapsuleInstance(sourceBoss,
                new ObjectSpawn(0x3F40, 0xB0, Sonic3kObjectIds.EGG_CAPSULE, 0, 0, false, 1)));

        harness.roundTrip();

        var bosses = manager.getActiveObjects().stream().filter(MgzEndBossKnuxInstance.class::isInstance)
                .map(MgzEndBossKnuxInstance.class::cast).toList();
        var capsules = manager.getActiveObjects().stream().filter(MgzEndBossKnuxEggCapsuleInstance.class::isInstance)
                .map(MgzEndBossKnuxEggCapsuleInstance.class::cast).toList();
        assertEquals(1, bosses.size());
        assertEquals(1, capsules.size());
        assertNotSame(sourceBoss, bosses.getFirst());
        assertSame(bosses.getFirst(), capsules.getFirst().ownerForTesting());

        var signal = MgzEndBossKnuxEggCapsuleInstance.class
                .getDeclaredMethod("signalOwnerResultsComplete");
        signal.setAccessible(true);
        signal.invoke(capsules.getFirst());
        var resultsComplete = MgzEndBossKnuxInstance.class.getDeclaredField("resultsComplete");
        resultsComplete.setAccessible(true);
        assertTrue(resultsComplete.getBoolean(bosses.getFirst()),
                "the restored capsule must continue signaling its restored boss owner");
    }

    private static List<MgzEndBossRenderChild> compositeChildren(MgzEndBossKnuxInstance boss) {
        return boss.getChildComponents().stream()
                .filter(MgzEndBossRenderChild.class::isInstance)
                .map(MgzEndBossRenderChild.class::cast)
                .toList();
    }

    private static void assertCompositeRolesAndParents(
            MgzEndBossKnuxInstance boss, List<MgzEndBossRenderChild> children) throws Exception {
        for (int role = MgzEndBossRenderChild.ROLE_FIRST; role <= MgzEndBossRenderChild.ROLE_LAST; role++) {
            int expectedRole = role;
            List<MgzEndBossRenderChild> roleChildren = children.stream()
                    .filter(child -> child.role() == expectedRole)
                    .toList();
            assertEquals(1, roleChildren.size(), "boss must own exactly one composite child for role " + role);
            assertSame(boss, parentOf(roleChildren.getFirst()),
                    "role " + role + " child must relink to its restored boss parent");
        }
    }

    private static Object parentOf(Object child) throws Exception {
        Class<?> type = child.getClass();
        while (type != null) {
            try {
                Field parent = type.getDeclaredField("parent");
                parent.setAccessible(true);
                return parent.get(child);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new AssertionError("missing parent field on " + child.getClass().getName());
    }

    private static MgzEndBossKnuxDrillChild onlyDrillChild(
            com.openggf.level.objects.ObjectManager manager) {
        List<MgzEndBossKnuxDrillChild> children = manager.getActiveObjects().stream()
                .filter(MgzEndBossKnuxDrillChild.class::isInstance)
                .map(MgzEndBossKnuxDrillChild.class::cast)
                .toList();
        assertEquals(1, children.size(), "the boss graph must own exactly one drill child slot");
        return children.getFirst();
    }

    private static int intField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }
}
