package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kInvisibleObjectRenderIsolation {

    @Test
    void invisibleObjectsNeverSubmitGeometryToNormalRenderPass() {
        for (AbstractObjectInstance object : invisibleObjects()) {
            List<GLCommand> normalCommands = new ArrayList<>();

            object.appendRenderCommands(normalCommands);

            assertTrue(normalCommands.isEmpty(),
                    () -> object.getClass().getSimpleName() + " leaked debug geometry into normal rendering");
        }
    }

    @Test
    void invisibleObjectDiagnosticsRemainAvailableToObjectDebugOverlay() {
        for (AbstractObjectInstance object : invisibleObjects()) {
            DebugRenderContext debugContext = new DebugRenderContext();

            object.appendDebugRenderCommands(debugContext);

            assertFalse(debugContext.getGeometryCommands().isEmpty(),
                    () -> object.getClass().getSimpleName() + " lost its object-debug geometry");
        }
    }

    @Test
    void autoSpinDebugGeometryRetainsBoxCenterCrossAndTriggerAxis() {
        AutoSpinObjectInstance autoSpin =
                new AutoSpinObjectInstance(spawn(Sonic3kObjectIds.AUTO_SPIN, 0x04));
        DebugRenderContext debugContext = new DebugRenderContext();

        autoSpin.appendDebugRenderCommands(debugContext);

        assertEquals(14, debugContext.getGeometryCommands().size());
    }

    private static List<AbstractObjectInstance> invisibleObjects() {
        return List.of(
                new AutoSpinObjectInstance(spawn(Sonic3kObjectIds.AUTO_SPIN, 0x04)),
                new Sonic3kInvisibleBlockObjectInstance(spawn(Sonic3kObjectIds.INVISIBLE_BLOCK, 0x42)),
                new Sonic3kInvisibleHurtBlockHObjectInstance(
                        spawn(Sonic3kObjectIds.INVISIBLE_HURT_BLOCK_H, 0x42)),
                new Sonic3kInvisibleHurtBlockVObjectInstance(
                        spawn(Sonic3kObjectIds.INVISIBLE_HURT_BLOCK_V, 0x42)),
                new Sonic3kTwistedRampObjectInstance(spawn(Sonic3kObjectIds.TWISTED_RAMP, 0)),
                new SinkingMudObjectInstance(spawn(Sonic3kObjectIds.SINKING_MUD, 0x04)));
    }

    private static ObjectSpawn spawn(int objectId, int subtype) {
        return new ObjectSpawn(0x100, 0x200, objectId, subtype, 0, false, 0);
    }
}
