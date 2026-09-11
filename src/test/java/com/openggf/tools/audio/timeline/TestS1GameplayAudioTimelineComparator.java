package com.openggf.tools.audio.timeline;

import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.HardwareRole.FM3;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.MUSIC;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.NONE;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.OwnerClass.NORMAL_SFX;
import static com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.SoundClass.SFX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestS1GameplayAudioTimelineComparator {
    @TempDir
    Path temp;

    @Test
    void comparesCallerRequestsAtTheirOwnFrameBeforeAdmissionTiming() throws Exception {
        Function<S1GameplayAudioTimeline.Frame, S1GameplayAudioTimeline.Frame> reference = frame -> {
            if (frame.bk2Frame() == 958) {
                return with(frame, List.of(request(12, 0xA0)), List.of(), frame.owners());
            }
            if (frame.bk2Frame() == 959) {
                return with(frame, List.of(), List.of(admission(12, 0xA0, music(), owner(0xA0, 12))),
                        owners(owner(0xA0, 12)));
            }
            return frame;
        };
        Function<S1GameplayAudioTimeline.Frame, S1GameplayAudioTimeline.Frame> engine = frame -> {
            if (frame.bk2Frame() == 958) {
                return with(frame, List.of(request(12, 0xA0)),
                        List.of(admission(12, 0xA0, music(), owner(0xA0, 12))), owners(owner(0xA0, 12)));
            }
            return frame;
        };

        S1GameplayAudioTimelineReport report = S1GameplayAudioTimelineComparator.compare(
                write("reference-delay.jsonl", S1GameplayAudioTimeline.REFERENCE_CAPTURE, reference, 0),
                write("engine-delay.jsonl", S1GameplayAudioTimeline.OPENGGF_CAPTURE, engine, 0));

        assertEquals(S1GameplayAudioTimelineReport.Kind.ADMISSION_EXTRA, report.kind());
        assertEquals("frame 958 admission 0", report.location());
    }

    @Test
    void ringRequestCanRemainRawB5WhileBothAdmissionsResolveToCe() throws Exception {
        Function<S1GameplayAudioTimeline.Frame, S1GameplayAudioTimeline.Frame> ring = frame ->
                frame.bk2Frame() == 972
                        ? with(frame, List.of(request(12, 0xB5)),
                                List.of(admission(12, 0xCE, music(), owner(0xCE, 12))), owners(owner(0xCE, 12)))
                        : frame;

        assertTrue(S1GameplayAudioTimelineComparator.compare(
                write("reference-ring.jsonl", S1GameplayAudioTimeline.REFERENCE_CAPTURE, ring, 0),
                write("engine-ring.jsonl", S1GameplayAudioTimeline.OPENGGF_CAPTURE, ring, 0)).matches());
    }

    @Test
    void distinguishesRawRequestAndResolvedAdmissionIds() throws Exception {
        assertKind(S1GameplayAudioTimelineReport.Kind.REQUEST_ID_MISMATCH, frame ->
                frame.bk2Frame() == 900
                        ? with(frame, List.of(request(10, 0xA2)), frame.admissions(), frame.owners()) : frame);
        assertKind(S1GameplayAudioTimelineReport.Kind.ADMISSION_ID_MISMATCH, frame ->
                frame.bk2Frame() == 900
                        ? with(frame, frame.requests(),
                                List.of(admission(10, 0xA2, music(), owner(0xA2, 10))), frame.owners()) : frame);
    }

    @Test
    void reportsRoleArbitrationAndFinalOwnershipDifferences() throws Exception {
        assertKind(S1GameplayAudioTimelineReport.Kind.ROLE_ACQUIRED_MISMATCH, frame -> {
            if (frame.bk2Frame() != 901) return frame;
            var retained = owner(0xA0, 10);
            return with(frame, frame.requests(), List.of(admission(11, 0xA1, retained, retained, false)), retainedOwners());
        });
        assertKind(S1GameplayAudioTimelineReport.Kind.RESTORATION_MISMATCH, frame ->
                frame.bk2Frame() == 902 ? with(frame, frame.requests(), frame.admissions(), owners(owner(0xA1, 11))) : frame);
    }

    @Test
    void retainsAtMostEightBeforeMismatchAndExactlyEightAfterWithoutFillingTheFront() throws Exception {
        Function<S1GameplayAudioTimeline.Frame, S1GameplayAudioTimeline.Frame> earlyMismatch = frame ->
                frame.bk2Frame() == 861
                        ? with(frame, List.of(request(1, 0xA2)), List.of(), frame.owners()) : frame;
        S1GameplayAudioTimelineReport early = S1GameplayAudioTimelineComparator.compare(
                write("reference-early.jsonl", S1GameplayAudioTimeline.REFERENCE_CAPTURE, Function.identity(), 0),
                write("engine-early.jsonl", S1GameplayAudioTimeline.OPENGGF_CAPTURE, earlyMismatch, 0));
        assertEquals(11, early.referenceContext().size(), "baseline + frame 860 + mismatch + eight after");
        assertTrue(early.referenceContext().getLast().contains("869"));

        Function<S1GameplayAudioTimeline.Frame, S1GameplayAudioTimeline.Frame> middleMismatch = frame ->
                frame.bk2Frame() == 1000
                        ? with(frame, List.of(request(12, 0xA2)), List.of(), frame.owners()) : frame;
        S1GameplayAudioTimelineReport middle = S1GameplayAudioTimelineComparator.compare(
                write("reference-middle.jsonl", S1GameplayAudioTimeline.REFERENCE_CAPTURE, Function.identity(), 0),
                write("engine-middle.jsonl", S1GameplayAudioTimeline.OPENGGF_CAPTURE, middleMismatch, 0));
        assertEquals(17, middle.referenceContext().size());
        assertTrue(middle.referenceContext().getFirst().contains("992"));
        assertTrue(middle.referenceContext().getLast().contains("1008"));
    }

    @Test
    void validatesContentionCoverageAndIgnoresDiagnosticTicks() throws Exception {
        Path reference = write("reference.jsonl", S1GameplayAudioTimeline.REFERENCE_CAPTURE, Function.identity(), 0);
        Path diagnostics = write("diagnostics.jsonl", S1GameplayAudioTimeline.OPENGGF_CAPTURE, Function.identity(), 300);
        assertTrue(S1GameplayAudioTimelineComparator.compare(reference, diagnostics).matches());

        Function<S1GameplayAudioTimeline.Frame, S1GameplayAudioTimeline.Frame> removeContention = frame ->
                frame.bk2Frame() >= 900 && frame.bk2Frame() <= 902
                        ? with(frame, List.of(), List.of(), owners(music())) : frame;
        S1GameplayAudioTimelineReport uncovered = S1GameplayAudioTimelineComparator.compare(
                write("reference-uncovered.jsonl", S1GameplayAudioTimeline.REFERENCE_CAPTURE, removeContention, 0),
                write("engine-uncovered.jsonl", S1GameplayAudioTimeline.OPENGGF_CAPTURE, removeContention, 0));
        assertEquals(S1GameplayAudioTimelineReport.Kind.CAPTURE_FAILURE, uncovered.kind());
    }

    @Test
    void rejectsSourceReplacementAfterCompleteValidation() throws Exception {
        Path reference = write("reference-stable.jsonl", S1GameplayAudioTimeline.REFERENCE_CAPTURE,
                Function.identity(), 0);
        Path engine = write("engine-stable.jsonl", S1GameplayAudioTimeline.OPENGGF_CAPTURE,
                Function.identity(), 0);
        Path replacement = write("replacement.jsonl", S1GameplayAudioTimeline.OPENGGF_CAPTURE, frame ->
                frame.bk2Frame() == 1000
                        ? with(frame, List.of(request(12, 0xA2)), List.of(), frame.owners()) : frame, 0);

        S1GameplayAudioTimelineReport report = S1GameplayAudioTimelineComparator.compare(reference, engine, () -> {
            try {
                Files.move(replacement, engine, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        });
        assertEquals(S1GameplayAudioTimelineReport.Kind.CAPTURE_FAILURE, report.kind());
    }

    private void assertKind(S1GameplayAudioTimelineReport.Kind expected,
            Function<S1GameplayAudioTimeline.Frame, S1GameplayAudioTimeline.Frame> mutate) throws Exception {
        String suffix = java.util.UUID.randomUUID().toString();
        Path reference = write("reference-" + suffix + ".jsonl", S1GameplayAudioTimeline.REFERENCE_CAPTURE,
                Function.identity(), 0);
        Path engine = write("engine-" + suffix + ".jsonl", S1GameplayAudioTimeline.OPENGGF_CAPTURE, mutate, 0);
        assertEquals(expected, S1GameplayAudioTimelineComparator.compare(reference, engine).kind());
    }

    private Path write(String name, String capture,
            Function<S1GameplayAudioTimeline.Frame, S1GameplayAudioTimeline.Frame> mutate, long tickOffset) {
        List<S1GameplayAudioTimeline.TimelineRecord> records = new ArrayList<>();
        records.add(new S1GameplayAudioTimeline.Baseline(860, 0x81, null, owners(music())));
        long requests = 0;
        long admissions = 0;
        for (int frameNumber = 860; frameNumber < 4975; frameNumber++) {
            List<S1GameplayAudioTimeline.Request> frameRequests = switch (frameNumber) {
                case 900 -> List.of(request(10, 0xA0));
                case 901 -> List.of(request(11, 0xA1));
                default -> List.of();
            };
            List<S1GameplayAudioTimeline.Admission> frameAdmissions = switch (frameNumber) {
                case 900 -> List.of(admission(10, 0xA0, music(), owner(0xA0, 10)));
                case 901 -> List.of(admission(11, 0xA1, owner(0xA0, 10), owner(0xA1, 11)));
                default -> List.of();
            };
            S1GameplayAudioTimeline.OwnerVector frameOwners = switch (frameNumber) {
                case 900 -> owners(owner(0xA0, 10));
                case 901 -> owners(owner(0xA1, 11));
                default -> owners(music());
            };
            S1GameplayAudioTimeline.Frame frame = mutate.apply(new S1GameplayAudioTimeline.Frame(frameNumber,
                    tickOffset + frameNumber - 859L, frameRequests, frameAdmissions, frameOwners));
            requests += frame.requests().size();
            admissions += frame.admissions().size();
            records.add(frame);
        }
        records.add(new S1GameplayAudioTimeline.Terminal(4115, requests, admissions, 4115));
        Path output = temp.resolve(name);
        S1GameplayAudioTimelineJsonl.writeNew(output, metadata(capture), records.iterator());
        return output;
    }

    private static S1GameplayAudioTimeline.Frame with(S1GameplayAudioTimeline.Frame frame,
            List<S1GameplayAudioTimeline.Request> requests, List<S1GameplayAudioTimeline.Admission> admissions,
            S1GameplayAudioTimeline.OwnerVector owners) {
        return new S1GameplayAudioTimeline.Frame(frame.bk2Frame(), frame.diagnosticTick(), requests, admissions, owners);
    }

    private static S1GameplayAudioTimeline.Request request(long ordinal, int rawId) {
        return new S1GameplayAudioTimeline.Request(ordinal, SFX, rawId);
    }

    private static S1GameplayAudioTimeline.Admission admission(long ordinal, int resolvedId,
            S1GameplayAudioTimeline.OwnerRef displaced, S1GameplayAudioTimeline.OwnerRef finalOwner) {
        return admission(ordinal, resolvedId, displaced, finalOwner, true);
    }

    private static S1GameplayAudioTimeline.Admission admission(long ordinal, int resolvedId,
            S1GameplayAudioTimeline.OwnerRef displaced, S1GameplayAudioTimeline.OwnerRef finalOwner,
            boolean acquired) {
        return new S1GameplayAudioTimeline.Admission(ordinal, SFX, resolvedId, List.of(FM3), List.of(
                new S1GameplayAudioTimeline.RoleArbitration(FM3, acquired, displaced, finalOwner)));
    }

    private static S1GameplayAudioTimeline.Metadata metadata(String capture) {
        return new S1GameplayAudioTimeline.Metadata(S1GameplayAudioTimeline.SCHEMA, capture,
                S1GameplayAudioTimeline.S1_REV01_SHA1, S1GameplayAudioTimeline.S1_REV01_CRC32,
                S1GameplayAudioTimeline.BK2_SHA256,
                S1GameplayAudioTimeline.REFERENCE_CAPTURE.equals(capture)
                        ? S1GameplayAudioTimeline.REFERENCE_PRODUCER : S1GameplayAudioTimeline.OPENGGF_PRODUCER,
                860, 4975, 4115);
    }

    private static S1GameplayAudioTimeline.OwnerVector retainedOwners() {
        return owners(owner(0xA0, 10));
    }

    private static S1GameplayAudioTimeline.OwnerVector owners(S1GameplayAudioTimeline.OwnerRef fm3) {
        return new S1GameplayAudioTimeline.OwnerVector(fm3, music(), music(), none(), none(), none());
    }

    private static S1GameplayAudioTimeline.OwnerRef music() {
        return new S1GameplayAudioTimeline.OwnerRef(MUSIC, 0x81, 0);
    }

    private static S1GameplayAudioTimeline.OwnerRef none() {
        return new S1GameplayAudioTimeline.OwnerRef(NONE, 0, -1);
    }

    private static S1GameplayAudioTimeline.OwnerRef owner(int id, long ordinal) {
        return new S1GameplayAudioTimeline.OwnerRef(NORMAL_SFX, id, ordinal);
    }
}
