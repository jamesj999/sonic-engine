package com.openggf.game.recording;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelState;
import com.openggf.level.LevelManager;
import com.openggf.physics.Direction;
import com.openggf.sprites.playable.AbstractPlayableSprite;

public final class DesyncLiteSnapshotter {
    private DesyncLiteSnapshotter() {
    }

    public static DesyncLiteFrame capture(int movieFrame) {
        AbstractPlayableSprite player = RecordingMainPlayerResolver.resolve(
                GameServices.configuration(),
                GameServices.sprites());
        Camera camera = GameServices.camera();
        LevelManager level = GameServices.level();
        LevelState levelState = level.getLevelGamestate();
        GameStateManager gameState = GameServices.gameState();

        long timerFrameCount = levelState != null ? levelState.getTimerFrames() : 0L;
        int timerFrames = (int) (timerFrameCount % 60);
        int timerSeconds = (int) ((timerFrameCount / 60) % 60);
        int timerMinutes = (int) (timerFrameCount / 3600);
        int rings = levelState != null ? levelState.getRings() : 0;

        return new DesyncLiteFrame(
                movieFrame,
                player.getCentreX(),
                player.getCentreY(),
                player.getXSpeed(),
                player.getYSpeed(),
                player.getGSpeed(),
                playerStatus(player),
                player.getAnimationId(),
                camera.getX(),
                camera.getY(),
                timerFrames,
                timerSeconds,
                timerMinutes,
                rings,
                gameState.getScore());
    }

    private static int playerStatus(AbstractPlayableSprite player) {
        int status = 0;
        if (player.getDirection() == Direction.LEFT) {
            status |= AbstractPlayableSprite.STATUS_FACING_LEFT & 0xFF;
        }
        if (player.getAir()) {
            status |= AbstractPlayableSprite.STATUS_IN_AIR & 0xFF;
        }
        if (player.getRolling()) {
            status |= AbstractPlayableSprite.STATUS_ROLLING & 0xFF;
        }
        if (player.isOnObject()) {
            status |= AbstractPlayableSprite.STATUS_ON_OBJECT & 0xFF;
        }
        if (player.getRollingJump()) {
            status |= AbstractPlayableSprite.STATUS_ROLLING_JUMP & 0xFF;
        }
        if (player.getPushing()) {
            status |= AbstractPlayableSprite.STATUS_PUSHING & 0xFF;
        }
        if (player.isInWater()) {
            status |= AbstractPlayableSprite.STATUS_UNDERWATER & 0xFF;
        }
        if (player.isPreventTailsRespawn()) {
            status |= AbstractPlayableSprite.STATUS_PREVENT_TAILS_RESPAWN & 0xFF;
        }
        return status;
    }
}
