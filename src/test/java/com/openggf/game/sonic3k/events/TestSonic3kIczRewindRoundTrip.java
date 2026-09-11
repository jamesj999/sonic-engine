package com.openggf.game.sonic3k.events;

import com.openggf.game.GameServices;
import com.openggf.game.RuntimeArtAdmissionLease;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.schema.ZoneEventSchemaSidecar;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.IczSnowboardIntroInstance;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.level.SeamlessTransitionResourceHandoff;
import com.openggf.level.SeamlessTransitionResourceHandoffId;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression coverage for the ICZ event-state inclusion in
 * {@link com.openggf.game.sonic3k.Sonic3kLevelEventManager} extra-state snapshots.
 *
 * <p>Before this fix, {@code captureExtra()} silently dropped {@link Sonic3kICZEvents}
 * state; rewinding inside ICZ reset the indoor palette cycle, snow physics, and
 * background routine to their post-{@code init()} defaults.
 */
class TestSonic3kIczRewindRoundTrip {

    @Test
    void schemaCaptureProducesNonEmptyPayload() {
        Sonic3kICZEvents events = new Sonic3kICZEvents();
        byte[] payload = ZoneEventSchemaSidecar.capture(events);

        assertTrue(payload.length >= 25,
                "ICZ schema must encode at minimum the legacy 5 booleans + 5 ints");
    }

    @Test
    void schemaCaptureIgnoresLiveSnowboardIntroReference() throws Exception {
        Sonic3kICZEvents events = new Sonic3kICZEvents();
        setSnowboardIntro(events, new IczSnowboardIntroInstance(new ObjectSpawn(
                IczSnowboardIntroInstance.INITIAL_SNOWBOARD_X,
                IczSnowboardIntroInstance.INITIAL_SNOWBOARD_Y,
                0, 0, 0, false,
                IczSnowboardIntroInstance.INITIAL_SNOWBOARD_Y)));

        assertDoesNotThrow(() -> ZoneEventSchemaSidecar.capture(events),
                "ICZ zone-event sidecar must not attempt to encode the live snowboard intro object reference");
    }

    @Test
    void roundTripPreservesPubliclyObservableState() {
        Sonic3kICZEvents original = new Sonic3kICZEvents();
        original.setEventsFg5(true);
        original.setIndoorPaletteCyclingActive(false);

        Sonic3kICZEvents restored = new Sonic3kICZEvents();
        // Ensure the restored instance starts with different values where it matters,
        // so a no-op read would produce a different state than the original.
        restored.setEventsFg5(false);
        restored.setIndoorPaletteCyclingActive(true);

        // Sanity: the two instances differ before the round-trip.
        assertNotEquals(original.isEventsFg5(), restored.isEventsFg5());
        assertNotEquals(original.isIndoorPaletteCyclingActive(),
                restored.isIndoorPaletteCyclingActive());

        ZoneEventSchemaSidecar.restore(restored, ZoneEventSchemaSidecar.capture(original));

        assertEquals(original.isEventsFg5(), restored.isEventsFg5(),
                "eventsFg5 must round-trip through capture/restore");
        assertEquals(original.isIndoorPaletteCyclingActive(),
                restored.isIndoorPaletteCyclingActive(),
                "indoorPaletteCyclingActive must round-trip through capture/restore");
        assertEquals(original.getIcz1BackgroundRoutine(),
                restored.getIcz1BackgroundRoutine(),
                "backgroundRoutine must round-trip through capture/restore");
        assertEquals(original.getIcz1BigSnowOffset(),
                restored.getIcz1BigSnowOffset(),
                "bigSnowOffset must round-trip through capture/restore");
    }

    @Test
    void capturedBytesAreStableAcrossRoundTrip() {
        // Stronger property: writing a state, reading into a fresh instance, and
        // writing that instance again must produce the identical byte sequence.
        // This covers private fields without exposing getters.
        Sonic3kICZEvents original = new Sonic3kICZEvents();
        original.setEventsFg5(true);
        original.setIndoorPaletteCyclingActive(false);

        byte[] first = ZoneEventSchemaSidecar.capture(original);

        Sonic3kICZEvents restored = new Sonic3kICZEvents();
        ZoneEventSchemaSidecar.restore(restored, first);

        byte[] second = ZoneEventSchemaSidecar.capture(restored);

        assertArrayEquals(first, second,
                "captured bytes must be identical after a schema capture-restore-capture cycle");
    }

