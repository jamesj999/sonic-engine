package com.openggf.game.sonic2;

import com.openggf.game.session.EngineContext;
import com.openggf.game.DebugModeProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.sonic2.debug.Sonic2DebugModeProvider;
import com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic2SpecialStageModuleGraph {

    @Test
    public void moduleOwnedSpecialStageGraphUsesSharedManagerAndDebugInstances() {
        Sonic2GameModule module = assertDoesNotThrow(Sonic2GameModule::new,
                "Module graph construction should not require configured EngineContext");

        SpecialStageProvider specialStageProvider = module.getSpecialStageProvider();
        DebugModeProvider debugModeProvider = module.getDebugModeProvider();
        Sonic2SpecialStageManager serviceManager = module.getGameService(Sonic2SpecialStageManager.class);
        Sonic2SpecialStageSpriteDebug serviceDebug =
                module.getGameService(Sonic2SpecialStageSpriteDebug.class);

        assertTrue(specialStageProvider instanceof Sonic2SpecialStageProvider);
        assertTrue(debugModeProvider instanceof Sonic2DebugModeProvider);

        Sonic2SpecialStageProvider provider = (Sonic2SpecialStageProvider) specialStageProvider;
        Sonic2DebugModeProvider debugProvider = (Sonic2DebugModeProvider) debugModeProvider;
        Sonic2DebugModeProvider.Sonic2SpecialStageDebugController controller =
                (Sonic2DebugModeProvider.Sonic2SpecialStageDebugController)
                        debugProvider.getSpecialStageDebugController();

        assertSame(serviceManager, provider.getManager());
        assertSame(serviceManager, controller.getManager());
        assertSame(serviceDebug, controller.getSpriteDebug());
    }

    @Test
    public void moduleDoesNotAdvertiseNativeLevelDebugPlacement() {
        Sonic2GameModule module = new Sonic2GameModule();

        assertFalse(module.getDebugModeProvider().hasLevelDebug(),
                "S2 exposes special-stage debug only; native Debug_placement_mode ring/item placement "
                        + "has no engine capability or input route.");
    }
}
