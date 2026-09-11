package com.openggf.game.sonic1;

import com.openggf.game.GameServices;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.palette.PaletteSurface;
import com.openggf.game.palette.PaletteWriteSupport;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sonic 1 has no palette cycler that resolves {@link PaletteOwnershipRegistry}
 * (Sonic1PaletteCycler writes palettes directly), so registry-submitted writes
 * such as the shared boss hit-flash rely on the game-agnostic frame fallback
 * ({@code PaletteWriteSupport.resolvePendingFrameWrites}, invoked by
 * LevelRenderer after the animated palette update). Without it, the next
 * {@code beginFrame()} silently dropped every submitted write.
 */
@RequiresRom(SonicGame.SONIC_1)
class TestSonic1PaletteOwnershipFallbackResolution {

    private static final int ZONE_GHZ_REGISTRY_INDEX = 0;
    private static final String FLASH_OWNER = "test.bossFlash";
    private static final int FLASH_PRIORITY = 200;
    private static final int FLASH_LINE = 1;
    private static final int FLASH_COLOR = 5;
    private static final int SEGA_WHITE = 0x0EEE;

    @Test
    void bossFlashStyleWriteResolvesThroughSharedFrameFallback() {
        loadGhz();

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        Level level = GameServices.level().getCurrentLevel();
        registry.beginFrame();

        // Same path as AbstractBossInstance.applyFlashColor: with a registry
        // attached, the write is submitted, not applied directly.
        PaletteWriteSupport.applyColor(registry, level, null,
                FLASH_OWNER, FLASH_PRIORITY, FLASH_LINE, FLASH_COLOR, SEGA_WHITE);
        assertFalse(isWhite(level), "registry submit path must defer the write");

        // The S1 cycler writes palettes directly and never resolves the registry.
        assertNotNull(GameServices.level().getAnimatedPaletteManager());
        GameServices.level().getAnimatedPaletteManager().update();
        assertFalse(registry.hasResolvedThisFrame(),
                "S1 animated palette manager is not expected to resolve the registry");

        // Shared fallback (LevelRenderer.resolvePendingPaletteOwnershipWrites).
        PaletteWriteSupport.resolvePendingFrameWrites(registry, level, null, null);

        assertTrue(registry.hasResolvedThisFrame());
        assertEquals(FLASH_OWNER, registry.ownerAt(PaletteSurface.NORMAL, FLASH_LINE, FLASH_COLOR));
        assertTrue(isWhite(level), "submitted boss-flash write must land in the live palette");
    }

    @Test
    void beginFrameResetsTheResolvedFlagSoTheNextFrameResolvesAgain() {
        loadGhz();

        PaletteOwnershipRegistry registry = GameServices.paletteOwnershipRegistry();
        Level level = GameServices.level().getCurrentLevel();

        registry.beginFrame();
        PaletteWriteSupport.applyColor(registry, level, null,
                FLASH_OWNER, FLASH_PRIORITY, FLASH_LINE, FLASH_COLOR, SEGA_WHITE);
        PaletteWriteSupport.resolvePendingFrameWrites(registry, level, null, null);
        assertTrue(registry.hasResolvedThisFrame());

        registry.beginFrame();
        assertFalse(registry.hasResolvedThisFrame());
        PaletteWriteSupport.applyColor(registry, level, null,
                FLASH_OWNER, FLASH_PRIORITY, FLASH_LINE, FLASH_COLOR, 0x0000);
        PaletteWriteSupport.resolvePendingFrameWrites(registry, level, null, null);
        assertFalse(isWhite(level), "next frame's write must resolve after beginFrame reset the flag");
    }

    @Test
    void sharedFallbackUsesFeatureRemappedWaterPaletteKey() throws IOException {
        String renderer = Files.readString(Path.of("src/main/java/com/openggf/level/LevelRenderer.java"));

        assertTrue(renderer.contains("lm.waterSystem.getUnderwaterPalette(lm.getFeatureZoneId(), lm.getFeatureActId())"),
                "fallback underwater lookup must use the same feature-remapped key used when water configs are stored");
        assertFalse(renderer.contains("lm.waterSystem.getUnderwaterPalette(lm.level.getZoneIndex(), lm.currentAct)"),
                "loaded level ids diverge from feature ids for remapped zones such as S1 SBZ3");
    }

    private static void loadGhz() {
        GraphicsManager.getInstance().initHeadless();
        HeadlessTestFixture.builder()
                .withZoneAndAct(ZONE_GHZ_REGISTRY_INDEX, 0)
                .build();
    }

    private static boolean isWhite(Level level) {
        var color = level.getPalette(FLASH_LINE).getColor(FLASH_COLOR);
        return (color.r & 0xFF) == 255 && (color.g & 0xFF) == 255 && (color.b & 0xFF) == 255;
    }
}