    @Test
    void roundTripPreservesSeamlessTransitionOrdinalsAndPublicationFences()
            throws Exception {
        Sonic3kICZEvents original = new Sonic3kICZEvents();
        setLong(original, "act2TransitionChunkOrdinal", 3);
        setLong(original, "act2TransitionBlockOrdinal", 4);
        setLong(original, "act2TransitionArtOrdinal", 2);
        setLong(original, "act2TransitionHandoffId", 9);
        setLong(original, "act2TransitionAdmissionLeaseId", 12);
        setLong(original, "act2TransitionAdmissionGeneration", 13);
        setLong(original, "act2TransitionAdmissionBatchFingerprint", 14);
        setBoolean(original, "act2TransitionAdmissionAccepted", true);
        setBoolean(original, "act2TransitionPublicationFailed", true);
        setBoolean(original, "act2TransitionDirectPublished", false);
        setBoolean(original, "act2TransitionArtPublished", false);

        Sonic3kICZEvents restored = new Sonic3kICZEvents();
        ZoneEventSchemaSidecar.restore(
                restored, ZoneEventSchemaSidecar.capture(original));

        assertEquals(3L, longField(restored, "act2TransitionChunkOrdinal"));
        assertEquals(4L, longField(restored, "act2TransitionBlockOrdinal"));
        assertEquals(2L, longField(restored, "act2TransitionArtOrdinal"));
        assertEquals(9L, longField(restored, "act2TransitionHandoffId"));
        assertEquals(12L,
                longField(restored, "act2TransitionAdmissionLeaseId"));
        assertEquals(13L,
                longField(restored, "act2TransitionAdmissionGeneration"));
        assertEquals(14L,
                longField(restored,
                        "act2TransitionAdmissionBatchFingerprint"));
        assertTrue(booleanField(restored,
                "act2TransitionAdmissionAccepted"));
        assertTrue(booleanField(restored,
                "act2TransitionPublicationFailed"));
        assertEquals(false,
                booleanField(restored, "act2TransitionDirectPublished"));
        assertEquals(false,
                booleanField(restored, "act2TransitionArtPublished"));
    }

    @Test
    void roundTripRetainsSuccessfulPublicationIdentityWithoutClearingOrdinals()
            throws Exception {
        Sonic3kICZEvents original = new Sonic3kICZEvents();
        setLong(original, "act2TransitionChunkOrdinal", 21);
        setLong(original, "act2TransitionBlockOrdinal", 22);
        setLong(original, "act2TransitionArtOrdinal", 23);
        setLong(original, "act2TransitionAdmissionLeaseId", 24);
        setLong(original, "act2TransitionAdmissionGeneration", 25);
        setLong(original, "act2TransitionAdmissionBatchFingerprint", 26);
        setBoolean(original, "act2TransitionAdmissionAccepted", true);
        setBoolean(original, "act2TransitionDirectPublished", true);
        setBoolean(original, "act2TransitionArtPublished", true);

        Sonic3kICZEvents restored = new Sonic3kICZEvents();
        ZoneEventSchemaSidecar.restore(
                restored, ZoneEventSchemaSidecar.capture(original));

        assertEquals(21L,
                longField(restored, "act2TransitionChunkOrdinal"));
        assertEquals(22L,
                longField(restored, "act2TransitionBlockOrdinal"));
        assertEquals(23L,
                longField(restored, "act2TransitionArtOrdinal"));
        assertEquals(24L,
                longField(restored, "act2TransitionAdmissionLeaseId"));
        assertEquals(25L,
                longField(restored, "act2TransitionAdmissionGeneration"));
        assertEquals(26L,
                longField(restored,
                        "act2TransitionAdmissionBatchFingerprint"));
        assertTrue(booleanField(restored,
                "act2TransitionAdmissionAccepted"));
        assertTrue(booleanField(restored,
                "act2TransitionDirectPublished"));
        assertTrue(booleanField(restored,
                "act2TransitionArtPublished"));
        assertEquals(false,
                booleanField(restored, "act2TransitionPublicationFailed"));
    }

    @Nested
    @RequiresRom(SonicGame.SONIC_3K)
    class ProductionLifecycleBoundaries {

