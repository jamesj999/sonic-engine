package com.openggf.game.rewind;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.identity.ObjectRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczCrushingColumnObjectInstance;
import com.openggf.game.sonic3k.objects.IczIceSpikesObjectInstance;
import com.openggf.game.sonic3k.objects.IczTensionPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.MgzMinibossInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kNestedHurtboxGraphRewind {
    private static final String MGZ_DRILL_ARM_CLASS =
            "com.openggf.game.sonic3k.objects.MgzMinibossInstance$DrillArmChild";
    private static final String MGZ_CEILING_DEBRIS_CLASS =
            "com.openggf.game.sonic3k.objects.MgzMinibossInstance$CeilingDebrisChild";
    private static final String MGZ_DEFEAT_FRAGMENT_CLASS =
            "com.openggf.game.sonic3k.objects.MgzMinibossInstance$DefeatFragmentChild";
    private static final String MGZ_SPIKE_PLATFORM_CLASS =
            "com.openggf.game.sonic3k.objects.MgzMinibossInstance$KnucklesSpikePlatformChild";
    private static final String MGZ_CAMERA_SCROLL_HELPER_CLASS =
            "com.openggf.game.sonic3k.objects.MgzMinibossInstance$MgzBossCameraScrollHelper";
    private static final String HCZ_VORTEX_BUBBLE_CLASS =
            "com.openggf.game.sonic3k.objects.HczMinibossInstance$VortexBubbleChild";
    private static final String ICZ_HURT_CHILD_CLASS =
            "com.openggf.game.sonic3k.objects.IczIceSpikesObjectInstance$SpikeHurtChild";
    private static final String ICZ_TENSION_SUPPORT_CLASS =
            "com.openggf.game.sonic3k.objects.IczTensionPlatformObjectInstance$SupportChild";
    private static final String ICZ_CRUSHING_COLUMN_DECORATION_CLASS =
            "com.openggf.game.sonic3k.objects.IczCrushingColumnObjectInstance$BottomDecoration";

    private static final ObjectSpawn MGZ_BOSS_SPAWN =
            new ObjectSpawn(0x0300, 0x0200, Sonic3kObjectIds.MGZ_MINIBOSS, 0, 0, false, 10);
    private static final ObjectSpawn HCZ_BOSS_SPAWN =
            new ObjectSpawn(0x0500, 0x0200, Sonic3kObjectIds.HCZ_MINIBOSS, 0, 0, false, 12);
    private static final ObjectSpawn ICZ_SPIKE_A =
            new ObjectSpawn(0x0120, 0x0180, Sonic3kObjectIds.ICZ_ICE_SPIKES, 0, 0, false, 20);
    private static final ObjectSpawn ICZ_SPIKE_B =
            new ObjectSpawn(0x0280, 0x01C0, Sonic3kObjectIds.ICZ_ICE_SPIKES, 0, 0, false, 21);
    private static final ObjectSpawn ICZ_TENSION_PLATFORM =
            new ObjectSpawn(0x0340, 0x0240, Sonic3kObjectIds.ICZ_TENSION_PLATFORM, 0, 0, false, 22);
    private static final ObjectSpawn ICZ_CRUSHING_COLUMN =
            new ObjectSpawn(0x0480, 0x0180, Sonic3kObjectIds.ICZ_CRUSHING_COLUMN, 0, 0, false, 23);

    @BeforeEach
    void initHeadless() {
        GraphicsManager.getInstance().initHeadless();
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x800, 0x700, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void mgzDrillArmsRestoreFreshRelinkParentSlotsAndDoNotDuplicate() {
        Harness harness = Harness.create(List.of(MGZ_BOSS_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        MgzMinibossInstance sourceBoss = only(objectManager, MgzMinibossInstance.class);
        invokeNoArg(sourceBoss, "spawnArmChildren");
        List<ObjectInstance> sourceArms = liveObjectsByName(objectManager, MGZ_DRILL_ARM_CLASS);
        assertEquals(2, sourceArms.size(), "precondition: MGZ miniboss must have two drill arms");
        ObjectInstance sourceLeft = armWithXOffset(sourceArms, -0x1C);
        ObjectInstance sourceRight = armWithXOffset(sourceArms, 0x1C);
        setIntField(sourceLeft, "currentX", 0x02D5);
        setIntField(sourceLeft, "currentY", 0x01E4);
        setIntField(sourceRight, "currentX", 0x032B);
        setIntField(sourceRight, "currentY", 0x01E5);

        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceLeft);
        objectManager.removeDynamicObject(sourceRight);
        ObjectInstance divergentArm = objectManager.createDynamicObject(
                () -> constructMgzArm(sourceBoss, 0x40, 0));
        assertEquals(1, liveObjectsByName(objectManager, MGZ_DRILL_ARM_CLASS).size(),
                "diverge step should leave one unrelated arm before restore");

        rewindRegistry.restore(snapshot);

        MgzMinibossInstance restoredBoss = objectById(objectManager, MgzMinibossInstance.class, bossId);
        List<ObjectInstance> restoredArms = liveObjectsByName(objectManager, MGZ_DRILL_ARM_CLASS);
        assertEquals(2, restoredArms.size(), "restore must keep exactly the captured two MGZ drill arms");
        ObjectInstance restoredLeft = armWithXOffset(restoredArms, -0x1C);
        ObjectInstance restoredRight = armWithXOffset(restoredArms, 0x1C);
        assertNotSame(sourceBoss, restoredBoss, "restore must recreate the MGZ miniboss parent");
        assertNotSame(sourceLeft, restoredLeft, "restore must recreate the left arm");
        assertNotSame(sourceRight, restoredRight, "restore must recreate the right arm");
        assertNotSame(divergentArm, restoredLeft, "restore must drop the divergent arm");
        assertNotSame(divergentArm, restoredRight, "restore must drop the divergent arm");

        assertSame(restoredBoss, readObjectField(restoredLeft, "parent"),
                "left arm parent must resolve to the restored MGZ miniboss by captured identity");
        assertSame(restoredBoss, readObjectField(restoredRight, "parent"),
                "right arm parent must resolve to the restored MGZ miniboss by captured identity");
        assertNotSame(sourceBoss, readObjectField(restoredLeft, "parent"),
                "left arm must not retain the stale pre-restore parent");
        assertNotSame(sourceBoss, readObjectField(restoredRight, "parent"),
                "right arm must not retain the stale pre-restore parent");
        assertSame(restoredLeft, readObjectField(restoredBoss, "leftArm"),
                "MGZ miniboss leftArm must point at the restored left arm");
        assertSame(restoredRight, readObjectField(restoredBoss, "rightArm"),
                "MGZ miniboss rightArm must point at the restored right arm");
        assertEquals(0x02D5, readIntField(restoredLeft, "currentX"));
        assertEquals(0x01E4, readIntField(restoredLeft, "currentY"));
        assertEquals(0x032B, readIntField(restoredRight, "currentX"));
        assertEquals(0x01E5, readIntField(restoredRight, "currentY"));

        invokeNoArg(restoredBoss, "spawnArmChildren");
        assertEquals(2, liveObjectsByName(objectManager, MGZ_DRILL_ARM_CLASS).size(),
                "post-restore arm spawn path must not duplicate restored arms");
    }

    @Test
    void mgzFlippedDrillArmsRestoreSemanticParentSlotsAndDoNotDuplicate() {
        Harness harness = Harness.create(List.of(MGZ_BOSS_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        MgzMinibossInstance sourceBoss = only(objectManager, MgzMinibossInstance.class);
        setBooleanField(sourceBoss, "facingRight", true);
        invokeNoArg(sourceBoss, "spawnArmChildren");
        List<ObjectInstance> sourceArms = liveObjectsByName(objectManager, MGZ_DRILL_ARM_CLASS);
        assertEquals(2, sourceArms.size(), "precondition: flipped MGZ miniboss must have two drill arms");
        ObjectInstance sourceLeft = armWithXOffset(sourceArms, -0x1C);
        ObjectInstance sourceRight = armWithXOffset(sourceArms, 0x1C);
        assertTrue(sourceLeft.getX() > sourceBoss.getX(),
                "precondition: facingRight flips the semantic left arm to the physical right");
        assertTrue(sourceRight.getX() < sourceBoss.getX(),
                "precondition: facingRight flips the semantic right arm to the physical left");

        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceLeft);
        objectManager.removeDynamicObject(sourceRight);
        objectManager.createDynamicObject(() -> constructMgzArm(sourceBoss, 0x40, 0));

        rewindRegistry.restore(snapshot);

        MgzMinibossInstance restoredBoss = objectById(objectManager, MgzMinibossInstance.class, bossId);
        List<ObjectInstance> restoredArms = liveObjectsByName(objectManager, MGZ_DRILL_ARM_CLASS);
        assertEquals(2, restoredArms.size(), "restore must keep exactly the captured flipped MGZ drill arms");
        ObjectInstance restoredLeft = armWithXOffset(restoredArms, -0x1C);
        ObjectInstance restoredRight = armWithXOffset(restoredArms, 0x1C);
        assertSame(restoredBoss, readObjectField(restoredLeft, "parent"),
                "flipped left arm parent must resolve to the restored MGZ miniboss");
        assertSame(restoredBoss, readObjectField(restoredRight, "parent"),
                "flipped right arm parent must resolve to the restored MGZ miniboss");
        assertSame(restoredLeft, readObjectField(restoredBoss, "leftArm"),
                "flipped restore must relink semantic leftArm by captured xOffset, not physical side");
        assertSame(restoredRight, readObjectField(restoredBoss, "rightArm"),
                "flipped restore must relink semantic rightArm by captured xOffset, not physical side");

        invokeNoArg(restoredBoss, "spawnArmChildren");
        assertEquals(2, liveObjectsByName(objectManager, MGZ_DRILL_ARM_CLASS).size(),
                "post-restore flipped arm spawn path must not duplicate restored arms");
    }

    @Test
    void mgzMinibossTailObjectsRestoreFreshRelinkParentAndPreserveScalars() {
        Harness harness = Harness.create(List.of(MGZ_BOSS_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        MgzMinibossInstance sourceBoss = only(objectManager, MgzMinibossInstance.class);
        ObjectInstance sourceDebris = objectManager.createDynamicObject(
                () -> constructMgzCeilingDebris(0x0330, 0x0180, 2, false));
        ObjectInstance sourceFragment = objectManager.createDynamicObject(
                () -> constructMgzDefeatFragment(0x0340, 0x01A0, 3, 0x120, -0x380));
        ObjectInstance sourcePlatform = objectManager.createDynamicObject(
                () -> constructMgzSpikePlatform(sourceBoss, true, 0x02E0, 0x0100));
        ObjectInstance sourceScrollHelper = objectManager.createDynamicObject(
                () -> constructMgzCameraScrollHelper(0x2E00));
        setIntField(sourcePlatform, "currentX", 0x03B4);
        setIntField(sourcePlatform, "currentY", 0x01C8);
        setIntField(sourcePlatform, "baseY", 0x01F0);
        setIntField(sourcePlatform, "routine", 4);
        setIntField(sourcePlatform, "timer", 0x55);

        ObjectRefId bossId = objectId(objectManager, sourceBoss);
        ObjectRefId debrisId = objectId(objectManager, sourceDebris);
        ObjectRefId fragmentId = objectId(objectManager, sourceFragment);
        ObjectRefId platformId = objectId(objectManager, sourcePlatform);
        ObjectRefId scrollHelperId = objectId(objectManager, sourceScrollHelper);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceDebris);
        objectManager.removeDynamicObject(sourceFragment);
        objectManager.removeDynamicObject(sourcePlatform);
        objectManager.removeDynamicObject(sourceScrollHelper);
        ObjectInstance divergentPlatform = objectManager.createDynamicObject(
                () -> constructMgzSpikePlatform(sourceBoss, false, 0x0200, 0x0200));
        assertEquals(1, liveObjectsByName(objectManager, MGZ_SPIKE_PLATFORM_CLASS).size(),
                "diverge step should leave one unrelated MGZ spike platform before restore");

        rewindRegistry.restore(snapshot);

        MgzMinibossInstance restoredBoss = objectById(objectManager, MgzMinibossInstance.class, bossId);
        ObjectInstance restoredDebris = objectById(objectManager, debrisId);
        ObjectInstance restoredFragment = objectById(objectManager, fragmentId);
        ObjectInstance restoredPlatform = objectById(objectManager, platformId);
        ObjectInstance restoredScrollHelper = objectById(objectManager, scrollHelperId);
        assertEquals(1, liveObjectsByName(objectManager, MGZ_CEILING_DEBRIS_CLASS).size(),
                "restore must keep exactly the captured MGZ ceiling debris");
        assertEquals(1, liveObjectsByName(objectManager, MGZ_DEFEAT_FRAGMENT_CLASS).size(),
                "restore must keep exactly the captured MGZ defeat fragment");
        assertEquals(1, liveObjectsByName(objectManager, MGZ_SPIKE_PLATFORM_CLASS).size(),
                "restore must keep exactly the captured MGZ spike platform");
        assertEquals(1, liveObjectsByName(objectManager, MGZ_CAMERA_SCROLL_HELPER_CLASS).size(),
                "restore must keep exactly the captured MGZ camera scroll helper");
        assertNotSame(sourceDebris, restoredDebris, "restore must recreate MGZ ceiling debris");
        assertNotSame(sourceFragment, restoredFragment, "restore must recreate MGZ defeat fragment");
        assertNotSame(sourcePlatform, restoredPlatform, "restore must recreate MGZ spike platform");
        assertNotSame(sourceScrollHelper, restoredScrollHelper, "restore must recreate MGZ camera scroll helper");
        assertNotSame(divergentPlatform, restoredPlatform, "restore must drop the divergent spike platform");
        assertSame(restoredBoss, readObjectField(restoredPlatform, "parent"),
                "spike platform parent must resolve to the restored MGZ miniboss");
        assertNotSame(sourceBoss, readObjectField(restoredPlatform, "parent"),
                "spike platform must not retain the stale pre-restore parent");
        assertEquals(2, readIntField(restoredDebris, "mappingFrame"));
        assertFalse(readBooleanField(restoredDebris, "spire"));
        assertEquals(3, readIntField(restoredFragment, "mappingFrame"));
        assertEquals(0x120, readIntField(restoredFragment, "xVel"));
        assertEquals(-0x380, readIntField(restoredFragment, "yVel"));
        assertTrue(readBooleanField(restoredPlatform, "mirrored"));
        assertEquals(0x03B4, readIntField(restoredPlatform, "currentX"));
        assertEquals(0x01C8, readIntField(restoredPlatform, "currentY"));
        assertEquals(0x01F0, readIntField(restoredPlatform, "baseY"));
        assertEquals(4, readIntField(restoredPlatform, "routine"));
        assertEquals(0x55, readIntField(restoredPlatform, "timer"));
        assertEquals(0x2E00, readIntField(restoredScrollHelper, "targetX"));
    }

    @Test
    void hczVortexBubbleRestoresFreshAndPreservesPullState() {
        Harness harness = Harness.create(List.of(HCZ_BOSS_SPAWN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        ObjectInstance sourceBubble = objectManager.createDynamicObject(
                () -> constructHczVortexBubble(0x0520, 0x01E0, 2, 0x0500, 0x0200));
        setIntField(sourceBubble, "phase", 1);
        setIntField(sourceBubble, "timer", 0x16);
        setShortField(sourceBubble, "xVel", (short) 0x0120);
        setIntField(sourceBubble, "xSub", 0x4000);
        setIntField(sourceBubble, "ySub", 0x8000);
        setBooleanField(sourceBubble, "vortexEnded", true);

        ObjectRefId bubbleId = objectId(objectManager, sourceBubble);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceBubble);
        ObjectInstance divergentBubble = objectManager.createDynamicObject(
                () -> constructHczVortexBubble(0x0600, 0x01A0, 0, 0x0600, 0x01A0));
        assertEquals(1, liveObjectsByName(objectManager, HCZ_VORTEX_BUBBLE_CLASS).size(),
                "diverge step should leave one unrelated HCZ vortex bubble before restore");

        rewindRegistry.restore(snapshot);

        ObjectInstance restoredBubble = objectById(objectManager, bubbleId);
        assertEquals(1, liveObjectsByName(objectManager, HCZ_VORTEX_BUBBLE_CLASS).size(),
                "restore must keep exactly the captured HCZ vortex bubble");
        assertNotSame(sourceBubble, restoredBubble, "restore must recreate the HCZ vortex bubble");
        assertNotSame(divergentBubble, restoredBubble, "restore must drop the divergent HCZ vortex bubble");
        assertEquals(0x0520, restoredBubble.getSpawn().x());
        assertEquals(0x01E0, restoredBubble.getSpawn().y());
        assertEquals(2, readIntField(restoredBubble, "frame"));
        assertEquals(0x0500, readIntField(restoredBubble, "vortexX"));
        assertEquals(0x0200, readIntField(restoredBubble, "vortexY"));
        assertEquals(1, readIntField(restoredBubble, "phase"));
        assertEquals(0x16, readIntField(restoredBubble, "timer"));
        assertEquals(0x0120, readShortField(restoredBubble, "xVel"));
        assertEquals(0x4000, readIntField(restoredBubble, "xSub"));
        assertEquals(0x8000, readIntField(restoredBubble, "ySub"));
        assertTrue(readBooleanField(restoredBubble, "vortexEnded"));
    }

    @Test
    void iczSpikeHurtChildrenRestoreFreshRelinkNearestParentsAndDoNotDuplicate() {
        Harness harness = Harness.create(List.of(ICZ_SPIKE_A, ICZ_SPIKE_B));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        List<IczIceSpikesObjectInstance> sourceParents =
                liveObjects(objectManager, IczIceSpikesObjectInstance.class);
        assertEquals(2, sourceParents.size(), "precondition: two ICZ spike bases must be live");
        IczIceSpikesObjectInstance sourceParentA = sourceParents.get(0);
        IczIceSpikesObjectInstance sourceParentB = sourceParents.get(1);
        sourceParentA.update(0, null);
        sourceParentB.update(0, null);
        List<ObjectInstance> sourceChildren = liveObjectsByName(objectManager, ICZ_HURT_CHILD_CLASS);
        assertEquals(2, sourceChildren.size(), "precondition: each ICZ spike base must spawn one hurt child");
        ObjectInstance sourceChildA = childWithParent(sourceChildren, sourceParentA);
        ObjectInstance sourceChildB = childWithParent(sourceChildren, sourceParentB);

        ObjectRefId parentAId = objectId(objectManager, sourceParentA);
        ObjectRefId parentBId = objectId(objectManager, sourceParentB);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceChildA);
        objectManager.removeDynamicObject(sourceChildB);
        ObjectInstance divergentChild = objectManager.createDynamicObject(
                () -> constructIczHurtChild(sourceParentA, 0x0500, 0x0500));
        assertEquals(1, liveObjectsByName(objectManager, ICZ_HURT_CHILD_CLASS).size(),
                "diverge step should leave one unrelated ICZ hurt child before restore");

        rewindRegistry.restore(snapshot);

        IczIceSpikesObjectInstance restoredParentA =
                objectById(objectManager, IczIceSpikesObjectInstance.class, parentAId);
        IczIceSpikesObjectInstance restoredParentB =
                objectById(objectManager, IczIceSpikesObjectInstance.class, parentBId);
        List<ObjectInstance> restoredChildren = liveObjectsByName(objectManager, ICZ_HURT_CHILD_CLASS);
        assertEquals(2, liveObjects(objectManager, IczIceSpikesObjectInstance.class).size(),
                "restore must keep exactly the captured two ICZ spike parents");
        assertEquals(2, restoredChildren.size(),
                "restore must keep exactly the captured two ICZ hurt children");
        ObjectInstance restoredChildA = childWithParent(restoredChildren, restoredParentA);
        ObjectInstance restoredChildB = childWithParent(restoredChildren, restoredParentB);
        assertNotSame(sourceParentA, restoredParentA, "restore must recreate ICZ parent A");
        assertNotSame(sourceParentB, restoredParentB, "restore must recreate ICZ parent B");
        assertNotSame(sourceChildA, restoredChildA, "restore must recreate ICZ child A");
        assertNotSame(sourceChildB, restoredChildB, "restore must recreate ICZ child B");
        assertNotSame(divergentChild, restoredChildA, "restore must drop the divergent ICZ child");
        assertNotSame(divergentChild, restoredChildB, "restore must drop the divergent ICZ child");

        assertSame(restoredParentA, readObjectField(restoredChildA, "parent"),
                "ICZ child A parent must resolve to restored parent A by captured identity");
        assertSame(restoredParentB, readObjectField(restoredChildB, "parent"),
                "ICZ child B parent must resolve to restored parent B by captured identity");
        assertNotSame(sourceParentA, readObjectField(restoredChildA, "parent"),
                "ICZ child A must not retain the stale pre-restore parent");
        assertNotSame(sourceParentB, readObjectField(restoredChildB, "parent"),
                "ICZ child B must not retain the stale pre-restore parent");
        assertSame(restoredChildA, readObjectField(restoredParentA, "hurtChild"),
                "ICZ parent A hurtChild must point at the restored child");
        assertSame(restoredChildB, readObjectField(restoredParentB, "hurtChild"),
                "ICZ parent B hurtChild must point at the restored child");
        assertTrue(readBooleanField(restoredParentA, "childSpawned"),
                "ICZ parent A childSpawned latch must remain true after relink");
        assertTrue(readBooleanField(restoredParentB, "childSpawned"),
                "ICZ parent B childSpawned latch must remain true after relink");

        restoredParentA.update(1, null);
        restoredParentB.update(1, null);
        assertEquals(2, liveObjectsByName(objectManager, ICZ_HURT_CHILD_CLASS).size(),
                "post-restore ICZ update must not spawn duplicate hurt children");
    }

    @Test
    void iczSupportChildrenRestoreFreshRelinkParentsAndDoNotDuplicate() {
        Harness harness = Harness.create(List.of(ICZ_TENSION_PLATFORM, ICZ_CRUSHING_COLUMN));
        ObjectManager objectManager = harness.objectManager();
        objectManager.setRewindInPlaceRestoreEnabledForTest(false);
        IczTensionPlatformObjectInstance sourcePlatform =
                only(objectManager, IczTensionPlatformObjectInstance.class);
        IczCrushingColumnObjectInstance sourceColumn =
                only(objectManager, IczCrushingColumnObjectInstance.class);
        sourcePlatform.update(0, null);
        sourceColumn.update(0, null);
        List<ObjectInstance> sourceSupports = liveObjectsByName(objectManager, ICZ_TENSION_SUPPORT_CLASS);
        List<ObjectInstance> sourceDecorations =
                liveObjectsByName(objectManager, ICZ_CRUSHING_COLUMN_DECORATION_CLASS);
        assertEquals(2, sourceSupports.size(), "precondition: tension platform must spawn two supports");
        assertEquals(1, sourceDecorations.size(), "precondition: crushing column must spawn one decoration");
        ObjectInstance sourceLeftSupport = childAtX(sourceSupports, ICZ_TENSION_PLATFORM.x() - 0x38);
        ObjectInstance sourceRightSupport = childAtX(sourceSupports, ICZ_TENSION_PLATFORM.x() + 0x38);
        ObjectInstance sourceDecoration = sourceDecorations.getFirst();
        setIntField(sourceLeftSupport, "y", 0x0238);
        setIntField(sourceRightSupport, "y", 0x023C);

        ObjectRefId platformId = objectId(objectManager, sourcePlatform);
        ObjectRefId columnId = objectId(objectManager, sourceColumn);
        RewindRegistry rewindRegistry = registryFor(objectManager);
        CompositeSnapshot snapshot = rewindRegistry.capture();

        objectManager.removeDynamicObject(sourceLeftSupport);
        objectManager.removeDynamicObject(sourceRightSupport);
        objectManager.removeDynamicObject(sourceDecoration);
        ObjectInstance divergentSupport = objectManager.createDynamicObject(
                () -> constructIczTensionSupport(sourcePlatform, 0x0600, 0x0300, false));
        ObjectInstance divergentDecoration = objectManager.createDynamicObject(
                () -> constructIczCrushingColumnDecoration(sourceColumn));
        assertEquals(1, liveObjectsByName(objectManager, ICZ_TENSION_SUPPORT_CLASS).size(),
                "diverge step should leave one unrelated tension support before restore");
        assertEquals(1, liveObjectsByName(objectManager, ICZ_CRUSHING_COLUMN_DECORATION_CLASS).size(),
                "diverge step should leave one unrelated column decoration before restore");

        rewindRegistry.restore(snapshot);

        IczTensionPlatformObjectInstance restoredPlatform =
                objectById(objectManager, IczTensionPlatformObjectInstance.class, platformId);
        IczCrushingColumnObjectInstance restoredColumn =
                objectById(objectManager, IczCrushingColumnObjectInstance.class, columnId);
        List<ObjectInstance> restoredSupports = liveObjectsByName(objectManager, ICZ_TENSION_SUPPORT_CLASS);
        List<ObjectInstance> restoredDecorations =
                liveObjectsByName(objectManager, ICZ_CRUSHING_COLUMN_DECORATION_CLASS);
        assertEquals(1, liveObjects(objectManager, IczTensionPlatformObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ tension platform");
        assertEquals(1, liveObjects(objectManager, IczCrushingColumnObjectInstance.class).size(),
                "restore must keep exactly the captured ICZ crushing column");
        assertEquals(2, restoredSupports.size(),
                "restore must keep exactly the captured two tension supports");
        assertEquals(1, restoredDecorations.size(),
                "restore must keep exactly the captured column decoration");
        ObjectInstance restoredLeftSupport = childAtX(restoredSupports, ICZ_TENSION_PLATFORM.x() - 0x38);
        ObjectInstance restoredRightSupport = childAtX(restoredSupports, ICZ_TENSION_PLATFORM.x() + 0x38);
        ObjectInstance restoredDecoration = restoredDecorations.getFirst();
        assertNotSame(sourcePlatform, restoredPlatform, "restore must recreate the ICZ tension platform");
        assertNotSame(sourceColumn, restoredColumn, "restore must recreate the ICZ crushing column");
        assertNotSame(sourceLeftSupport, restoredLeftSupport, "restore must recreate the left support");
        assertNotSame(sourceRightSupport, restoredRightSupport, "restore must recreate the right support");
        assertNotSame(sourceDecoration, restoredDecoration, "restore must recreate the decoration");
        assertNotSame(divergentSupport, restoredLeftSupport, "restore must drop the divergent support");
        assertNotSame(divergentSupport, restoredRightSupport, "restore must drop the divergent support");
        assertNotSame(divergentDecoration, restoredDecoration, "restore must drop the divergent decoration");

        assertSame(restoredPlatform, readObjectField(restoredLeftSupport, "parent"),
                "left support must relink to the restored tension platform");
        assertSame(restoredPlatform, readObjectField(restoredRightSupport, "parent"),
                "right support must relink to the restored tension platform");
        assertSame(restoredColumn, readObjectField(restoredDecoration, "parent"),
                "bottom decoration must relink to the restored crushing column");
        assertEquals(0x0238, readIntField(restoredLeftSupport, "y"));
        assertEquals(0x023C, readIntField(restoredRightSupport, "y"));

        restoredPlatform.update(1, null);
        restoredColumn.update(1, null);
        assertEquals(2, liveObjectsByName(objectManager, ICZ_TENSION_SUPPORT_CLASS).size(),
                "post-restore platform update must not spawn duplicate supports");
        assertEquals(1, liveObjectsByName(objectManager, ICZ_CRUSHING_COLUMN_DECORATION_CLASS).size(),
                "post-restore column update must not spawn duplicate decorations");
    }

    @Test
    void nestedHurtboxChildrenUseRewindRecreatableWithoutExplicitDynamicCodecs() throws Exception {
        assertTrue(RewindRecreatable.class.isAssignableFrom(IczIceSpikesObjectInstance.class),
                "ICZ ice spikes root must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(MGZ_DRILL_ARM_CLASS)),
                "MGZ drill arm must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(MGZ_CEILING_DEBRIS_CLASS)),
                "MGZ ceiling debris must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(MGZ_DEFEAT_FRAGMENT_CLASS)),
                "MGZ defeat fragment must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(MGZ_SPIKE_PLATFORM_CLASS)),
                "MGZ spike platform must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(MGZ_CAMERA_SCROLL_HELPER_CLASS)),
                "MGZ camera scroll helper must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(ICZ_HURT_CHILD_CLASS)),
                "ICZ ice-spike hurt child must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(ICZ_TENSION_SUPPORT_CLASS)),
                "ICZ tension-platform support must restore through RewindRecreatable generic recreate");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Class.forName(ICZ_CRUSHING_COLUMN_DECORATION_CLASS)),
                "ICZ crushing-column bottom decoration must restore through RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(IczIceSpikesObjectInstance.class.getName()),
                "ICZ ice spikes root must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MGZ_DRILL_ARM_CLASS),
                "MGZ drill arm must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MGZ_CEILING_DEBRIS_CLASS),
                "MGZ ceiling debris must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MGZ_DEFEAT_FRAGMENT_CLASS),
                "MGZ defeat fragment must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MGZ_SPIKE_PLATFORM_CLASS),
                "MGZ spike platform must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MGZ_CAMERA_SCROLL_HELPER_CLASS),
                "MGZ camera scroll helper must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ICZ_HURT_CHILD_CLASS),
                "ICZ ice-spike hurt child must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ICZ_TENSION_SUPPORT_CLASS),
                "ICZ tension-platform support must not keep an explicit S3K dynamic codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(ICZ_CRUSHING_COLUMN_DECORATION_CLASS),
                "ICZ crushing-column bottom decoration must not keep an explicit S3K dynamic codec");
    }

    @Test
    void captureFailsForNestedHurtboxWhoseRequiredParentHasNoRewindIdentity() {
        Harness mgzHarness = Harness.create(List.of());
        MgzMinibossInstance unmanagedBoss = new MgzMinibossInstance(MGZ_BOSS_SPAWN);
        unmanagedBoss.setServices(mgzHarness.services());
        ObjectInstance arm = mgzHarness.objectManager().createDynamicObject(
                () -> constructMgzArm(unmanagedBoss, -0x1C, -0x16));
        assertSame(unmanagedBoss, readObjectField(arm, "parent"),
                "precondition: MGZ arm parent is outside ObjectManager identity registration");

        IllegalStateException mgzThrown = assertThrows(
                IllegalStateException.class, registryFor(mgzHarness.objectManager())::capture);
        assertTrue(mgzThrown.getMessage().contains("no registered id for object reference"),
                "missing MGZ parent identity must fail loudly");

        Harness iczHarness = Harness.create(List.of());
        IczIceSpikesObjectInstance unmanagedSpike = new IczIceSpikesObjectInstance(ICZ_SPIKE_A);
        unmanagedSpike.setServices(iczHarness.services());
        ObjectInstance hurtChild = iczHarness.objectManager().createDynamicObject(
                () -> constructIczHurtChild(unmanagedSpike, ICZ_SPIKE_A.x(), ICZ_SPIKE_A.y() + 0x0C));
        assertSame(unmanagedSpike, readObjectField(hurtChild, "parent"),
                "precondition: ICZ hurt child parent is outside ObjectManager identity registration");

        IllegalStateException iczThrown = assertThrows(
                IllegalStateException.class, registryFor(iczHarness.objectManager())::capture);
        assertTrue(iczThrown.getMessage().contains("no registered id for object reference"),
                "missing ICZ parent identity must fail loudly");

        Harness columnHarness = Harness.create(List.of());
        IczCrushingColumnObjectInstance unmanagedColumn =
                new IczCrushingColumnObjectInstance(ICZ_CRUSHING_COLUMN);
        unmanagedColumn.setServices(columnHarness.services());
        ObjectInstance decoration = columnHarness.objectManager().createDynamicObject(
                () -> constructIczCrushingColumnDecoration(unmanagedColumn));
        assertSame(unmanagedColumn, readObjectField(decoration, "parent"),
                "precondition: ICZ crushing-column decoration parent is outside ObjectManager identity registration");

        IllegalStateException columnThrown = assertThrows(
                IllegalStateException.class, registryFor(columnHarness.objectManager())::capture);
        assertTrue(columnThrown.getMessage().contains("no registered id for object reference"),
                "missing ICZ crushing-column decoration parent identity must fail loudly");
    }

    private record Harness(ObjectManager objectManager, ObjectServices services) {
        static Harness create(List<ObjectSpawn> spawns) {
            ObjectManager[] holder = new ObjectManager[1];
            int cameraX = initialCameraX(spawns);
            Camera camera = mockCamera(cameraX);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
                @Override public GraphicsManager graphicsManager() { return GraphicsManager.getInstance(); }
            };
            ObjectManager objectManager = new ObjectManager(
                    spawns,
                    new Sonic3kObjectRegistry(),
                    0,
                    null,
                    null,
                    GraphicsManager.getInstance(),
                    camera,
                    services);
            holder[0] = objectManager;
            objectManager.reset(cameraX);
            return new Harness(objectManager, services);
        }
    }

    private static RewindRegistry registryFor(ObjectManager objectManager) {
        RewindRegistry rewindRegistry = new RewindRegistry();
        rewindRegistry.register(objectManager.rewindSnapshottable());
        return rewindRegistry;
    }

    private static ObjectRefId objectId(ObjectManager objectManager, ObjectInstance object) {
        RewindIdentityTable table = objectManager.captureIdentityContext().requireIdentityTable();
        ObjectRefId id = table.idFor(object);
        assertNotNull(id, "ObjectManager capture identity table must register " + object.getClass());
        return id;
    }

    private static <T extends ObjectInstance> T objectById(
            ObjectManager objectManager, Class<T> type, ObjectRefId id) {
        return liveObjects(objectManager, type).stream()
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static ObjectInstance objectById(ObjectManager objectManager, ObjectRefId id) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> !object.isDestroyed())
                .filter(object -> objectId(objectManager, object).equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing restored object " + id));
    }

    private static <T extends ObjectInstance> T only(ObjectManager objectManager, Class<T> type) {
        List<T> live = liveObjects(objectManager, type);
        assertEquals(1, live.size(), "expected exactly one live " + type.getSimpleName());
        return live.getFirst();
    }

    private static <T extends ObjectInstance> List<T> liveObjects(ObjectManager objectManager, Class<T> type) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass() == type && !object.isDestroyed())
                .map(type::cast)
                .sorted(Comparator.comparingInt(TestS3kNestedHurtboxGraphRewind::slotIndex))
                .toList();
    }

    private static List<ObjectInstance> liveObjectsByName(ObjectManager objectManager, String className) {
        return objectManager.getActiveObjects().stream()
                .filter(object -> object.getClass().getName().equals(className) && !object.isDestroyed())
                .sorted(Comparator.comparingInt(TestS3kNestedHurtboxGraphRewind::slotIndex))
                .toList();
    }

    private static int slotIndex(ObjectInstance object) {
        return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
    }

    private static ObjectInstance armWithXOffset(List<ObjectInstance> arms, int xOffset) {
        List<ObjectInstance> matches = arms.stream()
                .filter(arm -> readIntField(arm, "xOffset") == xOffset)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one MGZ drill arm xOffset " + xOffset);
        return matches.getFirst();
    }

    private static ObjectInstance childWithParent(List<ObjectInstance> children, Object parent) {
        return children.stream()
                .filter(child -> readObjectField(child, "parent") == parent)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing child for parent " + parent));
    }

    private static ObjectInstance childAtX(List<ObjectInstance> children, int x) {
        List<ObjectInstance> matches = children.stream()
                .filter(child -> child.getX() == x)
                .toList();
        assertEquals(1, matches.size(), "expected exactly one child at x=" + x);
        return matches.getFirst();
    }

    private static ObjectInstance constructMgzArm(MgzMinibossInstance parent, int xOffset, int yOffset) {
        try {
            Class<?> cls = Class.forName(MGZ_DRILL_ARM_CLASS);
            Constructor<?> ctor = cls.getDeclaredConstructor(MgzMinibossInstance.class, int.class, int.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(parent, xOffset, yOffset);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct MGZ drill arm", e);
        }
    }

    private static ObjectInstance constructMgzCeilingDebris(int x, int y, int mappingFrame, boolean spire) {
        try {
            Class<?> cls = Class.forName(MGZ_CEILING_DEBRIS_CLASS);
            Constructor<?> ctor = cls.getDeclaredConstructor(int.class, int.class, int.class, boolean.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(x, y, mappingFrame, spire);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct MGZ ceiling debris", e);
        }
    }

    private static ObjectInstance constructMgzDefeatFragment(
            int x, int y, int mappingFrame, int xVel, int yVel) {
        try {
            Class<?> cls = Class.forName(MGZ_DEFEAT_FRAGMENT_CLASS);
            Constructor<?> ctor =
                    cls.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(x, y, mappingFrame, xVel, yVel);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct MGZ defeat fragment", e);
        }
    }

    private static ObjectInstance constructMgzSpikePlatform(
            MgzMinibossInstance parent, boolean mirrored, int cameraX, int cameraY) {
        try {
            Class<?> cls = Class.forName(MGZ_SPIKE_PLATFORM_CLASS);
            Constructor<?> ctor =
                    cls.getDeclaredConstructor(MgzMinibossInstance.class, boolean.class, int.class, int.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(parent, mirrored, cameraX, cameraY);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct MGZ spike platform", e);
        }
    }

    private static ObjectInstance constructMgzCameraScrollHelper(int targetX) {
        try {
            Class<?> cls = Class.forName(MGZ_CAMERA_SCROLL_HELPER_CLASS);
            Constructor<?> ctor = cls.getDeclaredConstructor(int.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(targetX);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct MGZ camera scroll helper", e);
        }
    }

    private static ObjectInstance constructHczVortexBubble(
            int x, int y, int frame, int vortexX, int vortexY) {
        try {
            Class<?> cls = Class.forName(HCZ_VORTEX_BUBBLE_CLASS);
            Constructor<?> ctor =
                    cls.getDeclaredConstructor(int.class, int.class, int.class, int.class, int.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(x, y, frame, vortexX, vortexY);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct HCZ vortex bubble", e);
        }
    }

    private static ObjectInstance constructIczHurtChild(
            IczIceSpikesObjectInstance parent, int x, int y) {
        try {
            Class<?> cls = Class.forName(ICZ_HURT_CHILD_CLASS);
            Constructor<?> ctor =
                    cls.getDeclaredConstructor(IczIceSpikesObjectInstance.class, int.class, int.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(parent, x, y);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct ICZ hurt child", e);
        }
    }

    private static ObjectInstance constructIczTensionSupport(
            IczTensionPlatformObjectInstance parent, int x, int y, boolean hFlip) {
        try {
            Class<?> cls = Class.forName(ICZ_TENSION_SUPPORT_CLASS);
            Constructor<?> ctor = cls.getDeclaredConstructor(
                    IczTensionPlatformObjectInstance.class, int.class, int.class, boolean.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(parent, x, y, hFlip);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct ICZ tension support", e);
        }
    }

    private static ObjectInstance constructIczCrushingColumnDecoration(
            IczCrushingColumnObjectInstance parent) {
        try {
            Class<?> cls = Class.forName(ICZ_CRUSHING_COLUMN_DECORATION_CLASS);
            Constructor<?> ctor = cls.getDeclaredConstructor(IczCrushingColumnObjectInstance.class);
            ctor.setAccessible(true);
            return (ObjectInstance) ctor.newInstance(parent);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to construct ICZ crushing-column decoration", e);
        }
    }

    private static void invokeNoArg(Object target, String methodName) {
        try {
            Method method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to invoke " + methodName + " on " + target.getClass(), e);
        }
    }

    private static int readIntField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static boolean readBooleanField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getBoolean(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static short readShortField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).getShort(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static Object readObjectField(Object target, String fieldName) {
        try {
            return findField(target.getClass(), fieldName).get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + fieldName + " from " + target.getClass(), e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            findField(target.getClass(), fieldName).setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            findField(target.getClass(), fieldName).setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static void setShortField(Object target, String fieldName, short value) {
        try {
            findField(target.getClass(), fieldName).setShort(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to write " + fieldName + " on " + target.getClass(), e);
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static int initialCameraX(List<ObjectSpawn> spawns) {
        return spawns.stream().mapToInt(ObjectSpawn::x).min().orElse(0) & 0xFF80;
    }

    private static Camera mockCamera(int cameraX) {
        return new Camera() {
            @Override public short getX() { return (short) cameraX; }
            @Override public short getY() { return 0; }
            @Override public short getWidth() { return 0x800; }
            @Override public short getHeight() { return 0x700; }
            @Override public boolean isVerticalWrapEnabled() { return false; }
        };
    }
}
