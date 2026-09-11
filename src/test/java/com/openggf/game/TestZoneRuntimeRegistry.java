package com.openggf.game;

import com.openggf.game.zone.NoOpZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.game.zone.ZoneRuntimeState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestZoneRuntimeRegistry {

    @Test
    void registryDefaultsToNoOpState() {
        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();

        assertSame(NoOpZoneRuntimeState.INSTANCE, registry.current());
        assertTrue(registry.currentAs(NoOpZoneRuntimeState.class).isPresent());
        assertTrue(registry.currentAs(DummyState.class).isEmpty());
    }

    @Test
    void registryInstallsTypedStateAndClearsBackToNoOp() {
        ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
        DummyState state = new DummyState(1, 0);

        registry.install(state);
        assertSame(state, registry.current());
        assertSame(state, registry.currentAs(DummyState.class).orElseThrow());

        registry.clear();
        assertSame(NoOpZoneRuntimeState.INSTANCE, registry.current());
    }

    private record DummyState(int zoneIndex, int actIndex) implements ZoneRuntimeState {
        @Override public String gameId() { return "dummy"; }
    }
}
