package com.openggf.tools.audio.timeline;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Validates both pinned streams before a bounded, order-preserving semantic comparison. */
public final class S1GameplayAudioTimelineComparator {
    private static final int CONTEXT_EACH_SIDE = 8;

    private S1GameplayAudioTimelineComparator() {
    }

    public static S1GameplayAudioTimelineReport compare(Path reference, Path openGgf) {
        return compare(reference, openGgf, () -> { });
    }

    /* Package-visible only so the source-change contract can be tested without a tool command seam. */
    static S1GameplayAudioTimelineReport compare(Path reference, Path openGgf, Runnable afterValidation) {
        Fingerprint referenceFingerprint;
        Fingerprint openGgfFingerprint;
        try {
            referenceFingerprint = validateAndFingerprint(reference, "reference");
            openGgfFingerprint = validateAndFingerprint(openGgf, "OpenGGF");
        } catch (CaptureException failure) {
            return S1GameplayAudioTimelineReport.failure(S1GameplayAudioTimelineReport.Kind.CAPTURE_FAILURE,
                    failure.side + " validation", failure.getMessage(), List.of(), List.of());
        }
        afterValidation.run();
        try {
            return compareValidated(referenceFingerprint, openGgfFingerprint);
        } catch (CaptureException failure) {
            return S1GameplayAudioTimelineReport.failure(S1GameplayAudioTimelineReport.Kind.CAPTURE_FAILURE,
                    failure.side + " comparison", failure.getMessage(), List.of(), List.of());
        }
    }

    private static S1GameplayAudioTimelineReport compareValidated(Fingerprint reference, Fingerprint openGgf) {
        try (ReadPass leftPass = ReadPass.open(reference.path, "reference");
                ReadPass rightPass = ReadPass.open(openGgf.path, "OpenGGF")) {
            S1GameplayAudioTimelineJsonl.Reader left = leftPass.reader;
            S1GameplayAudioTimelineJsonl.Reader right = rightPass.reader;
            Context context = new Context();
            ContentionEvidence referenceEvidence = new ContentionEvidence();
            ContentionEvidence openGgfEvidence = new ContentionEvidence();
            S1GameplayAudioTimelineReport first = sameComparableMetadata(left.metadata(), right.metadata()) ? null
                    : S1GameplayAudioTimelineReport.failure(S1GameplayAudioTimelineReport.Kind.METADATA_MISMATCH,
                            "metadata", "pinned schema, ROM, BK2, or segment metadata differs", List.of(), List.of());
            while (true) {
                S1GameplayAudioTimeline.TimelineRecord leftRecord = next(left, "reference");
                S1GameplayAudioTimeline.TimelineRecord rightRecord = next(right, "OpenGGF");
                if (leftRecord == null && rightRecord == null) {
                    break;
                }
                referenceEvidence.accept(leftRecord);
                openGgfEvidence.accept(rightRecord);
                if (first == null) {
                    first = difference(leftRecord, rightRecord, context.beforeLeft(), context.beforeRight());
                }
                context.accept(leftRecord, rightRecord, first != null);
            }
            boolean referenceStable = leftPass.matches(reference.digest);
            boolean openGgfStable = rightPass.matches(openGgf.digest);
            if (!referenceStable || !openGgfStable) {
                String side = referenceStable ? "OpenGGF" : "reference";
                return S1GameplayAudioTimelineReport.failure(S1GameplayAudioTimelineReport.Kind.CAPTURE_FAILURE,
                        side + " comparison", side + " source changed during comparison", List.of(), List.of());
            }
            if (!referenceEvidence.complete() || !openGgfEvidence.complete()) {
                String side = !referenceEvidence.complete() ? "reference" : "OpenGGF";
                return S1GameplayAudioTimelineReport.failure(S1GameplayAudioTimelineReport.Kind.CAPTURE_FAILURE,
                        side + " contention coverage", side + " stream lacks required music/SFX takeover-and-restore or SFX/SFX contention",
                        List.of(), List.of());
            }
            return first == null ? S1GameplayAudioTimelineReport.match()
                    : S1GameplayAudioTimelineReport.failure(first.kind(), first.location(), first.detail(),
                            context.left(), context.right());
        } catch (CaptureException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CaptureException("comparison input no longer passes strict validation: " + failure.getMessage(),
                    "stream", failure);
        }
    }

