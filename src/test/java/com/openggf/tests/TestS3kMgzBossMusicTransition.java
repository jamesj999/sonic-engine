package com.openggf.tests;

import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.MgzDrillingRobotnikInstance;
import com.openggf.game.sonic3k.objects.MgzEndBossInstance;
import com.openggf.game.sonic3k.objects.MgzEndBossRenderChild;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS3kMgzBossMusicTransition {

    @Test
    void drillingRobotnikFadesZoneMusicBeforeDelayedBossMusic() {
        RecordingServices services = new RecordingServices();
        MgzDrillingRobotnikInstance robotnik = new MgzDrillingRobotnikInstance(
                new ObjectSpawn(0x08E0, 0x0690, 0, 0, 0, false, 0),
                false);
        robotnik.setServices(services);

        robotnik.update(0, null);

        assertEquals(1, services.fadeOutCount,
                "MGZ2 drilling Robotnik should issue the ROM init-time music fade-out");
        assertTrue(services.playedMusic.isEmpty(),
                "Boss music should wait for the ROM 2-second Obj_Wait delay");
        assertEquals(List.of("fade"), services.musicEvents,
                "The init SST pass must fade zone music without starting boss music");

        for (int frame = 1; frame <= 120; frame++) {
            robotnik.update(frame, null);
        }

        assertEquals(1, services.fadeOutCount,
                "Obj_Wait must not restart the init-time fade while its timer counts down");
        assertTrue(services.playedMusic.isEmpty(),
                "All 120 timer decrements must complete before Obj_Wait's signed-underflow callback");

        robotnik.update(121, null);

        assertEquals(List.of(Sonic3kMusic.BOSS.id), services.playedMusic,
                "Boss music should start when Obj_Wait underflows after the ROM 120-frame delay");
        assertEquals(List.of("fade", "music:" + Sonic3kMusic.BOSS.id), services.musicEvents,
                "The boss theme must follow the one-time zone-music fade");

        robotnik.update(122, null);

        assertEquals(1, services.fadeOutCount, "The init-time fade must be issued exactly once");
        assertEquals(List.of(Sonic3kMusic.BOSS.id), services.playedMusic,
                "The Obj_Wait callback must start boss music exactly once");
    }

    @Test
    void endBossFadesZoneMusicBeforeDelayedBossMusic() {
        RecordingServices services = new RecordingServices();
        MgzEndBossInstance boss = new MgzEndBossInstance(
                new ObjectSpawn(0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));
        boss.setServices(services);

        boss.update(0, null);

        assertEquals(1, services.fadeOutCount,
                "MGZ2 end boss should issue the ROM init-time music fade-out");
        assertTrue(services.playedMusic.isEmpty(),
                "End-boss music should wait for the ROM 2-second Obj_Wait delay");

        for (int frame = 1; frame < 120; frame++) {
            boss.update(frame, null);
        }

        assertEquals(List.of(Sonic3kMusic.BOSS.id), services.playedMusic,
                "End-boss music should start after the ROM 120-frame wait");
    }

    @Test
    void endBossRewindCaptureAcceptsBaseBossCustomMemory() {
        MgzEndBossInstance boss = new MgzEndBossInstance(
                new ObjectSpawn(0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));

        assertDoesNotThrow(() -> boss.captureRewindState(),
                "MGZ2 end-boss rewind capture must preserve AbstractBossInstance customMemory");
    }

    @Test
    void endBossDrawsDrillPieceBehindMainBodyWhenItAppears() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer drillRenderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer shipRenderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MGZ_ENDBOSS)).thenReturn(drillRenderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(shipRenderer);
        when(drillRenderer.isReady()).thenReturn(true);
        when(shipRenderer.isReady()).thenReturn(true);

        RecordingServices services = new RecordingServices(renderManager, true);
        MgzEndBossInstance boss = new MgzEndBossInstance(
                new ObjectSpawn(0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));
        boss.setServices(services);

        for (int frame = 0; frame < 120; frame++) {
            boss.update(frame, null);
        }

        List<MgzEndBossRenderChild> renderChildren = boss.getChildComponents().stream()
                .filter(MgzEndBossRenderChild.class::isInstance)
                .map(MgzEndBossRenderChild.class::cast)
                .toList();
        assertEquals(8, renderChildren.size(),
                "ChildObjDat_6D7C0 and its nested children must produce the complete eight-role render graph");
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), renderChildren.stream()
                        .map(MgzEndBossRenderChild::role)
                        .sorted()
                        .toList(),
                "every composite render role must occur exactly once");
        List<MgzEndBossRenderChild> managedRenderChildren = services.objectManager().getActiveObjects().stream()
                .filter(MgzEndBossRenderChild.class::isInstance)
                .map(MgzEndBossRenderChild.class::cast)
                .toList();
        assertEquals(8, managedRenderChildren.size(),
                "ObjectManager must own exactly the complete eight-role render graph");
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), managedRenderChildren.stream()
                        .map(MgzEndBossRenderChild::role)
                        .sorted()
                        .toList(),
                "ObjectManager must own each composite render role exactly once");
        for (MgzEndBossRenderChild child : renderChildren) {
            assertTrue(child.getSlotIndex() >= 0, "every render child must own a real SST slot");
            assertTrue(managedRenderChildren.stream().anyMatch(managed -> managed == child),
                    "every boss childComponents entry must be the exact identity owned by ObjectManager");
        }

        MgzEndBossRenderChild rearDrill = managedRenderChildren.stream()
                .filter(child -> child.role() == MgzEndBossRenderChild.ROLE_STATIC_BACK)
                .findFirst()
                .orElseThrow();
        assertSame(renderChildren.stream()
                        .filter(child -> child.role() == MgzEndBossRenderChild.ROLE_STATIC_BACK)
                        .findFirst()
                        .orElseThrow(),
                rearDrill,
                "the rendered rear drill must be the same managed instance retained by childComponents");
        assertEquals(7, rearDrill.getPriorityBucket(),
                "word_6D77C gives the rear drill child ROM priority $380");
        assertFalse(rearDrill.isHighPriority(),
                "loc_6C962 clears the managed rear-drill SST's art_tile priority bit for Obj_MGZEndBoss");
        assertEquals(6, boss.getPriorityBucket(),
                "ObjDat_MGZDrillBoss gives the parent body ROM priority $300");
        assertTrue(boss.isHighPriority(),
                "loc_6C354 sets the parent body art_tile priority bit independently of its sprite bucket");

        List<ObjectInstance> managedPair = new ArrayList<>(List.of(boss, rearDrill));
        managedPair.sort(TestS3kMgzBossMusicTransition::compareRuntimeRenderOrder);
        assertEquals(List.of(rearDrill, boss), managedPair,
                "the independent rear-drill SST must enter the painter before the parent body");
        managedPair.forEach(instance -> instance.appendRenderCommands(new ArrayList<>()));

        InOrder order = inOrder(drillRenderer);
        order.verify(drillRenderer).drawFrameIndex(1, 0x3D0C, 0x0677, false, false);
        order.verify(drillRenderer).drawFrameIndex(0, 0x3D20, 0x0668, false, false);
    }

    @Test
    void endBossThrusterFlameTouchUsesGameplayFrameWithoutRenderPass() {
        MgzEndBossInstance boss = new MgzEndBossInstance(
                new ObjectSpawn(0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));
        boss.setServices(new RecordingServices());

        updateThroughFrame(boss, 120);

        TouchResponseProvider.TouchRegion[] regions = boss.getMultiTouchRegions();
        assertEquals(0x9A, regions[2].collisionFlags(),
                "ROM loc_6CF62 touches the first thruster flame when V_int_run_count bit 0 is clear");
        assertEquals(0x9A, regions[3].collisionFlags(),
                "ROM loc_6CF62 touches the second thruster flame when V_int_run_count bit 0 is clear");

        boss.update(121, null);
        regions = boss.getMultiTouchRegions();
        assertEquals(0, regions[2].collisionFlags(),
                "ROM loc_6CF62 skips first thruster flame touch when V_int_run_count bit 0 is set");
        assertEquals(0, regions[3].collisionFlags(),
                "ROM loc_6CF62 skips second thruster flame touch when V_int_run_count bit 0 is set");
    }

    @Test
    void endBossTouchProfileExposesMultiRegionDispatch() {
        MgzEndBossInstance boss = new MgzEndBossInstance(
                new ObjectSpawn(0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));
        boss.setServices(new RecordingServices());

        updateThroughFrame(boss, 120);

        assertTrue(boss.getTouchResponseProfile().multiRegionSource(),
                "MGZ boss profile should advertise its body, drill, and flame regions to the dispatcher");
        assertEquals(TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY,
                boss.getTouchResponseProfile().stopAfterFirstOverlapPolicy());
    }

    @Test
    void endBossThrusterFlameDrawUsesGameplayFrameWithoutPriorRenderPass() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer drillRenderer = mock(PatternSpriteRenderer.class);
        PatternSpriteRenderer shipRenderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MGZ_ENDBOSS)).thenReturn(drillRenderer);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP)).thenReturn(shipRenderer);
        when(drillRenderer.isReady()).thenReturn(true);
        when(shipRenderer.isReady()).thenReturn(true);

        MgzEndBossInstance boss = new MgzEndBossInstance(
                new ObjectSpawn(0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0));
        boss.setServices(new RecordingServices(renderManager));

        updateThroughFrame(boss, 121);
        boss.appendRenderCommands(new ArrayList<>());

        verify(drillRenderer, never()).drawFrameIndex(
                anyInt(), anyInt(), anyInt(), anyBoolean(), anyBoolean(), eq(0));
    }

    private static void updateThroughFrame(MgzEndBossInstance boss, int frame) {
        for (int i = 0; i <= frame; i++) {
            boss.update(i, null);
        }
    }

    private static int compareRuntimeRenderOrder(ObjectInstance left, ObjectInstance right) {
        int bucketOrder = Integer.compare(
                RenderPriority.clamp(right.getPriorityBucket()),
                RenderPriority.clamp(left.getPriorityBucket()));
        if (bucketOrder != 0) {
            return bucketOrder;
        }
        int tilePriorityOrder = Boolean.compare(left.isHighPriority(), right.isHighPriority());
        if (tilePriorityOrder != 0) {
            return tilePriorityOrder;
        }
        return Integer.compare(renderSlot(right), renderSlot(left));
    }

    private static int renderSlot(ObjectInstance instance) {
        return instance instanceof AbstractObjectInstance object ? object.getSlotIndex() : Integer.MAX_VALUE;
    }

    private static final class RecordingServices extends StubObjectServices {
        private final ObjectRenderManager renderManager;
        private final ObjectManager objectManager;
        private int fadeOutCount;
        private final List<Integer> playedMusic = new ArrayList<>();
        private final List<String> musicEvents = new ArrayList<>();

        private RecordingServices() {
            this(null);
        }

        private RecordingServices(ObjectRenderManager renderManager) {
            this(renderManager, false);
        }

        private RecordingServices(ObjectRenderManager renderManager, boolean managerBacked) {
            this.renderManager = renderManager;
            this.objectManager = managerBacked
                    ? new ObjectManager(List.of(), null, 0, null, null, null, null, this)
                    : null;
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }

        @Override
        public void fadeOutMusic() {
            fadeOutCount++;
            musicEvents.add("fade");
        }

        @Override
        public void playMusic(int musicId) {
            playedMusic.add(musicId);
            musicEvents.add("music:" + musicId);
        }
    }
}
