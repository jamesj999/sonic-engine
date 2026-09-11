package com.openggf.trace.timing;

import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareWorkKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable indexed timing input compiled from a fixture's dedicated stream. */
public final class HardwareTimingSchedule {

    static final Comparator<HardwareCompletionEdge> CANONICAL_ORDER =
            Comparator.comparingInt(HardwareCompletionEdge::rawFrame)
                    .thenComparingInt(edge -> edge.boundary().ordinal())
                    .thenComparingInt(edge -> edge.kind().ordinal())
                    .thenComparingLong(HardwareCompletionEdge::ordinal);

    private static final HardwareTimingSchedule EMPTY = new HardwareTimingSchedule(false, List.of());

    private final boolean recordedInput;
    private final Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> admissionPolicies;
    private final List<HardwareCompletionEdge> edges;
    private final Map<FrameBoundary, List<HardwareCompletionEdge>> edgesByFrameAndBoundary;

    public HardwareTimingSchedule(List<HardwareCompletionEdge> edges) {
        this(true, edges);
    }

    private HardwareTimingSchedule(boolean recordedInput, List<HardwareCompletionEdge> edges) {
        this(recordedInput, edges, recordedAdmissionPolicies());
    }

    /** Explicit mixed-policy seam for generic unit tests; v5 loading never uses it. */
    static HardwareTimingSchedule withAdmissionPolicies(
            List<HardwareCompletionEdge> edges,
            Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies) {
        return new HardwareTimingSchedule(true, edges, Map.copyOf(policies));
    }

    private HardwareTimingSchedule(
            boolean recordedInput,
            List<HardwareCompletionEdge> edges,
            Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies) {
        this.recordedInput = recordedInput;
        this.admissionPolicies = Map.copyOf(policies);
        this.edges = List.copyOf(edges);
        for (HardwareCompletionEdge edge : this.edges) {
            Objects.requireNonNull(edge, "hardware completion edge");
            if (edge.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE
                    && edge.boundary() != HardwareServiceBoundary.PRE_MAIN_LOOP) {
                throw new IllegalArgumentException(
                        "direct decompression completion edges require PRE_MAIN_LOOP");
            }
        }
        Map<FrameBoundary, List<HardwareCompletionEdge>> indexed = new HashMap<>();
        for (HardwareCompletionEdge edge : this.edges) {
            indexed.computeIfAbsent(new FrameBoundary(edge.rawFrame(), edge.boundary()), ignored -> new ArrayList<>())
                    .add(edge);
        }
        Map<FrameBoundary, List<HardwareCompletionEdge>> immutable = new HashMap<>();
        indexed.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        this.edgesByFrameAndBoundary = Map.copyOf(immutable);
    }

    public static HardwareTimingSchedule empty() {
        return EMPTY;
    }

    /** A present, explicitly empty v5 timing stream with the complete registry. */
    public static HardwareTimingSchedule recordedEmpty() {
        return new HardwareTimingSchedule(true, List.of());
    }

    public List<HardwareCompletionEdge> edges() {
        return edges;
    }

    /** Whether a v5 hardware_timing.jsonl stream was present. */
    public boolean hasRecordedInput() {
        return recordedInput;
    }

    public Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> admissionPolicies() {
        return admissionPolicies;
    }

    public List<HardwareCompletionEdge> edgesAt(int rawFrame, HardwareServiceBoundary boundary) {
        return edgesByFrameAndBoundary.getOrDefault(
                new FrameBoundary(rawFrame, Objects.requireNonNull(boundary, "boundary")), List.of());
    }

    private record FrameBoundary(int rawFrame, HardwareServiceBoundary boundary) {
    }

    private static Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy>
            recordedAdmissionPolicies() {
        EnumMap<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies =
                new EnumMap<>(HardwareWorkKind.class);
        policies.put(HardwareWorkKind.KOS_MODULE_QUEUE,
                HardwareReadinessAdmissionPolicy.RECORDED);
        policies.put(HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                HardwareReadinessAdmissionPolicy.RECORDED);
        policies.put(HardwareWorkKind.NEMESIS_PLC_QUEUE,
                HardwareReadinessAdmissionPolicy.RECORDED);
        return Map.copyOf(policies);
    }

}
