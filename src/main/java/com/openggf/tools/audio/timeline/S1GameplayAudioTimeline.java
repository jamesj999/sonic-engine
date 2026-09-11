package com.openggf.tools.audio.timeline;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Immutable, tooling-only semantic contract for the S1 GHZ1 gameplay-audio timeline. */
public final class S1GameplayAudioTimeline {
    public static final String SCHEMA = "s1_gameplay_audio_timeline.v2";
    public static final String REFERENCE_CAPTURE = "s1_ghz_gameplay_audio_reference";
    public static final String OPENGGF_CAPTURE = "s1_ghz_gameplay_audio_openggf";
    public static final String REFERENCE_PRODUCER = "BizHawk 2.11 / Genesis Plus GX";
    public static final String OPENGGF_PRODUCER = "OpenGGF";
    public static final String S1_REV01_SHA1 = "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b";
    public static final String S1_REV01_CRC32 = "afe05eee";
    public static final String BK2_SHA256 = "f2e817936d07b2b1f2b80d61451f174189509a2817da2b2349ce0e19b8a5567b";
    public static final int SEGMENT_START_BK2_FRAME = 860;
    public static final int SEGMENT_END_BK2_FRAME = 4975;
    public static final int SEGMENT_FRAME_COUNT = 4115;
    public static final int MAX_REQUESTS_PER_FRAME = 64;
    public static final int MAX_ADMISSIONS_PER_FRAME = 64;
    public static final int MAX_ROLES_PER_REQUEST = HardwareRole.values().length;

    private S1GameplayAudioTimeline() {
    }

    public sealed interface TimelineRecord permits Baseline, Frame, Terminal {
    }

    public enum SoundClass { MUSIC, SFX, SPECIAL_SFX, COMMAND }

    public enum OwnerClass { NONE, MUSIC, NORMAL_SFX, SPECIAL_SFX }

    public enum HardwareRole { FM3, FM4, FM5, PSG1, PSG2, PSG3 }

    public record Metadata(String schema, String capture, String romSha1, String romCrc32,
            String bk2Sha256, String producer, int segmentStartBk2Frame,
            int segmentEndBk2Frame, int terminalFrameCount) {
        public Metadata {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(capture, "capture");
            Objects.requireNonNull(romSha1, "romSha1");
            Objects.requireNonNull(romCrc32, "romCrc32");
            Objects.requireNonNull(bk2Sha256, "bk2Sha256");
            Objects.requireNonNull(producer, "producer");
            if (!SCHEMA.equals(schema)
                    || (!REFERENCE_CAPTURE.equals(capture) && !OPENGGF_CAPTURE.equals(capture))
                    || !S1_REV01_SHA1.equals(romSha1.toLowerCase())
                    || !S1_REV01_CRC32.equals(romCrc32.toLowerCase())
                    || !BK2_SHA256.equals(bk2Sha256.toLowerCase())
                    || (REFERENCE_CAPTURE.equals(capture) && !REFERENCE_PRODUCER.equals(producer))
                    || (OPENGGF_CAPTURE.equals(capture) && !OPENGGF_PRODUCER.equals(producer))
                    || segmentStartBk2Frame != SEGMENT_START_BK2_FRAME
                    || segmentEndBk2Frame != SEGMENT_END_BK2_FRAME
                    || terminalFrameCount != SEGMENT_FRAME_COUNT) {
                throw new IllegalArgumentException("metadata does not identify the pinned S1 GHZ1 timeline");
            }
            romSha1 = romSha1.toLowerCase();
            romCrc32 = romCrc32.toLowerCase();
            bk2Sha256 = bk2Sha256.toLowerCase();
        }
    }

    public record Baseline(int bk2Frame, int activeMusicId, Long diagnosticTick, OwnerVector owners)
            implements TimelineRecord {
        public Baseline {
            nonNegative(diagnosticTick, "diagnosticTick");
            Objects.requireNonNull(owners, "owners");
            if (bk2Frame != SEGMENT_START_BK2_FRAME || activeMusicId != 0x81) {
                throw new IllegalArgumentException("baseline must be frame 860 with active music $81");
            }
        }
    }

