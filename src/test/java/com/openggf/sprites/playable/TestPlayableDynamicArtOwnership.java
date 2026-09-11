package com.openggf.sprites.playable;

import com.openggf.game.GameId;
import com.openggf.game.resources.DynamicArtDecisionOwner;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPlayableDynamicArtOwnership {

    @Test
    void animationCapabilityPublishesButGenericMappingSetterDoesNot() {
        DynamicArtLifecycleService lifecycle = new DynamicArtLifecycleService();
        lifecycle.beginRun();
        lifecycle.openComparisonSegment();
        PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
        when(renderer.dplcFrame(3)).thenReturn(
                new SpriteDplcFrame(List.of(new TileLoadRequest(2, 1))));
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        sonic.setSpriteRenderer(renderer);
        sonic.setAnimationFrameCount(8);
        sonic.setAnimationProfile((sprite, frameCounter, frameCount) -> 3);
        sonic.getAnimationManager().setDynamicArtDecisionOwner(
                new DynamicArtDecisionOwner(
                        lifecycle, GameId.S2, "sonic", renderer));

        sonic.getAnimationManager().update(0);
        lifecycle.publishRow(0, false);
        sonic.setMappingFrame(4);
        var setterOnly = lifecycle.publishRow(1, false);

        assertEquals(1, lifecycle.latestSnapshot().frame());
        assertEquals(0, setterOnly.edges().size());
        verify(renderer).applyRuntimeArtUpdate(
                org.mockito.ArgumentMatchers.eq(3),
                org.mockito.ArgumentMatchers.any());
    }
}
