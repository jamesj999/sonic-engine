package com.openggf.level;

import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.LevelLoadContext;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.WaterDataProvider;
import com.openggf.data.Rom;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;

import java.io.IOException;

/**
 * Owns level water lifecycle and playable underwater state updates.
 */
final class LevelWaterCoordinator {
    private final LevelManager levelManager;

    LevelWaterCoordinator(LevelManager levelManager) {
        this.levelManager = levelManager;
    }

    void initialize() throws IOException {
        initialize(false);
    }

    /**
     * ROM: CheckLevelForWater compares Apparent_zone_and_act to
     * Current_zone_and_act. During seamless transitions Apparent != Current,
     * which enables water in cases that a direct load would disable.
     */
    void initialize(boolean seamlessTransition) throws IOException {
        Rom rom = GameServices.rom().getRom();
        GameModule gameModule = levelManager.gameModule;
        WaterDataProvider waterProvider = gameModule != null ? gameModule.getWaterDataProvider() : null;
        int featureZone = levelManager.getFeatureZoneId();
        int featureAct = levelManager.getFeatureActId();
        if (waterProvider != null) {
            PlayerCharacter character = PlayerCharacter.SONIC_AND_TAILS;
            LevelEventProvider lep = gameModule.getLevelEventProvider();
            if (lep instanceof AbstractLevelEventManager alem) {
                character = alem.getPlayerCharacter();
            }
            levelManager.waterSystem.loadForLevelFromProvider(waterProvider, rom,
                    featureZone, featureAct, character, seamlessTransition);
        } else if (levelManager.zoneFeatureProvider != null
                && levelManager.zoneFeatureProvider.hasWater(featureZone)) {
            @SuppressWarnings("deprecation")
            Runnable fallback = () -> levelManager.waterSystem.loadForLevel(
                    rom, featureZone, featureAct, levelManager.level.getObjects());
            if (!levelManager.waterSystem.hasWater(featureZone, featureAct)) {
                fallback.run();
            }
        }
    }

    void advanceDynamicWaterLevel() {
        int featureZone = levelManager.getFeatureZoneId();
        int featureAct = levelManager.getFeatureActId();
        if (levelManager.level != null
                && levelManager.waterSystem != null
                && levelManager.waterSystem.hasWater(featureZone, featureAct)) {
            levelManager.waterSystem.updateDynamic(
                    featureZone, featureAct, levelManager.camera.getX(), levelManager.camera.getY());
            levelManager.waterSystem.update();
        }
    }

    void advanceDynamicWaterLevelAfterPlayerPhysicsIfNeeded() {
        if (!levelManager.advanceWaterLevelBeforePlayerPhysics()) {
            advanceDynamicWaterLevel();
        }
    }

    void updatePlayableWaterStatesForCurrentLevel() {
        int featureZone = levelManager.getFeatureZoneId();
        int featureAct = levelManager.getFeatureActId();
        if (levelManager.level == null
                || levelManager.waterSystem == null
                || !levelManager.waterSystem.hasWater(featureZone, featureAct)) {
            return;
        }
        Sprite player = levelManager.spriteManager.getSprite(levelManager.resolveMainCharacterCode());
        AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;
        if (playable == null) {
            return;
        }
        int waterY = levelManager.waterSystem.getGameplayWaterLevelY(featureZone, featureAct);
        updatePlayableWaterStates(playable, waterY);
    }

    void updatePlayableWaterStateForCurrentLevel(AbstractPlayableSprite playable) {
        int featureZone = levelManager.getFeatureZoneId();
        int featureAct = levelManager.getFeatureActId();
        if (levelManager.level == null
                || levelManager.waterSystem == null
                || !levelManager.waterSystem.hasWater(featureZone, featureAct)
                || playable == null) {
            return;
        }
        int waterY = levelManager.waterSystem.getGameplayWaterLevelY(featureZone, featureAct);
        updatePlayableWaterState(playable, waterY);
    }

    boolean shouldSuppressUnderwaterPalette(int zoneId, int actId) {
        return levelManager.zoneFeatureProvider != null
                && levelManager.zoneFeatureProvider.shouldSuppressUnderwaterPalette(zoneId, actId);
    }

    void restoreWaterFromCheckpoint(LevelLoadContext ctx) {
        if (!ctx.hasWaterState()) {
            return;
        }
        int featureZone = levelManager.getFeatureZoneId();
        int featureAct = levelManager.getFeatureActId();
        if (levelManager.waterSystem.hasWater(featureZone, featureAct)) {
            levelManager.waterSystem.setWaterLevelDirect(featureZone, featureAct, ctx.getCheckpointWaterLevel());
            levelManager.waterSystem.setWaterLevelTarget(featureZone, featureAct, ctx.getCheckpointWaterLevel());
        }
        if (levelManager.zoneFeatureProvider != null) {
            levelManager.zoneFeatureProvider.setWaterRoutine(ctx.getCheckpointWaterRoutine());
        }
    }

    private void updatePlayableWaterStates(AbstractPlayableSprite mainPlayable, int waterY) {
        updatePlayableWaterState(mainPlayable, waterY);
        for (AbstractPlayableSprite sidekick : levelManager.spriteManager.getSidekicks()) {
            if (sidekick != mainPlayable) {
                updatePlayableWaterState(sidekick, waterY);
            }
        }
    }

    private void updatePlayableWaterState(AbstractPlayableSprite playable, int waterY) {
        if (playable == null) {
            return;
        }
        if (playable.isHurt() || isDeferredSidekickDeadFallWaterBypass(playable)) {
            return;
        }
        // Only S3K's water routine skips the velocity change under object_control
        // (sonic3k.asm:22235, :27448). S1's Sonic_Water ("01 Sonic.asm":270-272) and
        // S2's Obj01_InWater (s2.asm:36393-36395) shift x_vel/y_vel unconditionally,
        // and Obj01_Control reaches bsr.w Sonic_Water outside the
        // btst #0,obj_control skip (s2.asm:36236-36251) -- so a character carried
        // into the water by the CPZ spin tube is still quartered.
        if (playable.isObjectControlSuppressesMovement()
                && playable.waterVelocityChangeGatedByObjectControl()) {
            playable.updateWaterStateObjectControlled(waterY);
            return;
        }
        playable.updateWaterState(waterY);
    }

    private static boolean isDeferredSidekickDeadFallWaterBypass(AbstractPlayableSprite playable) {
        if (!playable.isCpuControlled()) {
            return false;
        }
        SidekickCpuController cpu = playable.getCpuController();
        return cpu != null && cpu.isDeferredDespawnDeadFallContinuingThisFrame();
    }
}