    public record Frame(int bk2Frame, Long diagnosticTick, List<Request> requests,
            List<Admission> admissions, OwnerVector owners)
            implements TimelineRecord {
        public Frame {
            if (bk2Frame < SEGMENT_START_BK2_FRAME || bk2Frame >= SEGMENT_END_BK2_FRAME) {
                throw new IllegalArgumentException("frame is outside the GHZ1 BK2 interval");
            }
            nonNegative(diagnosticTick, "diagnosticTick");
            requests = List.copyOf(requests);
            admissions = List.copyOf(admissions);
            Objects.requireNonNull(owners, "owners");
            if (requests.size() > MAX_REQUESTS_PER_FRAME) {
                throw new IllegalArgumentException("frame contains too many requests");
            }
            if (admissions.size() > MAX_ADMISSIONS_PER_FRAME) {
                throw new IllegalArgumentException("frame contains too many admissions");
            }
        }
    }

    /** Caller/ROM-queue submission identity before any driver-side transformation. */
    public record Request(long requestOrdinal, SoundClass soundClass, int rawSoundId) {
        public Request {
            if (requestOrdinal <= 0) {
                throw new IllegalArgumentException("requestOrdinal must follow baseline ordinal zero");
            }
            Objects.requireNonNull(soundClass, "soundClass");
            if (!validSoundId(soundClass, rawSoundId)) {
                throw new IllegalArgumentException("request class or raw sound ID is invalid");
            }
        }
    }

    /** Driver/backend admission carrying resolved identity and per-role results. */
    public record Admission(long requestOrdinal, SoundClass soundClass, int soundId,
            List<HardwareRole> requestedRoles, List<RoleArbitration> arbitration) {
        public Admission {
            if (requestOrdinal <= 0) {
                throw new IllegalArgumentException("requestOrdinal must follow baseline ordinal zero");
            }
            Objects.requireNonNull(soundClass, "soundClass");
            requestedRoles = List.copyOf(requestedRoles);
            arbitration = List.copyOf(arbitration);
            if (requestedRoles.isEmpty() || requestedRoles.size() > MAX_ROLES_PER_REQUEST
                    || requestedRoles.size() != EnumSet.copyOf(requestedRoles).size()
                    || soundClass == SoundClass.COMMAND || !validSoundId(soundClass, soundId)) {
                throw new IllegalArgumentException("admission class, sound ID, or requested roles are invalid");
            }
            EnumSet<HardwareRole> arbitrationRoles = EnumSet.noneOf(HardwareRole.class);
            for (RoleArbitration decision : arbitration) {
                if (!requestedRoles.contains(decision.role()) || !arbitrationRoles.add(decision.role())) {
                    throw new IllegalArgumentException("arbitration roles must be unique requested roles");
                }
                if (decision.acquired()) {
                    if (!decision.finalOwner().equals(new OwnerRef(ownerClass(soundClass), soundId, requestOrdinal))) {
                        throw new IllegalArgumentException("acquired role must finish with this request as owner");
                    }
                } else if (!decision.displacedOwner().equals(decision.finalOwner())) {
                    throw new IllegalArgumentException("rejected role must retain its prior owner");
                }
            }
            if (!arbitrationRoles.equals(EnumSet.copyOf(requestedRoles))) {
                throw new IllegalArgumentException("each requested role requires exactly one arbitration decision");
            }
        }
    }

    public record RoleArbitration(HardwareRole role, boolean acquired,
            OwnerRef displacedOwner, OwnerRef finalOwner) {
        public RoleArbitration {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(displacedOwner, "displacedOwner");
            Objects.requireNonNull(finalOwner, "finalOwner");
        }
    }

    public record OwnerVector(OwnerRef fm3, OwnerRef fm4, OwnerRef fm5, OwnerRef psg1,
            OwnerRef psg2, OwnerRef psg3) {
        public OwnerVector {
            Objects.requireNonNull(fm3, "fm3");
            Objects.requireNonNull(fm4, "fm4");
            Objects.requireNonNull(fm5, "fm5");
            Objects.requireNonNull(psg1, "psg1");
            Objects.requireNonNull(psg2, "psg2");
            Objects.requireNonNull(psg3, "psg3");
        }

        public OwnerRef owner(HardwareRole role) {
            return switch (role) {
                case FM3 -> fm3;
                case FM4 -> fm4;
                case FM5 -> fm5;
                case PSG1 -> psg1;
                case PSG2 -> psg2;
                case PSG3 -> psg3;
            };
        }
    }

