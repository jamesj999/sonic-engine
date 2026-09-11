package com.openggf.game.sonic2;

import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.runtime.WfzRuntimeState;
import com.openggf.game.sonic2.runtime.WfzRuntimeStateView;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2WfzRuntimeStateRegistration {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void wfzInstallsOwnedRuntimeStateWithMetadata() {
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();

        manager.initLevel(Sonic2LevelEventManager.ZONE_WFZ, 0);

        WfzRuntimeState state = registry().currentAs(WfzRuntimeState.class).orElseThrow();
        assertInstanceOf(WfzRuntimeStateView.class, state);
        assertEquals("s2", state.gameId());
        assertEquals(Sonic2LevelEventManager.ZONE_WFZ, state.zoneIndex());
        assertEquals(0, state.actIndex());
    }

    @Test
    void wfzActSwitchReplacesOwnedViewMetadata() {
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();
        manager.initLevel(Sonic2LevelEventManager.ZONE_WFZ, 0);
        WfzRuntimeState first = registry().currentAs(WfzRuntimeState.class).orElseThrow();

        manager.initLevel(Sonic2LevelEventManager.ZONE_WFZ, 1);

        WfzRuntimeState second = registry().currentAs(WfzRuntimeState.class).orElseThrow();
        assertNotSame(first, second);
        assertEquals(1, second.actIndex());
    }

    @Test
    void ownedWfzViewClearsWhenLeavingWfz() {
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();
        manager.initLevel(Sonic2LevelEventManager.ZONE_WFZ, 0);

        manager.initLevel(Sonic2LevelEventManager.ZONE_EHZ, 0);

        assertTrue(registry().currentAs(WfzRuntimeState.class).isEmpty());
    }

    @Test
    void customWfzRuntimeStateIsNotOverwrittenByWfzInitialization() {
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();
        WfzRuntimeState custom = new CustomWfzRuntimeState(90, 4, 0x123, 0x456);
        registry().install(custom);

        manager.initLevel(Sonic2LevelEventManager.ZONE_WFZ, 0);

        assertSame(custom, registry().current());
    }

    @Test
    void customWfzRuntimeStateIsNotClearedByNonWfzInitialization() {
        Sonic2LevelEventManager manager = new Sonic2LevelEventManager();
        WfzRuntimeState custom = new CustomWfzRuntimeState(91, 5, 0x234, 0x567);
        registry().install(custom);

        manager.initLevel(Sonic2LevelEventManager.ZONE_EHZ, 0);

        assertSame(custom, registry().current());
    }

    private static ZoneRuntimeRegistry registry() {
        return GameServices.zoneRuntimeRegistry();
    }

    private record CustomWfzRuntimeState(
            int zoneIndex,
            int actIndex,
            int bgVscrollFactor,
            int bgXPos) implements WfzRuntimeState {
    }
}
