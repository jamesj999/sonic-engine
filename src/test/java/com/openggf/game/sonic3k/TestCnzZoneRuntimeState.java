package com.openggf.game.sonic3k;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.events.Sonic3kCNZEvents;
import com.openggf.game.sonic3k.runtime.CnzZoneRuntimeState;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzZoneRuntimeState {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void cnzRuntimeStateExposesPublishedFields() {
        Sonic3kCNZEvents events = new Sonic3kCNZEvents();
        events.init(0);
        events.forceForegroundRoutine(0x08);
        events.forceBackgroundRoutine(0x0C);
        events.setPublishedDeformInputs(0x24, 0x30);
        events.setBossScrollState(0x120, 0x40000);
        events.setWallGrabSuppressed(true);
        events.setWaterTargetY(0x0A58);

        CnzZoneRuntimeState state = new CnzZoneRuntimeState(0, PlayerCharacter.KNUCKLES, events);

        assertEquals(0x08, state.foregroundRoutine());
        assertEquals(0x0C, state.backgroundRoutine());
        assertEquals(0x24, state.deformPhaseBgX());
        assertEquals(0x30, state.publishedBgCameraX());
        assertEquals(0x120, state.bossScrollOffsetY());
        assertEquals(0x40000, state.bossScrollVelocityY());
        assertTrue(state.isWallGrabSuppressed());
        assertEquals(0x0A58, state.waterTargetY());
    }

    @Test
    void runtimeStateDoesNotNeedRawEventBackdoorToExposeBossMode() {
        Sonic3kCNZEvents events = new Sonic3kCNZEvents();
        events.init(0);
        events.beginKnucklesTeleporterRoute();

        CnzZoneRuntimeState state = new CnzZoneRuntimeState(0, PlayerCharacter.KNUCKLES, events);

        assertEquals(Sonic3kCNZEvents.BossBackgroundMode.ACT2_KNUCKLES_TELEPORTER, state.bossBackgroundMode());
    }
}
