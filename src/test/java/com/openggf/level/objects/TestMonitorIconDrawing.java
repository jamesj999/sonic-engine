package com.openggf.level.objects;

import com.openggf.game.sonic1.objects.Sonic1MonitorPowerUpObjectInstance;
import com.openggf.game.sonic2.objects.MonitorContentsObjectInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class TestMonitorIconDrawing {
    private final ObjectRenderManager manager = mock(ObjectRenderManager.class);
    private final PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
    private final ObjectSpriteSheet sheet = mock(ObjectSpriteSheet.class);
    private final SpriteMappingPiece icon = new SpriteMappingPiece(-8, -8, 2, 2, 4, false, false, 0);

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        when(manager.getMonitorRenderer()).thenReturn(renderer);
        when(manager.getMonitorSheet()).thenReturn(sheet);
        when(renderer.isReady()).thenReturn(true);
        when(sheet.getFrameCount()).thenReturn(16);
        when(sheet.getFrame(anyInt())).thenReturn(new SpriteMappingFrame(List.of(icon,
                new SpriteMappingPiece(0, 0, 1, 1, 9, false, false, 0))));
    }

    private <T extends AbstractMonitorObjectInstance> T wire(T object) {
        object.setServices(new TestObjectServices() {
            @Override public ObjectRenderManager renderManager() { return manager; }
        });
        return object;
    }

    @Test
    void callersKeepFrameOffsetsMasksAndFirstPieceSelection() {
        var s1 = wire(new Sonic1MonitorPowerUpObjectInstance(120, 140, 0x101, null));
        var s2 = wire(new MonitorContentsObjectInstance(220, 240, 0x11, null));
        s1.appendRenderCommands(List.of());
        s2.appendRenderCommands(List.of());
        verify(sheet).getFrame(3);
        verify(sheet).getFrame(2);
        verify(renderer).drawPieces(List.of(icon), 120, 140, false, false);
        verify(renderer).drawPieces(List.of(icon), 220, 240, false, false);
    }

    @Test
    void inactiveVisibilityRemainsDifferentButDestroyedObjectsNeverDraw() {
        AbstractMonitorObjectInstance s1 = wire(new Sonic1MonitorPowerUpObjectInstance(120, 140, 1, null));
        AbstractMonitorObjectInstance s2 = wire(new MonitorContentsObjectInstance(220, 240, 1, null));
        s1.iconActive = false;
        s2.iconActive = false;
        s1.appendRenderCommands(List.of());
        s2.appendRenderCommands(List.of());
        verify(renderer).drawPieces(List.of(icon), 120, 140, false, false);
        verify(renderer, never()).drawPieces(List.of(icon), 220, 240, false, false);
        clearInvocations(renderer, manager);
        s1.setDestroyed(true);
        s2.setDestroyed(true);
        s1.appendRenderCommands(List.of());
        s2.appendRenderCommands(List.of());
        verifyNoInteractions(manager, renderer);
    }

    @Test
    void absentOrUnreadyArtAndInvalidFramesDoNotDraw() {
        var object = wire(new MonitorContentsObjectInstance(120, 140, 1, null));
        when(manager.getMonitorRenderer()).thenReturn(null);
        object.appendRenderCommands(List.of());
        when(manager.getMonitorRenderer()).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(false);
        object.appendRenderCommands(List.of());
        when(renderer.isReady()).thenReturn(true);
        when(manager.getMonitorSheet()).thenReturn(null);
        object.appendRenderCommands(List.of());
        when(manager.getMonitorSheet()).thenReturn(sheet);
        when(sheet.getFrameCount()).thenReturn(2);
        object.appendRenderCommands(List.of());
        when(sheet.getFrameCount()).thenReturn(16);
        when(sheet.getFrame(2)).thenReturn(null);
        object.appendRenderCommands(List.of());
        when(sheet.getFrame(2)).thenReturn(new SpriteMappingFrame(List.of()));
        object.appendRenderCommands(List.of());
        verify(renderer, never()).drawPieces(anyList(), anyInt(), anyInt(), anyBoolean(), anyBoolean());
    }
}
