package com.openggf.game.sonic2;

import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.Sonic2ObjectRegistry;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tools.ObjectDiscoveryTool;
import com.openggf.tools.Sonic2ObjectProfile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the WFZ implementation checklist aligned with registered gameplay code.
 */
@RequiresRom(SonicGame.SONIC_2)
class TestSonic2WfzObjectProfile {

    @Test
    void wfzBossIsMarkedImplemented() {
        Sonic2ObjectProfile profile = new Sonic2ObjectProfile();

        assertTrue(profile.getImplementedIds().contains(0xC5),
                "ObjC5 WFZBoss has a registered implementation and must not be reported as missing");
    }

    @Test
    void wfzRuntimeSmokeObjectsAreMarkedImplemented() {
        Sonic2ObjectProfile profile = new Sonic2ObjectProfile();

        assertTrue(profile.getImplementedIds().contains(0xC3),
                "ObjC3 TornadoSmoke is spawned by WFZ ObjB2 and should be tracked as implemented");
        assertTrue(profile.getImplementedIds().contains(0xC4),
                "ObjC4 TornadoSmoke2 points to ObjC3 and should be tracked as implemented");
    }

    @Test
    void wfzPointerTableOnlyUnknownObjectIsMarkedImplemented() {
        Sonic2ObjectProfile profile = new Sonic2ObjectProfile();

        assertTrue(profile.getImplementedIds().contains(0xBB),
                "ObjBB is removed/unused but still has a ROM pointer-table routine and should be tracked");
    }

    @Test
    void wfzDebugAndPointerTableObjectsHaveConcreteFactories() {
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();

        List<Integer> ids = List.of(
                0x26, 0x8B, 0x79, 0xB3, 0xB4, 0xB5, 0xAD, 0xAE,
                0xB6, Sonic2ObjectIds.VERTICAL_LASER, 0xB8, 0xB9, 0xBA, Sonic2ObjectIds.WFZ_UNKNOWN,
                0xBC, 0xBD, 0xBE, Sonic2ObjectIds.WFZ_STICK, 0xC0, 0xC1,
                0xC2, Sonic2ObjectIds.TORNADO_SMOKE, Sonic2ObjectIds.TORNADO_SMOKE_2,
                0x19, 0xD9, 0x80, 0x3E, Sonic2ObjectIds.WFZ_BOSS);

        for (int id : ids) {
            assertTrue(registry.hasRegisteredFactory(id),
                    "WFZ debug/runtime/pointer-table ID 0x" + Integer.toHexString(id).toUpperCase()
                            + " should have a concrete factory");
        }
    }

    @Test
    void wfzToDezTransitionTargetObjectsHaveConcreteFactories() {
        Sonic2ObjectRegistry registry = new Sonic2ObjectRegistry();
        Sonic2ObjectProfile profile = new Sonic2ObjectProfile();

        List<Integer> ids = List.of(Sonic2ObjectIds.DEZ_EGGMAN, Sonic2ObjectIds.DEATH_EGG_ROBOT);
        for (int id : ids) {
            assertTrue(registry.hasRegisteredFactory(id),
                    "WFZ->DEZ target object 0x" + Integer.toHexString(id).toUpperCase()
                            + " should have a concrete factory");
            assertTrue(profile.getImplementedIds().contains(id),
                    "WFZ->DEZ target object 0x" + Integer.toHexString(id).toUpperCase()
                            + " should be counted as implemented");
        }
    }

    @Test
    void wingFortressPlacementScanHasNoUnimplementedObjects() throws IOException {
        Sonic2ObjectProfile profile = new Sonic2ObjectProfile();
        ObjectDiscoveryTool tool = new ObjectDiscoveryTool(
                RomByteReader.fromRom(GameServices.rom().getRom()),
                profile);

        ObjectDiscoveryTool.ZoneReport wfz = tool.scan().zoneReports().stream()
                .filter(report -> report.level().shortName().equals("WFZ"))
                .findFirst()
                .orElseThrow();

        assertEquals(0, wfz.unimplementedCount(),
                "Every object placed in Wing Fortress Zone should be marked implemented");
    }

    @Test
    void deathEggTransitionTargetPlacementScanHasNoUnimplementedObjects() throws IOException {
        Sonic2ObjectProfile profile = new Sonic2ObjectProfile();
        ObjectDiscoveryTool tool = new ObjectDiscoveryTool(
                RomByteReader.fromRom(GameServices.rom().getRom()),
                profile);

        ObjectDiscoveryTool.ZoneReport dez = tool.scan().zoneReports().stream()
                .filter(report -> report.level().shortName().equals("DEZ"))
                .findFirst()
                .orElseThrow();

        assertEquals(0, dez.unimplementedCount(),
                "The WFZ->DEZ transition target should not land on unimplemented DEZ layout objects");
    }
}
