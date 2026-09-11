package com.openggf.game.rewind.schema;

import com.openggf.game.rewind.identity.RewindIdentityTable;

import java.util.Objects;
import java.util.Optional;

public final class RewindCaptureContext {
    private static final RewindCaptureContext NONE = new RewindCaptureContext(null);

    public static RewindCaptureContext none() {
        return NONE;
    }

    public static RewindCaptureContext withIdentityTable(RewindIdentityTable identityTable) {
        return new RewindCaptureContext(Objects.requireNonNull(identityTable, "identityTable"));
    }

    private final RewindIdentityTable identityTable;

    private RewindCaptureContext(RewindIdentityTable identityTable) {
        this.identityTable = identityTable;
    }

    public Optional<RewindIdentityTable> identityTable() {
        return Optional.ofNullable(identityTable);
    }

    public RewindIdentityTable requireIdentityTable() {
        return identityTable().orElseThrow(() ->
                new IllegalStateException("RewindIdentityTable is required for player-reference rewind fields."));
    }
}
