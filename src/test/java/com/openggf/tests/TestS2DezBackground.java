package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.snapshot.ParallaxSnapshot;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.LevelTilemapManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROM-backed DEZ Plane B checks.
 *
 * <p>REV01's {@code InitCameraValues} seeds the BG camera, and
 * {@code InitCam_Null3} intentionally preserves that initial value. The
 * initial DEZ layout has a black 128px band above the star field, so using a
 * zero BG-Y origin makes the exterior window appear empty even though its ROM
 * art is loaded.</p>
 */
@RequiresRom(SonicGame.SONIC_2)
class TestS2DezBackground {
    private static final int DEZ_ZONE = Sonic2ZoneConstants.ZONE_DEZ;
    // REV01's CPZ_DEZ layout decodes the static exterior star run at these
    // pattern IDs ($226-$23F). This is distinct from the animated background
    // stream at ArtTile_ArtUnc_DEZAnimBack=$326 (s2.constants.asm:2502), which
    // the ROM's Animated_DEZ declaration writes into its dynamic VRAM slots.
    private static final int STAR_PATTERN_FIRST = 0x226;
    private static final int STAR_PATTERN_LIMIT = 0x240;

    private SharedLevel sharedLevel;

    @BeforeEach
    void load() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, DEZ_ZONE, 0);
    }

    @AfterEach
    void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
        }
    }

    @Test
    void initialWindowUsesRomBgYAndContainsLoadedStars() {
        Level level = sharedLevel.level();
        LevelManager levelManager = GameServices.level();

        updateParallax(0);
        assertEquals(0x0E, level.getZoneIndex(), "DEZ must load the ROM zone, not an internal index");
        assertEquals(GameServices.camera().getY(),
                GameServices.parallax().getVscrollFactorBG() & 0xFFFF,
                "DEZ Plane B must start at ROM Camera_BG_Y_pos seeded by InitCameraValues and preserved by InitCam_Null3");

        ensureBackgroundTilemap(levelManager);
        LevelTilemapManager tilemaps = levelManager.getTilemapManager();
        int tilemapStars = countStarDescriptors(tilemaps.getBackgroundTilemapData(),
                tilemaps.getBackgroundTilemapWidthTiles(),
                tilemaps.getBackgroundTilemapHeightTiles());
        assertTrue(tilemapStars > 0, "CPZ_DEZ layout must publish star descriptors to Plane B");

        Pattern starPattern = level.getPattern(STAR_PATTERN_FIRST);
        int[] starPixels = nonTransparentPixelValues(starPattern);
        assertTrue(starPixels.length > 0, "ROM star pattern must contain visible pixels");
        assertTrue(nonBlackPaletteEntries(level.getPalette(2), starPixels) > 0,
                "DEZ star palette line must contain a non-black ROM color");

        int visibleStars = countVisibleStars(levelManager, GameServices.parallax().getHScrollForShader(),
                GameServices.parallax().getVscrollFactorBG() & 0xFFFF);
        assertTrue(visibleStars > 0,
                "the initial screen-space BG sample must reach ROM star patterns");
    }

    @Test
    void starRowsAdvanceWithRomTempArrayAtDezStart() {
        updateParallax(0);
        int[] firstFrame = GameServices.parallax().getHScrollForShader().clone();
        updateParallax(1);
        int[] secondFrame = GameServices.parallax().getHScrollForShader().clone();

        int changedRows = 0;
        for (int line = 0; line < firstFrame.length; line++) {
            if ((short) firstFrame[line] != (short) secondFrame[line]) {
                changedRows++;
            }
        }
        assertTrue(changedRows > 0,
                "DEZ star rows must move as TempArray_LayerDef advances each frame");
        assertNotEquals((short) firstFrame[0], (short) secondFrame[0],
                "the first visible DEZ star row must use its ROM-specific speed");
    }

    @Test
    void endingBgOverrideDoesNotReplaceGameplayCameraOrigin() {
        updateParallax(0);
        int gameplayBgY = GameServices.parallax().getVscrollFactorBG() & 0xFFFF;
        int endingBgY = 0x48;

        GameServices.parallax().updateForEnding(DEZ_ZONE, 0, 4, endingBgY);
        assertEquals((short) endingBgY, GameServices.parallax().getVscrollFactorBG(),
                "DEZ ending must consume its ROM-supplied temporary BG-Y value");

        updateParallax(5);
        assertEquals(gameplayBgY, GameServices.parallax().getVscrollFactorBG() & 0xFFFF,
                "ending BG-Y override must not replace the gameplay camera origin");
    }

    @Test
    void planeBResourcesSurviveRewindRecompute() {
        LevelManager levelManager = GameServices.level();
        updateParallax(17);
        int[] expectedScroll = GameServices.parallax().getHScrollForShader().clone();
        int expectedBgY = GameServices.parallax().getVscrollFactorBG();
        ParallaxSnapshot snapshot = GameServices.parallax().capture();

        ensureBackgroundTilemap(levelManager);
        LevelTilemapManager tilemaps = levelManager.getTilemapManager();
        byte[] expectedTilemap = tilemaps.getBackgroundTilemapData().clone();
        tilemaps.resetTilemapsForRewindRestore();

        updateParallax(29);
        GameServices.parallax().restore(snapshot);
        updateParallax(17);

        assertArrayEquals(expectedScroll, GameServices.parallax().getHScrollForShader(),
                "DEZ star scroll must be deterministic after rewind recomputation");
        assertEquals(expectedBgY, GameServices.parallax().getVscrollFactorBG(),
                "rewind must retain the ROM-seeded DEZ BG-Y origin");
        ensureBackgroundTilemap(levelManager);
        assertArrayEquals(expectedTilemap, levelManager.getTilemapManager().getBackgroundTilemapData(),
                "rewind invalidation must not discard ROM Plane B resources");
    }

    private void updateParallax(int frameCounter) {
        GameServices.parallax().update(DEZ_ZONE, 0, GameServices.camera(), frameCounter,
                sharedLevel.level());
    }

    private static void ensureBackgroundTilemap(LevelManager levelManager) {
        try {
            Method method = LevelManager.class.getDeclaredMethod("ensureBackgroundTilemapData");
            method.setAccessible(true);
            method.invoke(levelManager);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError("failed to invoke production Plane B build", e);
        } catch (InvocationTargetException e) {
            throw new AssertionError("production Plane B build failed", e.getCause());
        }
    }

    private static int countStarDescriptors(byte[] tilemap, int widthTiles, int heightTiles) {
        int count = 0;
        for (int y = 0; y < heightTiles; y++) {
            for (int x = 0; x < widthTiles; x++) {
                int offset = (y * widthTiles + x) * 4;
                int pattern = (tilemap[offset] & 0xFF) | ((tilemap[offset + 1] & 0x07) << 8);
                if (pattern >= STAR_PATTERN_FIRST && pattern < STAR_PATTERN_LIMIT) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int countVisibleStars(LevelManager levelManager, int[] packedScroll, int bgY) {
        int count = 0;
        for (int line = 0; line < 224; line++) {
            int bgScroll = (short) packedScroll[line];
            for (int x = 0; x < 320; x += 8) {
                int descriptor = levelManager.getBackgroundTileDescriptorAtWorld(
                        x - bgScroll, bgY + line);
                int pattern = descriptor & 0x7FF;
                if (pattern >= STAR_PATTERN_FIRST && pattern < STAR_PATTERN_LIMIT) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int[] nonTransparentPixelValues(Pattern pattern) {
        return java.util.stream.IntStream.range(0, Pattern.PATTERN_WIDTH * Pattern.PATTERN_HEIGHT)
                .map(index -> pattern.getPixel(index % Pattern.PATTERN_WIDTH,
                        index / Pattern.PATTERN_WIDTH) & 0x0F)
                .filter(value -> value != 0)
                .distinct()
                .toArray();
    }

    private static int nonBlackPaletteEntries(Palette palette, int[] pixelValues) {
        int count = 0;
        for (int value : pixelValues) {
            Palette.Color color = palette.getColor(value);
            if ((color.r | color.g | color.b) != 0) {
                count++;
            }
        }
        return count;
    }
}
