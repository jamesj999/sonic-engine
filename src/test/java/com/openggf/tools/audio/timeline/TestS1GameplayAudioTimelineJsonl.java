package com.openggf.tools.audio.timeline;

import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.HardwareRole.FM3;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.MUSIC;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.NONE;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.SoundClass.SFX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestS1GameplayAudioTimelineJsonl {
    @TempDir
    Path temp;

    @Test
    void canonicalStreamRoundTripsByteForByte() throws Exception {
        Path first = temp.resolve("first.jsonl");
        Path second = temp.resolve("second.jsonl");
        S1GameplayAudioTimeline.Metadata metadata = metadata();

        S1GameplayAudioTimelineJsonl.writeNew(first, metadata, records().iterator());
        try (S1GameplayAudioTimelineJsonl.Reader reader = S1GameplayAudioTimelineJsonl.read(first)) {
            assertEquals(metadata, reader.metadata());
            List<S1GameplayAudioTimeline.TimelineRecord> parsed = new ArrayList<>();
            while (reader.hasNext()) {
                parsed.add(reader.next());
            }
            S1GameplayAudioTimelineJsonl.writeNew(second, metadata, parsed.iterator());
        }

        assertEquals(Files.readString(first), Files.readString(second));
    }

    @Test
    void validatesPinnedIdentityBoundsAndFrame860MusicBaseline() throws Exception {
        S1GameplayAudioTimeline.Metadata metadata = metadata();
        assertEquals("s1_gameplay_audio_timeline.v2", metadata.schema());
        assertEquals(860, metadata.segmentStartBk2Frame());
        assertEquals(4975, metadata.segmentEndBk2Frame());
        assertEquals(4115, metadata.terminalFrameCount());
        assertEquals("69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b", metadata.romSha1());
        assertEquals("afe05eee", metadata.romCrc32());

        S1GameplayAudioTimeline.Baseline baseline = baseline();
        assertEquals(860, baseline.bk2Frame());
        assertEquals(0x81, baseline.activeMusicId());
        assertThrows(IllegalArgumentException.class, () -> new S1GameplayAudioTimeline.Metadata(
                metadata.schema(), metadata.capture(), metadata.romSha1(), metadata.romCrc32(),
                metadata.bk2Sha256(), metadata.producer(), 861, 4975, 4115));
        assertThrows(IllegalArgumentException.class, () -> new S1GameplayAudioTimeline.Metadata(
                metadata.schema(), metadata.capture(), metadata.romSha1(), metadata.romCrc32(),
                metadata.bk2Sha256(), "unreviewed producer", 860, 4975, 4115));
        assertEquals("OpenGGF", new S1GameplayAudioTimeline.Metadata(metadata.schema(),
                S1GameplayAudioTimeline.OPENGGF_CAPTURE, metadata.romSha1(), metadata.romCrc32(),
                metadata.bk2Sha256(), "OpenGGF", 860, 4975, 4115).producer());
        assertThrows(IllegalArgumentException.class, () -> new S1GameplayAudioTimeline.Baseline(
                860, 0x80, null, baseline.owners()));
    }

    @Test
    void rejectsInvalidRequestOwnershipAndArbitrationContracts() {
        S1GameplayAudioTimeline.OwnerRef none = none();
        S1GameplayAudioTimeline.OwnerRef music = music();
        assertThrows(IllegalArgumentException.class,
                () -> new S1GameplayAudioTimeline.OwnerRef(NONE, 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new S1GameplayAudioTimeline.Request(0, SFX, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new S1GameplayAudioTimeline.Admission(1, SFX, 0xA0, List.of(FM3), List.of(
                        new S1GameplayAudioTimeline.RoleArbitration(
                                S1GameplayAudioTimeline.HardwareRole.FM4, true, music, music))));
        assertThrows(IllegalArgumentException.class,
                () -> new S1GameplayAudioTimeline.Admission(1, SFX, 0xA0, List.of(FM3), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new S1GameplayAudioTimeline.Admission(1, SFX, 0xA0, List.of(FM3), List.of(
                        new S1GameplayAudioTimeline.RoleArbitration(FM3, true, none, none))));
        assertThrows(IllegalArgumentException.class,
                () -> new S1GameplayAudioTimeline.Admission(1, SFX, 0xA0, List.of(FM3), List.of(
                        new S1GameplayAudioTimeline.RoleArbitration(FM3, false, none, music))));
        assertThrows(IllegalArgumentException.class,
                () -> new S1GameplayAudioTimeline.OwnerRef(MUSIC, 0xA0, 0));

        S1GameplayAudioTimeline.Request first = request(1);
        S1GameplayAudioTimeline.Request retrigger = request(2);
        assertEquals(first.rawSoundId(), retrigger.rawSoundId());
        assertFalse(first.requestOrdinal() == retrigger.requestOrdinal());
    }

    @Test
    void requestAndAdmissionKeepOneOrdinalAcrossTheirOwnFrameBoundaries() {
        // Break caught: queue/submission time is discarded and the later driver admission
        // is mislabeled as the caller request, hiding one-frame presentation latency.
        var request = new S1GameplayAudioTimeline.Request(1, SFX, 0xA0);
        var owner = new S1GameplayAudioTimeline.OwnerRef(
                S1GameplayAudioTimeline.OwnerClass.NORMAL_SFX, 0xA0, 1);
        var admission = new S1GameplayAudioTimeline.Admission(1, SFX, 0xA0,
                List.of(FM3), List.of(new S1GameplayAudioTimeline.RoleArbitration(
                        FM3, true, music(), owner)));

        var requestFrame = new S1GameplayAudioTimeline.Frame(
                958, 1L, List.of(request), List.of(), owners());
        var admissionFrame = new S1GameplayAudioTimeline.Frame(
                959, 2L, List.of(), List.of(admission), owners());

        assertEquals(1, requestFrame.requests().getFirst().requestOrdinal());
        assertEquals(0xA0, requestFrame.requests().getFirst().rawSoundId());
        assertEquals(1, admissionFrame.admissions().getFirst().requestOrdinal());
        assertEquals(0xA0, admissionFrame.admissions().getFirst().soundId());
    }

    @Test
    void ringRequestRetainsRawB5WhileAdmissionCarriesResolvedCe() {
        // Break caught: producer-specific stereo resolution is compared as if gameplay
        // requested different sounds, instead of being an admission-stage transformation.
        var request = new S1GameplayAudioTimeline.Request(2, SFX, 0xB5);
        var resolved = new S1GameplayAudioTimeline.OwnerRef(
                S1GameplayAudioTimeline.OwnerClass.NORMAL_SFX, 0xCE, 2);
        var admission = new S1GameplayAudioTimeline.Admission(2, SFX, 0xCE,
                List.of(FM3), List.of(new S1GameplayAudioTimeline.RoleArbitration(
                        FM3, true, music(), resolved)));

        assertEquals(0xB5, request.rawSoundId());
        assertEquals(0xCE, admission.soundId());
        assertEquals(request.requestOrdinal(), admission.requestOrdinal());
    }

    @Test
    void rejectsMalformedRootsUnknownOrDuplicateFieldsAndPreservesExistingOutput() throws Exception {
        Path invalid = temp.resolve("invalid.jsonl");
        Files.writeString(invalid, "{\"capture\":\"s1_ghz_gameplay_audio_reference\",\"capture\":\"x\"}\n");
        assertThrows(IllegalArgumentException.class, () -> S1GameplayAudioTimelineJsonl.read(invalid));
        Files.writeString(invalid, "{}{}\n");
        assertThrows(IllegalArgumentException.class, () -> S1GameplayAudioTimelineJsonl.read(invalid));

        Path valid = temp.resolve("unknown.jsonl");
        S1GameplayAudioTimelineJsonl.writeNew(valid, metadata(), records().iterator());
        Files.writeString(valid, Files.readString(valid).replaceFirst("\"type\":\"frame\"",
                "\"type\":\"frame\",\"unknown\":true"));
        assertThrows(IllegalArgumentException.class, () -> consume(valid));

        Path destination = temp.resolve("existing.jsonl");
        Files.writeString(destination, "trusted\n", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> S1GameplayAudioTimelineJsonl.writeNew(destination, metadata(),
                        List.<S1GameplayAudioTimeline.TimelineRecord>of(baseline()).iterator()));
        assertEquals("trusted\n", Files.readString(destination));
    }

    @Test
    void readerValidatesContinuityMonotonicTicksAndTerminalCounts() throws Exception {
        Path output = temp.resolve("valid.jsonl");
        S1GameplayAudioTimelineJsonl.writeNew(output, metadata(), records().iterator());
        try (S1GameplayAudioTimelineJsonl.Reader reader = S1GameplayAudioTimelineJsonl.read(output)) {
            int frames = 0;
            while (reader.hasNext()) {
                if (reader.next() instanceof S1GameplayAudioTimeline.Frame) {
                    frames++;
                }
            }
            assertEquals(4115, frames);
        }

        String content = Files.readString(output);
        Files.writeString(output, content.replaceFirst("\"bk2_frame\":861", "\"bk2_frame\":862"));
        assertThrows(IllegalArgumentException.class, () -> consume(output));

        Files.writeString(output, content.replaceFirst("\"diagnostic_tick\":1", "\"diagnostic_tick\":0"));
        assertThrows(IllegalArgumentException.class, () -> consume(output));
        Files.writeString(output, content.replaceFirst("\"request_count\":1", "\"request_count\":2"));
        assertThrows(IllegalArgumentException.class, () -> consume(output));
    }

    @Test
    void readerRejectsOversizedNestedRequestArraysBeforeMaterializingThem() throws Exception {
        Path output = temp.resolve("large.jsonl");
        List<S1GameplayAudioTimeline.TimelineRecord> records = records();
        S1GameplayAudioTimeline.Frame first = (S1GameplayAudioTimeline.Frame) records.get(1);
        List<S1GameplayAudioTimeline.Request> requests = new ArrayList<>();
        for (int ordinal = 1; ordinal <= 10_000; ordinal++) {
            requests.add(request(ordinal));
        }
        assertThrows(IllegalArgumentException.class,
                () -> records.set(1, new S1GameplayAudioTimeline.Frame(first.bk2Frame(), first.diagnosticTick(),
                        requests, first.admissions(), first.owners())));
        assertFalse(Files.exists(output));
    }

    @Test
    void semanticEqualityAndHashingExcludeDiagnosticTicks() {
        S1GameplayAudioTimeline.Frame first = new S1GameplayAudioTimeline.Frame(860, 1L,
                List.of(request(1)), List.of(admission(1)), owners());
        S1GameplayAudioTimeline.Frame second = new S1GameplayAudioTimeline.Frame(860, 2L,
                List.of(request(1)), List.of(admission(1)), owners());

        assertFalse(first.equals(second));
        assertTrue(S1GameplayAudioTimeline.semanticEquals(first, second));
        assertEquals(S1GameplayAudioTimeline.semanticHashCode(first),
                S1GameplayAudioTimeline.semanticHashCode(second));
    }

    private void consume(Path path) {
        try (S1GameplayAudioTimelineJsonl.Reader reader = S1GameplayAudioTimelineJsonl.read(path)) {
            while (reader.hasNext()) {
                reader.next();
            }
        }
    }

    private S1GameplayAudioTimeline.Metadata metadata() {
        return new S1GameplayAudioTimeline.Metadata("s1_gameplay_audio_timeline.v2",
                "s1_ghz_gameplay_audio_reference", "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b",
                "afe05eee", "f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b",
                "BizHawk 2.11 / Genesis Plus GX", 860, 4975, 4115);
    }

    private List<S1GameplayAudioTimeline.TimelineRecord> records() {
        List<S1GameplayAudioTimeline.TimelineRecord> records = new ArrayList<>();
        records.add(baseline());
        for (int frame = 860; frame < 4975; frame++) {
            records.add(new S1GameplayAudioTimeline.Frame(frame, (long) (frame - 860),
                    frame == 860 ? List.of(request(1)) : List.of(),
                    frame == 861 ? List.of(admission(1)) : List.of(), owners()));
        }
        records.add(new S1GameplayAudioTimeline.Terminal(4115, 1, 1, 4115));
        return records;
    }

    private S1GameplayAudioTimeline.Baseline baseline() {
        return new S1GameplayAudioTimeline.Baseline(860, 0x81, null, owners());
    }

    private S1GameplayAudioTimeline.Request request(long ordinal) {
        return new S1GameplayAudioTimeline.Request(ordinal, SFX, 0xA0);
    }

    private S1GameplayAudioTimeline.Admission admission(long ordinal) {
        return new S1GameplayAudioTimeline.Admission(ordinal, SFX, 0xA0, List.of(FM3), List.of(
                new S1GameplayAudioTimeline.RoleArbitration(FM3, true, music(),
                        new S1GameplayAudioTimeline.OwnerRef(
                                S1GameplayAudioTimeline.OwnerClass.NORMAL_SFX, 0xA0, ordinal))));
    }

    private S1GameplayAudioTimeline.OwnerVector owners() {
        return new S1GameplayAudioTimeline.OwnerVector(music(), music(), music(), none(), none(), none());
    }

    private S1GameplayAudioTimeline.OwnerRef music() {
        return new S1GameplayAudioTimeline.OwnerRef(MUSIC, 0x81, 0);
    }

    private S1GameplayAudioTimeline.OwnerRef none() {
        return new S1GameplayAudioTimeline.OwnerRef(NONE, 0, -1);
    }
}
