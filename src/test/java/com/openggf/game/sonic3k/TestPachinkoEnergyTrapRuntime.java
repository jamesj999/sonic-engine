package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.objects.PachinkoEnergyTrapObjectInstance;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestPachinkoEnergyTrapRuntime {
    private HeadlessTestFixture fixture;

    @BeforeAll
    public static void configure() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
    }

    @BeforeEach
    public void setUp() {
        fixture = null;
    }

    @Test
    public void bootstrapTrapPersistsAndRisesInLivePachinkoLevel() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0x14, 0)
                .build();

        ObjectManager objectManager = GameServices.level().getObjectManager();
        assertNotNull(objectManager);

        ObjectSpawn spawn = new ObjectSpawn(0x78, 0x0F30, 0xE8, 0, 0, false, 0);
        PachinkoEnergyTrapObjectInstance trap = new PachinkoEnergyTrapObjectInstance(spawn);
        objectManager.addDynamicObject(trap);

        int initialY = trap.getY();
        boolean sawBeamChild = false;

        for (int i = 0; i < 320; i++) {
            fixture.stepIdleFrames(1);

            boolean trapPresent = false;
            for (ObjectInstance instance : objectManager.getActiveObjects()) {
                if (instance == trap) {
                    trapPresent = true;
                }
                if (instance instanceof AbstractObjectInstance aoi
                        && "PachinkoEnergyBeam".equals(aoi.getName())) {
                    sawBeamChild = true;
                }
            }

            assertTrue(trapPresent, "Trap should remain in active object list at frame " + i);
        }

        assertTrue(trap.getY() < initialY, "Trap should have risen after 320 frames");
        assertTrue(sawBeamChild, "Trap should have spawned beam children");
    }
}


