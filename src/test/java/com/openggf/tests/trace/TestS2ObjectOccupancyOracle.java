package com.openggf.tests.trace;

import com.openggf.game.GameServices;
import com.openggf.game.sonic2.objects.ARZPlatformObjectInstance;
import com.openggf.game.sonic2.objects.ArrowProjectileInstance;
import com.openggf.game.sonic2.objects.GrounderRockProjectile;
import com.openggf.game.sonic2.objects.GrounderWallInstance;
import com.openggf.game.sonic2.objects.badniks.WhispBadnikInstance;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.level.objects.AnimalObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.BreathingBubbleInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSlotLayout;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SkidDustObjectInstance;
import com.openggf.level.rings.LostRingObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Comparison-only measurement and assertion of the engine's dynamic-slot
 * occupancy against the ROM trace timeline using {@link ObjectOccupancyOracle}.
 *
 * <p><strong>Self-deleting transient assertion (Task 1.7, piece a).</strong>
 * The green S2 traces (EHZ1, SCZ, WFZ) assert frame-for-frame parity of the
 * live <em>count</em> of the badnik-death explosion (Obj27), whose destroy
 * frame is a fixed {@code anim_frame_duration} countdown: it deletes 35 game
 * frames after spawn in S2/S3K (init 3 / reload 7 / delete at mapping_frame 5 —
 * docs/s2disasm/s2.asm:46672-46684). Piece (a) aligns the engine explosion to
 * that exact frame (previously it lingered 4 frames via a uniform 8-frame
 * delay).
 *
 * <p>The assertion is deliberately scoped two ways. First, by id (see
 * {@link #TRANSIENT_SELF_DELETE_IDS}): the Animal (Obj28) despawns by walk/fly
 * physics and the off-screen {@code MarkObjGone} window (docs/s2disasm/s2.asm
 * Obj28_Walk/Obj28_Fly), and the points popup (Obj29) — already ROM-correct on
 * lifespan — diverges only by a one-frame spawn-windowing offset; both are
 * object-lifetime categories outside piece (a). Second, by <em>count</em>
 * rather than by slot: it compares the number of live Obj27 instances the
 * engine holds against the ROM timeline. A delete that is a frame late leaves
 * engineCount &gt; romCount; a frame early leaves engineCount &lt; romCount.
 * Counting by id ignores spawn-slot-allocation / windowing drift (the engine
 * spawning the same transient into a different slot than the ROM — piece b),
 * which would otherwise mask or fake a transient-timing regression. See
 * {@link ObjectOccupancyOracle#firstTransientCountDivergence}.
 *
 * <p>MTZ1 stays a non-asserting MEASUREMENT: it is a trace frontier (not a green
 * trace), so its occupancy diverges for windowing reasons unrelated to transient
 * timing.
 *
 * <p><strong>Comparison-only invariant:</strong> the oracle and this test read
 * trace data and engine state and report; they never write engine state from
 * the trace.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2ObjectOccupancyOracle {

    private static final int FIRST_DYNAMIC_SLOT = ObjectSlotLayout.SONIC_2.firstDynamicSlot();

    /**
     * Self-deleting transient object id whose destroy frame this assertion
     * guards: Obj27, the badnik-death explosion. Its ROM lifespan is a fixed
     * {@code anim_frame_duration} countdown (init 3 / reload 7 / delete at
     * mapping_frame 5 in S2/S3K) = 35 game frames from spawn; piece (a) aligns
     * the engine to that exact frame.
     *
     * <p>Obj29 (the floating points popup) is deliberately NOT in scope even
     * though its self-delete logic is also fixed-countdown and the engine
     * already matches the ROM lifespan exactly (32 frames: delete when
     * {@code y_vel >= 0}, docs/s2disasm/s2.asm Obj29_Main). Its per-frame count
     * still diverges by one frame in some green traces (e.g. EHZ1 f1308: ROM
     * spawns the points at f1309, the engine at f1308) because the engine spawns
     * it one frame off the ROM {@code AllocateObject} ordering — a
     * spawn-slot-windowing offset (piece b), not a delete-frame error. Including
     * Obj29 would make this assertion red for a reason outside piece (a).
     */
    private static final Set<Integer> TRANSIENT_SELF_DELETE_IDS = Set.of(0x27);

    @Test
    public void measureHtz1OccupancyDivergence() throws Exception {
        ObjectOccupancyOracle.Divergence first =
                measureFirstDivergence("htz", Sonic2ZoneConstants.ZONE_HTZ, 0, null);
        if (first == null) {
            System.out.println(
                    "[occupancy-oracle] HTZ1: no dynamic-slot occupancy divergence "
                            + "across the trace.");
        } else {
            System.out.printf(
                    "[occupancy-oracle] HTZ1 first divergence: frame=%d slot=%d "
                            + "expectedId=0x%02X actualId=0x%02X%n",
                    first.frame(), first.slot(),
                    first.expectedId() & 0xFF, first.actualId() & 0xFF);
        }
        // Measurement only: HTZ1 is a trace frontier, not a green trace.
    }

    @Test
    public void measureMtz1OccupancyDivergence() throws Exception {
        ObjectOccupancyOracle.Divergence first =
                measureFirstDivergence("mtz", Sonic2ZoneConstants.ZONE_MTZ, 0, null);
        if (first == null) {
            System.out.println(
                    "[occupancy-oracle] MTZ1: no dynamic-slot occupancy divergence "
                            + "across the trace.");
        } else {
            System.out.printf(
                    "[occupancy-oracle] MTZ1 first divergence: frame=%d slot=%d "
                            + "expectedId=0x%02X actualId=0x%02X%n",
                    first.frame(), first.slot(),
                    first.expectedId() & 0xFF, first.actualId() & 0xFF);
        }
        // Measurement only: MTZ1 is a trace frontier, not a green trace.
    }

    @Test
    public void mtz1RespawnTrackedBadnikKillDoesNotReloadThroughPlacementWindow() throws Exception {
        Integer slot21Id = driveTrace("mtz", Sonic2ZoneConstants.ZONE_MTZ, 0,
                (trace, om, frame) -> {
                    if (frame != 1168) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x74, expected.get(21),
                            "ROM fixture should load the invisible block into slot 21 at MTZ1 f1168");
                    return actual.get(21);
                });
        Assertions.assertNotNull(slot21Id);
        Assertions.assertEquals(0x74, slot21Id,
                "S2 ChkLoadObj must skip the killed respawn-tracked Asteron at x=$0720 "
                        + "so the next streamed object takes slot 21");
    }

    @Test
    public void htz2Obj18StandingBitClearsWhenLaterRideWinsAtRomFrame4011() throws Exception {
        Boolean obj18Standing = driveTrace("htz2", Sonic2ZoneConstants.ZONE_HTZ, 1,
                (trace, om, frame) -> {
                    if (frame != 4011) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedObj18 = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 34)
                            .filter(near -> parseObjectType(near.objectType()) == 0x18)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedObj18,
                            "HTZ2 ROM fixture should report Obj18 slot 34 at f4011");
                    Assertions.assertEquals(0, parseObjectType(expectedObj18.status()) & 0x18,
                            "ROM Obj18 standing bits are clear before slot 22 becomes the active ride");
                    ARZPlatformObjectInstance actualObj18 =
                            om.activeObjectsOfType(ARZPlatformObjectInstance.class).stream()
                                    .filter(platform -> platform.getX() == 0x1860)
                                    .findFirst()
                                    .orElse(null);
                    Assertions.assertNotNull(actualObj18,
                            "Engine should have the HTZ2 Obj18 platform at x=$1860 by f4011");
                    AbstractPlayableSprite sonic =
                            (AbstractPlayableSprite) GameServices.sprites().getSprite("sonic");
                    return om.hasObjectStandingBit(sonic, actualObj18);
                });
        Assertions.assertNotNull(obj18Standing);
        Assertions.assertFalse(obj18Standing,
                "RideObject_SetRide must clear the previous object's standing bit when a later object "
                        + "wins the ride (docs/s2disasm/s2.asm:35999-36006)");
    }

    @Test
    public void htz2Obj18SidekickWalkOffClearsSagStandingBitAtRomFrame4104() throws Exception {
        Boolean tailsStanding = driveTrace("htz2", Sonic2ZoneConstants.ZONE_HTZ, 1,
                (trace, om, frame) -> {
                    if (frame != 4104) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedObj18 = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 34)
                            .filter(near -> parseObjectType(near.objectType()) == 0x18)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedObj18,
                            "HTZ2 ROM fixture should report Obj18 slot 34 at f4104");
                    Assertions.assertEquals(0, parseObjectType(expectedObj18.status()) & 0x18,
                            "ROM Obj18 standing bits are clear after Tails walks off the platform");
                    ARZPlatformObjectInstance actualObj18 =
                            om.activeObjectsOfType(ARZPlatformObjectInstance.class).stream()
                                    .filter(platform -> platform.getX() == 0x1860)
                                    .findFirst()
                                    .orElse(null);
                    Assertions.assertNotNull(actualObj18,
                            "Engine should have the HTZ2 Obj18 platform at x=$1860 by f4104");
                    AbstractPlayableSprite tails =
                            (AbstractPlayableSprite) GameServices.sprites().getSidekicks().get(0);
                    return om.hasObjectStandingBit(tails, actualObj18);
                });
        Assertions.assertNotNull(tailsStanding);
        Assertions.assertFalse(tailsStanding,
                "S2 Obj18 PlatformObject walk-off must clear Tails' standing bit before the next sag gate");
    }

    @Test
    public void htz2RisingLavaHurtClearsTailsPushButKeepsOnObjectAtRomFrame4165() throws Exception {
        StatusCheck statusCheck = driveTrace("htz2", Sonic2ZoneConstants.ZONE_HTZ, 1,
                (trace, om, frame) -> {
                    if (frame != 4165) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    Assertions.assertNotNull(expected.sidekick(),
                            "HTZ2 trace row f4165 must include Tails state");
                    Assertions.assertEquals(0x0A, expected.sidekick().statusByte(),
                            "ROM fixture should have Tails Status_InAir|Status_OnObj only at HTZ2 f4165");
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have a CPU Tails sidekick at HTZ2 f4165");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    return new StatusCheck(
                            expected.sidekick().statusByte(),
                            TraceCharacterState.statusByteFromSprite(tails),
                            tails.getPushing(),
                            tails.isOnObject(),
                            tails.getAir(),
                            tails.isHurt(),
                            String.format("tails=(x=%04X y=%04X xs=%04X ys=%04X) slots %s",
                                    tails.getCentreX() & 0xFFFF,
                                    tails.getCentreY() & 0xFFFF,
                                    tails.getXSpeed() & 0xFFFF,
                                    tails.getYSpeed() & 0xFFFF,
                                    describeSlots(om.occupiedDynamicSlotIds(), 36, 44)));
                });
        Assertions.assertNotNull(statusCheck);
        Assertions.assertEquals(statusCheck.expectedStatus(), statusCheck.actualStatus(),
                "S2 Obj30 subtype 6 hurts supported Tails after SolidObject_Always; Hurt_Sidekick "
                        + "clears Status_Push through ResetOnFloor_Part2 but leaves Status_OnObj "
                        + "for the next solid pass; " + statusCheck.summary());
        Assertions.assertFalse(statusCheck.pushing());
        Assertions.assertTrue(statusCheck.onObject());
        Assertions.assertTrue(statusCheck.air());
        Assertions.assertTrue(statusCheck.hurt());
    }

    @Test
    public void mtz3RotatingPlatformLoadKeepsRomSlot22Identity() throws Exception {
        SlotCheck slotCheck = driveTrace("mtz3", Sonic2ZoneConstants.ZONE_MTZ, 2,
                (trace, om, frame) -> {
                    if (frame != 1556) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x6E, expected.get(22),
                            "ROM fixture should load the MTZ large rotating platform into slot 22 at MTZ3 f1556");
                    return new SlotCheck(actual.get(22), describeSlots(actual, 16, 35));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x6E, slotCheck.actualId(),
                "MTZ3 slot 22 must remain the ROM Obj6E platform slot because "
                        + "TailsCPU_UpdateObjInteract dereferences interact(a0)=0x16 live; actual slots "
                        + slotCheck.summary());
    }

    @Test
    public void ooz1LauncherBallChainKeepsSourceBeforeTargetAtRomFrame5957() throws Exception {
        SlotWindowCheck slotCheck = driveTrace("ooz", Sonic2ZoneConstants.ZONE_OOZ, 0,
                (trace, om, frame) -> {
                    if (frame != 5957) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x48, expected.get(17),
                            "OOZ1 ROM fixture should keep the source LauncherBall in slot 17 at f5957");
                    Assertions.assertEquals(0x48, expected.get(18),
                            "OOZ1 ROM fixture should keep the target LauncherBall in slot 18 at f5957");
                    Assertions.assertEquals(0x48, actual.get(17),
                            "OOZ1 source LauncherBall must execute before the target ball at f5958; actual "
                                    + describeSlots(actual, 16, 22) + " live "
                                    + describeLiveSlots(om, 16, 35));
                    Assertions.assertEquals(0x48, actual.get(18),
                            "OOZ1 target LauncherBall must stay after the source ball at f5958; actual "
                                    + describeSlots(actual, 16, 22) + " live "
                                    + describeLiveSlots(om, 16, 35));
                    return new SlotWindowCheck(actual, describeSlots(actual, 16, 22)
                            + " live " + describeLiveSlots(om, 16, 22));
                });
        Assertions.assertNotNull(slotCheck);
    }

    @Test
    public void mtz3MovingPlatformUnloadReleasesRomSlot17() throws Exception {
        SlotCheck slotCheck = driveTrace("mtz3", Sonic2ZoneConstants.ZONE_MTZ, 2,
                (trace, om, frame) -> {
                    if (frame != 555) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertNull(expected.get(17),
                            "ROM fixture should unload MTZ Obj6A from slot 17 at MTZ3 f555");
                    return new SlotCheck(actual.get(17), describeSlots(actual, 16, 24));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertNull(slotCheck.actualId(),
                "MTZ Obj6A must unload from its ROM slot when objoff_32 leaves "
                        + "the MarkObjGone2 window; actual slots " + slotCheck.summary());
    }

    @Test
    public void mtz3TwinStomperNoContactClearsTailsPushAtRomFrame1743() throws Exception {
        PushCheck pushCheck = driveTrace("mtz3", Sonic2ZoneConstants.ZONE_MTZ, 2,
                (trace, om, frame) -> {
                    if (frame != 1743) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    Assertions.assertNotNull(expected.sidekick(),
                            "MTZ3 trace row f1743 must include Tails state");
                    Assertions.assertEquals(0, expected.sidekick().statusByte() & 0x20,
                            "ROM fixture should have cleared Tails Status_Push at MTZ3 f1743");
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have a CPU Tails sidekick at MTZ3 f1743");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    return new PushCheck(tails.getPushing(), tails.getCentreX(), tails.getCentreY(),
                            describeSlots(om.occupiedDynamicSlotIds(), 24, 28));
                });
        Assertions.assertNotNull(pushCheck);
        Assertions.assertFalse(pushCheck.pushing(),
                "S2 SolidObject_TestClearPush must clear Tails Status_Push when Obj64 "
                        + "is no longer contacting Tails at MTZ3 f1743; tails=("
                        + String.format("%04X,%04X", pushCheck.tailsX(), pushCheck.tailsY())
                        + ") nearby slots " + pushCheck.summary());
    }

    @Test
    public void mtz3CogAirborneStaleStandingBitKeepsTailsXSpeedAtRomFrame9555() throws Exception {
        SpeedCheck speedCheck = driveTrace("mtz3", Sonic2ZoneConstants.ZONE_MTZ, 2,
                (trace, om, frame) -> {
                    TraceFrame expected = trace.getFrame(frame);
                    if (expected.frame() != 9555) {
                        return null;
                    }
                    Assertions.assertNotNull(expected.sidekick(),
                            "MTZ3 trace row f9555 (CSV $2553) must include Tails state");
                    Assertions.assertEquals(0x07, expected.sidekick().statusByte(),
                            "ROM fixture should have Tails airborne/rolling after Obj70 stale-standing cleanup");
                    Assertions.assertEquals(0x2B, expected.sidekick().standOnObj(),
                            "ROM fixture should retain the Obj70 tooth slot latch at MTZ3 f9555");
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have a CPU Tails sidekick at MTZ3 f9555");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    return new SpeedCheck(
                            expected.sidekick().xSpeed(),
                            tails.getXSpeed(),
                            tails.getCentreX(),
                            tails.getCentreY(),
                            tails.getAir(),
                            tails.getRolling(),
                            describeSlots(om.occupiedDynamicSlotIds(), 27, 44));
                });
        Assertions.assertNotNull(speedCheck);
        Assertions.assertEquals(speedCheck.expectedXSpeed(), speedCheck.actualXSpeed(),
                "S2 Obj70 folds eight ROM SolidObject slots into one engine parent. "
                        + "At MTZ3 f9555 (CSV $2553) the ROM's slot-local standing-bit branch "
                        + "(docs/s2disasm/s2.asm:55084-55191, 35021-35040) returns d4=0 "
                        + "before a folded sibling side contact can stop Tails; "
                        + "tails=" + speedCheck.summary());
    }

    @Test
    public void mtz2CogAirborneLaunchKeepsTailsLeftwardSpeedAtRomFrame1217() throws Exception {
        CogLaunchCheck launchCheck = driveTrace("mtz2", Sonic2ZoneConstants.ZONE_MTZ, 1,
                (trace, om, frame) -> {
                    TraceFrame expected = trace.getFrame(frame);
                    if (expected.frame() != 1217) {
                        return null;
                    }
                    Assertions.assertNotNull(expected.sidekick(),
                            "MTZ2 trace row f1217 (CSV $04C1) must include Tails state");
                    Assertions.assertEquals(0x17, expected.sidekick().statusByte(),
                            "ROM fixture should have Tails airborne/rolling left after Obj70 contact");
                    Assertions.assertEquals(0x38, expected.sidekick().standOnObj(),
                            "ROM fixture should retain the MTZ2 Obj70 tooth slot latch at f1217");
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have a CPU Tails sidekick at MTZ2 f1217");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    return new CogLaunchCheck(
                            expected.sidekick().x(),
                            tails.getCentreX(),
                            expected.sidekick().xSpeed(),
                            tails.getXSpeed(),
                            expected.sidekick().gSpeed(),
                            tails.getGSpeed(),
                            describeSlots(om.occupiedDynamicSlotIds(), 36, 56));
                });
        Assertions.assertNotNull(launchCheck);
        Assertions.assertEquals(launchCheck.expectedXSpeed(), launchCheck.actualXSpeed(),
                "S2 Obj70 folded-tooth stale-latch suppression must not erase an already-applied "
                        + "leftward airborne launch. At MTZ2 f1217 the ROM keeps x_vel/g_inertia "
                        + "from the Obj70 contact instead of zeroing Tails; "
                        + "tails=" + launchCheck.summary());
        Assertions.assertEquals(launchCheck.expectedGSpeed(), launchCheck.actualGSpeed(),
                "S2 Obj70 must preserve Tails' ROM ground inertia on the MTZ2 f1217 launch; "
                        + "tails=" + launchCheck.summary());
    }

    @Test
    public void mcz2Obj75SpikeBallParentAndDisplayChildSurviveUntilTailsHit() throws Exception {
        SlotCheck slotCheck = driveTrace("mcz2", Sonic2ZoneConstants.ZONE_MCZ, 1,
                (trace, om, frame) -> {
                    if (frame != 6429) {
                        return null;
                    }
                    ObjectSpawnState state = obj75Mcz2SpikeBallState(om);
                    boolean matches = state != null
                            && state.active()
                            && !state.dormant()
                            && state.liveCount() >= 2;
                    return matches ? null : new SlotCheck(-1,
                            describeSlots(om.occupiedDynamicSlotIds(), 24, 36) + " | "
                                    + describeObj75Mcz2SpawnState(om));
                });
        Assertions.assertNull(slotCheck,
                () -> "MCZ2 Obj75 spike-ball parent/display child was not live at f6429; actual slots "
                        + slotCheck.summary());
    }

    @Test
    public void cnz2VerticalFlipperRightEdgeKeepsTailsPushBeforeCpuFollowAtRomFrame7983() throws Exception {
        PushCheck pushCheck = driveTrace("cnz2", Sonic2ZoneConstants.ZONE_CNZ, 1,
                (trace, om, frame) -> {
                    if (frame != 7983) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    Assertions.assertNotNull(expected.sidekick(),
                            "CNZ2 trace row f7983 must include Tails state");
                    Assertions.assertEquals(0x20, expected.sidekick().statusByte() & 0x20,
                            "ROM fixture should have Tails Status_Push set at CNZ2 f7983");
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have a CPU Tails sidekick at CNZ2 f7983");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    return new PushCheck(tails.getPushing(), tails.getCentreX(), tails.getCentreY(),
                            String.format("tails=(x=%04X y=%04X angle=%02X gs=%04X air=%s onObj=%s) slots %s",
                                    tails.getCentreX() & 0xFFFF,
                                    tails.getCentreY() & 0xFFFF,
                                    tails.getAngle() & 0xFF,
                                    tails.getGSpeed() & 0xFFFF,
                                    tails.getAir(),
                                    tails.isOnObject(),
                                    describeSlots(om.occupiedDynamicSlotIds(), 18, 25)));
                });
        Assertions.assertNotNull(pushCheck);
        Assertions.assertTrue(pushCheck.pushing(),
                "S2 Obj86 vertical flipper right-edge contact must leave Tails Status_Push set before "
                        + "the next TailsCPU_Normal follow pass; " + pushCheck.summary());
    }

    @Test
    public void cnz2SlopeRepelClearsTailsStalePushAtRomFrame8381() throws Exception {
        PushCheck pushCheck = driveTrace("cnz2", Sonic2ZoneConstants.ZONE_CNZ, 1,
                (trace, om, frame) -> {
                    if (frame != 8381) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    Assertions.assertNotNull(expected.sidekick(),
                            "CNZ2 trace row f8381 must include Tails state");
                    Assertions.assertEquals(0, expected.sidekick().statusByte() & 0x20,
                            "ROM fixture should have cleared Tails Status_Push at CNZ2 f8381");
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have a CPU Tails sidekick at CNZ2 f8381");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    return new PushCheck(tails.getPushing(), tails.getCentreX(), tails.getCentreY(),
                            String.format("tails=(x=%04X y=%04X angle=%02X gs=%04X air=%s onObj=%s moveLock=%d) slots %s",
                                    tails.getCentreX() & 0xFFFF,
                                    tails.getCentreY() & 0xFFFF,
                                    tails.getAngle() & 0xFF,
                                    tails.getGSpeed() & 0xFFFF,
                                    tails.getAir(),
                                    tails.isOnObject(),
                                    tails.getMoveLockTimer(),
                                    describeSlots(om.occupiedDynamicSlotIds(), 18, 25)));
                });
        Assertions.assertNotNull(pushCheck);
        Assertions.assertFalse(pushCheck.pushing(),
                "S2 Tails_SlopeRepel f8381 must expose the same-frame Animate_Tails "
                        + "Status_Push clear before trace comparison; " + pushCheck.summary());
    }

    private record SlotCheck(Integer actualId, String summary) {
    }

    private record PushCheck(boolean pushing, int tailsX, int tailsY, String summary) {
    }

    private record SpeedCheck(
            short expectedXSpeed,
            short actualXSpeed,
            int tailsX,
            int tailsY,
            boolean air,
            boolean rolling,
            String slots) {
        String summary() {
            return String.format("x=%04X y=%04X xs(exp=%04X act=%04X) air=%s rolling=%s slots %s",
                    tailsX & 0xFFFF,
                    tailsY & 0xFFFF,
                    expectedXSpeed & 0xFFFF,
                    actualXSpeed & 0xFFFF,
                    air,
                    rolling,
                    slots);
        }
    }

    private record CogLaunchCheck(
            int expectedX,
            int actualX,
            short expectedXSpeed,
            short actualXSpeed,
            short expectedGSpeed,
            short actualGSpeed,
            String slots) {
        String summary() {
            return String.format("x(exp=%04X act=%04X) xs(exp=%04X act=%04X) gs(exp=%04X act=%04X) slots %s",
                    expectedX & 0xFFFF,
                    actualX & 0xFFFF,
                    expectedXSpeed & 0xFFFF,
                    actualXSpeed & 0xFFFF,
                    expectedGSpeed & 0xFFFF,
                    actualGSpeed & 0xFFFF,
                    slots);
        }
    }

    private record StatusCheck(
            int expectedStatus,
            int actualStatus,
            boolean pushing,
            boolean onObject,
            boolean air,
            boolean hurt,
            String summary) {
    }

    private record ObjectSpawnState(boolean active, boolean dormant, int liveCount) {
    }

    private record RideCheck(int expectedY, int actualY, boolean actualAir, boolean actualOnObject) {
    }

    private record PushStatusCheck(boolean expectedPushing, boolean actualPushing, int expectedStatus,
                                   int actualStatus) {
    }

    private record DeadRideReleaseCheck(
            boolean onObject,
            boolean air,
            boolean riding,
            boolean objectStandingBit,
            String summary) {
    }

    @Test
    public void mtz1TwinStomperRetractionKeepsRiderOnPreMoveSurfaceAtRomFrame1267() throws Exception {
        RideCheck rideCheck = driveTrace("mtz", Sonic2ZoneConstants.ZONE_MTZ, 0,
                (trace, om, frame) -> {
                    if (frame != 1267) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    AbstractPlayableSprite sonic = Assertions.assertInstanceOf(
                            AbstractPlayableSprite.class,
                            GameServices.sprites().getSprite("sonic"),
                            "Engine fixture must have Sonic at MTZ1 f1267");
                    Assertions.assertEquals(0x1E, expected.standOnObj(),
                            "ROM fixture should have Sonic riding Obj64 in slot 30 at MTZ1 f1267");
                    return new RideCheck(expected.y() & 0xFFFF,
                            sonic.getCentreY() & 0xFFFF,
                            sonic.getAir(),
                            sonic.isOnObject());
                });
        Assertions.assertNotNull(rideCheck);
        Assertions.assertEquals(rideCheck.expectedY(), rideCheck.actualY(),
                "S2 Obj64 retraction should keep a continued top rider seated on the "
                        + "pre-update surface for the transition frame");
        Assertions.assertFalse(rideCheck.actualAir());
        Assertions.assertTrue(rideCheck.actualOnObject());
    }

    @Test
    public void mtz3DeadTailsObj6eStaleStandingBitClearsAtRomFrame3618() throws Exception {
        DeadRideReleaseCheck check = driveTrace("mtz3", Sonic2ZoneConstants.ZONE_MTZ, 2,
                (trace, om, frame) -> {
                    if (frame != 3618) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    Assertions.assertNotNull(expected.sidekick(),
                            "MTZ3 trace row f3618 must include Tails state");
                    Assertions.assertEquals(0, expected.sidekick().statusByte() & 0x08,
                            "ROM fixture should have cleared Tails Status_OnObj at MTZ3 f3618");
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have a CPU Tails sidekick at MTZ3 f3618");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    ObjectInstance obj6e = om.getActiveObjects().stream()
                            .filter(AbstractObjectInstance.class::isInstance)
                            .map(AbstractObjectInstance.class::cast)
                            .filter(instance -> instance.getSlotIndex() == 16)
                            .filter(instance -> instance.getSpawn() != null
                                    && (instance.getSpawn().objectId() & 0xFF) == 0x6E)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(obj6e,
                            "Engine fixture must still have the ridden Obj6E in slot 16 at MTZ3 f3618");
                    return new DeadRideReleaseCheck(
                            tails.isOnObject(),
                            tails.getAir(),
                            om.isRidingObject(tails),
                            om.hasObjectStandingBit(tails, obj6e),
                            describeSlots(om.occupiedDynamicSlotIds(), 16, 18));
                });
        Assertions.assertNotNull(check);
        Assertions.assertFalse(check.onObject(),
                "S2 SolidObject must clear dead Tails' stale Status_OnObj on Obj6E; slots "
                        + check.summary());
        Assertions.assertTrue(check.air());
        Assertions.assertFalse(check.riding(),
                "Dead Tails should not retain an engine riding record after the Obj6E stale clear; slots "
                        + check.summary());
        Assertions.assertFalse(check.objectStandingBit(),
                "Obj6E's sidekick standing bit must clear with Status_OnObj (docs/s2disasm/s2.asm:35022-35044)");
    }

    @Test
    public void arz2ChopChopLoadsIntoRomSlot19AfterBubbleBurstClears() throws Exception {
        SlotCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 458) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x91, expected.get(19),
                            "ROM fixture should load the ARZ2 ChopChop into slot 19 at f458");
                    return new SlotCheck(actual.get(19), describeSlots(actual, 16, 36));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x91, slotCheck.actualId(),
                "ARZ2 Obj91 must take ROM slot 19; lower slots must not be held by "
                        + "stale Obj24 bubble children. Actual slots " + slotCheck.summary());
    }

    @Test
    public void arz2ChopChopAnimalDoesNotMoveOnCreationFrame() throws Exception {
        AnimalPositionCheck check = animalPositionAtArz2Frame(549);
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "ARZ2 Obj28 should be born at the Obj27 position on its first DisplaySprite frame; slots "
                        + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj28_InitRandom branches to DisplaySprite and must not run ObjectMoveAndFall "
                        + "until routine 2 on the next object pass; slots " + check.summary());
    }

    @Test
    public void arz2ChopChopAnimalKeepsObjectMoveAndFallSubpixelCarry() throws Exception {
        AnimalPositionCheck check = animalPositionAtArz2Frame(553);
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj28 ObjectMoveAndFall should keep the animal at the Obj27 x-position while popping; slots "
                        + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj28 ObjectMoveAndFall must apply old y_vel through the ROM subpixel accumulator "
                        + "before gravity; slots " + check.summary());
    }

    @Test
    public void arz2ChopChopAnimalDoesNotWalkOnLandingTransitionFrame() throws Exception {
        AnimalPositionCheck check = animalPositionAtArz2Frame(593);
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj28_Main must branch to DisplaySprite after landing and must not run Obj28_Walk/Fly "
                        + "until the next ExecuteObjects pass; slots " + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj28_Main accepts only negative ObjCheckFloorDist (tst.w d1 / bpl.s DisplaySprite) "
                        + "before snapping and changing routine; slots " + check.summary());
    }

    @Test
    public void arz2ChopChopAnimalCarriesXSubpixelAfterLanding() throws Exception {
        AnimalPositionCheck check = animalPositionAtArz2Frame(595);
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj28_Walk calls ObjectMoveAndFall, whose longword x_pos update preserves x_sub "
                        + "(docs/s2disasm/s2.asm:24670-24673,30164-30174); slots " + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj28_Walk must keep sharing ObjectMoveAndFall vertical carry after landing; slots "
                        + check.summary());
    }

    @Test
    public void arz2ChopChopAnimalKeepsPriorRenderFlagUntilRomDeleteFrame() throws Exception {
        AnimalPositionCheck check = animalPositionAtArz2Frame(617);
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj28_Walk must not delete from a fresh post-move bounds check before ROM "
                        + "render_flags.on_screen clears; slots " + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj28_Walk uses the prior DisplaySprite render flag for deletion "
                        + "(docs/s2disasm/s2.asm:24670-24688); slots " + check.summary());
    }

    @Test
    public void arz2EggPrisonAnimalDoesNotWalkOnLandingTransitionFrame() throws Exception {
        AnimalPositionCheck check = animalPositionAtArz2Frame(7064, 0x1C);
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj28_Main must switch the prison animal to its walking/flying routine "
                        + "without running Obj28_Walk/Fly until the next object pass; slots " + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj28_Main accepts only negative ObjCheckFloorDist (tst.w d1 / bpl.s DisplaySprite) "
                        + "for prison animals too; slots " + check.summary());
    }

    @Test
    public void arz2ChopChopAnimalFreesSlotWhenRenderFlagClears() throws Exception {
        SlotCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 626) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x0A, expected.get(24),
                            "ROM fixture should reuse slot 24 for the mouth bubble after Obj28 deletes at f626");
                    return new SlotCheck(actual.get(24), describeSlots(actual, 19, 25));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x0A, slotCheck.actualId(),
                "S2 Obj28_Walk deletes when render_flags.on_screen is clear; the animal must free slot 24 "
                        + "for the next AllocateObject bubble (docs/s2disasm/s2.asm:24570-24594,24670-24688). "
                        + "Actual slots " + slotCheck.summary());
    }

    @Test
    public void arz2DynamicSlotOccupancyMatchesThroughArrowShooterStream() throws Exception {
        SlotWindowCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 687) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x8F, expected.get(61),
                            "ROM fixture should allocate Obj8F Grounder wall child into slot 61 at f687");
                    Assertions.assertEquals(0x8F, expected.get(62),
                            "ROM fixture should allocate Obj8F Grounder wall child into slot 62 at f687");
                    Assertions.assertEquals(0x8F, expected.get(63),
                            "ROM fixture should allocate Obj8F Grounder wall child into slot 63 at f687");
                    Assertions.assertEquals(0x0A, expected.get(64),
                            "ROM fixture should allocate the next Obj0A mouth bubble into slot 64 at f687");
                    return new SlotWindowCheck(actual, describeSlots(actual, 57, 64));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(0x8F, check.idAt(61),
                "S2 Obj8D loc_36C64 uses AllocateObject for Obj8F wall pieces before the "
                        + "same-frame Obj0A mouth bubble; actual slots " + check.summary());
        Assertions.assertEquals(0x8F, check.idAt(62),
                "S2 Obj8D loc_36C64 uses AllocateObject for Obj8F wall pieces before the "
                        + "same-frame Obj0A mouth bubble; actual slots " + check.summary());
        Assertions.assertEquals(0x8F, check.idAt(63),
                "S2 Obj8D loc_36C64 uses AllocateObject for Obj8F wall pieces before the "
                        + "same-frame Obj0A mouth bubble; actual slots " + check.summary());
        Assertions.assertEquals(0x0A, check.idAt(64),
                "Obj0A should take the next lowest slot after the Obj8F wall pieces; actual slots "
                        + check.summary());
    }

    @Test
    public void arz2ArrowProjectileAllocatesInRomSlot65OnRomFrame696() throws Exception {
        SlotProjectileCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 694 && frame != 695 && frame != 696) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    if (frame == 694 || frame == 695) {
                        Assertions.assertNull(expected.get(65),
                                "ROM fixture should not allocate the Obj22 arrow into slot 0x41 until f696");
                        Assertions.assertNull(actual.get(65),
                                "S2 Obj22 should not reserve the arrow projectile SST slot before "
                                        + "Obj22_ShootArrow runs (docs/s2disasm/s2.asm:51570-51587); "
                                        + "actual slots " + describeSlots(actual, 60, 65));
                        return null;
                    }

                    TraceEvent.ObjectNear expectedArrow = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 65)
                            .filter(near -> parseObjectType(near.objectType()) == 0x22)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedArrow,
                            "ARZ2 ROM fixture should report the first Obj22 arrow in slot 0x41 at f696");
                    ArrowProjectileInstance actualArrow = om.activeObjectsOfType(ArrowProjectileInstance.class)
                            .stream()
                            .filter(arrow -> arrow.getSlotIndex() == 65)
                            .findFirst()
                            .orElse(null);
                    return new SlotProjectileCheck(actual.get(65),
                            actualArrow == null ? -1 : actualArrow.getX(),
                            expectedArrow.x() & 0xFFFF,
                            describeSlots(actual, 60, 65));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x22, slotCheck.actualId(),
                "ARZ2 slot 0x41 should first contain Obj22 when ROM Obj22_ShootArrow allocates "
                        + "the arrow at f696; actual slots " + slotCheck.summary());
        Assertions.assertEquals(slotCheck.expectedX(), slotCheck.actualX(),
                "S2 Obj22_Arrow_Init falls through into Obj22_Arrow/ObjectMove on the allocation "
                        + "frame, so the first visible arrow X is already advanced by $400 "
                        + "(docs/s2disasm/s2.asm:51590-51607); actual slots " + slotCheck.summary());
    }

    @Test
    public void arz2SecondArrowProjectileAllocatesInRomLowSlotOnRomFrame796() throws Exception {
        SlotProjectileCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 796) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    TraceEvent.ObjectNear expectedArrow = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 18)
                            .filter(near -> parseObjectType(near.objectType()) == 0x22)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedArrow,
                            "ARZ2 ROM fixture should allocate the second Obj22 arrow in low slot 0x12 at f796");
                    ArrowProjectileInstance actualArrow = om.activeObjectsOfType(ArrowProjectileInstance.class)
                            .stream()
                            .filter(arrow -> arrow.getSlotIndex() == 18)
                            .findFirst()
                            .orElse(null);
                    return new SlotProjectileCheck(actual.get(18),
                            actualArrow == null ? -1 : actualArrow.getX(),
                            expectedArrow.x() & 0xFFFF,
                            "expected " + describeSlots(expected, 18, 42)
                                    + " actual " + describeSlots(actual, 18, 42));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x22, slotCheck.actualId(),
                "S2 Obj22_ShootArrow must use AllocateObject/lowest-free semantics; at f796 the "
                        + "free ROM slot is below the shooter, so the projectile belongs in slot 0x12. "
                        + "Actual slots " + slotCheck.summary());
        Assertions.assertEquals(slotCheck.expectedX(), slotCheck.actualX(),
                "A lower-slot Obj22 child has already been passed by ExecuteObjects on its allocation "
                        + "frame, so it must remain at the shooter x_pos until the next frame "
                        + "(docs/s2disasm/s2.asm:51570-51607); actual slots " + slotCheck.summary());
    }

    @Test
    public void arz2ArrowProjectileUsesRomWallProbeAtRomFrame844() throws Exception {
        SlotProjectileCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 844) {
                        return null;
                    }
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    TraceEvent.ObjectNear expectedArrow = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 65)
                            .filter(near -> parseObjectType(near.objectType()) == 0x22)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedArrow,
                            "ARZ2 ROM fixture should still report the first Obj22 arrow in slot 0x41 at f844");
                    ArrowProjectileInstance actualArrow = om.activeObjectsOfType(ArrowProjectileInstance.class)
                            .stream()
                            .filter(arrow -> arrow.getSlotIndex() == 65)
                            .findFirst()
                            .orElse(null);
                    return new SlotProjectileCheck(actual.get(65),
                            actualArrow == null ? -1 : actualArrow.getX(),
                            expectedArrow.x() & 0xFFFF,
                            describeSlots(actual, 60, 65));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x22, slotCheck.actualId(),
                "S2 Obj22 arrow must survive through the ROM opposite-side wall probe at f844; "
                        + "actual slots " + slotCheck.summary());
        Assertions.assertEquals(slotCheck.expectedX(), slotCheck.actualX(),
                "S2 Obj22_Arrow checks ObjCheckLeftWallDist at x_pos-8 for a right-moving arrow, "
                        + "not the right wall at x_pos+8 (docs/s2disasm/s2.asm:51607-51623); "
                        + "actual slots " + slotCheck.summary());
    }

    @Test
    public void arz2PlatformBobUsesRomStandingLatchOnJumpFrame888() throws Exception {
        PlatformPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 888) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedPlatform = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 0x1F)
                            .filter(near -> parseObjectType(near.objectType()) == 0x18)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedPlatform,
                            "ARZ2 ROM fixture should report the ridden Obj18 platform in slot 0x1F at f888");
                    ARZPlatformObjectInstance actualPlatform = om.activeObjectsOfType(ARZPlatformObjectInstance.class)
                            .stream()
                            .filter(platform -> platform.getSlotIndex() == 0x1F)
                            .findFirst()
                            .orElse(null);
                    return new PlatformPositionCheck(
                            expectedPlatform.x() & 0xFFFF,
                            expectedPlatform.y() & 0xFFFF,
                            actualPlatform == null ? -1 : actualPlatform.getX(),
                            actualPlatform == null ? -1 : actualPlatform.getY(),
                            describeSlots(om.occupiedDynamicSlotIds(), 0x1B, 0x24));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "ARZ2 Obj18 slot 0x1F should keep ROM X on Sonic's jump-off frame; slots "
                        + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj18_TopSolid reads status(a0)&standing_mask before PlatformObject clears "
                        + "the jump-off ride, so Obj18_Nudge must use the prior standing latch "
                        + "at f888 (docs/s2disasm/s2.asm:23219-23243,23311-23320); slots "
                        + check.summary());
    }

    @Test
    public void arz2LeafParticlesDoNotDisplaceMouthBubbleSlotOnRomFrame723() throws Exception {
        SlotWindowCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 723) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x0A, expected.get(17),
                            "ARZ2 ROM fixture should allocate the f723 mouth bubble into slot 0x11 "
                                    + "after Obj2C leaves have observed render_flags.on_screen and deleted");
                    ObjectOccupancyOracle.Divergence divergence =
                            ObjectOccupancyOracle.firstDivergence(trace, om, frame, FIRST_DYNAMIC_SLOT);
                    Assertions.assertNull(divergence,
                            "ARZ2 dynamic slots should still match at f723 after Obj2C_Leaf deletes "
                                    + "through render_flags.on_screen "
                                    + "(docs/s2disasm/s2.asm:52232-52237); expected "
                                    + describeSlots(expected, 16, 22) + " actual "
                                    + describeSlots(actual, 16, 22));
                    return new SlotWindowCheck(actual, describeSlots(actual, 16, 22));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x0A, slotCheck.idAt(17),
                "Obj0A mouth bubble should take the ROM slot 0x11 once off-screen Obj2C leaves "
                        + "delete through the render-flag path; actual slots " + slotCheck.summary());
    }

    @Test
    public void arz2ChopChopEmitsPatrolBubbleIntoRomSlot19AtFrame598() throws Exception {
        SlotBubbleCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 598) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    TraceEvent.ObjectAppeared expectedBubble = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectAppeared.class::isInstance)
                            .map(TraceEvent.ObjectAppeared.class::cast)
                            .filter(appeared -> appeared.slot() == 19)
                            .filter(appeared -> parseObjectType(appeared.objectType()) == 0x0A)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedBubble,
                            "ARZ2 ROM fixture should allocate the f598 Obj91 patrol bubble into slot 0x13");
                    BreathingBubbleInstance actualBubble = om.activeObjectsOfType(BreathingBubbleInstance.class)
                            .stream()
                            .filter(bubble -> bubble.getSlotIndex() == 19)
                            .findFirst()
                            .orElse(null);
                    return new SlotBubbleCheck(actual.get(19),
                            actualBubble == null ? -1 : actualBubble.getX(),
                            actualBubble == null ? -1 : actualBubble.getY(),
                            expectedBubble.x() & 0xFFFF,
                            expectedBubble.y() & 0xFFFF,
                            describeSlots(expected, 16, 26),
                            describeSlots(actual, 16, 26));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x0A, slotCheck.actualId(),
                "Obj91 must spawn its Obj0A patrol bubble through the normal free-slot path at f598; "
                        + "expected slots " + slotCheck.expectedSummary()
                        + " actual slots " + slotCheck.actualSummary());
        Assertions.assertEquals(slotCheck.expectedX(), slotCheck.actualX(),
                "Obj91_MakeBubble offsets x_pos by 0x14 from the ChopChop mouth "
                        + "(docs/s2disasm/s2.asm:73751-73764)");
        Assertions.assertEquals(slotCheck.expectedY(), slotCheck.actualY(),
                "Obj91_MakeBubble offsets y_pos by +6 from the ChopChop mouth "
                        + "(docs/s2disasm/s2.asm:73765-73769)");
    }

    @Test
    public void arz2ChopChopBubbleSurvivesFirstObj0aInitPassAtFrame599() throws Exception {
        SlotBubbleCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 599) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x0A, expected.get(19),
                            "ROM fixture should still hold the f598 Obj91 patrol bubble in slot 0x13 "
                                    + "after its first Obj0A pass at f599");
                    BreathingBubbleInstance actualBubble = om.activeObjectsOfType(BreathingBubbleInstance.class)
                            .stream()
                            .filter(bubble -> bubble.getSlotIndex() == 19)
                            .findFirst()
                            .orElse(null);
                    return new SlotBubbleCheck(actual.get(19),
                            actualBubble == null ? -1 : actualBubble.getX(),
                            actualBubble == null ? -1 : actualBubble.getY(),
                            -1,
                            -1,
                            describeSlots(expected, 16, 26),
                            describeSlots(actual, 16, 26));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x0A, slotCheck.actualId(),
                "Obj0A_Init sets render_flags on_screen|level_fg before Obj0A_ChkWater tests "
                        + "render_flags.on_screen, so a lower-slot child not executed on its spawn frame "
                        + "must not observe a cleared render bit on its first pass "
                        + "(docs/s2disasm/s2.asm:41888,41951). Expected slots "
                        + slotCheck.expectedSummary() + " actual slots " + slotCheck.actualSummary());
    }

    @Test
    public void arz2WhispSlot13MatchesRomSubpixelCarryAtFrame1225() throws Exception {
        AnimalPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 1225) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedWhisp = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 0x13)
                            .filter(near -> parseObjectType(near.objectType()) == 0x8C)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedWhisp,
                            "ARZ2 ROM fixture should report Obj8C Whisp in slot 0x13 at f1225");
                    WhispBadnikInstance actualWhisp = om.activeObjectsOfType(WhispBadnikInstance.class)
                            .stream()
                            .filter(whisp -> whisp.getSlotIndex() == 0x13)
                            .findFirst()
                            .orElse(null);
                    return new AnimalPositionCheck(expectedWhisp.x() & 0xFFFF, expectedWhisp.y() & 0xFFFF,
                            actualWhisp == null ? -1 : actualWhisp.getX(),
                            actualWhisp == null ? -1 : actualWhisp.getY(),
                            describeSlots(om.occupiedDynamicSlotIds(), 0x10, 0x1A));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "ARZ2 Obj8C slot 0x13 X should still match ROM at f1225; slots "
                        + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj8C ObjectMove must preserve the ROM y_sub carry phase for slot 0x13 "
                        + "at ARZ2 f1225 (docs/s2disasm/s2.asm:73231-73249,30191-30204); "
                        + "slots " + check.summary());
    }

    private record SlotWindowCheck(Map<Integer, Integer> slots, String summary) {
        int idAt(int slot) {
            return slots.getOrDefault(slot, -1);
        }
    }

    private record SlotProjectileCheck(Integer actualId, int actualX, int expectedX, String summary) {
    }

    private record SlotBubbleCheck(
            Integer actualId,
            int actualX,
            int actualY,
            int expectedX,
            int expectedY,
            String expectedSummary,
            String actualSummary) {
    }

    private record PlatformPositionCheck(int expectedX, int expectedY, int actualX, int actualY, String summary) {
    }

    private AnimalPositionCheck animalPositionAtArz2Frame(int targetFrame) throws Exception {
        return animalPositionAtArz2Frame(targetFrame, 24);
    }

    private AnimalPositionCheck animalPositionAtArz2Frame(int targetFrame, int expectedSlot) throws Exception {
        return driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != targetFrame) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedAnimal = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == expectedSlot)
                            .filter(near -> parseObjectType(near.objectType()) == 0x28)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedAnimal,
                            () -> String.format(
                                    "ARZ2 ROM fixture should report an Obj28 animal in slot %d at f%d",
                                    expectedSlot, targetFrame));

                    AbstractObjectInstance actualAnimal = om.getActiveObjects().stream()
                            .filter(AbstractObjectInstance.class::isInstance)
                            .map(AbstractObjectInstance.class::cast)
                            .filter(animal -> animal.getSlotIndex() == expectedSlot)
                            .filter(animal -> {
                                ObjectSpawn spawn = animal.getSpawn();
                                return spawn != null && spawn.objectId() == 0x28;
                            })
                            .findFirst()
                            .orElse(null);
                    return new AnimalPositionCheck(expectedAnimal.x() & 0xFFFF, expectedAnimal.y() & 0xFFFF,
                            actualAnimal == null ? -1 : actualAnimal.getX(),
                            actualAnimal == null ? -1 : actualAnimal.getY(),
                            describeSlots(om.occupiedDynamicSlotIds(),
                                    Math.max(FIRST_DYNAMIC_SLOT, expectedSlot - 5), expectedSlot + 5));
                });
    }

    private record AnimalPositionCheck(int expectedX, int expectedY, int actualX, int actualY, String summary) {
    }

    private static int parseObjectType(String objectType) {
        if (objectType == null || objectType.isBlank()) {
            return -1;
        }
        return Integer.parseInt(objectType.replace("0x", "").replace("0X", "").trim(), 16) & 0xFF;
    }

    private static String describeSlots(Map<Integer, Integer> occupancy, int firstSlot, int lastSlot) {
        StringBuilder sb = new StringBuilder();
        for (int slot = firstSlot; slot <= lastSlot; slot++) {
            Integer id = occupancy.get(slot);
            if (id == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(slot).append(':').append(String.format("%02X", id & 0xFF));
        }
        return sb.toString();
    }

    private static String describeLiveSlots(ObjectManager objectManager, int firstSlot, int lastSlot) {
        StringBuilder sb = new StringBuilder();
        objectManager.getActiveObjects().stream()
                .filter(AbstractObjectInstance.class::isInstance)
                .map(AbstractObjectInstance.class::cast)
                .filter(instance -> instance.getSlotIndex() >= firstSlot
                        && instance.getSlotIndex() <= lastSlot)
                .sorted(java.util.Comparator.comparingInt(AbstractObjectInstance::getSlotIndex))
                .forEach(instance -> {
                    if (!sb.isEmpty()) {
                        sb.append(' ');
                    }
                    ObjectSpawn spawn = instance.getSpawn();
                    int id = spawn == null ? -1 : spawn.objectId();
                    int x = spawn == null ? -1 : spawn.x();
                    int y = spawn == null ? -1 : spawn.y();
                    sb.append(String.format("s%d:%02X@%04X,%04X/%s",
                            instance.getSlotIndex(),
                            id & 0xFF,
                            x & 0xFFFF,
                            y & 0xFFFF,
                            instance.getName()));
                });
        return sb.toString();
    }

    private static String describeObj75Mcz2SpawnState(ObjectManager objectManager) {
        StringBuilder sb = new StringBuilder("obj75-spawns");
        for (var spawn : objectManager.getAllSpawns()) {
            if (spawn.objectId() == 0x75
                    && spawn.x() >= 0x1700
                    && spawn.x() <= 0x1800) {
                if (sb.length() > "obj75-spawns".length()) {
                    sb.append(' ');
                } else {
                    sb.append(' ');
                }
                int liveSlot = objectManager.getActiveObjects().stream()
                        .filter(AbstractObjectInstance.class::isInstance)
                        .map(AbstractObjectInstance.class::cast)
                        .filter(instance -> instance.getSpawn() != null)
                        .filter(instance -> instance.getSpawn().layoutIndex() == spawn.layoutIndex())
                        .mapToInt(AbstractObjectInstance::getSlotIndex)
                        .findFirst()
                        .orElse(-1);
                sb.append(String.format("i%d %02X@%04X,%04X sub=%02X active=%s dorm=%s rem=%s live=s%d",
                        spawn.layoutIndex(),
                        spawn.objectId(),
                        spawn.x(),
                        spawn.y(),
                        spawn.subtype(),
                        objectManager.getActiveSpawns().contains(spawn),
                        objectManager.isDormant(spawn),
                        objectManager.isRemembered(spawn),
                        liveSlot));
            }
        }
        return sb.toString();
    }

    private static ObjectSpawnState obj75Mcz2SpikeBallState(ObjectManager objectManager) {
        for (var spawn : objectManager.getAllSpawns()) {
            if (spawn.objectId() == 0x75
                    && spawn.x() == 0x1740
                    && spawn.y() == 0x0690
                    && spawn.subtype() == 0x17) {
                int liveCount = (int) objectManager.getActiveObjects().stream()
                        .filter(AbstractObjectInstance.class::isInstance)
                        .map(AbstractObjectInstance.class::cast)
                        .filter(instance -> instance.getSpawn() != null)
                        .filter(instance -> instance.getSpawn().layoutIndex() == spawn.layoutIndex())
                        .count();
                return new ObjectSpawnState(
                        objectManager.getActiveSpawns().contains(spawn),
                        objectManager.isDormant(spawn),
                        liveCount);
            }
        }
        return null;
    }

    @Test
    public void scz1TransientOccupancyMatchesRom() throws Exception {
        assertTransientOccupancy("scz", Sonic2ZoneConstants.ZONE_SCZ, 0);
    }

    @Test
    public void wfz1TransientOccupancyMatchesRom() throws Exception {
        assertTransientOccupancy("wfz", Sonic2ZoneConstants.ZONE_WFZ, 0);
    }

    /**
     * Asserts that for every replayed frame of the named green S2 trace, the
     * engine never holds MORE live instances of a self-deleting transient
     * ({@link #TRANSIENT_SELF_DELETE_IDS}) than the ROM timeline — i.e. the
     * transient never self-deletes LATER than the ROM {@code DeleteObject}. This
     * is exactly the piece-(a) regression the explosion fix closes (the old
     * uniform 8-frame delay made the explosion linger 4 frames). Counting by id
     * ignores slot reshuffle; restricting to {@code engineCount > romCount}
     * ignores the {@code engineCount < romCount} spawn-frame windowing offset
     * that belongs to piece (b).
     */
    private void assertTransientOccupancy(String route, int zone, int act) throws Exception {
        ObjectOccupancyOracle.CountDivergence first = driveTrace(route, zone, act,
                (trace, om, frame) -> ObjectOccupancyOracle.firstTransientCountDivergence(
                        trace, om, frame, FIRST_DYNAMIC_SLOT, TRANSIENT_SELF_DELETE_IDS, true));
        Assertions.assertNull(first, () -> first == null ? "" : String.format(
                "[occupancy-oracle] %s transient lingers past its ROM DeleteObject: "
                        + "frame=%d id=0x%02X romCount=%d engineCount=%d "
                        + "(scope=Obj27; engineCount>romCount = late self-delete)",
                route.toUpperCase(), first.frame(), first.id(),
                first.romCount(), first.engineCount()));
    }

    /** Per-frame comparison-only probe over the driven engine + ROM timeline. */
    @FunctionalInterface
    private interface FrameProbe<T> {
        T check(TraceData trace, ObjectManager om, int frame);
    }

    @Test
    public void ehz1TransientOccupancyMatchesRom() throws Exception {
        // EHZ1 is a green S2 trace (Sonic+Tails). Assert transient self-delete
        // occupancy (Obj27/Obj29) frame-for-frame against the ROM timeline.
        Path traceDir = Path.of("src/test/resources/traces/s2").resolve("ehz1_fullrun");
        Assumptions.assumeTrue(Files.isDirectory(traceDir),
                "EHZ1 trace directory not found: " + traceDir);
        assertTransientOccupancy("ehz1_fullrun", Sonic2ZoneConstants.ZONE_EHZ, 0);
    }

    /**
     * Unscoped slot-occupancy measurement (every slot divergence) used by the
     * non-asserting MTZ1 frontier probe.
     */
    private ObjectOccupancyOracle.Divergence measureFirstDivergence(
            String route, int zone, int act, Set<Integer> unused) throws Exception {
        return driveTrace(route, zone, act,
                (trace, om, frame) -> ObjectOccupancyOracle.firstDivergence(
                        trace, om, frame, FIRST_DYNAMIC_SLOT));
    }

    @Test
    public void arz2BackwardPostCameraCatchupIncludesOldLeftEdgeClusterAtFrame1992() throws Exception {
        SlotWindowCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 1992) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    return new SlotWindowCheck(actual,
                            "expected " + describeSlots(expected, 16, 60)
                                    + " actual " + describeSlots(actual, 16, 60)
                                    + " live " + describeLiveSlots(om, 16, 60));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x2C, slotCheck.idAt(44),
                "S2 ObjectsManager_GoingBackward loads streamed objects whose x_pos is greater "
                        + "than the new left edge, including entries equal to the prior left edge; "
                        + "Obj2C at x=$1400 must take slot 44 before later frees move allocation "
                        + "to slot 23 (docs/s2disasm/s2.asm:33050-33067); " + slotCheck.summary());
        Assertions.assertEquals(0x03, slotCheck.idAt(45),
                "The x=$1400 layer switcher belongs to the same backward streamed cluster; "
                        + slotCheck.summary());
        Assertions.assertEquals(0x26, slotCheck.idAt(46),
                "The x=$1400 monitor belongs to the same backward streamed cluster; "
                        + slotCheck.summary());
    }

    @Test
    public void arz2GrounderRocksFreeSlotsBeforeFrame1648PlacementCluster() throws Exception {
        SlotWindowCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 1648) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    ObjectOccupancyOracle.Divergence divergence =
                            ObjectOccupancyOracle.firstDivergence(trace, om, frame, FIRST_DYNAMIC_SLOT);
                    Assertions.assertNull(divergence,
                            "ARZ2 dynamic slots should still match at f1648 after Obj90 rocks "
                                    + "delete through the prior render_flags.on_screen gate "
                                    + "(docs/s2disasm/s2.asm:73490-73494); expected "
                                    + describeSlots(expected, 16, 40) + " actual "
                                    + describeSlots(actual, 16, 40));
                    return new SlotWindowCheck(actual, describeSlots(actual, 16, 40));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x1F, slotCheck.idAt(18),
                "Obj90 rocks must not occupy slot 0x12 when the f1648 placement cluster "
                        + "streams in; actual slots " + slotCheck.summary());
        Assertions.assertEquals(0x03, slotCheck.idAt(32),
                "The second stale Obj90 rock must be gone before slot 0x20 is reused by "
                        + "the ROM layer switcher; actual slots " + slotCheck.summary());
    }

    @Test
    public void arz2SkidDustDoesNotAllocateExtraSlot20AfterRomFixedDustDeletes() throws Exception {
        ObjectOccupancyOracle.Divergence divergence =
                driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                        (trace, om, frame) -> {
                            if (frame != 1698) {
                                return null;
                            }
                            Map<Integer, Integer> expected =
                                    ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                            Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                            ObjectOccupancyOracle.Divergence first =
                                    ObjectOccupancyOracle.firstDivergence(trace, om, frame, FIRST_DYNAMIC_SLOT);
                            Assertions.assertFalse(expected.containsKey(20),
                                    "ROM Obj08 fixed dust slot 20 should have deleted by ARZ2 f1698 "
                                            + "(docs/s2disasm/s2.asm:42759-42797)");
                            if (first != null) {
                                Assertions.fail("ARZ2 dynamic slots should still match at f1698 after "
                                        + "ROM Obj08_CheckSkid stops ticking when Stop animation ends "
                                        + "(docs/s2disasm/s2.asm:42759-42797); first divergence "
                                        + String.format("slot=%d expected=0x%02X actual=0x%02X",
                                        first.slot(), first.expectedId() & 0xFF, first.actualId() & 0xFF)
                                        + " expected " + describeSlots(expected, 16, 40)
                                        + " actual " + describeSlots(actual, 16, 40));
                            }
                            return null;
                        });
        Assertions.assertNull(divergence);
    }

    @Test
    public void arz2TailsSkidDustAppliesRomShorterSpriteYOffsetAtFrame4920() throws Exception {
        AnimalPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 4920) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedDust = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 0x14)
                            .filter(near -> parseObjectType(near.objectType()) == 0x08)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedDust,
                            "ARZ2 ROM fixture should report Obj08 skid dust slot 0x14 at f4920");
                    SkidDustObjectInstance actualDust = om.activeObjectsOfType(SkidDustObjectInstance.class)
                            .stream()
                            .filter(dust -> dust.getSlotIndex() == 0x14)
                            .findFirst()
                            .orElse(null);
                    return new AnimalPositionCheck(
                            expectedDust.x() & 0xFFFF,
                            expectedDust.y() & 0xFFFF,
                            actualDust == null ? -1 : actualDust.getX(),
                            actualDust == null ? -1 : actualDust.getY(),
                            describeSlots(om.occupiedDynamicSlotIds(), 0x10, 0x18));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj08 skid dust X should still use the parent x_pos; slots " + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj08_SkidDust adds $10 to parent y_pos, then subtracts 4 for Tails' shorter "
                        + "sprite before allocating the child dust "
                        + "(docs/s2disasm/s2.asm:42821-42841); slots " + check.summary());
    }

    @Test
    public void arz2GrounderWallWaitsOneFrameAfterActivationBeforeMoving() throws Exception {
        AnimalPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 1712) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedWall = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 38)
                            .filter(near -> parseObjectType(near.objectType()) == 0x8F)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedWall,
                            "ARZ2 ROM fixture should report Obj8F wall slot 0x26 at activation frame 1712");
                    GrounderWallInstance actualWall = om.activeObjectsOfType(GrounderWallInstance.class)
                            .stream()
                            .filter(wall -> wall.getSlotIndex() == 38)
                            .findFirst()
                            .orElse(null);
                    return new AnimalPositionCheck(
                            expectedWall.x() & 0xFFFF,
                            expectedWall.y() & 0xFFFF,
                            actualWall == null ? -1 : actualWall.getX(),
                            actualWall == null ? -1 : actualWall.getY(),
                            describeSlots(om.occupiedDynamicSlotIds(), 35, 41));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj8F loc_36BA6 only latches velocity when parent objoff_2B becomes nonzero; "
                        + "Obj8F_Move does not run until the next frame "
                        + "(docs/s2disasm/s2.asm:73424-73437); slots " + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj8F activation frame should keep the original wall Y until routine 4 runs "
                        + "(docs/s2disasm/s2.asm:73424-73437); slots " + check.summary());
    }

    @Test
    public void arz2GrounderWallUsesApproximateBuildSpritesYBandBeforeDelete() throws Exception {
        AnimalPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 1760) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedWall = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 0x28)
                            .filter(near -> parseObjectType(near.objectType()) == 0x8F)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedWall,
                            "ARZ2 ROM fixture should still report Obj8F wall slot 0x28 at frame 1760");
                    GrounderWallInstance actualWall = om.activeObjectsOfType(GrounderWallInstance.class)
                            .stream()
                            .filter(wall -> wall.getSlotIndex() == 0x28)
                            .findFirst()
                            .orElse(null);
                    return new AnimalPositionCheck(
                            expectedWall.x() & 0xFFFF,
                            expectedWall.y() & 0xFFFF,
                            actualWall == null ? -1 : actualWall.getX(),
                            actualWall == null ? -1 : actualWall.getY(),
                            describeSlots(om.occupiedDynamicSlotIds(), 0x26, 0x29));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj8F should survive through ARZ2 f1760 before Obj8F_Move observes "
                        + "the previous BuildSprites on-screen bit; slots " + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 BuildSprites approximate Y check keeps non-explicit-height sprites "
                        + "visible with a 32px band before Obj8F_Move's next-frame delete "
                        + "(docs/s2disasm/s2.asm:30569-30588,73489-73494); slots "
                        + check.summary());
    }

    @Test
    public void arz2GrounderRockUsesObjectMoveAndFallOldVelocityOrder() throws Exception {
        AnimalPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 1716) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedRock = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 20)
                            .filter(near -> parseObjectType(near.objectType()) == 0x90)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedRock,
                            "ARZ2 ROM fixture should report Obj90 rock slot 0x14 at frame 1716");
                    GrounderRockProjectile actualRock = om.activeObjectsOfType(GrounderRockProjectile.class)
                            .stream()
                            .filter(rock -> rock.getSlotIndex() == 20)
                            .findFirst()
                            .orElse(null);
                    return new AnimalPositionCheck(
                            expectedRock.x() & 0xFFFF,
                            expectedRock.y() & 0xFFFF,
                            actualRock == null ? -1 : actualRock.getX(),
                            actualRock == null ? -1 : actualRock.getY(),
                            describeSlots(om.occupiedDynamicSlotIds(), 20, 30));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj90 rock X should follow ObjectMoveAndFall at ARZ2 f1716; slots "
                        + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 ObjectMoveAndFall reads old y_vel for movement, then adds gravity "
                        + "(docs/s2disasm/s2.asm:30163-30177); slots " + check.summary());
    }

    @Test
    public void arz2SkidDustReusesFreedSlot18AtRomFrame1993() throws Exception {
        SlotWindowCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 1993) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x08, expected.get(18),
                            "ROM fixture should reuse slot 0x12 for Obj08 skid dust at ARZ2 f1993");
                    return new SlotWindowCheck(actual, describeSlots(actual, 16, 56));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x08, slotCheck.idAt(18),
                "S2 Obj08 skid dust must use the lowest free ROM slot after Obj82 unloads at f1993; "
                        + "actual slots "
                        + slotCheck.summary());
    }

    @Test
    public void arz2BossRoomSkidDustReusesFreedArrowSlotsAfterRelease() throws Exception {
        SlotWindowCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 6313) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    ObjectOccupancyOracle.Divergence first =
                            ObjectOccupancyOracle.firstDivergence(trace, om, frame, FIRST_DYNAMIC_SLOT);
                    Assertions.assertNull(first,
                            "ARZ2 boss-room Obj08 skid dust should reuse the lowest slots freed by "
                                    + "the Obj89 arrow release before ObjectsManager loads later objects; "
                                    + "Obj08_CheckSkid calls AllocateObject every fourth Stop-animation tick "
                                    + "(docs/s2disasm/s2.asm:42813-42841) after Obj89 clears riders "
                                    + "(docs/s2disasm/s2.asm:65689-65704). Expected slots "
                                    + describeSlots(expected, 16, 36) + " actual "
                                    + describeSlots(actual, 16, 36) + " live "
                                    + describeLiveSlots(om, 16, 36));
                    return new SlotWindowCheck(actual, describeSlots(actual, 16, 36));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x08, slotCheck.idAt(0x13),
                "ROM has Sonic's first boss-room Obj08 skid dust in slot 0x13 by ARZ2 f6313; "
                        + slotCheck.summary());
        Assertions.assertEquals(0x08, slotCheck.idAt(0x14),
                "ROM has Sonic's second boss-room Obj08 skid dust in slot 0x14 at ARZ2 f6313; "
                        + slotCheck.summary());
        Assertions.assertEquals(0x08, slotCheck.idAt(0x15),
                "ROM has Sonic's third boss-room Obj08 skid dust in slot 0x15 at ARZ2 f6313; "
                        + slotCheck.summary());
    }

    @Test
    public void arz2LostRingOwnerRunsObjectStepAtRomFrame2016() throws Exception {
        AnimalPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 2016) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedRing = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 33)
                            .filter(near -> parseObjectType(near.objectType()) == 0x37)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedRing,
                            "ARZ2 ROM fixture should report the first new Obj37 lost ring in slot 33 at f2016");
                    LostRingObjectInstance actualRing = om.activeObjectsOfType(LostRingObjectInstance.class)
                            .stream()
                            .filter(ring -> ring.getSlotIndex() == 33)
                            .findFirst()
                            .orElse(null);
                    return new AnimalPositionCheck(
                            expectedRing.x() & 0xFFFF,
                            expectedRing.y() & 0xFFFF,
                            actualRing == null ? -1 : actualRing.getX(),
                            actualRing == null ? -1 : actualRing.getY(),
                            describeSlots(om.occupiedDynamicSlotIds(), 33, 43));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 HurtCharacter allocates Obj37 with AllocateObject, then Obj37_Init falls through "
                        + "to Obj37_Main when ExecuteObjects reaches the new slot "
                        + "(docs/s2disasm/s2.asm:85444-85461,25125-25209); slots "
                        + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "The first new lost ring must receive its same-pass ObjectMove/gravity step at f2016; "
                        + "slots " + check.summary());
    }

    @Test
    public void arz2ChopChopSecondPatrolBubbleUsesRomByteTimerAtFrame2348() throws Exception {
        SlotWindowCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 2348) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    ObjectOccupancyOracle.Divergence divergence =
                            ObjectOccupancyOracle.firstDivergence(trace, om, frame, FIRST_DYNAMIC_SLOT);
                    Assertions.assertNull(divergence,
                            "ARZ2 slot-21 Obj91 must not emit an extra second patrol bubble at f2348; "
                                    + "Obj91_MakeBubble writes move.w #$50 to objoff_2C, but Obj91_Main "
                                    + "decrements the byte at objoff_2C, so the reset byte observed by "
                                    + "subq.b is 0 rather than 0x50 "
                                    + "(docs/s2disasm/s2.asm:73676-73688,73753-73754). Expected slots "
                                    + describeSlots(expected, 16, 41) + " actual "
                                    + describeSlots(actual, 16, 41) + " live "
                                    + describeLiveSlots(om, 16, 41));
                    return new SlotWindowCheck(actual, describeSlots(actual, 16, 41));
                });
        Assertions.assertNotNull(check);
    }

    @Test
    public void arz2WhispUsesClosestPlayerForChaseAtRomFrame4284() throws Exception {
        AnimalPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 4284) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedWhisp = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 29)
                            .filter(near -> parseObjectType(near.objectType()) == 0x8C)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedWhisp,
                            "ARZ2 ROM fixture should report Obj8C Whisp slot 0x1D at f4284");
                    WhispBadnikInstance actualWhisp = om.activeObjectsOfType(WhispBadnikInstance.class)
                            .stream()
                            .filter(whisp -> whisp.getSlotIndex() == 29)
                            .findFirst()
                            .orElse(null);
                    return new AnimalPositionCheck(
                            expectedWhisp.x() & 0xFFFF,
                            expectedWhisp.y() & 0xFFFF,
                            actualWhisp == null ? -1 : actualWhisp.getX(),
                            actualWhisp == null ? -1 : actualWhisp.getY(),
                            describeSlots(om.occupiedDynamicSlotIds(), 23, 31));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj8C_ChasePlayer must use Obj_GetOrientationToPlayer's closest-of-Sonic/Tails "
                        + "horizontal target before applying x acceleration "
                        + "(docs/s2disasm/s2.asm:72812-72834,73231-73249); slots "
                        + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj8C vertical acceleration should target the same closest player chosen by "
                        + "Obj_GetOrientationToPlayer; slots " + check.summary());
    }

    @Test
    public void arz2RotatingPlatformAssemblyConsumesRomChildSlotsAtFrame2855() throws Exception {
        SlotWindowCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 2855) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    ObjectOccupancyOracle.Divergence first =
                            ObjectOccupancyOracle.firstDivergence(trace, om, frame, FIRST_DYNAMIC_SLOT);
                    Assertions.assertNull(first,
                            "ARZ2 Obj83 must allocate its chain object plus platform 2/3 children "
                                    + "with AllocateObjectAfterCurrent before later FindFreeObj calls "
                                    + "observe the SST landscape (docs/s2disasm/s2.asm:57437-57466); "
                                    + "expected " + describeSlots(expected, 16, 60)
                                    + " actual " + describeSlots(actual, 16, 60)
                                    + " live " + describeLiveSlots(om, 16, 60));
                    return new SlotWindowCheck(actual, describeSlots(actual, 16, 60));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(0x83, check.idAt(17), "Obj83 parent should occupy slot 0x11; " + check.summary());
        Assertions.assertEquals(0x83, check.idAt(26), "Obj83 chain child should occupy slot 0x1A; " + check.summary());
        Assertions.assertEquals(0x83, check.idAt(30), "Obj83 platform-2 child should occupy slot 0x1E; " + check.summary());
        Assertions.assertEquals(0x83, check.idAt(35), "Obj83 platform-3 child should occupy slot 0x23; " + check.summary());
    }

    @Test
    public void arz2RisingPillarParentDebrisMovesOnBreakFrame4046() throws Exception {
        PlatformPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 4046) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedPillar = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 25)
                            .filter(near -> parseObjectType(near.objectType()) == 0x2B)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedPillar,
                            "ARZ2 ROM fixture should report Obj2B parent debris in slot 25 at f4046");
                    AbstractObjectInstance actualPillar = om.getActiveObjects().stream()
                            .filter(AbstractObjectInstance.class::isInstance)
                            .map(AbstractObjectInstance.class::cast)
                            .filter(instance -> instance.getSlotIndex() == 25)
                            .findFirst()
                            .orElse(null);
                    return new PlatformPositionCheck(
                            expectedPillar.x() & 0xFFFF,
                            expectedPillar.y() & 0xFFFF,
                            actualPillar == null ? -1 : actualPillar.getX(),
                            actualPillar == null ? -1 : actualPillar.getY(),
                            actualPillar == null ? "slot 25 missing"
                                    : String.format("slot25=%02X %s @%04X,%04X slots %s",
                                    actualPillar.getSpawn().objectId() & 0xFF,
                                    actualPillar.getName(),
                                    actualPillar.getX() & 0xFFFF,
                                    actualPillar.getY() & 0xFFFF,
                                    describeSlots(om.occupiedDynamicSlotIds(), 25, 51)));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "S2 Obj2B parent debris must run loc_25B8E on the same frame as the standing break "
                        + "(docs/s2disasm/s2.asm:51840-51855,51875-51888,51909-51949); "
                        + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj2B parent debris must move with the pre-gravity y_vel on the break frame; "
                        + check.summary());
    }

    @Test
    public void arz2RisingPillarDebrisKeepsSlotsForSecondBreakFrame4120() throws Exception {
        SlotWindowCheck slotCheck = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 4120) {
                        return null;
                    }
                    Map<Integer, Integer> expected =
                            ObjectOccupancyOracle.expectedOccupancy(trace, frame, FIRST_DYNAMIC_SLOT);
                    Map<Integer, Integer> actual = om.occupiedDynamicSlotIds();
                    Assertions.assertEquals(0x2B, expected.get(52),
                            "ARZ2 ROM fixture should allocate the second Obj2B debris cluster into slot 52");
                    Assertions.assertEquals(0x2B, expected.get(60),
                            "ARZ2 ROM fixture should allocate the second Obj2B debris cluster through slot 60");
                    return new SlotWindowCheck(actual,
                            "expected " + describeSlots(expected, 41, 60)
                                    + " actual " + describeSlots(actual, 41, 60)
                                    + " live " + describeLiveSlots(om, 41, 60));
                });
        Assertions.assertNotNull(slotCheck);
        Assertions.assertEquals(0x2B, slotCheck.idAt(52),
                "S2 Obj2B debris must remain live until loc_25BA4 observes the previous "
                        + "BuildSprites render_flags.on_screen bit; deleting from a fresh update-time "
                        + "bounds check frees first-pillar slots too early and moves the second debris "
                        + "cluster down (docs/s2disasm/s2.asm:30569-30588,51885-51888); "
                        + slotCheck.summary());
        Assertions.assertEquals(0x2B, slotCheck.idAt(60),
                "S2 Obj2B second debris cluster should still reach the ROM high slot window at f4120; "
                        + slotCheck.summary());
    }

    @Test
    public void arz2BossPillarsUseLowestFreeSlotsAtFrame4692() throws Exception {
        PlatformPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 4692) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedPillar = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 0x12)
                            .filter(near -> parseObjectType(near.objectType()) == 0x89)
                            .findFirst()
                            .orElse(null);
                    Assertions.assertNotNull(expectedPillar,
                            "ARZ2 ROM fixture should report the right Obj89 pillar in slot 0x12 at f4692");
                    AbstractObjectInstance actualPillar = om.getActiveObjects().stream()
                            .filter(AbstractObjectInstance.class::isInstance)
                            .map(AbstractObjectInstance.class::cast)
                            .filter(instance -> instance.getSlotIndex() == 0x12)
                            .findFirst()
                            .orElse(null);
                    return new PlatformPositionCheck(
                            expectedPillar.x() & 0xFFFF,
                            expectedPillar.y() & 0xFFFF,
                            actualPillar == null ? -1 : actualPillar.getX(),
                            actualPillar == null ? -1 : actualPillar.getY(),
                            "live " + describeLiveSlots(om, 0x10, 0x12));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedX(), check.actualX(),
                "Obj89_Init allocates ARZ boss pillars with AllocateObject/AllocateObjectAfterCurrent; "
                        + "the slot 0x12 pillar must exist immediately at the ROM X "
                        + "(docs/s2disasm/s2.asm:64836-64861); " + check.summary());
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "A higher-slot Obj89 pillar is processed later in the same ExecuteObjects pass, "
                        + "so Obj89_Pillar_Sub0 has already raised it one pixel at f4692 "
                        + "(docs/s2disasm/s2.asm:64836-64861,65330-65341); " + check.summary());
    }

    @Test
    public void arz2BossArrowTimerDecayDoesNotReseatSonicAtFrame5174() throws Exception {
        PlatformPositionCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 5174) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    AbstractPlayableSprite sonic =
                            (AbstractPlayableSprite) GameServices.sprites().getSprite("sonic");
                    return new PlatformPositionCheck(
                            expected.x() & 0xFFFF,
                            expected.y() & 0xFFFF,
                            sonic == null ? -1 : sonic.getCentreX() & 0xFFFF,
                            sonic == null ? -1 : sonic.getCentreY() & 0xFFFF,
                            "live " + describeLiveSlots(om, 0x12, 0x15));
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj89_Arrow_Platform branches directly to timer decay when obj89_arrow_timer "
                        + "is nonzero, skipping PlatformObject/MvSonicOnPtfm and preserving the "
                        + "landing y_pos through f5174 (docs/s2disasm/s2.asm:65658-65683); "
                        + check.summary());
    }

    @Test
    public void arz2BossArrowUsesPlatformObjectD3ForTailsLandingAtFrame5928() throws Exception {
        RideCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 5928) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have Tails at ARZ2 f5928");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    Assertions.assertNotNull(expected.sidekick(), "Trace frame must include recorded Tails state");
                    Assertions.assertEquals(0x14, expected.sidekick().standOnObj(),
                            "ROM fixture should have Tails standing on Obj89 arrow slot 0x14 at ARZ2 f5928");
                    return new RideCheck(expected.sidekick().y() & 0xFFFF,
                            tails.getCentreY() & 0xFFFF,
                            tails.getAir(),
                            tails.isOnObject());
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj89_Arrow_Platform passes d1=$1B,d2=1,d3=2 to PlatformObject; "
                        + "d3 is the landing surface height, so Tails must land on the "
                        + "arrow at f5928 (docs/s2disasm/s2.asm:65658-65665)");
        Assertions.assertFalse(check.actualAir());
        Assertions.assertTrue(check.actualOnObject());
    }

    @Test
    public void arz2BossArrowTimerDecayKeepsCpuTailsRidingAtFrame5968() throws Exception {
        RideCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 5968) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have Tails at ARZ2 f5968");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    Assertions.assertNotNull(expected.sidekick(), "Trace frame must include recorded Tails state");
                    Assertions.assertEquals(0x14, expected.sidekick().standOnObj(),
                            "ROM fixture should still have Tails standing on Obj89 arrow slot 0x14 at ARZ2 f5968");
                    return new RideCheck(expected.sidekick().y() & 0xFFFF,
                            tails.getCentreY() & 0xFFFF,
                            tails.getAir(),
                            tails.isOnObject());
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedY(), check.actualY(),
                "S2 Obj89_Arrow_Platform branches to timer decay when obj89_arrow_timer "
                        + "is nonzero, but that path only decrements the timer; it does not "
                        + "drop riders until Obj89_Arrow_Sub6 (docs/s2disasm/s2.asm:65658-65702)");
        Assertions.assertFalse(check.actualAir());
        Assertions.assertTrue(check.actualOnObject());
    }

    @Test
    public void arz2BossArrowTimerDecayDoesNotRestoreTailsPushAfterAnimationClear() throws Exception {
        PushStatusCheck check = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame != 6487) {
                        return null;
                    }
                    TraceFrame expected = trace.getFrame(frame);
                    Assertions.assertNotNull(expected.sidekick(), "Trace frame must include recorded Tails state");
                    Assertions.assertFalse(GameServices.sprites().getSidekicks().isEmpty(),
                            "Engine fixture must have Tails at ARZ2 f6487");
                    AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
                    int expectedStatus = expected.sidekick().statusByte() & 0xFF;
                    int actualStatus = TraceCharacterState.statusByteFromSprite(tails);
                    return new PushStatusCheck(
                            (expectedStatus & AbstractPlayableSprite.STATUS_PUSHING) != 0,
                            tails.getPushing(),
                            expectedStatus,
                            actualStatus);
                });
        Assertions.assertNotNull(check);
        Assertions.assertEquals(check.expectedPushing(), check.actualPushing(),
                "S2 Tails_Animate clears Status_Push when anim changes, and Obj89_Arrow_Platform_Decay "
                        + "skips PlatformObject, so the arrow must not re-set Tails' push bit at f6487 "
                        + "(docs/s2disasm/s2.asm:41272-41279,65658-65683); expected status=0x"
                        + Integer.toHexString(check.expectedStatus()) + " actual status=0x"
                        + Integer.toHexString(check.actualStatus()));
    }

    @Test
    public void arz2BossMainHoverPhaseMatchesRomAfterArrowRelease() throws Exception {
        PlatformPositionCheck firstMismatch = driveTrace("arz2", Sonic2ZoneConstants.ZONE_ARZ, 1,
                (trace, om, frame) -> {
                    if (frame < 6300 || frame > 6507) {
                        return null;
                    }
                    TraceEvent.ObjectNear expectedBoss = trace.getEventsForFrame(frame).stream()
                            .filter(TraceEvent.ObjectNear.class::isInstance)
                            .map(TraceEvent.ObjectNear.class::cast)
                            .filter(near -> near.slot() == 0x10)
                            .filter(near -> parseObjectType(near.objectType()) == 0x89)
                            .findFirst()
                            .orElse(null);
                    if (expectedBoss == null) {
                        return null;
                    }
                    AbstractObjectInstance actualBoss = om.getActiveObjects().stream()
                            .filter(AbstractObjectInstance.class::isInstance)
                            .map(AbstractObjectInstance.class::cast)
                            .filter(instance -> instance.getSlotIndex() == 0x10)
                            .findFirst()
                            .orElse(null);
                    int actualX = actualBoss == null ? -1 : actualBoss.getX();
                    int actualY = actualBoss == null ? -1 : actualBoss.getY();
                    int expectedX = expectedBoss.x() & 0xFFFF;
                    int expectedY = expectedBoss.y() & 0xFFFF;
                    if (expectedX == actualX && expectedY == actualY) {
                        return null;
                    }
                    return new PlatformPositionCheck(expectedX, expectedY, actualX, actualY,
                            "frame=" + frame + " live " + describeLiveSlots(om, 0x10, 0x15));
                });
        Assertions.assertNull(firstMismatch,
                () -> firstMismatch == null ? "" :
                        "S2 Obj89_Main_HandleHoveringAndHits writes the sine-derived y_pos "
                                + "before incrementing boss_sine_count; slot 0x10 must stay in "
                                + "the ROM hover phase through the post-arrow-release window "
                                + "(docs/s2disasm/s2.asm:65079-65091). Expected @"
                                + String.format("%04X,%04X", firstMismatch.expectedX(), firstMismatch.expectedY())
                                + " actual @"
                                + String.format("%04X,%04X", firstMismatch.actualX(), firstMismatch.actualY())
                                + "; " + firstMismatch.summary());
    }

    /**
     * Drives the named S2 level-select trace through the engine (mirroring the
     * S2 branch of {@code AbstractTraceReplayTest.replayMatchesTrace}) and
     * returns the first non-null result from {@code probe}, or {@code null} when
     * the probe reported no divergence for any replayed frame.
     */
    private <T> T driveTrace(String route, int zone, int act, FrameProbe<T> probe)
            throws Exception {
        Path traceDir = Path.of("src/test/resources/traces/s2").resolve(route);
        Assumptions.assumeTrue(Files.isDirectory(traceDir),
                "Trace directory not found: " + traceDir);
        Assumptions.assumeTrue(Files.exists(traceDir.resolve("metadata.json")),
                "metadata.json not found in " + traceDir);

        Path bk2Path = findBk2File(traceDir);
        Assumptions.assumeTrue(bk2Path != null, "No .bk2 file found in " + traceDir);

        TraceData trace = TraceData.load(traceDir);
        TraceMetadata meta = trace.metadata();
        Assumptions.assumeTrue("s2".equals(meta.game()),
                "Expected an S2 trace but metadata.game=" + meta.game());

        boolean requiresFreshLevelLoad =
                TraceReplayBootstrap.requiresFreshLevelLoadForTraceReplay(trace);
        TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);

        SharedLevel sharedLevel = requiresFreshLevelLoad
                ? null
                : SharedLevel.load(SonicGame.SONIC_2, zone, act);
        try {
            HeadlessTestFixture.Builder fixtureBuilder = HeadlessTestFixture.builder()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(
                            TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace));
            if (sharedLevel != null) {
                fixtureBuilder.withSharedLevel(sharedLevel);
            } else {
                fixtureBuilder.withZoneAndAct(zone, act);
            }
            if (TraceReplayBootstrap.shouldApplyMetadataStartPositionForTraceReplay(trace)) {
                fixtureBuilder
                        .startPosition(meta.startX(), meta.startY())
                        .startPositionIsCentre();
            }
            HeadlessTestFixture fixture = fixtureBuilder.build();

            TraceReplaySessionBootstrap.BootstrapResult boot =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);

            ObjectManager om = GameServices.level() != null
                    ? GameServices.level().getObjectManager() : null;
            Assumptions.assumeTrue(om != null, "ObjectManager unavailable after bootstrap");

            int startTraceIndex = boot.replayStart().startingTraceIndex();
            for (int i = startTraceIndex; i < trace.frameCount(); i++) {
                TraceFrame expected = trace.getFrame(i);
                TraceFrame previous = i > 0 ? trace.getFrame(i - 1) : null;
                TraceExecutionPhase phase =
                        TraceReplayBootstrap.phaseForReplay(trace, previous, expected);
                if (phase == TraceExecutionPhase.VBLANK_ONLY) {
                    fixture.skipFrameFromRecording();
                } else {
                    fixture.stepFrameFromRecording();
                }
                if (!TraceReplayBootstrap.shouldCompareGameplayStateForReplay(phase)) {
                    continue;
                }
                T divergence = probe.check(trace, om, i);
                if (divergence != null) {
                    return divergence;
                }
            }
            return null;
        } finally {
            if (sharedLevel != null) {
                sharedLevel.dispose();
            } else {
                TestEnvironment.resetAll();
            }
        }
    }

    private static Path findBk2File(Path dir) throws Exception {
        try (var files = Files.list(dir)) {
            return files
                    .filter(p -> p.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
    }
}