    private static S1GameplayAudioTimeline.TimelineRecord next(S1GameplayAudioTimelineJsonl.Reader reader, String side) {
        try {
            return reader.hasNext() ? reader.next() : null;
        } catch (RuntimeException failure) {
            throw new CaptureException(side + " stream became malformed during comparison: " + failure.getMessage(), side, failure);
        }
    }

    private static S1GameplayAudioTimelineReport difference(S1GameplayAudioTimeline.TimelineRecord left,
            S1GameplayAudioTimeline.TimelineRecord right, List<String> beforeLeft, List<String> beforeRight) {
        if (left == null || right == null) {
            return report(left == null ? S1GameplayAudioTimelineReport.Kind.REQUEST_EXTRA
                    : S1GameplayAudioTimelineReport.Kind.REQUEST_MISSING, "stream length",
                    "one validated stream ended before the other", beforeLeft, beforeRight);
        }
        if (left instanceof S1GameplayAudioTimeline.Baseline baselineLeft
                && right instanceof S1GameplayAudioTimeline.Baseline baselineRight) {
            if (baselineLeft.activeMusicId() != baselineRight.activeMusicId() || !baselineLeft.owners().equals(baselineRight.owners())) {
                return report(S1GameplayAudioTimelineReport.Kind.BASELINE_MISMATCH, "baseline frame 860",
                        "active music or initial ownership differs", beforeLeft, beforeRight);
            }
            return null;
        }
        if (left instanceof S1GameplayAudioTimeline.Frame frameLeft && right instanceof S1GameplayAudioTimeline.Frame frameRight) {
            if (frameLeft.bk2Frame() != frameRight.bk2Frame()) {
                return report(S1GameplayAudioTimelineReport.Kind.REQUEST_ORDINAL_MISMATCH, "frame alignment",
                        "validated BK2 frame coordinates differ without realignment", beforeLeft, beforeRight);
            }
            S1GameplayAudioTimelineReport requestDifference = requests(frameLeft, frameRight, beforeLeft, beforeRight);
            if (requestDifference != null) {
                return requestDifference;
            }
            S1GameplayAudioTimelineReport admissionDifference = admissions(frameLeft, frameRight, beforeLeft, beforeRight);
            if (admissionDifference != null) {
                return admissionDifference;
            }
            if (!frameLeft.owners().equals(frameRight.owners())) {
                return report(restorationDifference(frameLeft.owners(), frameRight.owners())
                                ? S1GameplayAudioTimelineReport.Kind.RESTORATION_MISMATCH
                                : S1GameplayAudioTimelineReport.Kind.FINAL_OWNER_MISMATCH,
                        "frame " + frameLeft.bk2Frame() + " final owners",
                        "final per-role owner vector differs", beforeLeft, beforeRight);
            }
            return null;
        }
        if (left instanceof S1GameplayAudioTimeline.Terminal terminalLeft
                && right instanceof S1GameplayAudioTimeline.Terminal terminalRight) {
            if (terminalLeft.frameCount() != terminalRight.frameCount()
                    || terminalLeft.requestCount() != terminalRight.requestCount()
                    || terminalLeft.admissionCount() != terminalRight.admissionCount()) {
                return report(S1GameplayAudioTimelineReport.Kind.TERMINAL_MISMATCH, "terminal",
                        "semantic terminal counts differ", beforeLeft, beforeRight);
            }
            return null; // diagnostic tick count deliberately does not gate semantic equality.
        }
        return report(S1GameplayAudioTimelineReport.Kind.CAPTURE_FAILURE, "record shape",
                "validated streams contain incompatible record types", beforeLeft, beforeRight);
    }

    private static S1GameplayAudioTimelineReport requests(S1GameplayAudioTimeline.Frame left,
            S1GameplayAudioTimeline.Frame right, List<String> beforeLeft, List<String> beforeRight) {
        int maximum = Math.max(left.requests().size(), right.requests().size());
        for (int index = 0; index < maximum; index++) {
            if (index == left.requests().size()) {
                return report(S1GameplayAudioTimelineReport.Kind.REQUEST_EXTRA, location(left, index),
                        "OpenGGF emitted an extra ordered request", beforeLeft, beforeRight);
            }
            if (index == right.requests().size()) {
                return report(S1GameplayAudioTimelineReport.Kind.REQUEST_MISSING, location(left, index),
                        "OpenGGF omitted an ordered reference request", beforeLeft, beforeRight);
            }
            S1GameplayAudioTimeline.Request expected = left.requests().get(index);
            S1GameplayAudioTimeline.Request actual = right.requests().get(index);
            if (expected.requestOrdinal() != actual.requestOrdinal()) {
                return report(S1GameplayAudioTimelineReport.Kind.REQUEST_ORDINAL_MISMATCH, location(left, index),
                        "request ordinal differs", beforeLeft, beforeRight);
            }
            if (expected.soundClass() != actual.soundClass()) {
                return report(S1GameplayAudioTimelineReport.Kind.REQUEST_CLASS_MISMATCH, location(left, index),
                        "sound class differs", beforeLeft, beforeRight);
            }
            if (expected.rawSoundId() != actual.rawSoundId()) {
                return report(S1GameplayAudioTimelineReport.Kind.REQUEST_ID_MISMATCH, location(left, index),
                        "raw caller/queue sound ID differs", beforeLeft, beforeRight);
            }
        }
        return null;
    }

