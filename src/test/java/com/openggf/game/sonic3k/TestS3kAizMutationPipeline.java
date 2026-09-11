package com.openggf.game.sonic3k;

import com.openggf.game.GameServices;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kAizMutationPipeline {

    private static final Path SEAMLESS_EXECUTOR = Path.of(
            "src/main/java/com/openggf/game/sonic3k/events/S3kSeamlessMutationExecutor.java");
    private static final Path INTRO_TERRAIN_SWAP = Path.of(
            "src/main/java/com/openggf/game/sonic3k/objects/AizIntroTerrainSwap.java");

    @Test
    void seamlessMutationExecutorShouldRouteAizImmediateMutationsThroughPipeline() throws IOException {
        String content = Files.readString(SEAMLESS_EXECUTOR);

        assertContains(content, "GameServices.zoneLayoutMutationPipeline().applyImmediately(");
        assertContains(content, "new LayoutMutationContext(");
        assertContains(content, "levelManager::applyMutationEffects");

        assertContains(content, "Sonic3kZoneEvents.loadPaletteFromPalPointers(PAL_POINTER_AIZ_FIRE_INDEX);");
        assertContains(content, "Sonic3kAIZEvents.applyFireTransitionPaletteLine4(levelManager);");
        assertContains(content, "Sonic3kZoneEvents.applyPlc(PLC_SPIKES_SPRINGS);");
        assertContains(content, "Sonic3kPlcLoader.refreshAffectedRenderers(overlayRanges, levelManager);");
    }

    @Test
    void aizIntroTerrainSwapShouldRouteImmediateApplyThroughPipelineAndKeepTilemapFallback() throws IOException {
        String content = Files.readString(INTRO_TERRAIN_SWAP);

        assertDoesNotContain(content, "GameServices.zoneLayoutMutationPipeline().applyImmediately(");
        assertContains(content, "services.zoneLayoutMutationPipeline().applyImmediately(");
        assertContains(content, "new LayoutMutationContext(");
        assertContains(content, "levelManager::applyMutationEffects");

        assertContains(content, "applyZoneArtOverlays(sonic3kLevel, services)");
        assertContains(content, "Sonic3kPlcLoader.refreshAffectedRenderers(modifiedRanges, levelManager);");
        assertContains(content, "if (!levelManager.swapToPrebuiltTilemaps()) {");
        assertContains(content, "levelManager.invalidateAllTilemaps();");
    }

    @Test
    void aizIntroDirectAndModulePayloadsHaveSeparatePublicationFences()
            throws IOException {
        String content = Files.readString(INTRO_TERRAIN_SWAP);

        assertContains(content, "applyMainLevelBlockOverlay(");
        assertContains(content, "preparedMainLevelBlocks16x16");
        assertContains(content, "sonic3kLevel.applyChunkOverlay(");
        assertContains(content, "levelManager.swapToPrebuiltTilemaps()");

        assertContains(content, "applyMainLevelPatternOverlay(");
        assertContains(content, "preparedMainLevelTiles8x8");
        assertContains(content, "sonic3kLevel.applyPatternOverlay(");
        assertContains(content,
                "Sonic3kPlcLoader.refreshAffectedRenderers(modifiedRanges, levelManager);");
    }

    @Test
    void aizIntroTerrainSwapCacheShouldResetAcrossGameBootstrap() throws IOException {
        String terrainSwap = Files.readString(INTRO_TERRAIN_SWAP);
        String module = Files.readString(Path.of("src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java"));

        assertContains(terrainSwap, "public static synchronized void reset()");
        assertContains(terrainSwap, "cachedOverlayData = null;");
        assertContains(module, "public void resetModuleScopedState()");
        assertContains(module, "AizIntroTerrainSwap.reset();");
    }

    @Test
    void zoneLayoutMutationPipelineExposesQueueEmptinessForLifecycleAssertions() {
        TestEnvironment.resetAll();

        assertTrue(GameServices.zoneLayoutMutationPipeline().isEmpty());
        GameServices.zoneLayoutMutationPipeline().queue(context -> MutationEffects.redrawAllTilemaps());
        assertFalse(GameServices.zoneLayoutMutationPipeline().isEmpty());

        GameServices.zoneLayoutMutationPipeline().clear();
        assertTrue(GameServices.zoneLayoutMutationPipeline().isEmpty());
    }

    private static void assertContains(String content, String expected) {
        assertTrue(content.contains(expected), () -> "Expected source to contain: " + expected);
    }

    private static void assertDoesNotContain(String content, String unexpected) {
        assertFalse(content.contains(unexpected), () -> "Expected source not to contain: " + unexpected);
    }
}
