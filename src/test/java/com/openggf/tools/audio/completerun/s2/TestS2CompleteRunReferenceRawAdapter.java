package com.openggf.tools.audio.completerun.s2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestS2CompleteRunReferenceRawAdapter {
    @TempDir Path temporary;

    @Test
    void rejectsRawV1BeforeOpeningTheSinkTransaction() throws Exception {
        Path raw = temporary.resolve("v1.jsonl");
        String state = "00".repeat(8192);
        Files.writeString(raw, metadata().replace("raw.v2", "raw.v1")
                + boundary("baseline", "\"row\":769,", state)
                + boundary("cutoff", "\"exclusive_end\":769,", state));
        var sink = new RecordingSink();

        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, sink));
        assertEquals(0, sink.beginCalls);
    }

    @Test
    void preservesAndValidatesCarriedServiceGenerationOrigins() throws Exception {
        Path accepted = temporary.resolve("origin.jsonl");
        String state = "00".repeat(8192);
        String baseline = boundary("baseline", "\"row\":769,", state);
        String cutoff = boundary("cutoff", "\"exclusive_end\":769,", state)
                .replace("\"active_services\":[]",
                        "\"active_services\":[" + dpcmService() + "]");
        Files.writeString(accepted, metadata() + baseline + cutoff);
        var sink = new RecordingSink();

        S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(accepted, sink);

        var carried = sink.baseline.activeServices().getFirst();
        assertEquals(700, carried.beginRow());
        assertEquals(12, carried.beginNativeOrdinal());

        for (String changed : List.of(
                baseline.replace("\"begin_row\":700", "\"begin_row\":769"),
                baseline.replace("\"begin_native_ordinal\":12",
                        "\"begin_native_ordinal\":65536"),
                cutoff.replace("\"begin_row\":700", "\"begin_row\":701"))) {
            Path invalid = temporary.resolve("bad-origin-" + Math.abs(changed.hashCode()) + ".jsonl");
            Files.writeString(invalid, metadata()
                    + (changed.startsWith("{\"type\":\"baseline\"") ? changed : baseline)
                    + (changed.startsWith("{\"type\":\"cutoff\"") ? changed : cutoff));
            assertThrows(IllegalArgumentException.class,
                    () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(
                            invalid, new RecordingSink()));
        }
    }

    @Test
    void rejectsDuplicateReorderedAndMutatedCutoffGenerationEvidence() throws Exception {
        String state = "00".repeat(8192);
        String firstChild = completedVintService(769, 0, 9);
        String secondChild = completedMusicService();
        String frames = "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\""
                + state + "\",\"events\":[" + vintBegin(0) + ","
                + musicBegin(1) + ","
                + snapshotTransaction(2, 3, 2, 9, 2, 1, 331, 2) + ","
                + musicEnd(5) + ","
                + snapshotTransaction(6, 2, 1, 3, 1, 1, 231, 2) + ","
                + vintEnd(9) + "]}\n";
        String cutoff = boundary("cutoff", "\"exclusive_end\":770,", state)
                .replace("\"active_services\":[]",
                        "\"active_services\":[" + dpcmService() + "]");
        String prefix = metadata() + boundary("baseline", "\"row\":769,", state) + frames;
        Path accepted = temporary.resolve("ordered-cutoff-inventory.jsonl");
        Files.writeString(accepted, prefix + cutoff.replace("\"pending_descendants\":[]",
                "\"pending_descendants\":[" + firstChild + "," + secondChild + "]"));
        S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(accepted, new RecordingSink());

        for (String pending : List.of(
                firstChild + "," + firstChild,
                secondChild + "," + firstChild)) {
            Path invalid = temporary.resolve("bad-cutoff-inventory-" + Math.abs(pending.hashCode()) + ".jsonl");
            Files.writeString(invalid, prefix + cutoff.replace("\"pending_descendants\":[]",
                            "\"pending_descendants\":[" + pending + "]"));
            assertThrows(IllegalArgumentException.class,
                    () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(
                            invalid, new RecordingSink()));
        }

        String mutatedBeginHook = boundary("cutoff", "\"exclusive_end\":769,", state)
                .replace("\"active_services\":[]",
                        "\"active_services\":[" + dpcmService()
                                .replace("\"begin_hook_token\":5",
                                        "\"begin_hook_token\":13") + "]");
        Path mutated = temporary.resolve("bad-cutoff-begin-hook.jsonl");
        Files.writeString(mutated, metadata() + boundary("baseline", "\"row\":769,", state)
                + mutatedBeginHook);
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(
                        mutated, new RecordingSink()));

        String forgedChip = cutoff.replace("\"chips\":[]",
                "\"chips\":[{\"coordinate\":0,\"native_ordinal\":0,"
                        + "\"event_kind\":4,\"subject\":0,\"value\":68,"
                        + "\"pc\":378,\"source_cpu\":1,\"data\":true,"
                        + "\"port\":0,\"register\":0}]")
                .replace("\"pending_descendants\":[]",
                        "\"pending_descendants\":[" + firstChild + "," + secondChild + "]");
        Path forgedChipPath = temporary.resolve("bad-cutoff-chip.jsonl");
        Files.writeString(forgedChipPath, prefix + forgedChip);
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(
                        forgedChipPath, new RecordingSink()));

        for (String forged : List.of(
                firstChild.replace("\"end_pc\":231", "\"end_pc\":271")
                        .replace("\"end_hook_token\":3", "\"end_hook_token\":4")
                        .replace("\"pc\":231", "\"pc\":271"),
                firstChild.replace("\"bytes_hex\":\"ff\"", "\"bytes_hex\":\"ee\""))) {
            Path invalid = temporary.resolve("bad-cutoff-evidence-" + Math.abs(forged.hashCode()) + ".jsonl");
            Files.writeString(invalid, prefix + cutoff.replace("\"pending_descendants\":[]",
                    "\"pending_descendants\":[" + forged + "," + secondChild + "]"));
            assertThrows(IllegalArgumentException.class,
                    () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(
                            invalid, new RecordingSink()));
        }
    }

    @Test
    void streamsPinnedRawRowsWithoutBufferingOrLosingUnsignedPayload() throws Exception {
        Path raw = temporary.resolve("raw.jsonl");
        String state = "00".repeat(8192);
        Files.writeString(raw, metadata()
                + boundary("baseline", "\"row\":769,", state)
                + "{\"type\":\"frame\",\"row\":769,\"lag\":true,\"state_hex\":\"" + state
                + "\",\"events\":[" + snapshotEvents() + "]}\n"
                + boundary("cutoff", "\"exclusive_end\":770,", state));
        var sink = new RecordingSink();

        S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, sink);

        assertEquals(769, sink.header.firstRow());
        assertEquals(1, sink.frames.size());
        assertEquals(true, sink.frames.getFirst().lag());
        assertArrayEquals(new byte[8192], sink.frames.getFirst().driverState());
        assertEquals(new BigInteger("255"),
                sink.frames.getFirst().events().get(1).payload());
        assertEquals(770, sink.cutoff.exclusiveEnd());
    }

    @Test
    void rejectsAnyRowGapBeforeCallingTheCutoffSink() throws Exception {
        Path raw = temporary.resolve("gap.jsonl");
        String state = "00".repeat(8192);
        Files.writeString(raw, metadata()
                + boundary("baseline", "\"row\":769,", state)
                + "{\"type\":\"frame\",\"row\":770,\"lag\":false,\"state_hex\":\"" + state
                + "\",\"events\":[]}\n"
                + boundary("cutoff", "\"exclusive_end\":812,", state));
        var sink = new RecordingSink();

        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, sink));
        assertEquals(null, sink.cutoff);
    }

    @Test
    void rejectsDuplicateJsonFields() throws Exception {
        Path raw = temporary.resolve("duplicate.jsonl");
        String state = "00".repeat(8192);
        Files.writeString(raw, metadata().replaceFirst(
                "\\{\"type\":\"metadata\",", "{\"type\":\"metadata\",\"type\":\"metadata\",")
                + boundary("baseline", "\"row\":769,", state)
                + boundary("cutoff", "\"exclusive_end\":769,", state));

        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
    }

    @Test
    void rejectsMalformedPinnedBoundaryBeforeStartingTheTransaction() throws Exception {
        Path raw = temporary.resolve("bad-boundary.jsonl");
        String state = "00".repeat(8192);
        Files.writeString(raw, metadata()
                + boundary("baseline", "\"row\":769,", state)
                    .replace("\"ym_port0_latch\":42", "\"ym_port0_latch\":42.5")
                + boundary("cutoff", "\"exclusive_end\":769,", state));
        var sink = new RecordingSink();

        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, sink));
        assertEquals(0, sink.beginCalls);
        assertEquals(0, sink.abortCalls);
    }

    @Test
    void rejectsNonCanonicalPayloadAndAbiInvalidEventFields() throws Exception {
        String state = "00".repeat(8192);
        for (String changed : List.of(
                event().replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"+1\""),
                event().replace("\"payload_length\":8", "\"payload_length\":9"),
                event().replace("\"reserved\":0", "\"reserved\":1"),
                event().replace("\"source_cpu\":1", "\"source_cpu\":4"),
                event().replace("\"pc\":4660", "\"pc\":18446744073709551616"),
                event().replace("\"kind\":6", "\"kind\":12"))) {
            Path raw = temporary.resolve("bad-event-" + Math.abs(changed.hashCode()) + ".jsonl");
            Files.writeString(raw, metadata()
                    + boundary("baseline", "\"row\":769,", state)
                    + "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\"" + state
                    + "\",\"events\":[{" + changed + "]}\n"
                    + boundary("cutoff", "\"exclusive_end\":770,", state));
            assertThrows(IllegalArgumentException.class,
                    () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
        }
    }

    @Test
    void rejectsPerKindAbi3EventShapeViolations() throws Exception {
        String state = "00".repeat(8192);
        for (String changed : List.of(
                event().replace("\"service_token\":2", "\"service_token\":0"),
                event().replace("\"parent_token\":1", "\"parent_token\":0"),
                event().replace("\"kind\":6", "\"kind\":3")
                        .replace("\"payload_length\":8", "\"payload_length\":0")
                        .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"0\"")
                        .replace("\"subject\":1", "\"subject\":4"),
                event().replace("\"kind\":6", "\"kind\":8")
                        .replace("\"service_kind\":2", "\"service_kind\":1")
                        .replace("\"payload_length\":8", "\"payload_length\":0")
                        .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"0\"")
                        .replace("\"source_cpu\":1", "\"source_cpu\":2"),
                event().replace("\"kind\":6", "\"kind\":10")
                        .replace("\"payload_length\":8", "\"payload_length\":0")
                        .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"0\"")
                        .replace("\"value\":0", "\"value\":4"))) {
            Path raw = temporary.resolve("bad-kind-shape-" + Math.abs(changed.hashCode()) + ".jsonl");
            Files.writeString(raw, metadata() + boundary("baseline", "\"row\":769,", state)
                    + "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\"" + state
                    + "\",\"events\":[{" + changed + "]}\n"
                    + boundary("cutoff", "\"exclusive_end\":770,", state));
            assertThrows(IllegalArgumentException.class,
                    () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
        }
    }

    @Test
    void rejectsLoneSnapshotChunkWithoutTransactionalEnvelope() throws Exception {
        String state = "00".repeat(8192);
        Path raw = temporary.resolve("lone-snapshot-chunk.jsonl");
        Files.writeString(raw, metadata() + boundary("baseline", "\"row\":769,", state)
                + "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\"" + state
                + "\",\"events\":[{" + event() + "}]}\n"
                + boundary("cutoff", "\"exclusive_end\":770,", state));

        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
    }

    @Test
    void rejectsSnapshotOwnerRangeOffsetAndTailChangesMidStream() throws Exception {
        String state = "00".repeat(8192);
        for (String changed : List.of(
                snapshotChunk().replace("\"subject\":2", "\"subject\":1"),
                snapshotChunk().replace("\"source_cpu\":1", "\"source_cpu\":2"),
                snapshotChunk().replace("\"pc\":432", "\"pc\":433"),
                snapshotChunk().replace("\"offset\":0", "\"offset\":1"),
                snapshotChunk().replace("\"payload\":\"255\"", "\"payload\":\"256\""))) {
            String events = "{" + snapshotBegin() + "},{" + changed + "},{" + snapshotEnd() + "}";
            Path raw = temporary.resolve("bad-snapshot-stream-" + Math.abs(changed.hashCode()) + ".jsonl");
            Files.writeString(raw, metadata() + boundary("baseline", "\"row\":769,", state)
                    + "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\"" + state
                    + "\",\"events\":[" + events + "]}\n"
                    + boundary("cutoff", "\"exclusive_end\":770,", state));
            assertThrows(IllegalArgumentException.class,
                    () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
        }
    }

    @Test
    void rejectsUnpairedResetAndPowerOrActiveCountMismatch() throws Exception {
        String state = "00".repeat(8192);
        for (String events : List.of(
                resetBegin(0, 0),
                resetEnd(0, 0),
                resetBegin(1, 1) + ","
                        + snapshotTransaction(1, 1, 0, 4, 0, 3, 0, 2) + ","
                        + resetCancellation(4) + "," + resetEnd(5, 1),
                resetBegin(1, 1) + ","
                        + snapshotTransaction(1, 1, 0, 4, 0, 3, 0, 2) + ","
                        + resetCancellation(4) + ","
                        + snapshotTransaction(5, 9, 0, 1, 0, 3, 0, 2) + ","
                        + resetEnd(8, 0),
                resetBegin(1, 0) + ","
                        + snapshotTransaction(1, 1, 0, 4, 0, 1, 378, 2) + ","
                        + normalDpcmCompletion(4) + "," + resetEnd(5, 0))) {
            Path raw = temporary.resolve("bad-reset-" + Math.abs(events.hashCode()) + ".jsonl");
            Files.writeString(raw, metadata() + boundary("baseline", "\"row\":769,", state)
                    + "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\"" + state
                    + "\",\"events\":[" + events + "]}\n"
                    + boundary("cutoff", "\"exclusive_end\":770,", state));
            assertThrows(IllegalArgumentException.class,
                    () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
        }
    }

    @Test
    void acceptsResetWithExactServiceAndResetManifestSnapshots() throws Exception {
        String state = "00".repeat(8192);
        String events = resetBegin(1, 1) + ","
                + snapshotTransaction(1, 1, 0, 4, 0, 3, 0, 2) + ","
                + resetCancellation(4) + ","
                + snapshotTransaction(5, 9, 0, 1, 0, 3, 0, 2) + ","
                + resetEnd(8, 1);
        Path raw = temporary.resolve("valid-reset.jsonl");
        Files.writeString(raw, metadata() + boundary("baseline", "\"row\":769,", state)
                + "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\"" + state
                + "\",\"events\":[" + events + "]}\n"
                + boundary("cutoff", "\"exclusive_end\":770,", state));

        S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink());
    }

    @Test
    void rejectsNormalCompletionWithoutItsManifestSnapshotRange() throws Exception {
        assertFrameRejected("missing-completion-range", normalDpcmCompletion(0));
    }

    @Test
    void rejectsSnapshotRangeOutsideTheOwningServiceManifestSlice() throws Exception {
        assertFrameRejected("wrong-owner-range",
                snapshotTransaction(0, 1, 0, 4, 0, 1, 432, 1));
    }

    @Test
    void rejectsBeginHookWhoseExpectedTopKindDoesNotMatchItsParent() throws Exception {
        String nestedRootOnlyVint = "{" + event()
                .replace("\"pc\":4660", "\"pc\":56")
                .replace("\"kind\":6", "\"kind\":1")
                .replace("\"service_kind\":2", "\"service_kind\":3")
                .replace("\"payload_length\":8", "\"payload_length\":0")
                .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"0\"") + "}";
        assertFrameRejected("wrong-begin-parent", nestedRootOnlyVint);
    }

    @Test
    void rejectsMalformedFrontierServiceAndAbortsAfterLateFailure() throws Exception {
        String state = "00".repeat(8192);
        String invalidService = boundary("cutoff", "\"exclusive_end\":770,", state)
                .replace("\"active_services\":[]", "\"active_services\":[{}]");
        Path raw = temporary.resolve("late-frontier.jsonl");
        Files.writeString(raw, metadata()
                + boundary("baseline", "\"row\":769,", state)
                + "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\"" + state
                + "\",\"events\":[]}\n" + invalidService);
        var sink = new RecordingSink();

        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, sink));
        assertEquals(1, sink.beginCalls);
        assertEquals(1, sink.abortCalls);
        assertEquals(0, sink.commitCalls);
        assertEquals(0, sink.frames.size());
        assertEquals(null, sink.cutoff);
    }

    @Test
    void commitsOnlyAfterTheValidatedCutoffAndTrailingEof() throws Exception {
        Path raw = temporary.resolve("transaction.jsonl");
        String state = "00".repeat(8192);
        Files.writeString(raw, metadata()
                + boundary("baseline", "\"row\":769,", state)
                + boundary("cutoff", "\"exclusive_end\":769,", state)
                    .replace("\"active_services\":[]",
                            "\"active_services\":[" + dpcmService() + "]"));
        var sink = new RecordingSink();

        S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, sink);

        assertEquals(1, sink.beginCalls);
        assertEquals(1, sink.commitCalls);
        assertEquals(0, sink.abortCalls);
        assertTrue(sink.cutoff != null);
    }

    @Test
    void parsesEveryStrictFrontierServiceFieldIntoTypedLosslessValues() throws Exception {
        Path raw = temporary.resolve("frontier.jsonl");
        String state = "00".repeat(8192);
        Files.writeString(raw, metadata()
                + boundary("baseline", "\"row\":769,", state)
                + boundary("cutoff", "\"exclusive_end\":769,", state)
                    .replace("\"active_services\":[]",
                            "\"active_services\":[" + dpcmService() + "]"));
        var sink = new RecordingSink();

        S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, sink);

        var service = sink.cutoff.activeServices().getFirst();
        assertEquals(1, service.token());
        assertEquals(4, service.kind());
        assertEquals(378, service.beginPc());
        assertEquals(0, service.chips().size());
        assertEquals(0, service.snapshots().size());
        assertEquals(0, service.ancestryTransitions().size());
    }

    @Test
    void parsesTypedChipAndSnapshotEvidenceLosslessly() throws Exception {
        Path raw = temporary.resolve("typed-frontier.jsonl");
        String state = "00".repeat(8192);
        String typed = dpcmService()
                .replace("\"chips\":[]", "\"chips\":[{\"coordinate\":7,"
                        + "\"native_ordinal\":8,\"event_kind\":3,\"subject\":1,"
                        + "\"value\":42,\"pc\":56,\"source_cpu\":1,\"data\":true,"
                        + "\"port\":0,\"register\":42}]")
                .replace("\"snapshots\":[]", "\"snapshots\":[{\"range_id\":2,"
                        + "\"source_cpu\":1,\"pc\":56,\"bytes_hex\":\"ab\"}]");
        Files.writeString(raw, metadata()
                + boundary("baseline", "\"row\":769,", state)
                    .replace(dpcmService(), typed)
                + boundary("cutoff", "\"exclusive_end\":769,", state)
                    .replace("\"active_services\":[]", "\"active_services\":[" + typed + "]"));
        var sink = new RecordingSink();

        S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, sink);

        var parsed = sink.cutoff.activeServices().getFirst();
        assertEquals(42, parsed.chips().getFirst().register());
        assertArrayEquals(new byte[] {(byte) 0xab}, parsed.snapshots().getFirst().bytes());
        assertEquals(0, parsed.ancestryTransitions().size());
    }

    @Test
    void rejectsCompletedResetAsCutoffPendingEvenWithExactAbsentBeginSourceShape() throws Exception {
        Path raw = temporary.resolve("reset-frontier.jsonl");
        String state = "00".repeat(8192);
        Files.writeString(raw, metadata()
                + boundary("baseline", "\"row\":769,", state)
                + "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\""
                + state + "\",\"events\":[" + resetBegin(1, 0) + ","
                + snapshotTransaction(1, 1, 0, 4, 0, 3, 0, 2) + ","
                + resetCancellation(4) + ","
                + snapshotTransaction(5, 9, 0, 1, 0, 3, 0, 2) + ","
                + resetEnd(8, 0) + "]}\n"
                + boundary("cutoff", "\"exclusive_end\":770,", state)
                    .replace("\"pending_descendants\":[]",
                            "\"pending_descendants\":[" + resetService()
                                    .replace("\"begin_coordinate\":1", "\"begin_coordinate\":0")
                                    .replace("\"begin_row\":700", "\"begin_row\":769")
                                    .replace("\"begin_native_ordinal\":12",
                                            "\"begin_native_ordinal\":0") + "]"));
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(
                        raw, new RecordingSink()));
    }

    @Test
    void rejectsKindZeroFrontierServicesAndClosedBaselineDpcm() throws Exception {
        String state = "00".repeat(8192);
        for (String changed : List.of(
                service().replace("\"kind\":3", "\"kind\":0"),
                dpcmService()
                        .replace("\"cancelled\":false", "\"cancelled\":true"),
                dpcmService()
                        .replace("\"complete\":false", "\"complete\":true")
                        .replace("\"end_coordinate\":0", "\"end_coordinate\":2"))) {
            Path raw = temporary.resolve("bad-frontier-kind-" + Math.abs(changed.hashCode()) + ".jsonl");
            String baseline = boundary("baseline", "\"row\":769,", state)
                    .replace(dpcmService(), changed);
            Files.writeString(raw, metadata() + baseline
                    + boundary("cutoff", "\"exclusive_end\":769,", state));
            assertThrows(IllegalArgumentException.class,
                    () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
        }
    }

    @Test
    void rejectsAnyAncestryTransitionWithoutPinnedPromotionHooks() throws Exception {
        String state = "00".repeat(8192);
        String transition = "{\"coordinate\":9,\"native_ordinal\":10,"
                + "\"previous_parent_token\":0,\"previous_depth\":0,"
                + "\"current_parent_token\":2,\"current_depth\":1,"
                + "\"hook_token\":20,\"source_cpu\":1,\"pc\":378}";
        String changed = service().replace("\"current_parent_token\":0", "\"current_parent_token\":2")
                .replace("\"current_depth\":0", "\"current_depth\":1")
                .replace("\"ancestry_transitions\":[]", "\"ancestry_transitions\":[" + transition + "]");
        Path raw = temporary.resolve("bad-ancestry.jsonl");
        Files.writeString(raw, metadata() + boundary("baseline", "\"row\":769,", state)
                + boundary("cutoff", "\"exclusive_end\":769,", state)
                .replace("\"active_services\":[]", "\"active_services\":[" + changed + "]"));
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
    }

    @Test
    void rejectsEffectiveAncestryRewriteWithoutAnyTransition() throws Exception {
        String state = "00".repeat(8192);
        String changed = service()
                .replace("\"current_parent_token\":0", "\"current_parent_token\":2")
                .replace("\"current_depth\":0", "\"current_depth\":1");
        Path raw = temporary.resolve("rewritten-ancestry-without-transition.jsonl");
        Files.writeString(raw, metadata() + boundary("baseline", "\"row\":769,", state)
                + boundary("cutoff", "\"exclusive_end\":769,", state)
                .replace("\"active_services\":[]", "\"active_services\":[" + changed + "]"));

        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
    }

    @Test
    void rejectsSoleBaselineServiceWhoseParentIsAbsent() throws Exception {
        String state = "00".repeat(8192);
        String changed = dpcmService()
                .replace("\"parent_token\":0", "\"parent_token\":2")
                .replace("\"depth\":0", "\"depth\":1")
                .replace("\"current_parent_token\":0", "\"current_parent_token\":2")
                .replace("\"current_depth\":0", "\"current_depth\":1");
        Path raw = temporary.resolve("baseline-absent-parent.jsonl");
        Files.writeString(raw, metadata()
                + boundary("baseline", "\"row\":769,", state).replace(dpcmService(), changed)
                + boundary("cutoff", "\"exclusive_end\":769,", state));

        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
    }

    private static String metadata() {
        return "{\"type\":\"metadata\",\"schema\":\"openggf.s2-complete-run-audio-raw.v2\","
                + "\"rom_sha1\":\"8bca5dcef1af3e00098666fd892dc1c2a76333f9\","
                + "\"bk2_sha256\":\"e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5\","
                + "\"service_manifest_sha256\":\"ef8f8103c38d70e41cb09cb29751f56815a0401709dc509071aa514d614813a0\","
                + "\"first_row\":769,\"exclusive_end\":259590,\"state_start\":0,"
                + "\"state_exclusive_end\":8192}\n";
    }

    private static String boundary(String type, String coordinate, String state) {
        String active = type.equals("baseline")
                ? "[" + dpcmService() + "]"
                : "[]";
        return "{\"type\":\"" + type + "\"," + coordinate + "\"state_hex\":\"" + state
                + "\",\"ym_port0_latch\":42,\"ym_port1_latch\":161,"
                + "\"native_arm_epoch\":1,\"native_armed\":true,"
                + "\"active_services\":" + active + ",\"pending_descendants\":[]}\n";
    }

    private static String event() {
        return "\"ordinal\":0,\"service_token\":2,\"parent_token\":1,\"pc\":4660,"
                + "\"subject\":1,\"offset\":0,\"kind\":6,\"service_kind\":2,"
                + "\"depth\":1,\"source_cpu\":1,\"payload_length\":8,\"value\":0,"
                + "\"flags\":0,\"reserved\":0,\"payload\":\"18446744073709551615\"";
    }

    private static String snapshotEvents() {
        return "{" + snapshotBegin() + "},{" + snapshotChunk() + "},{" + snapshotEnd() + "},"
                + dpcmPopCompletion(3);
    }

    private static String snapshotBegin() {
        return snapshotBase().replace("\"kind\":6", "\"kind\":5")
                .replace("\"subject\":1", "\"subject\":2")
                .replace("\"payload_length\":8", "\"payload_length\":0")
                .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"0\"");
    }

    private static String snapshotChunk() {
        return snapshotBase().replace("\"ordinal\":0", "\"ordinal\":1")
                .replace("\"subject\":1", "\"subject\":2")
                .replace("\"payload_length\":8", "\"payload_length\":1")
                .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"255\"");
    }

    private static String snapshotEnd() {
        return snapshotBase().replace("\"ordinal\":0", "\"ordinal\":2")
                .replace("\"kind\":6", "\"kind\":7")
                .replace("\"subject\":1", "\"subject\":2")
                .replace("\"offset\":0", "\"offset\":1")
                .replace("\"payload_length\":8", "\"payload_length\":0")
                .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"0\"");
    }

    private static String snapshotBase() {
        return event().replace("\"service_token\":2", "\"service_token\":1")
                .replace("\"parent_token\":1", "\"parent_token\":0")
                .replace("\"pc\":4660", "\"pc\":432")
                .replace("\"service_kind\":2", "\"service_kind\":4")
                .replace("\"depth\":1", "\"depth\":0");
    }

    private static String resetBegin(int activeCount, int power) {
        return "{" + event().replace("\"service_token\":2", "\"service_token\":9")
                .replace("\"parent_token\":1", "\"parent_token\":0")
                .replace("\"pc\":4660", "\"pc\":0")
                .replace("\"subject\":1", "\"subject\":" + activeCount)
                .replace("\"kind\":6", "\"kind\":8")
                .replace("\"service_kind\":2", "\"service_kind\":1")
                .replace("\"depth\":1", "\"depth\":0")
                .replace("\"source_cpu\":1", "\"source_cpu\":3")
                .replace("\"payload_length\":8", "\"payload_length\":0")
                .replace("\"flags\":0", "\"flags\":" + power)
                .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"0\"") + "}";
    }

    private static String resetEnd(int ordinal, int power) {
        String begin = resetBegin(0, power);
        return begin.replace("\"ordinal\":0", "\"ordinal\":" + ordinal)
                .replace("\"kind\":8", "\"kind\":9");
    }

    private static String resetCancellation(int ordinal) {
        return "{" + event().replace("\"ordinal\":0", "\"ordinal\":" + ordinal)
                .replace("\"service_token\":2", "\"service_token\":1")
                .replace("\"parent_token\":1", "\"parent_token\":0")
                .replace("\"pc\":4660", "\"pc\":0")
                .replace("\"subject\":1", "\"subject\":0")
                .replace("\"kind\":6", "\"kind\":2")
                .replace("\"service_kind\":2", "\"service_kind\":4")
                .replace("\"depth\":1", "\"depth\":0")
                .replace("\"source_cpu\":1", "\"source_cpu\":3")
                .replace("\"payload_length\":8", "\"payload_length\":0")
                .replace("\"flags\":0", "\"flags\":2")
                .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"0\"") + "}";
    }

    private void assertFrameRejected(String name, String events) throws Exception {
        String state = "00".repeat(8192);
        Path raw = temporary.resolve(name + ".jsonl");
        Files.writeString(raw, metadata() + boundary("baseline", "\"row\":769,", state)
                + "{\"type\":\"frame\",\"row\":769,\"lag\":false,\"state_hex\":\"" + state
                + "\",\"events\":[" + events + "]}\n"
                + boundary("cutoff", "\"exclusive_end\":770,", state));
        assertThrows(IllegalArgumentException.class,
                () -> S2CompleteRunReferenceRawAdapter.scanPrefixForTesting(raw, new RecordingSink()));
    }

    private static String snapshotTransaction(int firstOrdinal, int token, int parent,
            int serviceKind, int depth, int source, int pc, int range) {
        int length = range == 1 ? 8192 : 1;
        StringBuilder result = new StringBuilder();
        result.append(snapshotEvent(firstOrdinal, token, parent, pc, range, 0,
                5, serviceKind, depth, source, 0, "0"));
        int ordinal = firstOrdinal + 1;
        for (int offset = 0; offset < length; offset += 8) {
            int count = Math.min(8, length - offset);
            if (!result.isEmpty()) result.append(',');
            result.append(snapshotEvent(ordinal++, token, parent, pc, range, offset,
                    6, serviceKind, depth, source, count,
                    count == 1 ? "255" : "18446744073709551615"));
        }
        result.append(',').append(snapshotEvent(ordinal, token, parent, pc, range, length,
                7, serviceKind, depth, source, 0, "0"));
        return result.toString();
    }

    private static String snapshotEvent(int ordinal, int token, int parent, int pc,
            int range, int offset, int kind, int serviceKind, int depth, int source,
            int payloadLength, String payload) {
        return "{\"ordinal\":" + ordinal + ",\"service_token\":" + token
                + ",\"parent_token\":" + parent + ",\"pc\":" + pc
                + ",\"subject\":" + range + ",\"offset\":" + offset
                + ",\"kind\":" + kind + ",\"service_kind\":" + serviceKind
                + ",\"depth\":" + depth + ",\"source_cpu\":" + source
                + ",\"payload_length\":" + payloadLength
                + ",\"value\":0,\"flags\":0,\"reserved\":0,\"payload\":\""
                + payload + "\"}";
    }

    private static String normalDpcmCompletion(int ordinal) {
        return resetCancellation(ordinal)
                .replace("\"pc\":0", "\"pc\":378")
                .replace("\"subject\":0", "\"subject\":20")
                .replace("\"source_cpu\":3", "\"source_cpu\":1")
                .replace("\"flags\":2", "\"flags\":0");
    }

    private static String dpcmPopCompletion(int ordinal) {
        return normalDpcmCompletion(ordinal)
                .replace("\"pc\":378", "\"pc\":432")
                .replace("\"subject\":20", "\"subject\":6");
    }

    private static String service() {
        return "{\"token\":1,\"parent_token\":0,\"kind\":3,\"depth\":0,"
                + "\"current_parent_token\":0,\"current_depth\":0,"
                + "\"begin_coordinate\":1,\"end_coordinate\":0,"
                + "\"begin_row\":700,\"begin_native_ordinal\":12,"
                + "\"begin_pc\":56,\"end_pc\":0,\"begin_hook_token\":1,"
                + "\"end_hook_token\":0,\"begin_source_cpu\":1,"
                + "\"cancelled\":false,\"complete\":false,\"chips\":[],"
                + "\"snapshots\":[],\"ancestry_transitions\":[]}";
    }

    private static String dpcmService() {
        return service().replace("\"kind\":3", "\"kind\":4")
                .replace("\"begin_pc\":56", "\"begin_pc\":378")
                .replace("\"begin_hook_token\":1", "\"begin_hook_token\":5");
    }

    private static String resetService() {
        return "{\"token\":9,\"parent_token\":0,\"kind\":1,\"depth\":0,"
                + "\"current_parent_token\":0,\"current_depth\":0,"
                + "\"begin_coordinate\":1,\"end_coordinate\":2,"
                + "\"begin_row\":700,\"begin_native_ordinal\":12,"
                + "\"begin_pc\":0,\"end_pc\":0,\"begin_hook_token\":0,"
                + "\"end_hook_token\":0,\"begin_source_cpu\":0,"
                + "\"cancelled\":false,\"complete\":true,\"chips\":[],"
                + "\"snapshots\":[],\"ancestry_transitions\":[]}";
    }

    private static String completedVintService(int row, long beginCoordinate, long endCoordinate) {
        return service()
                .replace("\"token\":1", "\"token\":2")
                .replace("\"parent_token\":0", "\"parent_token\":1")
                .replace("\"depth\":0", "\"depth\":1")
                .replace("\"current_parent_token\":0", "\"current_parent_token\":1")
                .replace("\"current_depth\":0", "\"current_depth\":1")
                .replace("\"begin_coordinate\":1", "\"begin_coordinate\":" + beginCoordinate)
                .replace("\"end_coordinate\":0", "\"end_coordinate\":" + endCoordinate)
                .replace("\"begin_row\":700", "\"begin_row\":" + row)
                .replace("\"begin_native_ordinal\":12", "\"begin_native_ordinal\":0")
                .replace("\"begin_hook_token\":1", "\"begin_hook_token\":2")
                .replace("\"end_pc\":0", "\"end_pc\":231")
                .replace("\"end_hook_token\":0", "\"end_hook_token\":3")
                .replace("\"complete\":false", "\"complete\":true")
                .replace("\"snapshots\":[]", "\"snapshots\":[{\"range_id\":2,"
                        + "\"source_cpu\":1,\"pc\":231,\"bytes_hex\":\"ff\"}]");
    }

    private static String vintBegin(int ordinal) {
        return "{" + event()
                .replace("\"ordinal\":0", "\"ordinal\":" + ordinal)
                .replace("\"pc\":4660", "\"pc\":56")
                .replace("\"subject\":1", "\"subject\":2")
                .replace("\"kind\":6", "\"kind\":1")
                .replace("\"service_kind\":2", "\"service_kind\":3")
                .replace("\"payload_length\":8", "\"payload_length\":0")
                .replace("\"payload\":\"18446744073709551615\"", "\"payload\":\"0\"") + "}";
    }

    private static String vintEnd(int ordinal) {
        return vintBegin(ordinal)
                .replace("\"pc\":56", "\"pc\":231")
                .replace("\"subject\":2", "\"subject\":3")
                .replace("\"kind\":1", "\"kind\":2");
    }

    private static String musicBegin(int ordinal) {
        return vintBegin(ordinal)
                .replace("\"service_token\":2", "\"service_token\":3")
                .replace("\"parent_token\":1", "\"parent_token\":2")
                .replace("\"pc\":56", "\"pc\":272")
                .replace("\"subject\":2", "\"subject\":21")
                .replace("\"service_kind\":3", "\"service_kind\":9")
                .replace("\"depth\":1", "\"depth\":2");
    }

    private static String musicEnd(int ordinal) {
        return musicBegin(ordinal)
                .replace("\"pc\":272", "\"pc\":331")
                .replace("\"subject\":21", "\"subject\":22")
                .replace("\"kind\":1", "\"kind\":2");
    }

    private static String completedMusicService() {
        return completedVintService(769, 1, 5)
                .replace("\"begin_native_ordinal\":0", "\"begin_native_ordinal\":1")
                .replace("\"token\":2", "\"token\":3")
                .replace("\"parent_token\":1", "\"parent_token\":2")
                .replace("\"kind\":3", "\"kind\":9")
                .replace("\"depth\":1", "\"depth\":2")
                .replace("\"current_parent_token\":1", "\"current_parent_token\":2")
                .replace("\"current_depth\":1", "\"current_depth\":2")
                .replace("\"begin_pc\":56", "\"begin_pc\":272")
                .replace("\"end_pc\":231", "\"end_pc\":331")
                .replace("\"begin_hook_token\":2", "\"begin_hook_token\":21")
                .replace("\"end_hook_token\":3", "\"end_hook_token\":22")
                .replace("\"pc\":231", "\"pc\":331");
    }

    private static final class RecordingSink implements S2CompleteRunReferenceRawAdapter.Sink {
        private S2CompleteRunReferenceRawAdapter.Header header;
        private S2CompleteRunReferenceRawAdapter.RawBoundary baseline;
        private final List<S2CompleteRunReferenceRawAdapter.RawFrame> frames = new ArrayList<>();
        private S2CompleteRunReferenceRawAdapter.RawBoundary cutoff;
        private int beginCalls;
        private int commitCalls;
        private int abortCalls;
        @Override public void begin() { beginCalls++; }
        @Override public void header(S2CompleteRunReferenceRawAdapter.Header value) { header = value; }
        @Override public void baseline(S2CompleteRunReferenceRawAdapter.RawBoundary value) { baseline = value; }
        @Override public void frame(S2CompleteRunReferenceRawAdapter.RawFrame value) { frames.add(value); }
        @Override public void cutoff(S2CompleteRunReferenceRawAdapter.RawBoundary value) { cutoff = value; }
        @Override public void commit() { commitCalls++; }
        @Override public void abort() {
            abortCalls++;
            header = null;
            baseline = null;
            frames.clear();
            cutoff = null;
        }
    }
}