    private static S1GameplayAudioTimelineReport admissions(S1GameplayAudioTimeline.Frame left,
            S1GameplayAudioTimeline.Frame right, List<String> beforeLeft, List<String> beforeRight) {
        int maximum = Math.max(left.admissions().size(), right.admissions().size());
        for (int index = 0; index < maximum; index++) {
            if (index == left.admissions().size()) {
                return report(S1GameplayAudioTimelineReport.Kind.ADMISSION_EXTRA, admissionLocation(left, index),
                        "OpenGGF admitted an extra ordered request at this frame boundary", beforeLeft, beforeRight);
            }
            if (index == right.admissions().size()) {
                return report(S1GameplayAudioTimelineReport.Kind.ADMISSION_MISSING, admissionLocation(left, index),
                        "OpenGGF omitted an ordered reference admission at this frame boundary", beforeLeft, beforeRight);
            }
            S1GameplayAudioTimeline.Admission expected = left.admissions().get(index);
            S1GameplayAudioTimeline.Admission actual = right.admissions().get(index);
            if (expected.requestOrdinal() != actual.requestOrdinal()) {
                return report(S1GameplayAudioTimelineReport.Kind.ADMISSION_ORDINAL_MISMATCH,
                        admissionLocation(left, index), "admission request ordinal differs", beforeLeft, beforeRight);
            }
            if (expected.soundClass() != actual.soundClass()) {
                return report(S1GameplayAudioTimelineReport.Kind.ADMISSION_CLASS_MISMATCH,
                        admissionLocation(left, index), "admission sound class differs", beforeLeft, beforeRight);
            }
            if (expected.soundId() != actual.soundId()) {
                return report(S1GameplayAudioTimelineReport.Kind.ADMISSION_ID_MISMATCH,
                        admissionLocation(left, index), "resolved/admitted sound ID differs", beforeLeft, beforeRight);
            }
            if (!expected.requestedRoles().equals(actual.requestedRoles())) {
                return report(S1GameplayAudioTimelineReport.Kind.REQUEST_ROLE_MISMATCH, admissionLocation(left, index),
                        "requested hardware roles differ", beforeLeft, beforeRight);
            }
            for (int role = 0; role < expected.arbitration().size(); role++) {
                S1GameplayAudioTimeline.RoleArbitration expectedDecision = expected.arbitration().get(role);
                S1GameplayAudioTimeline.RoleArbitration actualDecision = actual.arbitration().get(role);
                if (expectedDecision.role() != actualDecision.role()) {
                    return report(S1GameplayAudioTimelineReport.Kind.ROLE_ORDER_MISMATCH, admissionLocation(left, index),
                            "arbitration role order differs", beforeLeft, beforeRight);
                }
                if (expectedDecision.acquired() != actualDecision.acquired()) {
                    return report(S1GameplayAudioTimelineReport.Kind.ROLE_ACQUIRED_MISMATCH, admissionLocation(left, index),
                            "role " + expectedDecision.role() + " acquisition differs", beforeLeft, beforeRight);
                }
                if (!expectedDecision.displacedOwner().equals(actualDecision.displacedOwner())) {
                    return report(S1GameplayAudioTimelineReport.Kind.ROLE_DISPLACED_OWNER_MISMATCH, admissionLocation(left, index),
                            "role " + expectedDecision.role() + " displaced owner differs", beforeLeft, beforeRight);
                }
                if (!expectedDecision.finalOwner().equals(actualDecision.finalOwner())) {
                    return report(S1GameplayAudioTimelineReport.Kind.ROLE_FINAL_OWNER_MISMATCH, admissionLocation(left, index),
                            "role " + expectedDecision.role() + " final owner differs", beforeLeft, beforeRight);
                }
            }
        }
        return null;
    }

