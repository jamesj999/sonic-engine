package com.openggf;

import com.openggf.game.BonusStageProvider;
import com.openggf.game.GameModule;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.NoOpBonusStageProvider;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.rules.GameRules;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.timing.HardwareTimingBoundaryObserver;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;

import java.util.Objects;

public record LevelFrameContext(GameModule gameModule,
                                GameRules gameRules,
                                LevelEventProvider levelEventProvider,
                                BonusStageProvider bonusStageProvider,
                                SpriteManager spriteManager,
                                GameStateManager gameStateManager,
                                TimerManager timerManager,
                                PaletteOwnershipRegistry paletteOwnershipRegistry,
                                HardwareTimingService hardwareTiming,
                                HardwareTimingBoundaryObserver hardwareTimingBoundaryObserver,
                                RuntimeArtCoordinator runtimeArtCoordinator) {

    public LevelFrameContext {
        Objects.requireNonNull(gameModule, "gameModule");
        bonusStageProvider = bonusStageProvider != null
                ? bonusStageProvider
                : NoOpBonusStageProvider.INSTANCE;
        Objects.requireNonNull(hardwareTiming, "hardwareTiming");
        hardwareTimingBoundaryObserver = hardwareTimingBoundaryObserver != null
                ? hardwareTimingBoundaryObserver
                : HardwareTimingBoundaryObserver.NO_OP;
        runtimeArtCoordinator = runtimeArtCoordinator != null
                ? runtimeArtCoordinator
                : RuntimeArtCoordinator.NONE;
    }

    public static LevelFrameContext from(GameplayModeContext context) {
        Objects.requireNonNull(context, "context");
        GameModule module = context.getWorldSession().getGameModule();
        return new LevelFrameContext(
                module,
                module.getRules(),
                module.getLevelEventProvider(),
                context.getActiveBonusStageProvider(),
                context.getSpriteManager(),
                context.getGameStateManager(),
                context.getTimerManager(),
                context.getPaletteOwnershipRegistry(),
                context.hardwareTiming(),
                context.hardwareTimingBoundaryObserver(),
                context.runtimeArtCoordinator());
    }
}
