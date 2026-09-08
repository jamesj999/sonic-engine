package com.openggf.tools.audio.parity.s3k;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.tools.audio.parity.AudioParityChipWrite;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The comparison must be able to disagree: identical streams match, and a
 * single corrupted state byte or chip write is reported at its exact tick,
 * field and event index with no realignment.
 */
class TestS3kAudioParityComparator {

    @Test
    void identicalStreamsMatch() {
        List<S3kAudioTick> reference = List.of(tick(0, 0x40, 0xB4), tick(1, 0x40, 0x9F));
        assertTrue(S3kAudioParityComparator.compare(reference, reference).matches());
    }

    @Test
    void corruptedTrackByteIsReportedAtItsTickAndField() {
        List<S3kAudioTick> reference = List.of(tick(0, 0x40, 0xB4), tick(1, 0x40, 0x9F));
        List<S3kAudioTick> engine = new ArrayList<>(reference);
        S3kAudioTick victim = reference.get(1);
        List<S3kAudioTrackState> tracks = new ArrayList<>(victim.tracks());
        S3kAudioTrackState fm1 = tracks.get(1);
        tracks.set(1, new S3kAudioTrackState(fm1.role(), fm1.playing(), fm1.overridden(),
                fm1.doNotAttack(), fm1.resting(), fm1.voiceControl(), fm1.tempoDivider(),
                fm1.dataPointer(), fm1.transpose(), fm1.volume() + 1, fm1.modulationCtrl(),
                fm1.voiceIndex(), fm1.amsFmsPan(), fm1.durationTimeout(), fm1.savedDuration(),
                fm1.frequency(), fm1.detune(), fm1.volEnv(), fm1.noteFillTimeout(),
                fm1.noteFillMaster(), fm1.modulationPtr(), fm1.modulationVal(),
                fm1.modulationWait(), fm1.modulationSpeed(), fm1.modulationDelta(),
                fm1.modulationSteps()));
        engine.set(1, new S3kAudioTick(victim.ordinal(), victim.lag(), victim.mailbox(),
                victim.global(), tracks, victim.writes()));

        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(reference, engine);
        assertEquals(S3kAudioParityComparator.Report.Kind.TRACK_STATE_MISMATCH, report.kind());
        assertEquals(1, report.tick());
        assertEquals("MUS_FM1", report.role());
        assertEquals("volume", report.field());
    }

    @Test
    void corruptedWriteIsReportedAtItsEventIndex() {
        List<S3kAudioTick> reference = List.of(tick(0, 0x40, 0xB4), tick(1, 0x40, 0x9F));
        List<S3kAudioTick> engine = new ArrayList<>(reference);
        S3kAudioTick victim = reference.get(0);
        List<AudioParityChipWrite> writes = new ArrayList<>(victim.writes());
        AudioParityChipWrite write = writes.get(1);
        writes.set(1, AudioParityChipWrite.ym2612(write.port(), write.register(),
                write.value() ^ 0x01));
        engine.set(0, new S3kAudioTick(victim.ordinal(), victim.lag(), victim.mailbox(),
                victim.global(), victim.tracks(), writes));

        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(reference, engine);
        assertEquals(S3kAudioParityComparator.Report.Kind.EVENT_VALUE_DIFFERENT, report.kind());
        assertEquals(0, report.tick());
        assertEquals(1, report.eventIndex());
    }

    @Test
    void missingWriteIsReportedAsEventMissing() {
        List<S3kAudioTick> reference = List.of(tick(0, 0x40, 0xB4));
        S3kAudioTick victim = reference.get(0);
        List<AudioParityChipWrite> writes = new ArrayList<>(victim.writes());
        writes.remove(writes.size() - 1);
        List<S3kAudioTick> engine = List.of(new S3kAudioTick(victim.ordinal(), victim.lag(),
                victim.mailbox(), victim.global(), victim.tracks(), writes));

        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(reference, engine);
        assertEquals(S3kAudioParityComparator.Report.Kind.EVENT_MISSING, report.kind());
    }

