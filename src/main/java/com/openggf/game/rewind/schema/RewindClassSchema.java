package com.openggf.game.rewind.schema;

import java.util.List;
import java.util.Objects;

public final class RewindClassSchema {
    private final int schemaId;
    private final Class<?> type;
    private final List<RewindFieldPlan> fields;
    private final List<RewindFieldPlan> capturedFields;
    private final List<RewindFieldPlan> unsupportedFields;

    RewindClassSchema(int schemaId, Class<?> type, List<RewindFieldPlan> fields) {
        this.schemaId = schemaId;
        this.type = Objects.requireNonNull(type, "type");
        this.fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        this.capturedFields = this.fields.stream()
                .filter(RewindFieldPlan::captured)
                .toList();
        this.unsupportedFields = this.fields.stream()
                .filter(field -> field.policy() == RewindFieldPolicy.UNSUPPORTED)
                .toList();
    }

    public int schemaId() {
        return schemaId;
    }

    public Class<?> type() {
        return type;
    }

    public List<RewindFieldPlan> fields() {
        return fields;
    }

    public List<RewindFieldPlan> capturedFields() {
        return capturedFields;
    }

    public List<RewindFieldPlan> unsupportedFields() {
        return unsupportedFields;
    }
}
