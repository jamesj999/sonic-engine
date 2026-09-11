package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMhzBreakableWallObjectInstance {

    @Test
    void mhzBreakableWallUsesRomZoneConfigFromConstructionContext() throws Exception {
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.BREAKABLE_WALL_MHZ)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(true);

        RecordingMhzServices services = new RecordingMhzServices(renderManager);
        BreakableWallObjectInstance wall = buildWithConstructionServices(
                new ObjectSpawn(0x1200, 0x0600, Sonic3kObjectIds.BREAKABLE_WALL, 0, 0, false, 0),
                services);

        SolidObjectParams params = wall.getSolidParams();
        assertEquals(0x1B, params.halfWidth(),
                "Obj_BreakableWall loc_214A4 stores MHZ width_pixels=$10 and SolidObject adds $0B");
        assertEquals(0x20, params.airHalfHeight(),
                "Obj_BreakableWall loc_214A4 stores MHZ height_pixels=$20");
        assertEquals(0x21, params.groundHalfHeight(),
                "BreakableWall passes height_pixels+1 as the grounded solid height");
        assertEquals(5, wall.getPriorityBucket(),
                "Obj_BreakableWall writes priority=$280 before the MHZ setup branch");

        wall.appendRenderCommands(new ArrayList<GLCommand>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.BREAKABLE_WALL_MHZ);
        verify(renderer).drawFrameIndex(0, 0x1200, 0x0600, false, false);
    }

    private static BreakableWallObjectInstance buildWithConstructionServices(
            ObjectSpawn spawn, ObjectServices services) throws Exception {
        ThreadLocal<ObjectServices> context = constructionContext();
        context.set(services);
        try {
            BreakableWallObjectInstance wall = new BreakableWallObjectInstance(spawn);
            wall.setServices(services);
            return wall;
        } finally {
            context.remove();
        }
    }

    @SuppressWarnings("unchecked")
    private static ThreadLocal<ObjectServices> constructionContext() throws Exception {
        Field field = AbstractObjectInstance.class.getDeclaredField("CONSTRUCTION_CONTEXT");
        field.setAccessible(true);
        return (ThreadLocal<ObjectServices>) field.get(null);
    }

    private static final class RecordingMhzServices extends TestObjectServices {
        private final ObjectRenderManager renderManager;

        private RecordingMhzServices(ObjectRenderManager renderManager) {
            this.renderManager = renderManager;
        }

        @Override
        public int romZoneId() {
            return Sonic3kZoneIds.ZONE_MHZ;
        }

        @Override
        public ObjectRenderManager renderManager() {
            return renderManager;
        }
    }
}
