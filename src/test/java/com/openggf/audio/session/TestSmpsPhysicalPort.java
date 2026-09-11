package com.openggf.audio.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSmpsPhysicalPort {
    @Test
    void hiddenOutgoingStaleEpochCrossOwnerAndCrossSessionCallsFailBeforeMutation() {
        SmpsSessionTestFixtures.RecordingObserver firstObserver =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session =
                SmpsSessionTestFixtures.session(firstObserver);
        SmpsDriverSession other = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        SmpsDriverServiceObserver.DriverIdentity owner =
                SmpsSessionTestFixtures.owner(1);

        SmpsPhysicalPort first = session.openTestEpoch(owner);
        SmpsPhysicalPort.AdmissionToken token =
                first.captureAdmissionState(1, 1);
        session.closeTestEpoch(first.epoch());
        JsonNode before = SmpsSessionTestFixtures.json(
                session.captureSnapshot());

        assertThrows(IllegalStateException.class,
                () -> first.writePsg(0x9F));
        assertEquals(before, SmpsSessionTestFixtures.json(
                session.captureSnapshot()));

        SmpsPhysicalPort otherPort = other.openTestEpoch(owner);
        JsonNode otherBefore = SmpsSessionTestFixtures.json(
                other.captureSnapshot());
        assertThrows(IllegalArgumentException.class,
                () -> otherPort.restoreAdmissionState(token));
        assertEquals(otherBefore, SmpsSessionTestFixtures.json(
                other.captureSnapshot()));
        assertTrue(firstObserver.events().isEmpty());
    }

    @Test
    void admissionRollbackIsBoundedAndByteExact() {
        SmpsSessionTestFixtures.RecordingObserver observer =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = SmpsSessionTestFixtures.session(observer);
        SmpsDriverServiceObserver.DriverIdentity owner =
                SmpsSessionTestFixtures.owner(2);
        SmpsPhysicalPort port = session.openTestEpoch(owner);
        JsonNode before = SmpsSessionTestFixtures.json(
                session.captureSnapshot());
        SmpsPhysicalPort.AdmissionToken token =
                port.captureAdmissionState(1, 1);

        port.writeFm(0, 0xA0, 0x34);
        port.writePsg(0x84);
        port.writePsg(0x12);
        port.writePsg(0x92);
        observer.clear();
        port.restoreAdmissionState(token);

        assertTrue(observer.events().isEmpty());
        assertEquals(before, SmpsSessionTestFixtures.json(
                session.captureSnapshot()));
        assertThrows(IllegalStateException.class,
                () -> port.restoreAdmissionState(token));

        SmpsPhysicalPort.AdmissionToken staleOwnerToken =
                port.captureAdmissionState(1, 1);
        session.closeTestEpoch(port.epoch());
        SmpsPhysicalPort replacement = session.openTestEpoch(
                SmpsSessionTestFixtures.owner(3));
        assertThrows(IllegalArgumentException.class,
                () -> replacement.restoreAdmissionState(staleOwnerToken));
    }

    @Test
    void admissionTokenIsSingleUseAndBoundToDeviceEpochOwner() {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        SmpsDriverSession other = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        SmpsDriverServiceObserver.DriverIdentity owner =
                SmpsSessionTestFixtures.owner(40);
        SmpsPhysicalPort port = session.openTestEpoch(owner);
        SmpsPhysicalPort.AdmissionToken token =
                port.captureAdmissionState(1, 1);

        port.restoreAdmissionState(token);
        assertThrows(IllegalStateException.class,
                () -> port.restoreAdmissionState(token));
        SmpsPhysicalPort.AdmissionToken epochBound =
                port.captureAdmissionState(1, 1);
        session.closeTestEpoch(port.epoch());
        SmpsPhysicalPort next = session.openTestEpoch(owner);
        assertThrows(IllegalArgumentException.class,
                () -> next.restoreAdmissionState(epochBound));
        SmpsPhysicalPort otherPort = other.openTestEpoch(owner);
        assertThrows(IllegalArgumentException.class,
                () -> otherPort.restoreAdmissionState(epochBound));
    }

    @Test
    void physicalPortRoutesDacInstrumentAndSilenceOnlyDuringCurrentEpoch() {
        SmpsSessionTestFixtures.RecordingObserver observer =
                new SmpsSessionTestFixtures.RecordingObserver();
        SmpsDriverSession session = SmpsSessionTestFixtures.session(observer);
        SmpsDriverServiceObserver.DriverIdentity owner =
                SmpsSessionTestFixtures.owner(4);
        SmpsPhysicalPort port = session.openTestEpoch(owner);
        byte[] voice = {
                0x32,
                0x71, 0x0D, 0x33, 0x01,
                0x5F, 0x5F, 0x5F, 0x5F,
                0x14, 0x0E, 0x0E, 0x0E,
                0x08, 0x08, 0x08, 0x08,
                0x0F, 0x0F, 0x0F, 0x0F,
                0x1B, 0x16, 0x1F, 0x00
        };
        JsonNode before = SmpsSessionTestFixtures.json(
                session.captureSnapshot());

        port.setInstrument(0, voice);
        port.selectDac(new SmpsDacSelection(
                SmpsSessionTestFixtures.source(5),
                SmpsSessionTestFixtures.dac()));
        port.playDac(0x81);
        port.stopDac();
        port.forceSilenceFmChannel(0);
        SmpsDriverSessionSnapshot committed = session.captureSnapshot();
        int committedWriteCount = observer.events().size();

        assertSame(owner, port.owner());
        assertEquals(SmpsSessionTestFixtures.source(5),
                committed.selectedDacSource());
        assertNotEquals(before, SmpsSessionTestFixtures.json(committed));
        assertEquals(30, committedWriteCount);

        session.closeTestEpoch(port.epoch());
        assertThrows(IllegalStateException.class,
                () -> port.playDac(0x82));
        assertEquals(committedWriteCount, observer.events().size());
        assertEquals(SmpsSessionTestFixtures.json(committed),
                SmpsSessionTestFixtures.json(session.captureSnapshot()));
    }

    @Test
    void muteAndOutputControlsRequireTheCurrentScopedEpoch() {
        SmpsDriverSession session = SmpsSessionTestFixtures.session(
                new SmpsSessionTestFixtures.RecordingObserver());
        session.install();

        session.applyChannelMasks(1 << 2, 1 << 1);
        SmpsDriverSessionSnapshot masked = session.captureSnapshot();
        assertTrue(masked.physical().synth().ym().mutes()[2]);
        assertTrue(masked.physical().synth().psg().mutes()[1]);

        SmpsPhysicalPort port = session.openTestEpoch(
                SmpsSessionTestFixtures.owner(50));
        port.setFmMute(2, false);
        port.setPsgMute(1, false);
        port.writePsg(0x84);
        assertFalse(session.captureSnapshot().physical().outputSilenced());
        port.silenceOutput();
        session.closeTestEpoch(port.epoch());
        JsonNode committed = SmpsSessionTestFixtures.json(
                session.captureSnapshot());

        assertFalse(session.captureSnapshot()
                .physical().synth().ym().mutes()[2]);
        assertFalse(session.captureSnapshot()
                .physical().synth().psg().mutes()[1]);
        assertTrue(session.captureSnapshot().physical().outputSilenced());
        assertThrows(IllegalStateException.class,
                () -> port.setFmMute(2, true));
        assertThrows(IllegalStateException.class,
                () -> port.setPsgMute(1, true));
        assertThrows(IllegalStateException.class, port::silenceOutput);
        assertEquals(committed, SmpsSessionTestFixtures.json(
                session.captureSnapshot()));
    }
}
