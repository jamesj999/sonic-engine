package com.openggf.tests;

import com.openggf.game.session.SessionManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.objects.CnzCannonInstance;
import com.openggf.game.sonic3k.objects.CnzEggCapsuleInstance;
import com.openggf.game.sonic3k.objects.CnzTeleporterBeamInstance;
import com.openggf.game.sonic3k.objects.CnzTeleporterInstance;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.game.sonic3k.objects.SongFadeTransitionInstance;
import com.openggf.game.sonic3k.objects.bosses.CnzEndBossInstance;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.level.Palette;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless Task 8 coverage for CNZ's Knuckles teleporter route and bounded
 * end-boss defeat handoff.
 *
 * <p>These tests deliberately stop at the Task 8 scope the plan and ROM notes
 * agreed on:
 * <ul>
 *   <li>{@code Obj_CNZTeleporter} must clamp and lock the player, queue the
 *   teleporter handoff, and spawn the shared {@code Obj_TeleporterBeam}</li>
 *   <li>The teleporter route must <strong>not</strong> spawn the egg capsule</li>
 *   <li>{@code Obj_CNZEndBoss} only owns startup/defeat handoff for now:
 *   clear the boss flag, widen camera bounds, spawn the CNZ capsule wrapper,
 *   and restore player control</li>
 * </ul>
 *
 * <p>Full CNZ end-boss attack-state parity is intentionally deferred.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzTeleporterRouteHeadless {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        com.openggf.game.session.SessionManager.clear();
    }

    /**
     * ROM anchors:
     * {@code Obj_CNZTeleporter} arms when Knuckles reaches the late Act 2 route,
     * clamps airborne overshoot back to {@code x=$4A40}, zeroes the movement
     * speeds, locks control, queues {@code ArtKosM_CNZTeleport}, then hands off
     * to {@code Obj_CNZTeleporterMain}. That main phase waits for the art load
     * and for Knuckles to be grounded before spawning the shared
     * {@code Obj_TeleporterBeam}.
     *
     * <p>Task 8 must preserve one critical boundary from the verified ROM notes:
     * the teleporter route does <strong>not</strong> spawn the egg capsule. That
     * handoff belongs only to the later {@code Obj_CNZEndBoss} defeat path.
     */
    @Test
    void groundedTeleporterWaitsForArtReadinessAndPublishesRomPalettePatch() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        assertTrue(GameServices.level().consumePendingInitialProcessSpritesPass(),
                "consume the native load-time Process_Sprites pass before injecting the later route object");

        CnzTeleporterInstance teleporter = new CnzTeleporterInstance(
                new ObjectSpawn(0x4A40, 0x0A38, 0, 0, 0, false, 0));
        teleporter.setServices(TestEnvironment.objectServices());
        GameServices.level().getObjectManager().addDynamicObject(teleporter);
        getCnzEvents().beginKnucklesTeleporterRoute();
        fixture.sprite().setCentreX((short) 0x4A40);
        fixture.sprite().setAir(false);

        teleporter.update(0, fixture.sprite());

        assertFalse(isObjectPresent(CnzTeleporterBeamInstance.class),
                "Obj_CNZTeleporter must return after Queue_Kos_Module and poll readiness next frame");
        Sonic3kObjectArtProvider artProvider = (Sonic3kObjectArtProvider)
                TestEnvironment.objectServices().renderManager().getArtProvider();
        assertTrue(artProvider.isCnzTeleporterArtPending(),
                "arming must leave a genuine ArtKosM_CNZTeleport workload pending");
        assertFalse(artProvider.isCnzTeleporterArtComplete(),
                "the runtime queue must not report completion in the request frame");
        int[] colorIndices = {1, 2, 8, 10};
        int[] segaWords = {0x0EEE, 0x00EC, 0x0EA2, 0x0E80};
        Palette line2 = GameServices.level().getCurrentLevel().getPalette(1);
        for (int i = 0; i < colorIndices.length; i++) {
            assertEquals(S3kPaletteOwners.CNZ_TELEPORTER,
                    GameServices.paletteOwnershipRegistry().ownerAt(
                            PaletteSurface.NORMAL, 1, colorIndices[i]));
            assertSegaColor(line2.getColor(colorIndices[i]), segaWords[i]);
        }

        fixture.stepIdleFrames(1);
        assertFalse(artProvider.isCnzTeleporterArtPending());
        assertTrue(artProvider.isCnzTeleporterArtComplete(),
                "the shared frame pipeline should consume the queued KosM workload");
        assertFalse(isObjectPresent(CnzTeleporterBeamInstance.class),
                "the object pass precedes the loop-tail art pump, so readiness is visible next frame");

        fixture.stepIdleFrames(1);
        assertTrue(isObjectPresent(CnzTeleporterBeamInstance.class),
                "Obj_CNZTeleporterMain should proceed once the teleporter renderer reports ready");
    }

    @Test
    void knucklesTeleporterRequiresPublishedRouteBeforeLockingControl() {
        SonicConfigurationService.getInstance()
                .setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();

        CnzTeleporterInstance teleporter = new CnzTeleporterInstance(
                new ObjectSpawn(0x4A40, 0x0A38, 0, 0, 0, false, 0));
        teleporter.setServices(TestEnvironment.objectServices());
        GameServices.level().getObjectManager().addDynamicObject(teleporter);

        fixture.sprite().setCentreX((short) 0x4A50);
        fixture.sprite().setCentreY((short) 0x0A30);
        fixture.sprite().setAir(true);
        fixture.sprite().setJumping(true);
        fixture.sprite().setXSpeed((short) 0x180);
        fixture.sprite().setGSpeed((short) 0x200);

        fixture.stepIdleFrames(1);
        assertFalse(fixture.sprite().isControlLocked(),
                "Obj_CNZTeleporter should stay dormant until the CNZ route seam is published externally");
        assertFalse(getCnzEvents().isTeleporterBeamSpawned(),
                "The shared beam handoff must remain inactive before the route is published");

        Sonic3kCNZEvents events = getCnzEvents();
        events.beginKnucklesTeleporterRoute();

        fixture.sprite().setCentreX((short) 0x4A50);
        fixture.sprite().setCentreY((short) 0x0A30);
        fixture.sprite().setAir(true);
        fixture.sprite().setJumping(true);
        fixture.sprite().setXSpeed((short) 0x180);
        fixture.sprite().setGSpeed((short) 0x200);

        teleporter.update(0, fixture.sprite());
        assertEquals(0x4A40, fixture.sprite().getCentreX(),
                "Obj_CNZTeleporter's arming routine should write x_pos=$4A40 before returning");
        fixture.stepIdleFrames(1);
        assertTrue(fixture.sprite().isControlLocked(),
                "Obj_CNZTeleporter should mirror Ctrl_1_locked by immediately removing player control");
        assertEquals(0, fixture.sprite().getXSpeed(),
                "The teleporter arming frame should clear x_vel before the beam sequence begins");
        assertEquals(0, fixture.sprite().getGSpeed(),
                "The teleporter arming frame should also clear ground_vel");
        assertEquals(0x4750, fixture.camera().getMinX(),
                "The Knuckles-only route should publish the late Act 2 left camera clamp");
        assertEquals(0x48E0, fixture.camera().getMaxX(),
                "The Knuckles-only route should publish the late Act 2 right camera clamp");

        fixture.sprite().setAir(false);
        fixture.sprite().setJumping(false);
        fixture.stepIdleFrames(1);

        assertTrue(isObjectPresent(CnzTeleporterBeamInstance.class),
                "Obj_CNZTeleporterMain should spawn the shared beam once the player is grounded");
        assertTrue(events.isTeleporterBeamSpawned(),
                "The CNZ event bridge should record the explicit beam-handoff seam");
        assertFalse(isObjectPresent(CnzEggCapsuleInstance.class),
                "The teleporter route must not spawn the capsule; that belongs to Obj_CNZEndBoss");

        fixture.stepIdleFrames(CnzTeleporterBeamInstance.PLAYER_CAPTURE_COUNTER);
        assertTrue(fixture.sprite().isObjectControlled(),
                "At beam counter 8, Obj_CNZTeleporterMain should take full player object control");
        assertFalse(fixture.sprite().isObjectControlAllowsCpu(),
                "Full bit-7 teleporter control should not leave CPU movement enabled");
        assertTrue(fixture.sprite().isObjectControlSuppressesMovement(),
                "Full bit-7 teleporter control should suppress player movement");
        assertTrue(fixture.sprite().isTouchResponseSuppressedByObjectControl(),
                "Full bit-7 teleporter control should suppress normal touch responses");
    }

    private static void assertSegaColor(Palette.Color actual, int segaWord) {
        Palette.Color expected = new Palette.Color();
        expected.fromSegaFormat(new byte[] {(byte) (segaWord >>> 8), (byte) segaWord}, 0);
        assertEquals(expected.r, actual.r);
        assertEquals(expected.g, actual.g);
        assertEquals(expected.b, actual.b);
    }

    /**
     * ROM anchors:
     * Task 8 only claims the defeat handoff from {@code Obj_CNZEndBoss}. When
     * the boss sequence is finished, it must clear {@code Boss_flag}, widen the
     * boss camera clamp, spawn the egg capsule, and restore level music/control.
     *
     * <p>This test intentionally does not claim attack-state parity, hit timing,
     * or the full cutscene choreography.
     */
    @Test
    void cnzEndBossSlotStaysPassiveUntilExternalBossStateExists() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();

        CnzEndBossInstance boss = spawnCnzEndBossForTest();
        fixture.stepIdleFrames(1);

        assertFalse(getCnzEvents().isBossFlag(),
                "The promoted CNZ end-boss slot must not claim Boss_flag on its own");
        assertEquals(0, GameServices.gameState().getCurrentBossId(),
                "Task 8 keeps boss-mode ownership on the external CNZ event seam until later attack work exists");
        assertFalse(isObjectPresent(CnzEggCapsuleInstance.class),
                "Without an external boss-mode seam and defeat signal, the bounded wrapper must stay inert");

        boss.setDestroyed(true);
    }

    /**
     * ROM anchors:
     * Task 8 only claims the defeat handoff from {@code Obj_CNZEndBoss}. When
     * the boss sequence is finished, it must clear {@code Boss_flag}, widen the
     * boss camera clamp, spawn the egg capsule, and restore level music/control.
     *
     * <p>This test intentionally does not claim attack-state parity, hit timing,
     * or the full cutscene choreography.
     */
    @Test
    void cnzEndBossDefeatHandoffClearsBossFlagWidensCameraAndSpawnsCapsule() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();

        Sonic3kCNZEvents events = getCnzEvents();
        events.setBossFlag(true);
        GameServices.gameState().setCurrentBossId(Sonic3kObjectIds.CNZ_END_BOSS);

        fixture.camera().setMaxX((short) 0x48E0);
        fixture.sprite().setControlLocked(true);
        fixture.sprite().setObjectControlled(true);
        fixture.sprite().setHidden(true);

        CnzEndBossInstance boss = spawnCnzEndBossForTest();
        defeatCnzEndBossWithPlayerAttacks(boss, fixture.sprite());

        CnzEggCapsuleInstance capsule = findObject(CnzEggCapsuleInstance.class);
        assertTrue(capsule != null,
                "The bounded Task 8 defeat handoff should spawn the CNZ-local egg capsule wrapper");

        GameServices.gameState().setEndOfLevelFlag(true);
        fixture.sprite().setCentreX((short) 0x4A20);
        boss.update(1, fixture.sprite());
        assertEquals(1, boss.getPostCapsuleReleaseCountForTest(),
                "loc_6E724 should consume the results owner's global completion publication directly");

        for (int i = 0; i < 4; i++) {
            boss.update(2 + i, fixture.sprite());
        }
        assertEquals(1, boss.getPostCapsuleReleaseCountForTest(),
                "Waiting left of the launcher trigger must not replay CNZ2 music/control restore every frame");

        assertFalse(fixture.sprite().isControlLocked(),
                "Capsule release should return player control once the results screen has finished");
        assertFalse(fixture.sprite().isObjectControlled(),
                "Capsule release should clear object control instead of leaving the player frozen");
        assertFalse(fixture.sprite().isHidden(),
                "If the teleporter route hid the player earlier, capsule release must reveal them again");
        assertEquals(Sonic3kAnimationIds.WAIT.id(), fixture.sprite().getAnimationId(),
                "Restore_PlayerControl should publish Wait before returning normal control");
        assertFalse(events.isBossFlag(),
                "Task 8 owns clearing Boss_flag so later CNZ event logic can leave boss mode");
        assertEquals(0, GameServices.gameState().getCurrentBossId(),
                "Defeat handoff should clear Current_Boss_ID alongside Boss_flag");
        assertEquals(0x4A70, events.getCameraStoredMaxXPos() & 0xFFFF,
                "post-results loc_6E778 must use _unkFAB4=$4760 plus $310");
        fixture.stepIdleFrames(8);
        assertTrue(fixture.camera().getMaxX() > 0x48E0,
                "Task 8 should widen the CNZ boss camera clamp during the capsule handoff");
    }

    @Test
    void cnzEndBossDefeatUsesBothNativeWaitsBeforeCapsuleHandoff() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();
        Sonic3kCNZEvents events = getCnzEvents();
        events.setBossFlag(true);
        GameServices.gameState().setCurrentBossId(Sonic3kObjectIds.CNZ_END_BOSS);
        CnzEndBossInstance boss = spawnCnzEndBossForTest();

        defeatCnzEndBossHitsOnly(boss, fixture.sprite());

        assertTrue(GameServices.level().getLevelGamestate().isTimerPaused(),
                "BossDefeated_StopTimer must stop Update_HUD_timer on hit zero");
        assertTrue(boss.isNativeBodyRenderableForTest(),
                "Wait_FadeToLevelMusic keeps drawing the body during its initial $3F countdown");
        assertFalse(isObjectPresent(SongFadeTransitionInstance.class));
        assertFalse(isObjectPresent(CnzEggCapsuleInstance.class));
        assertEquals(0, activeNamed("CnzEndBossDefeatDebrisChild"));

        for (int frame = 0; frame < 0x40; frame++) {
            boss.update(frame, fixture.sprite());
        }
        assertTrue(boss.isNativeBodyRenderableForTest(),
                "$2E reaches zero while Wait_FadeToLevelMusic still draws this dispatch");
        boss.update(0x40, fixture.sprite());

        assertFalse(boss.isNativeBodyRenderableForTest(),
                "the signed underflow dispatch hides the body before loc_6E6C6");
        assertTrue(isObjectPresent(SongFadeTransitionInstance.class),
                "the first wait expiry creates Obj_Song_Fade_ToLevelMusic");
        assertEquals(2, activeNamed("CnzEndBossDefeatDebrisChild"),
                "loc_6E6C6 creates the exact two body-half slots at parent bit 4");
        assertFalse(isObjectPresent(CnzEggCapsuleInstance.class),
                "the capsule waits for the separate (2*60)-1 Obj_Wait");
        assertTrue(events.isBossFlag(), "Boss_flag remains set throughout the second wait");

        for (int frame = 0; frame < (2 * 60) - 1; frame++) {
            boss.update(0x41 + frame, fixture.sprite());
        }
        assertFalse(isObjectPresent(CnzEggCapsuleInstance.class));
        boss.update(0x41 + (2 * 60) - 1, fixture.sprite());

        assertTrue(isObjectPresent(CnzEggCapsuleInstance.class));
        assertFalse(events.isBossFlag());
        assertEquals(0x48F0, events.getCameraStoredMaxXPos() & 0xFFFF);
    }

    @Test
    void cnzPostCapsuleRouteSpawnsCannonAndRequestsIczAfterLaunchThreshold() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_CNZ, 1)
                .build();

        Sonic3kCNZEvents events = getCnzEvents();
        events.setBossFlag(true);
        GameServices.gameState().setCurrentBossId(Sonic3kObjectIds.CNZ_END_BOSS);

        CnzEndBossInstance boss = spawnCnzEndBossForTest();
        defeatCnzEndBossWithPlayerAttacks(boss, fixture.sprite());

        CnzEggCapsuleInstance capsule = findObject(CnzEggCapsuleInstance.class);
        assertTrue(capsule != null,
                "Obj_CNZEndBoss loc_6E6E4 should create the egg capsule before the launcher route");

        Sonic3kObjectArtProvider artProvider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        for (int frame = 0; frame < 10_000
                && (!artProvider.capture().pendingKosModules().isEmpty()
                || !S3kRuntimeArtCoordinator.current().moduleQueue().hasCapacityFor(1)); frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertEquals(java.util.List.of(), artProvider.capture().pendingKosModules(),
                "pre-existing CNZ art must leave the parent FIFO before the late handoff");
        assertTrue(S3kRuntimeArtCoordinator.current().moduleQueue().hasCapacityFor(1),
                "the production KosM FIFO must service existing CNZ art before the late handoff");
        capsule.forceResultsCompleteForTest();
        fixture.sprite().setCentreX((short) 0x4A2F);
        boss.update(0, fixture.sprite());
        fixture.sprite().setCentreX((short) 0x4A30);
        long explosionJobsBeforeCannon = TestEnvironment.activeGameplayMode()
                .hardwareTiming().capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .filter(job -> job.romSourceAddress()
                        == Sonic3kConstants.ART_KOSM_BADNIK_EXPLOSION_ADDR)
                .filter(job -> job.destinationAddress()
                        == Sonic3kConstants.ARTTILE_EXPLOSION * 32)
                .count();
        int expectedCannonSlot = GameServices.level().getObjectManager().firstFreeDynamicSlot();
        boss.update(1, fixture.sprite());

        CnzCannonInstance cannon = findObject(CnzCannonInstance.class);
        assertTrue(cannon != null,
                "Obj_CNZEndBoss loc_6E778 should spawn Obj_CNZCannon at the launcher handoff");
        assertEquals(expectedCannonSlot, cannon.getSlotIndex(),
                "AllocateObject must put the cannon in the lowest free SST");
        var explosionJobs = TestEnvironment.activeGameplayMode()
                .hardwareTiming().capture().jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_MODULE_QUEUE)
                .filter(job -> job.romSourceAddress()
                        == Sonic3kConstants.ART_KOSM_BADNIK_EXPLOSION_ADDR)
                .filter(job -> job.destinationAddress()
                        == Sonic3kConstants.ARTTILE_EXPLOSION * 32)
                .toList();
        assertEquals(explosionJobsBeforeCannon + 1, explosionJobs.size(),
                "loc_6E778 queues ArtKosM_BadnikExplosion before allocating the cannon");
        assertEquals("sha256:70da89e553f70fe647a00489dec5f2612854986b444b87a2e8d81ab0f821e431",
                explosionJobs.getLast().handle().submissionFingerprint(),
                "the cannon handoff must submit the exact ROM-backed KosM parent");

        fixture.sprite().setCentreX((short) 0x4B20);
        fixture.sprite().setCentreY((short) 0x0280);
        fixture.sprite().setAir(false);
        fixture.sprite().setJumping(false);
        cannon.onSolidContact(fixture.sprite(), new SolidContact(true, false, false, true, false), 0);
        assertTrue(cannon.hasCapturedPlayerForEndSequence());
        boss.update(2, fixture.sprite());
        assertEquals(0x0200, fixture.camera().getMaxYTarget() & 0xFFFF,
                "loc_6E7B6 arms the camera drop as soon as cannon state $30 becomes 1");
        assertTrue(fixture.sprite().isControlLocked());
        invokeCannonLaunchReadyHook(cannon);

        for (int frame = 0; frame < 300 && fixture.sprite().isObjectControlled(); frame++) {
            boss.update(frame, fixture.sprite());
            cannon.update(frame, fixture.sprite());
        }
        assertFalse(fixture.sprite().isObjectControlled(),
                "Obj_CNZEndBoss loc_6E7E4 should force the cannon launch instead of waiting for manual input");
        assertEquals(0x14, cannon.getSpinAngle(),
                "the parent observes raw angle $12, then the later cannon slot derives its frame and stores $14");
        assertEquals(0x0B50, fixture.sprite().getXSpeed(),
                "raw old angle $12 must derive frame 6 before the cannon stores $14 and launches");

        fixture.sprite().setCentreY((short) (fixture.camera().getY() + 0x10));
        short launchXSpeed = fixture.sprite().getXSpeed();
        short launchYSpeed = fixture.sprite().getYSpeed();
        boolean launchAir = fixture.sprite().getAir();
        boolean launchHidden = fixture.sprite().isHidden();
        boss.update(3, fixture.sprite());

        assertTrue(GameServices.level().consumeZoneActRequest(),
                "Obj_CNZEndBoss loc_6E80C should request StartNewLevel once the launcher carries Sonic offscreen");
        int requestedZone = GameServices.level().getRequestedZone();
        int requestedAct = GameServices.level().getRequestedAct();
        assertEquals(Sonic3kZoneIds.ZONE_ICZ, requestedZone);
        assertEquals(0, requestedAct);
        assertTrue(GameServices.level().isLevelInactiveForTransition(),
                "The ICZ request should freeze level updates while the fade transition owns the load");
        assertEquals(launchXSpeed, fixture.sprite().getXSpeed());
        assertEquals(launchYSpeed, fixture.sprite().getYSpeed(),
                "StartNewLevel must preserve the live cannon velocity until ICZ load owns replacement state");
        assertEquals(launchAir, fixture.sprite().getAir());
        assertEquals(launchHidden, fixture.sprite().isHidden(),
                "CNZ must not invent a hidden neutral transition pose before the destination load");

        GameServices.level().loadZoneAndActAtFreshTitleCardBoundary(requestedZone, requestedAct);
        assertEquals(0, fixture.sprite().getCentreX());
        assertEquals(0, fixture.sprite().getCentreY(),
                "Level: clears the old player slot at the pre-title-card boundary");
        assertEquals(0, fixture.sprite().getXSpeed());
        assertEquals(0, fixture.sprite().getYSpeed());
        assertFalse(fixture.sprite().getRolling());
        assertEquals(0, fixture.sprite().getAnimationId());

        GameServices.level().publishFreshLevelTransitionInitialBoundary();
        assertEquals(0x0010, fixture.sprite().getCentreX() & 0xFFFF);
        assertEquals(0x00F0, fixture.sprite().getCentreY() & 0xFFFF);
        assertEquals(0, fixture.sprite().getXSpeed());
        assertEquals(0, fixture.sprite().getYSpeed());
        assertFalse(fixture.sprite().getRolling(),
                "the first destination row precedes Obj_LevelIntroICZ1's dispatch");
        assertEquals(0, fixture.sprite().getAnimationId());

        GameServices.level().completeFreshLevelTransitionBoundary();

        assertFalse(fixture.sprite().isHidden(),
                "ICZ load should clear the neutral fade pose and own the new player state");
        assertEquals(0x0800, fixture.sprite().getXSpeed(),
                "ICZ load must replace the neutral fade pose with Obj_LevelIntroICZ1's snowboard startup x velocity");
        assertEquals(0x0280, fixture.sprite().getYSpeed(),
                "ICZ load must replace the neutral fade pose with Obj_LevelIntroICZ1's snowboard startup y velocity");
        assertTrue(fixture.sprite().getAir(),
                "ICZ load should hand off to the airborne snowboard intro state, not carry CNZ launcher state");
    }

    /**
     * Task 7 still left the CNZ end-boss slot placeholder-backed. Task 8 should
     * promote that explicit registry slot to the bounded CNZ end-boss wrapper so
     * the route and defeat handoff can be exercised without touching unrelated
     * zones that also reuse object ID {@code $A7}.
     */
    @Test
    void registryPromotesCnzEndBossSlotForTask8() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();

        ObjectInstance endBoss = registry.create(
                new ObjectSpawn(0x4A40, 0x0A38, Sonic3kObjectIds.CNZ_END_BOSS, 0, 0, false, 0));

        assertInstanceOf(CnzEndBossInstance.class, endBoss);
    }

    private Sonic3kCNZEvents getCnzEvents() {
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        return manager.getCnzEvents();
    }

    private CnzEndBossInstance spawnCnzEndBossForTest() {
        CnzEndBossInstance boss = new CnzEndBossInstance(
                new ObjectSpawn(0x4A40, 0x0A38, Sonic3kObjectIds.CNZ_END_BOSS, 0, 0, false, 0));
        boss.setServices(TestEnvironment.objectServices());
        GameServices.level().getObjectManager().addDynamicObject(boss);
        return boss;
    }

    private void defeatCnzEndBossWithPlayerAttacks(CnzEndBossInstance boss, AbstractPlayableSprite player) {
        defeatCnzEndBossHitsOnly(boss, player);
        for (int defeatFrame = 0; defeatFrame < 0x41 + 2 * 60; defeatFrame++) {
            boss.update(defeatFrame, player);
        }
    }

    private void defeatCnzEndBossHitsOnly(CnzEndBossInstance boss, AbstractPlayableSprite player) {
        TouchResponseResult hit = new TouchResponseResult(0x06, 0, 0, TouchCategory.ENEMY);
        for (int i = 0; i < 8; i++) {
            boss.onPlayerAttack(player, hit);
            for (int cooldown = 0; i < 7 && cooldown < 0x20; cooldown++) {
                boss.update(cooldown, player);
            }
        }
    }

    private long activeNamed(String simpleName) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(object -> !object.isDestroyed())
                .filter(object -> object.getClass().getSimpleName().equals(simpleName))
                .count();
    }

    private boolean isObjectPresent(Class<?> type) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .anyMatch(type::isInstance);
    }

    private <T> T findObject(Class<T> type) {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElse(null);
    }

    private static void invokeCannonLaunchReadyHook(CnzCannonInstance cannon) {
        try {
            java.lang.reflect.Method method =
                    CnzCannonInstance.class.getDeclaredMethod("setLaunchDelayFramesForTest", int.class);
            method.setAccessible(true);
            method.invoke(cannon, 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to ready CNZ cannon for route test", e);
        }
    }
}
