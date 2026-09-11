package com.openggf.game.rewind.snapshot;

import com.openggf.game.render.SpecialRenderEffect;
import com.openggf.game.render.SpecialRenderEffectStage;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Snapshot of {@link com.openggf.game.render.SpecialRenderEffectRegistry}
 * active-effect list per stage.
 *
 * <p>Effect object references are captured by identity. Effects are stateless
 * unless they implement {@link com.openggf.game.rewind.RewindSnapshottable};
 * stateful effect snapshots are captured alongside the identity list and
 * restored into the same registered effect instance.
 */
public record SpecialRenderEffectSnapshot(
        Map<SpecialRenderEffectStage, List<SpecialRenderEffect>> effectsByStage,
        Map<SpecialRenderEffectStage, List<EffectState>> effectStatesByStage
) {
    private static final Map<SpecialRenderEffectStage, List<SpecialRenderEffect>> EMPTY_EFFECT_MAP =
            immutableEmptyEffectMap();
    private static final Map<SpecialRenderEffectStage, List<EffectState>> EMPTY_STATE_MAP =
            immutableEmptyStateMap();
    private static final SpecialRenderEffectSnapshot EMPTY =
            new SpecialRenderEffectSnapshot(fullEmptyStageMap(), EMPTY_STATE_MAP);

    public record EffectState(int index, String key, Object snapshot) {
    }

    /** Returns the shared immutable snapshot for a registry with no effects. */
    public static SpecialRenderEffectSnapshot empty() {
        return EMPTY;
    }

    public SpecialRenderEffectSnapshot(Map<SpecialRenderEffectStage, List<SpecialRenderEffect>> effectsByStage) {
        this(effectsByStage, Map.of());
    }

    public SpecialRenderEffectSnapshot {
        Objects.requireNonNull(effectsByStage, "effectsByStage");
        Objects.requireNonNull(effectStatesByStage, "effectStatesByStage");
        if (effectsByStage.isEmpty()) {
            effectsByStage = EMPTY_EFFECT_MAP;
        } else {
            EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffect>> effectCopy =
                    new EnumMap<>(SpecialRenderEffectStage.class);
            for (Map.Entry<SpecialRenderEffectStage, List<SpecialRenderEffect>> e
                    : effectsByStage.entrySet()) {
                effectCopy.put(e.getKey(), List.copyOf(e.getValue()));
            }
            effectsByStage = Collections.unmodifiableMap(effectCopy);
        }

        if (effectStatesByStage.isEmpty()) {
            effectStatesByStage = EMPTY_STATE_MAP;
        } else {
            EnumMap<SpecialRenderEffectStage, List<EffectState>> stateCopy =
                    new EnumMap<>(SpecialRenderEffectStage.class);
            for (Map.Entry<SpecialRenderEffectStage, List<EffectState>> e
                    : effectStatesByStage.entrySet()) {
                stateCopy.put(e.getKey(), List.copyOf(e.getValue()));
            }
            effectStatesByStage = Collections.unmodifiableMap(stateCopy);
        }
    }

    private static Map<SpecialRenderEffectStage, List<SpecialRenderEffect>> immutableEmptyEffectMap() {
        return Collections.unmodifiableMap(new EnumMap<>(SpecialRenderEffectStage.class));
    }

    private static Map<SpecialRenderEffectStage, List<EffectState>> immutableEmptyStateMap() {
        return Collections.unmodifiableMap(new EnumMap<>(SpecialRenderEffectStage.class));
    }

    private static Map<SpecialRenderEffectStage, List<SpecialRenderEffect>> fullEmptyStageMap() {
        EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffect>> result =
                new EnumMap<>(SpecialRenderEffectStage.class);
        for (SpecialRenderEffectStage stage : SpecialRenderEffectStage.values()) {
            result.put(stage, List.of());
        }
        return result;
    }
}
