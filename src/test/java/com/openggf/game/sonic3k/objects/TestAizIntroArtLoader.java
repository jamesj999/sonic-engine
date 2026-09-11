package com.openggf.game.sonic3k.objects;

import com.openggf.tests.TestEnvironment;

import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestAizIntroArtLoader {
    private SharedLevel sharedLevel;

    @BeforeEach
    public void setUp() throws Exception {
        AizIntroArtLoader.reset();
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, 0, 0);
    }

    @AfterEach
    public void tearDown() {
        AizIntroArtLoader.reset();
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    @Test
    public void planeRendererCanBeCachedAfterServiceBackedLoadCompletes() {
        DefaultObjectServices services = TestEnvironment.objectServices();

        AizIntroArtLoader.loadAllIntroArt(services);

        PatternSpriteRenderer renderer = AizIntroArtLoader.getPlaneRenderer(services);
        assertNotNull(renderer);
        assertTrue(renderer.isReady());
    }

    @Test
    public void animatedPlanePiecesUsePlaneArtRatherThanChaosEmeraldArt() {
        DefaultObjectServices services = TestEnvironment.objectServices();
        AizIntroArtLoader.loadAllIntroArt(services);
        AizPlaneIntroInstance intro = new AizPlaneIntroInstance(
                new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0));
        AizIntroPlaneChild plane = new AizIntroPlaneChild(
                new ObjectSpawn(0x3E, 0x5C, 0, 0, 0, false, 0), intro);
        AizIntroEmeraldGlowChild piece = new AizIntroEmeraldGlowChild(
                new ObjectSpawn(0x76, 0x60, 0, 0, 0, false, 0), plane, 0);
        piece.setServices(services);

        PatternSpriteRenderer renderer = piece.planePieceRenderer();

        assertSame(AizIntroArtLoader.getPlaneRenderer(services), renderer,
                "AIZ intro propeller/booster mappings belong to Map_AIZIntroPlane");
        assertNotSame(AizIntroArtLoader.getEmeraldRenderer(services), renderer,
                "plane pieces must never select frames from the Chaos Emerald sheet");
    }

    @Test
    public void resetClearsActiveObjectServicesReference() throws Exception {
        DefaultObjectServices services = TestEnvironment.objectServices();
        Field activeServices = AizIntroArtLoader.class.getDeclaredField("activeServices");
        activeServices.setAccessible(true);
        activeServices.set(null, services);

        AizIntroArtLoader.reset();

        assertNull(activeServices.get(null),
                "reset must not retain a gameplay-scoped ObjectServices reference");
    }
}
