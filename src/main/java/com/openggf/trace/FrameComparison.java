package com.openggf.trace;

import java.util.List;
import java.util.Map;

/**
 * Comparison result for a single frame: all fields compared,
 * plus optional diagnostic context from ROM trace and engine.
 */
public record FrameComparison(
    int frame,
    Map<String, FieldComparison> fields,
    String romDiagnostics,
    String engineDiagnostics
) {
    /** Convenience constructor without diagnostics (backward-compatible). */
    public FrameComparison(int frame, Map<String, FieldComparison> fields) {
        this(frame, fields, "", "");
    }

    /** True if any field has a non-MATCH severity. */
    public boolean hasDivergence() {
        return fields.values().stream().anyMatch(FieldComparison::isDivergent);
    }

    /** True if any field is ERROR severity. */
    public boolean hasError() {
        return fields.values().stream()
            .anyMatch(fc -> fc.severity() == Severity.ERROR);
    }

    /** True if any field in the selected verification scope is ERROR severity. */
    public boolean hasError(TraceVerificationScope scope) {
        return fields.values().stream()
                .anyMatch(fc -> scope.includes(fc.verificationGroup())
                        && fc.severity() == Severity.ERROR);
    }

    /** Get all divergent fields. */
    public List<FieldComparison> divergentFields() {
        return fields.values().stream()
            .filter(FieldComparison::isDivergent)
            .toList();
    }

    /** True if a specific field has ERROR severity. */
    public boolean hasErrorInField(String fieldName) {
        FieldComparison fc = fields.get(fieldName);
        return fc != null && fc.severity() == Severity.ERROR;
    }
}

