package com.openggf.game.sonic1.objects;

import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1JunctionObjectInstance {

    @Test
    void constructorMatchesJunMainSeedState() throws Exception {
        SessionManager.clear();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());

        Sonic1JunctionObjectInstance junction = new Sonic1JunctionObjectInstance(
                new ObjectSpawn(0x1490, 0x0170, 0x66, 0x00, 0, false, 0));
        try {
            assertEquals(1, getPrivateInt(junction, "frameDirection"));
            assertEquals(0, getPrivateInt(junction, "mappingFrame"));
            assertEquals(0, getPrivateInt(junction, "frameTimer"));
        } finally {
            SessionManager.clear();
        }
    }

    @Test
    void solidProfileKeepsRightEdgeInclusiveForS1SolidObject() {
        Sonic1JunctionObjectInstance junction = new Sonic1JunctionObjectInstance(
                new ObjectSpawn(0x1490, 0x0170, 0x66, 0x00, 0, false, 0));

        SolidRoutineProfile profile = junction.getSolidRoutineProfile();

        assertTrue(profile.inclusiveRightEdge(),
                "S1 SolidObject uses bhi on the right edge, so equality remains a side contact");
    }

    private static int getPrivateInt(Object instance, String fieldName) throws Exception {
        Field field = Sonic1JunctionObjectInstance.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(instance);
    }
}
