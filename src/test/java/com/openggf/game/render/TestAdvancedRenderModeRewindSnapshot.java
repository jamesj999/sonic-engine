package com.openggf.game.render;

import com.openggf.game.rewind.snapshot.AdvancedRenderModeSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestAdvancedRenderModeRewindSnapshot {

    @Test
    void roundTripPreservesActiveModes() {
        AdvancedRenderModeController ctrl = new AdvancedRenderModeController();
        ctrl.register(stubMode("aiz-water"));
        ctrl.register(stubMode("htz-shake"));
        AdvancedRenderModeSnapshot snap = ctrl.capture();
        ctrl.clear();
        assertEquals(0, ctrl.size());
        ctrl.restore(snap);
        assertEquals(2, ctrl.size());
    }

    @Test
    void keyIsAdvancedRenderMode() {
        assertEquals("advanced-render-mode", new AdvancedRenderModeController().key());
    }

    @Test
    void emptyControllerRoundTrips() {
        AdvancedRenderModeController ctrl = new AdvancedRenderModeController();
        AdvancedRenderModeSnapshot snap = ctrl.capture();
        ctrl.restore(snap);
        assertEquals(0, ctrl.size());
    }

    private AdvancedRenderMode stubMode(String id) {
        return new AdvancedRenderMode() {
            @Override
            public String id() { return id; }
            @Override
            public void contribute(AdvancedRenderModeContext context,
                                   AdvancedRenderFrameState.Builder builder) {}
        };
    }
}
