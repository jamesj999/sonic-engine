package com.openggf.graphics;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.camera.Camera;
import com.openggf.game.GameStateManager;
import com.openggf.game.session.EngineContext;
import com.openggf.graphics.pipeline.UiRenderPipeline;
import com.openggf.level.objects.HudRenderManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class TestGraphicsManagerFadeRebinding {

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
    }

    @AfterEach
    public void tearDown() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GraphicsManager graphicsManager = EngineContext.fromLegacySingletonsForBootstrap().graphics();
        graphicsManager.resetState();
        setPrivateField(graphicsManager, "uiRenderPipeline", null);
    }

    @Test
    public void testExplicitRuntimeBindingReplacesBootstrapManagers() throws Exception {
        SessionManager.clear();
        EngineContext engineContext = EngineContext.fromLegacySingletonsForBootstrap();
        GraphicsManager graphicsManager = engineContext.graphics();
        graphicsManager.resetState();

        FadeManager bootstrapFade = graphicsManager.getFadeManager();
        Camera bootstrapCamera = (Camera) getPrivateField(graphicsManager, "camera");
        UiRenderPipeline pipeline = new UiRenderPipeline(graphicsManager);
        pipeline.setFadeManager(bootstrapFade);
        setPrivateField(graphicsManager, "uiRenderPipeline", pipeline);

        FadeManager runtimeFade = new FadeManager();
        Camera runtimeCamera = new Camera(engineContext.configuration());

        graphicsManager.bindRuntimeManagedReferences(runtimeCamera, runtimeFade);

        assertSame(runtimeFade, graphicsManager.getFadeManager(), "GraphicsManager should switch to the explicitly bound runtime FadeManager");
        assertSame(runtimeFade, pipeline.getFadeManager(), "UiRenderPipeline should also use the runtime FadeManager");
        assertSame(runtimeCamera, getPrivateField(graphicsManager, "camera"), "GraphicsManager should switch to the runtime Camera");
        assertSame(bootstrapFade, getPrivateField(graphicsManager, "bootstrapFadeManager"), "Bootstrap FadeManager should be retained");
        assertSame(bootstrapCamera, getPrivateField(graphicsManager, "bootstrapCamera"), "Bootstrap Camera should be retained");
    }

    @Test
    public void testClearRuntimeBindingRestoresBootstrapManagers() throws Exception {
        SessionManager.clear();
        EngineContext engineContext = EngineContext.fromLegacySingletonsForBootstrap();
        GraphicsManager graphicsManager = engineContext.graphics();
        graphicsManager.resetState();

        FadeManager bootstrapFade = graphicsManager.getFadeManager();
        Camera bootstrapCamera = (Camera) getPrivateField(graphicsManager, "camera");
        UiRenderPipeline pipeline = new UiRenderPipeline(graphicsManager);
        pipeline.setFadeManager(bootstrapFade);
        setPrivateField(graphicsManager, "uiRenderPipeline", pipeline);

        graphicsManager.bindRuntimeManagedReferences(new Camera(engineContext.configuration()), new FadeManager());

        graphicsManager.clearRuntimeManagedReferences();

        assertSame(bootstrapFade, graphicsManager.getFadeManager(), "GraphicsManager should return to the bootstrap FadeManager");
        assertSame(bootstrapFade, graphicsManager.getUiRenderPipeline().getFadeManager(),
                "UiRenderPipeline should return to the bootstrap FadeManager");
        assertSame(bootstrapCamera, getPrivateField(graphicsManager, "camera"), "GraphicsManager should return to the bootstrap Camera");
    }

    @Test
    public void testClearRuntimeBindingClearsHudRenderManager() throws Exception {
        SessionManager.clear();
        EngineContext engineContext = EngineContext.fromLegacySingletonsForBootstrap();
        GraphicsManager graphicsManager = engineContext.graphics();
        graphicsManager.resetState();

        UiRenderPipeline pipeline = new UiRenderPipeline(graphicsManager);
        HudRenderManager hud = new HudRenderManager(graphicsManager,
                new Camera(engineContext.configuration()), new GameStateManager());
        pipeline.setHudRenderManager(hud);
        setPrivateField(graphicsManager, "uiRenderPipeline", pipeline);

        graphicsManager.clearRuntimeManagedReferences();

        assertNull(pipeline.getHudRenderManager(),
                "UiRenderPipeline must not retain HUD managers from a destroyed gameplay session");
    }

    @Test
    public void testGetFadeManagerProvidesBootstrapDependenciesBeforeRuntime() throws Exception {
        SessionManager.clear();
        GraphicsManager graphicsManager = EngineContext.fromLegacySingletonsForBootstrap().graphics();
        graphicsManager.resetState();

        FadeManager resolvedFade = graphicsManager.getFadeManager();
        Camera resolvedCamera = (Camera) getPrivateField(graphicsManager, "camera");

        assertSame(resolvedFade, graphicsManager.getFadeManager(), "Pre-game rendering should use the bootstrap FadeManager");
        assertSame(resolvedCamera, getPrivateField(graphicsManager, "camera"), "Pre-game rendering should use the bootstrap Camera");
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getPrivateField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
