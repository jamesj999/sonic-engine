package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance;
import com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss2Instance;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeState;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ResultsHardwareTimingFixture;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestLbzFinalBoss1Instance {
    private static final int OBJ_LBZ_FINAL_BOSS_1 = 0xCA;

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
    }

    @AfterEach
    void resetObjectCameraBounds() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @Test
    void initSetsRomHpCollisionTimerArtAndChildren() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);

        boss.update(0, null);

        assertEquals(9, boss.getCollisionProperty(), "loc_729DE writes collision_property(a0)=9");
        assertEquals(0x0F, boss.getCollisionFlags(), "ObjDat_LBZFinalBoss1 collision_flags is raw $0F");
        assertTrue(boss.usesCurrentTouchResponseState(),
                "sub_734FA publishes the ship's post-move position to Collision_response_list");
        assertEquals(0x7F, boss.getActivationTimer(), "Sonic/Tails branch arms a $7F wait before activation");
        assertEquals(0x0C, boss.getMappingFrame(), "Robotnik ship body starts on frame $0C");
        assertEquals(Sonic3kObjectArtKeys.ROBOTNIK_SHIP, boss.getBodyArtKey());
        assertEquals(Sonic3kObjectArtKeys.LBZ_FINAL_BOSS_1, boss.getTurretArtKey());
        assertEquals(1, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.ROBOTNIK_HEAD).size());
        assertEquals(1, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.TOP_ATTACHMENT).size());
        assertEquals(3, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.TURRET_SEGMENT).size());
        assertTrue(firstBossChild(boss, LbzFinalBoss1Instance.ChildKind.TURRET_SEGMENT)
                        .usesCurrentTouchResponseState(),
                "boss children publish their refreshed post-move coordinates");
        assertEquals(4, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.LASER_HEAD).size(),
                "ChildObjDat_737BA is reached by both loc_7308E and loc_730F8, so top and middle segments each get a laser-head pair");
        assertEquals(1, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.ORBITING_POD).size());
        assertEquals(2, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.GUN_POD).size());
        assertEquals(-1, firstBossChild(boss, LbzFinalBoss1Instance.ChildKind.TURRET_SEGMENT).paletteOverrideForTest(),
                "ObjDat3_736D8/736E4 use make_art_tile(ArtTile_LBZFinalBoss1,1,1); keep platform/turret art on line 1");
        assertEquals(0, firstBossChild(boss, LbzFinalBoss1Instance.ChildKind.GUN_POD).paletteOverrideForTest(),
                "word_736F0 uses make_art_tile(ArtTile_LBZFinalBoss1,0,1) for the thruster/gun pods");
        // ROM: boss music arrives through Obj_Song_Fade_Transition, not a direct play.
        assertEquals(1, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.MUSIC_FADE).size());
        assertTrue(services.musicIds.isEmpty(),
                "loc_729DE spawns a fade-transition object instead of playing mus_EndBoss directly");
    }

    @Test
    void activationAfterTimerStartsVerticalShuttleAndLoadsPalette() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);

        boss.update(0, null);
        for (int frame = 1; frame <= 0x80; frame++) {
            boss.update(frame, null);
        }

        assertEquals(4, boss.getRoutine(), "loc_72A5A switches routine to $04 after Obj_Wait");
        assertEquals(-0x100, boss.getYVelocity(), "activation sets y_vel=-$100");
        assertTrue(boss.isFinalBossPaletteLoaded(), "Pal_LBZFinalBoss1 must be loaded to line 1 on activation");
    }

    @Test
    void robotnikHeadFacesInwardAfterRandomSideReposition() throws Exception {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.activateForTest();
        LbzFinalBoss1Instance.RobotnikHeadChild head = assertInstanceOf(
                LbzFinalBoss1Instance.RobotnikHeadChild.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.ROBOTNIK_HEAD).get(0));

        boss.forceHitCountForTest(2);
        boss.setCentreYForTest(services.cameraY() - 0xB0);
        services.rng().setSeed(0x10001); // Odd post-swap bit: word_72AE8+$2 => camera.x+$30.
        boss.update(1, null);
        head.update(1, null);

        assertEquals(services.camera.getX() + 0x30, boss.getCentreX(),
                "loc_72AAA places the odd-random pass on the left side of the arena");
        assertTrue(hFlipForTest(head),
                "Robotnik head must inherit render_flags bit0 and face right/inward on the left side");

        boss.setCentreYForTest(services.cameraY() + 0x118);
        services.rng().setSeed(1); // Even post-swap bit: word_72AE8 => camera.x+$110.
        boss.update(2, null);
        head.update(2, null);

        assertEquals(services.camera.getX() + 0x110, boss.getCentreX(),
                "loc_72AAA places the even-random pass on the right side of the arena");
        assertFalse(hFlipForTest(head),
                "Robotnik head must face left/inward on the right side");
    }

    @Test
    void laserHeadsStrobeFiringNotchAndSpawnMuzzleForCurrentDirection() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.activateForTest();
        boss.setRenderXFlipForTest(false);
        LbzFinalBoss1Instance.LaserHeadChild head = boss.childrenOfKindForTest(
                        LbzFinalBoss1Instance.ChildKind.LASER_HEAD).stream()
                .filter(LbzFinalBoss1Instance.LaserHeadChild.class::isInstance)
                .map(LbzFinalBoss1Instance.LaserHeadChild.class::cast)
                .findFirst()
                .orElseThrow();

        // Step 8 -> 9: frame $04 with table bit7 clear, matching the unflipped boss.
        head.forceArcStepForTest(8);
        head.forceStepTimerExpiredForTest();
        head.update(1, null);

        assertTrue(boss.isLaserFiringNotchSet(), "frame $04 sets $38 bit3 so routine $04 can reposition");
        assertEquals(1, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.MUZZLE_LASER).size(),
                "matching direction at the firing notch creates loc_7321A muzzle child");
        LbzFinalBoss1Instance.MuzzleLaserChild muzzle = assertInstanceOf(
                LbzFinalBoss1Instance.MuzzleLaserChild.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.MUZZLE_LASER).get(0));
        assertEquals(0, muzzle.paletteOverrideForTest(),
                "word_73704 uses make_art_tile(ArtTile_LBZFinalBoss1,0,1) for the muzzle/laser");

        for (int frame = 4; frame < 120
                && boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.LASER_TRAIL).isEmpty(); frame++) {
            muzzle.update(frame, null);
        }
        assertTrue(muzzle.isFiredForTest(), "muzzle should reach loc_732BA and fire the beam");
        assertEquals(0, firstBossChild(boss, LbzFinalBoss1Instance.ChildKind.LASER_TRAIL).paletteOverrideForTest(),
                "word_7370C is the beam trail child and must match the laser palette");

        // Step 0 -> 1: frame $04 with table bit7 set — notch strobes but the
        // direction mismatch (sub_733FC d2 == 0) spawns no muzzle.
        head.forceArcStepForTest(0);
        head.forceStepTimerExpiredForTest();
        head.update(2, null);

        assertTrue(boss.isLaserFiringNotchSet(), "frame $04 always strobes $38 bit3");
        assertEquals(1, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.MUZZLE_LASER).size(),
                "mismatched head direction must not spawn a second muzzle");

        head.forceArcStepForTest(1);
        head.forceStepTimerExpiredForTest();
        head.update(3, null);

        assertFalse(boss.isLaserFiringNotchSet(), "non-notch arc steps clear $38 bit3");
    }

    @Test
    void hitsAtHpFiveAndOneDetachSegmentsAndEnterRecoilHoldPath() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.activateForTest();

        boss.forceHitCountForTest(6);
        hitBoss(boss, null);

        assertEquals(5, boss.getCollisionProperty());
        assertTrue(boss.isDetachFlagSetForTest(0), "HP 5 sets $38 bit0 — the TOP segment detaches");
        assertEquals(8, boss.getRoutine(), "detach threshold enters routine $08 recoil wait");
        assertEquals(0x0F, boss.getRecoilTimer());
        assertEquals(2, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.HIT_SPARK).size());

        boss.finishHitFlashForTest();
        boss.forceHitCountForTest(2);
        hitBoss(boss, null);

        assertEquals(1, boss.getCollisionProperty());
        assertTrue(boss.isDetachFlagSetForTest(1), "HP 1 sets $38 bit1 — the MID segment detaches");
        assertFalse(boss.isDetachFlagSetForTest(2), "the bottom segment only detaches at defeat");
        assertTrue(boss.isLaserFiringNotchSet(), "HP 1 also forces the aggressive reposition bit");
    }

    @Test
    void detachedTopSegmentStillLetsLaserHeadStrobeFinalFiringNotch() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.activateForTest();
        boss.setRenderXFlipForTest(false);
        LbzFinalBoss1Instance.TurretSegmentChild topSegment = assertInstanceOf(
                LbzFinalBoss1Instance.TurretSegmentChild.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.TURRET_SEGMENT).get(0));
        LbzFinalBoss1Instance.LaserHeadChild head = assertInstanceOf(
                LbzFinalBoss1Instance.LaserHeadChild.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.LASER_HEAD).get(0));

        head.forceArcStepForTest(8);
        head.forceStepTimerExpiredForTest();
        boss.forceHitCountForTest(6);
        hitBoss(boss, null);
        topSegment.update(1, null);

        assertTrue(topSegment.isDestroyed(), "HP 5 detaches the top segment before its laser-head children update.");
        assertFalse(boss.isLaserFiringNotchSet(), "The regression setup needs the boss movement gate bit clear.");

        head.update(2, null);

        assertTrue(boss.isLaserFiringNotchSet(),
                "ROM loc_731F4 runs sub_733FC before Child_Draw_Sprite_FlickerMove reacts to the detached parent, "
                        + "so the final frame-$04 strobe can still set $38 bit3.");
        assertEquals(1, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.MUZZLE_LASER).size(),
                "The final strobe should still create the loc_7321A muzzle child before the head becomes debris.");
    }

    @Test
    void hitSpeedQuirkDoublesInRangeAndLeavesOutOfRangeVelocityUnchanged() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.activateForTest();

        // In range: y_vel*2 within ±$800 is applied.
        boss.forceYVelocityForTest(0x0300);
        boss.forceHitCountForTest(7);
        hitBoss(boss, null);

        assertEquals(0x0600, boss.getYVelocity(),
                "sub_734FA doubles y_vel when the doubled value stays within ±$800");

        // Out of range: the ROM skips the write — y_vel keeps its original value.
        boss.finishHitFlashForTest();
        boss.forceYVelocityForTest(0x0600);
        boss.forceHitCountForTest(7);
        hitBoss(boss, null);

        assertEquals(0x0600, boss.getYVelocity(),
                "sub_734FA leaves y_vel unchanged when y_vel*2 exceeds +$800 (no clamping)");

        boss.finishHitFlashForTest();
        boss.forceYVelocityForTest(-0x0600);
        boss.forceHitCountForTest(7);
        hitBoss(boss, null);

        assertEquals(-0x0600, boss.getYVelocity(),
                "sub_734FA leaves y_vel unchanged when y_vel*2 exceeds -$800 (no clamping)");
    }

    @Test
    void recoilWaitsSixteenFramesThenDropsForExactlyFiveFramesBeforeHold() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.activateForTest();
        boss.forceHitCountForTest(6);
        hitBoss(boss, null);
        int startY = boss.getCentreY();

        // ROM loc_72AEE: $40 = $F decrements to -1 — sixteen wait frames.
        for (int frame = 0; frame < 0x0F; frame++) {
            boss.update(frame, null);
            assertEquals(8, boss.getRoutine(), "routine $08 must consume sixteen wait frames");
            assertEquals(startY, boss.getCentreY(), "routine $08 wait frames must not drop the boss");
        }
        boss.update(0x0F, null);
        assertEquals(0x0A, boss.getRoutine(), "the sixteenth wait frame arms routine $0A");
        assertEquals(startY, boss.getCentreY(), "the transition frame itself does not drop yet");

        // ROM loc_72B04: $40 = 4 — y += 8 on every drop frame including the
        // one where the counter goes negative (five drops total).
        for (int frame = 0; frame < 5; frame++) {
            boss.update(0x20 + frame, null);
            assertEquals(startY + ((frame + 1) * 8), boss.getCentreY(),
                    "routine $0A adds y+=8 once per drop frame");
            if (frame < 4) {
                assertEquals(0x0A, boss.getRoutine(), "routine $0A owns the first four visible drop frames");
            }
        }

        assertEquals(6, boss.getRoutine(), "after five drop frames the boss returns to routine $06 hold");
        assertEquals(startY + 0x28, boss.getCentreY());
    }

    @Test
    void sonicDefeatSinksThenSpawnsResultsWithoutFinalBoss2Handoff() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.activateForTest();

        boss.forceHitCountForTest(1);
        hitBoss(boss, null);
        boss.setCentreYForTest(services.cameraY() + 0x140);
        boss.setPlayersReadyForResultsForTest(true);
        // Sink bottom reached: loc_72B34 arms the $3F wait + Ctrl_2 lock.
        boss.update(10, null);
        assertEquals(LbzFinalBoss1Instance.FinalePhase.WAIT_PLAYER_READY, boss.getFinalePhase());
        boss.forceFinaleTimerForTest(0);
        boss.update(11, null);

        assertEquals(LbzFinalBoss1Instance.FinalePhase.WAIT_RESULTS_COMPLETE, boss.getFinalePhase());
        assertTrue(boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.RESULTS_SCREEN).stream()
                .anyMatch(S3kResultsScreenObjectInstance.class::isInstance));
        assertFalse(boss.spawnedClassNamesForTest().contains("Obj_LBZFinalBoss2"),
                "Sonic/Tails route must not enter the Knuckles handoff branch");
    }

    @Test
    void sonicFinaleWithRealGroundedPlayerAppliesEndingPoseAndClearsEndOfLevelFlag() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        services.gameState.setEndOfLevelFlag(true);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x44A0, (short) 0x0740);
        player.setDead(false);
        player.setAirForTest(false);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.activateForTest();
        boss.forceHitCountForTest(1);
        hitBoss(boss, player);
        boss.setCentreYForTest(services.cameraY() + 0x140);

        boss.update(10, player);
        boss.forceFinaleTimerForTest(0);
        boss.update(11, player);

        assertEquals(LbzFinalBoss1Instance.FinalePhase.WAIT_RESULTS_COMPLETE, boss.getFinalePhase());
        assertTrue(player.isObjectControlled(), "Set_PlayerEndingPose applies native object_control ownership");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "Set_PlayerEndingPose freezes player movement while results spawn");
        assertEquals(Sonic3kAnimationIds.VICTORY.id(), player.getForcedAnimationId(),
                "Sonic/Tails finale should force the victory animation");
        assertEquals(0, player.getForcedInputMask(), "ending pose clears scripted input");
        assertFalse(services.gameState.isEndOfLevelFlag(), "Obj_LBZFinalBoss1 clears End_of_level_flag before results");
        assertTrue(boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.RESULTS_SCREEN).stream()
                .anyMatch(S3kResultsScreenObjectInstance.class::isInstance));
    }

    @Test
    void knucklesDefeatRisesToCameraMinus50ThenSpawnsFinalBoss2Placeholder() {
        HarnessServices services = new HarnessServices(PlayerCharacter.KNUCKLES);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.setCentreYForTest(services.cameraY() - 0x4E);

        boss.forceHitCountForTest(1);
        hitBoss(boss, null);
        boss.update(1, null);

        assertEquals(LbzFinalBoss1Instance.FinalePhase.KNUCKLES_HANDOFF, boss.getFinalePhase(),
                "loc_735FC switches FinalBoss1 to loc_73054 instead of the Sonic/Tails sink path");
        assertEquals(services.cameraY() - 0x4F, boss.getCentreY(),
                "loc_73054 decrements y_pos by 1 while still at or below Camera_Y_pos-$50");
        assertFalse(boss.isDestroyed(), "FinalBoss1 stays visible during the upward handoff travel");

        boss.update(2, null);
        assertEquals(services.cameraY() - 0x50, boss.getCentreY(),
                "the equality frame is still drawn before the ROM's blo branch takes the handoff");
        assertTrue(boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.FINAL_BOSS_2_HANDOFF).isEmpty());

        boss.update(3, null);

        assertEquals(services.cameraY() - 0x50, boss.getCentreY(),
                "loc_73070 snaps y_pos to Camera_Y_pos-$50 before allocating Obj_LBZFinalBoss2");
        assertTrue(boss.isFinalBoss2HandoffFlagSetForTest(), "loc_73070 sets bit 5 in FinalBoss1 $38");
        assertTrue(boss.isDestroyed(), "loc_73088 deletes FinalBoss1 after allocating Obj_LBZFinalBoss2");
        LbzFinalBoss2Instance handoffTarget = assertInstanceOf(LbzFinalBoss2Instance.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.FINAL_BOSS_2_HANDOFF)
                        .stream().findFirst().orElseThrow());
        assertEquals("LBZFinalBoss2", handoffTarget.getName());
        assertEquals(0xCC, handoffTarget.getSpawn().objectId(), "S3KL object $CC is Obj_LBZFinalBoss2");
    }

    @Test
    void resultsCompletionRestoresMusicAndLaunchMilestonesExposeState() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        boss.forceSonicFinalePhaseForTest(LbzFinalBoss1Instance.FinalePhase.WAIT_RESULTS_COMPLETE);

        boss.signalResultsCompleteForTest();
        boss.update(20, null);

        assertTrue(services.musicIds.contains(services.levelMusicId));
        assertEquals(LbzFinalBoss1Instance.FinalePhase.POST_RESULTS_DELAY, boss.getFinalePhase());

        boss.forceFinaleTimerForTest(0);
        boss.update(21, null);

        assertTrue(boss.isBossExplosionPlcQueuedForTest(),
                "FinalBoss1 step 3 should issue the raw PLC_BossExplosion load after the $1F delay");
        assertTrue(boss.isDeathEggSmallArtQueuedForTest(),
                "FinalBoss1 step 4 should queue ArtKosM_LBZ2DeathEggSmall for the launch miniatures");
        assertTrue(boss.isCutsceneAnchorRegisteredForTest(),
                "FinalBoss1 step 4 should register itself as the launch cutscene anchor");
        assertEquals(System.identityHashCode(boss), services.lbzState.getFinaleCutsceneAnchorId().orElseThrow(),
                "runtime anchor must be an object-reference-free id suitable for rewind snapshots");

        boss.forceSonicFinalePhaseForTest(LbzFinalBoss1Instance.FinalePhase.WAIT_LAUNCH_MILESTONE_B);
        boss.signalLaunchMilestoneBForTest();
        boss.update(21, null);

        assertTrue(services.lbzState.consumeFinalFallRequested(),
                "FinalBoss1 step 7 should expose the semantic FINAL_FALL hook through LBZ launch state");
    }

    @Test
    void autoWalkDrivesPlayerToCameraAnchorThenSpawnsLaunchFlames() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x4460, (short) 0x0740);
        boss.update(0, null);
        boss.forceSonicFinalePhaseForTest(LbzFinalBoss1Instance.FinalePhase.AUTOWALK);

        boss.update(30, player);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, player.getForcedInputMask(),
                "autowalk should drive P1 toward camera.x+$A0 before flames spawn");
        assertFalse(player.isObjectControlSuppressesMovement(),
                "loc_72BBC restores player movement before Ctrl_1_locked forced autowalk; "
                        + "movement suppression leaves Sonic running in place forever");
        assertEquals(LbzFinalBoss1Instance.FinalePhase.AUTOWALK, boss.getFinalePhase());

        player.setCentreX((short) 0x44A0);
        boss.update(31, player);

        assertEquals(AbstractPlayableSprite.INPUT_UP, player.getForcedInputMask(),
                "when P1 reaches the launch mark the cutscene stops and holds Up for look-up setup");
        assertEquals(Direction.RIGHT, player.getDirection(),
                "loc_72C3C clears Status_Facing so Sonic faces right/away before looking up");
        assertFalse(player.getForcedAnimationId() == Sonic3kAnimationIds.LOOK_UP.id(),
                "loc_72C3C only holds Up and clears facing; the visible turn-away pose comes from "
                        + "Animate_ExternalPlayerSprite after milestone A");
        assertEquals(2, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.ENGINE_FLAME).size(),
                "FinalBoss1 finale step 5 spawns the two Death Egg engine flames");
        assertEquals(LbzFinalBoss1Instance.FinalePhase.WAIT_LAUNCH_MILESTONE_A, boss.getFinalePhase());
    }

    @Test
    void launchMilestoneAFreezesPlayerAndMilestoneBRequestsFinalFall() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x44A0, (short) 0x0740);
        TestablePlayableSprite sidekick = new TestablePlayableSprite("tails", (short) 0x4480, (short) 0x0740);
        services.withPlayers(player, sidekick);
        boss.update(0, null);
        boss.forceSonicFinalePhaseForTest(LbzFinalBoss1Instance.FinalePhase.WAIT_LAUNCH_MILESTONE_A);

        boss.signalLaunchMilestoneAForTest();
        boss.update(40, player);

        assertTrue(player.isObjectControlled(), "milestone A freezes P1 under object control for the look-up script");
        assertEquals(-1, player.getForcedAnimationId(),
                "loc_72C68 switches to external player sprite animation, not the regular LOOK_UP anim");
        assertEquals(LbzFinalBoss1Instance.FinalePhase.LOOK_UP, boss.getFinalePhase());

        int frame = 41;
        boss.update(frame++, player);

        assertTrue(player.isObjectMappingFrameControl(),
                "Animate_ExternalPlayerSprite writes mapping_frame directly under object control");
        assertEquals(0x55, player.getMappingFrame(),
                "Animate_ExternalPlayerSprite advances past byte_7386A's retained $C4 mapping "
                        + "and emits $55 first");
        assertEquals(Direction.RIGHT, player.getDirection(),
                "Animate_ExternalPlayerSprite must not change Sonic's status-facing bit");
        assertEquals(0x55, sidekick.getMappingFrame(),
                "byte_73874 emits the same first external frame for P2");
        assertEquals(Direction.RIGHT, sidekick.getDirection(),
                "P2's external render flip must not change its status-facing bit");
        assertEquals(LbzFinalBoss1Instance.FinalePhase.LOOK_UP, boss.getFinalePhase());

        for (int i = 0; i < 6; i++) {
            boss.update(frame++, player);
        }
        assertExternalFrame(player, 0x59, Direction.LEFT, "byte_7386A second Sonic frame");
        assertExternalFrame(sidekick, 0x59, Direction.LEFT, "byte_73874 second P2 frame");

        for (int i = 0; i < 6; i++) {
            boss.update(frame++, player);
        }
        assertExternalFrame(player, 0x5A, Direction.LEFT, "byte_7386A third Sonic frame");
        assertExternalFrame(sidekick, 0x5A, Direction.LEFT, "byte_73874 third P2 frame");

        for (int i = 0; i < 5; i++) {
            boss.update(frame++, player);
            assertEquals(LbzFinalBoss1Instance.FinalePhase.LOOK_UP, boss.getFinalePhase());
        }
        boss.update(frame++, player); // Dispatch 19: Sonic's terminal zero callback.
        assertEquals(LbzFinalBoss1Instance.FinalePhase.WAIT_LAUNCH_MILESTONE_B, boss.getFinalePhase());
        assertExternalFrame(sidekick, 0x5A, Direction.LEFT,
                "loc_72C9E still calls P2 on the dispatch where P1 changes the boss routine");
        player.setMappingFrame(0x59);
        for (int i = 0; i < 8; i++) {
            boss.update(frame++, player);
        }
        assertEquals(0x59, player.getMappingFrame(), "loc_72CC6 no longer calls the external animator");

        boss.signalLaunchMilestoneBForTest();
        boss.update(frame, player);

        assertTrue(services.lbzState.consumeFinalFallRequested());
        assertFalse(player.getAir(),
                "loc_72CC6 only sets Events_fg_5; P1 stays in the external cutscene pose while the screen falls");
        assertTrue(player.isObjectMappingFrameControl(),
                "final fall must not release manual mapping control back to the regular player animation");
        assertEquals(LbzFinalBoss1Instance.FinalePhase.FINAL_FALL, boss.getFinalePhase());
    }

    @Test
    void launchPadEngineFlamesPlayBossExplosionSfx() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x44A0, (short) 0x0740);
        boss.update(0, null);
        boss.forceSonicFinalePhaseForTest(LbzFinalBoss1Instance.FinalePhase.AUTOWALK);

        boss.update(30, player);
        AbstractObjectInstance flame = (AbstractObjectInstance) boss.childrenOfKindForTest(
                LbzFinalBoss1Instance.ChildKind.ENGINE_FLAME).get(0);
        services.rng().setSeed(0x12345678L); // Random_Number returns $1234C399.
        flame.update(31, player);
        AbstractObjectInstance explosion = assertInstanceOf(AbstractObjectInstance.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.BOSS_EXPLOSION).get(0));
        assertEquals(0x4439, explosion.getX());
        assertEquals(0x072C, explosion.getY(), "sub_83E90 uses SWAP, not a byte shift, for Y");

        assertTrue(services.sfxIds.contains(Sonic3kSfx.EXPLODE.id),
                "sub_83E84 creates Obj_BossExplosion1, whose init routine plays sfx_Explode");
    }

    @Test
    void explosionShowerConsumesHarnessRngStream() throws Exception {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        services.rng().setSeed(0x13572468L);
        GameRng expected = new GameRng(GameRng.Flavour.S3K, 0x13572468L);
        expected.nextRaw();
        AbstractObjectInstance shower = newExplosionShowerForTest(boss, 0x44A0, 0x0780, 0);

        shower.update(0, null);
        shower.update(1, null);
        shower.update(2, null);

        assertEquals(expected.getSeed(), services.rng().getSeed(),
                "sub_83E84 calls global Random_Number for the first boss-explosion offset");
        assertTrue(services.sfxIds.contains(Sonic3kSfx.EXPLODE.id),
                "Obj_BossExplosion1 init plays sfx_Explode after the first global RNG draw");
    }

    @Test
    void finalFallRequestsMhzTransitionWhenPlayerDropsBelowCamera() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x44A0, (short) 0x0730);
        boss.update(0, null);
        boss.forceSonicFinalePhaseForTest(LbzFinalBoss1Instance.FinalePhase.FINAL_FALL);

        boss.update(50, player);

        assertTrue(services.transitionRequested);
        assertTrue(boss.isDestroyed());
    }

    @Test
    void finalFallTestsThePreEventCameraAtTheObjectDispatchBoundary() {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x44A0, (short) (services.cameraY() + 0x11E));
        boss.update(0, null);
        boss.forceSonicFinalePhaseForTest(LbzFinalBoss1Instance.FinalePhase.FINAL_FALL);

        // LevelFrameStep uses the legacy S3K object-before-physics ordering.
        // The ROM loc_72CDA therefore reads Camera_Y_pos before the falling
        // event's same-loop decrement, rather than anticipating that write.
        boss.update(50, player);
        assertFalse(services.transitionRequested,
                "the object pass must not consume the two-pixel post-event margin early");

        player.setTestY((short) (services.cameraY() + 0x120));
        boss.update(51, player);
        assertTrue(services.transitionRequested,
                "the next object pass must request StartNewLevel at Camera_Y_pos+$120");
    }

    @Test
    void deathEggExplosionDebrisUsesSpriteCheckDeleteXYNotXOnlyRange() throws Exception {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        AbstractObjectInstance debris = newDeathEggExplosionDebrisForTest(
                boss,
                services.camera.getX() + 0x20,
                services.camera.getY() + 0x180,
                7);

        debris.update(0, null);
        assertFalse(debris.isDestroyed(), "loc_72E54 returns after initialization");
        debris.update(1, null);

        assertTrue(debris.isDestroyed(),
                "loc_72E54 switches to MoveChkDel, which calls Sprite_CheckDeleteXY; "
                        + "Y-offscreen debris must delete instead of wrapping forever while X remains in range");
    }

    @Test
    void deathEggMiniatureClusterUsesExplosionSequencerCameraRelativeBase() throws Exception {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        services.camera.setX((short) 0x4310);
        services.camera.setY((short) 0x0668);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        AbstractObjectInstance sequencer = newExplosionSequencerForTest(boss);
        writeIntField(sequencer, "milestoneWait", 0);

        sequencer.update(0, null);

        List<Object> miniatures = boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.DEATH_EGG_MINIATURE);
        assertEquals(7, miniatures.size());
        AbstractObjectInstance lead = assertInstanceOf(AbstractObjectInstance.class, miniatures.get(0));
        AbstractObjectInstance lowerLeft = assertInstanceOf(AbstractObjectInstance.class, miniatures.get(1));
        assertEquals(0x43E0, lead.getX(),
                "ChildObjDat_7380C is relative to the loc_72DEA sequencer at camera.x+$D0");
        assertEquals(0x0648, lead.getY(),
                "ChildObjDat_7380C is relative to the loc_72DEA sequencer at camera.y-$20");
        assertEquals(0x43D0, lowerLeft.getX());
        assertEquals(0x0670, lowerLeft.getY());
    }

    @Test
    void debrisRandomizationBelongsToChildInitAndUsesRomWordOrder() throws Exception {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        AbstractObjectInstance sequencer = newExplosionSequencerForTest(boss);
        services.rng().setSeed(0x12345678L);
        sequencer.update(0, null);
        assertEquals(0x12345678L, services.rng().getSeed(), "AllocateObject does not run loc_72E54");
        AbstractObjectInstance debris = assertInstanceOf(AbstractObjectInstance.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.BOSS_EXPLOSION).get(0));
        debris.update(1, null);
        assertEquals(8, readIntField(debris, "mappingFrame"));
        assertEquals((services.camera.getX() & 0xFFFF) + 0x20 + 0x34, debris.getX());
        assertEquals((services.camera.getY() & 0xFFFF) - 0x20, debris.getY());
        assertFalse(debris.isHighPriority());
        assertEquals(6, debris.getPriorityBucket());
    }

    @Test
    void sequencerEmits23DebrisThenConsumesFirstWaitTickOnCount24() throws Exception {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        AbstractObjectInstance sequencer = newExplosionSequencerForTest(boss);
        int dispatch = 0;
        // loc_72DEA: counts 1..23 at 0, 6, 13, ...; count 24 at 391.
        for (; dispatch <= 390; dispatch++) {
            sequencer.update(dispatch, null);
        }
        long debris = boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.BOSS_EXPLOSION)
                .stream().filter(o -> o.getClass().getSimpleName().equals("DeathEggExplosionDebrisChild")).count();
        assertEquals(23, debris);
        assertTrue(services.lbzState.consumePadCollapseStartRequested());
        sequencer.update(dispatch++, null);
        assertEquals(23, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.BOSS_EXPLOSION).size(),
                "loc_72E00 branches before allocation on count $18");
        for (int i = 0; i < 382; i++) {
            sequencer.update(dispatch++, null);
            assertTrue(boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.DEATH_EGG_MINIATURE).isEmpty());
        }
        sequencer.update(dispatch, null);
        assertTrue(sequencer.isDestroyed());
        assertEquals(7, boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.DEATH_EGG_MINIATURE).size());
    }

    @Test
    void miniatureInitDoesNotMoveOrSmokeAndSmokeUsesVBlankClock() throws Exception {
        HarnessServices services = new HarnessServices(PlayerCharacter.SONIC_AND_TAILS);
        LbzFinalBoss1Instance boss = newBoss(services);
        boss.update(0, null);
        AbstractObjectInstance sequencer = newExplosionSequencerForTest(boss);
        writeIntField(sequencer, "milestoneWait", 0);
        sequencer.update(0, null);
        AbstractObjectInstance lead = assertInstanceOf(AbstractObjectInstance.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.DEATH_EGG_MINIATURE).get(0));
        int startY = lead.getY();
        lead.update(16, null);
        assertTrue(boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.DEATH_EGG_SMOKE).isEmpty(),
                "loc_72E9E initializes even on a VBlank divisible by 16");
        assertEquals(startY, lead.getY());
        assertFalse(lead.isHighPriority());
        assertEquals(7, lead.getPriorityBucket());
        lead.update(32, null);
        AbstractObjectInstance smoke = assertInstanceOf(AbstractObjectInstance.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.DEATH_EGG_SMOKE).get(0));
        smoke.update(32, null);
        assertTrue(readBooleanField(smoke, "hFlip"), "loc_72F7A rotates immediately at V_int_run_count & 3 == 0");
        assertEquals(6, smoke.getPriorityBucket());
        AbstractObjectInstance puff = assertInstanceOf(AbstractObjectInstance.class,
                boss.childrenOfKindForTest(LbzFinalBoss1Instance.ChildKind.DEATH_EGG_SMOKE).get(1));
        int[] expected = {4, 4, 4, 5, 5, 5, 6, 6, 6};
        for (int i = 0; i < expected.length; i++) {
            puff.update(32 + i, null);
            assertEquals(expected[i], readIntField(puff, "mappingFrame"));
            assertFalse(puff.isDestroyed());
        }
        puff.update(41, null);
        assertTrue(puff.isDestroyed(), "byte_73864 reaches $F4 on dispatch 10");
    }

    private static int readIntField(Object target, String name) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static boolean readBooleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getSuperclass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static LbzFinalBoss1Instance newBoss(HarnessServices services) {
        LbzFinalBoss1Instance boss = new LbzFinalBoss1Instance(new ObjectSpawn(
                0x44A0, 0x0780, OBJ_LBZ_FINAL_BOSS_1, 0, 0, false, 0));
        boss.setServices(services);
        return boss;
    }

    private static void hitBoss(LbzFinalBoss1Instance boss, PlayableEntity player) {
        boss.onPlayerAttack(player, null);
        boss.update(0, player);
    }

    private static AbstractObjectInstance newDeathEggExplosionDebrisForTest(
            LbzFinalBoss1Instance boss,
            int x,
            int y,
            int frame)
            throws Exception {
        Class<?> cls = Class.forName(
                "com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$DeathEggExplosionDebrisChild");
        Constructor<?> ctor = cls.getDeclaredConstructor(LbzFinalBoss1Instance.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        return (AbstractObjectInstance) ctor.newInstance(boss, x, y, frame);
    }

    private static AbstractObjectInstance newExplosionSequencerForTest(LbzFinalBoss1Instance boss)
            throws Exception {
        Class<?> cls = Class.forName(
                "com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$ExplosionSequencerChild");
        Constructor<?> ctor = cls.getDeclaredConstructor(LbzFinalBoss1Instance.class);
        ctor.setAccessible(true);
        return (AbstractObjectInstance) ctor.newInstance(boss);
    }

    private static AbstractObjectInstance newExplosionShowerForTest(
            LbzFinalBoss1Instance boss,
            int x,
            int y,
            int subtype)
            throws Exception {
        Class<?> cls = Class.forName(
                "com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance$ExplosionShowerChild");
        Constructor<?> ctor = cls.getDeclaredConstructor(
                LbzFinalBoss1Instance.class, int.class, int.class, int.class);
        ctor.setAccessible(true);
        return (AbstractObjectInstance) ctor.newInstance(boss, x, y, subtype);
    }

    private static void writeIntField(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static LbzFinalBoss1Instance.BossChild firstBossChild(
            LbzFinalBoss1Instance boss,
            LbzFinalBoss1Instance.ChildKind kind) {
        return assertInstanceOf(LbzFinalBoss1Instance.BossChild.class,
                boss.childrenOfKindForTest(kind).stream().findFirst().orElseThrow());
    }

    private static boolean hFlipForTest(LbzFinalBoss1Instance.BossChild child) throws Exception {
        Field hFlip = LbzFinalBoss1Instance.BossChild.class.getDeclaredField("hFlip");
        hFlip.setAccessible(true);
        return hFlip.getBoolean(child);
    }

    private static void assertExternalFrame(
            TestablePlayableSprite sprite,
            int mappingFrame,
            Direction direction,
            String message) {
        assertEquals(mappingFrame, sprite.getMappingFrame(), message + " mapping_frame");
        assertEquals(direction == Direction.LEFT, sprite.getRenderHFlip(),
                message + " render_flags bit0");
        assertEquals(Direction.RIGHT, sprite.getDirection(),
                message + " must preserve the status-facing bit");
    }

    private static final class HarnessServices extends StubObjectServices {
        private final ResultsHardwareTimingFixture resultsTiming = new ResultsHardwareTimingFixture();
        private final Camera camera = new Camera();
        private final LbzZoneRuntimeState lbzState;
        private final GameStateManager gameState = new GameStateManager();
        private final List<Integer> musicIds = new ArrayList<>();
        private final List<Integer> sfxIds = new ArrayList<>();
        private final List<PlayableEntity> sidekicks = new ArrayList<>();
        private final int levelMusicId = 0x17;
        private PlayableEntity mainPlayer;
        private boolean transitionRequested;

        private HarnessServices(PlayerCharacter character) {
            lbzState = new LbzZoneRuntimeState(1, character);
            camera.setX((short) 0x4400);
            camera.setY((short) 0x0600);
        }

        @Override
        public ObjectPlayerQuery playerQuery() {
            return new ObjectPlayerQuery(() -> mainPlayer, () -> sidekicks);
        }

        private void withPlayers(PlayableEntity mainPlayer, PlayableEntity... sidekicks) {
            this.mainPlayer = mainPlayer;
            this.sidekicks.clear();
            for (PlayableEntity sidekick : sidekicks) {
                this.sidekicks.add(sidekick);
            }
        }

        private int cameraY() {
            return Short.toUnsignedInt(camera.getY());
        }

        @Override
        public Camera camera() {
            return camera;
        }

        @Override
        public com.openggf.game.timing.HardwareTimingService hardwareTiming() {
            return resultsTiming.hardwareTiming();
        }

        @Override
        public com.openggf.data.Rom rom() {
            return TestEnvironment.currentRom();
        }

        @Override
        public com.openggf.data.RomByteReader romReader() {
            try {
                return com.openggf.data.RomByteReader.fromRom(rom());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public ZoneRuntimeState zoneRuntimeState() {
            return lbzState;
        }

        @Override
        public GameStateManager gameState() {
            return gameState;
        }

        @Override
        public RuntimeArtCoordinator runtimeArtCoordinator() {
            return TestEnvironment.activeGameplayMode().runtimeArtCoordinator();
        }

        @Override
        public void playMusic(int musicId) {
            musicIds.add(musicId);
        }

        @Override
        public void playSfx(int soundId) {
            sfxIds.add(soundId);
        }

        @Override
        public int getCurrentLevelMusicId() {
            return levelMusicId;
        }

        @Override
        public void requestZoneAndAct(int zone, int act, boolean deactivateLevelNow) {
            assertEquals(Sonic3kZoneIds.ZONE_MHZ, zone);
            assertEquals(0, act);
            assertTrue(deactivateLevelNow);
            transitionRequested = true;
        }
    }
}
