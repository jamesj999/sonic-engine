package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.GameStateManager;
import com.openggf.game.GameRng;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.LevelManager;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.tests.TestablePlayableSprite;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMadmoleBadnikInstance {

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void registryCreatesMadmoleForSklSlot8cInMhz() {
        Sonic3kObjectRegistry registry = new MhzRegistry();

        ObjectInstance instance = registry.create(new ObjectSpawn(0x120, 0x100,
                Sonic3kObjectIds.MADMOLE, 0, 0, false, 0));

        assertInstanceOf(MadmoleBadnikInstance.class, instance);
    }

    @Test
    void wakingMadmoleReservesItsCreateChild1BodySlotAfterTheParent() {
        ObjectManager objectManager = mock(ObjectManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        ObjectSpawn spawn = new ObjectSpawn(
                0x120, 0x100, Sonic3kObjectIds.MADMOLE, 0, 0, false, 0);
        MadmoleBadnikInstance madmole = new MadmoleBadnikInstance(spawn);
        madmole.setServices(new TestObjectServices()
                .withLevelManager(levelManager)
                .withGameState(mock(GameStateManager.class)));
        madmole.setSlotIndex(17);
        TestablePlayableSprite player = player(0x120, 0x100);

        advanceToRising(madmole, player);

        verify(objectManager).allocateChildSlotsAfter(spawn, 1, 17);

        int frame = advanceWhileState(madmole, player, 3, "RISING");
        frame = advanceWhileState(madmole, player, frame, "PAUSING");
        frame = advanceWhileState(madmole, player, frame, "DRILLING");
        advanceWhileState(madmole, player, frame, "SINKING");

        verify(objectManager).freeReservedChildSlot(spawn, 0);
    }

    @Test
    void usesRomRenderBoundsAndSideDrillPriorityFromObjectData() {
        MadmoleBadnikInstance madmole = madmole();
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild();

        assertEquals(0x18, madmole.getOnScreenHalfWidth(),
                "ObjDat_Madmole width_pixels byte is $18");
        assertEquals(0x04, madmole.getOnScreenHalfHeight(),
                "ObjDat_Madmole height_pixels byte is $04");
        assertEquals(5, madmole.getPriorityBucket(),
                "ObjDat_Madmole priority word $280 maps to render bucket 5");
        assertEquals(0x08, child.getOnScreenHalfWidth(),
                "word_8D9BA side drill width_pixels byte is $08");
        assertEquals(0x08, child.getOnScreenHalfHeight(),
                "word_8D9BA side drill height_pixels byte is $08");
        assertEquals(5, child.getPriorityBucket(),
                "word_8D9BA side drill priority word $280 maps to render bucket 5");
    }

    @Test
    void buriedCapUsesRomMappingFrameAndHasNoTouchCollision() {
        MadmoleBadnikInstance madmole = madmole();
        TestablePlayableSprite player = player(0x1C0, 0x100);

        advancePastWaitOffscreenInit(madmole, player);

        assertEquals(0x0D, mappingFrameOf(madmole),
                "ObjDat_Madmole stores mapping_frame=$0D for the buried cap");
        assertEquals(0, madmole.getCollisionFlags(),
                "ObjDat_Madmole's final byte is collision_flags=0; the cap is solid-only via SolidObjectFull");
    }

    @Test
    void activeBodyUsesRomChildObjectDataMetadata() {
        MadmoleBadnikInstance madmole = madmole();
        TestablePlayableSprite player = player(0x100, 0x100);

        advanceToRising(madmole, player);

        assertEquals(0x0C, madmole.getOnScreenHalfWidth(),
                "word_8D9B4 body-child width_pixels byte is $0C once ChildObjDat_8D9C0 is active");
        assertEquals(0x0C, madmole.getOnScreenHalfHeight(),
                "word_8D9B4 body-child height_pixels byte is $0C once ChildObjDat_8D9C0 is active");
        assertEquals(0x0B, madmole.getCollisionFlags(),
                "word_8D9B4 body-child collision byte is $0B");
        assertEquals(5, madmole.getPriorityBucket(),
                "word_8D9B4 body-child priority word $280 maps to render bucket 5");
    }

    @Test
    void activeBodyRendersStaticHoleAndMovingBodySeparately() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MADMOLE)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        MadmoleBadnikInstance madmole = madmole(new TestObjectServices()
                .withLevelManager(levelManager));
        TestablePlayableSprite player = player(0x100, 0x100);

        advanceToRising(madmole, player);
        madmole.update(3, player);
        madmole.appendRenderCommands(new ArrayList<>());

        InOrder inOrder = inOrder(renderer);
        // advanceToRising already ran the creation-frame rise step (y+$10 -> y+$F,
        // loc_8D620->loc_8D636), and update(3) rises one more pixel to y+$E.
        inOrder.verify(renderer).drawFrameIndex(0, 0x120, 0x10E, false, false);
        inOrder.verify(renderer).drawFrameIndex(0x0D, 0x120, 0x100, false, false);
    }

    @Test
    void exposesRomSolidObjectFullCapDimensions() {
        MadmoleBadnikInstance madmole = madmole();

        SolidObjectProvider solid = assertInstanceOf(SolidObjectProvider.class, madmole,
                "Obj_Madmole calls SolidObjectFull with d1=$1F,d2=4,d3=5 after its routine");
        SolidObjectParams params = solid.getSolidParams();

        assertEquals(0x1F, params.halfWidth());
        assertEquals(4, params.airHalfHeight());
        assertEquals(5, params.groundHalfHeight());
        assertEquals(0, params.offsetX());
        assertEquals(0, params.offsetY());
    }

    @Test
    void objWaitOffscreenSuppressesRangeDetectionAndCollisionUntilSetupRuns() {
        MadmoleBadnikInstance madmole = madmole();
        TestablePlayableSprite player = player(0x100, 0x100);

        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 0xC0, 0);
        madmole.update(0, player);

        assertEquals("BURIED", madmole.getStateName());
        assertEquals(0x100, madmole.getY());
        assertEquals(0, madmole.getCollisionFlags());

        putMadmoleOnScreen();
        madmole.update(1, player);

        assertEquals("BURIED", madmole.getStateName(),
                "Obj_WaitOffscreen restores Obj_Madmole and returns before SetUp_ObjAttributes");
        assertEquals(0, madmole.getCollisionFlags());

        madmole.update(2, player);

        assertEquals("BURIED", madmole.getStateName(),
                "loc_8D5A6 setup returns before range detection can run");
        assertEquals(0, madmole.getCollisionFlags(),
                "ObjDat_Madmole has mapping_frame=$0D and collision_flags=0");

        madmole.update(3, player);

        assertEquals("RISING", madmole.getStateName());
        assertEquals(0x10F, madmole.getY(),
                "loc_8D620 falls through to loc_8D636, so the body child does its first "
                        + "MoveSprite2 rise step (parent y+$10 up one pixel) on the creation frame");
        assertEquals(-0x100, madmole.getYVelocity());
    }

    @Test
    void solidCapStaysAtParentPositionWhileBodyChildRises() {
        MadmoleBadnikInstance madmole = madmole();
        TestablePlayableSprite player = player(0x100, 0x100);
        advanceToRising(madmole, player);

        for (int frame = 3; frame <= 0x22; frame++) {
            madmole.update(frame, player);
        }

        assertEquals("PAUSING", madmole.getStateName());
        assertEquals(0x0F0, madmole.getY(),
                "ChildObjDat_8D9C0 starts the body child at parent y+$10 before loc_8D636 rises for $20 pixels");

        SolidObjectParams params = madmole.getSolidParams();
        assertEquals(0x10, params.offsetY(),
                "parent Obj_Madmole keeps SolidObjectFull anchored at its original y_pos while the body child rises");
        assertEquals(0x100, madmole.getY() + params.offsetY());
    }

    @Test
    void staysBuriedUntilPlayerIsWithinRomA0Range() {
        MadmoleBadnikInstance madmole = madmole();
        TestablePlayableSprite player = player(0x1C0, 0x100);

        advancePastWaitOffscreenInit(madmole, player);
        madmole.update(2, player);

        assertEquals("BURIED", madmole.getStateName());
        assertEquals(0x120, madmole.getX());
        assertEquals(0x100, madmole.getY());

        player.setCentreX((short) 0x1BF);
        madmole.update(3, player);

        assertEquals("RISING", madmole.getStateName());
        assertEquals(-0x100, madmole.getYVelocity());
        assertEquals(0x1E, madmole.getTimer(),
                "loc_8D620 sets $2E=$1F then falls through to loc_8D636, whose subq.w #1 "
                        + "leaves $2E=$1E on the same frame the body child is created");
    }

    @Test
    void nativeP2InsideRomRangeWakesMadmoleWhenP1IsTooFar() {
        TestablePlayableSprite sidekick = player(0x121, 0x100);
        MadmoleBadnikInstance madmole = madmole(new TestObjectServices()
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = player(0x1C0, 0x100);

        advancePastWaitOffscreenInit(madmole, sonic);
        madmole.update(2, sonic);

        assertEquals("RISING", madmole.getStateName(),
                "loc_8D5B0 uses Find_SonicTails before testing d2<$A0");
        assertEquals(-0x100, madmole.getYVelocity());
    }

    @Test
    void deadPlayerInsideRomRangeStillWakesMadmole() {
        MadmoleBadnikInstance madmole = madmole();
        TestablePlayableSprite player = player(0x121, 0x100);
        player.setDead(true);

        advancePastWaitOffscreenInit(madmole, player);
        madmole.update(2, player);

        assertEquals("RISING", madmole.getStateName(),
                "loc_8D5B0 only checks Find_SonicTails distance d2<$A0; it does not gate on player death");
        assertEquals(-0x100, madmole.getYVelocity());
    }

    @Test
    void risesPausesDrillsThenSinksBeforeCooldown() {
        MadmoleBadnikInstance madmole = madmole();
        TestablePlayableSprite player = player(0x100, 0x100);

        advanceToRising(madmole, player);

        // Frame values are irrelevant to the state machine (it uses internal
        // timers), so drive until each ROM routine transition instead of using
        // hard-coded frame windows.
        int frame = 3;
        frame = advanceWhileState(madmole, player, frame, "RISING");
        assertEquals("PAUSING", madmole.getStateName());
        assertEquals(0x0F0, madmole.getY());
        assertEquals(0x1F, madmole.getTimer(),
                "loc_8D648 sets $2E=$1F when the body child finishes rising");

        frame = advanceWhileState(madmole, player, frame, "PAUSING");
        assertEquals("DRILLING", madmole.getStateName());

        frame = advanceWhileState(madmole, player, frame, "DRILLING");
        assertEquals("SINKING", madmole.getStateName());
        assertEquals(0x100, madmole.getYVelocity());

        frame = advanceWhileState(madmole, player, frame, "SINKING");
        assertEquals("COOLDOWN", madmole.getStateName());
        assertEquals(60, madmole.getTimer(),
                "loc_8D5DE sets $2E=60 the frame after the body child finishes sinking "
                        + "(parent routine 4 observes the cleared busy bit one frame late)");

        frame = advanceWhileState(madmole, player, frame, "COOLDOWN");
        assertEquals("BURIED", madmole.getStateName(),
                "Obj_Wait jumps through $34 only once the word timer has underflowed negative");
        assertEquals(0x100, madmole.getY());
    }

    @Test
    void cooldownExitsOnlyAfterObjWaitTimerUnderflowsSigned() {
        MadmoleBadnikInstance madmole = madmole();
        TestablePlayableSprite player = player(0x100, 0x100);

        advanceToRising(madmole, player);
        for (int frame = 3; frame <= 0x7F; frame++) {
            madmole.update(frame, player);
        }

        assertEquals("COOLDOWN", madmole.getStateName());
        assertEquals(60, madmole.getTimer());

        for (int frame = 0x80; frame < 0x80 + 60; frame++) {
            madmole.update(frame, player);
        }

        assertEquals("COOLDOWN", madmole.getStateName(),
                "Madmole routine 6 calls Obj_Wait, whose subq.w/bmi leaves timer value 0 waiting");
        assertEquals(0, madmole.getTimer());

        madmole.update(0x80 + 60, player);

        assertEquals("BURIED", madmole.getStateName(),
                "Obj_Wait jumps through $34 only once the word timer has underflowed negative");
    }

    @Test
    void attackStartupUsesRomRawAnimationBeforeSideDrillFrames() {
        MadmoleBadnikInstance madmole = madmole();
        TestablePlayableSprite player = player(0x100, 0x100);
        advanceToRising(madmole, player);

        for (int frame = 3; frame <= 0x42; frame++) {
            madmole.update(frame, player);
        }
        assertEquals("DRILLING", madmole.getStateName());

        madmole.update(0x43, player);

        assertEquals(1, mappingFrameOf(madmole),
                "loc_8D67A runs Animate_Raw over byte_8D9D8; fresh anim_frame=0 advances to frame byte 1");
    }

    @Test
    void sideDrillPhasePlaysSpikeMoveAndUsesRomRawFrameDelay() {
        CapturingServices services = new CapturingServices();
        MadmoleBadnikInstance madmole = madmole(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        advanceToRising(madmole, player);

        int frame = advanceWhileState(madmole, player, 3, "RISING");
        frame = advanceWhileState(madmole, player, frame, "PAUSING");
        assertEquals("DRILLING", madmole.getStateName());

        // byte_8D9D8 (attack-startup) animates frames 0,1,2 and holds mapping 2
        // until its $F4 callback (loc_8D680) plays sfx_SpikeMove and spawns the
        // side-drill child.
        while (services.soundIds.isEmpty()) {
            madmole.update(frame++, player);
        }
        assertEquals(List.of(Sonic3kSfx.SPIKE_MOVE.id), services.soundIds,
                "loc_8D680 plays sfx_SpikeMove when byte_8D9D8 reaches its $F4 callback");
        assertEquals(2, mappingFrameOf(madmole),
                "the $F4 callback swaps to byte_8D9DD but does not animate the new script until the next frame");

        // byte_8D9DD = {2,3,3,4,...}: like every Animate_Raw script the initial
        // frame byte is only the setup mapping, so the first advance shows the
        // second 3 and holds it for the delay-2 cadence before reaching 4.
        madmole.update(frame++, player);
        assertEquals(3, mappingFrameOf(madmole),
                "byte_8D9DD's first animated frame after the $F4 swap is 3");
        int threeHold = 1;
        while (mappingFrameOf(madmole) == 3) {
            madmole.update(frame++, player);
            if (mappingFrameOf(madmole) == 3) {
                threeHold++;
            }
        }
        assertEquals(3, threeHold,
                "byte_8D9DD uses Animate_Raw delay 2, so the displayed frame 3 is held three engine frames");
        assertEquals(4, mappingFrameOf(madmole),
                "byte_8D9DD advances to frame 4 after frame 3 is exhausted");
    }

    @Test
    void sideDrillCallbackSpawnsCollisionChildAtRomFacingOffset() {
        ObjectManager objectManager = mock(ObjectManager.class);
        CapturingServices services = new CapturingServices(objectManager);
        MadmoleBadnikInstance madmole = madmole(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        advanceToRising(madmole, player);

        for (int frame = 3; frame <= 0x49; frame++) {
            madmole.update(frame, player);
        }

        ArgumentCaptor<ObjectInstance> captor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager).addDynamicObjectAfterCurrent(captor.capture());
        MadmoleBadnikInstance.SideDrillChild child =
                assertInstanceOf(MadmoleBadnikInstance.SideDrillChild.class, captor.getValue());
        assertEquals(0x120 - 0x0E, child.getX(),
                "ChildObjDat_8D9C8 uses x offset -$E when the body is facing left");
        assertEquals(0x0F0 - 0x0C, child.getY(),
                "ChildObjDat_8D9C8/8D9D0 use y offset -$C from the raised body child");

        TouchResponseProvider touch = assertInstanceOf(TouchResponseProvider.class, child);
        assertEquals(0xD8, touch.getCollisionFlags(),
                "word_8D9BA gives the side drill child collision byte $D8");
    }

    @Test
    void sideDrillChildUsesContinuousS3kSpecialPropertyTouchProfile() {
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild();

        assertEquals(0xD8, child.getCollisionFlags(),
                "word_8D9BA gives the side drill child collision byte $D8");
        assertEquals(TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY,
                child.getTouchResponseProfile().categoryDecodeMode(),
                "$D8 is routed through S3K Touch_Special and collision_property, not generic boss handling");
        assertEquals(true, child.getTouchResponseProfile().continuousCallbacks(),
                "sub_8D8E6/sub_8D94A clear and poll collision_property every active side-drill frame");
    }

    @Test
    void sideDrillChildUsesRomRawAnimationScript() {
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild();

        child.update(0x48, player(0x100, 0x100));
        assertEquals(5, sideChildMappingFrameOf(child),
                "loc_8D746 only sets up word_8D9BA and loc_8D89E; Animate_Raw does not run until the next frame");
        child.update(0x49, player(0x100, 0x100));
        assertEquals(6, sideChildMappingFrameOf(child),
                "routine 2/4 runs Animate_Raw over byte_8D9E7 after the setup frame");
        child.update(0x50, player(0x100, 0x100));
        assertEquals(6, sideChildMappingFrameOf(child));
        child.update(0x51, player(0x100, 0x100));
        assertEquals(6, sideChildMappingFrameOf(child));
        child.update(0x52, player(0x100, 0x100));
        assertEquals(7, sideChildMappingFrameOf(child),
                "byte_8D9E7 uses delay 2 before advancing from frame 6 to frame 7");
    }

    @Test
    void sideDrillChildInitializesRomStraightSlideFromRandomNumber() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 1);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(rng);

        child.update(0x48, player(0x100, 0x100));

        assertEquals(-0x600, sideChildIntField(child, "xVelocity"),
                "loc_8D89E selects word_8D8DE[0] when tst.b Random_Number is non-negative");
        assertEquals(0, sideChildIntField(child, "yVelocity"));
        assertEquals(0x120 - 0x0E, child.getX(),
                "loc_8D746/8D89E initializes velocity only; MoveSprite2 starts on the next side-child frame");

        child.update(0x49, player(0x100, 0x100));
        assertEquals(0x120 - 0x0E - 6, child.getX());
        assertEquals(0x0F0 - 0x0C, child.getY());
    }

    @Test
    void sideDrillChildInitializesRomArcingDrillFromRandomNumber() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 4);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(rng);

        child.update(0x48, player(0x100, 0x100));

        assertEquals(-0x380, sideChildIntField(child, "xVelocity"),
                "loc_8D89E selects word_8D8DE[1] when tst.b Random_Number is negative");
        assertEquals(0x200, sideChildIntField(child, "yVelocity"));

        child.update(0x49, player(0x100, 0x100));
        assertEquals(0x120 - 0x0E - 4, child.getX());
        assertEquals(0x0F0 - 0x0C + 2, child.getY(),
                "loc_8D778 uses MoveSprite_LightGravity, moving with old y_vel before gravity is applied");
        assertEquals(0x200 + 0x20, sideChildIntField(child, "yVelocity"),
                "MoveSprite_LightGravity (sonic3k.asm:178357) applies moveq #$20 gravity, not the $38 object default");
    }

    @Test
    void straightSideDrillTouchLaunchesPlayerWithRomFlipperResponse() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 1);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        child.update(0x48, player);
        services.soundIds.clear();

        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child,
                "loc_8D768 polls sub_8D8E6 for collision_property on the straight drill branch");
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);

        assertEquals(List.of(), services.soundIds,
                "TouchResponse only writes collision_property; the later side-drill SST slot owns sub_8D8E6");
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getGSpeed());
        assertEquals(0, player.getYSpeed());
        assertEquals(false, player.getAir());

        child.update(0x49, player);

        assertEquals(List.of(Sonic3kSfx.FLIPPER.id), services.soundIds);
        assertEquals(-0xC00, player.getXSpeed(),
                "sub_8D8E6 doubles the side-drill x_vel into player x_vel");
        assertEquals(-0xC00, player.getGSpeed());
        assertEquals(-0x200, player.getYSpeed());
        assertEquals(true, player.getAir());
        assertEquals(0x1A, player.getAnimationId(),
                "sub_8D8E6 writes anim=$1A on the straight side-drill flipper response");
    }

    @Test
    void straightSideDrillStillLaunchesPlayerDuringPostHitInvulnerabilityTimer() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 1);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        player.setInvulnerableFrames(0x78);
        player.setInvincibleFrames(0);
        child.update(0x48, player);
        services.soundIds.clear();

        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child);
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);

        assertEquals(List.of(), services.soundIds,
                "post-hit invulnerability still permits collision_property, but not an inline player mutation");
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());

        child.update(0x49, player);

        assertEquals(List.of(Sonic3kSfx.FLIPPER.id), services.soundIds,
                "sub_8D8E6 checks Status_Invincible in status_secondary, not invulnerable_time");
        assertEquals(-0xC00, player.getXSpeed());
        assertEquals(-0x200, player.getYSpeed());
    }

    @Test
    void arcingSideDrillTouchCapturesAndCarriesPlayerAtRomOffset() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 4);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        child.update(0x48, player);
        services.soundIds.clear();

        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child,
                "loc_8D778 polls sub_8D94A for collision_property on the arcing branch");
        // The player TouchResponse pass only records collision_property; sub_8D94A
        // runs during the side drill's own update (loc_8D778), so the grab applies
        // on the frame the arm executes, not inside the touch callback.
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);
        child.update(0x49, player);

        assertEquals(List.of(Sonic3kSfx.FLIPPER.id), services.soundIds);
        assertEquals(true, player.getAir());
        assertEquals(true, player.isObjectControlled(),
                "sub_8D94A sets object_control(a1)=1 so the side drill owns player movement");
        assertEquals(true, player.isObjectControlAllowsCpu(),
                "object_control=1 is a native bits 0-6 state, not the signed bit-7 full-control state");
        assertEquals(true, player.isObjectControlSuppressesMovement(),
                "object_control=1 suppresses normal movement while the arcing drill carries the player");
        assertEquals(0x1A, player.getAnimationId());
        assertEquals(0, child.getPriorityBucket(),
                "sub_8D94A writes priority(a0)=0 when the arcing side drill captures a player");

        // loc_8D778 (routine 4) still moves the arm on the grab frame without
        // carrying; the carry (loc_8D7A8, routine 8) starts the next frame and pins
        // the player to the arm's pre-move coordinates before MoveSprite runs.
        int armXBeforeCarry = child.getX();
        int armYBeforeCarry = child.getY();
        child.update(0x4A, player);

        assertEquals(armXBeforeCarry - 8, player.getCentreX(),
                "loc_8D7A8 pins the captured player to the pre-move x_pos(a0)-8 while x_vel is negative");
        assertEquals(armYBeforeCarry + 8, player.getCentreY(),
                "loc_8D7A8 pins the captured player to the pre-move y_pos(a0)+8");
    }

    @Test
    void arcingSideDrillCarryPreservesCapturedPlayerSubpixels() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 4);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        // Give the CPU the ROM's frozen carry subpixels (Player_2 x_sub/y_sub).
        player.setSubpixelRaw(0xF600, 0x2E00);
        child.update(0x48, player);

        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child);
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);
        child.update(0x49, player); // sub_8D94A captures (grab frame, no carry yet)

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));

            child.update(0x4A, player); // loc_8D7A8 carry: move.w to x_pos/y_pos only
        }

        assertEquals(0xF600, player.getXSubpixelRaw(),
                "loc_8D7D4 writes x_pos(a1) with move.w, leaving the captured player's x_sub untouched");
        assertEquals(0x2E00, player.getYSubpixelRaw(),
                "loc_8D7D4 writes y_pos(a1) with move.w, leaving the captured player's y_sub untouched");
    }

    @Test
    void arcingSideDrillIgnoresRepeatedTouchPollsAfterCapture() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 4);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        child.update(0x48, player);
        services.soundIds.clear();

        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child,
                "loc_8D778 polls sub_8D94A only until it captures a player and switches to loc_8D7A8");
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);
        child.update(0x49, player);
        // Once captured, the player is object-controlled, so a later TouchResponse
        // poll is ignored (sub_8D94A's tst.b object_control(a2) guard) and no second
        // capture / flipper is queued.
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x4A);
        child.update(0x4A, player);

        assertEquals(List.of(Sonic3kSfx.FLIPPER.id), services.soundIds,
                "after sub_8D94A sets routine=8, the side drill carries the captured player instead of re-running capture");
    }

    @Test
    void arcingSideDrillFloorImpactReboundsWhileBelowRomReleaseVelocity() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 4);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        child.update(0x48, player);
        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child);
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);
        child.update(0x49, player);
        // Drop the capture flipper so the assertion below only sees the rebound one.
        services.soundIds.clear();

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));

            // Run the arc until ObjHitFloor_DoRoutine invokes $34(a0) = loc_8D846.
            for (int frame = 0x4A; frame < 0x4A + 40 && services.soundIds.isEmpty(); frame++) {
                child.update(frame, player);
            }
        }

        assertEquals(-0x500, sideChildIntField(child, "yVelocity"),
                "ObjHitFloor_DoRoutine's $34(a0) hook loc_8D846 resets y_vel to -$500 while y_vel<$A00");
        assertEquals(List.of(Sonic3kSfx.FLIPPER.id), services.soundIds);
        assertEquals(true, player.isObjectControlled(),
                "loc_8D846's below-threshold branch rebounds the drill without releasing the captured player");
    }

    @Test
    void arcingSideDrillFloorImpactReleasesPlayerAtRomThresholdVelocity() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 4);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        child.update(0x48, player);
        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child);
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));

            // ROM routine 4 (loc_8D778) still owns the capture frame; the carrying
            // routine loc_8D7A8 with its $34(a0) = loc_8D846 hook runs the frame after.
            child.update(0x49, player);
            setSideChildIntField(child, "yVelocity", 0xA00);
            child.update(0x4A, player);
        }

        assertEquals(false, player.isObjectControlled(),
                "loc_8D846's threshold branch clears object_control and releases the captured player");
        assertEquals(-0x300, player.getYSpeed());
        assertEquals(-0x380, player.getXSpeed());
        assertEquals(-0x200, sideChildIntField(child, "yVelocity"));
    }

    @Test
    void releasedArcingSideDrillCannotImmediatelyRecapturePlayer() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 4);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        child.update(0x48, player);
        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child);
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkRightWallDist(anyInt(), anyInt()))
                    .thenReturn(TerrainCheckResult.noCollision());
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(anyInt(), anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(0, (byte) 0, 0));

            child.update(0x49, player);
            setSideChildIntField(child, "yVelocity", 0xA00);
            child.update(0x4A, player);
            listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x4B);
            child.update(0x4B, player);
        }

        assertEquals(false, player.isObjectControlled(),
                "loc_8D834 switches the arcing side drill to routine 6, so continuous touch polling cannot recapture Sonic");
        assertEquals(-0x200, sideChildIntField(child, "yVelocity"),
                "post-release routine 6 uses MoveSprite2; it does not keep applying light gravity");
    }

    @Test
    void arcingSideDrillWallImpactReleasesPlayerBeforeTerrainClippingLoop() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 4);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        child.update(0x48, player);
        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child);
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);
        child.update(0x49, player);
        services.soundIds.clear();

        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()))
                    .thenReturn(new TerrainCheckResult(-2, (byte) 0, 0));

            // loc_8D7A8's ObjCheckWallDist runs during the carry routine, i.e. the
            // frame after sub_8D94A captured the player.
            child.update(0x4A, player);
            terrain.verify(() -> ObjectTerrainUtils.checkLeftWallDist(anyInt(), anyInt()));
        }

        assertEquals(false, player.isObjectControlled(),
                "loc_8D7A8 clears object_control when ObjCheckLeftWallDist reports a blocked carry path");
        assertEquals(true, player.getAir());
        assertEquals(0x380, player.getXSpeed(),
                "loc_8D820 negates the drill's x_vel into Sonic's x_vel on wall impact");
        assertEquals(0x1C0, sideChildIntField(child, "xVelocity"),
                "loc_8D820 halves the reversed velocity before switching the drill to routine 6");

        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x4B);
        child.update(0x4B, player);
        assertEquals(false, player.isObjectControlled(),
                "after loc_8D834 switches to routine 6, continuous touch polling must not recapture Sonic");
    }

    @Test
    void offscreenArcingSideDrillDeletesAndReleasesCapturedPlayerLikeRomWrapper() {
        GameRng rng = new GameRng(GameRng.Flavour.S3K, 4);
        CapturingServices services = new CapturingServices(mock(ObjectManager.class));
        services.withRng(rng);
        MadmoleBadnikInstance.SideDrillChild child = spawnedSideDrillChild(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        child.update(0x48, player);
        TouchResponseListener listener = assertInstanceOf(TouchResponseListener.class, child);
        listener.onTouchResponse(player, new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY), 0x49);
        child.update(0x49, player);
        assertEquals(true, player.isObjectControlled());

        AbstractObjectInstance.updateCameraBounds(0, 0, 0x100, 0x100, 0);
        setSideChildIntField(child, "currentX", 0x0500);
        setSideChildIntField(child, "currentY", 0x0500);
        child.update(0x4A, player);

        assertEquals(true, child.isDestroyed(),
                "loc_8D6E6 deletes the side drill when its custom camera window test fails");
        assertEquals(false, player.isObjectControlled(),
                "loc_8D724 clears object_control on the captured player before deleting the offscreen drill");
        assertEquals(true, player.getAir(),
                "loc_8D724 also leaves Status_InAir set on the released player");
    }

    @Test
    void defeatingBodyLeavesSolidCapStumpThatNeverReEmerges() {
        ObjectManager objectManager = mock(ObjectManager.class);
        CapturingServices services = new CapturingServices(objectManager);
        MadmoleBadnikInstance madmole = madmole(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        advanceToRising(madmole, player);

        for (int frame = 3; frame <= 0x42; frame++) {
            madmole.update(frame, player);
        }
        assertEquals("DRILLING", madmole.getStateName());

        player.setCentreY((short) 0x0E0);
        player.setYSpeed((short) 0x029B);
        TouchResponseResult result = new TouchResponseResult(0x18, 0x18, 0x08, TouchCategory.ENEMY);
        madmole.onPlayerAttack(player, result);

        assertEquals((short) -0x029B, player.getYSpeed(),
                "Touch_EnemyNormal negates downward y_vel when the player destroys the body from above, "
                        + "even though the separate parent-cap SST remains alive");
        assertEquals(false, madmole.isDestroyed(),
                "sub_8D876 keeps running unconditionally on the parent's own SST slot every frame; "
                        + "EnemyDefeated only removes the body child, not the cap");
        assertEquals("BURIED", madmole.getStateName(),
                "the body child role is gone; the parent snaps back to its cap-only state");
        assertEquals(0, madmole.getCollisionFlags(),
                "the body child's $0B enemy collision is gone; only the cap's zero-collision solid stump remains");

        SolidObjectProvider solid = assertInstanceOf(SolidObjectProvider.class, madmole,
                "the cap keeps SolidObjectFull collision even after the body is destroyed");
        assertEquals(0, solid.getSolidParams().offsetY(),
                "the cap stays anchored at its original home position -- still solid, not raised/sunk");

        // Player stays within the ROM $A0 activation range for many more frames;
        // the busy bit ($38 bit 1) never clears off the normal sink-delete path
        // (loc_8D6D6) once the body was destroyed by EnemyDefeated, so the parent
        // must never spawn a new body child again.
        for (int frame = 0x43; frame <= 0x140; frame++) {
            madmole.update(frame, player);
        }
        assertEquals("BURIED", madmole.getStateName(),
                "the cap must remain a permanent stump and never re-emerge the body");
        assertEquals(false, madmole.isDestroyed());
    }

    private static MadmoleBadnikInstance madmole() {
        return madmole(new TestObjectServices().withGameState(mock(GameStateManager.class)));
    }

    private static MadmoleBadnikInstance madmole(TestObjectServices services) {
        MadmoleBadnikInstance madmole = new MadmoleBadnikInstance(new ObjectSpawn(
                0x120, 0x100, Sonic3kObjectIds.MADMOLE, 0, 0, false, 0));
        madmole.setServices(services.withGameState(mock(GameStateManager.class)));
        return madmole;
    }

    private static TestablePlayableSprite player(int x, int y) {
        return new TestablePlayableSprite("sonic", (short) x, (short) y);
    }

    private static void advancePastWaitOffscreenInit(MadmoleBadnikInstance madmole,
            TestablePlayableSprite player) {
        putMadmoleOnScreen();
        madmole.update(0, player);
        madmole.update(1, player);
    }

    private static void advanceToRising(MadmoleBadnikInstance madmole,
            TestablePlayableSprite player) {
        advancePastWaitOffscreenInit(madmole, player);
        madmole.update(2, player);
    }

    /**
     * Advances the madmole one frame at a time while it remains in {@code state},
     * returning the next unused frame index. Frame values are irrelevant to the
     * state machine (it is driven by internal timers), so callers can chain calls
     * to walk the ROM routine sequence without hard-coding frame windows.
     */
    private static int advanceWhileState(MadmoleBadnikInstance madmole,
            TestablePlayableSprite player, int startFrame, String state) {
        int frame = startFrame;
        for (int guard = 0; guard < 1000 && madmole.getStateName().equals(state); guard++) {
            madmole.update(frame++, player);
        }
        return frame;
    }

    private static void putMadmoleOnScreen() {
        AbstractObjectInstance.updateCameraBounds(0x80, 0x80, 0x1C0, 0x160, 0);
    }

    private static int mappingFrameOf(MadmoleBadnikInstance madmole) {
        try {
            Field field = AbstractS3kBadnikInstance.class.getDeclaredField("mappingFrame");
            field.setAccessible(true);
            return field.getInt(madmole);
        } catch (ReflectiveOperationException e) {
            fail(e);
            return -1;
        }
    }

    private static MadmoleBadnikInstance.SideDrillChild spawnedSideDrillChild() {
        return spawnedSideDrillChild(new GameRng(GameRng.Flavour.S3K, 1));
    }

    private static MadmoleBadnikInstance.SideDrillChild spawnedSideDrillChild(GameRng rng) {
        ObjectManager objectManager = mock(ObjectManager.class);
        CapturingServices services = new CapturingServices(objectManager);
        services.withRng(rng);
        return spawnedSideDrillChild(services);
    }

    private static MadmoleBadnikInstance.SideDrillChild spawnedSideDrillChild(CapturingServices services) {
        ObjectManager objectManager = services.objectManager;
        MadmoleBadnikInstance madmole = madmole(services);
        TestablePlayableSprite player = player(0x100, 0x100);
        advanceToRising(madmole, player);
        for (int frame = 3; frame <= 0x49; frame++) {
            madmole.update(frame, player);
        }
        ArgumentCaptor<ObjectInstance> captor = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager).addDynamicObjectAfterCurrent(captor.capture());
        MadmoleBadnikInstance.SideDrillChild child =
                assertInstanceOf(MadmoleBadnikInstance.SideDrillChild.class, captor.getValue());
        child.setServices(services);
        return child;
    }

    private static int sideChildMappingFrameOf(MadmoleBadnikInstance.SideDrillChild child) {
        return sideChildIntField(child, "mappingFrame");
    }

    private static int sideChildIntField(MadmoleBadnikInstance.SideDrillChild child, String fieldName) {
        try {
            Field field = MadmoleBadnikInstance.SideDrillChild.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(child);
        } catch (ReflectiveOperationException e) {
            fail(e);
            return -1;
        }
    }

    private static void setSideChildIntField(MadmoleBadnikInstance.SideDrillChild child, String fieldName, int value) {
        try {
            Field field = MadmoleBadnikInstance.SideDrillChild.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(child, value);
        } catch (ReflectiveOperationException e) {
            fail(e);
        }
    }

    private static final class MhzRegistry extends Sonic3kObjectRegistry {
        @Override
        protected int currentRomZoneId() {
            return Sonic3kZoneIds.ZONE_MHZ;
        }
    }

    private static final class CapturingServices extends TestObjectServices {
        private final List<Integer> soundIds = new ArrayList<>();
        private final ObjectManager objectManager;

        private CapturingServices() {
            this(null);
        }

        private CapturingServices(ObjectManager objectManager) {
            this.objectManager = objectManager;
        }

        @Override
        public void playSfx(int soundId) {
            soundIds.add(soundId);
        }

        @Override
        public ObjectManager objectManager() {
            return objectManager;
        }
    }
}
