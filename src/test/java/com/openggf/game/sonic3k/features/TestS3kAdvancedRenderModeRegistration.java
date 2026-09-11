package com.openggf.game.sonic3k.features;

import com.openggf.game.session.SessionManager;
import com.openggf.game.GameServices;
import com.openggf.game.render.AdvancedRenderFrameState;
import com.openggf.game.render.AdvancedRenderModeContext;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.sonic3k.Sonic3kZoneFeatureProvider;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kAdvancedRenderModeRegistration {

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void aizAct1RegistersModeButLeavesHeatHazeDisabledWithoutEventState() {
        Sonic3kZoneFeatureProvider provider = new Sonic3kZoneFeatureProvider();
        AdvancedRenderModeController controller = new AdvancedRenderModeController();

        provider.registerAdvancedRenderModes(controller, Sonic3kZoneIds.ZONE_AIZ, 0);

        assertEquals(1, controller.size());
        AdvancedRenderFrameState state = controller.resolve(new AdvancedRenderModeContext(
                GameServices.camera(),
                0,
                GameServices.level(),
                Sonic3kZoneIds.ZONE_AIZ,
                0,
                GameServices.camera().getX()));
        assertFalse(state.enableForegroundHeatHaze());
        assertFalse(state.enablePerLineForegroundScroll());
    }

    @Test
    void aizAct2AdvancedModeEnablesForegroundHeatHazeFallback() {
        Sonic3kZoneFeatureProvider provider = new Sonic3kZoneFeatureProvider();
        AdvancedRenderModeController controller = new AdvancedRenderModeController();

        provider.registerAdvancedRenderModes(controller, Sonic3kZoneIds.ZONE_AIZ, 1);

        assertEquals(1, controller.size());
        AdvancedRenderFrameState state = controller.resolve(new AdvancedRenderModeContext(
                GameServices.camera(),
                0,
                GameServices.level(),
                Sonic3kZoneIds.ZONE_AIZ,
                1,
                GameServices.camera().getX()));
        assertTrue(state.enableForegroundHeatHaze());
        assertFalse(state.enablePerLineForegroundScroll());
    }

    @Test
    void slotsAdvancedModeEnablesPerLineForegroundScroll() {
        Sonic3kZoneFeatureProvider provider = new Sonic3kZoneFeatureProvider();
        AdvancedRenderModeController controller = new AdvancedRenderModeController();

        provider.registerAdvancedRenderModes(controller, Sonic3kZoneIds.ZONE_SLOT_MACHINE, 0);

        assertEquals(1, controller.size());
        AdvancedRenderFrameState state = controller.resolve(new AdvancedRenderModeContext(
                GameServices.camera(),
                0,
                GameServices.level(),
                Sonic3kZoneIds.ZONE_SLOT_MACHINE,
                0,
                GameServices.camera().getX()));
        assertFalse(state.enableForegroundHeatHaze());
        assertTrue(state.enablePerLineForegroundScroll());
    }

    @Test
    void mgzAct2RegistersCollapseForegroundVScrollMode() {
        Sonic3kZoneFeatureProvider provider = new Sonic3kZoneFeatureProvider();
        AdvancedRenderModeController controller = new AdvancedRenderModeController();

        provider.registerAdvancedRenderModes(controller, Sonic3kZoneIds.ZONE_MGZ, 1);

        assertEquals(1, controller.size());
    }

    @Test
    void unrelatedZonesDoNotRegisterAdvancedModes() {
        Sonic3kZoneFeatureProvider provider = new Sonic3kZoneFeatureProvider();
        AdvancedRenderModeController controller = new AdvancedRenderModeController();

        provider.registerAdvancedRenderModes(controller, Sonic3kZoneIds.ZONE_HCZ, 0);

        assertTrue(controller.isEmpty());
    }
}
