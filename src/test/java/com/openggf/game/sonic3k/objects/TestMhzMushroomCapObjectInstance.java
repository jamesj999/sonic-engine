package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMhzMushroomCapObjectInstance {
    private static final int MHZ_MUSHROOM_CAP = 0x23;

    @Test
    void registryRoutesSklSlot23ToMhzMushroomCapSolidObject() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance cap = registry.create(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));

        assertFalse(cap instanceof PlaceholderObjectInstance,
                "SKL slot $23 is Obj_MHZMushroomCap and must not fall back to placeholder rendering");
        assertInstanceOf(SolidObjectProvider.class, cap,
                "Obj_MHZMushroomCap calls SolidObjectTop every frame");
        assertEquals(1, cap.getPriorityBucket(),
                "Obj_MHZMushroomCap initializes priority=$80 unless subtype bit 7 is set");
    }

    @Test
    void mushroomCapProvidesS3kTailsCpuInteractPointerWord() {
        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));

        RomObjectCodePointerProvider pointerProvider =
                assertInstanceOf(RomObjectCodePointerProvider.class, cap);
        assertEquals(0x0003, pointerProvider.romObjectCodePointerHighWord(),
                "Obj_MHZMushroomCap is installed at 0x0003E080; S3K Tails_CPU_interact "
                        + "stores word 0's high word while Tails stands on it");
    }

    @Test
    void mushroomCapSolidObjectTopUsesRomD3SurfaceHeight() {
        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));
        SolidObjectProvider solid = cap;

        assertEquals(0x12, solid.getSolidParams().groundHalfHeight(),
                "Obj_MHZMushroomCap passes byte_3E0DA[mapping_frame] as SolidObjectTop d3");
        assertTrue(solid.usesGroundHalfHeightForTopSolidContact(),
                "SolidObjectTop must test the cap surface at y_pos-d3, not y_pos; "
                        + "otherwise low caps catch Sonic while he is still following terrain slopes");
        assertFalse(solid.usesPlatformObjectLandingSnap(),
                "SolidObjectTop landing preserves the helper's y_pos += d0+3 result, not PlatformObject snap");
        assertTrue(solid.rejectsZeroDistanceTopSolidLanding(),
                "SolidObjectTop rejects d0 == 0 through cmpi.w #-$10,d0 / blo "
                        + "(sonic3k.asm:41996-42015)");
        assertFalse(solid.carriesRiderOnHorizontalMove(null),
                "Obj_MHZMushroomCap passes current x_pos as SolidObjectTop d4, so horizontal bobbing "
                        + "does not carry riders through MvSonicOnPtfm");
    }

    @Test
    void subtypeBitSevenPromotesMushroomCapSpritePriority() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance cap = registry.create(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0x80, 0, false, 0));

        assertEquals(6, cap.getPriorityBucket(),
                "Obj_MHZMushroomCap changes priority to $300 when subtype bit 7 is set");
    }

    @Test
    void mushroomCapUsesRomAnimCounterByteOffsetPositionTable() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance cap = registry.create(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));

        cap.update(0x0A, null);
        assertEquals(0x11FF, cap.getX(),
                "MHZMushroomCap_Positions byte offset $0A applies X offset -1 from the spawn x_pos");
        assertEquals(0x0500, cap.getY(),
                "MHZMushroomCap_Positions byte offset $0A applies Y offset 0 from the spawn y_pos");

        cap.update(0x20, null);
        assertEquals(0x11FE, cap.getX(),
                "MHZMushroomCap_Positions byte offset $20 applies X offset -2 from the spawn x_pos");
        assertEquals(0x0500, cap.getY(),
                "MHZMushroomCap_Positions byte offset $20 applies Y offset 0 from the spawn y_pos");

        ObjectInstance phaseShiftedCap = registry.create(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 1, 0, false, 0));
        phaseShiftedCap.update(0, null);
        assertEquals(0x11FD, phaseShiftedCap.getX(),
                "subtype bit 0 stores $14 in object field $36, shifting the byte offset to $14");
        assertEquals(0x0501, phaseShiftedCap.getY(),
                "subtype bit 0 uses the $14 table entry's Y offset");
    }

    @Test
    void mushroomCapReadsPublishedMhzAnimCounterWhenRuntimeStatePresent() {
        ZoneRuntimeRegistry runtimeRegistry = new ZoneRuntimeRegistry();
        MhzZoneRuntimeState runtimeState =
                new MhzZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS);
        runtimeState.publishMushroomCapPositionCounter(0x20);
        runtimeRegistry.install(runtimeState);

        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));
        cap.setServices(new TestObjectServices().withZoneRuntimeRegistry(runtimeRegistry));

        cap.update(0, null);

        assertEquals(0x11FE, cap.getX(),
                "Obj_MHZMushroomCap reads Anim_Counters+$F from MHZ runtime state, not the global frame");
        assertEquals(0x0500, cap.getY());
    }

    @Test
    void mushroomCapAppliesRuntimeCounterPositionWhenServicesAreInjected() {
        ZoneRuntimeRegistry runtimeRegistry = new ZoneRuntimeRegistry();
        MhzZoneRuntimeState runtimeState =
                new MhzZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS);
        runtimeState.publishMushroomCapPositionCounter(0x20);
        runtimeRegistry.install(runtimeState);

        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));

        cap.setServices(new TestObjectServices().withZoneRuntimeRegistry(runtimeRegistry));

        assertEquals(0x11FE, cap.getX(),
                "Obj_MHZMushroomCap init falls through to its main routine and reads "
                        + "Anim_Counters+$F before the first solid/contact pass");
        assertEquals(0x0500, cap.getY());
    }

    @Test
    void darkSpottedMushroomCapRendersRomLevelArtFrame() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_DARK)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));
        cap.setServices(new TestObjectServices().withLevelManager(levelManager));
        cap.appendRenderCommands(new ArrayList<>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_DARK);
        verify(renderer).drawFrameIndexForcedPriority(0, 0x1200, 0x0500, false, false, -1, true);
    }

    @Test
    void subtypeBitZeroMushroomCapRendersLightSpottedRomLevelArtFrame() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_LIGHT)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 1, 0, false, 0));
        cap.setServices(new TestObjectServices().withLevelManager(levelManager));
        cap.appendRenderCommands(new ArrayList<>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_LIGHT);
        verify(renderer).drawFrameIndexForcedPriority(0, 0x11FD, 0x0501, false, false, -1, true);
    }

    @Test
    void subtypeBitSixStripsMushroomCapPlanePriority() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_DARK)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0x40, 0, false, 0));
        cap.setServices(new TestObjectServices().withLevelManager(levelManager));
        cap.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndexForcedPriority(0, 0x1200, 0x0500, false, false, -1, false);
    }

    @Test
    void bounceAnimationSeq1HasExactlyTwentyThreeFramesBeforeFcTerminator() throws ReflectiveOperationException {
        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));
        Field field = MhzMushroomCapObjectInstance.class.getDeclaredField("animationState");
        field.setAccessible(true);
        ObjectAnimationState animationState = (ObjectAnimationState) field.get(cap);

        assertEquals(23, animationState.frameCount(1 /* ANIM_BOUNCE */),
                "Ani_MHZMushroomCap_Seq1 (byte_3E1E1, sonic3k.asm Anim - Mushroom Cap.asm:5-6) plays "
                        + "exactly 23 frames before hitting the $FC 'increment routine counter' terminator; "
                        + "the script must not carry an extra trailing idle-delay frame at speed 0");
    }

    @Test
    void standingOnSpringFrameLaunchesFromStoredPreviousYVelocity() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance cap = registry.create(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));
        SolidObjectListener listener = assertInstanceOf(SolidObjectListener.class, cap,
                "Obj_MHZMushroomCap_BounceCharacter runs from the solid-object standing callback");
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1200, (short) 0x04E0);

        player.setYSpeed((short) 0x0700);
        cap.update(0, player);
        player.setYSpeed((short) 0);
        player.setAir(false);
        player.setOnObject(true);
        player.setSpindash(true);

        listener.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);
        for (int frame = 1; frame <= 6; frame++) {
            cap.update(frame, player);
            listener.onSolidContact(player, new SolidContact(true, false, false, true, false), frame);
        }

        assertEquals((short) -0x0780, player.getYSpeed(),
                "Obj_MHZMushroomCap_BounceCharacter maps stored y_vel $0700 to bounce y_vel -$780");
        assertTrue(player.getAir(), "bounce sets Status_InAir");
        assertFalse(player.isOnObject(), "bounce clears Status_OnObj");
        assertFalse(player.getSpindash(), "bounce clears spin_dash_flag");
        assertEquals(Sonic3kAnimationIds.SPRING.id(), player.getAnimationId(),
                "bounce sets anim to Spring ($10)");
    }

    @Test
    void springFrameLaunchWaitsForCurrentSolidObjectTopSnap() {
        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x0150, 0x0540, MHZ_MUSHROOM_CAP, 0, 0, false, 0));
        SolidObjectListener listener = cap;
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x014E, (short) 0x0525);

        player.setYSpeed((short) 0x0700);
        cap.update(0, player);
        player.setYSpeed((short) 0);
        player.setAir(false);
        player.setOnObject(true);

        for (int frame = 1; frame <= 5; frame++) {
            listener.onSolidContact(player, new SolidContact(true, false, false, true, false), frame);
            cap.update(frame, player);
        }

        cap.update(6, player);
        assertEquals((short) 0, player.getYSpeed(),
                "Obj_MHZMushroomCap_BounceCharacter runs after the current SolidObjectTop call, not before it");

        player.setCentreY((short) 0x051B);
        listener.onSolidContact(player, new SolidContact(true, false, false, true, false), 6);

        assertEquals((short) -0x0780, player.getYSpeed(),
                "mapping frame 3 launches after SolidObjectTop has snapped the rider to the cap surface");
        assertEquals(0x051B, player.getCentreY() & 0xFFFF,
                "the launch must preserve SolidObjectTop's y_pos = cap_y - d3 - y_radius");
        assertTrue(player.getAir());
        assertFalse(player.isOnObject());
    }

    @Test
    void sidekickSpringFrameUsesItsOwnStoredPreviousYVelocity() {
        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));
        SolidObjectListener listener = cap;
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x1100, (short) 0x04E0);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x1200, (short) 0x04E0);
        cap.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        tails.setYSpeed((short) 0x0700);
        cap.update(0, sonic);
        tails.setYSpeed((short) 0);
        tails.setAir(false);
        tails.setOnObject(true);

        listener.onSolidContact(tails, new SolidContact(true, false, false, true, false), 0);
        for (int frame = 1; frame <= 6; frame++) {
            cap.update(frame, sonic);
            listener.onSolidContact(tails, new SolidContact(true, false, false, true, false), frame);
        }

        assertEquals((short) -0x0780, tails.getYSpeed(),
                "Obj_MHZMushroomCap_BounceP2 keeps a separate stored y_vel for the sidekick path");
    }

    @Test
    void mushroomCapBounceForcesPlayerBackToNormalRoutine() {
        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));
        SolidObjectListener listener = cap;
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1200, (short) 0x04E0);

        player.setYSpeed((short) 0x0700);
        cap.update(0, player);
        player.setYSpeed((short) 0);
        player.setAir(false);
        player.setOnObject(true);
        player.setHurt(true);

        listener.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);
        for (int frame = 1; frame <= 6; frame++) {
            cap.update(frame, player);
            listener.onSolidContact(player, new SolidContact(true, false, false, true, false), frame);
        }

        assertFalse(player.isHurt(),
                "MHZMushroomCap_BounceCharacter writes routine=2 after the bounce, leaving the hurt routine");
    }

    @Test
    void bounceAnimationHonorsRomFcRoutineIncrementFrameBeforeRestarting() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_CAP_DARK)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        MhzMushroomCapObjectInstance cap = new MhzMushroomCapObjectInstance(new ObjectSpawn(
                0x1200, 0x0500, MHZ_MUSHROOM_CAP, 0, 0, false, 0));
        cap.setServices(new TestObjectServices().withLevelManager(levelManager));
        SolidObjectListener listener = cap;
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1200, (short) 0x04E0);

        for (int frame = 0; frame <= 23; frame++) {
            cap.update(frame, player);
            listener.onSolidContact(player, new SolidContact(true, false, false, true, false), frame);
        }
        cap.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndexForcedPriority(0, 0x1201, 0x04FD, false, false, -1, true);
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }
}
