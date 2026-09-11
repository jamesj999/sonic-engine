package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

import static com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance.HCZBreakableBarState;

/**
 * Object 0x39 — HCZ Large Fan (Sonic 3 & Knuckles, Hydrocity Zone).
 *
 * <p>ROM reference: {@code Obj_HCZLargeFan} (sonic3k.asm:65583-65670).
 *
 * <p>The object stays dormant until Sonic enters a narrow trigger window below it.
 * On activation it plays the latch SFX, drops downward for 8 frames, then clears
 * the shared HCZ tunnel/bar block state and keeps cycling its 5-frame animation
 * while playing the big fan SFX every 16 frames.
 */
public class HCZLargeFanObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final int PRIORITY = 4; // ROM: priority = $200
    private static final int FAN_FRAME_COUNT = 5;
    private static final int DROP_FRAMES = 8;
    private static final int DROP_SPEED = 8;

    // Activation window from Obj_HCZLargeFan:
    //   player.x - $20 >= fan.x
    //   0 <= (player.y - $20 - fan.y) < $40
    private static final int PLAYER_X_TRIGGER_OFFSET = 0x20;
    private static final int PLAYER_Y_TRIGGER_OFFSET = 0x20;
    private static final int PLAYER_Y_TRIGGER_RANGE = 0x40;

    private static final int PHASE_WAITING = 0;
    private static final int PHASE_LOADING_ART = 1;
    private static final int PHASE_ACTIVE = 2;

    private int x;
    private int y;
    private int phase;
    private S3kKosModuleQueue artQueue;
    private HardwareWorkHandle artHandle;
    private long artOrdinal = -1;
    private int dropFramesRemaining;
    private int mappingFrame;
    private int animFrameTimer;

    public HCZLargeFanObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZLargeFan");
        this.x = spawn.x();
        this.y = spawn.y();
        this.phase = PHASE_WAITING;
    }

    @Override
    public HCZLargeFanObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZLargeFanObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        rebindArtAfterRestore();
        AbstractPlayableSprite player = (playerEntity instanceof AbstractPlayableSprite sprite)
                ? sprite : null;

        if (phase == PHASE_WAITING) {
            HCZBreakableBarState.setState(3);
            if (!shouldActivate(player)) {
                return;
            }
            phase = PHASE_LOADING_ART;
            queueFanArt();
            return;
        }

        if (phase == PHASE_LOADING_ART) {
            if (!artQueue.isReady(artHandle)) {
                return;
            }
            artQueue.claim(artHandle);
            artHandle = null;
            artQueue = null;
            artOrdinal = -1;
            phase = PHASE_ACTIVE;
            dropFramesRemaining = DROP_FRAMES;
            services().playSfx(Sonic3kSfx.FAN_LATCH.id);
        }

        if (dropFramesRemaining > 0) {
            y += DROP_SPEED;
            dropFramesRemaining--;
            if (dropFramesRemaining == 0) {
                HCZBreakableBarState.setState(0);
            }
        }

        // ROM: sfx_FanBig every 16 frames (sonic3k.asm:65632-65636)
        // ROM uses (Level_frame_counter+1) & $F, matching global frame counter
        if ((vIntRunCount & 0x0F) == 0) {
            // ROM: only reaches this code if Sprite_OnScreen_Test passes (object
            // is deleted when off-screen).  Guard with isOnScreen() so we don't
            // play the sound for a fan that has scrolled out of view.
            if (isOnScreen()) {
                services().playSfx(Sonic3kSfx.FAN_BIG.id);
            }
        }

        animFrameTimer--;
        if (animFrameTimer < 0) {
            animFrameTimer = 0;
            mappingFrame++;
            if (mappingFrame >= FAN_FRAME_COUNT) {
                mappingFrame = 0;
            }
        }

        // ROM: jmp (Sprite_OnScreen_Test).l — deletes sprite when off-screen.
        // Our object manager handles OOR culling, but the ROM uses a tighter
        // on-screen window.  Self-destroy when clearly off-screen to match ROM
        // lifetime and prevent lingering SFX.
        if (!isOnScreen(64)) {
            setDestroyed(true);
        }
    }

    private void queueFanArt() {
        try {
            artQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
            artHandle = artQueue.queue(
                    services().rom(),
                    Sonic3kConstants.ART_KOSM_HCZ_LARGE_FAN_ADDR,
                    Sonic3kConstants.ARTTILE_HCZ_LARGE_FAN);
            artOrdinal = artHandle.ordinal();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to queue HCZ large-fan KosM art", e);
        }
    }

    private void rebindArtAfterRestore() {
        if (artOrdinal < 0 || artQueue != null) {
            return;
        }
        artHandle = services().hardwareTiming().pendingHandle(
                        HardwareWorkKind.KOS_MODULE_QUEUE, artOrdinal)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing restored HCZ large-fan KosM job " + artOrdinal));
        artQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
    }

    private boolean shouldActivate(AbstractPlayableSprite player) {
        if (player == null) {
            return false;
        }

        int playerX = player.getCentreX() & 0xFFFF;
        if (playerX - PLAYER_X_TRIGGER_OFFSET < x) {
            return false;
        }

        int relY = player.getCentreY() - PLAYER_Y_TRIGGER_OFFSET - y;
        return relY >= 0 && relY < PLAYER_Y_TRIGGER_RANGE;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_LARGE_FAN);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }
}
