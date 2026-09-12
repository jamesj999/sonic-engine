package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCpzBossPresentation {

    @Test
    void drawsTheCpzPartsFrameWithTheObjectFlipBit() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_PARTS)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        CpzBossPresentation.drawFrame(renderManager, 0x23, 0x1800, 0x0500, 1);

        verify(renderer).drawFrameIndex(0x23, 0x1800, 0x0500, true, false);
    }

    @Test
    void doesNotResolveArtForAnInvalidMappingFrame() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);

        CpzBossPresentation.drawFrame(renderManager, -1, 0x1800, 0x0500, 0);

        verify(renderManager, never()).getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_PARTS);
    }
}
