package com.openggf.game.sonic3k.titlecard;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionOwnerKind;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.snapshot.PlcProgressSnapshot;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kTitleCardManagerRewind {

    @Test
    void restoreRebindsFourProductionHandlesWithoutResubmission() {
        HardwareTimingService timing = startLevel();
        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        RewindRegistry registry = new RewindRegistry();
        registry.register(timing);
        registry.register(title);

        title.initializeInLevel(0, 1);
        List<?> submitted = timing.pendingHandles();
        assertEquals(4, submitted.size());
        CompositeSnapshot snapshot = registry.capture();

        title.reset();
        timing.resetForMissingSnapshot();
        registry.restore(snapshot);

        assertFalse(title.isComplete());
        assertEquals(0, title.getCurrentZone());
        assertEquals(1, title.getCurrentAct());
        assertEquals(submitted, timing.pendingHandles(),
                "restore must rebind the original fully identified jobs");

        title.update();

        assertEquals(4, timing.pendingHandles().size(),
                "restored title ownership must not submit replacement work");
        assertEquals(4, timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE));
    }

    @Test
    void gameplaySessionRegistersLiveTitleManagerBesideHardwareTiming() {
        startLevel();

        var keys = TestEnvironment.activeGameplayMode()
                .getRewindRegistry().capture().entries().keySet();

        assertTrue(keys.contains(HardwareTimingService.REWIND_KEY));
        assertTrue(keys.contains(Sonic3kTitleCardManager.REWIND_KEY));
    }

    @Test
    void phaseOneInLevelResetTargetsNativeDisplayBoundary() {
        startLevel();
        GameServices.level().getObjectManager().initVblaCounter(1);

        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        title.initializeInLevel(0, 1);
        title.requestLevelGamestateResetAtInLevelDisplay();

        assertEquals(30, title.capture().resetLevelGamestateCountdown(),
                "phase-one title handoff must reach the ROM display reset row");
        assertFalse(title.retainedControlPollFollowsTitleCompletion(),
                "the phase-one title owner runs before the retained control slot");
    }

    @Test
    void phaseTwoInLevelResetTargetsNativeDisplayBoundary() {
        startLevel();
        GameServices.level().getObjectManager().initVblaCounter(2);

        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        title.initializeInLevel(0, 1);
        title.requestLevelGamestateResetAtInLevelDisplay();

        assertEquals(30, title.capture().resetLevelGamestateCountdown(),
                "phase-two title handoff must reach the ROM display reset row");
        assertEquals(1, title.capture().inLevelExitDelayFrames(),
                "phase-two child retirement needs the following Wait2 owner poll");
        assertTrue(title.retainedControlPollFollowsTitleCompletion(),
                "the phase-two title owner publishes after the retained control slot");
    }

    @Test
    void phaseOneInLevelArtAdmissionFollowsTheSecondExitOwnerPoll()
            throws Exception {
        startLevel();
        GameServices.level().getObjectManager().initVblaCounter(1);

        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        Sonic3kObjectArtProvider provider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        title.initializeInLevel(0, 1);
        prepareExitForCompletion(title);
        // LBZ's retained preloaded-act title keeps the camera-release tail alive
        // for eleven dispatches; LoadEnemyArt still follows the second Wait2 poll.
        setField(title, "inLevelExitDelayFrames", 11);

        title.update();
        assertFalse(provider.capture().runtimeArtAdmissionConsumed(),
                "the first Wait2 poll only observes the drained children");
        assertFalse(title.hasPublishedInLevelRuntimeArtAdmission(),
                "the camera handoff must remain behind the first Wait2 poll");
        assertEquals(10, title.capture().inLevelExitDelayFrames());

        title.update();
        assertTrue(provider.capture().runtimeArtAdmissionConsumed(),
                "LoadEnemyArt belongs to the following owner poll");
        assertTrue(title.hasPublishedInLevelRuntimeArtAdmission(),
                "the camera handoff follows the native LoadEnemyArt boundary");
        assertEquals(9, title.capture().inLevelExitDelayFrames());
    }

    @Test
    void allocatedInLevelTitleOwnerUsesFollowingWait2PollForArtAdmission()
            throws Exception {
        startLevel();

        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        Sonic3kObjectArtProvider provider = (Sonic3kObjectArtProvider)
                GameServices.module().getObjectArtProvider();
        title.initializeInLevel(0, 0);
        prepareExitForCompletion(title);
        // The AIZ intro allocates Obj_TitleCard after the current object pass.
        // Its first dispatch queues the card, and the lower Obj_TitleCardWait2
        // owner reaches LoadEnemyArt on the following poll.
        title.requestInLevelExitAdditionalDispatches(1);

        title.update();
        assertFalse(provider.capture().runtimeArtAdmissionConsumed(),
                "the allocated owner must retain the first Wait2 poll");
        assertFalse(title.isComplete());
        assertEquals(0, title.capture().inLevelExitDelayFrames());

        title.update();
        assertTrue(provider.capture().runtimeArtAdmissionConsumed(),
                "LoadEnemyArt belongs to the following Wait2 poll");
        assertTrue(title.isComplete());
    }

    @Test
    void preloadedActCameraReleasesOnThirdPostChildOwnerDispatch()
            throws Exception {
        startLevel();

        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        title.initializeInLevel(5, 1);
        prepareExitForCompletion(title);
        GameServices.camera().setScrollLocked(true);
        // The manager has already observed the drained children. These are
        // the two remaining Obj_TitleCardWait2 polls before completion.
        title.requestPreloadedActCameraReleaseOnComplete(2);

        title.update();
        assertTrue(GameServices.camera().getFrozen());
        assertEquals(1, title.capture().inLevelExitDelayFrames());

        title.update();
        assertTrue(GameServices.camera().getFrozen(),
                "Change_Act2Sizes is prepared while Scroll_lock remains held");
        assertEquals(0, title.capture().inLevelExitDelayFrames());

        title.update();
        assertFalse(GameServices.camera().getFrozen(),
                "the third post-child owner dispatch publishes title completion");
        assertTrue(title.isComplete());
    }

    @Test
    void productionRegistryRestoresTitleBeforeProviderAndConsumesTheExactLease()
            throws Exception {
        startLevel();
        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        Sonic3kTitleCardManager title = (Sonic3kTitleCardManager)
                GameServices.module().getTitleCardProvider();
        RewindRegistry registry = TestEnvironment.activeGameplayMode()
                .getRewindRegistry();

        List<String> productionOrder = List.copyOf(
                registry.capture().entries().keySet());
        assertTrue(productionOrder.indexOf(Sonic3kTitleCardManager.REWIND_KEY)
                        < productionOrder.indexOf(provider.key()),
                "production registers the title owner before its PLC provider");

        title.initializeInLevel(0, 0);
        prepareExitForCompletion(title);
        CompositeSnapshot beforeCompletion = registry.capture();
        long leaseId = admissionLeaseId((Sonic3kTitleCardManager.Snapshot)
                beforeCompletion.get(Sonic3kTitleCardManager.REWIND_KEY));

        assertTrue(leaseId >= 0,
                "title initialization binds a lease even while art is queued or cached");
        assertEquals(provider.capture().runtimeArtAdmissionLeaseId(), leaseId);

        RuntimeArtAdmissionLease replacement =
                provider.prepareRuntimeArtForActTransition(
                        0, RuntimeArtAdmissionPolicy.TITLE_OWNER);
        assertTrue(replacement.id() != leaseId);

        assertDoesNotThrow(() -> registry.restore(beforeCompletion),
                "title restore must copy its scalar before the provider restores");
        assertEquals(leaseId, admissionLeaseId(title.capture()));
        assertEquals(leaseId, provider.capture().runtimeArtAdmissionLeaseId());

        title.update();
        assertTrue(provider.capture().runtimeArtAdmissionConsumed());

        CompositeSnapshot afterCompletion = registry.capture();
        RuntimeArtAdmissionLease laterReplacement =
                provider.prepareRuntimeArtForActTransition(
                        0, RuntimeArtAdmissionPolicy.TITLE_OWNER);
        assertTrue(laterReplacement.id() != leaseId);

        assertDoesNotThrow(() -> registry.restore(afterCompletion));
        PlcProgressSnapshot restoredConsumed = provider.capture();
        assertEquals(leaseId, restoredConsumed.runtimeArtAdmissionLeaseId());
        assertTrue(restoredConsumed.runtimeArtAdmissionConsumed());
        title.update();
        assertTrue(provider.capture().runtimeArtAdmissionConsumed(),
                "restoring COMPLETE cannot consume or rebind a later batch");
    }

    @Test
    void titleRestoreDefersStaleAndMissingLeaseValidationUntilOwnerAction()
            throws Exception {
        startLevel();
        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        Sonic3kTitleCardManager title = (Sonic3kTitleCardManager)
                GameServices.module().getTitleCardProvider();

        title.initializeInLevel(0, 0);
        prepareExitForCompletion(title);
        Sonic3kTitleCardManager.Snapshot held = title.capture();
        long leaseId = admissionLeaseId(held);

        provider.prepareRuntimeArtForActTransition(
                0, RuntimeArtAdmissionPolicy.TITLE_OWNER);
        PlcProgressSnapshot replacement = provider.capture();

        assertDoesNotThrow(() -> title.restore(held),
                "scalar restore must not inspect the still-live provider");
        assertEquals(replacement, provider.capture(),
                "title restore cannot opportunistically bind current provider state");
        assertThrows(IllegalStateException.class, title::update,
                "the next lease-dependent action rejects the stale scalar id");
        assertEquals(Sonic3kTitleCardState.EXIT, title.capture().state(),
                "a rejected owner action cannot publish title completion");
        assertEquals(replacement, provider.capture(),
                "stale owner rejection must leave every provider lifecycle field unchanged");

        provider.restore(new PlcProgressSnapshot(replacement.loadEpoch()));
        PlcProgressSnapshot missing = provider.capture();
        assertDoesNotThrow(() -> title.restore(held));
        assertThrows(IllegalStateException.class, title::update,
                "the next lease-dependent action rejects a missing lease");
        assertEquals(Sonic3kTitleCardState.EXIT, title.capture().state(),
                "a missing exact lease keeps the title owner retryable");
        assertEquals(leaseId, admissionLeaseId(title.capture()));
        assertEquals(missing, provider.capture(),
                "missing owner rejection must not arm or defer a batch");
    }

    @Test
    void normalTitleCannotCompleteWithoutItsStoredLeaseIdentity()
            throws Exception {
        startLevel();
        Sonic3kObjectArtProvider provider =
                (Sonic3kObjectArtProvider) GameServices.module()
                        .getObjectArtProvider();
        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        provider.prepareRuntimeArtForActTransition(
                0, RuntimeArtAdmissionPolicy.TITLE_OWNER);
        setField(title, "artLoaded", true);
        setField(title, "lastLoadedZone", 0);
        setField(title, "lastLoadedAct", 0);
        setField(title, "combinedPatterns", new com.openggf.level.Pattern[0x100]);
        title.initializeInLevel(0, 0);
        prepareExitForCompletion(title);
        setField(title, "runtimeArtAdmissionLeaseId", -1L);
        PlcProgressSnapshot before = provider.capture();

        assertThrows(IllegalStateException.class, title::update);

        assertEquals(Sonic3kTitleCardState.EXIT, title.capture().state());
        assertEquals(before, provider.capture(),
                "missing scalar identity cannot consume or defer current provider work");
    }

    @Test
    void rewindAroundCompletionReleasesOnlyWhenReplayingTheExitTransition()
            throws Exception {
        startLevel();
        Sonic3kTitleCardManager title = new Sonic3kTitleCardManager();
        CountingObjectArtProvider provider = installCountingProvider();
        setField(title, "artLoaded", true);
        setField(title, "lastLoadedZone", 0);
        setField(title, "lastLoadedAct", 0);
        setField(title, "combinedPatterns", new com.openggf.level.Pattern[0x100]);
        title.initializeInLevel(0, 0);
        prepareExitForCompletion(title);

        Sonic3kTitleCardManager.Snapshot beforeCompletion = title.capture();
        title.restore(beforeCompletion);
        title.update();

        assertEquals(1, provider.titleCardRetirementCount,
                "restoring immediately before COMPLETE must replay one owner release");

        Sonic3kTitleCardManager.Snapshot afterCompletion = title.capture();
        title.restore(afterCompletion);
        title.update();

        assertEquals(1, provider.titleCardRetirementCount,
                "restoring COMPLETE must not create another owner release");
    }

    private static HardwareTimingService startLevel() {
        TestEnvironment.resetAll();
        SessionManager.clear();
        EngineServices.configure(
                EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic3kGameModule());
        TestEnvironment.activeGameplayMode();
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        HardwareTimingService timing = GameServices.hardwareTiming();
        timing.resetForMissingSnapshot();
        return timing;
    }

    private static CountingObjectArtProvider installCountingProvider() throws Exception {
        Sonic3kGameModule module = (Sonic3kGameModule) GameServices.module();
        Field field = Sonic3kGameModule.class.getDeclaredField("objectArtProvider");
        field.setAccessible(true);
        CountingObjectArtProvider provider = new CountingObjectArtProvider();
        field.set(module, provider);
        provider.prepareRuntimeArtForActTransition(
                0, RuntimeArtAdmissionPolicy.TITLE_OWNER);
        return provider;
    }

    private static void prepareExitForCompletion(Sonic3kTitleCardManager title)
            throws Exception {
        setField(title, "state", Sonic3kTitleCardState.EXIT);
        setField(title, "exitChildrenGone", true);
        setField(title, "inLevelExitDelayFrames", 0);
        setField(title, "artLoading", false);
        setField(title, "actNumberVisible", true);
        boolean[] exited = (boolean[]) getField(title, "elemExited");
        java.util.Arrays.fill(exited, true);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static long admissionLeaseId(Sonic3kTitleCardManager.Snapshot snapshot)
            throws Exception {
        try {
            return (long) snapshot.getClass()
                    .getMethod("runtimeArtAdmissionLeaseId")
                    .invoke(snapshot);
        } catch (NoSuchMethodException e) {
            fail("title snapshot must store the scalar admission lease id");
            return -1;
        }
    }

    private static final class CountingObjectArtProvider extends Sonic3kObjectArtProvider {
        private int titleCardRetirementCount;

        @Override
        public void consumeRuntimeArtAdmission(
                RuntimeArtAdmissionLease lease,
                RuntimeArtAdmissionOwnerKind ownerKind) {
            super.consumeRuntimeArtAdmission(lease, ownerKind);
            titleCardRetirementCount++;
        }
    }
}
