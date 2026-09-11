package com.openggf.game.render;

import com.openggf.game.rewind.snapshot.SpecialRenderEffectSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestSpecialRenderEffectRewindSnapshot {

    @Test
    void roundTripPreservesActiveEffects() {
        SpecialRenderEffectRegistry reg = new SpecialRenderEffectRegistry();
        reg.register(stubEffect(SpecialRenderEffectStage.AFTER_BACKGROUND));
        reg.register(stubEffect(SpecialRenderEffectStage.AFTER_FOREGROUND));
        SpecialRenderEffectSnapshot snap = reg.capture();
        reg.clear();
        assertEquals(0, reg.activeEffectCount());
        reg.restore(snap);
        assertEquals(2, reg.activeEffectCount());
    }

    @Test
    void keyIsSpecialRender() {
        assertEquals("special-render", new SpecialRenderEffectRegistry().key());
    }

    @Test
    void emptyRegistryRoundTrips() {
        SpecialRenderEffectRegistry reg = new SpecialRenderEffectRegistry();
        SpecialRenderEffectSnapshot snap = reg.capture();
        reg.restore(snap);
        assertEquals(0, reg.activeEffectCount());
    }

    private SpecialRenderEffect stubEffect(SpecialRenderEffectStage stage) {
        return new SpecialRenderEffect() {
            @Override
            public SpecialRenderEffectStage stage() { return stage; }
            @Override
            public void render(SpecialRenderEffectContext context) {}
        };
    }
}
