package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.GameStateManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDragonflyBadnikInstance {

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void registryCreatesDragonflyForSklSlot8eInMhz() {
        Sonic3kObjectRegistry registry = new MhzRegistry();

        ObjectInstance instance = registry.create(new ObjectSpawn(0x120, 0x100,
                Sonic3kObjectIds.DRAGONFLY, 0, 0, false, 0));

        assertInstanceOf(DragonflyBadnikInstance.class, instance);
    }

    @Test
    void firstPatrolFrameAppliesRomSwingBeforeMoveSprite2() {
        DragonflyBadnikInstance dragonfly = dragonfly();

        putDragonflyOnScreen();
        activateDragonfly(dragonfly);
        dragonfly.update(2, player());

        assertEquals(3, dragonfly.getMappingFrame(),
                "Obj_Dragonfly runs Animate_Raw over byte_8DFCE before Swing_LeftAndRight/Swing_UpAndDown");
        assertEquals(0x121, dragonfly.getX());
        assertEquals(0x101, dragonfly.getY());
        assertEquals(0x1E0, dragonfly.getXVelocity());
        assertEquals(0x1F8, dragonfly.getYVelocity());
    }

    @Test
    void verticalSwingReachesHoverWaitWhenYVelocityReturnsToZero() {
        DragonflyBadnikInstance dragonfly = dragonfly();

        putDragonflyOnScreen();
        activateDragonfly(dragonfly);
        for (int frame = 2; frame < 66; frame++) {
            dragonfly.update(frame, player());
        }

        assertEquals("WAITING", dragonfly.getStateName());
        assertEquals(0x0F, dragonfly.getWaitTimer());
        assertEquals(0, dragonfly.getYVelocity());
    }

    @Test
    void firstHoverExitKeepsRomBodyAnimationScriptBecauseBchgTestsOldBit() {
        DragonflyBadnikInstance dragonfly = dragonfly();
        TestablePlayableSprite player = player();

        putDragonflyOnScreen();
        activateDragonfly(dragonfly);

        int frame = 2;
        while (frame < 100 && !"WAITING".equals(dragonfly.getStateName())) {
            dragonfly.update(frame++, player);
        }
        assertEquals("WAITING", dragonfly.getStateName());

        while (frame < 130 && "WAITING".equals(dragonfly.getStateName())) {
            dragonfly.update(frame++, player);
        }
        assertEquals("PATROLLING", dragonfly.getStateName());

        dragonfly.update(frame, player);

        assertEquals(3, dragonfly.getMappingFrame(),
                "loc_8DDF8 does bchg #1,$38 then bne on the old bit value; "
                        + "with the initial old bit clear, it keeps byte_8DFCE for the first post-hover patrol");
    }

    @Test
    void wingFlapSequenceOverFiveHoverCyclesAlternatesUpDownOnToggledBit() {
        DragonflyBadnikInstance dragonfly = dragonfly();
        TestablePlayableSprite player = player();

        putDragonflyOnScreen();
        activateDragonfly(dragonfly);

        List<Boolean> downFlagsAfterEachHoverExit = new ArrayList<>();
        int frame = 2;
        boolean wasWaiting = false;
        while (downFlagsAfterEachHoverExit.size() < 5 && frame < 5000) {
            dragonfly.update(frame, player);
            boolean isWaiting = "WAITING".equals(dragonfly.getStateName());
            if (wasWaiting && !isWaiting) {
                downFlagsAfterEachHoverExit.add(dragonfly.isUsingDownBodyAnimation());
            }
            wasWaiting = isWaiting;
            frame++;
        }

        assertEquals(List.of(false, true, false, true, false), downFlagsAfterEachHoverExit,
                "loc_8DDF8 does bchg #1,$38 then selects on the toggled bit, so five successive "
                        + "hover-wait exits alternate byte_8DFCE/byte_8DFC2 as UP,DOWN,UP,DOWN,UP "
                        + "-- not a doubled UP,UP,DOWN,... first cycle");
    }

    @Test
    void objWaitOffscreenSuppressesPatrolAndCollisionUntilSetupRuns() {
        DragonflyBadnikInstance dragonfly = dragonfly();

        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 0xC0, 0);
        dragonfly.update(0, player());

        assertEquals(0x120, dragonfly.getX());
        assertEquals(0x100, dragonfly.getY());
        assertEquals(0, dragonfly.getCollisionFlags());
        assertEquals(0, dragonfly.getXVelocity(),
                "Obj_WaitOffscreen returns before loc_8DD52 seeds x_vel");
        assertEquals(0, dragonfly.getYVelocity(),
                "Obj_WaitOffscreen returns before loc_8DD52 seeds y_vel");

        putDragonflyOnScreen();
        dragonfly.update(1, player());

        assertEquals(0x120, dragonfly.getX());
        assertEquals(0x100, dragonfly.getY());
        assertEquals(0, dragonfly.getCollisionFlags());

        dragonfly.update(2, player());

        assertEquals(0x120, dragonfly.getX());
        assertEquals(0x100, dragonfly.getY());
        assertEquals(0x17, dragonfly.getCollisionFlags());
        assertEquals(0x200, dragonfly.getXVelocity(),
                "loc_8DD52 seeds x_vel during setup, before the first patrol frame");
        assertEquals(0x200, dragonfly.getYVelocity(),
                "loc_8DD52 seeds y_vel during setup, before the first patrol frame");

        dragonfly.update(3, player());

        assertEquals(0x121, dragonfly.getX());
        assertEquals(0x101, dragonfly.getY());
    }

    @Test
    void setupCreatesRomWingAndSevenLinkedBodyChildren() {
        SpawnHarness harness = new SpawnHarness();
        DragonflyBadnikInstance dragonfly = dragonfly(harness.services);

        putDragonflyOnScreen();
        dragonfly.update(0, player());
        assertEquals(0, harness.spawned.size(),
                "Obj_WaitOffscreen returns before loc_8DD52 setup");

        dragonfly.update(1, player());

        assertEquals(8, harness.spawned.size(),
                "loc_8DD52 creates one loc_8DF3C child and seven loc_8DE26 linked children");
    }

    @Test
    void linkedBodySegmentsFollowPreviousLinkYInsteadOfDragonflyParentY() {
        SpawnHarness harness = new SpawnHarness();
        DragonflyBadnikInstance dragonfly = dragonfly(harness.services);
        TestablePlayableSprite player = player();

        putDragonflyOnScreen();
        dragonfly.update(0, player);
        dragonfly.update(1, player);

        List<DragonflyBadnikInstance.LinkedBodyChild> segments = harness.spawned.stream()
                .filter(DragonflyBadnikInstance.LinkedBodyChild.class::isInstance)
                .map(DragonflyBadnikInstance.LinkedBodyChild.class::cast)
                .toList();
        assertEquals(7, segments.size(),
                "CreateChild4_LinkListRepeated allocates seven linked body children");

        segments.get(0).update(2, player);
        segments.get(1).update(2, player);
        segments.get(0).update(3, player);
        segments.get(1).update(3, player);

        assertEquals(dragonfly.getY() - 0x0C, segments.get(0).getY(),
                "first linked segment follows the Dragonfly parent by byte_8DF78[0]");
        assertEquals(segments.get(0).getY() - 5, segments.get(1).getY(),
                "CreateChild4_LinkListRepeated stores the previous object in parent3(a0), "
                        + "so later body links apply byte_8DF78 to the preceding link");
    }

    @Test
    void sevenSegmentTailEntersReturnOneFrameApartDownTheChainNotAllAtOnce() {
        SpawnHarness harness = new SpawnHarness();
        DragonflyBadnikInstance dragonfly = dragonfly(harness.services);
        TestablePlayableSprite player = player();

        putDragonflyOnScreen();
        dragonfly.update(0, player);
        dragonfly.update(1, player);

        List<DragonflyBadnikInstance.LinkedBodyChild> segments = harness.spawned.stream()
                .filter(DragonflyBadnikInstance.LinkedBodyChild.class::isInstance)
                .map(DragonflyBadnikInstance.LinkedBodyChild.class::cast)
                .toList();
        assertEquals(7, segments.size());

        Integer[] returnEntryFrame = new Integer[segments.size()];
        boolean[] wasReturning = new boolean[segments.size()];

        int frame = 2;
        while (frame < 500 && (!"WAITING".equals(dragonfly.getStateName())
                || returnEntryFrame[segments.size() - 1] == null)) {
            dragonfly.update(frame, player);
            for (int i = 0; i < segments.size(); i++) {
                segments.get(i).update(frame, player);
                boolean isReturning = segments.get(i).isReturningToParentY();
                if (isReturning && !wasReturning[i] && returnEntryFrame[i] == null) {
                    returnEntryFrame[i] = frame;
                }
                wasReturning[i] = isReturning;
            }
            frame++;
        }

        for (int i = 0; i < segments.size(); i++) {
            assertNotNull(returnEntryFrame[i], "segment " + i + " never entered the return phase");
        }
        for (int i = 1; i < segments.size(); i++) {
            assertEquals(returnEntryFrame[i - 1] + 1, (int) returnEntryFrame[i],
                    "loc_8DE8A gates segment " + i + "'s return on btst #2,$38 of segment " + (i - 1)
                            + ", a 1-frame ripple down the chain, not a simultaneous transition");
        }
    }

    @Test
    void wingChildRunsRomRawAnimationScriptAfterSetupFrame() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.DRAGONFLY)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        DragonflyBadnikInstance dragonfly = dragonfly();
        DragonflyBadnikInstance.WingChild wing = new DragonflyBadnikInstance.WingChild(dragonfly);
        wing.setServices(new TestObjectServices().withLevelManager(levelManager));

        wing.update(0, player());
        wing.update(1, player());
        wing.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndexForcedPriority(9, 0x120, 0x100, false, false, -1, true);
    }

    @Test
    void wingChildUsesRomPriorityBucketFromObjData() {
        DragonflyBadnikInstance dragonfly = dragonfly();
        DragonflyBadnikInstance.WingChild wing = new DragonflyBadnikInstance.WingChild(dragonfly);

        assertEquals(5, wing.getPriorityBucket(),
                "word_8DFA2 priority word $280 maps the Dragonfly wing child to render bucket 5");
    }

    @Test
    void usesRomRenderBoundsFromObjData() {
        DragonflyBadnikInstance dragonfly = dragonfly();
        DragonflyBadnikInstance.WingChild wing = new DragonflyBadnikInstance.WingChild(dragonfly);
        DragonflyBadnikInstance.LinkedBodyChild segment =
                new DragonflyBadnikInstance.LinkedBodyChild(dragonfly, 0, 0);

        assertEquals(0x08, dragonfly.getOnScreenHalfWidth(),
                "ObjDat_Dragonfly width_pixels byte is $08");
        assertEquals(0x08, dragonfly.getOnScreenHalfHeight(),
                "ObjDat_Dragonfly height_pixels byte is $08");
        assertEquals(0x20, wing.getOnScreenHalfWidth(),
                "word_8DFA2 wing width_pixels byte is $20");
        assertEquals(0x08, wing.getOnScreenHalfHeight(),
                "word_8DFA2 wing height_pixels byte is $08");
        assertEquals(0x04, segment.getOnScreenHalfWidth(),
                "word_8DFA8 linked body width_pixels byte is $04");
        assertEquals(0x04, segment.getOnScreenHalfHeight(),
                "word_8DFA8 linked body height_pixels byte is $04");
    }

    @Test
    void linkedChildMovesHorizontallyAfterSubtypeCountdown() {
        DragonflyBadnikInstance dragonfly = dragonfly();
        DragonflyBadnikInstance.LinkedBodyChild segment =
                new DragonflyBadnikInstance.LinkedBodyChild(dragonfly, 0, 0);

        segment.update(0, player());
        segment.update(1, player());
        segment.update(2, player());

        assertEquals(0x121, segment.getX(),
                "loc_8DE74 counts subtype 0 down, then loc_8DE8A applies Swing_LeftAndRight/MoveSprite2");
        assertEquals(0x0F4, segment.getY(),
                "sub_8DF80 keeps the child at parent y plus byte_8DF78[0] while it starts horizontal swing");
    }

    @Test
    void linkedChildSetupFrameKeepsCreateChild4ParentPositionBeforeSub8df80Runs() {
        DragonflyBadnikInstance dragonfly = dragonfly();
        DragonflyBadnikInstance.LinkedBodyChild segment =
                new DragonflyBadnikInstance.LinkedBodyChild(dragonfly, 0, 0);

        assertEquals(0x120, segment.getX());
        assertEquals(0x100, segment.getY(),
                "CreateChild4_LinkListRepeated copies the parent x_pos/y_pos without applying byte_8DF78");

        segment.update(0, player());

        assertEquals(0x120, segment.getX());
        assertEquals(0x100, segment.getY(),
                "loc_8DE44 only seeds child_dy; sub_8DF80 first applies the vertical offset in loc_8DE74");
    }

    @Test
    void linkedBodyChildExposesRomHurtCollisionFlags() {
        DragonflyBadnikInstance dragonfly = dragonfly();
        DragonflyBadnikInstance.LinkedBodyChild segment =
                new DragonflyBadnikInstance.LinkedBodyChild(dragonfly, 0, 0);

        TouchResponseProvider provider = assertInstanceOf(TouchResponseProvider.class, segment,
                "loc_8DE26 ends with Child_DrawTouch_Sprite, so linked body segments must participate in touch");

        assertEquals(0x98, provider.getCollisionFlags(),
                "word_8DFA8 stores collision_flags=$98 for Dragonfly linked body children");
        assertEquals(0, provider.getCollisionProperty());
    }

    @Test
    void childOutOfRangeReferenceUsesParentAnchor() {
        DragonflyBadnikInstance dragonfly = dragonfly();
        putDragonflyOnScreen();
        activateDragonfly(dragonfly);
        DragonflyBadnikInstance.WingChild wing = new DragonflyBadnikInstance.WingChild(dragonfly);
        DragonflyBadnikInstance.LinkedBodyChild segment =
                new DragonflyBadnikInstance.LinkedBodyChild(dragonfly, 0, 0);

        for (int frame = 2; frame < 20; frame++) {
            dragonfly.update(frame, player());
            segment.update(frame, player());
        }

        assertEquals(dragonfly.getX(), wing.getOutOfRangeReferenceX(),
                "Dragonfly child sprites unload with the parent slot, not with their own shifted X");
        assertEquals(dragonfly.getX(), segment.getOutOfRangeReferenceX(),
                "linked body children must use the parent x_pos anchor for MarkObjGone-style range checks");
    }

    @Test
    void linkedBodyChildStartsWithRomVerticalRenderFlipSet() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.DRAGONFLY)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        DragonflyBadnikInstance dragonfly = dragonfly();
        DragonflyBadnikInstance.LinkedBodyChild segment =
                new DragonflyBadnikInstance.LinkedBodyChild(dragonfly, 0, 0);
        segment.setServices(new TestObjectServices().withLevelManager(levelManager));

        segment.update(0, player());
        segment.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndexForcedPriority(
                5, 0x120, 0x100, false, true, -1, true);
    }

    @Test
    void linkedChildStartsVerticalReturnWhenParentEntersHoverWait() {
        DragonflyBadnikInstance dragonfly = dragonfly();
        putDragonflyOnScreen();
        activateDragonfly(dragonfly);
        DragonflyBadnikInstance.LinkedBodyChild segment =
                new DragonflyBadnikInstance.LinkedBodyChild(dragonfly, 0, 0);
        TestablePlayableSprite player = player();

        for (int frame = 2; frame < 66; frame++) {
            dragonfly.update(frame, player);
            segment.update(frame, player);
        }

        assertEquals("WAITING", dragonfly.getStateName());
        assertEquals(dragonfly.getY() - 0x0C, segment.getY(),
                "loc_8DE8A still applies sub_8DF80 on the frame it observes parent $38 bit 2");

        segment.update(66, player);

        assertEquals(dragonfly.getY() - 0x0B, segment.getY(),
                "loc_8DEB6 moves child y_pos by child_dx after parent hover requests the body flip");
    }

    @Test
    void linkedChildTogglesVerticalRenderFlipAfterCompletingBodyFlipCycle() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.DRAGONFLY)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        DragonflyBadnikInstance dragonfly = dragonfly();
        putDragonflyOnScreen();
        activateDragonfly(dragonfly);
        DragonflyBadnikInstance.LinkedBodyChild segment =
                new DragonflyBadnikInstance.LinkedBodyChild(dragonfly, 0, 0);
        segment.setServices(new TestObjectServices().withLevelManager(levelManager));
        TestablePlayableSprite player = player();

        for (int frame = 2; frame < 1000; frame++) {
            dragonfly.update(frame, player);
            segment.update(frame, player);
            segment.appendRenderCommands(new ArrayList<>());
        }

        verify(renderer, atLeastOnce()).drawFrameIndexForcedPriority(
                eq(5), anyInt(), anyInt(), eq(false), eq(true), eq(-1), eq(true));
    }

    @Test
    void rendersWithRomArtTilePriorityBit() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.DRAGONFLY)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        DragonflyBadnikInstance dragonfly = new DragonflyBadnikInstance(new ObjectSpawn(
                0x120, 0x100, Sonic3kObjectIds.DRAGONFLY, 0, 0, false, 0));
        dragonfly.setServices(new TestObjectServices().withLevelManager(levelManager));
        putDragonflyOnScreen();
        activateDragonfly(dragonfly);

        dragonfly.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndexForcedPriority(4, 0x120, 0x100, false, false, -1, true);
    }

    private static DragonflyBadnikInstance dragonfly() {
        return dragonfly(new TestObjectServices().withGameState(mock(GameStateManager.class)));
    }

    private static DragonflyBadnikInstance dragonfly(TestObjectServices services) {
        DragonflyBadnikInstance dragonfly = new DragonflyBadnikInstance(new ObjectSpawn(
                0x120, 0x100, Sonic3kObjectIds.DRAGONFLY, 0, 0, false, 0));
        dragonfly.setServices(services);
        return dragonfly;
    }

    private static void activateDragonfly(DragonflyBadnikInstance dragonfly) {
        dragonfly.update(0, player());
        dragonfly.update(1, player());
    }

    private static void putDragonflyOnScreen() {
        AbstractObjectInstance.updateCameraBounds(0x80, 0x80, 0x1C0, 0x160, 0);
    }

    private static TestablePlayableSprite player() {
        return new TestablePlayableSprite("sonic", (short) 0x100, (short) 0x100);
    }

    private static final class MhzRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_MHZ;
        }
    }

    private static final class SpawnHarness {
        private final List<ObjectInstance> spawned = new ArrayList<>();
        private final TestObjectServices services;

        private SpawnHarness() {
            ObjectManager objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                spawned.add(invocation.getArgument(0));
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));

            LevelManager levelManager = mock(LevelManager.class);
            when(levelManager.getObjectManager()).thenReturn(objectManager);
            services = new TestObjectServices()
                    .withGameState(mock(GameStateManager.class))
                    .withLevelManager(levelManager);
        }
    }
}
