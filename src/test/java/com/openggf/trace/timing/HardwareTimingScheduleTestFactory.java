package com.openggf.trace.timing;

import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareWorkKind;

import java.util.List;
import java.util.Map;

/** Test-only access to the explicit mixed-policy schedule seam. */
public final class HardwareTimingScheduleTestFactory {
    private HardwareTimingScheduleTestFactory() {
    }

    public static HardwareTimingSchedule withAdmissionPolicies(
            List<HardwareCompletionEdge> edges,
            Map<HardwareWorkKind, HardwareReadinessAdmissionPolicy> policies) {
        return HardwareTimingSchedule.withAdmissionPolicies(edges, policies);
    }
}