    @Test
    void authenticatedUnavailableProducerInputIsAReferenceLimitation() {
        S3kAudioTick ordinary = tick(0, 0x40, 0xB4);
        S3kAudioTick reference = new S3kAudioTick(
                ordinary.ordinal(), ordinary.lag(), ordinary.mailbox(),
                ordinary.global(), ordinary.tracks(), ordinary.writes(),
                S3kAudioTick.ProducerInputEvidence.unavailable(
                        "mailbox sampling was suspended"));
        List<AudioParityChipWrite> writes = new ArrayList<>(ordinary.writes());
        writes.removeLast();
        S3kAudioTick engine = new S3kAudioTick(
                ordinary.ordinal(), ordinary.lag(), ordinary.mailbox(),
                ordinary.global(), ordinary.tracks(), writes);

        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(
                        List.of(reference), List.of(engine));

        assertEquals(S3kAudioParityComparator.Report.Kind.REFERENCE_LIMITATION,
                report.kind());
        assertEquals(0, report.tick());
        assertEquals("producer_input", report.field());
        assertTrue(report.toHumanText().startsWith(
                "S3K audio oracle: REFERENCE_LIMITATION"));
        assertTrue(report.toMachineText().contains(
                "\"kind\":\"REFERENCE_LIMITATION\""));
        assertEquals(S3kAudioParityTool.EXIT_REFERENCE_LIMITATION,
                S3kAudioParityTool.exitCode(report));
    }

    @Test
    void missingWriteWithoutProducerEvidenceCannotBeDowngraded() {
        S3kAudioTick reference = tick(0, 0x40, 0xB4);
        List<AudioParityChipWrite> writes = new ArrayList<>(reference.writes());
        writes.removeLast();
        S3kAudioTick engine = new S3kAudioTick(
                reference.ordinal(), reference.lag(), reference.mailbox(),
                reference.global(), reference.tracks(), writes);

        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(
                        List.of(reference), List.of(engine));

        assertEquals(S3kAudioParityComparator.Report.Kind.EVENT_MISSING,
                report.kind());
        assertEquals(S3kAudioParityTool.EXIT_MISMATCH,
                S3kAudioParityTool.exitCode(report));
    }

    @Test
    void cliReportFormatSelectsStableHumanOrMachineRendering() {
        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(
                        List.of(tick(0, 0x40, 0xB4)),
                        List.of(tick(0, 0x40, 0xB4)));

        assertEquals(report.toHumanText(),
                S3kAudioParityTool.renderReport(report, "text"));
        assertEquals(report.toMachineText(),
                S3kAudioParityTool.renderReport(report, "json"));
    }

    /**
     * The sample-end {@code 2Bh = 0} left the strictly partitioned service
     * stream, so a run that exhausts on one side and is superseded on the
     * other no longer fails the tick comparison. Reverting the exclusion in
     * {@code serviceWrites} makes this fail with {@code EVENT_VALUE_DIFFERENT}
     * at tick 1 event 0.
     */
    @Test
    void theSampleEndDisableIsNotComparedInTheServiceStream() {
        List<S3kAudioTick> reference = List.of(tick(0, 0x40, 0xB4),
                withWrites(tick(1, 0x40, 0x9F),
                        AudioParityChipWrite.ym2612(0, 0xA4, 0x1B)));
        List<S3kAudioTick> engine = List.of(tick(0, 0x40, 0xB4),
                withWrites(tick(1, 0x40, 0x9F),
                        AudioParityChipWrite.ym2612(0, 0x2B, 0x00),
                        AudioParityChipWrite.ym2612(0, 0xA4, 0x1B)));

        assertTrue(S3kAudioParityComparator.compare(reference, engine).matches(),
                () -> S3kAudioParityComparator.compare(reference, engine).toHumanText());
    }

    /**
     * The {@code 2Bh = 80h} enable did NOT move. The idle loop writes it on
     * finding {@code zDACIndex} non-zero at a service boundary, so every run
     * start stays pinned to an exact tick. Widening the exclusion in
     * {@code serviceWrites} to all {@code 2Bh} writes makes this fail.
     */
    @Test
    void theRunStartEnableStaysComparedInTheServiceStream() {
        List<S3kAudioTick> reference = List.of(tick(0, 0x40, 0xB4),
                withWrites(tick(1, 0x40, 0x9F),
                        AudioParityChipWrite.ym2612(0, 0xA4, 0x1B)));
        List<S3kAudioTick> engine = List.of(tick(0, 0x40, 0xB4),
                withWrites(tick(1, 0x40, 0x9F),
                        AudioParityChipWrite.ym2612(0, 0x2B, 0x80),
                        AudioParityChipWrite.ym2612(0, 0xA4, 0x1B)));

        S3kAudioParityComparator.Report report =
                S3kAudioParityComparator.compare(reference, engine);
        assertEquals(S3kAudioParityComparator.Report.Kind.EVENT_VALUE_DIFFERENT,
                report.kind());
        assertEquals(1, report.tick());
    }

