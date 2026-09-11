package com.openggf.audio.session;

import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.presentation.AudioPresentationCommand;
import com.openggf.audio.presentation.AudioPresentationSourceFactory;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.SmpsAssetKey;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsSessionDiagnostics {
    @Test
    void observerEventsPublishOnlyAfterCommit() {
        SmpsSessionTestFixtures.RecordingObserver writes =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = SmpsSessionTestFixtures.session(writes);
        List<SmpsDriverServiceObserver.LifecycleEvent> lifecycle =
                new ArrayList<>();
        session.setDriverServiceObserver(new SmpsDriverServiceObserver() {
            @Override
            public void onLifecycle(LifecycleEvent event) {
                lifecycle.add(event);
            }
        });
        session.install();
        writes.clear();
        lifecycle.clear();

        SmpsDriverSession.LiveMutationToken mutation =
                session.captureLiveMutation();
        session.applyCommand(new SmpsSessionCommand.HardReset());
        assertTrue(writes.events().isEmpty());
        assertTrue(lifecycle.isEmpty());
        session.commitLiveMutation(mutation);
        assertTrue(writes.events().isEmpty());
        assertTrue(lifecycle.isEmpty());

        session.publishCommittedDiagnostics();
        assertFalse(writes.events().isEmpty());
        assertEquals(List.of(
                        SmpsDriverServiceObserver.LifecycleKind.RESTORE,
                        SmpsDriverServiceObserver.LifecycleKind.RESET),
                lifecycle.stream().map(
                        SmpsDriverServiceObserver.LifecycleEvent::kind)
                        .toList());
    }

    @Test
    void observerExceptionIsQuarantinedWithoutReplay() {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        List<RuntimeException> failures = new ArrayList<>();
        int[] callbacks = {0};
        session.setDriverServiceObserver(new SmpsDriverServiceObserver() {
            @Override
            public void onLifecycle(LifecycleEvent event) {
                callbacks[0]++;
                throw new IllegalStateException("observer failed");
            }
        });
        session.setDiagnosticErrorSink(failures::add);
        session.install();
        failures.clear();
        callbacks[0] = 0;

        SmpsDriverSession.LiveMutationToken mutation =
                session.captureLiveMutation();
        session.applyCommand(new SmpsSessionCommand.HardReset());
        session.commitLiveMutation(mutation);
        session.publishCommittedDiagnostics();
        int published = callbacks[0];
        SmpsDriverSnapshot committed = session.captureLogicalSnapshot();

        session.publishCommittedDiagnostics();

        assertEquals(2, published);
        assertEquals(published, failures.size());
        assertEquals(published, callbacks[0]);
        assertEquals(committed, session.captureLogicalSnapshot());
    }

    @Test
    void everyPhysicalWriteHasStableSessionIdentityAndLogicalContext() {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        List<SmpsDriverServiceObserver.LifecycleEvent> lifecycle =
                new ArrayList<>();
        session.setDriverServiceObserver(new SmpsDriverServiceObserver() {
            @Override
            public void onLifecycle(LifecycleEvent event) {
                lifecycle.add(event);
            }
        });
        session.install();
        SmpsDriverServiceObserver.DriverIdentity identity = lifecycle.stream()
                .filter(event -> event.scope()
                        == SmpsDriverServiceObserver.LifecycleScope.DRIVER)
                .map(SmpsDriverServiceObserver.LifecycleEvent::driver)
                .findFirst().orElseThrow();
        lifecycle.clear();

        SmpsDriverSession.LiveMutationToken mutation =
                session.captureLiveMutation();
        session.applyCommand(new SmpsSessionCommand.HardReset());
        session.commitLiveMutation(mutation);
        session.publishCommittedDiagnostics();

        assertTrue(lifecycle.stream()
                .filter(event -> event.scope()
                        == SmpsDriverServiceObserver.LifecycleScope.DRIVER)
                .allMatch(event -> event.driver() == identity));
        assertSame(identity, session.logicalDriverForTesting()
                .diagnosticIdentity());
    }

    @Test
    void admissionDiagnosticsWaitForBothCompositeCommits() {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        session.install();
        SmpsCoordFlagHandlerOwner handlers = new SmpsCoordFlagHandlerOwner(
                new SmpsCoordFlagRuntimeState());
        AudioPresentationSourceFactory factory =
                new AudioPresentationSourceFactory(
                        () -> true, handlers,
                        AudioPresentationSourceFactory.Settings.defaults(),
                        session);
        AudioVoiceRegistry registry = new AudioVoiceRegistry(
                factory, factory, handlers, ignored -> { }, session);
        List<Boolean> decisions = new ArrayList<>();
        factory.setAdmissionObserver(decision ->
                decisions.add(decision.result().accepted()));
        SmpsAssetKey key = new SmpsAssetKey(
                "test", SmpsAssetKey.Route.BASE_ID, 0xA0, null);
        factory.registerSmpsSfxAsset(
                key, 0, new AudioTestFixtures.StubSmpsData("sfx"),
                SmpsSessionTestFixtures.dac(),
                new SmpsSequencerConfig.Builder().build(), false);
        var source = factory.resolveSmpsSfx(
                1, key, 1 << 16, 0x40, 0, 0, 128);
        var command = new AudioPresentationCommand.AddSmpsSfx(
                source, factory.prepareCached(source));

        SmpsDriverSession.LiveMutationToken sessionMutation =
                session.captureLiveMutation();
        AudioVoiceRegistry.LiveMutationToken registryMutation =
                registry.captureLiveMutation();
        registry.prepareSessionSfx(command);
        assertTrue(decisions.isEmpty());
        registry.commitLiveMutation(registryMutation);
        session.commitLiveMutation(sessionMutation);
        assertTrue(decisions.isEmpty());

        registry.publishCommittedDiagnostics(registryMutation);
        session.publishCommittedDiagnostics();
        assertEquals(List.of(true), decisions);
    }
}
