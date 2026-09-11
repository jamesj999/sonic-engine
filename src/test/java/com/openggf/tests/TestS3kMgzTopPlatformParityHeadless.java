package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.BreakableWallObjectInstance;
import com.openggf.game.sonic3k.objects.CollapsingBridgeObjectInstance;
import com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kSpringObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kSpikeObjectInstance;
import com.openggf.level.ChunkDesc;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kMgzTopPlatformParityHeadless {
    // Captured from the MGZ Act 1 debug-overlay repro used by the launcher regression.
    private static final int START_PIXEL_X = 10612;
    private static final int START_PIXEL_Y = 2036;
    private static final short TEST_CROSSING_SPEED = (short) 0x600;

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;
    private static Object oldMainCharacter;
    private static Object oldSidekickCharacter;

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    private record TerrainApproachCandidate(int centreX, int centreY, int clearance) {
    }

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        oldMainCharacter = config.getConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE);
        oldSidekickCharacter = config.getConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MGZ, 0);
    }

    @AfterAll
    static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE,
                oldMainCharacter != null ? oldMainCharacter : "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE,
                oldSidekickCharacter != null ? oldSidekickCharacter : "tails");
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        sprite = (Sonic) fixture.sprite();
        sprite.setCentreX((short) (START_PIXEL_X + (sprite.getWidth() / 2)));
        sprite.setCentreY((short) (START_PIXEL_Y + (sprite.getHeight() / 2)));
        sprite.setPushing(false);
        sprite.setRolling(false);
        sprite.setJumping(false);
        sprite.setDirection(com.openggf.physics.Direction.LEFT);
        sprite.clearWallClingState();
        teleportToReproArea();
        fixture.stepIdleFrames(1);
    }

    @Test
    void ordinaryStandingRider_isNotCarriedByPostMoveHorizontalDelta() {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(START_PIXEL_X, START_PIXEL_Y, 0x5B, 0, 0, false, 0));

        assertTrue(platform.usesPreUpdatePositionForSolidContact(sprite));
        assertEquals(0x18, platform.getBalanceWidthPixels(),
                "Tails edge balance must read Obj_MGZTopPlatform width_pixels");
        assertFalse(platform.carriesRiderOnHorizontalMove(sprite),
                "Obj_MGZTopPlatform moves after SolidObjectFull_1P, so an ordinary rider sees zero X carry");
        assertTrue(platform.usesPreUpdateYForContinuedRide(sprite),
                "The ordinary rider must remain seated on the pre-move surface Y");
    }

    @Test
    void grabbedPlatform_usesTrueObjectControlledOwnership() {
        MGZTopPlatformObjectInstance platform = runUntilGrabbedHoldingLeft();

        assertNotNull(platform, "Expected Sonic to grab the MGZ top platform");
        assertTrue(sprite.isObjectControlled(),
                "MGZ top platform should own the player via objectControlled while grabbed");
        assertTrue(sprite.isObjectControlAllowsCpu(),
                "MGZ top platform carry uses native bit 0, so P2 CPU remains eligible");
        assertTrue(sprite.isObjectControlSuppressesMovement(),
                "MGZ top platform carry should suppress normal movement while grabbed");
        assertFalse(sprite.isTouchResponseSuppressedByObjectControl(),
                "Native bit-0 control leaves ROM touch-response polling active");
        assertTrue(sprite.isWallCling(),
                "MGZ top platform should keep the ROM wall-cling/status-tertiary state while grabbed");
        assertFalse(sprite.isOnObject(),
                "Grabbed player should not remain in ordinary on-object standing state");
    }

    @Test
    void grabInitiation_preservesPlayerVelocity() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0x0B88, 0x0AC3, 0x5B, 0, 0, false, 0));
        Object grabState = newPlayerGrabState();
        short expectedXVel = (short) 0x00DA;
        short expectedYVel = (short) 0xFFE8;
        int expectedXSub = 0xBE00;
        int expectedYSub = 0xB900;
        sprite.setXSpeed(expectedXVel);
        sprite.setYSpeed(expectedYVel);
        sprite.setSubpixelRaw(expectedXSub, expectedYSub);
        sprite.setOnObject(true);
        sprite.setAir(false);

        invokeGrabPlayer(platform, sprite, grabState);

        assertTrue(sprite.isObjectControlled(),
                "ROM loc_34F84 should arm object control during grab initiation");
        assertFalse(sprite.isOnObject(),
                "ROM loc_34F84 should clear Status_OnObj during grab initiation");
        assertTrue(sprite.getAir(),
                "ROM loc_34F84 should set Status_InAir during grab initiation");
        assertEquals(expectedXVel, sprite.getXSpeed(),
                "ROM loc_34F84 should preserve x_vel while entering MGZ top-platform carry");
        assertEquals(expectedYVel, sprite.getYSpeed(),
                "ROM loc_34F84 should preserve y_vel while entering MGZ top-platform carry");
        assertEquals(expectedXSub, sprite.getXSubpixelRaw(),
                "ROM loc_34F84 move.w x_pos should preserve x_sub during grab initiation");
        assertEquals(expectedYSub, sprite.getYSubpixelRaw(),
                "ROM loc_34F84 should not touch y_sub during grab initiation");
        assertEquals((expectedXSub >> 8) & 0xFF, getIntField(grabState, "xSub"),
                "MGZ carry MoveSprite2 state should inherit the preserved x_sub high byte");
        assertEquals((expectedYSub >> 8) & 0xFF, getIntField(grabState, "ySub"),
                "MGZ carry MoveSprite2 state should inherit the preserved y_sub high byte");
    }

    @Test
    void grabbedState_reassertsExpandedRadiusAfterSolidLandingReset() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        sprite.applyStandingRadii(false);

        Method applyGrabbedRadii = MGZTopPlatformObjectInstance.class.getDeclaredMethod(
                "applyGrabbedCollisionRadii", AbstractPlayableSprite.class);
        applyGrabbedRadii.setAccessible(true);
        applyGrabbedRadii.invoke(platform, sprite);

        assertEquals(sprite.getStandYRadius() + 0x18, sprite.getYRadius(),
                "loc_34F84 rewrites the carried collision radius every platform tick");
    }

    @Test
    void freshStandingCheckpoint_grabsAlignedPlayerInSameObjectSlot() {
        int platformX = 0x0B88;
        int platformY = 0x0AC3;
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(platformX, platformY, 0x5B, 0, 0, false, 0));
        sprite.setCentreX((short) platformX);
        sprite.setCentreY((short) (platformY - 0x0C - sprite.getStandYRadius()));
        sprite.setAir(false);
        sprite.setOnObject(true);
        sprite.clearWallClingState();

        platform.onSolidContact(
                sprite, new SolidContact(true, false, false, true, false), 0);

        assertTrue(sprite.isObjectControlled(),
                "loc_34F2A must fall through to the aligned grab check in the contact slot");
        assertTrue(sprite.getAir());
        assertFalse(sprite.isOnObject());
        assertEquals(platformY - 0x0C - sprite.getStandYRadius(), sprite.getCentreY(),
                "same-slot sub_35202 should apply the carried-player post-sync");
    }

    @Test
    void airborneGravity_allowsNativePostAddOvershoot() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        setIntField(platform, "yVel", 0x01FC);
        Method gravity = MGZTopPlatformObjectInstance.class.getDeclaredMethod("applyAirborneGravity");
        gravity.setAccessible(true);

        gravity.invoke(platform);
        assertEquals(0x0204, getIntField(platform, "yVel"),
                "loc_34C88 compares before add and does not clamp the $1FC + 8 result");

        gravity.invoke(platform);
        assertEquals(0x0204, getIntField(platform, "yVel"));
    }

    @Test
    void diagonalAirborneWallRetainsVelocityUntilSteepAngleGate() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        Method applyWallVelocity = MGZTopPlatformObjectInstance.class.getDeclaredMethod(
                "applyAirborneSideWallVelocity", boolean.class, int.class);
        applyWallVelocity.setAccessible(true);
        setIntField(platform, "xVel", 0x0BE8);
        setIntField(platform, "yVel", -0x0111);
        setIntField(platform, "groundVel", 0x0828);

        applyWallVelocity.invoke(platform, true, 0x00);

        assertEquals(0x0BE8, getIntField(platform, "xVel"),
                "loc_3547A should retain x_vel after a shallow diagonal wall correction");
        assertEquals(0x0828, getIntField(platform, "groundVel"),
                "A shallow wall should not transfer y_vel into ground_vel");

        applyWallVelocity.invoke(platform, true, 0x40);

        assertEquals(0, getIntField(platform, "xVel"),
                "A wall passing the ROM's (angle+$30) >= $60 gate should stop x_vel");
        assertEquals(-0x0111, getIntField(platform, "groundVel"),
                "The steep-wall branch should transfer y_vel into ground_vel");
    }

    @Test
    void diagonalAirborneWallProbeUsesPriorSolidTileAngleOnZeroWidthRegress() throws Exception {
        SolidTile priorTile = new SolidTile(0x86, new byte[16], new byte[16], (byte) 0xFC);
        SolidTile originalTile = new SolidTile(0x87, new byte[16], new byte[16], (byte) 0xFF);
        Method createPreviousWallResult = ObjectTerrainUtils.class.getDeclaredMethod(
                "createPreviousWallResult",
                SolidTile.class, ChunkDesc.class, SolidTile.class, ChunkDesc.class,
                byte.class, int.class, boolean.class, boolean.class);
        createPreviousWallResult.setAccessible(true);
        TerrainCheckResult wall = (TerrainCheckResult) createPreviousWallResult.invoke(
                null,
                priorTile, new ChunkDesc(0x86), originalTile, new ChunkDesc(0x87),
                (byte) 0, 0x1B53, false, true);

        assertEquals(-4, wall.distance(),
                "MGZ diagonal approach should regress four pixels from the full tile edge");
        assertEquals(0xFC, wall.angle() & 0xFF,
                "sub_F584 keeps the prior solid tile's angle when its sampled width is zero");
        assertEquals(0x86, wall.tileIndex(),
                "The regress result should identify the prior tile that supplied the angle");
    }

    @Test
    void miniMotionFacingChange_publishesRunAsPreviousAnimation() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        sprite.setDirection(com.openggf.physics.Direction.RIGHT);
        sprite.setPushing(true);
        sprite.getAnimationManager().publishPreviousAnimationId(0);
        Method setDirection = MGZTopPlatformObjectInstance.class.getDeclaredMethod(
                "setMiniMotionDirection", AbstractPlayableSprite.class,
                com.openggf.physics.Direction.class);
        setDirection.setAccessible(true);

        setDirection.invoke(platform, sprite, com.openggf.physics.Direction.LEFT);

        assertFalse(sprite.getPushing(), "native facing change clears Status_Push");
        assertEquals(1, sprite.getAnimationManager().captureRewindState().lastAnimationId(),
                "native mini-motion writes prev_anim=Run when Status_Facing changes");
    }

    @Test
    void jumpRelease_clearsObjectControlledAndWallCling() {
        MGZTopPlatformObjectInstance platform = runUntilGrabbedHoldingLeft();
        assertNotNull(platform, "Expected Sonic to grab the MGZ top platform before jump release");
        assertTrue(sprite.isObjectControlled(),
                "Release regression requires MGZ top platform to start from true object-controlled ownership");
        assertTrue(sprite.isWallCling(),
                "Release regression requires MGZ wall-cling status to be armed before jump release");

        boolean released = false;
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepFrame(false, false, false, false, true);
            if (!sprite.isObjectControlled()) {
                released = true;
                break;
            }
        }

        assertTrue(released, "Expected jump input to release Sonic from the MGZ top platform");
        assertFalse(sprite.isObjectControlled(), "Jump release should clear object-controlled ownership");
        assertFalse(sprite.isObjectControlAllowsCpu(), "Jump release should clear object-control CPU allowance");
        assertFalse(sprite.isObjectControlSuppressesMovement(), "Jump release should clear movement suppression");
        assertFalse(sprite.isTouchResponseSuppressedByObjectControl(),
                "Jump release should clear touch-response suppression");
        assertFalse(sprite.isWallCling(), "Jump release should clear MGZ wall-cling bits");
        assertTrue(sprite.getAir(), "Released player should return to airborne movement");
    }

    @Test
    void grabbedPlayer_hurtPathDoesNotUseGenericLostRingSpawnOrdering() throws Exception {
        MGZTopPlatformObjectInstance platform = runUntilGrabbedHoldingLeft();
        assertNotNull(platform, "Expected Sonic to grab the MGZ top platform before hurt");
        sprite.setRingCount(10);

        invokeSharedTouchHurt(platform);
        fixture.stepIdleFrames(1);

        assertTrue(sprite.isHurt(), "Attached player should still enter hurt state");
        assertFalse(sprite.isObjectControlled(), "Hurt should release MGZ platform ownership");
        assertFalse(sprite.isWallCling(), "Hurt should clear the MGZ wall-cling status");
        assertEquals(10, sprite.getRingCount(),
                "MGZ attached hurt should keep the player's rings while wall-cling is active");
        assertEquals(0, activeLostRingCount(),
                "MGZ attached hurt path should not use the generic pre-hurt lost-ring spawn ordering");
    }

    @Test
    void ordinaryTouchHurtStillUsesGenericLostRingSpawnOrdering() throws Exception {
        sprite.setRingCount(10);

        invokeSharedTouchHurt(null);
        fixture.stepIdleFrames(1);

        assertTrue(sprite.isHurt(), "Ordinary touch hurt should still put the player into hurt");
        assertEquals(0, sprite.getRingCount(),
                "Ordinary touch hurt should still spend the player's rings through the generic spill path");
        assertEquals(10, activeLostRingCount(),
                "Ordinary touch hurt should still spawn lost rings in the shared hurt path");
    }

    @Test
    void wallClingAlone_doesNotOptIntoObjectControlledSolidContacts() {
        Sonic isolated = new Sonic("sonic", (short) 0, (short) 0);
        MGZTopPlatformObjectInstance candidate = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        isolated.setObjectControlled(true);
        isolated.setWallCling(true);

        assertFalse(isolated.allowsSolidContactsWhileObjectControlled(candidate),
                "Generic wall-cling state should not re-enable solid contacts while object-controlled");
    }

    @Test
    void solidContactProjectsGroundMovementBecausePlatformRunsAfterPlayerSlot() {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0x1A60, 0x0A45, 0x5B, 0, 0, false, 0));

        assertTrue(platform.projectsPreMovementGroundXForSolidContact(sprite),
                "Obj_MGZTopPlatform must test the grounded X position already advanced by Obj01");
    }

    @Test
    void mgzCarryController_allowsRomPositiveObjectControlSolids() {
        Sonic isolated = new Sonic("sonic", (short) 0, (short) 0);
        MGZTopPlatformObjectInstance controllingPlatform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        MGZTopPlatformObjectInstance otherPlatform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(32, 0, 0x5B, 0, 0, false, 1));
        BreakableWallObjectInstance mgzWall = new BreakableWallObjectInstance(
                new ObjectSpawn(0, 0, 0x0D, 0, 0, false, 0));
        Sonic3kMonitorObjectInstance monitor = new Sonic3kMonitorObjectInstance(
                new ObjectSpawn(0, 0, 0x01, 0x06, 0, false, 0));
        isolated.setObjectControlled(true);
        isolated.setMgzTopPlatformCarrySolidContactObject(controllingPlatform);

        assertTrue(isolated.allowsSolidContactsWhileObjectControlled(controllingPlatform),
                "MGZ carry should allow solid contacts against the controlling platform instance");
        assertTrue(isolated.allowsSolidContactsWhileObjectControlled(mgzWall),
                "MGZ carry should still allow the MGZ wall checkpoint contact the controller depends on");
        assertTrue(isolated.allowsSolidContactsWhileObjectControlled(monitor),
                "S3K monitor SolidObject_cont accepts the platform's positive object_control bit 0");
        assertTrue(monitor.zeroXSpeedStopsOnLeftSideContact(),
                "S3K SolidObject_cont treats zero velocity as entering its player-left side");
        assertFalse(isolated.allowsSolidContactsWhileObjectControlled(otherPlatform),
                "MGZ carry should not opt the player back into every other solid while object-controlled");
    }

    @Test
    void mgzCarryController_allowsSpringCandidates() {
        Sonic isolated = new Sonic("sonic", (short) 0, (short) 0);
        MGZTopPlatformObjectInstance controllingPlatform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        Sonic3kSpringObjectInstance spring = new Sonic3kSpringObjectInstance(
                new ObjectSpawn(0, 0, 0x07, 0x00, 0x00, false, 0));
        isolated.setObjectControlled(true);
        isolated.setMgzTopPlatformCarrySolidContactObject(controllingPlatform);

        assertTrue(isolated.allowsSolidContactsWhileObjectControlled(spring),
                "MGZ carry should still allow spring solid contacts so the carried spring handoff can run");
        assertTrue(spring.zeroXSpeedStopsOnLeftSideContact(),
                "S3K SolidObject_cont stops zero velocity on a spring's left side");
    }

    @Test
    void mgzCarryController_allowsSpikeCandidates() {
        Sonic isolated = new Sonic("sonic", (short) 0, (short) 0);
        MGZTopPlatformObjectInstance controllingPlatform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        Sonic3kSpikeObjectInstance spikes = new Sonic3kSpikeObjectInstance(
                new ObjectSpawn(0, 0, 0x08, 0x00, 0x00, false, 0));
        isolated.setObjectControlled(true);
        isolated.setMgzTopPlatformCarrySolidContactObject(controllingPlatform);

        assertTrue(isolated.allowsSolidContactsWhileObjectControlled(spikes),
                "Positive object_control bit 0 must not block Obj_Spikes' SolidObject_cont path");
    }

    @Test
    void mgzCarryController_allowsMgzStompBridgeCandidates() throws Exception {
        Sonic isolated = new Sonic("sonic", (short) 0, (short) 0);
        MGZTopPlatformObjectInstance controllingPlatform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        CollapsingBridgeObjectInstance stompBridge = newMgzStompBridge();
        isolated.setObjectControlled(true);
        isolated.setMgzTopPlatformCarrySolidContactObject(controllingPlatform);

        assertTrue(isolated.allowsSolidContactsWhileObjectControlled(stompBridge),
                "MGZ carry should still allow MGZ stomp bridge contacts so collapse-on-impact can run");
    }

    @Test
    void mgzStompBridge_contactWhileCarriedTriggersCollapse() throws Exception {
        CollapsingBridgeObjectInstance stompBridge = newMgzStompBridge();
        sprite.setWallCling(true);
        sprite.setAir(true);
        sprite.setOnObject(false);

        Method performStomp = CollapsingBridgeObjectInstance.class.getDeclaredMethod(
                "performMgzStomp", AbstractPlayableSprite.class);
        performStomp.setAccessible(true);
        performStomp.invoke(stompBridge, sprite);

        assertTrue(getBooleanField(stompBridge, "fragmented"),
                "MGZ stomp bridge should shatter when carried Sonic touches it with wall-cling armed");
        assertEquals(3, getIntField(stompBridge, "state"),
                "MGZ stomp bridge should enter its falling fragment state immediately on stomp contact");
    }

    @Test
    void grabbedCarryMotion_resolvesFloorInsteadOfTunnellingThroughTerrain() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(START_PIXEL_X, START_PIXEL_Y, 0x5B, 0, 0, false, 0));
        Object grabState = newPlayerGrabState();
        setIntField(grabState, "routine", 4);
        setBooleanField(grabState, "grabbed", true);
        playerStates(platform).put(sprite, grabState);

        sprite.setObjectControlled(true);
        sprite.setMgzTopPlatformCarrySolidContactObject(platform);
        sprite.setWallCling(true);
        sprite.setAir(true);
        sprite.setOnObject(false);

        TerrainApproachCandidate candidate = findNearbyFloorApproachCandidate();
        assertNotNull(candidate, "Expected MGZ repro area to provide a nearby floor-approach terrain sample");

        sprite.setCentreX((short) candidate.centreX());
        sprite.setCentreY((short) candidate.centreY());
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed(TEST_CROSSING_SPEED);

        invokeMoveGrabbedPlayer(platform, sprite, grabState);

        int maxResolvedCentreY = candidate.centreY() + candidate.clearance();
        assertTrue(sprite.getCentreY() <= maxResolvedCentreY,
                "Grabbed carry motion should resolve nearby floor contact instead of moving through terrain");
    }

    @Test
    void grabbedCarryMotion_preservesGroundedResultFromLaterSolidSlot() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(START_PIXEL_X, START_PIXEL_Y, 0x5B, 0, 0, false, 0));
        Object grabState = newPlayerGrabState();
        setIntField(grabState, "routine", 4);
        setBooleanField(grabState, "grabbed", true);
        playerStates(platform).put(sprite, grabState);

        sprite.setObjectControlled(true);
        sprite.setMgzTopPlatformCarrySolidContactObject(platform);
        sprite.setWallCling(true);
        sprite.setAir(false);
        sprite.setOnObject(false);
        sprite.setXSpeed((short) 0x100);
        sprite.setYSpeed((short) 0x100);

        invokeMoveGrabbedPlayer(platform, sprite, grabState);

        assertFalse(sprite.getAir(),
                "loc_35070 must preserve a grounded contact written by a permitted earlier solid slot");
    }

    @Test
    void grabbedCarryMotion_keepsFlatAnimationAngleAfterSlopeResolution() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(START_PIXEL_X, START_PIXEL_Y, 0x5B, 0, 0, false, 0));
        Object grabState = newPlayerGrabState();
        setIntField(grabState, "routine", 4);
        setBooleanField(grabState, "grabbed", true);
        playerStates(platform).put(sprite, grabState);

        sprite.setObjectControlled(true);
        sprite.setMgzTopPlatformCarrySolidContactObject(platform);
        sprite.setWallCling(true);
        sprite.setAir(true);
        sprite.setOnObject(false);

        TerrainApproachCandidate candidate = findNearbySlopedFloorApproachCandidate();
        assertNotNull(candidate, "Expected MGZ repro area to provide a nearby sloped floor sample");

        sprite.setCentreX((short) candidate.centreX());
        sprite.setCentreY((short) candidate.centreY());
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed(TEST_CROSSING_SPEED);
        sprite.setAngle((byte) 0x20);

        invokeMoveGrabbedPlayer(platform, sprite, grabState);

        assertEquals(0, sprite.getAngle() & 0xFF,
                "MGZ carry should keep Sonic on flat animation-driving angle semantics after terrain resolution");
    }

    @Test
    void grabbedCarryMotion_resolvesWallInsteadOfTunnellingThroughTerrain() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(START_PIXEL_X, START_PIXEL_Y, 0x5B, 0, 0, false, 0));
        Object grabState = newPlayerGrabState();
        setIntField(grabState, "routine", 4);
        setBooleanField(grabState, "grabbed", true);
        playerStates(platform).put(sprite, grabState);

        sprite.setObjectControlled(true);
        sprite.setMgzTopPlatformCarrySolidContactObject(platform);
        sprite.setWallCling(true);
        sprite.setAir(true);
        sprite.setOnObject(false);

        TerrainApproachCandidate candidate = findNearbyRightWallApproachCandidate();
        assertNotNull(candidate, "Expected MGZ repro area to provide a nearby wall-approach terrain sample");

        sprite.setCentreX((short) candidate.centreX());
        sprite.setCentreY((short) candidate.centreY());
        sprite.setXSpeed(TEST_CROSSING_SPEED);
        sprite.setYSpeed((short) 0);

        invokeMoveGrabbedPlayer(platform, sprite, grabState);

        int maxResolvedCentreX = candidate.centreX() + candidate.clearance();
        assertTrue(sprite.getCentreX() <= maxResolvedCentreX,
                "Grabbed carry motion should resolve nearby wall contact instead of moving through terrain");
    }

    @Test
    void postMotionSnap_preservesPlayerVelocityWhenNotStandingOnObject() throws Exception {
        int platformX = 0x0B88;
        int platformY = 0x0AC3;
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(platformX, platformY, 0x5B, 0, 0, false, 0));
        Object grabState = newPlayerGrabState();
        setIntField(grabState, "routine", 4);
        setBooleanField(grabState, "grabbed", true);
        playerStates(platform).put(sprite, grabState);

        short expectedXVel = (short) 0x00DA;
        short expectedYVel = (short) 0xFFE8;
        sprite.setOnObject(false);
        sprite.setAir(true);
        sprite.setXSpeed(expectedXVel);
        sprite.setYSpeed(expectedYVel);
        sprite.setSubpixelRaw(0xBE00, 0xB900);

        invokeSnapGrabbedPlayer(platform, sprite);

        assertEquals(platformX, sprite.getCentreX(),
                "ROM sub_35202 should snap carried player X to platform X");
        assertEquals(platformY - 0x0C - sprite.getStandYRadius(), sprite.getCentreY(),
                "ROM sub_35202 should snap carried player Y from platform top and default radius");
        assertEquals(expectedXVel, sprite.getXSpeed(),
                "ROM sub_35202 should not clear x_vel after sub_35504 loc_3554E writes it from ground_vel");
        assertEquals(expectedYVel, sprite.getYSpeed(),
                "ROM sub_35202 should not clear y_vel in the non-standing snap branch");
        assertEquals(0xBE00, sprite.getXSubpixelRaw(),
                "ROM sub_35202 move.w x_pos should preserve x_sub");
        assertEquals(0xB900, sprite.getYSubpixelRaw(),
                "ROM sub_35202 move.w y_pos should preserve y_sub");
    }

    @Test
    void grabbedPlayerMove_updatesVisibleSpriteSubpixels() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0x0B88, 0x0AC3, 0x5B, 0, 0, false, 0));
        Object grabState = newPlayerGrabState();
        setIntField(grabState, "xSub", 0xFF);
        setIntField(grabState, "ySub", 0xFE);
        int startX = 0x0B88;
        int startY = 0x0AA4;
        sprite.setCentreX((short) startX);
        sprite.setCentreY((short) startY);
        sprite.setSubpixelRaw(0xFF00, 0xFE00);
        sprite.setXSpeed((short) 0x0002);
        sprite.setYSpeed((short) 0x0004);

        invokeMoveGrabbedPlayer(platform, sprite, grabState, false);

        assertEquals(startX + 1, sprite.getCentreX(),
                "ROM MoveSprite2 should carry x_sub overflow into x_pos");
        assertEquals(startY + 1, sprite.getCentreY(),
                "ROM MoveSprite2 should carry y_sub overflow into y_pos");
        assertEquals(0x0100, sprite.getXSubpixelRaw(),
                "MGZ carry should publish MoveSprite2 x_sub back to the sprite state");
        assertEquals(0x0200, sprite.getYSubpixelRaw(),
                "MGZ carry should publish MoveSprite2 y_sub back to the sprite state");
    }

    @Test
    void positiveCenteringKickUsesRomNegThenArithmeticShift() throws Exception {
        int platformX = 0x0B88;
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(platformX, 0x0AC3, 0x5B, 0, 0, false, 0));
        setIntField(platform, "xVel", 0);
        setIntField(platform, "yVel", 0);
        sprite.setCentreX((short) (platformX + 1));

        invokeApplyCenteringOrLateralLaunch(platform, sprite);

        assertEquals(4, getIntField(platform, "xVel"),
                "ROM loc_35130 should add dx*4 to platform x_vel");
        assertEquals(-9, getIntField(platform, "yVel"),
                "ROM loc_35148 neg.w/asr.w should make x_vel=4 contribute -1 to y_vel");
        assertTrue(getBooleanField(platform, "airborne"),
                "ROM loc_35168 should set the airborne status bit after the centering kick");
    }

    @Test
    void positiveCenteringKickAllowsRomOvershootPastMinus100Band() throws Exception {
        int platformX = 0x0B88;
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(platformX, 0x0AC3, 0x5B, 0, 0, false, 0));
        setIntField(platform, "xVel", 0x00F0);
        setIntField(platform, "yVel", -0x00F0);
        sprite.setCentreX((short) (platformX + 2));

        invokeApplyCenteringOrLateralLaunch(platform, sprite);

        assertEquals(0x00F8, getIntField(platform, "xVel"),
                "ROM loc_35130 should update x_vel before calculating the vertical kick");
        assertEquals(-0x0108, getIntField(platform, "yVel"),
                "ROM loc_35148 compares against -$100 before add.w d0,y_vel and does not clamp after it");
    }

    @Test
    void waypointApproachUsesRawDestinationBeforeArcCentreAdjustment() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0x1768, 0x06C4, 0x5B, 0, 0, false, 0));
        setIntField(platform, "groundVel", -0x0C00);
        int[] waypoint = {
                0,
                0x1760, 0x06C5, 0x8181, 0x16C0, 0x06A6, 0x0140, 0x1760, 0x0546
        };

        Method activateArc = MGZTopPlatformObjectInstance.class
                .getDeclaredMethod("activateArc", int[].class, int.class);
        activateArc.setAccessible(true);
        activateArc.invoke(platform, waypoint, 1);

        assertEquals(-0x0C00, getIntField(platform, "xVel"),
                "sub_35666 should calculate the linear approach against d5's raw destination Y");
        assertEquals(-0x0224, getIntField(platform, "yVel"),
                "The raw destination should produce the ROM's shallow initial MGZ2 approach slope");
        assertEquals(0x0566, getIntField(platform, "homeY"),
                "Only the stored arc centre should receive the waypoint delta-Y adjustment");
    }

    @Test
    void releasedFlight_clearsOccupiedSecondaryRiderStandingState() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        Sonic mainPlayer = new Sonic("sonic", (short) 0, (short) 0);
        Tails sidekick = new Tails("tails", (short) 0, (short) 0);
        mainPlayer.setObjectControlled(true);
        mainPlayer.setMgzTopPlatformCarrySolidContactObject(platform);
        mainPlayer.setWallCling(true);
        sidekick.setOnObject(true);

        Object mainState = newPlayerGrabState();
        setIntField(mainState, "routine", 4);
        setBooleanField(mainState, "grabbed", true);
        setIntField(mainState, "entrySideBias", 0x0F);

        Object sidekickState = newPlayerGrabState();
        setIntField(sidekickState, "routine", 2);
        setBooleanField(sidekickState, "standingNow", true);
        setBooleanField(sidekickState, "grabbed", false);
        setIntField(sidekickState, "entrySideBias", 0x0F);

        Map<Object, Object> playerStates = playerStates(platform);
        playerStates.put(mainPlayer, mainState);
        playerStates.put(sidekick, sidekickState);

        Method enterReleasedFlight = MGZTopPlatformObjectInstance.class.getDeclaredMethod("enterReleasedFlight");
        enterReleasedFlight.setAccessible(true);
        enterReleasedFlight.invoke(platform);

        assertFalse(mainPlayer.isObjectControlled(),
                "Released-flight handoff should drop main-player object control");
        assertFalse(mainPlayer.isWallCling(),
                "Released-flight handoff should clear the MGZ wall-cling bits");
        assertFalse(mainPlayer.allowsSolidContactsWhileObjectControlled(platform),
                "Released-flight handoff should clear the explicit MGZ carry solid-contact seam");
        assertFalse(sidekick.isOnObject(),
                "Occupied secondary riders should be detached from ordinary standing state");
        assertEquals(6, getIntField(mainState, "routine"),
                "Main player should advance to the released slot state");
        assertEquals(6, getIntField(sidekickState, "routine"),
                "Occupied secondary rider should advance to the released slot state");
        assertFalse(getBooleanField(mainState, "grabbed"),
                "Released-flight handoff should clear main-player grabbed state");
        assertFalse(getBooleanField(sidekickState, "grabbed"),
                "Released-flight handoff should clear occupied secondary-rider grabbed state");
        assertEquals(0, getIntField(mainState, "entrySideBias"),
                "Released-flight handoff should clear main-player entry bias");
        assertEquals(0, getIntField(sidekickState, "entrySideBias"),
                "Released-flight handoff should clear secondary-rider entry bias");
    }

    @Test
    void releasedFlight_forcesAndPostSyncsUntouchedSecondarySlot() throws Exception {
        int platformX = 0x1128;
        int platformY = 0x08B0;
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(platformX, platformY, 0x5B, 0, 0, false, 0));
        Sonic mainPlayer = new Sonic("sonic", (short) platformX, (short) 0x0884);
        Tails sidekick = new Tails("tails", (short) 0x0B65, (short) 0x0AC2);

        Object mainState = newPlayerGrabState();
        setIntField(mainState, "routine", 4);
        setBooleanField(mainState, "grabbed", true);
        Object sidekickState = newPlayerGrabState();
        setIntField(sidekickState, "routine", 0);

        Map<Object, Object> playerStates = playerStates(platform);
        playerStates.put(mainPlayer, mainState);
        playerStates.put(sidekick, sidekickState);

        Method enterReleasedFlight = MGZTopPlatformObjectInstance.class.getDeclaredMethod("enterReleasedFlight");
        enterReleasedFlight.setAccessible(true);
        enterReleasedFlight.invoke(platform);

        assertEquals(6, getIntField(sidekickState, "routine"),
                "ROM sub_3519A must force the untouched P2 slot to state 6");

        Method snapGrabbedPlayer = MGZTopPlatformObjectInstance.class
                .getDeclaredMethod("snapGrabbedPlayer", AbstractPlayableSprite.class);
        snapGrabbedPlayer.setAccessible(true);
        snapGrabbedPlayer.invoke(platform, sidekick);

        assertEquals(platformX, sidekick.getCentreX(),
                "Same-frame sub_35202 should snap an unseated released P2 to platform X");
        assertEquals(platformY - 0x0C - sidekick.getStandYRadius(), sidekick.getCentreY(),
                "Same-frame sub_35202 should snap an unseated released P2 above the platform");
    }

    @Test
    void destroyedPlatform_clearsMgzCarryOwnershipSeam() throws Exception {
        MGZTopPlatformObjectInstance platform = new MGZTopPlatformObjectInstance(
                new ObjectSpawn(0, 0, 0x5B, 0, 0, false, 0));
        Sonic carriedPlayer = new Sonic("sonic", (short) 0, (short) 0);
        carriedPlayer.setObjectControlled(true);
        carriedPlayer.setMgzTopPlatformCarrySolidContactObject(platform);
        carriedPlayer.setWallCling(true);

        Object carriedState = newPlayerGrabState();
        setIntField(carriedState, "routine", 4);
        setBooleanField(carriedState, "grabbed", true);
        setIntField(carriedState, "entrySideBias", 0x0F);
        playerStates(platform).put(carriedPlayer, carriedState);

        platform.setDestroyed(true);

        assertFalse(carriedPlayer.isObjectControlled(),
                "Destroy cleanup should clear object-control ownership");
        assertFalse(carriedPlayer.isMgzTopPlatformCarryOwnedBy(platform),
                "Destroy cleanup should clear the explicit MGZ carry solid-contact owner");
        assertFalse(carriedPlayer.isWallCling(),
                "Destroy cleanup should clear MGZ wall-cling state");
    }

    private MGZTopPlatformObjectInstance runUntilGrabbedHoldingLeft() {
        for (int frame = 0; frame < 120; frame++) {
            fixture.stepFrame(false, false, true, false, false);
            MGZTopPlatformObjectInstance platform = findGrabbedPlatform();
            if (platform != null) {
                return platform;
            }
        }
        return null;
    }

    private MGZTopPlatformObjectInstance findGrabbedPlatform() {
        for (ObjectInstance obj : GameServices.level().getObjectManager().getActiveObjects()) {
            if (obj instanceof MGZTopPlatformObjectInstance platform && platform.isPlayerGrabbed(sprite)) {
                return platform;
            }
        }
        return null;
    }

    private void teleportToReproArea() {
        fixture.camera().updatePosition(true);
        GameServices.level().postCameraObjectPlacementSync();
        GameServices.level().getObjectManager().reset(fixture.camera().getX());
    }

    private TerrainApproachCandidate findNearbyFloorApproachCandidate() {
        int minX = START_PIXEL_X - 0x80;
        int maxX = START_PIXEL_X + 0x80;
        int minY = START_PIXEL_Y - 0x80;
        int maxY = START_PIXEL_Y + 0x80;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, sprite.getYRadius());
                if (!floor.foundSurface()) {
                    continue;
                }
                if (floor.distance() <= 0 || floor.distance() >= 6) {
                    continue;
                }
                return new TerrainApproachCandidate(x, y, floor.distance());
            }
        }
        return null;
    }

    private TerrainApproachCandidate findNearbySlopedFloorApproachCandidate() {
        int minX = START_PIXEL_X - 0x80;
        int maxX = START_PIXEL_X + 0x80;
        int minY = START_PIXEL_Y - 0x80;
        int maxY = START_PIXEL_Y + 0x80;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, sprite.getYRadius());
                if (!floor.foundSurface()) {
                    continue;
                }
                int angle = floor.angle() & 0xFF;
                if ((angle & 0x01) != 0 || angle == 0) {
                    continue;
                }
                if (floor.distance() <= 0 || floor.distance() >= 6) {
                    continue;
                }
                return new TerrainApproachCandidate(x, y, floor.distance());
            }
        }
        return null;
    }

    private TerrainApproachCandidate findNearbyRightWallApproachCandidate() {
        int minX = START_PIXEL_X - 0x80;
        int maxX = START_PIXEL_X + 0x80;
        int minY = START_PIXEL_Y - 0x80;
        int maxY = START_PIXEL_Y + 0x80;
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                TerrainCheckResult wall = ObjectTerrainUtils.checkRightWallDist(x + sprite.getXRadius(), y);
                if (!wall.foundSurface()) {
                    continue;
                }
                if (wall.distance() <= 0 || wall.distance() >= 6) {
                    continue;
                }
                return new TerrainApproachCandidate(x, y, wall.distance());
            }
        }
        return null;
    }

    private static Object newPlayerGrabState() throws Exception {
        Constructor<?> ctor = Class
                .forName("com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance$PlayerGrabState")
                .getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static CollapsingBridgeObjectInstance newMgzStompBridge() throws Exception {
        CollapsingBridgeObjectInstance bridge = new CollapsingBridgeObjectInstance(
                new ObjectSpawn(0, 0, 0x0F, 0x20, 0x00, false, 0));
        Method initMgz = CollapsingBridgeObjectInstance.class.getDeclaredMethod("initMGZ", int.class);
        initMgz.setAccessible(true);
        initMgz.invoke(bridge, 0x20);
        return bridge;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> playerStates(MGZTopPlatformObjectInstance platform) throws Exception {
        Field field = MGZTopPlatformObjectInstance.class.getDeclaredField("playerStates");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(platform);
    }

    private static int getIntField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static boolean getBooleanField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void invokeMoveGrabbedPlayer(MGZTopPlatformObjectInstance platform,
                                                Sonic player,
                                                Object playerState) throws Exception {
        invokeMoveGrabbedPlayer(platform, player, playerState, true);
    }

    private static void invokeMoveGrabbedPlayer(MGZTopPlatformObjectInstance platform,
                                                Sonic player,
                                                Object playerState,
                                                boolean resolveTerrainAfterMove) throws Exception {
        Method moveGrabbedPlayer = MGZTopPlatformObjectInstance.class.getDeclaredMethod(
                "moveGrabbedPlayer",
                com.openggf.sprites.playable.AbstractPlayableSprite.class,
                Class.forName("com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance$PlayerGrabState"),
                boolean.class);
        moveGrabbedPlayer.setAccessible(true);
        moveGrabbedPlayer.invoke(platform, player, playerState, resolveTerrainAfterMove);
    }

    private static void invokeGrabPlayer(MGZTopPlatformObjectInstance platform,
                                         Sonic player,
                                         Object playerState) throws Exception {
        Method grabPlayer = MGZTopPlatformObjectInstance.class.getDeclaredMethod(
                "grabPlayer",
                com.openggf.sprites.playable.AbstractPlayableSprite.class,
                Class.forName("com.openggf.game.sonic3k.objects.MGZTopPlatformObjectInstance$PlayerGrabState"));
        grabPlayer.setAccessible(true);
        grabPlayer.invoke(platform, player, playerState);
    }

    private static void invokeSnapGrabbedPlayer(MGZTopPlatformObjectInstance platform,
                                                Sonic player) throws Exception {
        Method snapGrabbedPlayer = MGZTopPlatformObjectInstance.class.getDeclaredMethod(
                "snapGrabbedPlayer",
                com.openggf.sprites.playable.AbstractPlayableSprite.class);
        snapGrabbedPlayer.setAccessible(true);
        snapGrabbedPlayer.invoke(platform, player);
    }

    private static void invokeApplyCenteringOrLateralLaunch(MGZTopPlatformObjectInstance platform,
                                                            Sonic player) throws Exception {
        Method applyCenteringOrLateralLaunch = MGZTopPlatformObjectInstance.class.getDeclaredMethod(
                "applyCenteringOrLateralLaunch",
                com.openggf.sprites.playable.AbstractPlayableSprite.class);
        applyCenteringOrLateralLaunch.setAccessible(true);
        applyCenteringOrLateralLaunch.invoke(platform, player);
    }

    private void invokeSharedTouchHurt(ObjectInstance source) throws Exception {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        Field touchResponsesField = ObjectManager.class.getDeclaredField("touchResponses");
        touchResponsesField.setAccessible(true);
        Object touchResponses = touchResponsesField.get(objectManager);
        assertNotNull(touchResponses, "Expected ObjectManager touch responses to be available");

        Method applyHurt = touchResponses.getClass()
                .getDeclaredMethod("applyHurt",
                        com.openggf.game.PlayableEntity.class,
                        ObjectInstance.class,
                        TouchResponseResult.class);
        applyHurt.setAccessible(true);
        applyHurt.invoke(touchResponses, sprite, source, null);
    }

    private int activeLostRingCount() throws Exception {
        Object ringManager = GameServices.level().getRingManager();
        assertNotNull(ringManager, "Expected level ring manager to be available");

        Field lostRingsField = ringManager.getClass().getDeclaredField("lostRings");
        lostRingsField.setAccessible(true);
        Object lostRings = lostRingsField.get(ringManager);

        Field activeRingCountField = lostRings.getClass().getDeclaredField("activeRingCount");
        activeRingCountField.setAccessible(true);
        return activeRingCountField.getInt(lostRings);
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
