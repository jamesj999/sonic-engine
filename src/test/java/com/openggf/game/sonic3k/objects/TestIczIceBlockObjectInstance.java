package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestIczIceBlockObjectInstance {

    // ICZ objects only resolve in the S3KL zone set. Sonic3kObjectRegistry derives the
    // zone set from the global current level (GameServices.levelOrNull().getRomZoneId()).
    // Clear any gameplay session a prior test in this fork leaked (e.g. one that loaded an
    // SKL zone, MHZ-DDZ) so currentRomZoneId()==-1 -> S3KL default; otherwise create()
    // returns a PlaceholderObjectInstance and these assertions flake under the parallel suite.
    @BeforeEach
    void clearLeakedGameplaySession() {
        com.openggf.game.session.SessionManager.clear();
    }

    @Test
    void registryCreatesIceBlockAndProfileMarksS3klSlotImplemented() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(spawn(0x1200, 0x0700));

        assertNotEquals("PlaceholderObjectInstance", instance.getClass().getSimpleName());
        assertTrue(new Sonic3kObjectProfile().getImplementedIds().contains(Sonic3kObjectIds.ICZ_ICE_BLOCK));
    }

    @Test
    void solidGeometryMatchesRomSolidObjectTopCall() {
        ObjectInstance instance = new Sonic3kObjectRegistry().create(spawn(0x1200, 0x0700));
        SolidObjectProvider solid = assertInstanceOf(SolidObjectProvider.class, instance);

        SolidObjectParams params = solid.getSolidParams();

        assertTrue(solid.isTopSolidOnly());
        assertEquals(SolidExecutionMode.AUTO_AFTER_UPDATE, solid.solidExecutionMode());
        assertEquals(0x1B, params.halfWidth());
        assertEquals(0x10, params.airHalfHeight());
        assertEquals(0x11, params.groundHalfHeight());
    }

    private static ObjectSpawn spawn(int x, int y) {
        return new ObjectSpawn(x, y, Sonic3kObjectIds.ICZ_ICE_BLOCK, 0, 0, false, 0);
    }
}
