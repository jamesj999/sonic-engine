package com.openggf.tests.trace.s3k;

import com.openggf.game.GameServices;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3K HCZ from the Sonic+Tails complete-run TAS. Per-zone segment: act1 -> seamless act1->act2
 * transition -> act2 -> the act2->next-zone exit handoff. zone()=1 (S3K zone_id == engine index);
 * act()=0.
 *
 * <p><b>Expected RED since 2026-08-15, deliberately, with the mechanism traced end to end.</b>
 * Two errors: {@code rings} 1 vs 2 at frame 29095 and 0 vs 1 at 29262. Nothing here is weakened,
 * tolerance-fitted or trimmed to reach a green; the measured first error and count live in
 * {@code docs/status/trace-frontier-log.md} and the mechanism in
 * {@code docs/status/known-discrepancies.md}.
 *
 * <p>The cause is <i>not</i> a defect in this zone. Commit {@code b31069c3f} corrected two
 * ROM-cited MegaChopper defects — {@code Obj_MegaChopper}'s opening
 * {@code jsr (Obj_WaitOffscreen)} suppressing every routine including Init until the sprite is
 * drawn (sonic3k.asm:184233, :180271-180302), and its {@code Touch_Special} defeat path owing the
 * player the {@code EnemyDefeated} bounce itself (:184242-184244 -> loc_85758's
 * {@code subi.w #$100,y_vel(a1)}). That moved the HCZ <i>segment</i> frontier 1434 -> 2478.
 *
 * <p>It also moved this class red, and the propagation is fully traced: at frame 1481 the
 * MegaChopper sits at x=3840 with the fix and 3839 without, and this run's own aux records
 * {@code object_x 0x0F00} = 3840 — <b>the fix is the ROM-correct side.</b> That one pixel changes
 * object-slot occupancy on 16,289 of 31,482 rows, so 27,600 frames later the boss-hurt ring
 * scatter puts ring 0 in a different slot (38 vs 4). {@code Obj_Bouncing_Ring} gates its floor
 * probe on its own SST slot — {@code move.b (V_int_run_count+3).w,d0 / add.b d7,d0 /
 * andi.b #7,d0 / bne} (sonic3k.asm:35629-35632), {@code d7} being {@code Process_Sprites}' live
 * slot countdown — and those slots are two apart in the {@code &7} cycle, so the ring bounces
 * differently and is collected four frames early.
 *
 * <p><b>The previous green was accidental, not a baseline.</b> With the slot probe armed,
 * engine-vs-ROM occupancy diverges on 2387 of 2387 sampled frames on <i>both</i> trees; this test
 * compares no object identity, slot or position at all. The old green was a slot-phase lottery
 * that happened to land the boss rings in compatible {@code &7} phases.
 *
 * <p><b>Do not "fix" this by adjusting ring timing, slot allocation or the bounce.</b> The
 * remaining work is slot-occupancy parity, which cannot currently be measured: S3K's SST has no
 * id field at any offset and objects overwrite their own dispatch pointer to advance state, so
 * the recorded type is a live program counter and only 4.26% of entries are object-table entries.
 * See {@code docs/architecture/audits/2026-08-15-s3k-object-code-pointer-identity.md}.
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kHczZoneSliceTraceReplay extends AbstractTraceReplayTest {
    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s3k/hcz_completerun");
    private static final int POINDEXTER_PRE_HIT_FRAME = 5725;
    private static final Set<Integer> POINDEXTER_OBSERVATION_FRAMES =
            Set.of(5595, 5600, 5650, 5700, POINDEXTER_PRE_HIT_FRAME);

    @Override protected SonicGame game() { return SonicGame.SONIC_3K; }
    @Override protected int zone() { return 1; }
    @Override protected int act() { return 0; }
    @Override protected Path traceDirectory() { return TRACE_DIR; }

    @Test
    void hczPoindexterOccupiesRomSlotBeforeTailsBounce() throws Exception {
        TraceData trace = TraceData.load(TRACE_DIR);
        TraceMetadata meta = trace.metadata();
        Path bk2Path = resolveBk2File(TRACE_DIR, meta);
        Assumptions.assumeTrue(bk2Path != null, "No BK2 found for " + TRACE_DIR);
        TraceReplaySessionBootstrap.prepareConfiguration(trace, meta);

        HeadlessTestFixture fixture = null;
        try {
            fixture = HeadlessTestFixture.builder()
                    .withRecording(bk2Path)
                    .withRecordingStartFrame(TraceReplayBootstrap.recordingStartFrameForTraceReplay(trace))
                    .withZoneAndAct(zone(), act())
                    .withHardwareReadinessAdmissionPolicy(
                            HardwareReadinessAdmissionPolicy.RECORDED)
                    .startPosition(meta.startX(), meta.startY())
                    .startPositionIsCentre()
                    .build();

            TraceReplaySessionBootstrap.BootstrapResult boot =
                    TraceReplaySessionBootstrap.applyBootstrap(trace, fixture, -1);
            TraceReplayBootstrap.ReplayStartState replayStart = boot.replayStart();
            int driveTraceIndex = replayStart.startingTraceIndex();
            TraceFrame previousDriveFrame = replayStart.hasSeededTraceState()
                    ? trace.getFrame(replayStart.seededTraceIndex())
                    : driveTraceIndex > 0 ? trace.getFrame(driveTraceIndex - 1) : null;

            TraceReplaySessionBootstrap.alignFrameCountersForReplayStart(
                    previousDriveFrame,
                    driveTraceIndex < trace.frameCount() ? trace.getFrame(driveTraceIndex) : null);

            StringBuilder observations = new StringBuilder();
            while (driveTraceIndex <= POINDEXTER_PRE_HIT_FRAME) {
                TraceFrame driveFrame = trace.getFrame(driveTraceIndex);
                fixture.beginTraceRow(driveTraceIndex, driveFrame.frame());
                TraceExecutionPhase phase =
                        TraceReplayBootstrap.phaseForReplay(trace, previousDriveFrame, driveFrame);
                TraceReplayBootstrap.markVblankStarvedIterationForReplay(
                        previousDriveFrame, driveFrame);
                driveScenarioReplayFrame(trace, fixture, phase);
                previousDriveFrame = driveFrame;
                if (POINDEXTER_OBSERVATION_FRAMES.contains(driveTraceIndex)) {
                    appendPoindexterObservation(observations, driveTraceIndex);
                }
                driveTraceIndex++;
            }

            ObjectManager objectManager = GameServices.level().getObjectManager();
            assertTrue(hasPoindexterAt(objectManager, 0x3190, 0x0768),
                    "Frame 5725: ROM slot 13 holds Obj_Poindexter at the Tails bounce point "
                            + "before Touch_EnemyNormal converts it to Obj_Explosion on frame 5726 "
                            + "(docs/skdisasm/sonic3k.asm:184529-184596,20697-20964). "
                            + "poindexters=" + describePoindexters(objectManager)
                            + "\nobservations:\n" + observations);
        } finally {
            if (fixture != null) {
                fixture.abortHardwareTimingReplayRun();
            }
            TestEnvironment.resetAll();
        }
    }

    private static Path resolveBk2File(Path traceDir, TraceMetadata meta) throws IOException {
        if (meta.sourceBk2() != null && !meta.sourceBk2().isBlank()) {
            Path shared = traceDir.getParent().resolve("_movies").resolve(meta.sourceBk2());
            if (Files.exists(shared)) {
                return shared;
            }
        }
        try (var files = Files.list(traceDir)) {
            return files
                    .filter(path -> path.toString().endsWith(".bk2"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static boolean hasPoindexterAt(ObjectManager objectManager, int x, int y) {
        if (objectManager == null) {
            return false;
        }
        for (ObjectInstance object : objectManager.getActiveObjects()) {
            if (!(object instanceof AbstractObjectInstance aoi)) {
                continue;
            }
            if (!"Poindexter".equals(aoi.getName())) {
                continue;
            }
            if ((aoi.getX() & 0xFFFF) == (x & 0xFFFF)
                    && (aoi.getY() & 0xFFFF) == (y & 0xFFFF)) {
                return true;
            }
        }
        return false;
    }

    private static void appendPoindexterObservation(StringBuilder out, int frame) {
        ObjectManager objectManager = GameServices.level().getObjectManager();
        AbstractPlayableSprite player = GameServices.camera().getFocusedSprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().isEmpty()
                ? null
                : GameServices.sprites().getSidekicks().getFirst();
        out.append(String.format("f%d cam=(%04X,%04X) sonic=(%04X,%04X) tails=(%04X,%04X) poindexters=%s%n",
                frame,
                GameServices.camera().getX() & 0xFFFF,
                GameServices.camera().getY() & 0xFFFF,
                player != null ? player.getCentreX() & 0xFFFF : -1,
                player != null ? player.getCentreY() & 0xFFFF : -1,
                tails != null ? tails.getCentreX() & 0xFFFF : -1,
                tails != null ? tails.getCentreY() & 0xFFFF : -1,
                describePoindexters(objectManager)));
    }

    private static String describePoindexters(ObjectManager objectManager) {
        if (objectManager == null) {
            return "<no object manager>";
        }
        return objectManager.getActiveObjects().stream()
                .filter(object -> object instanceof AbstractObjectInstance aoi
                        && "Poindexter".equals(aoi.getName()))
                .map(AbstractObjectInstance.class::cast)
                .sorted(Comparator.comparingInt(AbstractObjectInstance::getSlotIndex))
                .map(aoi -> String.format("s%d @%04X,%04X spawn=@%04X,%04X col=%02X",
                        aoi.getSlotIndex(),
                        aoi.getX() & 0xFFFF,
                        aoi.getY() & 0xFFFF,
                        aoi.getSpawn().x() & 0xFFFF,
                        aoi.getSpawn().y() & 0xFFFF,
                        aoi instanceof com.openggf.level.objects.TouchResponseProvider provider
                                ? provider.getCollisionFlags() & 0xFF
                                : 0))
                .reduce((left, right) -> left + " | " + right)
                .orElse("<none>");
    }
}
