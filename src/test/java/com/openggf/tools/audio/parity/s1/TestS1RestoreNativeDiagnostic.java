package com.openggf.tools.audio.parity.s1;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.audio.AudioAdmissionObserver.AudioAdmissionDecision;
import com.openggf.audio.AudioRequestObserver.RequestClass;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.AdmissionResult;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.RejectionReason;
import com.openggf.audio.driver.SmpsRequestAdmissionPolicy.SmpsAdmissionContext;
import com.openggf.audio.rewind.SmpsSourceDescriptor;
import com.openggf.tools.audio.completerun.CompleteRunAudioObserverLease;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestS1RestoreNativeDiagnostic {
    @TempDir Path temp;

    @Test
    void rejectsDataWithoutMatchingNativeAddress() throws Exception {
        assertRejected(boundary("[{\"native_ordinal\":1,\"port\":0,"
                + "\"event_kind\":3,\"register\":176,\"value\":58,\"data\":true}]"),
                "matching address");
    }

    @Test
    void rejectsUnmatchedRestoreIdentity() throws Exception {
        assertRejected(stream("", boundaryRecord(validWrites())), "request identity");
    }

    @Test
    void rejectsRestoreWithoutTerminalKeyOff() throws Exception {
        assertRejected(boundary("[{\"native_ordinal\":1,\"port\":0,"
                + "\"event_kind\":3,\"register\":0,\"value\":176,\"data\":false},"
                + "{\"native_ordinal\":2,\"event_kind\":3,\"port\":0,\"register\":176,"
                + "\"value\":58,\"data\":true}]"), "key-off");
    }

    @Test
    void acceptsInterleavedPsgWithoutTreatingStaleRegisterAsYmLatch()
            throws Exception {
        Path raw = Files.writeString(temp.resolve("raw.jsonl"), boundary(validWrites()));
        Path seal = Files.writeString(temp.resolve("seal.json"), attestation());
        S1RestoreNativeDiagnostic.Capture capture =
                S1RestoreNativeDiagnostic.readVerified(raw, seal);
        assertTrue(capture.boundary().writes().getLast().chip().equals("PSG"));
    }

    @Test
    void rejectsUnexpectedNativeWriteKind() throws Exception {
        assertRejected(boundary("[{\"native_ordinal\":1,\"event_kind\":9,"
                + "\"port\":0,\"register\":0,\"value\":255,\"data\":true}]"),
                "unexpected");
    }

    @Test
    void rejectsDoubledAddressAndAddressWithoutData() throws Exception {
        assertRejected(boundary("[{\"native_ordinal\":1,\"event_kind\":3,"
                + "\"port\":0,\"register\":0,\"value\":176,\"data\":false},"
                + "{\"native_ordinal\":2,\"event_kind\":3,\"port\":0,"
                + "\"register\":176,\"value\":48,\"data\":false},"
                + "{\"native_ordinal\":3,\"event_kind\":4,\"port\":0,"
                + "\"register\":182,\"value\":255,\"data\":true}]"),
                "replaced");
    }

    @Test
    void publicReaderRejectsUnsealedDigestBeforeParsing() throws Exception {
        Path raw = Files.writeString(temp.resolve("raw.jsonl"), "altered\n");
        Path seal = Files.writeString(temp.resolve("seal.json"), "{}\n");
        IOException failure = assertThrows(IOException.class,
                () -> S1RestoreNativeDiagnostic.read(raw, seal));
        assertTrue(failure.getMessage().contains("byte count"));
    }

    @Test
    void rejectsAttestationThatDoesNotBindThePinnedRawDigest() throws Exception {
        Path raw = Files.writeString(temp.resolve("raw.jsonl"), boundary(validWrites()));
        Path seal = Files.writeString(temp.resolve("seal.json"),
                attestation().replace(S1RestoreNativeDiagnostic.RAW_SHA256,
                        "0".repeat(64)));
        IOException failure = assertThrows(IOException.class,
                () -> S1RestoreNativeDiagnostic.readVerified(raw, seal));
        assertTrue(failure.getMessage().contains("raw_sha256"));
    }

    @Test
    void restoreStopRequiresOneUpRequestAdmissionLifecycleAndFollowingServiceEnd() {
        S1RestoreDiagnosticTool.Probe probe = new S1RestoreDiagnosticTool.Probe(capture());
        var restore = new CompleteRunAudioObserverLease.LifecycleObserved(0,
                SmpsDriverServiceObserver.LifecycleEvent.driver(
                        SmpsDriverServiceObserver.LifecycleKind.RESTORE,
                        SmpsDriverServiceObserver.LifecycleSource.DRIVER_MUTATION,
                        SmpsDriverServiceObserver.DriverIdentity.unspecified()));
        var end = new CompleteRunAudioObserverLease.ServiceEndObserved(1,
                service(SmpsDriverServiceObserver.ServiceKind.SEQUENCER_TICK),
                nullSnapshot());
        probe.acceptEvents(0, java.util.List.of(restore, end));

        var request = new CompleteRunAudioObserverLease.RequestObserved(2,
                RequestClass.MUSIC, 0x88);
        var admission = new CompleteRunAudioObserverLease.AdmissionObserved(3,
                new AudioAdmissionDecision(new SmpsAdmissionContext(
                        0x88, 0x88, -1, -1, false, false),
                        new AdmissionResult(true, RejectionReason.NONE,
                                -1, -1, 0x88)));
        var unrelatedRestore = restore;
        var correctRestore = new CompleteRunAudioObserverLease.LifecycleObserved(4,
                SmpsDriverServiceObserver.LifecycleEvent.registry(
                        SmpsDriverServiceObserver.LifecycleKind.RESTORE,
                        SmpsDriverServiceObserver.LifecycleSource.MUSIC_OVERRIDE));
        var fadeEnd = new CompleteRunAudioObserverLease.ServiceEndObserved(5,
                service(SmpsDriverServiceObserver.ServiceKind.FADE_STEP),
                nullSnapshot());
        probe.acceptEvents(1, java.util.List.of(request, admission,
                unrelatedRestore, fadeEnd));
        probe.acceptEvents(2, java.util.List.of(correctRestore, fadeEnd));
        assertTrue(!probe.result().path("restored_service_end_observed").booleanValue());

        assertThrows(S1RestoreDiagnosticTool.Done.class, () -> probe.acceptEvents(3,
                java.util.List.of(new CompleteRunAudioObserverLease.ServiceEndObserved(
                        6, service(SmpsDriverServiceObserver.ServiceKind.SEQUENCER_TICK),
                        nullSnapshot()))));
        assertTrue(probe.result().path("restored_service_end_observed").booleanValue());
    }

    @Test
    void admissionBeforeOneUpRequestDoesNotSatisfyTheOrderedGate() {
        S1RestoreDiagnosticTool.Probe probe = new S1RestoreDiagnosticTool.Probe(capture());
        var admission = new CompleteRunAudioObserverLease.AdmissionObserved(0,
                new AudioAdmissionDecision(new SmpsAdmissionContext(
                        0x88, 0x88, -1, -1, false, false),
                        new AdmissionResult(true, RejectionReason.NONE,
                                -1, -1, 0x88)));
        var request = new CompleteRunAudioObserverLease.RequestObserved(1,
                RequestClass.MUSIC, 0x88);
        var restore = new CompleteRunAudioObserverLease.LifecycleObserved(2,
                SmpsDriverServiceObserver.LifecycleEvent.registry(
                        SmpsDriverServiceObserver.LifecycleKind.RESTORE,
                        SmpsDriverServiceObserver.LifecycleSource.MUSIC_OVERRIDE));
        var end = new CompleteRunAudioObserverLease.ServiceEndObserved(3,
                service(SmpsDriverServiceObserver.ServiceKind.SEQUENCER_TICK),
                nullSnapshot());

        probe.acceptEvents(0, java.util.List.of(admission, request, restore, end));

        assertTrue(!probe.result().path("one_up_admission_observed").booleanValue());
        assertTrue(!probe.result().path("restored_service_end_observed").booleanValue());
    }

    private static S1RestoreNativeDiagnostic.Capture capture() {
        return new S1RestoreNativeDiagnostic.Capture(860, 225101,
                java.util.List.of(), java.util.List.of(),
                new S1RestoreNativeDiagnostic.Boundary(3698, 3699, 3910,
                        2, 12, java.util.List.of()));
    }

    private static SmpsDriverServiceObserver.ServiceEvent service(
            SmpsDriverServiceObserver.ServiceKind kind) {
        return new SmpsDriverServiceObserver.ServiceEvent(1,
                SmpsDriverServiceObserver.DriverIdentity.unspecified(),
                new SmpsDriverServiceObserver.SequencerIdentity(1,
                        new SmpsSourceDescriptor(SmpsSourceDescriptor.Kind.UNKNOWN,
                                -1, null, null, 0, 0, 0, false, 0), false),
                kind);
    }

    private static com.openggf.audio.rewind.SmpsDriverSnapshot nullSnapshot() {
        return org.mockito.Mockito.mock(com.openggf.audio.rewind.SmpsDriverSnapshot.class);
    }

    private void assertRejected(String content, String message) throws Exception {
        Path raw = Files.writeString(temp.resolve("raw.jsonl"), content);
        Path seal = Files.writeString(temp.resolve("seal.json"), attestation());
        IOException failure = assertThrows(IOException.class,
                () -> S1RestoreNativeDiagnostic.readVerified(raw, seal));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
    }

    private static String boundary(String writes) {
        return stream(requestHistory(), boundaryRecord(writes));
    }

    private static String boundaryRecord(String writes) {
        return "{\"request\":\"cfFadeInToPrevious\","
                + "\"admission\":\"native_restore_entry\",\"request_frame\":3698,"
                + "\"admission_frame\":3699,\"frame\":3910,\"service_token\":2,"
                + "\"native_ordinal\":12,\"fix_bugs\":0,"
                + "\"writes_dac_disable_zero\":false,\"writes\":" + writes
                + ",\"type\":\"override_resume\"}";
    }

    private static String stream(String history, String boundary) {
        return "{\"type\":\"metadata\",\"schema\":\"openggf.s1-complete-run-audio-raw.v1\","
                + "\"rom_sha1\":\"69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B\","
                + "\"bk2_sha256\":\"" + S1RestoreNativeDiagnostic.BK2_SHA256 + "\","
                + "\"first_row\":860,\"exclusive_end\":225101,\"native_abi\":5}\n"
                + history + boundary + "\n";
    }

    private static String requestHistory() {
        return "{\"type\":\"request\",\"row\":3698,\"request_id\":9,\"sound_id\":136}\n"
                + "{\"type\":\"dispatch\",\"row\":3699,\"request_id\":9,\"sound_id\":136}\n";
    }

    private static String validWrites() {
        return "[{\"native_ordinal\":1,\"event_kind\":3,\"port\":0,\"register\":0,"
                + "\"value\":176,\"data\":false},{\"native_ordinal\":2,"
                + "\"event_kind\":3,\"port\":0,\"register\":176,\"value\":58,\"data\":true},"
                + "{\"native_ordinal\":3,\"event_kind\":4,\"port\":0,\"register\":182,"
                + "\"value\":255,\"data\":true}]";
    }

    private static String attestation() {
        return "{\"schema\":\"openggf.override-resume-first-divergence-attestation.v1\","
                + "\"raw_sha256\":\"" + S1RestoreNativeDiagnostic.RAW_SHA256 + "\","
                + "\"status\":\"ok\",\"fault_count\":0,\"overflow_count\":0}";
    }
}