        @Test
        void compositeRestoreReplaysPreAcceptanceAndRetainsIncompleteQueues()
                throws Exception {
            TransitionHarness harness = TransitionHarness.create();
            var rewind = harness.fixture.gameplayMode().getRewindRegistry();
            CompositeSnapshot beforeAcceptance = rewind.capture();

            harness.accept();
            CompositeSnapshot whileIncomplete = rewind.capture();
            var incompleteProvider = harness.provider.capture();
            var incompleteTiming = GameServices.hardwareTiming().capture();
            serviceUntilReady(harness);
            harness.events().update(1, 0);
            assertTrue(harness.provider.capture().runtimeArtAdmissionConsumed());

            rewind.restore(beforeAcceptance);
            assertFalse(harness.provider.capture().runtimeArtAdmissionConsumed());
            assertTrue(GameServices.hardwareTiming().isPending(harness.chunkHandle));
            assertTrue(GameServices.hardwareTiming().isPending(harness.blockHandle));
            assertTrue(GameServices.hardwareTiming().isPending(harness.artHandle));
            harness.accept();

            rewind.restore(whileIncomplete);
            harness.events().update(1, 1);
            assertEquals(incompleteProvider, harness.provider.capture(),
                    "restored incomplete carried queues must keep admission held");
            assertTimingLifecycleEquals(
                    incompleteTiming, GameServices.hardwareTiming().capture(),
                    "an incomplete owner update cannot advance, claim, or replace work");
        }

        @Test
        void compositeRestoreRetainsTerminalPreclaimedFence() throws Exception {
            TransitionHarness harness = TransitionHarness.create();
            harness.accept();
            serviceUntilReady(harness);
            harness.directQueue.claim(harness.chunkHandle);
            assertThrows(IllegalStateException.class,
                    () -> harness.events().update(1, 0));
            var rewind = harness.fixture.gameplayMode().getRewindRegistry();
            CompositeSnapshot terminalFailure = rewind.capture();
            var failedProvider = harness.provider.capture();
            var failedTiming = GameServices.hardwareTiming().capture();

            HardwareBoundaryPump.service(HardwareServiceBoundary.POST_OBJECTS);
            rewind.restore(terminalFailure);

            assertThrows(IllegalStateException.class,
                    () -> harness.events().update(1, 1));
            assertEquals(failedProvider, harness.provider.capture());
            assertTimingLifecycleEquals(
                    failedTiming, GameServices.hardwareTiming().capture(),
                    "restored terminal state cannot retry or double-claim work");
        }

        @Test
        void compositeRestoreRetainsSuccessfulPublicationWithoutDuplicates()
                throws Exception {
            TransitionHarness harness = TransitionHarness.create();
            harness.accept();
            serviceUntilReady(harness);
            harness.events().update(1, 0);
            harness.provider.processRuntimeArtQueue();
            var rewind = harness.fixture.gameplayMode().getRewindRegistry();
            CompositeSnapshot successfulPublication = rewind.capture();
            var successfulProvider = harness.provider.capture();
            var successfulTiming = GameServices.hardwareTiming().capture();

            harness.events().update(1, 1);
            harness.provider.processRuntimeArtQueue();
            rewind.restore(successfulPublication);
            assertTimingLifecycleEquals(
                    successfulTiming, GameServices.hardwareTiming().capture(),
                    "composite restore must recover every successful queue job");
            harness.events().update(1, 2);
            harness.provider.processRuntimeArtQueue();

            assertEquals(successfulProvider, harness.provider.capture(),
                    "restored successful owner cannot consume admission twice");
            assertTimingLifecycleEquals(
                    successfulTiming, GameServices.hardwareTiming().capture(),
                    "restored successful owner cannot duplicate submitted work");
        }
    }

    private static void serviceUntilReady(TransitionHarness harness) {
        int services = 0;
        while (!(harness.directQueue.isReady(harness.chunkHandle)
                && harness.directQueue.isReady(harness.blockHandle)
                && harness.moduleQueue.isReady(harness.artHandle))) {
            HardwareBoundaryPump.service(HardwareServiceBoundary.POST_OBJECTS);
            HardwareBoundaryPump.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            if (++services > 100_000) {
                throw new AssertionError(
                        "ICZ transition work did not become ready");
            }
        }
    }

