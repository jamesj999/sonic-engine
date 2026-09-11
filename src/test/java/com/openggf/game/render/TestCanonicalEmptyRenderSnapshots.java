package com.openggf.game.render;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.AdvancedRenderModeSnapshot;
import com.openggf.game.rewind.snapshot.SpecialRenderEffectSnapshot;
import com.sun.management.ThreadMXBean;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCanonicalEmptyRenderSnapshots {
    private static volatile Object snapshotSink;
    private static volatile Object legacySnapshotSink;

    @Test
    void emptyCapturesReuseCanonicalSnapshotsByIdentity() {
        SpecialRenderEffectRegistry effects = new SpecialRenderEffectRegistry();
        AdvancedRenderModeController modes = new AdvancedRenderModeController();

        assertSame(effects.capture(), effects.capture());
        assertSame(new SpecialRenderEffectRegistry().capture(), effects.capture());
        assertSame(modes.capture(), modes.capture());
        assertSame(new AdvancedRenderModeController().capture(), modes.capture());
    }

    @Test
    void canonicalSpecialSnapshotPreservesFullStageAndEnumMapCompatibility() {
        SpecialRenderEffectSnapshot snapshot = new SpecialRenderEffectRegistry().capture();

        assertEquals(EnumSet.allOf(SpecialRenderEffectStage.class),
                snapshot.effectsByStage().keySet());
        for (SpecialRenderEffectStage stage : SpecialRenderEffectStage.values()) {
            assertTrue(snapshot.effectsByStage().containsKey(stage));
            assertTrue(snapshot.effectsByStage().get(stage).isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> snapshot.effectsByStage().get(stage).add(
                            new StatelessEffect("x", stage)));
        }
        assertEquals(null, snapshot.effectsByStage().get(null));
        assertFalse(snapshot.effectsByStage().containsKey(null));
        assertEquals(null, snapshot.effectStatesByStage().get(null));
        assertFalse(snapshot.effectStatesByStage().containsKey(null));

        SpecialRenderEffectSnapshot publicEmpty =
                new SpecialRenderEffectSnapshot(Map.of(), Map.of());
        assertTrue(publicEmpty.effectsByStage().isEmpty());
        assertTrue(publicEmpty.effectStatesByStage().isEmpty());
        assertEquals(null, publicEmpty.effectsByStage().get(null));
        assertFalse(publicEmpty.effectsByStage().containsKey(null));
        assertEquals(null, publicEmpty.effectStatesByStage().get(null));
        assertFalse(publicEmpty.effectStatesByStage().containsKey(null));
        assertThrows(UnsupportedOperationException.class,
                () -> publicEmpty.effectsByStage().put(
                        SpecialRenderEffectStage.AFTER_BACKGROUND, List.of()));
    }

    @Test
    void registeredStatelessContributorsNeverUseCanonicalEmptySnapshot() {
        SpecialRenderEffectRegistry effects = new SpecialRenderEffectRegistry();
        SpecialRenderEffectSnapshot emptyEffects = effects.capture();
        effects.register(new StatelessEffect("effect", SpecialRenderEffectStage.AFTER_BACKGROUND));
        SpecialRenderEffectSnapshot registeredEffects = effects.capture();

        AdvancedRenderModeController modes = new AdvancedRenderModeController();
        AdvancedRenderModeSnapshot emptyModes = modes.capture();
        modes.register(new StatelessMode("mode"));
        AdvancedRenderModeSnapshot registeredModes = modes.capture();

        assertNotSame(emptyEffects, registeredEffects);
        assertEquals(1, registeredEffects.effectsByStage()
                .get(SpecialRenderEffectStage.AFTER_BACKGROUND).size());
        assertTrue(registeredEffects.effectStatesByStage().isEmpty());
        assertNotSame(emptyModes, registeredModes);
        assertEquals(1, registeredModes.modes().size());
        assertTrue(registeredModes.modeStates().isEmpty());
    }

    @Test
    void restoringOldEmptySnapshotsClearsContributorsRegisteredLater() {
        SpecialRenderEffectRegistry effects = new SpecialRenderEffectRegistry();
        SpecialRenderEffectSnapshot emptyEffects = effects.capture();
        effects.register(new StatefulEffect(
                "late-effect", SpecialRenderEffectStage.AFTER_FOREGROUND, 7));
        effects.restore(emptyEffects);

        AdvancedRenderModeController modes = new AdvancedRenderModeController();
        AdvancedRenderModeSnapshot emptyModes = modes.capture();
        modes.register(new StatefulMode("late-mode", 9));
        modes.restore(emptyModes);

        assertTrue(effects.isEmpty());
        assertTrue(modes.isEmpty());
    }

    @Test
    void nullRestoreDoesNotMutateEitherRegistry() {
        SpecialRenderEffectRegistry effects = new SpecialRenderEffectRegistry();
        StatelessEffect background = new StatelessEffect(
                "background", SpecialRenderEffectStage.AFTER_BACKGROUND);
        StatelessEffect foreground = new StatelessEffect(
                "foreground", SpecialRenderEffectStage.AFTER_FOREGROUND);
        effects.register(background);
        effects.register(foreground);

        assertThrows(NullPointerException.class, () -> effects.restore(null));

        assertEquals(2, effects.activeEffectCount());
        SpecialRenderEffectSnapshot effectSnapshot = effects.capture();
        assertEquals(List.of(background), effectSnapshot.effectsByStage()
                .get(SpecialRenderEffectStage.AFTER_BACKGROUND));
        assertEquals(List.of(foreground), effectSnapshot.effectsByStage()
                .get(SpecialRenderEffectStage.AFTER_FOREGROUND));

        AdvancedRenderModeController modes = new AdvancedRenderModeController();
        StatelessMode first = new StatelessMode("first");
        StatelessMode second = new StatelessMode("second");
        modes.register(first);
        modes.register(second);

        assertThrows(NullPointerException.class, () -> modes.restore(null));

        assertEquals(2, modes.size());
        assertEquals(List.of(first, second), modes.capture().modes());
    }

    @Test
    void activeStatelessAndStatefulEntriesRoundTripInStageAndRegistrationOrder() {
        SpecialRenderEffectRegistry effects = new SpecialRenderEffectRegistry();
        StatelessEffect bgFirst = new StatelessEffect(
                "bg-first", SpecialRenderEffectStage.AFTER_BACKGROUND);
        StatefulEffect bgSecond = new StatefulEffect(
                "bg-second", SpecialRenderEffectStage.AFTER_BACKGROUND, 11);
        StatelessEffect sprites = new StatelessEffect(
                "sprites", SpecialRenderEffectStage.AFTER_SPRITES);
        effects.register(bgFirst);
        effects.register(bgSecond);
        effects.register(sprites);
        SpecialRenderEffectSnapshot effectSnapshot = effects.capture();
        bgSecond.value = 99;
        effects.clear();
        effects.restore(effectSnapshot);

        assertEquals(List.of(bgFirst, bgSecond), effects.capture().effectsByStage()
                .get(SpecialRenderEffectStage.AFTER_BACKGROUND));
        assertEquals(List.of(sprites), effects.capture().effectsByStage()
                .get(SpecialRenderEffectStage.AFTER_SPRITES));
        assertEquals(11, bgSecond.value);

        AdvancedRenderModeController modes = new AdvancedRenderModeController();
        StatelessMode first = new StatelessMode("first");
        StatefulMode second = new StatefulMode("second", 13);
        StatelessMode third = new StatelessMode("third");
        modes.register(first);
        modes.register(second);
        modes.register(third);
        AdvancedRenderModeSnapshot modeSnapshot = modes.capture();
        second.value = 101;
        modes.clear();
        modes.restore(modeSnapshot);

        assertEquals(List.of(first, second, third), modes.capture().modes());
        assertEquals(13, second.value);
    }

    @Test
    void publicSnapshotConstructorsRemainDefensiveAndImmutable() {
        StatelessEffect effect = new StatelessEffect(
                "effect", SpecialRenderEffectStage.AFTER_BACKGROUND);
        List<SpecialRenderEffect> mutableEffects = new ArrayList<>(List.of(effect));
        EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffect>> effectMap =
                new EnumMap<>(SpecialRenderEffectStage.class);
        effectMap.put(SpecialRenderEffectStage.AFTER_BACKGROUND, mutableEffects);
        List<SpecialRenderEffectSnapshot.EffectState> mutableEffectStates =
                new ArrayList<>(List.of(new SpecialRenderEffectSnapshot.EffectState(0, "effect", 3)));
        EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffectSnapshot.EffectState>> effectStateMap =
                new EnumMap<>(SpecialRenderEffectStage.class);
        effectStateMap.put(SpecialRenderEffectStage.AFTER_BACKGROUND, mutableEffectStates);
        SpecialRenderEffectSnapshot effectSnapshot =
                new SpecialRenderEffectSnapshot(effectMap, effectStateMap);
        mutableEffects.clear();
        effectMap.clear();
        mutableEffectStates.clear();
        effectStateMap.clear();

        List<AdvancedRenderMode> mutableModes = new ArrayList<>(List.of(new StatelessMode("mode")));
        List<AdvancedRenderModeSnapshot.ModeState> mutableModeStates =
                new ArrayList<>(List.of(new AdvancedRenderModeSnapshot.ModeState(0, "mode", 5)));
        AdvancedRenderModeSnapshot modeSnapshot =
                new AdvancedRenderModeSnapshot(mutableModes, mutableModeStates);
        mutableModes.clear();
        mutableModeStates.clear();

        assertEquals(1, effectSnapshot.effectsByStage()
                .get(SpecialRenderEffectStage.AFTER_BACKGROUND).size());
        assertThrows(UnsupportedOperationException.class,
                () -> effectSnapshot.effectsByStage().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> effectSnapshot.effectsByStage()
                        .get(SpecialRenderEffectStage.AFTER_BACKGROUND).clear());
        assertEquals(1, effectSnapshot.effectStatesByStage()
                .get(SpecialRenderEffectStage.AFTER_BACKGROUND).size());
        assertThrows(UnsupportedOperationException.class,
                () -> effectSnapshot.effectStatesByStage()
                        .get(SpecialRenderEffectStage.AFTER_BACKGROUND).clear());
        assertEquals(1, modeSnapshot.modes().size());
        assertThrows(UnsupportedOperationException.class, () -> modeSnapshot.modes().clear());
        assertEquals(1, modeSnapshot.modeStates().size());
        assertThrows(UnsupportedOperationException.class, () -> modeSnapshot.modeStates().clear());
    }

    @Test
    void warmedEmptyCaptureUsesNoAllocation() {
        ThreadMXBean bean = allocationBeanOrSkip();
        SpecialRenderEffectRegistry effects = new SpecialRenderEffectRegistry();
        AdvancedRenderModeController modes = new AdvancedRenderModeController();
        EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffect>> legacyEffects =
                emptyLegacyEffectMap();
        List<AdvancedRenderMode> legacyModes = new ArrayList<>();

        for (int i = 0; i < 100_000; i++) {
            legacySnapshotSink = legacySpecialCapture(legacyEffects);
            legacySnapshotSink = legacyModeCapture(legacyModes);
            snapshotSink = effects.capture();
            snapshotSink = modes.capture();
        }

        long[] legacySamples = new long[3];
        long[] optimizedSamples = new long[3];
        for (int repetition = 0; repetition < legacySamples.length; repetition++) {
            long before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int i = 0; i < 100_000; i++) {
                legacySnapshotSink = legacySpecialCapture(legacyEffects);
                legacySnapshotSink = legacyModeCapture(legacyModes);
            }
            legacySamples[repetition] = (bean.getThreadAllocatedBytes(
                    Thread.currentThread().threadId()) - before) / 100_000L;

            before = bean.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int i = 0; i < 100_000; i++) {
                snapshotSink = effects.capture();
                snapshotSink = modes.capture();
            }
            optimizedSamples[repetition] = (bean.getThreadAllocatedBytes(
                    Thread.currentThread().threadId()) - before) / 100_000L;
        }
        Arrays.sort(legacySamples);
        Arrays.sort(optimizedSamples);

        System.out.printf("emptyRenderSnapshots legacyAllocatedBytes=%d optimizedAllocatedBytes=%d "
                        + "legacySamples=%s optimizedSamples=%s%n",
                legacySamples[1], optimizedSamples[1],
                Arrays.toString(legacySamples), Arrays.toString(optimizedSamples));
        assertTrue(legacySamples[1] > 0L);
        assertEquals(0L, optimizedSamples[1]);
    }

    private static LegacySpecialRenderEffectSnapshot legacySpecialCapture(
            Map<SpecialRenderEffectStage, List<SpecialRenderEffect>> effects) {
        EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffectSnapshot.EffectState>> states =
                new EnumMap<>(SpecialRenderEffectStage.class);
        for (Map.Entry<SpecialRenderEffectStage, List<SpecialRenderEffect>> entry
                : effects.entrySet()) {
            List<SpecialRenderEffectSnapshot.EffectState> stageStates = new ArrayList<>();
            List<SpecialRenderEffect> stageEffects = entry.getValue();
            for (int i = 0; i < stageEffects.size(); i++) {
                SpecialRenderEffect effect = stageEffects.get(i);
                if (effect instanceof RewindSnapshottable<?> snapshottable) {
                    stageStates.add(new SpecialRenderEffectSnapshot.EffectState(
                            i, snapshottable.key(), snapshottable.capture()));
                }
            }
            if (!stageStates.isEmpty()) {
                states.put(entry.getKey(), stageStates);
            }
        }
        return new LegacySpecialRenderEffectSnapshot(effects, states);
    }

    private static LegacyAdvancedRenderModeSnapshot legacyModeCapture(List<AdvancedRenderMode> modes) {
        return new LegacyAdvancedRenderModeSnapshot(modes, new ArrayList<>());
    }

    private static EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffect>> emptyLegacyEffectMap() {
        EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffect>> result =
                new EnumMap<>(SpecialRenderEffectStage.class);
        for (SpecialRenderEffectStage stage : SpecialRenderEffectStage.values()) {
            result.put(stage, new ArrayList<>());
        }
        return result;
    }

    private static ThreadMXBean allocationBeanOrSkip() {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(raw instanceof ThreadMXBean,
                "ThreadMXBean allocation accounting unavailable");
        ThreadMXBean bean = (ThreadMXBean) raw;
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "thread allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        Assumptions.assumeTrue(bean.isThreadAllocatedMemoryEnabled(),
                "thread allocation accounting could not be enabled");
        Assumptions.assumeTrue(bean.getThreadAllocatedBytes(
                Thread.currentThread().threadId()) >= 0L,
                "thread allocation accounting unavailable for current thread");
        return bean;
    }

    /** Test-local bytecode equivalent of the pre-optimization public snapshot constructor. */
    private record LegacySpecialRenderEffectSnapshot(
            Map<SpecialRenderEffectStage, List<SpecialRenderEffect>> effectsByStage,
            Map<SpecialRenderEffectStage, List<SpecialRenderEffectSnapshot.EffectState>> effectStatesByStage) {
        private LegacySpecialRenderEffectSnapshot {
            EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffect>> effectCopy =
                    new EnumMap<>(SpecialRenderEffectStage.class);
            for (Map.Entry<SpecialRenderEffectStage, List<SpecialRenderEffect>> entry
                    : effectsByStage.entrySet()) {
                effectCopy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            effectsByStage = Collections.unmodifiableMap(effectCopy);

            EnumMap<SpecialRenderEffectStage, List<SpecialRenderEffectSnapshot.EffectState>> stateCopy =
                    new EnumMap<>(SpecialRenderEffectStage.class);
            for (Map.Entry<SpecialRenderEffectStage, List<SpecialRenderEffectSnapshot.EffectState>> entry
                    : effectStatesByStage.entrySet()) {
                stateCopy.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            effectStatesByStage = Collections.unmodifiableMap(stateCopy);
        }
    }

    /** Test-local bytecode equivalent of the pre-optimization public snapshot constructor. */
    private record LegacyAdvancedRenderModeSnapshot(
            List<AdvancedRenderMode> modes,
            List<AdvancedRenderModeSnapshot.ModeState> modeStates) {
        private LegacyAdvancedRenderModeSnapshot {
            modes = List.copyOf(modes);
            modeStates = List.copyOf(modeStates);
        }
    }

    private record StatelessEffect(String name, SpecialRenderEffectStage stage)
            implements SpecialRenderEffect {
        @Override public void render(SpecialRenderEffectContext context) { }
    }

    private static final class StatefulEffect
            implements SpecialRenderEffect, RewindSnapshottable<Integer> {
        private final String name;
        private final SpecialRenderEffectStage stage;
        private int value;

        private StatefulEffect(String name, SpecialRenderEffectStage stage, int value) {
            this.name = name;
            this.stage = stage;
            this.value = value;
        }

        @Override public SpecialRenderEffectStage stage() { return stage; }
        @Override public void render(SpecialRenderEffectContext context) { }
        @Override public String key() { return name; }
        @Override public Integer capture() { return value; }
        @Override public void restore(Integer snapshot) { value = snapshot; }
    }

    private record StatelessMode(String id) implements AdvancedRenderMode {
        @Override public void contribute(
                AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) { }
    }

    private static final class StatefulMode
            implements AdvancedRenderMode, RewindSnapshottable<Integer> {
        private final String id;
        private int value;

        private StatefulMode(String id, int value) {
            this.id = id;
            this.value = value;
        }

        @Override public String id() { return id; }
        @Override public void contribute(
                AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) { }
        @Override public String key() { return id; }
        @Override public Integer capture() { return value; }
        @Override public void restore(Integer snapshot) { value = snapshot; }
    }
}
