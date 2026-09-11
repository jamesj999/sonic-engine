package com.openggf.tools.audio.completerun;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioAdmissionObserver;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.driver.SfxContentionObserver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.rewind.AudioLogicalSnapshot;
import com.openggf.audio.synth.ChipWriteObserver;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.PreRowBoundary;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.RequestObserved;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease.RowObservation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestCompleteRunAudioObserverLease {
    private AudioManager audio;
    private EmittingBackend backend;

    @BeforeEach
    void setUp() {
        audio = AudioManager.getInstance();
        audio.destroy();
        audio.resetState();
        backend = new EmittingBackend();
        audio.setBackend(backend);
    }

    @AfterEach
    void tearDown() {
        audio.destroy();
        audio.resetState();
        audio.setBackend(new NullAudioBackend());
    }

    @Test
    void installationBeforeStartupRetainsAnExplicitPreRowBoundary() {
        try (CompleteRunAudioObserverLease lease =
                     CompleteRunAudioObserverLease.acquire(audio)) {
            audio.playMusic(0x81);

            AudioLogicalSnapshot pre = audio.captureLogicalSnapshot();
            PreRowBoundary boundary = lease.beginRow(0, pre);
            audio.playMusic(0x82);
            AudioLogicalSnapshot post = audio.captureLogicalSnapshot();
            RowObservation row = lease.finishRow(0, post);

            assertEquals(0, boundary.absoluteFrame());
            assertTrue(boundary.observationsBeforeRow().size() > 1,
                    "session boot diagnostics precede the first row");
            RequestObserved startup = boundary.observationsBeforeRow()
                    .stream().filter(RequestObserved.class::isInstance)
                    .map(RequestObserved.class::cast)
                    .findFirst().orElseThrow();
            RequestObserved frame = (RequestObserved) row.events().getFirst();
            assertEquals(0, startup.ordinal());
            assertEquals(0x81, startup.rawSoundId());
            assertEquals(boundary.firstRowEventOrdinal(), frame.ordinal());
            assertEquals(0x82, frame.rawSoundId());
            assertEquals(pre, boundary.logicalSnapshot());
            assertEquals(post, row.logicalSnapshot());
        }
    }

    @Test
    void rowsAreSingleUseSequentialAndImmutable() {
        try (CompleteRunAudioObserverLease lease =
                     CompleteRunAudioObserverLease.acquire(audio)) {
            PreRowBoundary firstBoundary = lease.beginRow(
                    4, audio.captureLogicalSnapshot());
            audio.playMusic(0x81);
            RowObservation first = lease.finishRow(
                    4, audio.captureLogicalSnapshot());

            assertThrows(UnsupportedOperationException.class,
                    () -> first.events().clear());
            assertThrows(IllegalStateException.class,
                    () -> lease.finishRow(4, audio.captureLogicalSnapshot()));
            assertThrows(IllegalStateException.class,
                    () -> lease.beginRow(6, audio.captureLogicalSnapshot()));

            PreRowBoundary secondBoundary = lease.beginRow(
                    5, audio.captureLogicalSnapshot());
            audio.playMusic(0x82);
            RowObservation second = lease.finishRow(
                    5, audio.captureLogicalSnapshot());
            assertEquals(4, first.absoluteFrame());
            assertEquals(5, second.absoluteFrame());
            assertEquals(firstBoundary.firstRowEventOrdinal(),
                    first.events().getFirst().ordinal());
            assertEquals(secondBoundary.firstRowEventOrdinal(),
                    second.events().getFirst().ordinal());
        }
    }

    @Test
    void callbacksOutsideAnActiveLaterRowFailInsteadOfChangingRowOwnership() {
        try (CompleteRunAudioObserverLease lease =
                     CompleteRunAudioObserverLease.acquire(audio)) {
            lease.beginRow(0, audio.captureLogicalSnapshot());
            lease.finishRow(0, audio.captureLogicalSnapshot());

            assertThrows(IllegalStateException.class,
                    () -> audio.playMusic(0x81));
        }
    }

    @Test
    void callbackDomainsShareOneGlobalSourceOrder() {
        try (CompleteRunAudioObserverLease lease =
                     CompleteRunAudioObserverLease.acquire(audio)) {
            PreRowBoundary boundary = lease.beginRow(
                    0, audio.captureLogicalSnapshot());

            audio.playMusic(0x81);
            backend.chipWrite.onPsgWrite(0x92);
            backend.driverService.onLifecycle(
                    SmpsDriverServiceObserver.LifecycleEvent.session(
                            SmpsDriverServiceObserver.LifecycleKind.PAUSE));
            RowObservation row = lease.finishRow(
                    0, audio.captureLogicalSnapshot());

            assertEquals(3, row.events().size());
            assertInstanceOf(RequestObserved.class, row.events().get(0));
            assertInstanceOf(
                    CompleteRunAudioObserverLease.PsgWriteObserved.class,
                    row.events().get(1));
            assertInstanceOf(
                    CompleteRunAudioObserverLease.LifecycleObserved.class,
                    row.events().get(2));
            long firstOrdinal = boundary.firstRowEventOrdinal();
            assertEquals(firstOrdinal, row.events().get(0).ordinal());
            assertEquals(firstOrdinal + 1, row.events().get(1).ordinal());
            assertEquals(firstOrdinal + 2, row.events().get(2).ordinal());
        }
    }

    @Test
    void closeStopsObservationAndReleasesTheManagerLease() {
        CompleteRunAudioObserverLease lease =
                CompleteRunAudioObserverLease.acquire(audio);
        assertTrue(lease.active());

        lease.close();

        assertFalse(lease.active());
        audio.playMusic(0x81);
        CompleteRunAudioObserverLease replacement =
                CompleteRunAudioObserverLease.acquire(audio);
        replacement.close();
    }

    private static final class EmittingBackend extends NullAudioBackend {
        private AudioAdmissionObserver admission = AudioAdmissionObserver.NONE;
        private SmpsDriverServiceObserver driverService =
                SmpsDriverServiceObserver.NONE;
        private ChipWriteObserver chipWrite = ChipWriteObserver.NONE;
        private SfxContentionObserver sfxContention =
                SfxContentionObserver.NONE;

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
            chipWrite = observer;
        }

        @Override
        public void setSfxContentionObserver(
                SfxContentionObserver observer) {
            sfxContention = observer;
        }
    }
}
