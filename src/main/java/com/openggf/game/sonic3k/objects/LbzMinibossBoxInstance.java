package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Standalone LBZ1 miniboss box for star-post restarts.
 *
 * <p>ROM: {@code Obj_LBZMinibossBox} at {@code sonic3k.asm:192357}. When the
 * player restarts from the lamppost after the building collapse, the Robotnik
 * intro never runs ({@code _unkFAAB} clear), so this layout object re-stages
 * the fight: it locks the camera, hosts the closed box pieces, fades to the
 * miniboss music, and spawns {@code Obj_LBZMiniboss} once the player walks in.
 */
public final class LbzMinibossBoxInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int CAMERA_MIN_X = 0x3C00;
    private static final int CAMERA_MAX_X = 0x3EA0;
    private static final int CAMERA_LOCKED_MIN_X = 0x3DA0;
    private static final int PLAYER_NEAR_X = 0x70;
    private static final int ACTIVATE_WAIT = 0x1F;
    private static final int MINIBOSS_MUSIC_FADE_FRAMES = 90;
    private static final int BOX_PALETTE_LINE = 2;

    private enum Phase { INIT, WAIT_PLAYER, ACTIVATE_WAIT, DONE }

    private Phase phase = Phase.INIT;
    private int activateTimer;
    private LbzMinibossBoxRig boxRig;

    public LbzMinibossBoxInstance(ObjectSpawn spawn) {
        super(spawn, "LBZMinibossBox");
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (boxRig != null) {
            var camera = services().camera();
            boxRig.update(camera != null ? camera.getX() & 0xFFFF : LbzMinibossBoxRig.NO_CAMERA);
        }
        switch (phase) {
            case INIT -> initialize();
            case WAIT_PLAYER -> updateWaitPlayer(playerEntity);
            case ACTIVATE_WAIT -> updateActivateWait();
            case DONE -> {
                if (boxRig == null || !boxRig.hasVisiblePieces()) {
                    ObjectLifetimeOps.deleteNoRespawn(this);
                }
            }
        }
    }

    private void initialize() {
        // ROM Obj_LBZMinibossBox: Knuckles and the Robotnik-intro path
        // (_unkFAAB set) both delete the standalone box immediately.
        if (isPlayerKnuckles() || isRobotnikIntroActive()) {
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }
        ensureArtLoaded();
        services().camera().setMaxX((short) CAMERA_MAX_X);
        services().camera().setMinX((short) CAMERA_MIN_X);
        boxRig = new LbzMinibossBoxRig(getX(), getY());
        // ROM: sub_8D116 fades to the miniboss music as soon as the box stages.
        spawnFreeChild(() -> new SongFadeTransitionInstance(
                MINIBOSS_MUSIC_FADE_FRAMES, Sonic3kMusic.MINIBOSS.id));
        phase = Phase.WAIT_PLAYER;
    }

    private void updateWaitPlayer(PlayableEntity playerEntity) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        int dx = Math.abs((short) ((player.getCentreX() & 0xFFFF) - (getX() & 0xFFFF)));
        if (dx >= PLAYER_NEAR_X) {
            return;
        }
        // ROM loc_8CDF2: lock the arena and arm the $1F activation wait.
        services().camera().setMinX((short) CAMERA_LOCKED_MIN_X);
        activateTimer = ACTIVATE_WAIT;
        phase = Phase.ACTIVATE_WAIT;
    }

    private void updateActivateWait() {
        activateTimer--;
        if (activateTimer >= 0) {
            return;
        }
        // ROM loc_8CD9C: release the pieces, play sfx_BossActivate, spawn the
        // miniboss, and let the screen event swap the boss-area chunk.
        if (boxRig != null) {
            boxRig.release();
        }
        services().playSfx(Sonic3kSfx.BOSS_ACTIVATE.id);
        if (services().levelEventProvider() instanceof Sonic3kLevelEventManager manager
                && manager.getLbzEvents() != null) {
            manager.getLbzEvents().applyMinibossBoxOpenedChunkSwap(false);
        }
        int x = getX();
        int y = getY();
        spawnChild(() -> new LbzMinibossInstance(new ObjectSpawn(
                x & 0xFFFF, y & 0xFFFF, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0)));
        phase = Phase.DONE;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || boxRig == null || !boxRig.hasVisiblePieces()) {
            return;
        }
        PatternSpriteRenderer boxRenderer = getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX);
        if (boxRenderer == null) {
            return;
        }
        boxRig.draw(boxRenderer, BOX_PALETTE_LINE);
    }

    private boolean isPlayerKnuckles() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    private boolean isRobotnikIntroActive() {
        LbzZoneRuntimeState lbz = services().zoneRuntimeRegistry() == null
                ? null
                : S3kRuntimeStates.currentLbz(services().zoneRuntimeRegistry()).orElse(null);
        return lbz != null && lbz.isRobotnikIntroActive();
    }

    private void ensureArtLoaded() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null && renderManager.getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.LBZ_MINIBOSS);
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX);
        }
    }
}
