package com.openggf.tools.audio.parity.s1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openggf.audio.driver.SmpsDriverServiceObserver.LifecycleKind;
import com.openggf.audio.driver.SmpsDriverServiceObserver.LifecycleScope;
import com.openggf.audio.driver.SmpsDriverServiceObserver.LifecycleSource;
import com.openggf.audio.driver.SmpsDriverServiceObserver.ServiceKind;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.game.session.EngineServices;
import com.openggf.tools.HeadlessGameBoot;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease;
import com.openggf.tools.audio.completerun.ProductionBk2AudioRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Runtime-probed, non-publishing S1 restore prerequisite diagnostic. */
public final class S1RestoreDiagnosticTool {
    private static final ObjectMapper JSON = new ObjectMapper();
    private S1RestoreDiagnosticTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("usage: RAW ATTESTATION ROM BK2 NEW_OUTPUT");
        Path raw = Path.of(args[0]); Path seal = Path.of(args[1]);
        Path rom = Path.of(args[2]); Path movie = Path.of(args[3]);
        Path output = Path.of(args[4]);
        requireIdentity(rom, "SHA-1", S1RestoreNativeDiagnostic.ROM_SHA1);
        requireIdentity(movie, "SHA-256", S1RestoreNativeDiagnostic.BK2_SHA256);
        if (Files.exists(output)) throw new IOException("diagnostic output already exists");
        S1RestoreNativeDiagnostic.Capture reference = S1RestoreNativeDiagnostic.read(raw, seal);
        ObjectNode result = run(rom, movie, reference);
        Files.writeString(output, result.toPrettyString() + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("Diagnostic only: " + result.path("status").textValue());
    }

    static ObjectNode run(Path rom, Path movie,
            S1RestoreNativeDiagnostic.Capture reference) throws Exception {
        Probe probe = new Probe(reference);
        // Reuse only the established hidden GL lifetime; boot(...) would skip
        // the canonical title route and is deliberately not called.
        try (HeadlessGameBoot ignored = new HeadlessGameBoot(320, 224)) {
            var config = EngineServices.current().configuration();
            config.setConfigValue(SonicConfiguration.AUDIO_ENABLED, true);
            config.setConfigValue(SonicConfiguration.SHOW_LEGAL_DISCLAIMER_ON_STARTUP, false);
            config.setConfigValue(SonicConfiguration.MASTER_TITLE_SCREEN_ON_STARTUP, false);
            config.setConfigValue(SonicConfiguration.TITLE_SCREEN_ON_STARTUP, true);
            config.setConfigValue(SonicConfiguration.LEVEL_SELECT_ON_STARTUP, false);
            config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
            config.setConfigValue(SonicConfiguration.SCREEN_WIDTH_PIXELS, 320);
            config.setConfigValue(SonicConfiguration.SCREEN_HEIGHT_PIXELS, 224);
            config.setConfigValue(SonicConfiguration.DEFAULT_ROM, "s1");
            config.setConfigValue(SonicConfiguration.SONIC_1_ROM,
                    rom.toAbsolutePath().toString());
            try {
                ProductionBk2AudioRunner.run(EngineServices.current(),
                        new Bk2MovieLoader().load(movie), probe::accept);
            } catch (Done expected) { }
        }
        return probe.result();
    }

    static final class Probe {
        private final S1RestoreNativeDiagnostic.Capture reference;
        private final List<Integer> current = new ArrayList<>();
        private int currentRow = -1;
        private int completed = -1;
        private String divergence;
        private boolean oneUpRequest;
        private boolean oneUpAdmission;
        private boolean restoreLifecycle;
        private boolean restoredServiceEnd;

        Probe(S1RestoreNativeDiagnostic.Capture reference) { this.reference = reference; }

        void accept(ProductionBk2AudioRunner.RowResult row) {
            acceptEvents(row.absoluteFrame(), row.observation().events());
        }

        void acceptEvents(int row,
                List<CompleteRunAudioObserverLease.Observation> events) {
            completed = row; currentRow = completed; current.clear();
            for (var event : events) {
                if (event instanceof CompleteRunAudioObserverLease.RequestObserved value) {
                    current.add(value.rawSoundId());
                    if (value.rawSoundId() == 0x88) oneUpRequest = true;
                } else if (event instanceof CompleteRunAudioObserverLease.AdmissionObserved value
                        && oneUpRequest
                        && value.decision().context().requestedSoundId() == 0x88
                        && value.decision().result().accepted()) {
                    oneUpAdmission = true;
                } else if (event instanceof CompleteRunAudioObserverLease.LifecycleObserved value
                        && oneUpRequest && oneUpAdmission
                        && value.event().kind() == LifecycleKind.RESTORE
                        && value.event().scope() == LifecycleScope.REGISTRY
                        && value.event().source() == LifecycleSource.MUSIC_OVERRIDE) {
                    restoreLifecycle = true;
                } else if (event instanceof CompleteRunAudioObserverLease.ServiceEndObserved value
                        && restoreLifecycle
                        && value.event().kind() == ServiceKind.SEQUENCER_TICK) {
                    restoredServiceEnd = true;
                }
            }
            if (currentRow >= reference.firstRow()) {
                List<Integer> expected = reference.requests().stream()
                        .filter(value -> value.row() == currentRow)
                        .map(S1RestoreNativeDiagnostic.Request::soundId).toList();
                if (!expected.equals(current)) {
                    divergence = "request history diverged at row " + currentRow
                            + ": native=" + expected + " openggf=" + current;
                }
            }
            if (divergence != null || restoredServiceEnd
                    || currentRow >= reference.boundary().frame()) throw new Done();
        }

        ObjectNode result() {
            ObjectNode node = JSON.createObjectNode();
            node.put("schema", "openggf.s1-restore-diagnostic.v1");
            node.put("authority", "diagnostic-only-unpublished");
            node.put("native_raw_sha256", S1RestoreNativeDiagnostic.RAW_SHA256);
            node.put("native_attestation_sha256",
                    S1RestoreNativeDiagnostic.ATTESTATION_SHA256);
            node.put("rom_sha1", S1RestoreNativeDiagnostic.ROM_SHA1);
            node.put("bk2_sha256", S1RestoreNativeDiagnostic.BK2_SHA256);
            node.put("bounded_from_bk2_row", 0);
            node.put("bounded_through_completed_row", completed);
            node.put("native_reference_first_observed_row", reference.firstRow());
            node.put("native_restore_row", reference.boundary().frame());
            node.put("one_up_request_observed", oneUpRequest);
            node.put("one_up_admission_observed", oneUpAdmission);
            node.put("restore_lifecycle_observed", restoreLifecycle);
            node.put("restored_service_end_observed", restoredServiceEnd);
            node.put("exact_write_values_compared", false);
            if (divergence != null) {
                node.put("status", "PREREQUISITE_DIVERGENCE");
                node.put("first_prerequisite_divergence", divergence);
            } else if (!restoredServiceEnd) {
                node.put("status", "RESTORE_UNREACHED_AT_NATIVE_BOUND");
            } else {
                node.put("status", "REFERENCE_HISTORY_INCOMPLETE");
                node.put("limitation", "native request observation begins at row 860; exact state-dependent write comparison withheld");
            }
            return node;
        }
    }

    static final class Done extends RuntimeException { }

    private static void requireIdentity(Path path, String algorithm, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[1024 * 1024];
            for (int count; (count = input.read(bytes)) >= 0;) digest.update(bytes, 0, count);
        }
        if (!expected.equals(HexFormat.of().formatHex(digest.digest()))) {
            throw new IOException(path + " " + algorithm + " mismatch");
        }
    }
}
