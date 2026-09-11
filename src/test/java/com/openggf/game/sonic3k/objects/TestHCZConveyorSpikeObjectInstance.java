package com.openggf.game.sonic3k.objects;

import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHCZConveyorSpikeObjectInstance {

    {
        TestEnvironment.activeGameplayMode();
    }
    private final Camera camera = GameServices.camera();

    @AfterEach
    void tearDown() throws Exception {
        constructionContext().remove();
        SessionManager.clear();
    }

    @Test
    void registryCreatesHczConveyorSpikeForId0x3fInS3klZoneSet() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        ObjectSpawn spawn = new ObjectSpawn(0x0C40, 0x03E0, Sonic3kObjectIds.HCZ_CONVEYOR_SPIKE,
                0x02, 0, false, 0);

        ObjectInstance instance = registry.create(spawn);

        assertTrue(instance instanceof HCZConveyorSpikeObjectInstance);
    }

    @Test
    void primaryNameFor0x3fMatchesDisassemblyLabel() {
        Sonic3kObjectRegistry registry = new Sonic3kObjectRegistry();
        assertEquals("HCZConveyorSpike", registry.getPrimaryName(Sonic3kObjectIds.HCZ_CONVEYOR_SPIKE));
    }

    @Test
    void hczPlanIncludesLevelArtEntryForConveyorSpike() {
        Sonic3kPlcArtRegistry.ZoneArtPlan plan = Sonic3kPlcArtRegistry.getPlan(0x01, 0);

        Sonic3kPlcArtRegistry.LevelArtEntry spike = plan.levelArt().stream()
                .filter(e -> Sonic3kObjectArtKeys.HCZ_CONVEYOR_SPIKE.equals(e.key()))
                .findFirst()
                .orElse(null);

        assertNotNull(spike);
        assertEquals(Sonic3kConstants.MAP_HCZ_CONVEYOR_SPIKE_ADDR, spike.mappingAddr());
        assertEquals(Sonic3kConstants.ARTTILE_HCZ_CONVEYOR_SPIKE, spike.artTileBase());
        assertEquals(1, spike.palette());
    }

    @Test
    void unflippedSpikeStartsAboveAndEntersRightCurve() throws Exception {
        camera.setX((short) 0x0C00);
        TestObjectServices services = new TestObjectServices().withCamera(camera);
        HCZConveyorSpikeObjectInstance spike = buildSpike(services, 0x0CD6, 0x03E0, 0x02, 0);

        assertEquals(0x0CD6, spike.getX());
        assertEquals(0x03C8, spike.getY());
        assertEquals(0x8B, spike.getCollisionFlags());
        assertEquals(5, spike.getPriorityBucket());

        spike.update(1, null);
        assertEquals(0x0CD8, spike.getX());
        assertEquals(0x03C8, spike.getY());

        spike.update(2, null);
        assertEquals(0x0CDA, spike.getX());
        assertEquals(0x03C8, spike.getY());
    }

    @Test
    void flippedSpikeStartsBelowAndEntersLeftCurve() throws Exception {
        camera.setX((short) 0x0C00);
        TestObjectServices services = new TestObjectServices().withCamera(camera);
        HCZConveyorSpikeObjectInstance spike = buildSpike(services, 0x0BAA, 0x03E0, 0x02, 1);

        assertEquals(0x0BAA, spike.getX());
        assertEquals(0x03F8, spike.getY());

        spike.update(1, null);
        assertEquals(0x0BA8, spike.getX());
        assertEquals(0x03F8, spike.getY());

        spike.update(2, null);
        assertEquals(0x0BA5, spike.getX());
        assertEquals(0x03F7, spike.getY());
    }

    private static HCZConveyorSpikeObjectInstance buildSpike(
            ObjectServices services, int x, int y, int subtype, int renderFlags) throws Exception {
        ThreadLocal<ObjectServices> context = constructionContext();
        context.set(services);
        try {
            HCZConveyorSpikeObjectInstance spike = new HCZConveyorSpikeObjectInstance(
                    new ObjectSpawn(x, y, Sonic3kObjectIds.HCZ_CONVEYOR_SPIKE,
                            subtype, renderFlags, false, 0));
            spike.setServices(services);
            return spike;
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
}


