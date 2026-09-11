package com.openggf.game.sonic3k;

import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.HardwareWorkPreparation;
import com.openggf.game.timing.HardwareWorkPreparationSnapshot;
import com.openggf.game.timing.HardwareWorkSubmission;
import com.openggf.game.timing.LoadTimeDecisionSource;
import com.openggf.game.timing.LoadTimeSimulationMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kLoadTimeProfile {

    @Test
    void kosModuleParentHasExplicitZeroAdditionalCostWithoutWarning() {
        Sonic3kGameModule module = new Sonic3kGameModule();
        List<String> warnings = new ArrayList<>();
        var profile = module.createLoadTimeProfile(
                LoadTimeSimulationMode.PROFILED, warnings::add);
        HardwareWorkSubmission submission = new HardwareWorkSubmission(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                0x1234, 100, 0x4000, 2048,
                "kosinski_moduled", 2, false, new NeverPrepared());
        HardwareWorkHandle handle = new HardwareWorkHandle(
                HardwareWorkKind.KOS_MODULE_QUEUE, 0, "sha256:parent");

        var decision = profile.assign(submission, handle);

        assertEquals(0, decision.serviceFrames());
        assertEquals(LoadTimeDecisionSource.IMMEDIATE, decision.source());
        assertEquals("s3k-kos-v1-composite-parent", decision.serviceModel());
        assertEquals(List.of(), warnings);
    }

    @Test
    void fastManifestLoadsWithoutWarningAndKeepsTheCompositeParentRule() {
        Sonic3kGameModule module = new Sonic3kGameModule();
        List<String> warnings = new ArrayList<>();
        var profile = module.createLoadTimeProfile(
                LoadTimeSimulationMode.FAST, warnings::add);
        HardwareWorkSubmission submission = new HardwareWorkSubmission(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                0x1234, 100, 0x4000, 2048,
                "kosinski_moduled", 2, false, new NeverPrepared());
        HardwareWorkHandle handle = new HardwareWorkHandle(
                HardwareWorkKind.KOS_MODULE_QUEUE, 0, "sha256:parent");

        var decision = profile.assign(submission, handle);

        assertEquals(0, decision.serviceFrames());
        assertEquals(LoadTimeDecisionSource.IMMEDIATE, decision.source());
        assertEquals(List.of(), warnings);
    }

    private static final class NeverPrepared implements HardwareWorkPreparation {
        @Override public boolean stepOneWorkUnit() { return false; }
        @Override public boolean isPrepared() { return false; }
        @Override public byte[] preparedPayload() { return new byte[0]; }
        @Override public HardwareWorkPreparationSnapshot snapshot() {
            throw new UnsupportedOperationException();
        }
        @Override public void restore(HardwareWorkPreparationSnapshot snapshot) {
            throw new UnsupportedOperationException();
        }
    }
}