    /**
     * A byte streaming while the DAC is disabled is counted, not failed. The
     * committed reference does it once itself, at service 3,837, so calling
     * it an error would fail the oracle on the reference's own behaviour.
     * The count must still reach the result line, or the difference would be
     * invisible.
     */
    @Test
    void bytesStreamedWhileTheDacIsDisabledAreCountedNotFailed() {
        // Four one-byte runs each side, same bytes, same run count. The only
        // difference is that the second run's byte arrives before its enable.
        List<S3kAudioTick> clean = List.of(withWrites(tick(0, 0x40, 0xB4),
                enable(), byte7F(), disable(),
                enable(), byte7F(), disable(),
                enable(), byte7F(), disable(),
                enable(), byte7F()));
        List<S3kAudioTick> streaming = List.of(withWrites(tick(0, 0x40, 0xB4),
                enable(), byte7F(), disable(),
                byte7F(), disable(),
                enable(), byte7F(), disable(),
                enable(), byte7F()));

        assertTrue(S3kAudioParityComparator.compareDacStream(clean, clean).matches());
        S3kAudioParityComparator.DacStreamReport report =
                S3kAudioParityComparator.compareDacStream(clean, streaming);
        assertTrue(report.matches(), report::toHumanText);
        assertEquals(1, report.idleByteDelta());
        assertTrue(report.toHumanText().contains("idle-byte delta 1"),
                report::toHumanText);
    }

    private static AudioParityChipWrite enable() {
        return AudioParityChipWrite.ym2612(0, 0x2B, 0x80);
    }

    @Test
    void byteMismatchReportsBothRunStartServicesWithoutRealigningRuns() {
        List<S3kAudioTick> reference = List.of(
                withWrites(tick(12, 0x40, 0xB4), enable(), byte7F()),
                withWrites(tick(13, 0x40, 0xB4),
                        AudioParityChipWrite.ym2612(0, 0x2A, 0x88)));
        List<S3kAudioTick> engine = List.of(
                withWrites(tick(20, 0x40, 0xB4), enable(), byte7F()),
                withWrites(tick(21, 0x40, 0xB4),
                        AudioParityChipWrite.ym2612(0, 0x2A, 0x99)));

        var report = S3kAudioParityComparator.compareDacStream(reference, engine);
        assertEquals(S3kAudioParityComparator.DacStreamReport.Kind.BYTE_DIFFERENT, report.kind());
        assertEquals(1, report.byteOffset());
        assertTrue(report.toHumanText().contains("reference run start service: 12"), report::toHumanText);
        assertTrue(report.toHumanText().contains("openggf run start service: 20"), report::toHumanText);
        assertTrue(report.toMachineText().contains("\"reference_run_start_service\":12"), report::toMachineText);
        assertTrue(report.toMachineText().contains("\"openggf_run_start_service\":20"), report::toMachineText);
    }

    private static AudioParityChipWrite disable() {
        return AudioParityChipWrite.ym2612(0, 0x2B, 0x00);
    }

    private static AudioParityChipWrite byte7F() {
        return AudioParityChipWrite.ym2612(0, 0x2A, 0x7F);
    }

    private static S3kAudioTick withWrites(S3kAudioTick source,
            AudioParityChipWrite... extra) {
        List<AudioParityChipWrite> writes = new ArrayList<>(source.writes());
        writes.addAll(List.of(extra));
        return new S3kAudioTick(source.ordinal(), source.lag(), source.mailbox(),
                source.global(), source.tracks(), writes);
    }

    private static S3kAudioTick tick(int ordinal, int tempo, int psgWrite) {
        List<S3kAudioTrackState> tracks = new ArrayList<>();
        for (int index = 0; index < S3kAudioParitySchema.ROLES.size(); index++) {
            String role = S3kAudioParitySchema.ROLES.get(index);
            tracks.add(index == 1
                    ? new S3kAudioTrackState(role, true, false, false, false, 0x00, 1, null,
                            0, 0x0D, 0, 1, 0xC0, 1, 1, 0x2200, 0, null, null, null,
                            null, 0, 0, 0, 0, 0)
                    : S3kAudioTrackState.idle(role));
        }
        return new S3kAudioTick(ordinal, false, List.of(0, 0, 0),
                new S3kAudioTick.GlobalState(tempo, 0, 0, 0, null, null, null, null, null, null,
                        null, null, 5),
                tracks,
                List.of(AudioParityChipWrite.ym2612(0, 0x28, 0x00),
                        AudioParityChipWrite.ym2612(0, 0xB4, 0xC0),
                        AudioParityChipWrite.psg(psgWrite)));
    }
}
