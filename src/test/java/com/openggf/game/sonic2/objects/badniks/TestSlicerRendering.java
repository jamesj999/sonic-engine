package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSlicerRendering {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void placementOrientationSurvivesEveryBodyFrame(int flags) throws Exception {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic2ObjectArtKeys.SLICER)).thenReturn(renderer);

        // MTZ2's ceiling placement: (0528, 0450), subtype 28, y_flip set.
        // Include both facing directions and upright controls.
        SlicerBadnikInstance slicer = new SlicerBadnikInstance(
                new ObjectSpawn(0x528, 0x450, Sonic2ObjectIds.SLICER, 0x28, flags, false, 0));
        slicer.setServices(new StubObjectServices() {
            @Override
            public ObjectRenderManager renderManager() {
                return renderManager;
            }
        });

        var frame = AbstractBadnikInstance.class.getDeclaredField("animFrame");
        frame.setAccessible(true);
        // ObjA1 uses 0/2 for walking, 3 for windup, and 4 after throwing.
        for (int mappingFrame : new int[] {0, 2, 3, 4}) {
            frame.setInt(slicer, mappingFrame);
            slicer.appendRenderCommands(new ArrayList<>());
            verify(renderer).drawFrameIndex(mappingFrame, 0x528, 0x450,
                    (flags & 1) != 0, (flags & 2) != 0);
        }
    }
}
