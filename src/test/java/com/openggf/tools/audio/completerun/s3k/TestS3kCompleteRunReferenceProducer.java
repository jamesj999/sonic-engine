package com.openggf.tools.audio.completerun.s3k;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tools.audio.completerun.CompleteRunAudioProducer;
import com.openggf.tools.audio.completerun.CompleteRunAudioCaptureStore;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCompleteRunReferenceProducer {
    @TempDir Path temporary;

    @Test
    void projectsValidatedStateAndCutoffChipsWithoutInventingRequestsOrDecisions() throws Exception {
        Path raw = Files.writeString(temporary.resolve("s3k-prefix.jsonl"), rawPrefix(false));
        Path output = temporary.resolve("s3k-capture").toAbsolutePath();
        new S3kCompleteRunReferenceProjector().projectPrefixForTesting(raw.toAbsolutePath(), rom(), output);
        List<CompleteRunAudioTrace.Record> records = records(output);
        CompleteRunAudioTrace.Baseline baseline = (CompleteRunAudioTrace.Baseline) records.getFirst();
        CompleteRunAudioTrace.Frame frame = (CompleteRunAudioTrace.Frame) records.get(1);
        CompleteRunAudioTrace.CutoffFrontier cutoff =
                (CompleteRunAudioTrace.CutoffFrontier) records.get(2);
        assertEquals(null, baseline.state());
        assertEquals(null, baseline.roleOwners());
        assertEquals(null, frame.lag());
        assertEquals(null, frame.requests());
        assertEquals(null, frame.decisions());
        assertEquals(null, frame.services());
        assertEquals(null, frame.postRowState());
        assertEquals(List.of(new CompleteRunAudioTrace.PsgWrite(0, 0x44)), frame.chipEvents());
        assertEquals(null, cutoff.rawChipEvents());
        assertEquals(null, cutoff.terminalState());
    }

    @Test
    void assignsGloballyContiguousCanonicalOrdinalsAcrossFrames() throws Exception {
        Path raw = Files.writeString(temporary.resolve("s3k-two-frames.jsonl"), twoFramePrefix());

        List<CompleteRunAudioTrace.Record> records = new S3kCompleteRunReferenceProjector()
                .projectPrefixForTesting(raw.toAbsolutePath(), rom()).records();
        CompleteRunAudioTrace.Frame first = (CompleteRunAudioTrace.Frame) records.get(1);
        CompleteRunAudioTrace.Frame second = (CompleteRunAudioTrace.Frame) records.get(2);

        assertEquals(null, first.services());
        assertEquals(null, second.services());
        assertEquals(0, first.chipEvents().getFirst().ordinal());
        assertEquals(1, second.chipEvents().getFirst().ordinal());
    }

    @Test
    void lateRawFailureAbortsThePrivateProjectionTransaction() throws Exception {
        Path raw = Files.writeString(temporary.resolve("invalid.jsonl"), rawPrefix(true));
        Path output = temporary.resolve("invalid-capture").toAbsolutePath();
        assertThrows(IllegalArgumentException.class, () ->
                new S3kCompleteRunReferenceProjector().projectPrefixForTesting(raw.toAbsolutePath(), rom(), output));
        assertFalse(Files.exists(output));
    }

    @Test
    void preservesTheFullNativeOneActiveFourPendingCutoffThroughCanonicalProjection() throws Exception {
        Path raw = Files.writeString(temporary.resolve("s3k-frontier.jsonl"), fullFrontierPrefix());

        var records = new S3kCompleteRunReferenceProjector()
                .projectPrefixForTesting(raw.toAbsolutePath(), rom()).records();
        CompleteRunAudioTrace.CutoffFrontier cutoff =
                (CompleteRunAudioTrace.CutoffFrontier) records.get(2);

        assertNull(cutoff.activeStack());
        assertNull(cutoff.pendingDescendants());
        assertNull(cutoff.rawChipEvents());

        var chip = new CompleteRunAudioTrace.FrontierChipEvent(
                12, 12, "Z80", 4300, 4, 0, 68, true, null, null);
        var snapshot = new CompleteRunAudioTrace.FrontierSnapshot(2, "Z80", 4300, List.of(0x7f));
        var active = new CompleteRunAudioTrace.FrontierService(1, 0, 0, "DpcmIteration",
                CompleteRunAudioTrace.FrontierServiceState.OPEN, 810, 0, 4300, 10, "Z80",
                null, null, null, null, List.of(snapshot), List.of(chip), 0, 0, List.of());
        List<CompleteRunAudioTrace.FrontierService> pending = List.of(
                completedService(2, 1, 1, 1, 2, 1, 1, 132, 3, List.of()),
                completedService(3, 1, 1, 3, 4, 1, 1, 132, 3, List.of()),
                completedService(4, 1, 1, 5, 6, 1, 1, 132, 3, List.of()),
                completedService(5, 1, 1, 7, 8, 1, 1, 132, 3, List.of()));
        var nativeCutoff = cutoff.nativeDiagnostics();
        assertEquals(List.of(active), nativeCutoff.activeStack());
        assertEquals(pending, nativeCutoff.pendingDescendants());
        assertEquals(List.of(new CompleteRunAudioTrace.FrontierOwnedChip(1, chip)),
                nativeCutoff.rawChipInventory());
        assertEquals(List.of(new CompleteRunAudioTrace.FrontierOwnedSnapshot(1, 0, snapshot)),
                nativeCutoff.rawSnapshotInventory());
        assertEquals(null, nativeCutoff.pendingDeferredServiceBegin());
        assertEquals(1, nativeCutoff.armEpoch());
        assertTrue(nativeCutoff.armed());
        assertEquals(sha256(new byte[1024]), nativeCutoff.terminalZ80Digest());
    }

    @Test
    void bindsCutoffCoordinatesToTheFinalFrameWhenNativeTokensAreReused() throws Exception {
        Path raw = Files.writeString(temporary.resolve("s3k-token-reuse.jsonl"), tokenReusePrefix());

        var records = new S3kCompleteRunReferenceProjector()
                .projectPrefixForTesting(raw.toAbsolutePath(), rom()).records();
        CompleteRunAudioTrace.CutoffFrontier cutoff =
                (CompleteRunAudioTrace.CutoffFrontier) records.get(3);

        assertEquals(811, cutoff.nativeDiagnostics().activeStack().getFirst().beginFrame());
        assertEquals(811, cutoff.nativeDiagnostics().pendingDescendants().get(1).beginFrame());
        assertEquals(14, cutoff.nativeDiagnostics().rawChipInventory().getFirst().event().coordinate());
    }

    @Test
    void preservesDistinctPendingGenerationsWhenAFrameLocalTokenIsReused() throws Exception {
        Path raw = Files.writeString(temporary.resolve("s3k-pending-token-reuse.jsonl"),
                pendingTokenReusePrefix());

        var records = new S3kCompleteRunReferenceProjector()
                .projectPrefixForTesting(raw.toAbsolutePath(), rom()).records();
        CompleteRunAudioTrace.CutoffFrontier cutoff =
                (CompleteRunAudioTrace.CutoffFrontier) records.get(3);

        assertEquals(List.of(2L, 2L), cutoff.nativeDiagnostics().pendingDescendants().stream()
                .map(CompleteRunAudioTrace.FrontierService::token).toList());
        assertEquals(List.of(810, 811), cutoff.nativeDiagnostics().pendingDescendants().stream()
                .map(CompleteRunAudioTrace.FrontierService::beginFrame).toList());
        assertNull(cutoff.pendingDescendants());
    }

    @Test
    void rejectsCutoffBoundariesThatDifferFromTheirObservedEvents() throws Exception {
        String mismatchedBoundary = fullFrontierPrefix().replaceFirst(
                "\\\"begin_pc\\\":4300", "\\\"begin_pc\\\":4301");
        assertCorrelationFailure(mismatchedBoundary, "s3k-boundary-mismatch.jsonl",
                "S3K cutoff service differs from its observed boundary event");

        String mismatchedBeginCoordinate = fullFrontierPrefix().replaceFirst(
                "\\\"begin_coordinate\\\":1", "\\\"begin_coordinate\\\":0");
        assertCorrelationFailure(mismatchedBeginCoordinate, "s3k-begin-coordinate-mismatch.jsonl",
                "S3K cutoff service begin was not observed in the raw rows");

        String mismatchedEndCoordinate = fullFrontierPrefix().replaceFirst(
                "\\\"end_coordinate\\\":2", "\\\"end_coordinate\\\":1");
        assertCorrelationFailure(mismatchedEndCoordinate, "s3k-end-coordinate-mismatch.jsonl",
                "S3K cutoff service differs from its observed boundary event");

        String endedOpenService = fullFrontierPrefix().replace(
                psgEvent(12, 1, 7, 4300), psgEvent(12, 1, 7, 4300)
                        + "," + endEvent(13, 1, 0, 7, 4357, 12));
        assertCorrelationFailure(endedOpenService, "s3k-open-service-ended.jsonl",
                "S3K cutoff service lifecycle differs from observed events");
    }

    @Test
    void rejectsBoundaryAndMarkerEventsOutsideTheReviewedS3kManifest() throws Exception {
        String validBegin = beginEvent(0, 1, 0, 3, 0, 56, 2);
        String wrongPc = rawPrefix(false).replace(validBegin,
                beginEvent(0, 1, 0, 3, 0, 57, 2));
        assertCorrelationFailure(wrongPc, "s3k-known-hook-wrong-pc.jsonl",
                "S3K raw service hook semantics differ from reviewed service manifest");

        String wrongSource = rawPrefix(false).replace(validBegin,
                validBegin.replace("\"source_cpu\":1", "\"source_cpu\":2"));
        assertCorrelationFailure(wrongSource, "s3k-known-hook-wrong-source.jsonl",
                "S3K raw service hook semantics differ from reviewed service manifest");

        String wrongAction = rawPrefix(false).replace(validBegin,
                beginEvent(0, 1, 0, 3, 0, 132, 3));
        assertCorrelationFailure(wrongAction, "s3k-known-hook-wrong-action.jsonl",
                "S3K raw service hook semantics differ from reviewed service manifest");

        String wrongExpectedKind = beginEvent(0, 1, 0, 9, 0, 4256, 9) + ","
                + beginEvent(1, 2, 1, 3, 1, 56, 21);
        assertCorrelationFailure(withFrameEvents(rawPrefix(false), wrongExpectedKind),
                "s3k-known-hook-wrong-expected-kind.jsonl",
                "S3K raw service hook semantics differ from reviewed service manifest");

        String zeroExpectedNested = beginEvent(0, 1, 0, 9, 0, 4256, 9) + ","
                + beginEvent(1, 2, 1, 7, 1, 4300, 10);
        assertCorrelationFailure(withFrameEvents(rawPrefix(false), zeroExpectedNested),
                "s3k-zero-expected-kind-nested.jsonl",
                "S3K raw service hook semantics differ from reviewed service manifest");

        String resetBegin = rawEvent(0, 1, 0, 0, 0, 8, 3, 0, 0)
                .replace("\"source_cpu\":1", "\"source_cpu\":3");
        String resetEnd = rawEvent(1, 1, 0, 0, 0, 9, 3, 0, 0)
                .replace("\"source_cpu\":1", "\"source_cpu\":3");
        assertCorrelationFailure(withFrameEvents(rawPrefix(false), resetBegin + "," + resetEnd),
                "s3k-reset-known-nonreset-kind.jsonl",
                "S3K raw reset service kind differs from reviewed service manifest");

        String ordinaryResetEnd = rawEvent(2, 1, 0, 0, 3, 2, 3, 0, 0)
                .replace("\"source_cpu\":1", "\"source_cpu\":3");
        String resetProvenance = rawPrefix(false).replace(
                endEvent(2, 1, 0, 3, 132, 3), ordinaryResetEnd);
        assertCorrelationFailure(resetProvenance, "s3k-ordinary-reset-end.jsonl",
                "S3K raw service hook semantics differ from reviewed service manifest");

        String unknownMarker = rawEvent(1, 1, 0, 56, 255, 10, 3, 0, 3);
        String unknownMarkerEvents = validBegin + "," + unknownMarker + ","
                + endEvent(2, 1, 0, 3, 132, 3);
        assertCorrelationFailure(withFrameEvents(rawPrefix(false), unknownMarkerEvents),
                "s3k-unknown-marker-hook.jsonl",
                "S3K raw hook token is not declared by reviewed service manifest");

        String wrongExpectedKindMarker = rawEvent(1, 1, 0, 56, 21, 10, 3, 0, 3);
        String wrongExpectedKindEvents = validBegin + "," + wrongExpectedKindMarker + ","
                + endEvent(2, 1, 0, 3, 132, 3);
        assertCorrelationFailure(withFrameEvents(rawPrefix(false), wrongExpectedKindEvents),
                "s3k-marker-wrong-expected-kind.jsonl",
                "S3K raw marker hook semantics differ from reviewed service manifest");

        String forgedTransition = beginEvent(0, 1, 0, 7, 0, 4300, 10)
                + "," + transitionEvent(1, 1, 0, 7, 0, 4357, 12);
        assertCorrelationFailure(withFrameEvents(rawPrefix(false), forgedTransition),
                "s3k-forged-ancestry-hook.jsonl",
                "S3K raw ancestry hook semantics differ from reviewed service manifest");
    }

    @Test
    void acceptsTheReviewedTailPopPushBoundaryAsOneExactPair() throws Exception {
        String events = beginEvent(0, 1, 0, 6, 0, 0, 1) + ","
                + endEvent(1, 1, 0, 6, 56, 4) + ","
                + beginEvent(2, 2, 0, 3, 0, 56, 4) + ","
                + endEvent(3, 2, 0, 3, 132, 3);
        Path raw = Files.writeString(temporary.resolve("s3k-reviewed-tail-pair.jsonl"),
                withFrameEvents(rawPrefix(false), events));

        assertEquals(3, new S3kCompleteRunReferenceProjector()
                .projectPrefixForTesting(raw.toAbsolutePath(), rom()).records().size());
    }

    @Test
    void retainsTheManifestIndependentResetCancellationLifecycle() throws Exception {
        String serviceBegin = beginEvent(0, 1, 0, 3, 0, 56, 2);
        String resetBegin = rawEvent(1, 2, 0, 0, 1, 8, 1, 0, 0)
                .replace("\"source_cpu\":1", "\"source_cpu\":3");
        String cancellation = rawEvent(2, 1, 0, 0, 0, 2, 3, 0, 0)
                .replace("\"source_cpu\":1", "\"source_cpu\":3")
                .replace("\"flags\":0", "\"flags\":2");
        String resetEnd = rawEvent(3, 2, 0, 0, 0, 9, 1, 0, 0)
                .replace("\"source_cpu\":1", "\"source_cpu\":3");
        Path raw = Files.writeString(temporary.resolve("s3k-reset-cancellation.jsonl"),
                withFrameEvents(rawPrefix(false), String.join(",",
                        serviceBegin, resetBegin, cancellation, resetEnd)));

        assertEquals(3, new S3kCompleteRunReferenceProjector()
                .projectPrefixForTesting(raw.toAbsolutePath(), rom()).records().size());
    }

    @Test
    void rejectsMalformedRawYmAndPsgEventsBeforeCanonicalProjection() throws Exception {
        String invalidYm = fullFrontierPrefix().replace(
                psgEvent(12, 1, 7, 4300), rawEvent(12, 1, 0, 4300, 4, 3, 7, 0, 68));
        assertCorrelationFailure(invalidYm, "s3k-invalid-ym-subject.jsonl",
                "S3K raw YM event shape changed");

        String invalidPsg = fullFrontierPrefix().replace(
                psgEvent(12, 1, 7, 4300), rawEvent(12, 1, 0, 4300, 1, 4, 7, 0, 68));
        assertCorrelationFailure(invalidPsg, "s3k-invalid-psg-subject.jsonl",
                "S3K raw PSG event shape changed");

        String ordinaryResetChip = fullFrontierPrefix().replace(
                psgEvent(12, 1, 7, 4300),
                psgEvent(12, 1, 7, 4300).replace("\"pc\":4300", "\"pc\":0")
                        .replace("\"source_cpu\":1", "\"source_cpu\":3"));
        assertCorrelationFailure(ordinaryResetChip, "s3k-ordinary-reset-chip.jsonl",
                "S3K raw ordinary chip source/PC changed");

        String resetBegin = rawEvent(0, 1, 0, 0, 0, 8, 1, 0, 0)
                .replace("\"source_cpu\":1", "\"source_cpu\":3");
        String resetChip = rawEvent(1, 1, 0, 1, 0, 4, 1, 0, 68);
        String resetEnd = rawEvent(2, 1, 0, 0, 0, 9, 1, 0, 0)
                .replace("\"source_cpu\":1", "\"source_cpu\":3");
        assertCorrelationFailure(withFrameEvents(rawPrefix(false),
                        resetBegin + "," + resetChip + "," + resetEnd),
                "s3k-reset-nonreset-chip.jsonl", "S3K raw reset chip source/PC changed");

        String orphanedGeneration = rawPrefix(false)
                .replace("\"parent_token\":0", "\"parent_token\":2")
                .replace("\"depth\":0", "\"depth\":1");
        assertCorrelationFailure(orphanedGeneration, "s3k-orphaned-service-generation.jsonl",
                "S3K raw service begin is not nested under innermost service");

        String malformedMarker = rawPrefix(false).replace(
                endEvent(2, 1, 0, 3, 132, 3),
                rawEvent(2, 1, 0, 132, 9, 10, 3, 0, 255)
                        + "," + endEvent(3, 1, 0, 3, 132, 3));
        assertCorrelationFailure(malformedMarker, "s3k-malformed-marker.jsonl",
                "S3K raw marker event shape changed");

        String impossibleFirstToken = rawPrefix(false).replace(
                "\"service_token\":1", "\"service_token\":42");
        assertCorrelationFailure(impossibleFirstToken, "s3k-impossible-token-generation.jsonl",
                "S3K raw service token allocation changed");
    }

    @Test
    void rejectsEventGraphsThatCouldNotBeEmittedByTheNativeServiceStack() throws Exception {
        String nonTopParent = fullFrontierPrefix().replace(
                beginEvent(3, 3, 1, 3, 1, 56, 21),
                beginEvent(3, 3, 2, 3, 2, 56, 21));
        assertCorrelationFailure(nonTopParent, "s3k-non-top-parent.jsonl",
                "S3K raw service begin is not nested under innermost service");

        String nonTopEvent = fullFrontierPrefix().replace(
                endEvent(2, 2, 1, 3, 132, 3),
                rawEvent(2, 1, 0, 4300, 0, 4, 7, 0, 68));
        assertCorrelationFailure(nonTopEvent, "s3k-non-top-event.jsonl",
                "S3K raw event is not owned by innermost service");

        String snapshotProvenance = fullFrontierPrefix().replace(
                snapshotEvent(10, 1, 0, 4300, 2, 6, 7, 0, 1, "127"),
                snapshotEvent(10, 1, 0, 4301, 2, 6, 7, 0, 1, "127"));
        assertCorrelationFailure(snapshotProvenance, "s3k-snapshot-provenance.jsonl",
                "S3K raw snapshot source/PC continuity changed");
    }

    @Test
    void rejectsCutoffChipAndSnapshotPayloadsNotProvenByObservedEvents() throws Exception {
        String chipCoordinate = fullFrontierPrefix().replace(
                "\"coordinate\":12,\"native_ordinal\":12,\"event_kind\":4",
                "\"coordinate\":11,\"native_ordinal\":12,\"event_kind\":4");
        assertCorrelationFailure(chipCoordinate, "s3k-chip-coordinate-mismatch.jsonl",
                "S3K cutoff chip differs from its observed event");

        String chipValue = fullFrontierPrefix().replace(
                "\"event_kind\":4,\"subject\":0,\"value\":68",
                "\"event_kind\":4,\"subject\":0,\"value\":69");
        assertCorrelationFailure(chipValue, "s3k-chip-value-mismatch.jsonl",
                "S3K cutoff chip differs from its observed event");

        assertCorrelationFailure(fullFrontierPrefix(0, true, "7f"),
                "s3k-chip-owner-mismatch.jsonl",
                "S3K cutoff chip differs from its observed event");
        assertCorrelationFailure(fullFrontierPrefix(0, false, "7e"),
                "s3k-snapshot-mismatch.jsonl",
                "S3K cutoff snapshot differs from its observed event sequence");
    }

    @Test
    void fixedProducerRejectsEveryMismatchedTypedRequestBeforePublication() throws Exception {
        S3kCompleteRunReferenceProducer producer = new S3kCompleteRunReferenceProducer();
        // tools/tracechaser is an optional submodule that ordinary builds do
        // not initialise, so an uninitialised checkout made validate() reject
        // this request on the missing launcher long before the ordering this
        // test is about.
        CompleteRunAudioProducer.Request valid = withReferenceHome(
                validRequest(temporary.resolve("output").toAbsolutePath()),
                fakeTraceChaser("exit 23\n"));

        assertThrows(IllegalArgumentException.class, () -> producer.capture(new CompleteRunAudioProducer.Request(
                CompleteRunAudioTrace.ProducerKind.OPENGGF, valid.profileId(), valid.rom(), valid.bk2(),
                valid.runManifest(), valid.referenceHome(), valid.output())));
        assertThrows(IllegalArgumentException.class, () -> producer.capture(new CompleteRunAudioProducer.Request(
                valid.producerKind(), "wrong", valid.rom(), valid.bk2(), valid.runManifest(),
                valid.referenceHome(), valid.output())));
        assertThrows(IllegalArgumentException.class, () -> producer.capture(new CompleteRunAudioProducer.Request(
                valid.producerKind(), valid.profileId(), valid.runManifest(), valid.bk2(), valid.runManifest(),
                valid.referenceHome(), valid.output())));
        assertThrows(IllegalArgumentException.class, () -> producer.capture(new CompleteRunAudioProducer.Request(
                valid.producerKind(), valid.profileId(), valid.rom(), valid.runManifest(), valid.runManifest(),
                valid.referenceHome(), valid.output())));
        assertThrows(IllegalArgumentException.class, () -> producer.capture(new CompleteRunAudioProducer.Request(
                valid.producerKind(), valid.profileId(), valid.rom(), valid.bk2(), valid.bk2(),
                valid.referenceHome(), valid.output())));
        assertThrows(IllegalArgumentException.class, () -> producer.capture(new CompleteRunAudioProducer.Request(
                valid.producerKind(), valid.profileId(), valid.rom(), valid.bk2(), valid.runManifest(),
                valid.rom(), valid.output())));
        Files.createDirectory(valid.output());
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> producer.capture(valid));
        Files.delete(valid.output());
        assertThrows(IllegalStateException.class, () -> producer.capture(valid));
        assertFalse(Files.exists(valid.output()));
    }

    private static CompleteRunAudioProducer.Request withReferenceHome(
            CompleteRunAudioProducer.Request request, Path referenceHome) {
        return new CompleteRunAudioProducer.Request(request.producerKind(),
                request.profileId(), request.rom(), request.bk2(),
                request.runManifest(), referenceHome, request.output());
    }

    /** Minimal TraceChaser tree carrying only what validate() requires. */
    private Path fakeTraceChaser(String body) throws Exception {
        Path root = Files.createTempDirectory(temporary, "tracechaser-")
                .toAbsolutePath();
        Path tool = Files.createDirectories(root.resolve("bizhawk-headless"));
        Path fixtures = Files.createDirectories(tool.resolve("fixtures"));
        Path launcher = Files.writeString(
                tool.resolve("run-complete-audio.sh"), "#!/bin/sh\n" + body);
        launcher.toFile().setExecutable(true, true);
        Files.writeString(
                fixtures.resolve("gpgx-audio-service-manifests-v1.json"),
                "manifest");
        return root;
    }

    private CompleteRunAudioProducer.Request validRequest(Path output) {
        Path root = Path.of("").toAbsolutePath();
        return new CompleteRunAudioProducer.Request(CompleteRunAudioTrace.ProducerKind.REFERENCE,
                S3kCompleteRunAudioProfile.ID, rom(),
                root.resolve("src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds/"
                        + "s3k-knuckles-complete-superemeralds.bk2"),
                root.resolve("src/test/resources/traces/s3k/runs/s3k-knuckles-complete-superemeralds/run_manifest.json"),
                root.resolve("tools/tracechaser"), output);
    }

    private static Path rom() {
        File value = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(value != null, "Sonic 3&K locked-on ROM not available — skipping test");
        try { return value.toPath().toRealPath(); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static String rawPrefix(boolean trailing) {
        String state = "00".repeat(1024);
        String metadata = "{\"type\":\"metadata\",\"schema\":\"openggf.s3k-complete-run-audio-raw.v1\","
                + "\"rom_sha1\":\"cfbf98c36c776677290a872547ac47c53d2761d6\","
                + "\"bk2_sha256\":\"aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc\","
                + "\"service_manifest_sha256\":\"ef8f8103c38d70e41cb09cb29751f56815a0401709dc509071aa514d614813a0\","
                + "\"first_row\":810,\"exclusive_end\":434417,\"state_start\":7168,\"state_exclusive_end\":8192}\n";
        String baseline = boundary("baseline", "\"row\":810,", state, "[]");
        String frame = "{\"type\":\"frame\",\"row\":810,\"lag\":true,\"state_hex\":\""
                + state + "\",\"events\":[" + beginEvent(0, 1, 0, 3, 0, 56, 2)
                + "," + psgEvent(1, 1, 3, 56)
                + "," + endEvent(2, 1, 0, 3, 132, 3) + "]}\n";
        String cutoff = boundary("cutoff", "\"exclusive_end\":811,", state, "[]");
        return metadata + baseline + frame + cutoff + (trailing ? "{}\n" : "");
    }

    private static String twoFramePrefix() {
        String first = rawPrefix(false);
        String state = "00".repeat(1024);
        String cutoff = boundary("cutoff", "\"exclusive_end\":811,", state, "[]");
        String second = "{\"type\":\"frame\",\"row\":811,\"lag\":true,\"state_hex\":\""
                + state + "\",\"events\":[" + beginEvent(0, 1, 0, 3, 0, 56, 2)
                + "," + psgEvent(1, 1, 3, 56)
                + "," + endEvent(2, 1, 0, 3, 132, 3) + "]}\n";
        return first.replace(cutoff, second
                + boundary("cutoff", "\"exclusive_end\":812,", state, "[]"));
    }

    private static List<CompleteRunAudioTrace.Record> records(Path output) throws Exception {
        List<CompleteRunAudioTrace.Record> records = new java.util.ArrayList<>();
        try (var reader = new CompleteRunAudioCaptureStore().read(output)) {
            while (reader.hasNext()) records.add(reader.next());
        }
        return records;
    }

    private static String fullFrontierPrefix() {
        return fullFrontierPrefix(0);
    }

    private static String fullFrontierPrefix(int coordinateOffset) {
        return fullFrontierPrefix(coordinateOffset, false, "7f");
    }

    private static String fullFrontierPrefix(int coordinateOffset, boolean chipOwnedBySecond,
            String snapshotHex) {
        String state = "00".repeat(1024);
        String metadata = "{\"type\":\"metadata\",\"schema\":\"openggf.s3k-complete-run-audio-raw.v1\","
                + "\"rom_sha1\":\"cfbf98c36c776677290a872547ac47c53d2761d6\","
                + "\"bk2_sha256\":\"aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc\","
                + "\"service_manifest_sha256\":\"ef8f8103c38d70e41cb09cb29751f56815a0401709dc509071aa514d614813a0\","
                + "\"first_row\":810,\"exclusive_end\":434417,\"state_start\":7168,\"state_exclusive_end\":8192}\n";
        String baseline = boundary("baseline", "\"row\":810,", state, "[]");
        String events = beginEvent(0, 1, 0, 7, 0, 4300, 10)
                + "," + beginEvent(1, 2, 1, 3, 1, 56, 21)
                + "," + endEvent(2, 2, 1, 3, 132, 3)
                + "," + beginEvent(3, 3, 1, 3, 1, 56, 21)
                + "," + endEvent(4, 3, 1, 3, 132, 3)
                + "," + beginEvent(5, 4, 1, 3, 1, 56, 21)
                + "," + endEvent(6, 4, 1, 3, 132, 3)
                + "," + beginEvent(7, 5, 1, 3, 1, 56, 21)
                + "," + endEvent(8, 5, 1, 3, 132, 3)
                + "," + snapshotEvent(9, 1, 0, 4300, 2, 5, 7, 0, 0, "0")
                + "," + snapshotEvent(10, 1, 0, 4300, 2, 6, 7, 0, 1, "127")
                + "," + snapshotEvent(11, 1, 0, 4300, 2, 7, 7, 1, 0, "0")
                + "," + psgEvent(12, 1, 7, 4300);
        String frame = "{\"type\":\"frame\",\"row\":810,\"lag\":false,\"state_hex\":\""
                + state + "\",\"events\":[" + events + "]}\n";
        String chip = "{\"coordinate\":" + (12 + coordinateOffset)
                + ",\"native_ordinal\":12,\"event_kind\":4,\"subject\":0,"
                + "\"value\":68,\"pc\":4300,\"source_cpu\":1,\"data\":true,"
                + "\"port\":0,\"register\":0}";
        String active = frontierService(1, 0, 7, 0, coordinateOffset, 0, 4300, 10,
                false, 0, 0, 0, 0,
                chipOwnedBySecond ? "[]" : "[" + chip + "]",
                "[{\"range_id\":2,\"source_cpu\":1,\"pc\":4300,\"bytes_hex\":\""
                        + snapshotHex + "\"}]", "[]");
        List<String> pending = List.of(
                frontierService(2, 1, 3, 1, 1 + coordinateOffset, 2 + coordinateOffset, 56, 21,
                        true, 132, 3, 1, 1, chipOwnedBySecond ? "[" + chip + "]" : "[]",
                        "[]", "[]"),
                frontierService(3, 1, 3, 1, 3 + coordinateOffset, 4 + coordinateOffset, 56, 21,
                        true, 132, 3, 1, 1, "[]", "[]", "[]"),
                frontierService(4, 1, 3, 1, 5 + coordinateOffset, 6 + coordinateOffset, 56, 21,
                        true, 132, 3, 1, 1, "[]", "[]", "[]"),
                frontierService(5, 1, 3, 1, 7 + coordinateOffset, 8 + coordinateOffset, 56, 21,
                        true, 132, 3, 1, 1, "[]", "[]", "[]"));
        String cutoff = boundaryWithPending(state, active, String.join(",", pending));
        return metadata + baseline + frame + cutoff;
    }

    private static String tokenReusePrefix() {
        String original = fullFrontierPrefix(2);
        int frameStart = original.indexOf("{\"type\":\"frame\"");
        String prefix = original.substring(0, frameStart);
        String reused = "{\"type\":\"frame\",\"row\":810,\"lag\":false,\"state_hex\":\""
                + "00".repeat(1024) + "\",\"events\":["
                + beginEvent(0, 1, 0, 7, 0, 4300, 10) + ","
                + endEvent(1, 1, 0, 7, 4357, 12) + "]}\n";
        String finalFrame = original.substring(frameStart)
                .replaceFirst("\\\"row\\\":810", "\\\"row\\\":811")
                .replaceFirst("\\\"exclusive_end\\\":811", "\\\"exclusive_end\\\":812");
        return prefix + reused + finalFrame;
    }

    private static String pendingTokenReusePrefix() {
        String base = rawPrefix(false);
        int frameStart = base.indexOf("{\"type\":\"frame\"");
        String prefix = base.substring(0, frameStart);
        String state = "00".repeat(1024);
        String first = "{\"type\":\"frame\",\"row\":810,\"lag\":false,\"state_hex\":\""
                + state + "\",\"events\":[" + beginEvent(0, 1, 0, 7, 0, 4300, 10) + ","
                + beginEvent(1, 2, 1, 3, 1, 56, 21) + ","
                + endEvent(2, 2, 1, 3, 132, 3) + "]}\n";
        String second = "{\"type\":\"frame\",\"row\":811,\"lag\":false,\"state_hex\":\""
                + state + "\",\"events\":[" + beginEvent(0, 2, 1, 3, 1, 56, 21) + ","
                + endEvent(1, 2, 1, 3, 132, 3) + "]}\n";
        String active = frontierService(1, 0, 7, 0, 0, 0, 4300, 10,
                false, 0, 0, 0, 0, "[]", "[]", "[]");
        String firstPending = frontierService(2, 1, 3, 1, 1, 2, 56, 21,
                true, 132, 3, 1, 1, "[]", "[]", "[]");
        String secondPending = frontierService(2, 1, 3, 1, 3, 4, 56, 21,
                true, 132, 3, 1, 1, "[]", "[]", "[]");
        String cutoff = boundaryWithPending(state, active, firstPending + "," + secondPending)
                .replace("\"exclusive_end\":811", "\"exclusive_end\":812");
        return prefix + first + second + cutoff;
    }

    private static String withFrameEvents(String prefix, String events) {
        int marker = prefix.indexOf("\"events\":[");
        int start = marker + "\"events\":[".length();
        int end = prefix.indexOf("]}\n", start);
        return prefix.substring(0, start) + events + prefix.substring(end);
    }

    private static String beginEvent(int ordinal, int token, int parent, int serviceKind,
            int depth, int pc, int hook) {
        return rawEvent(ordinal, token, parent, pc, hook, 1, serviceKind, depth, 0);
    }

    private static String endEvent(int ordinal, int token, int parent, int serviceKind, int pc, int hook) {
        return rawEvent(ordinal, token, parent, pc, hook, 2, serviceKind, parent == 0 ? 0 : 1, 0);
    }

    private static String transitionEvent(int ordinal, int token, int parent, int serviceKind,
            int depth, int pc, int hook) {
        return rawEvent(ordinal, token, parent, pc, hook, 11, serviceKind, depth, 0);
    }

    private static String psgEvent(int ordinal, int token, int serviceKind, int pc) {
        return rawEvent(ordinal, token, 0, pc, 0, 4, serviceKind, 0, 68);
    }

    private static String rawEvent(int ordinal, int token, int parent, int pc, int subject,
            int kind, int serviceKind, int depth, int value) {
        return "{\"ordinal\":" + ordinal + ",\"service_token\":" + token
                + ",\"parent_token\":" + parent + ",\"pc\":" + pc + ",\"subject\":" + subject
                + ",\"offset\":0,\"kind\":" + kind + ",\"service_kind\":" + serviceKind
                + ",\"depth\":" + depth + ",\"source_cpu\":1,\"payload_length\":0,"
                + "\"value\":" + value + ",\"flags\":0,\"reserved\":0,\"payload\":\"0\"}";
    }

    private static String snapshotEvent(int ordinal, int token, int parent, int pc, int subject,
            int kind, int serviceKind, int offset, int payloadLength, String payload) {
        return "{\"ordinal\":" + ordinal + ",\"service_token\":" + token
                + ",\"parent_token\":" + parent + ",\"pc\":" + pc + ",\"subject\":" + subject
                + ",\"offset\":" + offset + ",\"kind\":" + kind + ",\"service_kind\":"
                + serviceKind + ",\"depth\":0,\"source_cpu\":1,\"payload_length\":"
                + payloadLength + ",\"value\":0,\"flags\":0,\"reserved\":0,\"payload\":\""
                + payload + "\"}";
    }

    private static String frontierService(int token, int parent, int kind, int depth,
            int beginCoordinate, int endCoordinate, int beginPc, int beginHook, boolean complete,
            int endPc, int endHook, int currentParent, int currentDepth, String chips,
            String snapshots, String ancestry) {
        return "{\"token\":" + token + ",\"parent_token\":" + parent + ",\"kind\":" + kind
                + ",\"depth\":" + depth + ",\"current_parent_token\":" + currentParent
                + ",\"current_depth\":" + currentDepth + ",\"begin_coordinate\":" + beginCoordinate
                + ",\"end_coordinate\":" + endCoordinate + ",\"begin_pc\":" + beginPc
                + ",\"end_pc\":" + endPc + ",\"begin_hook_token\":" + beginHook
                + ",\"end_hook_token\":" + endHook + ",\"begin_source_cpu\":1,"
                + "\"cancelled\":false,\"complete\":" + complete + ",\"chips\":" + chips
                + ",\"snapshots\":" + snapshots + ",\"ancestry_transitions\":" + ancestry + "}";
    }

    private static CompleteRunAudioTrace.FrontierService completedService(int token, int parent,
            int depth, int beginOrdinal, int endOrdinal, int currentParent, int currentDepth,
            int endPc, int endHook, List<CompleteRunAudioTrace.NativeAncestryTransition> transitions) {
        return new CompleteRunAudioTrace.FrontierService(token, parent, depth, "VInt",
                CompleteRunAudioTrace.FrontierServiceState.COMPLETED, 810, beginOrdinal, 56, 21,
                "Z80", 810, (long) endOrdinal, endPc, endHook, List.of(), List.of(), currentParent,
                currentDepth, transitions);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void assertCorrelationFailure(String contents, String filename, String message) throws Exception {
        Path raw = Files.writeString(temporary.resolve(filename), contents);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new S3kCompleteRunReferenceProjector()
                        .projectPrefixForTesting(raw.toAbsolutePath(), rom()));
        assertEquals(message, failure.getMessage());
        assertNull(failure.getCause());
    }

    private static String boundaryWithPending(String state, String active, String pending) {
        return "{\"type\":\"cutoff\",\"exclusive_end\":811,\"state_hex\":\"" + state
                + "\",\"ym_port0_latch\":40,\"ym_port1_latch\":161,\"native_arm_epoch\":1,"
                + "\"native_armed\":true,\"active_services\":[" + active
                + "],\"pending_descendants\":[" + pending + "]}\n";
    }

    private static String boundary(String type, String coordinate, String state, String active) {
        return "{\"type\":\"" + type + "\"," + coordinate + "\"state_hex\":\"" + state
                + "\",\"ym_port0_latch\":40,\"ym_port1_latch\":161,"
                + "\"native_arm_epoch\":1,\"native_armed\":true,\"active_services\":"
                + active + ",\"pending_descendants\":[]}\n";
    }

}
