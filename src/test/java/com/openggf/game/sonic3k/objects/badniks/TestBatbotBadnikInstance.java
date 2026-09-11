package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_3K)
class TestBatbotBadnikInstance {

    /**
     * HeadlessTestFixture opens a gameplay session with a loaded CNZ level and
     * this class uses no reset extension, so the session would outlive the
     * class. Later unit tests in the same fork that query terrain statically
     * (ObjectTerrainUtils reads GameServices.levelOrNull(); e.g. the Madmole
     * side drill's ObjHitFloor_DoRoutine) assume no level is registered and
     * would otherwise snap onto CNZ collision.
     */
    @AfterEach
    void closeHeadlessSession() {
        TestEnvironment.resetAll();
    }

    @Test
    void registryCreatesBatbotForSkSetOneSlotA5() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        ObjectInstance instance = registry.create(new ObjectSpawn(0x1AF0, 0x0638,
                Sonic3kObjectIds.BATBOT, 0, 0, false, 0));

        assertTrue(instance instanceof BatbotBadnikInstance);
    }

    @Test
    void collisionResponseListDereferencesLiveBatbotPosition() {
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1AF0,
                0x0638, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));

        assertTrue(batbot.usesCurrentTouchResponseState(),
                "S3K Touch_Loop dereferences the Obj_Batbot SST pointer and reads live x_pos/y_pos "
                        + "(docs/skdisasm/sonic3k.asm:186312-186319,20656-20710)");
    }

    @Test
    void waitsUntilPlayerIsWithinFortyPixelsBeforeMoving() {
        putBatbotOnScreen();
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1AF0,
                0x0638, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        batbot.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setCentreX((short) 0x1A00);
        player.setCentreY((short) 0x0638);
        batbot.update(0, player);
        batbot.update(1, player);

        assertEquals(0x1AF0, batbot.getX());
        assertEquals(0x0638, batbot.getY());

        player.setCentreX((short) 0x1B10);
        batbot.update(2, player);

        assertEquals(0x1AF0, batbot.getX(),
                "ROM activation sets x_vel but does not call MoveSprite2 until the chase routine");
        batbot.update(3, player);

        assertEquals(0x1AF2, batbot.getX());
        assertEquals(0x0638, batbot.getY());
    }

    @Test
    void activationUsesNearestPlayersHorizontalDistance() {
        putBatbotOnScreen();
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1AF0,
                0x0638, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        AbstractPlayableSprite tails = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        batbot.setServices(new TestObjectServices().withSidekicks(java.util.List.of(tails)));

        player.setCentreX((short) 0x1A00);
        player.setCentreY((short) 0x0738);
        tails.setCentreX((short) 0x1B10);
        tails.setCentreY((short) 0x0738);
        batbot.update(0, player);
        batbot.update(1, player);

        assertEquals(0x1AF0, batbot.getX(),
                "Obj_Batbot compares only d2 from Find_SonicTails, the nearest player's absolute X distance");
        batbot.update(2, player);
        assertEquals(0x1AF0, batbot.getX(),
                "Obj_WaitOffscreen restores Obj_Batbot one frame before its activation routine runs");
        batbot.update(3, player);

        assertEquals(0x1AF1, batbot.getX(),
                "Find_SonicTails lets P2 activate, but loc_893CC still chases Player 1");
    }

    @Test
    void chaseObjectAccelerationClampsAtRomMaximum() {
        putBatbotOnScreen();
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1AF0,
                0x0638, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        batbot.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setCentreX((short) 0x1B10);
        player.setCentreY((short) 0x0648);

        batbot.update(0, player);
        batbot.update(1, player);
        batbot.update(2, player);

        assertEquals(0x1AF0, batbot.getX(),
                "The first normal Batbot frame can arm x_vel but does not call MoveSprite2");
        batbot.update(3, player);

        assertEquals(0x1AF2, batbot.getX(),
                "x_vel starts at $200 and Chase_Object refuses to store $208");
        assertEquals(0x0638, batbot.getY(),
                "y_vel is only $08 on the first MoveSprite2 frame");
        batbot.update(4, player);

        assertEquals(0x1AF4, batbot.getX());
        assertEquals(0x0638, batbot.getY());
    }

    @Test
    void objWaitOffscreenSuppressesLogicAndCollisionUntilVisible() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1AF0,
                0x0638, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        batbot.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setCentreX((short) 0x1B10);
        player.setCentreY((short) 0x0638);

        batbot.update(0, player);

        assertEquals(0x1AF0, batbot.getX());
        assertEquals(0, batbot.getCollisionFlags());

        putBatbotOnScreen();
        batbot.update(1, player);

        assertEquals(0, batbot.getCollisionFlags(),
                "Obj_WaitOffscreen restores Obj_Batbot and returns before SetUp_ObjAttributes");
        assertEquals(0x1AF0, batbot.getX());

        batbot.update(2, player);

        assertEquals(0x0D, batbot.getCollisionFlags());
        assertEquals(0x1AF0, batbot.getX(),
                "The first normal Obj_Batbot frame initializes collision but does not move");
    }

    @Test
    void objWaitOffscreenUsesRomTwentyPixelTemporarySpriteMargin() {
        AbstractObjectInstance.updateCameraBounds(0x2420, 0x00A1, 0x2560, 0x0181, 0);
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x24C0,
                0x0088, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        batbot.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setCentreX((short) 0x24C0);
        player.setCentreY((short) 0x0088);

        batbot.update(0, player);

        assertEquals(0, batbot.getCollisionFlags(),
                "Map_Offscreen width/height $20 makes the wrapper visible, but the restored op waits one frame");
        batbot.update(1, player);

        assertEquals(0x0D, batbot.getCollisionFlags(),
                "Obj_Batbot now runs SetUp_ObjAttributes instead of waiting for exact point visibility");
    }

    @Test
    void objWaitOffscreenRequiresVerticalVisibilityBeforeLogicStarts() {
        AbstractObjectInstance.updateCameraBounds(0x1000, 0x0100, 0x1140, 0x01E0, 0);
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1078,
                0x0428, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        batbot.setServices(new TestObjectServices());
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();
        player.setCentreX((short) 0x103A);
        player.setCentreY((short) 0x0322);

        batbot.update(0, player);

        assertEquals(0, batbot.getCollisionFlags(),
                "Obj_WaitOffscreen waits for Draw_Sprite to set render_flags bit 7, not just X range");
        assertEquals(0x1078, batbot.getX());
        assertEquals(0x0428, batbot.getY());
    }

    @Test
    void objWaitOffscreenDeletesDormantBatbotAfterCameraPassesItsCoarseX() {
        AbstractObjectInstance.updateCameraBounds(0x1700, 0, 0x1840, 0x0100, 0);
        Camera camera = mock(Camera.class);
        when(camera.getX()).thenReturn((short) 0x1700);
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1400,
                0x0A80, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        batbot.setServices(new TestObjectServices().withCamera(camera));

        batbot.update(0, null);

        assertTrue(batbot.isDestroyed(),
                "Obj_WaitOffscreen loc_85AD2 deletes a never-visible placeholder once coarse X exceeds $280");
    }

    @Test
    void renderIncludesRomVisualChildSpritesForBodyAndLamp() {
        putBatbotOnScreen();
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1AF0,
                0x0638, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer("cnz_batbot")).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        TestObjectServices services = new TestObjectServices().withLevelManager(levelManager);
        batbot.setServices(services);
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        batbot.update(0, player);
        batbot.update(1, player);

        var children = captureSpawnedVisualChildren(objectManager);
        children.forEach(child -> child.setServices(services));
        children.forEach(child -> child.update(1, player));

        batbot.appendRenderCommands(new ArrayList<GLCommand>());
        children.forEach(child -> child.appendRenderCommands(new ArrayList<GLCommand>()));

        verify(renderer).drawFrameIndex(2, 0x1AF0, 0x0638, false, false);
        verify(renderer).drawFrameIndex(3, 0x1AF0, 0x0648, false, false);
        verify(renderer).drawFrameIndex(5, 0x1AF0, 0x063B, false, false);
    }

    @Test
    void activeBatbotAnimatesParentAndBodyFramesFromRomRawScripts() {
        putBatbotOnScreen();
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1AF0,
                0x0638, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer("cnz_batbot")).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        TestObjectServices services = new TestObjectServices().withLevelManager(levelManager);
        batbot.setServices(services);
        AbstractPlayableSprite player = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 0)
                .build()
                .sprite();

        player.setCentreX((short) 0x1B10);
        player.setCentreY((short) 0x0638);
        batbot.update(0, player);
        batbot.update(1, player);
        var children = captureSpawnedVisualChildren(objectManager);
        children.forEach(child -> child.setServices(services));
        children.forEach(child -> child.update(1, player));
        batbot.update(2, player);
        children.forEach(child -> child.update(2, player));

        batbot.appendRenderCommands(new ArrayList<GLCommand>());
        children.forEach(child -> child.appendRenderCommands(new ArrayList<GLCommand>()));
        verify(renderer).drawFrameIndex(2, 0x1AF0, 0x0638, false, false);
        verify(renderer).drawFrameIndex(4, 0x1AF0, 0x0648, false, false);

        player.setCentreX((short) 0x1C00);
        clearInvocations(renderer);
        batbot.update(3, player);
        children.forEach(child -> child.update(3, player));
        batbot.appendRenderCommands(new ArrayList<GLCommand>());
        children.forEach(child -> child.appendRenderCommands(new ArrayList<GLCommand>()));
        verify(renderer).drawFrameIndex(1, 0x1AF2, 0x0638, false, false);
        verify(renderer).drawFrameIndex(4, 0x1AF2, 0x0648, false, false);

        clearInvocations(renderer);
        for (int frame = 4; frame <= 33; frame++) {
            batbot.update(frame, player);
            int childFrame = frame;
            children.forEach(child -> child.update(childFrame, player));
        }
        batbot.appendRenderCommands(new ArrayList<GLCommand>());
        children.forEach(child -> child.appendRenderCommands(new ArrayList<GLCommand>()));
        verify(renderer).drawFrameIndex(1, 0x1B2E, 0x0638, false, false);
        verify(renderer).drawFrameIndex(3, 0x1B2E, 0x0648, false, false);
    }

    @Test
    void visualChildrenUseNativePriorityAndDoNotInheritParentFlip() {
        putBatbotOnScreen();
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1AF0,
                0x0638, Sonic3kObjectIds.BATBOT, 0, 1, false, 0));
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer("cnz_batbot")).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        TestObjectServices services = new TestObjectServices().withLevelManager(levelManager);
        batbot.setServices(services);

        batbot.update(0, null);
        batbot.update(1, null);
        var children = captureSpawnedVisualChildren(objectManager);
        children.forEach(child -> child.setServices(services));
        children.forEach(child -> child.update(1, null));
        children.forEach(child -> child.appendRenderCommands(new ArrayList<GLCommand>()));

        children.forEach(child -> assertEquals(4, child.getPriorityBucket()));
        children.forEach(child -> assertTrue(child.isHighPriority()));
        verify(renderer).drawFrameIndex(3, 0x1AF0, 0x0648, false, false);
        verify(renderer).drawFrameIndex(5, 0x1AF0, 0x063B, false, false);
    }

    @Test
    void visualChildrenRetainSlotsForOneDeleteCurrentSpriteDispatch() {
        putBatbotOnScreen();
        BatbotBadnikInstance batbot = new BatbotBadnikInstance(new ObjectSpawn(0x1AF0,
                0x0638, Sonic3kObjectIds.BATBOT, 0, 0, false, 0));
        ObjectManager objectManager = mock(ObjectManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        TestObjectServices services = new TestObjectServices().withLevelManager(levelManager);
        batbot.setServices(services);

        batbot.update(0, null);
        batbot.update(1, null);
        var children = captureSpawnedVisualChildren(objectManager);
        children.forEach(child -> child.setServices(services));
        children.forEach(child -> child.update(1, null));
        batbot.setDestroyed(true);

        children.forEach(child -> child.update(2, null));
        children.forEach(child -> assertFalse(child.isDestroyed(),
                "Child_Draw_Sprite only installs Delete_Current_Sprite on this dispatch"));
        children.forEach(child -> child.update(3, null));
        children.forEach(child -> assertTrue(child.isDestroyed(),
                "Delete_Current_Sprite clears the child SST on its following dispatch"));
    }

    private static void putBatbotOnScreen() {
        AbstractObjectInstance.updateCameraBounds(0x1A00, 0, 0x1B40, 0x1000, 0);
    }

    private static java.util.List<BatbotBadnikInstance.BatbotVisualChild> captureSpawnedVisualChildren(
            ObjectManager objectManager) {
        ArgumentCaptor<ObjectInstance> captor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, org.mockito.Mockito.times(2)).addDynamicObjectAfterCurrent(captor.capture());
        return captor.getAllValues().stream()
                .map(BatbotBadnikInstance.BatbotVisualChild.class::cast)
                .toList();
    }
}
