package com.openggf.tests.trace.runs;

import com.openggf.audio.AudioManager.DiagnosticObserverSet;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.game.sonic2.audio.Sonic2Music;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestS2RequestProjectionAudioRecorder {

    @Test
    void sfxServiceCannotSupplyMusicTickCompletionOrWrites() {
        S2RequestProjectionBk2Capture.ProductionAudioRecorder recorder =
                new S2RequestProjectionBk2Capture.ProductionAudioRecorder();
        DiagnosticObserverSet observers = recorder.observers();
        recorder.beginRow(10_202);
        SmpsDriverServiceObserver.ServiceEvent event = event(
                SmpsSourceDescriptor.Kind.BASE_SFX_ID,
                Sonic2Music.EMERALD_HILL.id, true);

        observers.driverService().onServiceBegin(event);
        observers.chipWrite().onPsgWrite(0x91);
        observers.driverService().onServiceEnd(event, snapshot());
        S2RequestProjectionBk2TestBridge.ProductionAudioRow row =
                recorder.finishObservedRow();

        assertFalse(row.completedDriverService());
        assertEquals(List.of(), row.writes());
    }

    @Test
    void sameIdWithWrongSourceKindCannotSupplyMusicTickCompletionOrWrites() {
        S2RequestProjectionBk2Capture.ProductionAudioRecorder recorder =
                new S2RequestProjectionBk2Capture.ProductionAudioRecorder();
        DiagnosticObserverSet observers = recorder.observers();
        recorder.beginRow(10_202);
        SmpsDriverServiceObserver.ServiceEvent event = event(
                SmpsSourceDescriptor.Kind.BASE_SFX_ID,
                Sonic2Music.EMERALD_HILL.id, false);

        observers.driverService().onServiceBegin(event);
        observers.chipWrite().onYm2612Write(0, 0x28, 0xf0);
        observers.driverService().onServiceEnd(event, snapshot());
        S2RequestProjectionBk2TestBridge.ProductionAudioRow row =
                recorder.finishObservedRow();

        assertFalse(row.completedDriverService());
        assertEquals(List.of(), row.writes());
    }

    private static SmpsDriverServiceObserver.ServiceEvent event(
            SmpsSourceDescriptor.Kind kind, int id, boolean sfx) {
        SmpsSourceDescriptor source = new SmpsSourceDescriptor(
                kind, id, null, null, 0, 0, 0, false, 0);
        return new SmpsDriverServiceObserver.ServiceEvent(
                1, SmpsDriverServiceObserver.DriverIdentity.unspecified(),
                new SmpsDriverServiceObserver.SequencerIdentity(1, source, sfx),
                SmpsDriverServiceObserver.ServiceKind.SEQUENCER_TICK);
    }

    private static SmpsDriverSnapshot snapshot() {
        return new SmpsDriverSnapshot(
                SmpsSequencer.Region.NTSC, SmpsDriver.ReadMode.HYBRID,
                0, false, 0, 5, List.of(),
                new int[6], new int[4], List.of());
    }
}
