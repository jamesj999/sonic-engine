package com.openggf.audio.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsSessionThreadOwnership {
    @Test
    void offOwnerThreadEntryPointsFailBeforeMutation()
            throws InterruptedException {
        SmpsSessionTestFixtures.RecordingObserver observer =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsPhysicalDevice device = new SmpsPhysicalDevice(
                SmpsSessionTestFixtures.settings(), observer);
        SmpsPhysicalDevice.Snapshot snapshot = device.captureSnapshot();
        SmpsPhysicalDevice.LiveMutationToken token =
                device.captureLiveMutation();
        SmpsWriteProgram writes = new SmpsWriteProgram(List.of(
                new SmpsChipWrite.Psg(0x9F)));

        List<Runnable> entries = List.of(
                () -> device.apply(writes),
                () -> device.applyTransientPsgSilence(writes),
                () -> device.renderFrames(new short[2], 0, 1),
                device::captureSnapshot,
                () -> device.restoreSnapshot(snapshot, null),
                device::captureLiveMutation,
                () -> device.rollbackLiveMutation(token),
                device::close);

        for (Runnable entry : entries) {
            assertInstanceOf(IllegalStateException.class,
                    runOffThread(entry));
        }
        assertTrue(observer.events().isEmpty());
        assertEquals(SmpsSessionTestFixtures.json(snapshot),
                SmpsSessionTestFixtures.json(device.captureSnapshot()));
        device.rollbackLiveMutation(token);
        device.close();
    }

    @Test
    void sessionAndPortOffOwnerEntriesFailBeforeMutation()
            throws InterruptedException {
        SmpsSessionTestFixtures.RecordingObserver observer =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = SmpsSessionTestFixtures.session(observer);
        SmpsPhysicalPort port = session.openTestEpoch(
                SmpsSessionTestFixtures.owner(10));
        SmpsPhysicalPort.AdmissionToken admission =
                port.captureAdmissionState(1, 1);
        SmpsDriverSession.LiveMutationToken mutation =
                session.captureLiveMutation();
        var before = SmpsSessionTestFixtures.json(session.captureSnapshot());

        List<Runnable> entries = List.of(
                session::installed,
                session::captureSnapshot,
                session::captureLiveMutation,
                () -> session.commitLiveMutation(mutation),
                () -> session.rollbackLiveMutation(mutation),
                () -> session.applyChannelMasks(1, 1),
                () -> session.openTestEpoch(
                        SmpsSessionTestFixtures.owner(11)),
                () -> session.closeTestEpoch(port.epoch()),
                () -> port.writeFm(0, 0xA0, 0x20),
                () -> port.writePsg(0x9F),
                () -> port.applyTransientPsgSilence(
                        SmpsWriteProgram.SILENCE_ALL_PSG),
                () -> port.setInstrument(0, new byte[25]),
                () -> port.selectDac(new SmpsDacSelection(
                        SmpsSessionTestFixtures.source(12),
                        SmpsSessionTestFixtures.dac())),
                () -> port.playDac(0x81),
                port::stopDac,
                () -> port.forceSilenceFmChannel(0),
                () -> port.setFmMute(0, true),
                () -> port.setPsgMute(0, true),
                port::silenceOutput,
                () -> port.captureAdmissionState(1, 1),
                () -> port.restoreAdmissionState(admission),
                session::close);

        for (Runnable entry : entries) {
            assertInstanceOf(IllegalStateException.class,
                    runOffThread(entry));
        }
        assertTrue(observer.events().isEmpty());
        assertEquals(before, SmpsSessionTestFixtures.json(
                session.captureSnapshot()));
        session.commitLiveMutation(mutation);
        session.closeTestEpoch(port.epoch());
        session.close();
    }

    private static Throwable runOffThread(Runnable action)
            throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "smps-session-non-owner");
        thread.start();
        thread.join();
        return failure.get();
    }
}
