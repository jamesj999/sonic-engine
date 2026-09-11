package com.openggf.game.render;

import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.util.ShortIndexedView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TestAdvancedRenderModeController {

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void emptyControllerResolvesDisabledFrameState() {
        TestEnvironment.activeGameplayMode();
        AdvancedRenderModeController controller = new AdvancedRenderModeController();

        AdvancedRenderFrameState state = controller.resolve(new AdvancedRenderModeContext(
                GameServices.camera(),
                0,
                GameServices.level(),
                0,
                0,
                GameServices.camera().getX()));

        assertFalse(state.enableForegroundHeatHaze());
        assertFalse(state.enablePerLineForegroundScroll());
        assertNull(state.foregroundPerColumnVScrollOverride());
    }

    @Test
    void registeredModesMergeFrameStateContributions() {
        TestEnvironment.activeGameplayMode();
        AdvancedRenderModeController controller = new AdvancedRenderModeController();

        controller.register(new AdvancedRenderMode() {
            @Override
            public String id() {
                return "heat-haze";
            }

            @Override
            public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
                builder.enableForegroundHeatHaze();
            }
        });
        controller.register(new AdvancedRenderMode() {
            @Override
            public String id() {
                return "slot-scroll";
            }

            @Override
            public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
                builder.enablePerLineForegroundScroll();
                builder.setForegroundPerColumnVScrollOverride(new short[]{3, 5, 7});
            }
        });

        AdvancedRenderFrameState state = controller.resolve(new AdvancedRenderModeContext(
                GameServices.camera(),
                10,
                GameServices.level(),
                1,
                2,
                GameServices.camera().getX()));

        assertTrue(state.enableForegroundHeatHaze());
        assertTrue(state.enablePerLineForegroundScroll());
        assertArrayEquals(new short[]{3, 5, 7}, state.foregroundPerColumnVScrollOverride());
    }

    @Test
    void clearRemovesRegisteredModes() {
        AdvancedRenderModeController controller = new AdvancedRenderModeController();
        controller.register(new AdvancedRenderMode() {
            @Override
            public String id() {
                return "one";
            }

            @Override
            public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
                builder.enableForegroundHeatHaze();
            }
        });

        assertFalse(controller.isEmpty());
        assertEquals(1, controller.size());

        controller.clear();

        assertTrue(controller.isEmpty());
        assertEquals(0, controller.size());
        assertSame(AdvancedRenderFrameState.disabled(), controller.resolve(null));
    }

    @Test
    void resolvedColumnsAreFrameOwnedReadOnlyAndReuseTwoBackingsAcross600Frames() {
        TestEnvironment.activeGameplayMode();
        AdvancedRenderModeController controller = new AdvancedRenderModeController();
        short[] contributed = {3, 5, 7};
        controller.register(new AdvancedRenderMode() {
            @Override
            public String id() {
                return "columns";
            }

            @Override
            public void contribute(AdvancedRenderModeContext context, AdvancedRenderFrameState.Builder builder) {
                builder.setForegroundPerColumnVScrollOverride(contributed);
            }
        });

        AdvancedRenderModeContext context = new AdvancedRenderModeContext(
                GameServices.camera(), 0, GameServices.level(), 1, 2, GameServices.camera().getX());
        AdvancedRenderFrameState frameN = controller.resolve(context);
        ShortIndexedView frameNColumns = frameN.foregroundPerColumnVScrollView();
        assertEquals(3, frameNColumns.size());
        assertEquals(5, frameNColumns.get(1));

        contributed[0] = 11;
        contributed[1] = 13;
        contributed[2] = 17;
        AdvancedRenderFrameState frameNPlusOne = controller.resolve(context);

        assertEquals(3, frameNColumns.get(0), "frame N must remain unchanged while frame N+1 is built");
        assertEquals(5, frameNColumns.get(1));
        assertEquals(7, frameNColumns.get(2));
        assertEquals(13, frameNPlusOne.foregroundPerColumnVScrollView().get(1));

        short[] defensiveCopy = frameN.foregroundPerColumnVScrollOverride();
        defensiveCopy[0] = 99;
        assertEquals(3, frameNColumns.get(0), "the public compatibility array must not expose mutable backing");

        Set<Object> states = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> views = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> backings = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int frame = 0; frame < 600; frame++) {
            AdvancedRenderFrameState state = controller.resolve(context);
            states.add(state);
            views.add(state.foregroundPerColumnVScrollView());
            backings.add(state.foregroundPerColumnVScrollBackingIdentity());
        }

        assertEquals(2, states.size(), "controller must reuse a bounded pair of frame states");
        assertEquals(2, views.size(), "read-only column views must be reused with their frame slots");
        assertEquals(2, backings.size(), "column storage must be copied once into two reusable frame backings");
    }
}
