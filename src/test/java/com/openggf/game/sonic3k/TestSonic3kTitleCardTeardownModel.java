package com.openggf.game.sonic3k;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.game.rewind.snapshot.PlcProgressSnapshot;
import com.openggf.game.sonic3k.titlecard.Sonic3kTitleCardTeardownModel;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.level.SeamlessTransitionResourceHandoff;
import com.openggf.level.resources.DeferredLevelResourceManifest;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pins the ROM-derived residual lifetime of the title-card owner object.
 *
 * <p>The expected level frame is not a calibration target. It follows from
 * {@code docs/skdisasm/sonic3k.asm}:
 *
 * <ul>
 *   <li>{@code objoff_2E} is seeded to {@code $16} (22) at line 7878, one write
 *       before {@code LevelLoop}, and {@code Obj_TitleCardWait2} spends
 *       zero-based trace frames 0-21 draining it (62249-62253).</li>
 *   <li>Trace frame 22 first bumps {@code objoff_32} (62256-62261). The longest-lived
 *       element is {@code Obj_TitleCardName}: {@code objoff_28} = 3, so it starts
 *       moving on frame 24 and advances {@code $20} per frame from its
 *       {@code objoff_46} rest position {@code $120} (288) (62450-62456,
 *       62356-62366).</li>
 *   <li>{@code Render_Sprites} drops a {@code render_flags} {@code $40} sprite
 *       once {@code x_pos - 128 - width_pixels >= 320} (36444-36456). With
 *       {@code width_pixels} = {@code $80} (128) that is {@code x_pos >= 576},
 *       reached after nine steps on frame 32: {@code 288 + 9 * 32 = 576}.</li>
 *   <li>The trace-frame-32 draw records the element as off-screen. On trace
 *       frame 33, the child's following dispatch sees the clear render flag,
 *       decrements {@code objoff_30} to zero, and deletes itself
 *       (62358-62361). The lower-slot owner already returned from its nonzero
 *       {@code objoff_30} branch, so it first falls through {@code loc_2D86E}
 *       to {@code loc_2D8CA}'s {@code LoadEnemyArt} on provider tick 35,
 *       zero-based trace frame 34
 *       (62256-62263, 62295-62299).</li>
 * </ul>
 *
 * <p>Recorded ROM ground truth agrees: every non-AIZ S3K zone first becomes
 * Kos-queue busy on zero-based trace frame 34 (the 35th provider tick), and ICZ's recorded
 * {@code KOS_DECOMPRESSION_QUEUE} completion whose fingerprint matches the
 * engine's enemy-art submission is admitted on that frame.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kTitleCardTeardownModel {

    /**
     * Provider tick on which the ROM reaches LoadEnemyArt. The provider ticks
     * once per represented level frame, so provider tick N corresponds to
     * zero-based trace frame N - 1.
     */
    private static final int EXPECTED_LOAD_ENEMY_ART_TICK = 35;

    @Test
    @DisplayName("owner reaches LoadEnemyArt one dispatch after its last child retires")
    void reachesLoadEnemyArtOnDerivedFrame() {
        Sonic3kTitleCardTeardownModel model = new Sonic3kTitleCardTeardownModel();
        int firedFrame = -1;
        for (int frame = 0; frame < 200; frame++) {
            if (model.tick()) {
                firedFrame = model.ticksElapsed();
                break;
            }
        }
        assertEquals(EXPECTED_LOAD_ENEMY_ART_TICK, firedFrame,
                "LoadEnemyArt tick must match the ROM-derived count");
        assertTrue(model.isComplete());
    }

    @Test
    @DisplayName("tick 34 drains children but tick 35 owns LoadEnemyArt")
    void ownerCannotObserveHigherSlotRetirementUntilFollowingDispatch() {
        Sonic3kTitleCardTeardownModel model = new Sonic3kTitleCardTeardownModel();

        for (int tick = 1; tick <= 34; tick++) {
            assertFalse(model.tick(),
                    "the lower-slot owner cannot observe a higher-slot child "
                            + "retirement during tick " + tick);
        }

        assertEquals(34, model.ticksElapsed());
        assertFalse(model.isComplete(),
                "tick 34 is a distinct drained-children/pending-owner state");
        assertTrue(model.tick(), "tick 35 is the owner's next SST dispatch");
        assertTrue(model.isComplete());
        assertFalse(model.tick(), "the owner reaches LoadEnemyArt exactly once");
    }

    @Test
    @DisplayName("objoff_2E alone holds the owner for its full $16 frames")
    void wait2CounterHoldsForTwentyTwoFrames() {
        Sonic3kTitleCardTeardownModel model = new Sonic3kTitleCardTeardownModel();
        for (int frame = 0; frame < 0x16; frame++) {
            assertFalse(model.tick(), "frame " + frame + " must still be counting down");
        }
        assertFalse(model.isComplete(), "element drain has not started yet");
    }

    @Test
    @DisplayName("restoreTicks replays the model exactly")
    void restoreReplaysDeterministically() {
        Sonic3kTitleCardTeardownModel reference = new Sonic3kTitleCardTeardownModel();
        for (int frame = 0; frame < 34; frame++) {
            reference.tick();
        }
        Sonic3kTitleCardTeardownModel restored = new Sonic3kTitleCardTeardownModel();
        restored.restoreTicks(reference.ticksElapsed());
        assertEquals(reference.ticksElapsed(), restored.ticksElapsed());
        assertFalse(reference.isComplete());
        assertFalse(restored.isComplete(),
                "restore must preserve the tick-34 pending owner observation");
        assertEquals(reference.tick(), restored.tick(),
                "restored model must fire on the same owner dispatch");
        assertTrue(restored.isComplete(), "restored model fires on tick 35");
        assertTrue(restored.isComplete());
    }

    @Test
    @DisplayName("AIZ preserve-current reload keeps the existing enemy batch and lease")
    void aizPreserveCurrentTransitionDoesNotRegisterOrReleaseEnemyArt() throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        Sonic3kObjectArtProvider provider = (Sonic3kObjectArtProvider) GameServices.module()
                .getObjectArtProvider();
        PlcProgressSnapshot before = provider.capture();

        GameServices.level().executeActTransition(SeamlessLevelTransitionRequest.builder(
                        SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(0, 1)
                .preserveMusic(true)
                .preserveLevelGamestate(true)
                .showInLevelTitleCard(false)
                .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.PRESERVE_CURRENT)
                .build());

        PlcProgressSnapshot after = provider.capture();
        assertEquals(before.runtimeArtAdmissionGeneration(),
                after.runtimeArtAdmissionGeneration(),
                "AIZ1BGE_FireTransition does not execute LoadEnemyArt");
        assertEquals(before.runtimeArtAdmissionLeaseId(),
                after.runtimeArtAdmissionLeaseId());
        assertEquals(before.pendingKosModules(), after.pendingKosModules(),
                "the existing AIZ enemy descriptors stay owned");
        assertEquals(before.pendingKosOrdinals(), after.pendingKosOrdinals(),
                "preserve-current cannot clear or resubmit live enemy work");
        assertEquals(-1, after.titleCardTeardownTicks(),
                "a false transition overlay never creates skipped-initial teardown");
    }

    @Test
    void immediateTransitionArmsInExecutorAndSubmitsAtFollowingProviderPump()
            throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        Sonic3kObjectArtProvider provider = (Sonic3kObjectArtProvider) GameServices.module()
                .getObjectArtProvider();

        GameServices.level().executeActTransition(SeamlessLevelTransitionRequest.builder(
                        SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                .targetZoneAct(0, 1)
                .preserveMusic(true)
                .preserveLevelGamestate(true)
                .showInLevelTitleCard(false)
                .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.IMMEDIATE)
                .build());

        PlcProgressSnapshot armed = provider.capture();
        assertTrue(armed.runtimeArtAdmissionConsumed());
        assertTrue(armed.kosSubmissionArmed());
        assertFalse(armed.pendingKosModules().isEmpty());
        assertTrue(armed.pendingKosOrdinals().isEmpty(),
                "executor consumption arms only; it must not submit parents");

        provider.processRuntimeArtQueue();

        assertFalse(provider.capture().pendingKosOrdinals().isEmpty(),
                "the existing following provider pump submits the armed batch");
    }

    @Test
    void directResourceHandoffPreparationIssuesHeldResourceOwnerLease() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        Sonic3kObjectArtProvider provider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        RuntimeArtAdmissionLease lease =
                provider.prepareRuntimeArtForActTransition(
                        0, RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER);

        PlcProgressSnapshot prepared = provider.capture();
        assertEquals(RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER,
                lease.ownerKind());
        assertEquals(lease.id(), prepared.runtimeArtAdmissionLeaseId());
        assertEquals(lease.generation(), prepared.runtimeArtAdmissionGeneration());
        assertEquals(lease.batchFingerprint(),
                prepared.runtimeArtAdmissionBatchFingerprint());
        assertFalse(prepared.runtimeArtAdmissionConsumed());
        assertFalse(prepared.kosSubmissionArmed(),
                "resource-owner preparation must hold enemy admission");
    }

    @Test
    void resourceHandoffPolicyTransfersExactHeldLeaseAfterTargetInit()
            throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        var levelManager = GameServices.level();
        Sonic3kObjectArtProvider provider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        var handoff = new RecordingHandoff();
        var handoffId = GameServices.seamlessTransitionResourceHandoffs()
                .register(handoff);

        SeamlessLevelTransitionRequest request =
                SeamlessLevelTransitionRequest.builder(
                                SeamlessLevelTransitionRequest.TransitionType
                                        .RELOAD_TARGET_LEVEL)
                        .targetZoneAct(0, 1)
                        .preserveMusic(true)
                        .resourceHandoff(handoffId)
                        .runtimeArtAdmissionPolicy(
                                RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER)
                        .build();

        levelManager.executeActTransition(request);

        RuntimeArtAdmissionLease transferred = handoff.transferredLease.get();
        assertEquals(RuntimeArtAdmissionOwnerKind.RESOURCE_HANDOFF_OWNER,
                transferred.ownerKind());
        PlcProgressSnapshot prepared = provider.capture();
        assertEquals(transferred.id(), prepared.runtimeArtAdmissionLeaseId());
        assertEquals(transferred.generation(),
                prepared.runtimeArtAdmissionGeneration());
        assertEquals(transferred.batchFingerprint(),
                prepared.runtimeArtAdmissionBatchFingerprint());
        assertFalse(prepared.runtimeArtAdmissionConsumed());
        assertFalse(prepared.kosSubmissionArmed());
        assertEquals(1, handoff.transferCount.get());
        assertThrows(IllegalStateException.class,
                () -> GameServices.seamlessTransitionResourceHandoffs()
                        .claim(handoffId));
    }

    @Test
    void failedPostClaimResourceTransferIsTerminalWithoutRebindingLease()
            throws Exception {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        var levelManager = GameServices.level();
        Sonic3kObjectArtProvider provider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        RecordingHandoff handoff = new RecordingHandoff(true);
        var registry = GameServices.seamlessTransitionResourceHandoffs();
        var handoffId = registry.register(handoff);
        SeamlessLevelTransitionRequest request =
                SeamlessLevelTransitionRequest.builder(
                                SeamlessLevelTransitionRequest.TransitionType
                                        .RELOAD_TARGET_LEVEL)
                        .targetZoneAct(0, 1)
                        .preserveMusic(true)
                        .resourceHandoff(handoffId)
                        .runtimeArtAdmissionPolicy(
                                RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER)
                        .build();

        assertThrows(IllegalStateException.class,
                () -> levelManager.executeActTransition(request));

        RecordingHandoff failed =
                (RecordingHandoff) registry.failedTransfer(handoffId);
        RuntimeArtAdmissionLease exactLease = failed.admissionLease;
        PlcProgressSnapshot afterFailure = provider.capture();
        assertEquals(exactLease.id(), afterFailure.runtimeArtAdmissionLeaseId());
        assertEquals(exactLease.generation(),
                afterFailure.runtimeArtAdmissionGeneration());
        assertEquals(exactLease.batchFingerprint(),
                afterFailure.runtimeArtAdmissionBatchFingerprint());
        assertFalse(afterFailure.runtimeArtAdmissionConsumed());
        assertFalse(afterFailure.kosSubmissionArmed());

        assertThrows(IllegalStateException.class,
                () -> levelManager.executeActTransition(request));
        assertEquals(afterFailure, provider.capture(),
                "a terminal claimed-transfer fence cannot bind a later batch or retry admission");
        assertEquals(1, handoff.transferCount.get());
    }

    private static final class RecordingHandoff
            implements SeamlessTransitionResourceHandoff {
        private final AtomicInteger transferCount;
        private final AtomicReference<RuntimeArtAdmissionLease> transferredLease;
        private final RuntimeArtAdmissionLease admissionLease;
        private final boolean failTransfer;

        private RecordingHandoff() {
            this(false);
        }

        private RecordingHandoff(boolean failTransfer) {
            this(new AtomicInteger(), new AtomicReference<>(), null,
                    failTransfer);
        }

        private RecordingHandoff(
                AtomicInteger transferCount,
                AtomicReference<RuntimeArtAdmissionLease> transferredLease,
                RuntimeArtAdmissionLease admissionLease,
                boolean failTransfer) {
            this.transferCount = transferCount;
            this.transferredLease = transferredLease;
            this.admissionLease = admissionLease;
            this.failTransfer = failTransfer;
        }

        @Override
        public DeferredLevelResourceManifest deferredResources() {
            return DeferredLevelResourceManifest.EMPTY;
        }

        @Override
        public SeamlessTransitionResourceHandoff withAdmissionLease(
                RuntimeArtAdmissionLease lease) {
            return new RecordingHandoff(
                    transferCount, transferredLease, lease, failTransfer);
        }

        @Override
        public void transferAfterTargetInit() {
            transferredLease.set(admissionLease);
            transferCount.incrementAndGet();
            if (failTransfer) {
                throw new IllegalStateException("injected target transfer failure");
            }
        }
    }
}
