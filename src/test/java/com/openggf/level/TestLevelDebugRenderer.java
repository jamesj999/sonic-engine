package com.openggf.level;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.DebugOverlayToggle;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.managers.SpriteManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLevelDebugRenderer {

    @AfterEach
    void tearDown() {
        DebugOverlayManager.getInstance().resetState();
    }

    @Test
    void objectDebugSkipsSpawnlessObjects() {
        DebugOverlayManager overlayManager = DebugOverlayManager.getInstance();
        overlayManager.resetState();
        overlayManager.setEnabled(DebugOverlayToggle.OBJECT_DEBUG, true);

        GraphicsManager graphicsManager = mock(GraphicsManager.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        SpriteManager spriteManager = mock(SpriteManager.class);
        SpawnlessObjectInstance spawnless = mock(SpawnlessObjectInstance.class);

        when(objectManager.getActiveSpawns()).thenReturn(List.of());
        when(objectManager.getActiveObjects()).thenReturn(List.of(spawnless));
        when(spawnless.getSpawn()).thenReturn(null);

        LevelDebugContext context = new LevelDebugContext(null, 16, overlayManager, graphicsManager, 320, 224);
        LevelDebugRenderer renderer = new LevelDebugRenderer(context);

        renderer.renderDebugOverlays(true, objectManager, null, spriteManager, null,
                SonicConfigurationService.getInstance(), 0);

        verify(spawnless).getSpawn();
        verify(graphicsManager).enqueueDebugLineState();
        verify(graphicsManager, never()).registerCommand(org.mockito.ArgumentMatchers.any());
    }

    private interface SpawnlessObjectInstance extends ObjectInstance {
        @Override
        default ObjectSpawn getSpawn() {
            return null;
        }

        @Override
        default void update(int vIntRunCount, PlayableEntity player) {
        }

        @Override
        default void appendRenderCommands(List<GLCommand> commands) {
        }

        @Override
        default boolean isHighPriority() {
            return false;
        }

        @Override
        default boolean isDestroyed() {
            return false;
        }
    }
}


