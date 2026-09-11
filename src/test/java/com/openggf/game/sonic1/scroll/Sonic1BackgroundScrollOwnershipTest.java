package com.openggf.game.sonic1.scroll;

import com.openggf.level.scroll.ZoneScrollHandler;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.level.ParallaxManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Characterizes the runtime-owned S1 parallax surface. */
class Sonic1BackgroundScrollOwnershipTest {

    @BeforeEach
    void setUpGameplayServices() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void clearGameplayServices() {
        SessionManager.clear();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("zoneHandlers")
    void restoredCameraAndFrameRecomputeExactScroll(String zone, Supplier<ZoneScrollHandler> factory) {
        int cameraX = 0x280;
        int cameraY = 0x1C8;
        int restoredFrame = 12;
        int[] expected = new int[224];
        ZoneScrollHandler handler = factory.get();
        handler.update(expected, cameraX, cameraY, restoredFrame, 0);
        short expectedVScroll = handler.getVscrollFactorBG();

        Object rewindState = handler.captureRewindState();
        // Advance the live handler, then restore the captured production state.
        handler.update(new int[224], cameraX + 173, cameraY + 47, 37, 0);
        int[] recomputed = new int[224];
        handler.restoreRewindState(rewindState);
        handler.update(recomputed, cameraX, cameraY, restoredFrame, 0);

        assertArrayEquals(expected, recomputed, zone + " HScroll must be reproducible after rewind");
        assertEquals(expectedVScroll, handler.getVscrollFactorBG(),
                zone + " VScroll must be reproducible after rewind");
    }

    @org.junit.jupiter.api.Test
    void deprecatedIgnoredYOverloadsMatchCanonicalUpdate() {
        TestEnvironment.configureGameModuleFixture(new Sonic1GameModule());
        Camera camera = GameServices.camera();
        camera.setX((short) 0x280);
        camera.setY((short) 0x1C8);

        ParallaxManager canonical = new ParallaxManager();
        ParallaxManager compatibility = new ParallaxManager();
        canonical.load(null);
        compatibility.load(null);
        canonical.update(0, 0, camera, 12);
        compatibility.update(0, 0, camera, 12, 0x7FFF);

        assertArrayEquals(canonical.getHScroll(), compatibility.getHScroll());
        assertArrayEquals(canonical.getVScrollPerLineBGForShader(), compatibility.getVScrollPerLineBGForShader());
        assertArrayEquals(canonical.getVScrollPerColumnBGForShader(), compatibility.getVScrollPerColumnBGForShader());
        assertEquals(canonical.getVscrollFactorBG(), compatibility.getVscrollFactorBG());
        assertEquals(canonical.getVscrollFactorFG(), compatibility.getVscrollFactorFG());
        ParallaxManager compatibilityWithLevel = new ParallaxManager();
        compatibilityWithLevel.load(null);
        compatibilityWithLevel.update(0, 0, camera, 12, -0x4000, null);
        assertArrayEquals(canonical.getHScroll(), compatibilityWithLevel.getHScroll());
        assertEquals(canonical.getVscrollFactorBG(), compatibilityWithLevel.getVscrollFactorBG());
        assertEquals(canonical.getVscrollFactorFG(), compatibilityWithLevel.getVscrollFactorFG());
    }

    private static Stream<Arguments> zoneHandlers() {
        return Stream.of(
                Arguments.of("GHZ", (Supplier<ZoneScrollHandler>) SwScrlGhz::new),
                Arguments.of("LZ", (Supplier<ZoneScrollHandler>) SwScrlLz::new),
                Arguments.of("MZ", (Supplier<ZoneScrollHandler>) SwScrlMz::new),
                Arguments.of("SLZ", (Supplier<ZoneScrollHandler>) SwScrlSlz::new),
                Arguments.of("SYZ", (Supplier<ZoneScrollHandler>) SwScrlSyz::new),
                Arguments.of("SBZ", (Supplier<ZoneScrollHandler>) SwScrlSbz::new),
                Arguments.of("FZ", (Supplier<ZoneScrollHandler>) SwScrlFz::new),
                Arguments.of("Ending", (Supplier<ZoneScrollHandler>) SwScrlEnd::new));
    }
}
