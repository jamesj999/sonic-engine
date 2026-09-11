package com.openggf.audio.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerConfig;
import com.openggf.audio.synth.VirtualSynthesizer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSfxAdmissionMutationJournal {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void selectiveChipStateMatchesFullSnapshotOracleForEveryLegalMask() {
        for (int fmMask = 0; fmMask < 1 << 6; fmMask++) {
            for (int psgMask = 0; psgMask < 1 << 4; psgMask++) {
                VirtualSynthesizer synth = new VirtualSynthesizer(48_000);
                VirtualSynthesizer.Snapshot full =
                        synth.captureSynthSnapshot();
                VirtualSynthesizer.SfxAdmissionState selective =
                        synth.captureSfxAdmissionState(fmMask, psgMask);

                mutateAffectedChannels(synth, fmMask, psgMask);
                synth.restoreSfxAdmissionState(selective);

                assertEquals(JSON.valueToTree(full),
                        JSON.valueToTree(synth.captureSynthSnapshot()),
                        "selective restore differs for FM mask " + fmMask
                                + " PSG mask " + psgMask);
            }
        }
    }

    @Test
    void selectiveStateRetainsNoWholeChipSnapshot() {
        VirtualSynthesizer synth = new VirtualSynthesizer(48_000);
        Object state = synth.captureSfxAdmissionState(0, 0);

        assertRetainsNoSnapshot(state, state.getClass());
    }

    @Test
    void continuousExtensionSkipsChipStateAndRetriesAfterJournalRestore()
            throws Exception {
        CountingJournalDriver driver = new CountingJournalDriver();
        AudioTestFixtures.StubSmpsData continuousData =
                new AudioTestFixtures.StubSmpsData("continuous");
        continuousData.setId(0xBC);
        SmpsSequencer existing = new SmpsSequencer(
                continuousData,
                AudioTestFixtures.EMPTY_DAC, driver,
                AudioManager.getInstance(),
                new SmpsSequencerConfig.Builder().build());
        existing.setSfxMode(true);
        driver.addSequencer(existing, true);
        driver.startContinuousSfx(0xBC, 3);
        PreparedSfxAdmission extension =
                driver.prepareContinuousSfxExtension(0xBC, 5);
        SmpsCoordFlagRuntimeState coord = new SmpsCoordFlagRuntimeState();
        SfxAdmissionMutationJournal journal =
                SfxAdmissionMutationJournal.capture(
                        driver, extension, coord, coord.snapshot());

        Field driverStateField = SfxAdmissionMutationJournal.class
                .getDeclaredField("driverState");
        driverStateField.setAccessible(true);
        Object driverState = driverStateField.get(journal);
        for (Field field : driverState.getClass().getDeclaredFields()) {
            if (!field.getType().isArray()) {
                continue;
            }
            field.setAccessible(true);
            assertEquals(null, field.get(driverState),
                    "continuous journal retains general array " + field);
        }
        assertThrows(NoSuchFieldException.class,
                () -> driverState.getClass().getDeclaredField("synthState"),
                "logical admission journals cannot retain physical chip state");

        driver.commitSfxAdmissionUnderJournal(extension);
        journal.restore();
        driver.commitSfxAdmission(extension);
        assertEquals(1, driver.captureCalls,
                "one registry journal owns capture across commit");
    }

    private static void assertRetainsNoSnapshot(
            Object value, Class<?> owner) {
        for (Field field : owner.getDeclaredFields()) {
            field.setAccessible(true);
            assertEquals(false,
                    field.getType() == VirtualSynthesizer.Snapshot.class
                            || field.getType().getSimpleName()
                            .equals("Snapshot"),
                    "selective state retains whole snapshot field " + field);
            try {
                Object nested = field.get(value);
                if (nested != null
                        && field.getType().getSimpleName()
                        .endsWith("SfxAdmissionState")) {
                    assertRetainsNoSnapshot(nested, field.getType());
                }
            } catch (IllegalAccessException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static void mutateAffectedChannels(
            VirtualSynthesizer synth, int fmMask, int psgMask) {
        for (int channel = 0; channel < 6; channel++) {
            if ((fmMask & (1 << channel)) == 0) {
                continue;
            }
            synth.forceSilenceChannel(channel);
            int port = channel / 3;
            int hardwareChannel = channel % 3 + (port == 0 ? 0 : 4);
            synth.writeFm(null, port, 0x28, hardwareChannel);
        }
        for (int channel = 0; channel < 4; channel++) {
            if ((psgMask & (1 << channel)) != 0) {
                synth.writePsg(null,
                        0x80 | (channel << 5) | 0x10 | channel);
            }
        }
    }

    private static final class CountingJournalDriver extends SmpsDriver {
        private int captureCalls;

        @Override
        SfxAdmissionMutationState captureSfxAdmissionMutation(
                PreparedSfxAdmission admission) {
            captureCalls++;
            return super.captureSfxAdmissionMutation(admission);
        }
    }
}
