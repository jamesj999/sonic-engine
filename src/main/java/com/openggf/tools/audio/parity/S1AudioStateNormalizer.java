package com.openggf.tools.audio.parity;

import com.openggf.audio.rewind.SmpsSequencerSnapshot;
import com.openggf.audio.rewind.SmpsTrackSnapshot;
import com.openggf.audio.smps.SmpsSequencer.Region;
import com.openggf.audio.smps.SmpsSequencer.TrackType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Converts an OpenGGF Sonic 1 sequencer snapshot to the versioned gating state. */
public final class S1AudioStateNormalizer {
    private S1AudioStateNormalizer() {
    }

    public record GhzAssetRange(long romBase, long romEndExclusive) {
        public GhzAssetRange {
            if (romBase < 0 || romEndExclusive > 0xffff_ffffL || romBase >= romEndExclusive) {
                throw new IllegalArgumentException("GHZ asset range must be a positive unsigned-32-bit range");
            }
        }

        public int length() {
            long length = romEndExclusive - romBase;
            if (length > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("GHZ asset range is too large for sequencer coordinates");
            }
            return (int) length;
        }
    }

    public record NormalizedState(AudioParityTick.GlobalState global,
            List<AudioParityTrackState> tracks) {
        public NormalizedState {
            Objects.requireNonNull(global, "global");
            tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        }
    }

    public static NormalizedState normalize(SmpsSequencerSnapshot snapshot, GhzAssetRange assetRange,
            Set<Integer> parsedF7LoopIndices) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(assetRange, "assetRange");
        Objects.requireNonNull(parsedF7LoopIndices, "parsedF7LoopIndices");
        if (snapshot.region() != Region.NTSC || snapshot.sfxMode()) {
            throw new IllegalArgumentException("S1 GHZ parity requires an NTSC music sequencer snapshot");
        }

        List<Integer> loopIndices = validatedLoopIndices(parsedF7LoopIndices);
        Map<String, SmpsTrackSnapshot> byRole = new HashMap<>();
        for (SmpsTrackSnapshot track : snapshot.tracks()) {
            String role = role(track);
            if (byRole.putIfAbsent(role, track) != null) {
                throw new IllegalArgumentException("duplicate S1 music hardware role " + role);
            }
        }

        List<AudioParityTrackState> tracks = new ArrayList<>(AudioParitySchema.ROLES.size());
        for (String role : AudioParitySchema.ROLES) {
            SmpsTrackSnapshot track = byRole.get(role);
            tracks.add(track == null || !track.active()
                    ? AudioParityTrackState.inactive(role)
                    : normalizeActive(role, track, assetRange.length(), loopIndices));
        }
        return new NormalizedState(normalizeGlobal(snapshot), tracks);
    }

    private static AudioParityTick.GlobalState normalizeGlobal(SmpsSequencerSnapshot snapshot) {
        int tempoReload = unsignedByte(snapshot.tempoWeight(), "tempoWeight");
        int tempoTimeout = unsignedByte(snapshot.tempoAccumulator(), "tempoAccumulator");
        SmpsSequencerSnapshot.FadeSnapshot fade = snapshot.fade();
        boolean active = fade.active();
        return new AudioParityTick.GlobalState(active,
                active ? (fade.fadeOut() ? "out" : "in") : "none",
                active ? unsignedByte(fade.delayCounter(), "fade.delayCounter") : null,
                active ? unsignedByte(fade.steps(), "fade.steps") : null,
                snapshot.speedShoes(), tempoReload, tempoTimeout);
    }

    private static AudioParityTrackState normalizeActive(String role, SmpsTrackSnapshot track,
            int assetLength, List<Integer> loopIndices) {
        int position = position(track.pos(), assetLength, false, "sequence position");
        List<Integer> loops = new ArrayList<>(loopIndices.size());
        int[] sourceLoops = track.loopCounters();
        for (int index : loopIndices) {
            loops.add(index < sourceLoops.length ? unsignedByte(sourceLoops[index], "loop counter") : 0);
        }

        int returnSp = track.returnSp();
        int[] sourceStack = track.returnStack();
        if (returnSp < 0 || returnSp > sourceStack.length) {
            throw new IllegalArgumentException("return stack cursor exceeds supplied stack");
        }
        List<Long> stack = new ArrayList<>(returnSp);
        for (int index = 0; index < returnSp; index++) {
            stack.add((long) position(sourceStack[index], assetLength, true, "return stack entry"));
        }

        boolean psg = role.startsWith("PSG");
        Integer baseFrequency;
        if (role.equals("DAC")) {
            baseFrequency = null;
        } else if (psg) {
            baseFrequency = unsignedWord(track.baseFnum(), "PSG baseFnum");
        } else {
            int block = range(track.baseBlock(), 0, 7, "FM baseBlock");
            int fnum = range(track.baseFnum(), 0, 0x7ff, "FM baseFnum");
            baseFrequency = (block << 11) | fnum;
        }

        Integer pan = null;
        Integer ams = null;
        Integer fms = null;
        Integer envelopeCursor = null;
        if (psg) {
            envelopeCursor = unsignedByte(track.envPos(), "PSG envelope cursor");
        } else {
            pan = range(track.pan(), 0, 0xff, "pan");
            ams = range(track.ams(), 0, 3, "AMS");
            fms = range(track.fms(), 0, 7, "FMS");
        }

        return new AudioParityTrackState(role, AudioParitySchema.HARDWARE_BY_ROLE.get(role), true,
                baseFrequency, signedByte(track.detune()), track.tieNext(),
                unsignedByte(track.duration(), "duration"),
                unsignedByte(track.scaledDuration(), "scaledDuration"), envelopeCursor,
                loops, track.modEnabled(), track.overridden(), pan, ams, fms, stack, position,
                signedByte(track.keyOffset()),
                unsignedByte(psg ? track.instrumentId() : track.voiceId(), "voice/envelope id"),
                signedByte(track.volumeOffset()));
    }

    private static String role(SmpsTrackSnapshot track) {
        TrackType type = Objects.requireNonNull(track.type(), "track type");
        return switch (type) {
            case DAC -> {
                if (track.channelId() != 5) {
                    throw new IllegalArgumentException("S1 DAC track must own the FM6 hardware slot");
                }
                yield "DAC";
            }
            case FM -> {
                if (track.channelId() < 0 || track.channelId() > 5) {
                    throw new IllegalArgumentException("S1 FM channel id is out of range");
                }
                yield "FM" + (track.channelId() + 1);
            }
            case PSG -> {
                if (track.channelId() < 0 || track.channelId() > 2) {
                    throw new IllegalArgumentException("S1 PSG channel id is out of range");
                }
                yield "PSG" + (track.channelId() + 1);
            }
        };
    }

    private static List<Integer> validatedLoopIndices(Set<Integer> indices) {
        TreeSet<Integer> sorted = new TreeSet<>();
        for (Integer index : indices) {
            if (index == null || index < 0 || index > 0xff) {
                throw new IllegalArgumentException("parsed $F7 loop index must be an unsigned byte");
            }
            sorted.add(index);
        }
        return List.copyOf(sorted);
    }

    private static int position(int value, int assetLength, boolean allowEnd, String field) {
        int maximum = allowEnd ? assetLength : assetLength - 1;
        return range(value, 0, maximum, field);
    }

    private static int signedByte(int value) {
        return (byte) value;
    }

    private static int unsignedByte(int value, String field) {
        return range(value, 0, 0xff, field);
    }

    private static int unsignedWord(int value, String field) {
        return range(value, 0, 0xffff, field);
    }

    private static int range(int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return value;
    }
}