    private static boolean restorationDifference(S1GameplayAudioTimeline.OwnerVector expected,
            S1GameplayAudioTimeline.OwnerVector actual) {
        for (S1GameplayAudioTimeline.HardwareRole role : S1GameplayAudioTimeline.HardwareRole.values()) {
            if (expected.owner(role).ownerClass() == S1GameplayAudioTimeline.OwnerClass.MUSIC
                    && actual.owner(role).ownerClass() != S1GameplayAudioTimeline.OwnerClass.MUSIC) {
                return true;
            }
        }
        return false;
    }

    private static String location(S1GameplayAudioTimeline.Frame frame, int request) {
        return "frame " + frame.bk2Frame() + " request " + request;
    }

    private static String admissionLocation(S1GameplayAudioTimeline.Frame frame, int admission) {
        return "frame " + frame.bk2Frame() + " admission " + admission;
    }

    private static S1GameplayAudioTimelineReport report(S1GameplayAudioTimelineReport.Kind kind, String location,
            String detail, List<String> beforeLeft, List<String> beforeRight) {
        return S1GameplayAudioTimelineReport.failure(kind, location, detail, beforeLeft, beforeRight);
    }

    private static boolean sameComparableMetadata(S1GameplayAudioTimeline.Metadata left,
            S1GameplayAudioTimeline.Metadata right) {
        return S1GameplayAudioTimeline.REFERENCE_CAPTURE.equals(left.capture())
                && S1GameplayAudioTimeline.OPENGGF_CAPTURE.equals(right.capture())
                && left.schema().equals(right.schema()) && left.romSha1().equals(right.romSha1())
                && left.romCrc32().equals(right.romCrc32()) && left.bk2Sha256().equals(right.bk2Sha256())
                && left.segmentStartBk2Frame() == right.segmentStartBk2Frame()
                && left.segmentEndBk2Frame() == right.segmentEndBk2Frame()
                && left.terminalFrameCount() == right.terminalFrameCount();
    }

    private static Fingerprint validateAndFingerprint(Path path, String side) {
        try (ReadPass pass = ReadPass.open(path, side)) {
            while (pass.reader.hasNext()) {
                pass.reader.next();
            }
            return new Fingerprint(path, pass.digest(), side);
        } catch (CaptureException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new CaptureException(side + " stream is malformed or incomplete: " + failure.getMessage(), side, failure);
        }
    }

    private record Fingerprint(Path path, byte[] digest, String side) { }

    private static final class ReadPass implements AutoCloseable {
        private final DigestInputStream input;
        private final S1GameplayAudioTimelineJsonl.Reader reader;
        private final MessageDigest digest;

        private ReadPass(DigestInputStream input, S1GameplayAudioTimelineJsonl.Reader reader, MessageDigest digest) {
            this.input = input;
            this.reader = reader;
            this.digest = digest;
        }