    private static void assertTimingLifecycleEquals(
            HardwareTimingSnapshot expected,
            HardwareTimingSnapshot actual,
            String message) {
        assertEquals(expected.nextOrdinals(), actual.nextOrdinals(), message);
        assertEquals(expected.jobs().size(), actual.jobs().size(), message);
        for (int i = 0; i < expected.jobs().size(); i++) {
            var expectedJob = expected.jobs().get(i);
            var actualJob = actual.jobs().get(i);
            assertEquals(expectedJob.handle(), actualJob.handle(), message);
            assertEquals(expectedJob.ready(), actualJob.ready(), message);
            assertEquals(expectedJob.claimed(), actualJob.claimed(), message);
            assertEquals(expectedJob.profileActive(), actualJob.profileActive(),
                    message);
            assertEquals(expectedJob.physicallyRetired(),
                    actualJob.physicallyRetired(), message);
            assertEquals(expectedJob.remainingServiceFrames(),
                    actualJob.remainingServiceFrames(), message);
        }
    }

    private record TransitionHarness(
            HeadlessTestFixture fixture,
            Sonic3kLevelEventManager eventManager,
            Sonic3kObjectArtProvider provider,
            S3kKosDecompressionQueue directQueue,
            HardwareWorkHandle chunkHandle,
            HardwareWorkHandle blockHandle,
            S3kKosModuleQueue moduleQueue,
            HardwareWorkHandle artHandle,
            RuntimeArtAdmissionLease lease,
            SeamlessTransitionResourceHandoffId handoffId) {

        private static TransitionHarness create() throws IOException {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withZoneAndAct(Sonic3kZoneIds.ZONE_ICZ, 1)
                    .build();
            Sonic3kLevelEventManager eventManager =
                    (Sonic3kLevelEventManager) GameServices.module()
                            .getLevelEventProvider();
            var rom = GameServices.rom().getRom();
            int entry = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                    + 11 * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
            int artSource = rom.read32BitAddr(entry + 4) & 0x00FF_FFFF;
            int blockSource = rom.read32BitAddr(entry + 12) & 0x00FF_FFFF;
            int chunkSource = rom.read32BitAddr(entry + 20) & 0x00FF_FFFF;
            S3kKosDecompressionQueue directQueue =
                    S3kRuntimeArtCoordinator.current().directQueue();
            HardwareWorkHandle chunkHandle = directQueue.queueStandardKos(
                    rom, chunkSource, S3kKosRamDestinations.RAM_START + 0x0A00);
            HardwareWorkHandle blockHandle = directQueue.queueStandardKos(
                    rom, blockSource,
                    S3kKosRamDestinations.blockTableOffset(0x0408));
            S3kKosModuleQueue moduleQueue =
                    S3kRuntimeArtCoordinator.current().moduleQueue();
            HardwareWorkHandle artHandle =
                    moduleQueue.queueForIczSeamlessHandoff(
                            rom, artSource, 0x0122);
            Sonic3kObjectArtProvider provider =
                    (Sonic3kObjectArtProvider) GameServices.module()
                            .getObjectArtProvider();
            RuntimeArtAdmissionLease lease =
                    provider.prepareRuntimeArtForActTransition(
                            Sonic3kZoneIds.ZONE_ICZ,
                            RuntimeArtAdmissionPolicy.RESOURCE_HANDOFF_OWNER);
            SeamlessTransitionResourceHandoff handoff =
                    new IczSeamlessTransitionResourceHandoff(
                            directQueue, chunkHandle, blockHandle,
                            moduleQueue, artHandle, eventManager);
            SeamlessTransitionResourceHandoffId handoffId =
                    GameServices.seamlessTransitionResourceHandoffs()
                            .register(handoff);
            return new TransitionHarness(
                    fixture, eventManager, provider,
                    directQueue, chunkHandle, blockHandle,
                    moduleQueue, artHandle, lease, handoffId);
        }

        private Sonic3kICZEvents events() {
            return eventManager.getIczEvents();
        }

        private void accept() {
            SeamlessTransitionResourceHandoff handoff =
                    GameServices.seamlessTransitionResourceHandoffs()
                            .claim(handoffId)
                            .withAdmissionLease(lease);
            handoff.transferAfterTargetInit();
        }
    }

    private static void setSnowboardIntro(Sonic3kICZEvents events, IczSnowboardIntroInstance intro)
            throws ReflectiveOperationException {
        Field field = Sonic3kICZEvents.class.getDeclaredField("snowboardIntro");
        field.setAccessible(true);
        field.set(events, intro);
    }

    private static void setLong(Object target, String name, long value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static void setBoolean(Object target, String name, boolean value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static long longField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static boolean booleanField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }
}
