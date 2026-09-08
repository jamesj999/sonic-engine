package com.openggf.mods.code;

import com.openggf.game.modzone.ModZoneAdapter;
import com.openggf.game.modzone.ModZoneLevelData;
import com.openggf.game.modzone.ModZoneRuntimeProfile;
import com.openggf.audio.GameAudioProfile;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.game.AbstractStandaloneGameModule;
import com.openggf.game.GameDataSource;
import com.openggf.game.GameModule;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.ZoneRegistry;
import com.openggf.game.patch.DelegatingGameModule;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.Sonic2ModZoneAdapter;
import com.openggf.level.Level;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectPlacementEncoding;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.TouchResponseTable;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestModZoneAdapterRouting {

    @Test
    void publishedAdapterExposesOnlyTheThreeHostOperations() {
        Set<String> methods = java.util.Arrays.stream(ModZoneAdapter.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("validate", "load", "runtimeProfile"), methods);
    }

    @Test
    void aConcreteModuleWithoutAnOverrideUsesTheExactUnsupportedAdapter() {
        GameModule module = minimalModule();

        assertSame(GameModule.EMPTY_MOD_ZONE_ADAPTER, module.getModZoneAdapter());
    }

    @Test
    void sonic2ModuleReturnsOneStableTypedAdapter() {
        Sonic2GameModule module = new Sonic2GameModule();

        ModZoneAdapter adapter = module.getModZoneAdapter();
        assertInstanceOf(Sonic2ModZoneAdapter.class, adapter);
        assertSame(adapter, module.getModZoneAdapter());
    }

    @Test
    void sonic2AdapterBuildsThroughTheImmutableDefinitionLoader(@TempDir Path directory) throws Exception {
        RingSpriteSheet ringSheet = new RingSpriteSheet(
                new Pattern[0], List.of(), 1, 8, 0, 0);
        Sonic2GameModule module = new Sonic2GameModule() {
            @Override
            public synchronized RingSpriteSheet getAdditiveLevelRingSpriteSheet() {
                return ringSheet;
            }
        };
        byte[] romBytes = new byte[Sonic2Constants.SONIC_TAILS_PALETTE_ADDR
                + Palette.PALETTE_SIZE_IN_ROM];
        romBytes[Sonic2Constants.SONIC_TAILS_PALETTE_ADDR + 2] = 0x0E;
        romBytes[Sonic2Constants.SONIC_TAILS_PALETTE_ADDR + 3] = (byte) 0xEE;
        Path path = directory.resolve("palette-source.gen");
        Files.write(path, romBytes);
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(path.toString()));
            module.createGame(rom);
            ModLevelDefinition definition = levelDefinition();

            Level adapted = module.getModZoneAdapter().load("alpha",
                    TestS3kModZoneAdapter.hostData(definition));
            Level direct = ModZoneLoader.load(definition, ringSheet);

            assertEquals(direct.getClass(), adapted.getClass());
            assertEquals(direct.getZoneIndex(), adapted.getZoneIndex());
            assertEquals(direct.getMinX(), adapted.getMinX());
            assertEquals(direct.getMaxX(), adapted.getMaxX());
            assertEquals(direct.getMinY(), adapted.getMinY());
            assertEquals(direct.getMaxY(), adapted.getMaxY());
            assertEquals(direct.getMap().getWidth(), adapted.getMap().getWidth());
            assertEquals(direct.getMap().getHeight(), adapted.getMap().getHeight());
            assertSame(ringSheet, adapted.getRingSpriteSheet());
            Palette.Color hostColor = adapted.getPalette(0).getColor(1);
            assertEquals(255, Byte.toUnsignedInt(hostColor.r));
            assertEquals(255, Byte.toUnsignedInt(hostColor.g));
            assertEquals(255, Byte.toUnsignedInt(hostColor.b));
        }
    }

    @Test
    void runtimeProfileRejectsANullScrollPolicy() {
        assertThrows(NullPointerException.class,
                () -> new ModZoneRuntimeProfile(null, false, false, false, false));
    }

    @Test
    void delegatingModuleForwardsTheExactAdapterInstance() {
        ModZoneAdapter adapter = mock(ModZoneAdapter.class);
        GameModule base = moduleWithAdapter(adapter);
        GameModule decorated = new DelegatingGameModule(base, "test") {};

        assertSame(adapter, decorated.getModZoneAdapter());
    }

    @Test
    void aModuleWithoutAnAdapterRejectsAdditiveZones() {
        assertThrows(com.openggf.game.modzone.ModZoneRegistrationException.class,
                () -> GameModule.EMPTY_MOD_ZONE_ADAPTER.validate("alpha",
                        TestS3kModZoneAdapter.hostData(levelDefinition())));
    }

    private static GameModule moduleWithAdapter(ModZoneAdapter adapter) {
        GameModule module = mock(GameModule.class);
        when(module.getModZoneAdapter()).thenReturn(adapter);
        return module;
    }

    private static GameModule minimalModule() {
        return new AbstractStandaloneGameModule() {
            @Override public String getIdentifier() { return "minimal"; }
            @Override public Game createGame(GameDataSource source) { return null; }
            @Override public TouchResponseTable createTouchResponseTable(GameDataSource source) { return null; }
            @Override public ObjectRegistry createObjectRegistry() { return null; }
            @Override public ObjectPlacementEncoding getObjectPlacementEncoding() { return null; }
            @Override public GameAudioProfile getAudioProfile() { return null; }
            @Override public ZoneRegistry getZoneRegistry() { return null; }
            @Override public PhysicsProvider getPhysicsProvider() { return null; }
        };
    }

    private static ModLevelDefinition levelDefinition() {
        return new ModLevelDefinition(1, "ZONE", 0x40, 0x400, 8, 1, 1,
                new ModLevelDefinition.Bounds(0, 0x100, 0, 0x100),
                new ModLevelDefinition.Start(0x20, 0x20),
                new ModLevelDefinition.StockMusic(1), List.of(), List.of(),
                new byte[32], new byte[8], new byte[128], new byte[1], null,
                new byte[16], new byte[16], new byte[1], new int[]{0}, new int[]{0},
                new byte[][]{new byte[32], new byte[32], new byte[32], new byte[32]},
                1, 1, 1, 1);
    }
}
