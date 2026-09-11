package com.openggf.audio;

import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.synth.ChipWriteObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAudioManagerDiagnosticObserverLease {
    private AudioManager audio;
    private TrackingBackend backend;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.destroy();
        audio.resetState();
        backend = new TrackingBackend();
        audio.setBackend(backend);
    }

    @AfterEach
    void tearDown() {
        audio.destroy();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @Test
    void leasePublishesOneWholeObserverSetAndRejectsReplacement() {
        AudioManager.DiagnosticObserverSet observers = observerSet();

        AudioManager.DiagnosticObserverHandle handle =
                audio.acquireDiagnosticObservers(observers);

        assertTrue(handle.active());
        assertSame(observers.admission(), backend.admission);
        assertSame(observers.driverService(), backend.driverService);
        assertSame(observers.chipWrite(), backend.chipWrite);
        assertSame(observers.sfxContention(), backend.sfxContention);
        assertThrows(IllegalStateException.class,
                () -> audio.acquireDiagnosticObservers(observerSet()));
        assertThrows(IllegalStateException.class,
                () -> audio.setAdmissionObserver(AudioAdmissionObserver.NONE));
        handle.close();
    }

    @Test
    void resetAndBackendReplacementPreserveTheActiveLease() {
        AudioManager.DiagnosticObserverSet observers = observerSet();
        AudioManager.DiagnosticObserverHandle handle =
                audio.acquireDiagnosticObservers(observers);

        audio.resetState();
        TrackingBackend replacement = new TrackingBackend();
        audio.setBackend(replacement);

        assertTrue(handle.active());
        assertSame(observers.admission(), replacement.admission);
        assertSame(observers.driverService(), replacement.driverService);
        assertSame(observers.chipWrite(), replacement.chipWrite);
        assertSame(observers.sfxContention(), replacement.sfxContention);
        handle.close();
    }

    @Test
    void closeClearsObserversAndReleasesExclusiveOwnership() {
        AudioManager.DiagnosticObserverHandle handle =
                audio.acquireDiagnosticObservers(observerSet());

        handle.close();

        assertFalse(handle.active());
        assertSame(AudioAdmissionObserver.NONE, backend.admission);
        assertSame(SmpsDriverServiceObserver.NONE, backend.driverService);
        assertSame(ChipWriteObserver.NONE, backend.chipWrite);
        assertSame(SfxContentionObserver.NONE, backend.sfxContention);
        AudioManager.DiagnosticObserverHandle replacement =
                audio.acquireDiagnosticObservers(observerSet());
        assertTrue(replacement.active());
        replacement.close();
    }

    @Test
    void failedCloseKeepsLeaseActiveAndCanBeRetried() {
        AudioManager.DiagnosticObserverSet observers = observerSet();
        AudioManager.DiagnosticObserverHandle handle =
                audio.acquireDiagnosticObservers(observers);
        backend.failNextChipObserver = true;

        assertThrows(IllegalStateException.class, handle::close);
        assertTrue(handle.active());
        assertSame(observers.admission(), backend.admission,
                "failed cleanup must roll earlier observer slots back");

        handle.close();
        assertFalse(handle.active());
    }

    @Test
    void failedAcquisitionRollsBackAndLeavesOwnershipAvailable() {
        backend.failNextChipObserver = true;

        assertThrows(IllegalStateException.class,
                () -> audio.acquireDiagnosticObservers(observerSet()));
        assertSame(AudioAdmissionObserver.NONE, backend.admission);
        assertSame(SmpsDriverServiceObserver.NONE, backend.driverService);
        assertSame(ChipWriteObserver.NONE, backend.chipWrite);
        assertSame(SfxContentionObserver.NONE, backend.sfxContention);

        AudioManager.DiagnosticObserverHandle retry =
                audio.acquireDiagnosticObservers(observerSet());
        assertTrue(retry.active());
        retry.close();
    }

    @Test
    void onlyTheAcquiringThreadCanCloseTheLease() throws Exception {
        AudioManager.DiagnosticObserverHandle handle =
                audio.acquireDiagnosticObservers(observerSet());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                handle.close();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });

        thread.start();
        thread.join();

        assertTrue(failure.get() instanceof IllegalStateException);
        assertTrue(handle.active());
        handle.close();
    }

    private static AudioManager.DiagnosticObserverSet observerSet() {
        return new AudioManager.DiagnosticObserverSet(
                (requestClass, rawSoundId) -> { },
                decision -> { },
                new SmpsDriverServiceObserver() { },
                new ChipWriteObserver() {
                    @Override
                    public void onYm2612Write(int port, int register, int value) {
                    }

                    @Override
                    public void onPsgWrite(int value) {
                    }
                },
                new SfxContentionObserver() { });
    }

    private static final class TrackingBackend extends NullAudioBackend {
        private AudioAdmissionObserver admission = AudioAdmissionObserver.NONE;
        private SmpsDriverServiceObserver driverService =
                SmpsDriverServiceObserver.NONE;
        private ChipWriteObserver chipWrite = ChipWriteObserver.NONE;
        private SfxContentionObserver sfxContention =
                SfxContentionObserver.NONE;
        private boolean failNextChipObserver;

        @Override
        public void setAdmissionObserver(AudioAdmissionObserver observer) {
            admission = observer;
        }

        @Override
        public void setDriverServiceObserver(
                SmpsDriverServiceObserver observer) {
            driverService = observer;
        }

        @Override
        public void setChipWriteObserver(ChipWriteObserver observer) {
            if (failNextChipObserver) {
                failNextChipObserver = false;
                throw new IllegalStateException("injected chip observer failure");
            }
            chipWrite = observer;
        }

        @Override
        public void setSfxContentionObserver(
                SfxContentionObserver observer) {
            sfxContention = observer;
        }
    }
}
