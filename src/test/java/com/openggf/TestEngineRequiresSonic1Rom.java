package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic1.dataselect.S1DataSelectImageCacheManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_1)
class TestEngineRequiresSonic1Rom {

    @Test
    void sonic1GameModuleWarmupStartsGenerationThroughRealManager() throws Exception {
        GraphicsManager originalGraphicsManager = replaceGraphicsManagerSingleton(mock(GraphicsManager.class));
        try {
            EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
            SonicConfigurationService.getInstance().setConfigValue(
                    SonicConfiguration.CROSS_GAME_S1_DATA_SELECT_IMAGE_GEN_OVERRIDE, true);
            Sonic1GameModule module = new Sonic1GameModule();

            try (MockedStatic<CrossGameFeatureProvider> donor = mockStatic(CrossGameFeatureProvider.class)) {
                donor.when(CrossGameFeatureProvider::isS3kDonorActive).thenReturn(true);

                GraphicsManager graphics = GraphicsManager.getInstance();
                CompletableFuture<Object> blocked = new CompletableFuture<>();
                when(graphics.submitRenderThreadTask(any())).thenReturn((CompletableFuture) blocked);

                S1DataSelectImageCacheManager manager = module.getGameService(S1DataSelectImageCacheManager.class);
                assertTrue(manager instanceof Sonic1GameModule.S1DataSelectImageWarmup);

                ((Sonic1GameModule.S1DataSelectImageWarmup) manager).ensureGenerationStarted();

                Field inFlightField = S1DataSelectImageCacheManager.class.getDeclaredField("inFlight");
                inFlightField.setAccessible(true);
                assertNotNull(inFlightField.get(manager));
                blocked.complete(new com.openggf.graphics.RgbaImage(320, 224, new int[320 * 224]));
                manager.awaitGenerationIfRunning();
            }
        } finally {
            replaceGraphicsManagerSingleton(originalGraphicsManager);
        }
    }

    private static GraphicsManager replaceGraphicsManagerSingleton(GraphicsManager replacement) throws Exception {
        Field instanceField = GraphicsManager.class.getDeclaredField("graphicsManager");
        instanceField.setAccessible(true);
        GraphicsManager original = (GraphicsManager) instanceField.get(null);
        instanceField.set(null, replacement);
        return original;
    }
}
