package uk.co.jamesj999.sonic.audio.smps;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class SmpsSequencerConfig {
    private final Map<Integer, Integer> speedUpTempos;
    private final int tempoModBase;
    private final int[] fmChannelOrder;
    private final int[] psgChannelOrder;

    public SmpsSequencerConfig(
            Map<Integer, Integer> speedUpTempos,
            int tempoModBase,
            int[] fmChannelOrder,
            int[] psgChannelOrder) {
        Objects.requireNonNull(speedUpTempos, "speedUpTempos");
        Objects.requireNonNull(fmChannelOrder, "fmChannelOrder");
        Objects.requireNonNull(psgChannelOrder, "psgChannelOrder");
        this.speedUpTempos = Collections.unmodifiableMap(new HashMap<>(speedUpTempos));
        this.tempoModBase = tempoModBase;
        this.fmChannelOrder = Arrays.copyOf(fmChannelOrder, fmChannelOrder.length);
        this.psgChannelOrder = Arrays.copyOf(psgChannelOrder, psgChannelOrder.length);
    }

    public Map<Integer, Integer> getSpeedUpTempos() {
        return speedUpTempos;
    }

    public int getTempoModBase() {
        return tempoModBase;
    }

    public int[] getFmChannelOrder() {
        return Arrays.copyOf(fmChannelOrder, fmChannelOrder.length);
    }

    public int[] getPsgChannelOrder() {
        return Arrays.copyOf(psgChannelOrder, psgChannelOrder.length);
    }
}
