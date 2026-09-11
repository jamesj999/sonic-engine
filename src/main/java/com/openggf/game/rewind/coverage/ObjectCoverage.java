package com.openggf.game.rewind.coverage;

import java.util.ArrayList;
import java.util.List;

/**
 * Coverage record for a single concrete {@code AbstractObjectInstance} subclass.
 *
 * <p>Phase 1 defaults: {@code hasRecreatePath = true}, field lists empty.
 * Later tasks (2-4) populate the field lists and set {@code hasRecreatePath}
 * based on actual rewind-schema inspection.
 *
 * <p>Gap keys are stable, per-reason identifiers so the baseline tracks
 * individual gaps rather than class names — a new gap on an already-baselined
 * class is still caught.
 */
public record ObjectCoverage(
        String className,
        boolean isLayoutSpawnable,
        boolean isDynamicSpawnable,
        boolean hasRecreatePath,
        List<String> uncapturedFinalScalarFields,
        List<String> unIdObjectRefFields,
        List<String> uncapturedHelperStateFields) {

    /**
     * Returns {@code true} when the object is fully covered by rewind:
     * it has a recreate path and no uncaptured scalar, object-ref, or
     * helper-state fields.
     */
    public boolean isCovered() {
        return hasRecreatePath
                && uncapturedFinalScalarFields.isEmpty()
                && unIdObjectRefFields.isEmpty()
                && uncapturedHelperStateFields.isEmpty();
    }

    /**
     * Returns the stable, per-reason gap keys this class contributes.
     * Empty when the object is covered.
     *
     * <ul>
     *   <li>{@code className + "#recreate"} — when {@code !hasRecreatePath}</li>
     *   <li>{@code className + "#finalScalar#" + field} — per entry in
     *       {@code uncapturedFinalScalarFields}</li>
     *   <li>{@code className + "#objectRef#" + field} — per entry in
     *       {@code unIdObjectRefFields}</li>
     *   <li>{@code className + "#" + field + "#uncaptured-helper-state"} — per entry in
     *       {@code uncapturedHelperStateFields} (a mutable plain-helper field whose
     *       type has no {@code RewindCodecs} codec, so it is silently dropped on rewind)</li>
     * </ul>
     */
    public List<String> gapKeys() {
        if (isCovered()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        if (!hasRecreatePath) {
            keys.add(className + "#recreate");
        }
        for (String field : uncapturedFinalScalarFields) {
            keys.add(className + "#finalScalar#" + field);
        }
        for (String field : unIdObjectRefFields) {
            keys.add(className + "#objectRef#" + field);
        }
        for (String field : uncapturedHelperStateFields) {
            keys.add(className + "#" + field + "#uncaptured-helper-state");
        }
        return keys;
    }
}
