package com.openggf.audio.session;

import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.SmpsSequencerConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Source-owned logical song-load policies shared by prepared programs. */
public final class SmpsLogicalTransitionPolicies {
    private static final SmpsLogicalTransitionPolicy PRESERVE_SFX =
            new Policy(true, false);
    private static final SmpsLogicalTransitionPolicy CLEAR_SFX =
            new Policy(false, false);
    private static final SmpsLogicalTransitionPolicy CLEAR_SFX_AND_TEMPO =
            new Policy(false, true);
    private static final SmpsLogicalTransitionPolicy PRESERVE_SFX_RESET_TEMPO =
            new Policy(true, true);

    private SmpsLogicalTransitionPolicies() {
    }

    public static SmpsLogicalTransitionPolicy forConfig(
            SmpsSequencerConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.isResetTempoOnMusicLoad()) {
            return config.isDirect68kDriver()
                    ? PRESERVE_SFX_RESET_TEMPO : CLEAR_SFX_AND_TEMPO;
        }
        return config.isDirect68kDriver()
                ? PRESERVE_SFX : CLEAR_SFX;
    }

    private record Policy(boolean preserveSfx, boolean resetsTempoOnMusicStart)
            implements SmpsLogicalTransitionPolicy {
        @Override
        public Result prepareMusicStart(
                SmpsDriverSnapshot current,
                SmpsDriverSnapshot.SequencerEntry incomingMusic) {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(incomingMusic, "incomingMusic");
            if (incomingMusic.sfx()) {
                throw new IllegalArgumentException(
                        "music transition requires a music entry");
            }
            List<SmpsDriverSnapshot.SequencerEntry> entries =
                    new ArrayList<>();
            entries.add(incomingMusic);
            int[] remap = new int[current.sequencers().size()];
            Arrays.fill(remap, -1);
            if (preserveSfx) {
                for (int index = 0;
                        index < current.sequencers().size(); index++) {
                    SmpsDriverSnapshot.SequencerEntry entry =
                            current.sequencers().get(index);
                    if (entry.sfx()) {
                        remap[index] = entries.size();
                        entries.add(entry);
                    }
                }
            }
            return new Result(new SmpsDriverSnapshot(
                    current.region(), current.readMode(),
                    preserveSfx ? current.continuousSfxId() : 0,
                    preserveSfx && current.continuousSfxFlag(),
                    preserveSfx ? current.contSfxLoopCnt() : 0,
                    current.palUpdateCounter(), entries,
                    remap(current.fmLockSequencerIds(), remap),
                    remap(current.psgLockSequencerIds(), remap)),
                    SmpsWriteProgram.EMPTY);
        }

        @Override
        public Result prepareOverrideRestore(
                SmpsDriverSnapshot current,
                SmpsDriverSnapshot saved) {
            Objects.requireNonNull(current, "current");
            return new Result(
                    Objects.requireNonNull(saved, "saved"),
                    SmpsWriteProgram.EMPTY);
        }

        private static int[] remap(int[] locks, int[] remap) {
            int[] result = new int[locks.length];
            Arrays.fill(result, -1);
            for (int index = 0; index < locks.length; index++) {
                int prior = locks[index];
                if (prior >= 0 && prior < remap.length) {
                    result[index] = remap[prior];
                }
            }
            return result;
        }
    }
}
