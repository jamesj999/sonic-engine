package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameRng;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.events.Sonic3kMHZEvents;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.physics.TrigLookupTable;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TestMhzPollenObjects {
    private static final ObjectSpawn SPAWNER_SPAWN = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);

    @AfterEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void groundedFastPlayerSpawnsSpringPollenAndReservesRuntimeCounter() {
        Harness harness = new Harness(false);
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0x0600);

        harness.spawner.update(0, player);

        MhzPollenParticleInstance particle = harness.singleParticle();
        assertEquals(MhzPollenParticleInstance.ArtMode.POLLEN, particle.getArtMode());
        assertEquals(0x1200, particle.getX());
        assertEquals(0x0710, particle.getY());
        assertEquals(-0x210, particle.getVelocityY());
        assertTrue(particle.getGravityStep() >= 2 && particle.getGravityStep() <= 5);
        assertEquals(1, harness.runtimeState.pollenParticleCount());
    }

    @Test
    void autumnSeasonUsesLeafSelectionTableFromRuntimeSeasonFlag() {
        Harness harness = new Harness(true);
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0x0600);

        harness.spawner.update(0, player);

        MhzPollenParticleInstance particle = harness.singleParticle();
        assertEquals(MhzPollenParticleInstance.ArtMode.BIG_LEAF, particle.getArtMode());
        assertEquals(1, harness.runtimeState.pollenLeafPatternCounter());
    }

    @Test
    void capAtSixteenParticlesPreventsFurtherSpawns() {
        Harness harness = new Harness(false);
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0x0600);
        for (int i = 0; i < 16; i++) {
            assertTrue(harness.runtimeState.tryReservePollenParticle());
        }

        harness.spawner.update(0, player);

        assertEquals(0, harness.spawned.size());
        assertEquals(16, harness.runtimeState.pollenParticleCount());
    }

    @Test
    void sidekickPollenOnlyProcessesWhenRenderFlagsReportsOnScreen() {
        Harness harness = new Harness(false);
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0);
        TestablePlayableSprite sidekick = groundedPlayer(0x1300, 0x0700, 0x0600);
        sidekick.setRenderFlagOnScreen(false);
        harness.services.withSidekicks(List.of(sidekick));

        harness.spawner.update(0, player);

        assertEquals(0, harness.spawned.size(),
                "Obj_MHZ_Pollen_Spawner returns before Player_2 when render_flags(a2) is non-negative");
        assertEquals(0, harness.runtimeState.pollenParticleCount());
    }

    @Test
    void nativeP2PollenUsesObjectPlayerQueryWhenRawSidekicksAreEmpty() {
        Harness harness = new Harness(false);
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0);
        player.setTopSolidBit((byte) 0);
        TestablePlayableSprite nativeP2 = groundedPlayer(0x1300, 0x0700, 0x0600);
        harness.queriedSidekicks = List.of(nativeP2);

        harness.spawner.update(0, player);

        MhzPollenParticleInstance particle = harness.singleParticle();
        assertEquals(0x1300, particle.getX(),
                "Obj_MHZ_Pollen_Spawner processes native Player_2 via the P2 RAM slot, not the raw engine sidekick list");
        assertEquals(1, harness.runtimeState.pollenParticleCount());
    }

    @Test
    void nativeP2StillProcessesAfterPlayerOneFillsRomEntryGate() {
        Harness harness = new Harness(false);
        for (int i = 0; i < 15; i++) {
            assertTrue(harness.runtimeState.tryReservePollenParticle());
        }
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0x0600);
        TestablePlayableSprite nativeP2 = groundedPlayer(0x1300, 0x0700, 0x0600);
        nativeP2.setRenderFlagOnScreen(true);
        harness.queriedSidekicks = List.of(nativeP2);

        harness.spawner.update(0, player);

        assertEquals(2, harness.spawned.size(),
                "Obj_MHZ_Pollen_Spawner checks MHZ_pollen_counter before Player_1 only; Player_2 still runs after Player_1 increments it to $10");
        assertEquals(0x1200, ((MhzPollenParticleInstance) harness.spawned.get(0)).getX());
        assertEquals(0x1300, ((MhzPollenParticleInstance) harness.spawned.get(1)).getX());
        assertEquals(17, harness.runtimeState.pollenParticleCount());
    }

    @Test
    void landingBurstUsesOneRomRandomValuePerParticle() {
        Harness harness = new Harness(false);
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0);
        player.setAirForTest(true);
        player.setYSpeed((short) 0x0500);
        harness.spawner.update(0, player);

        player.setAirForTest(false);
        harness.spawner.update(1, player);

        assertEquals(6, harness.spawned.size(),
                "loc_3DACA allocates six pollen particles for a hard landing burst");
        int[][] expected = {
                {0x1200, 0x0718, 8, -164, 2},
                {0x1200, 0x0718, 176, -200, 2},
                {0x1200, 0x0718, 316, -304, 2},
                {0x1200, 0x0718, -45, -188, 5},
                {0x1200, 0x0718, -229, -147, 5},
                {0x1200, 0x0718, -443, -155, 3},
        };
        for (int i = 0; i < expected.length; i++) {
            MhzPollenParticleInstance particle =
                    assertInstanceOf(MhzPollenParticleInstance.class, harness.spawned.get(i));
            assertEquals(expected[i][0], particle.getX(), "burst particle " + i + " x_pos");
            assertEquals(expected[i][1], particle.getY(), "burst particle " + i + " y_pos");
            assertEquals(expected[i][2], particle.getVelocityX(), "burst particle " + i + " x_vel");
            assertEquals(expected[i][3], particle.getVelocityY(), "burst particle " + i + " y_vel");
            assertEquals(expected[i][4], particle.getGravityStep(),
                    "loc_3DAD6 derives $34 from the same Random_Number value used for x_vel/y_vel");
        }
        assertEquals(6, harness.runtimeState.pollenParticleCount());
        assertEquals(0, harness.spawner.getPlayerOneStoredYVelocity(),
                "sub_3DA24 clears the stored landing velocity after the hard-landing burst");
    }

    @Test
    void landingBurstUsesRomUnsignedWordComparisonForStoredVelocity() {
        Harness harness = new Harness(false);
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0);
        player.setAirForTest(true);
        player.setYSpeed((short) 0xFFF0);
        harness.spawner.update(0, player);

        player.setAirForTest(false);
        harness.spawner.update(1, player);

        assertEquals(6, harness.spawned.size(),
                "cmpi.w #$400 / bhs treats a negative stored y_vel as an unsigned word and takes loc_3DACA");
    }

    @Test
    void landingBurstCanExceedParticleGateBecauseRomChecksCounterBeforePlayerRoutine() {
        Harness harness = new Harness(false);
        for (int i = 0; i < 15; i++) {
            assertTrue(harness.runtimeState.tryReservePollenParticle());
        }
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0);
        player.setAirForTest(true);
        player.setYSpeed((short) 0x0500);
        harness.spawner.update(0, player);

        player.setAirForTest(false);
        harness.spawner.update(1, player);

        assertEquals(6, harness.spawned.size(),
                "Obj_MHZ_Pollen_Spawner gates MHZ_pollen_counter before sub_3DA24, so loc_3DACA still allocates all six burst particles");
        assertEquals(21, harness.runtimeState.pollenParticleCount(),
                "The ROM burst path increments MHZ_pollen_counter per particle after the entry gate and can temporarily exceed $10");
    }

    @Test
    void landingBurstPreservesRandomAngleWhenEnteringFloatRoutine() {
        Harness harness = new Harness(false);
        TestablePlayableSprite player = groundedPlayer(0x1200, 0x0700, 0);
        player.setAirForTest(true);
        player.setYSpeed((short) 0x0500);
        harness.spawner.update(0, player);

        player.setAirForTest(false);
        harness.spawner.update(1, player);

        AbstractObjectInstance.updateCameraBounds(0x1000, 0x0600, 0x1400, 0x0800, 0);
        MhzPollenParticleInstance particle =
                assertInstanceOf(MhzPollenParticleInstance.class, harness.spawned.get(0));
        particle.setServices(harness.services);
        for (int frame = 2; frame < 24; frame++) {
            particle.update(frame, null);
        }

        // Task 10: loc_3DAD6 `move.w d0,angle(a1)` writes the byte-sized angle
        // field from the HIGH byte of the low word (big-endian first byte of
        // the word write); for this seed/call sequence raw=$A43488, so the
        // seed angle is $34, not the low byte $88.
        assertEquals(TrigLookupTable.sinHex(0x34), particle.getVelocityX(),
                "loc_3DC18 switches to loc_3DBE0 without replacing the random angle written at loc_3DAD6");
        assertNotEquals(TrigLookupTable.sinHex(0x88), particle.getVelocityX(),
                "the burst-particle seed angle must come from the high byte ($34) of the RNG word, not the low byte ($88)");
    }

    @Test
    void floatingParticleOffscreenCleanupWritesRomSentinelAndReleasesCounter() {
        Harness harness = new Harness(false);
        assertTrue(harness.runtimeState.tryReservePollenParticle());
        MhzPollenParticleInstance particle = new MhzPollenParticleInstance(
                0x1200, 0x0700, 0, 0, 2, 0, MhzPollenParticleInstance.ArtMode.POLLEN);
        particle.setServices(harness.services);

        AbstractObjectInstance.updateCameraBounds(0x1000, 0x0600, 0x1400, 0x0800, 0);
        particle.update(0, null);
        AbstractObjectInstance.updateCameraBounds(0, 0, 0x0100, 0x0100, 0);
        particle.update(1, null);

        assertEquals(0x7F00, particle.getX(),
                "loc_3DBE0 writes x_pos=$7F00 when render_flags reports the pollen off-screen");
        assertEquals(0, harness.runtimeState.pollenParticleCount());
    }

    @Test
    void floatingParticleUsesPriorRenderBoundsWithoutGenericOffscreenMargin() {
        Harness harness = new Harness(false);
        assertTrue(harness.runtimeState.tryReservePollenParticle());
        MhzPollenParticleInstance particle = new MhzPollenParticleInstance(
                0x02DF, 0x055D, 0, 0, 2, 0, MhzPollenParticleInstance.ArtMode.POLLEN);
        particle.setServices(harness.services);

        AbstractObjectInstance.updateCameraBounds(0x02E9, 0x054C, 0x0429, 0x062C, 0);
        particle.snapshotPreUpdatePosition();
        particle.update(0, null);
        particle.snapshotPreUpdatePosition();
        particle.update(1, null);

        assertEquals(0x7F00, particle.getX(),
                "loc_3DBE0 reads the prior Render_Sprites flag using width_pixels=4; "
                        + "the generic 32-pixel object margin must not retain pollen left of camera");
        assertEquals(0, harness.runtimeState.pollenParticleCount());
    }

    @Test
    void risingParticleOffscreenDoesNotReleaseCounterUntilFloatRoutine() {
        Harness harness = new Harness(false);
        assertTrue(harness.runtimeState.tryReservePollenParticle());
        MhzPollenParticleInstance particle = new MhzPollenParticleInstance(
                0x1200, 0x0700, 0, -0x0200, 2, 0, MhzPollenParticleInstance.ArtMode.POLLEN);
        particle.setServices(harness.services);

        AbstractObjectInstance.updateCameraBounds(0, 0, 0x0100, 0x0100, 0);
        particle.update(0, null);

        assertEquals(0x1200, particle.getX(),
                "Obj_MHZ_Pollen and loc_3DC18 end with Draw_Sprite while rising; only loc_3DBE0 writes x_pos=$7F00");
        assertEquals(1, harness.runtimeState.pollenParticleCount(),
                "MHZ_pollen_counter is decremented only by the floating loc_3DBE0 offscreen path");
        assertTrue(!particle.isDestroyed(),
                "Draw_Sprite should leave an offscreen rising particle alive until it reaches loc_3DBE0");
    }

    private static TestablePlayableSprite groundedPlayer(int x, int y, int xSpeed) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        player.setTopSolidBit((byte) 0x0C);
        player.setAir(false);
        player.setXSpeed((short) xSpeed);
        return player;
    }

    private static final class Harness {
        private final MhzZoneRuntimeState runtimeState;
        private final MhzPollenSpawnerInstance spawner;
        private final List<ObjectInstance> spawned = new ArrayList<>();
        private final TestObjectServices services;
        private List<TestablePlayableSprite> queriedSidekicks = List.of();

        private Harness(boolean seasonFlagSet) {
            Sonic3kMHZEvents events = new Sonic3kMHZEvents();
            if (seasonFlagSet) {
                events.applySeasonStateForTest(Sonic3kMHZEvents.SeasonPaletteMode.AUTUMN);
            }
            runtimeState = new MhzZoneRuntimeState(0, PlayerCharacter.SONIC_ALONE, events);
            ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
            registry.install(runtimeState);

            ObjectManager objectManager = mock(ObjectManager.class);
            doAnswer(invocation -> {
                ObjectInstance object = invocation.getArgument(0);
                spawned.add(object);
                return null;
            }).when(objectManager).addDynamicObjectAfterCurrent(any(ObjectInstance.class));

            services = new TestObjectServices() {
                @Override
                public ObjectManager objectManager() {
                    return objectManager;
                }

                @Override
                public ObjectPlayerQuery playerQuery() {
                    return new ObjectPlayerQuery(
                            () -> null,
                            () -> queriedSidekicks.isEmpty() ? sidekicks() : queriedSidekicks);
                }
            };
            services.withRng(new GameRng(GameRng.Flavour.S3K, 4))
                    .withZoneRuntimeRegistry(registry);

            spawner = new MhzPollenSpawnerInstance(SPAWNER_SPAWN);
            spawner.setServices(services);
        }

        private MhzPollenParticleInstance singleParticle() {
            assertEquals(1, spawned.size());
            return assertInstanceOf(MhzPollenParticleInstance.class, spawned.get(0));
        }
    }
}
