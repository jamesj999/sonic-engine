package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestCutsceneKnucklesLbz1ThrownBomb {

    @Test
    void rendersRomBombFrameWithObjectPriorityBit() {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.LBZ1_CUTSCENE_KNUCKLES_BOMB))
                .thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        CutsceneKnucklesLbz1ThrownBomb bomb =
                new CutsceneKnucklesLbz1ThrownBomb(0x3C20, 0x00E0);
        bomb.setServices(new TestObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        bomb.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.LBZ1_CUTSCENE_KNUCKLES_BOMB);
        verify(renderer).drawFrameIndexForcedPriority(0, 0x3C20, 0x00E0, false, false, -1, true);
    }
}