    public record OwnerRef(OwnerClass ownerClass, int soundId, long requestOrdinal) {
        public OwnerRef {
            Objects.requireNonNull(ownerClass, "ownerClass");
            if (ownerClass == OwnerClass.NONE) {
                if (soundId != 0 || requestOrdinal != -1) {
                    throw new IllegalArgumentException("NONE owner must use sound ID 0 and ordinal -1");
                }
            } else if (!validOwnerSoundId(ownerClass, soundId) || requestOrdinal < 0) {
                throw new IllegalArgumentException("active owner identity is out of range");
            }
        }
    }

    public record Terminal(int frameCount, long requestCount, long admissionCount,
            long diagnosticTickCount)
            implements TimelineRecord {
        public Terminal {
            if (frameCount != SEGMENT_FRAME_COUNT || requestCount < 0 || admissionCount < 0
                    || admissionCount > requestCount || diagnosticTickCount < 0) {
                throw new IllegalArgumentException("terminal counts are invalid");
            }
        }
    }

    private static boolean validSoundId(SoundClass soundClass, int soundId) {
        return switch (soundClass) {
            case MUSIC -> soundId >= 0x81 && soundId <= 0x9f;
            case SFX -> soundId >= 0xa0 && soundId <= 0xcf;
            case SPECIAL_SFX -> soundId >= 0xd0 && soundId <= 0xdf;
            case COMMAND -> soundId >= 0xe0 && soundId <= 0xff;
        };
    }

    private static OwnerClass ownerClass(SoundClass soundClass) {
        return switch (soundClass) {
            case MUSIC -> OwnerClass.MUSIC;
            case SFX -> OwnerClass.NORMAL_SFX;
            case SPECIAL_SFX -> OwnerClass.SPECIAL_SFX;
            case COMMAND -> throw new IllegalArgumentException("commands cannot acquire hardware ownership");
        };
    }

    private static boolean validOwnerSoundId(OwnerClass ownerClass, int soundId) {
        return switch (ownerClass) {
            case NONE -> soundId == 0;
            case MUSIC -> soundId >= 0x81 && soundId <= 0x9f;
            case NORMAL_SFX -> soundId >= 0xa0 && soundId <= 0xcf;
            case SPECIAL_SFX -> soundId >= 0xd0 && soundId <= 0xdf;
        };
    }

    /** Compares semantic records while deliberately excluding diagnostic tick coordinates. */
    public static boolean semanticEquals(TimelineRecord first, TimelineRecord second) {
        if (first == second) {
            return true;
        }
        if (first == null || second == null || first.getClass() != second.getClass()) {
            return false;
        }
        if (first instanceof Baseline left && second instanceof Baseline right) {
            return left.bk2Frame == right.bk2Frame && left.activeMusicId == right.activeMusicId
                    && left.owners.equals(right.owners);
        }
        if (first instanceof Frame left && second instanceof Frame right) {
            return left.bk2Frame == right.bk2Frame && left.requests.equals(right.requests)
                    && left.admissions.equals(right.admissions)
                    && left.owners.equals(right.owners);
        }
        return first.equals(second);
    }

    /** Hashes according to {@link #semanticEquals(TimelineRecord, TimelineRecord)}. */
    public static int semanticHashCode(TimelineRecord record) {
        if (record instanceof Baseline baseline) {
            return Objects.hash(baseline.bk2Frame, baseline.activeMusicId, baseline.owners);
        }
        if (record instanceof Frame frame) {
            return Objects.hash(frame.bk2Frame, frame.requests, frame.admissions, frame.owners);
        }
        return Objects.hashCode(record);
    }

    private static void nonNegative(Long value, String name) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative when present");
        }
    }
}
