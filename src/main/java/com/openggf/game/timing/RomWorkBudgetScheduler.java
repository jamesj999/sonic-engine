package com.openggf.game.timing;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic boundary-to-integer-work-unit scheduler.
 *
 * <p>The scheduler has no payload, clock, thread, or replay input. It advances
 * only the FIFO preparation supplied by production code.
 */
public final class RomWorkBudgetScheduler {
    private final Map<HardwareServiceBoundary, Integer> workUnitsByBoundary;

    public RomWorkBudgetScheduler(Map<HardwareServiceBoundary, Integer> workUnitsByBoundary) {
        Objects.requireNonNull(workUnitsByBoundary, "workUnitsByBoundary");
        EnumMap<HardwareServiceBoundary, Integer> copy =
                new EnumMap<>(HardwareServiceBoundary.class);
        for (var entry : workUnitsByBoundary.entrySet()) {
            HardwareServiceBoundary boundary = Objects.requireNonNull(
                    entry.getKey(), "workUnitsByBoundary key");
            int units = Objects.requireNonNull(
                    entry.getValue(), "workUnitsByBoundary value");
            if (units < 0) {
                throw new IllegalArgumentException("work-unit budget must be non-negative");
            }
            if (units != 0) {
                copy.put(boundary, units);
            }
        }
        this.workUnitsByBoundary = Map.copyOf(copy);
    }

    public static RomWorkBudgetScheduler oneWorkUnitAt(HardwareServiceBoundary boundary) {
        return new RomWorkBudgetScheduler(Map.of(
                Objects.requireNonNull(boundary, "boundary"), 1));
    }

    void service(HardwareServiceBoundary boundary, java.util.List<HardwareTimingJob> jobs) {
        int budget = workUnitsByBoundary.getOrDefault(boundary, 0);
        for (int unit = 0; unit < budget; unit++) {
            HardwareTimingJob head = firstUnprepared(jobs);
            if (head == null) {
                return;
            }
            if (head.preparation().isBoundaryDriven()) {
                return;
            }
            if (head.preparation().isPrepared()) {
                head.capturePreparedPayload();
                unit--;
                continue;
            }
            boolean advanced = head.preparation().stepOneWorkUnit();
            if (head.preparation().isPrepared()) {
                head.capturePreparedPayload();
            }
            if (!advanced) {
                return;
            }
        }
    }

    private static HardwareTimingJob firstUnprepared(
            java.util.List<HardwareTimingJob> jobs) {
        for (HardwareTimingJob job : jobs) {
            if (!job.isClaimed() && !job.hasPreparedPayload()) {
                return job;
            }
        }
        return null;
    }
}