        private static ReadPass open(Path path, String side) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                DigestInputStream input = new DigestInputStream(Files.newInputStream(path), digest);
                return new ReadPass(input, S1GameplayAudioTimelineJsonl.read(input), digest);
            } catch (IOException | NoSuchAlgorithmException | RuntimeException failure) {
                throw new CaptureException(side + " stream cannot be opened: " + failure.getMessage(), side, failure);
            }
        }

        private byte[] digest() { return digest.digest(); }
        private boolean matches(byte[] expected) { return MessageDigest.isEqual(expected, digest.digest()); }
        @Override public void close() { reader.close(); }
    }

    private static final class Context {
        private final Deque<String> beforeLeft = new ArrayDeque<>();
        private final Deque<String> beforeRight = new ArrayDeque<>();
        private final List<String> left = new ArrayList<>();
        private final List<String> right = new ArrayList<>();
        private boolean mismatchSeen;
        private int remainingAfter;

        private List<String> beforeLeft() {
            return List.copyOf(beforeLeft);
        }

        private List<String> beforeRight() {
            return List.copyOf(beforeRight);
        }

        private void accept(S1GameplayAudioTimeline.TimelineRecord leftRecord,
                S1GameplayAudioTimeline.TimelineRecord rightRecord, boolean mismatch) {
            if (!mismatchSeen && mismatch) {
                mismatchSeen = true;
                remainingAfter = CONTEXT_EACH_SIDE;
                left.addAll(beforeLeft);
                right.addAll(beforeRight);
            }
            if (mismatchSeen) {
                if (remainingAfter >= 0) {
                    addBounded(left, describe(leftRecord), Integer.MAX_VALUE);
                    addBounded(right, describe(rightRecord), Integer.MAX_VALUE);
                    remainingAfter--;
                }
            } else {
                addBounded(beforeLeft, describe(leftRecord), CONTEXT_EACH_SIDE);
                addBounded(beforeRight, describe(rightRecord), CONTEXT_EACH_SIDE);
            }
        }

        private List<String> left() {
            return List.copyOf(left);
        }

        private List<String> right() {
            return List.copyOf(right);
        }

        private static void addBounded(java.util.Collection<String> values, String value, int maximum) {
            if (value == null) {
                return;
            }
            if (values instanceof Deque<String> rolling) {
                while (rolling.size() >= maximum) {
                    rolling.removeFirst();
                }
                rolling.addLast(value);
                return;
            }
            if (values.size() >= maximum) {
                return;
            }
            values.add(value);
        }

        private static String describe(S1GameplayAudioTimeline.TimelineRecord record) {
            if (record instanceof S1GameplayAudioTimeline.Baseline) {
                return "baseline frame 860";
            }
            if (record instanceof S1GameplayAudioTimeline.Frame frame) {
                return "frame " + frame.bk2Frame() + " requests=" + frame.requests().size()
                        + " admissions=" + frame.admissions().size();
            }
            if (record instanceof S1GameplayAudioTimeline.Terminal terminal) {
                return "terminal frames=" + terminal.frameCount() + " requests=" + terminal.requestCount();
            }
            return null;
        }
    }

    private static final class ContentionEvidence {
        private final java.util.EnumMap<S1GameplayAudioTimeline.HardwareRole, S1GameplayAudioTimeline.OwnerRef> musicTaken =
                new java.util.EnumMap<>(S1GameplayAudioTimeline.HardwareRole.class);
        private boolean musicRestored;
        private boolean sfxContention;

        private void accept(S1GameplayAudioTimeline.TimelineRecord record) {
            if (!(record instanceof S1GameplayAudioTimeline.Frame frame)) {
                return;
            }
            for (S1GameplayAudioTimeline.Admission admission : frame.admissions()) {
                for (S1GameplayAudioTimeline.RoleArbitration decision : admission.arbitration()) {
                    if (decision.acquired()
                            && decision.displacedOwner().ownerClass() == S1GameplayAudioTimeline.OwnerClass.MUSIC
                            && decision.finalOwner().ownerClass() != S1GameplayAudioTimeline.OwnerClass.MUSIC) {
                        musicTaken.put(decision.role(), decision.displacedOwner());
                    }
                    if ((admission.soundClass() == S1GameplayAudioTimeline.SoundClass.SFX
                            || admission.soundClass() == S1GameplayAudioTimeline.SoundClass.SPECIAL_SFX)
                            && (decision.displacedOwner().ownerClass() == S1GameplayAudioTimeline.OwnerClass.NORMAL_SFX
                            || decision.displacedOwner().ownerClass() == S1GameplayAudioTimeline.OwnerClass.SPECIAL_SFX)
                            && !decision.displacedOwner().equals(new S1GameplayAudioTimeline.OwnerRef(
                                    ownerClass(admission.soundClass()), admission.soundId(), admission.requestOrdinal()))) {
                        sfxContention = true;
                    }
                }
            }
            for (var entry : musicTaken.entrySet()) {
                if (frame.owners().owner(entry.getKey()).equals(entry.getValue())) {
                    musicRestored = true;
                }
            }
        }

        private boolean complete() {
            return musicRestored && sfxContention;
        }

        private static S1GameplayAudioTimeline.OwnerClass ownerClass(S1GameplayAudioTimeline.SoundClass soundClass) {
            return soundClass == S1GameplayAudioTimeline.SoundClass.SPECIAL_SFX
                    ? S1GameplayAudioTimeline.OwnerClass.SPECIAL_SFX : S1GameplayAudioTimeline.OwnerClass.NORMAL_SFX;
        }
    }

    private static final class CaptureException extends RuntimeException {
        private final String side;

        private CaptureException(String message, String side) {
            super(message);
            this.side = side;
        }

        private CaptureException(String message, String side, Throwable cause) {
            super(message, cause);
            this.side = side;
        }
    }
}
