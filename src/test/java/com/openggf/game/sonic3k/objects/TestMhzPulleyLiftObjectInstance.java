package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.GameStateManager;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.NativePositionOps;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class TestMhzPulleyLiftObjectInstance {
    private static final int MHZ_PULLEY_LIFT = 0x06;

    @Test
    void registryRoutesSklSlot06ToMhzPulleyLiftInsteadOfAizRideVine() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));

        assertEquals("MHZPulleyLift", pulley.getName(),
                "SKL slot $06 is Obj_MHZPulleyLift; MHZ must not use the S3KL AIZ ride-vine object");
        assertEquals(5, pulley.getPriorityBucket(),
                "Obj_MHZPulleyLift initializes parent and handle priority=$280");
    }

    @Test
    void pulleyReservesBothAllocateObjectAfterCurrentHandleSlots() {
        ObjectSpawn spawn = new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0);
        MhzPulleyLiftObjectInstance pulley = new MhzPulleyLiftObjectInstance(spawn);
        ObjectManager objectManager = mock(ObjectManager.class);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        pulley.setServices(new TestObjectServices().withLevelManager(levelManager));
        pulley.setSlotIndex(37);

        pulley.update(0, null);
        pulley.update(1, null);

        verify(objectManager, times(1)).allocateChildSlotsAfter(spawn, 2, 37);
    }

    @Test
    void fallingPlayerInLeftHandleGrabWindowIsCarriedAtRomOffset() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);
        player.setRenderFlips(false, true);

        assertEquals("MHZPulleyLift", pulley.getName(),
                "SKL slot $06 must construct the MHZ pulley before behavior can be validated");
        pulley.update(0, player);

        assertTrue(player.isObjectControlled(),
                "sub_3E508 writes object_control=3 when a falling player grabs a pulley handle");
        assertTrue(player.isObjectControlAllowsCpu(),
                "object_control=3 is a bits 0-6 control state, not ROM bit-7 full-control");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "object_control=3 suppresses normal player movement while the pulley owns positioning");
        assertEquals(0x17CE, player.getCentreX() & 0xFFFF,
                "left child handle spawns at parent x_pos-$32");
        assertEquals(0x063C, player.getCentreY() & 0xFFFF,
                "grabbed player is snapped to child handle y_pos+$42");
        assertEquals(0x90, player.getMappingFrame(),
                "loc_3E658 keeps mapping_frame=$90 (GRAB) on a fresh grab because the handle's own $34 "
                        + "offset is seeded at 0, not $20+");
        assertTrue(player.isObjectMappingFrameControl(),
                "Perform_Player_DPLC uses the pulley-owned mapping frame while object_control=3 holds the player");
        assertFalse(player.getRenderVFlip(),
                "loc_3E690 clears render_flags bit 1 on grab so pulley-owned player frames are never V-flipped");
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertEquals(0, player.getGSpeed());
    }

    @Test
    void nativeP2CanGrabPulleyWhenPlayerOneIsOutsideGrabWindow() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        AbstractObjectInstance pulley = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite sidekick = fallingPlayerAt(0x17CE, 0x062C);
        pulley.setServices(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = fallingPlayerAt(0x1900, 0x0660);

        pulley.update(0, sonic);

        assertFalse(sonic.isObjectControlled());
        assertTrue(sidekick.isObjectControlled(),
                "sub_3E4EC checks Player_2 before Player_1, so native P2 can grab a pulley handle independently");
        assertEquals(0x17CE, sidekick.getCentreX() & 0xFFFF);
        assertEquals(0x063C, sidekick.getCentreY() & 0xFFFF);
    }

    @Test
    void nativeP2CanJoinPlayerOneOnTheSamePulleyHandle() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        AbstractObjectInstance pulley = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite sidekick = fallingPlayerAt(0x1900, 0x0660);
        pulley.setServices(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = fallingPlayerAt(0x17CE, 0x062C);

        pulley.update(0, sonic);
        assertTrue(sonic.isObjectControlled());
        assertFalse(sidekick.isObjectControlled());

        NativePositionOps.writeXPosPreserveSubpixel(sidekick, 0x17CE);
        NativePositionOps.writeYPosPreserveSubpixel(sidekick, 0x062C);
        sidekick.setYSpeed((short) 0x200);
        pulley.update(1, sonic);

        assertTrue(sonic.isObjectControlled(),
                "the handle's $30 occupancy byte keeps Player_1 attached");
        assertTrue(sidekick.isObjectControlled(),
                "sub_3E4EC gives the same handle independent $31/$30 occupancy bytes, so Player_2 may join Player_1");
        assertEquals(sonic.getCentreX() & 0xFFFF, sidekick.getCentreX() & 0xFFFF);
        assertEquals(sonic.getCentreY() & 0xFFFF, sidekick.getCentreY() & 0xFFFF);
        assertEquals(0x90, sidekick.getMappingFrame());
    }

    @Test
    void nativeP2ReleasesOnCpuGeneratedLogicalJumpEdge() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        AbstractObjectInstance pulley = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite sidekick = fallingPlayerAt(0x17CE, 0x062C);
        pulley.setServices(new TestObjectServices()
                .withGameState(mock(GameStateManager.class))
                .withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = fallingPlayerAt(0x1900, 0x0660);

        pulley.update(0, sonic);
        assertTrue(sidekick.isObjectControlled());

        sidekick.setDirectionalInputPressed(false, false, false, true);
        sidekick.setJumpInputPressed(false, false);
        sidekick.setLogicalInputState(false, false, false, true, true, true);
        pulley.update(1, sonic);

        assertFalse(sidekick.isObjectControlled(),
                "sub_3E508 reads the Ctrl_2_logical pressed byte, including CPU-generated jump edges");
        assertEquals((short) 0x200, sidekick.getXSpeed());
        assertEquals((short) -0x380, sidekick.getYSpeed());
    }

    @Test
    void hurtPlayerInsidePulleyGrabWindowIsNotCaptured() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);
        player.setHurt(true);

        pulley.update(0, player);

        assertFalse(player.isObjectControlled(),
                "loc_3E682 rejects routine(a1) >= 4 before the pulley grab path writes object_control=3");
        assertFalse(player.isObjectMappingFrameControl(),
                "a hurt player rejected by the ROM routine gate must not receive pulley-owned mapping frames");
        assertEquals((short) 0x200, player.getYSpeed(),
                "rejected pulley grab must leave the player's falling y_vel untouched");
    }

    @Test
    void jumpReleasesPulleyHandleWithRomVelocityAndDirectionalXSpeed() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        assertEquals("MHZPulleyLift", pulley.getName(),
                "SKL slot $06 must construct the MHZ pulley before behavior can be validated");
        pulley.update(0, player);
        int heldY = player.getCentreY() & 0xFFFF;
        player.setDirectionalInputPressed(false, false, true, false);
        player.setJumpInputPressed(true, true);
        player.setLogicalInputState(false, false, true, false, true, true);
        pulley.update(1, player);

        assertFalse(player.isObjectControlled(),
                "button A/B/C release clears object_control in sub_3E508");
        assertFalse(player.isObjectMappingFrameControl(),
                "pulley release returns mapping-frame ownership to the player animation system");
        assertEquals((short) -0x200, player.getXSpeed(),
                "holding left during pulley release writes x_vel=-$200");
        assertEquals((short) -0x380, player.getYSpeed(),
                "pulley release writes y_vel=-$380");
        assertEquals(heldY, player.getCentreY() & 0xFFFF,
                "sub_3E508 changes status and radii without changing the ROM y_pos centre in the later handle slot");
        assertTrue(player.getAir(), "release sets Status_InAir");
        assertTrue(player.isJumping(), "sub_3E508 writes jumping=1 on pulley release");
        assertTrue(player.getRolling(), "release sets Status_Roll");
    }

    @Test
    void heldJumpWithoutFreshPressDoesNotReleasePulleyHandle() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        pulley.update(0, player);
        player.setJumpInputPressed(true, false);
        pulley.update(1, player);

        assertTrue(player.isObjectControlled(),
                "sub_3E508 masks only the low Ctrl_1_logical A/B/C bits; held jump alone does not release");
        assertTrue(player.isObjectMappingFrameControl(),
                "the pulley still owns the player's mapping frame while held jump is ignored");
        assertEquals(0, player.getYSpeed(),
                "held jump must not apply the pulley release y_vel=-$380");
    }

    @Test
    void hurtPlayerIsReleasedFromHeldPulleyWithoutLaunchVelocity() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        pulley.update(0, player);
        player.setHurt(true);
        pulley.update(1, player);

        assertFalse(player.isObjectControlled(),
                "loc_3E58A releases the handle when routine(a1) >= 4, including the hurt routine");
        assertFalse(player.isObjectMappingFrameControl(),
                "the pulley must return mapping-frame ownership when the forced release path runs");
        assertEquals(0, player.getXSpeed(),
                "the non-jump forced release path does not write x_vel");
        assertEquals(0, player.getYSpeed(),
                "the non-jump forced release path does not write y_vel=-$380");
    }

    @Test
    void heldDirectionalInputUpdatesFacingAndRenderFlip() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        player.setDirection(Direction.RIGHT);
        player.setRenderFlips(false, false);
        pulley.update(0, player);

        player.setDirectionalInputPressed(false, false, true, false);
        pulley.update(1, player);

        assertEquals(Direction.LEFT, player.getDirection(),
                "loc_3E5F2 sets Status_Facing when holding left on a pulley handle");
        assertTrue(player.getRenderHFlip(),
                "loc_3E60C mirrors Status_Facing into render_flags bit 0 while held");

        player.setDirectionalInputPressed(false, false, false, true);
        pulley.update(2, player);

        assertEquals(Direction.RIGHT, player.getDirection(),
                "loc_3E600 clears Status_Facing when holding right on a pulley handle");
        assertFalse(player.getRenderHFlip(),
                "loc_3E60C clears render_flags bit 0 when Status_Facing is clear");
    }

    @Test
    void initialHandleExtensionsAreZeroWithNoParentYNudgeOnFrameOne() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x1900, 0x0660);

        pulley.update(0, player);

        String details = pulley.traceDebugDetails();
        assertTrue(details.contains(" leftOffset=0 rightOffset=0 "),
                "Obj_MHZPulleyLift's init writes $34/$36 into field $36(a1) as a PARENT-FIELD-OFFSET "
                        + "selector (loc_3E4B6 reads $36(a0) to pick which parent field to mirror into), not "
                        + "an extension seed; the handle's own $34(a0) offset is left at its implicit "
                        + "RAM-zero default on frame 1");
        assertTrue(details.contains(" parentY=1536 "),
                "loc_3E37E only adjusts parent y_pos on a nonzero average-handle-offset delta; with both "
                        + "handle offsets seeded at 0 there is no delta on frame 1, so parentY (spawn y_pos "
                        + "$600 = 1536) must not nudge");
    }

    @Test
    void heldPlayerPositionRemainsStableAcrossIdleFramesWithNoParentYNudge() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        pulley.update(0, player);
        pulley.update(1, player);
        pulley.update(2, player);

        assertEquals(0x063C, player.getCentreY() & 0xFFFF,
                "with both handle offsets seeded at 0, loc_3E37E never sees a nonzero average-offset "
                        + "delta, so parentY stays put and loc_3E646 snaps the held player to a constant "
                        + "handle y_pos+$42 across idle frames");
    }

    @Test
    void releasingDownRetractsHandleAndMovesHeldPlayerOnFollowingParentPass() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 0xFF, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        pulley.update(0, player);
        player.setDirectionalInputPressed(false, true, false, false);
        for (int frame = 1; frame <= 16; frame++) {
            pulley.update(frame, player);
        }
        assertTrue(pulley.traceDebugDetails().contains(" leftOffset=64 "));
        pulley.update(17, player);

        player.setDirectionalInputPressed(false, false, false, false);
        int heldY = player.getCentreY() & 0xFFFF;
        pulley.update(18, player);

        assertTrue(pulley.traceDebugDetails().contains(" leftOffset=60 "),
                "loc_3E4AA retracts the child handle by four as soon as DOWN is no longer held");

        pulley.update(19, player);

        assertEquals(heldY - 6, player.getCentreY() & 0xFFFF,
                "the next parent slot consumes the child offset delta (-2 parent, -4 handle) before loc_3E646 snaps the player");
    }

    @Test
    void downInputOnlyPlaysPulleyMoveSfxForNonZeroSubtype() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        RecordingServices services = new RecordingServices();
        AbstractObjectInstance pulley = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 0, 0, false, 0));
        pulley.setServices(services);
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        pulley.update(0, player);
        services.clear();

        player.setDirectionalInputPressed(false, true, false, false);
        pulley.update(1, player);

        assertFalse(services.playedSfx(Sonic3kSfx.PULLEY_MOVE.id),
                "loc_3E632 skips sfx_PulleyMove when the parent subtype is zero");

        RecordingServices enabledServices = new RecordingServices();
        AbstractObjectInstance enabledPulley = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        enabledPulley.setServices(enabledServices);
        TestablePlayableSprite enabledPlayer = fallingPlayerAt(0x17CE, 0x062C);

        enabledPulley.update(0, enabledPlayer);
        enabledServices.clear();

        enabledPlayer.setDirectionalInputPressed(false, true, false, false);
        enabledPulley.update(1, enabledPlayer);

        assertTrue(enabledServices.playedSfx(Sonic3kSfx.PULLEY_MOVE.id),
                "loc_3E632 plays sfx_PulleyMove when down is pressed and the parent subtype is nonzero");
    }

    @Test
    void heldDownKeepsHandleExtendedAfterPullCounterIsExhausted() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance pulley = registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        pulley.update(0, player);
        player.setDirectionalInputPressed(false, true, false, false);
        int frame = 1;
        while (!pulley.traceDebugDetails().contains(" remainingPullSteps=0 ") && frame <= 32) {
            pulley.update(frame, player);
            frame++;
        }

        assertTrue(pulley.traceDebugDetails().contains(" remainingPullSteps=0 "),
                "precondition: loc_3E3F8 consumes the single permitted pull step");
        int exhaustedOffset = detailValue(pulley.traceDebugDetails(), "leftOffset");
        assertTrue(exhaustedOffset > 0,
                "the child remains extended when the parent consumes its final pull step");

        pulley.update(frame, player);

        assertEquals(exhaustedOffset, detailValue(pulley.traceDebugDetails(), "leftOffset"),
                "with DOWN held, loc_3E472 sees $3A!=0 then subtype=0 and branches directly to loc_3E4B6; "
                        + "it neither extends nor retracts the handle");
    }

    @Test
    void heldDownInputDoesNotRetriggerPulleyMoveSfxEveryFrame() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        RecordingServices services = new RecordingServices();
        AbstractObjectInstance pulley = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        pulley.setServices(services);
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        pulley.update(0, player);
        services.clear();

        player.setDirectionalInputPressed(false, true, false, false);
        pulley.update(1, player);
        pulley.update(2, player);

        assertEquals(1, services.sfxCount(Sonic3kSfx.PULLEY_MOVE.id),
                "loc_3E632 tests Ctrl_1_logical low-byte down press, not the held down bit");
    }

    @Test
    void downHeldBeforeGrabDoesNotPlayPulleyMoveSfxAfterGrab() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        RecordingServices services = new RecordingServices();
        AbstractObjectInstance pulley = (AbstractObjectInstance) registry.create(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        pulley.setServices(services);
        TestablePlayableSprite player = fallingPlayerAt(0x17CE, 0x062C);

        player.setDirectionalInputPressed(false, true, false, false);
        pulley.update(0, player);
        services.clear();
        pulley.update(1, player);

        assertFalse(services.playedSfx(Sonic3kSfx.PULLEY_MOVE.id),
                "a held down bit that was already active before grab is not a fresh Ctrl_1_logical down press");
    }

    @Test
    void pulleyLiftRendersRomParentRopePulleysAndHandles() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_PULLEY_LIFT)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        MhzPulleyLiftObjectInstance pulley = new MhzPulleyLiftObjectInstance(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        pulley.setServices(new TestObjectServices().withLevelManager(levelManager));

        pulley.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(4, 0x1800, 0x0600, false, false);
        verify(renderer).drawFrameIndex(0, 0x1800, 0x0640, false, false);
        verify(renderer).drawFrameIndex(5, 0x17F0, 0x0678, false, false);
        verify(renderer).drawFrameIndex(6, 0x1810, 0x0678, false, false);
        verify(renderer).drawFrameIndex(3, 0x17CE, 0x05FA, false, false);
        verify(renderer).drawFrameIndex(3, 0x1832, 0x05FA, true, false);
    }

    @Test
    void idleSpawnUsesParentCopyOffsetsBeforeChildHandleOffsets() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_PULLEY_LIFT)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        MhzPulleyLiftObjectInstance pulley = new MhzPulleyLiftObjectInstance(new ObjectSpawn(
                0x1800, 0x0600, MHZ_PULLEY_LIFT, 1, 0, false, 0));
        pulley.setServices(new TestObjectServices().withLevelManager(levelManager));
        TestablePlayableSprite player = fallingPlayerAt(0x1900, 0x0660);

        pulley.update(0, player);
        pulley.appendRenderCommands(new ArrayList<>());

        verify(renderer).drawFrameIndex(4, 0x1800, 0x0600, false, false);
        verify(renderer).drawFrameIndex(0, 0x1800, 0x0640, false, false);
        verify(renderer).drawFrameIndex(5, 0x17F0, 0x0678, false, false);
        verify(renderer).drawFrameIndex(6, 0x1810, 0x0678, false, false);
        verify(renderer).drawFrameIndex(3, 0x17CE, 0x05FA, false, false);
        verify(renderer).drawFrameIndex(3, 0x1832, 0x05FA, true, false);
    }

    private static TestablePlayableSprite fallingPlayerAt(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        player.setYSpeed((short) 0x200);
        player.setXSpeed((short) 0x100);
        player.setGSpeed((short) 0x100);
        player.setAir(true);
        return player;
    }

    private static int detailValue(String details, String key) {
        int start = details.indexOf(key + "=");
        int valueStart = start + key.length() + 1;
        int valueEnd = details.indexOf(' ', valueStart);
        return Integer.parseInt(details.substring(valueStart, valueEnd));
    }

    private static final class RecordingServices extends TestObjectServices {
        private final List<Integer> sfx = new ArrayList<>();

        @Override
        public void playSfx(int soundId) {
            sfx.add(soundId);
        }

        private boolean playedSfx(int soundId) {
            return sfx.contains(soundId);
        }

        private int sfxCount(int soundId) {
            return (int) sfx.stream()
                    .filter(id -> id == soundId)
                    .count();
        }

        private void clear() {
            sfx.clear();
        }
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
