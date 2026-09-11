package com.openggf.game.zone;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.ZoneRuntimeSnapshot;

import java.util.Objects;
import java.util.Optional;

public final class ZoneRuntimeRegistry implements RewindSnapshottable<ZoneRuntimeSnapshot> {
    private ZoneRuntimeState current = NoOpZoneRuntimeState.INSTANCE;

    public ZoneRuntimeState current() {
        return current;
    }

    public void install(ZoneRuntimeState state) {
        this.current = Objects.requireNonNull(state, "state");
    }

    public void clear() {
        this.current = NoOpZoneRuntimeState.INSTANCE;
    }

    public <T extends ZoneRuntimeState> Optional<T> currentAs(Class<T> type) {
        if (type.isInstance(current)) {
            return Optional.of(type.cast(current));
        }
        return Optional.empty();
    }

    // ── RewindSnapshottable ───────────────────────────────────────────────

    @Override
    public String key() {
        return "zone-runtime";
    }

    @Override
    public ZoneRuntimeSnapshot capture() {
        return new ZoneRuntimeSnapshot(
                current.getClass().getName(),
                current.gameId(),
                current.zoneIndex(),
                current.actIndex(),
                current.captureBytes());
    }

    @Override
    public void restore(ZoneRuntimeSnapshot s) {
        Objects.requireNonNull(s, "s");
        validateSnapshotIdentity(s);
        current.restoreBytes(s.stateBytes());
    }

    private void validateSnapshotIdentity(ZoneRuntimeSnapshot s) {
        String currentType = current.getClass().getName();
        if (!currentType.equals(s.stateType())
                || !Objects.equals(current.gameId(), s.gameId())
                || current.zoneIndex() != s.zoneIndex()
                || current.actIndex() != s.actIndex()) {
            throw new IllegalStateException(
                    "Cannot restore zone runtime snapshot for "
                            + s.stateType() + "[" + s.gameId() + ":"
                            + s.zoneIndex() + "/" + s.actIndex() + "] into "
                            + currentType + "[" + current.gameId() + ":"
                            + current.zoneIndex() + "/" + current.actIndex() + "]");
        }
    }
}
